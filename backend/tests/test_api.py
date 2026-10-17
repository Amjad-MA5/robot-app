"""Tests für das Backend im Demo-Modus (python -m pytest im backend-Ordner)."""
from __future__ import annotations

import os
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

os.environ["SR_MODE"] = "mock"
os.environ["SR_API_KEY"] = "test-key"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import clock, directory, scheduling  # noqa: E402
from app.config import settings  # noqa: E402
from app.main import app  # noqa: E402
from app.models import BusyBlock  # noqa: E402

BERLIN = ZoneInfo("Europe/Berlin")
FIXED_NOW = datetime(2026, 10, 14, 10, 2, tzinfo=BERLIN)  # ein Mittwoch
HEADERS = {"X-Api-Key": "test-key"}


@pytest.fixture(autouse=True)
def frozen_time():
    clock.freeze(FIXED_NOW)
    yield
    clock.freeze(None)


@pytest.fixture()
def client():
    return TestClient(app)


def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["mode"] == "mock"


def test_api_requires_key(client):
    assert client.get("/api/employees").status_code == 401
    assert client.get("/api/employees", headers=HEADERS).status_code == 200


@pytest.mark.parametrize(
    "query, expected_id",
    [
        ("Frau Schmitt", "anna.schmidt"),
        ("anna schmid", "anna.schmidt"),
        ("Herrn Lukas Bekker", "lukas.becker"),
        ("Sofie Wagner", "sophie.wagner"),
        ("Mehmet Yilmaz", "mehmet.yilmaz"),
        ("Thomas Mueller", "thomas.mueller"),
        ("Herr Müller", "thomas.mueller"),
        ("Julia Hofman", "julia.hoffmann"),
    ],
)
def test_fuzzy_employee_search(client, query, expected_id):
    r = client.get("/api/employees", params={"q": query}, headers=HEADERS)
    assert r.status_code == 200
    hits = r.json()
    assert hits, f"kein Treffer für {query!r}"
    assert hits[0]["id"] == expected_id


def test_unknown_name_gives_no_confident_hit(client):
    hits = client.get("/api/employees", params={"q": "Bundeskanzler"}, headers=HEADERS).json()
    assert not hits or hits[0]["score"] < 80


def test_koelner_phonetik():
    assert directory.koelner_phonetik("Müller-Lüdenscheidt") == "65752682"
    assert directory.koelner_phonetik("Meyer") == directory.koelner_phonetik("Maier") == "67"
    assert directory.koelner_phonetik("Schmidt") == directory.koelner_phonetik("Schmitt")


def test_scenario1_arrival_notifies_employee(client):
    r = client.post(
        "/api/visits/arrival",
        json={"visitor_name": "Max Mustermann", "employee_id": "anna.schmidt"},
        headers=HEADERS,
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["notification"]["sent"] is True
    assert body["appointment"]["subject"] == "Visitor Appointment at Reception"
    inbox = client.get("/api/mock/inbox", headers=HEADERS).json()
    assert inbox[0]["to"] == "Anna Schmidt"
    assert "Max Mustermann" in inbox[0]["text"]


def test_visitor_name_is_html_escaped(client):
    client.post(
        "/api/visits/arrival",
        json={"visitor_name": "<script>x</script>", "employee_id": "anna.schmidt"},
        headers=HEADERS,
    )
    inbox = client.get("/api/mock/inbox", headers=HEADERS).json()
    assert "<script>" not in inbox[0]["html"]


def test_scenario2_walk_in_free_today(client):
    r = client.post(
        "/api/visits/walk-in",
        json={"visitor_name": "Erika Musterfrau", "employee_id": "lukas.becker"},
        headers=HEADERS,
    )
    body = r.json()
    assert r.status_code == 200, r.text
    assert body["available_today"] is True
    assert body["free_now"] is True
    assert body["notified"] is True


def test_scenario3_walk_in_busy_today_offers_slots(client):
    r = client.post(
        "/api/visits/walk-in",
        json={"visitor_name": "Erika Musterfrau", "employee_id": "sophie.wagner"},
        headers=HEADERS,
    )
    body = r.json()
    assert body["available_today"] is False
    assert body["notified"] is False
    assert 1 <= len(body["next_slots"]) <= settings.max_slots
    first = datetime.fromisoformat(body["next_slots"][0]["start"])
    assert first.date() > FIXED_NOW.date()


def test_scenario3_book_slot_and_conflict(client):
    slots = client.get("/api/employees/sophie.wagner/slots", headers=HEADERS).json()
    assert slots
    payload = {
        "visitor_name": "Erika Musterfrau",
        "employee_id": "sophie.wagner",
        "start": slots[0]["start"],
        "end": slots[0]["end"],
        "purpose": "Bewerbung",
    }
    r = client.post("/api/appointments", json=payload, headers=HEADERS)
    assert r.status_code == 200, r.text
    assert r.json()["created"] is True
    again = client.post("/api/appointments", json=payload, headers=HEADERS)
    assert again.status_code == 409
    new_slots = client.get("/api/employees/sophie.wagner/slots", headers=HEADERS).json()
    assert new_slots[0]["start"] != slots[0]["start"]


def test_unknown_employee_404(client):
    r = client.post("/api/visits/arrival", json={"visitor_name": "X", "employee_id": "nope"}, headers=HEADERS)
    assert r.status_code == 404


def test_free_slots_respect_busy_and_hours():
    cfg = settings
    day = FIXED_NOW.replace(hour=0, minute=0)
    busy = [BusyBlock(start=day.replace(hour=10), end=day.replace(hour=12))]
    slots = scheduling.find_free_slots(busy, FIXED_NOW, cfg, days=1, max_slots=5, max_per_day=5)
    for s in slots:
        assert not (s.start < day.replace(hour=12) and s.end > day.replace(hour=10))
        assert s.start >= FIXED_NOW + timedelta(minutes=cfg.lead_minutes)
        assert s.end.time() <= cfg.work_end
    assert slots and slots[0].start == day.replace(hour=12)


def test_is_free_now():
    busy = [BusyBlock(start=FIXED_NOW - timedelta(minutes=5), end=FIXED_NOW + timedelta(minutes=30))]
    assert scheduling.is_free_now(busy, FIXED_NOW, settings) is False
    assert scheduling.is_free_now([], FIXED_NOW, settings) is True

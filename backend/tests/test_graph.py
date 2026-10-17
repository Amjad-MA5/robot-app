"""Prüft die MS-Graph-Anbindung gegen einen simulierten Graph-Server (kein Netz nötig)."""
from __future__ import annotations

import json
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest

from app.config import settings
from app.models import Employee
from app.providers import graph as graph_provider
from app.providers.graph import GraphProvider

ME_ID = "me-0001"
EMP = Employee(id="emp-42", name="Anna Schmidt", email="anna.schmidt@contoso.com")


class FakeGraph:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict | None]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content) if request.content else None
        path = request.url.path.replace("/v1.0", "")
        self.calls.append((request.method, path, body))
        assert request.headers["Authorization"] == "Bearer test-token"
        if path == "/me":
            return httpx.Response(200, json={"id": ME_ID, "userPrincipalName": "empfang@contoso.com"})
        if path == "/users":
            query = request.url.params.get("$filter")
            if query is not None:
                if EMP.email.casefold() in query.casefold():
                    return httpx.Response(200, json={"value": [
                        {
                            "id": "aad-emp-42",
                            "displayName": EMP.name,
                            "mail": EMP.email,
                            "userPrincipalName": EMP.email,
                        }
                    ]})
                return httpx.Response(200, json={"value": []})
            return httpx.Response(200, json={"value": [
                {"id": ME_ID, "displayName": "Empfang", "mail": "empfang@contoso.com"},
                {"id": EMP.id, "displayName": EMP.name, "mail": EMP.email},
                {"id": "room-1", "displayName": "Raum 1", "mail": None},
            ]})
        if path == "/me/calendar/getSchedule":
            return httpx.Response(200, json={"value": [{
                "scheduleId": EMP.email,
                "scheduleItems": [
                    {"status": "busy", "start": {"dateTime": "2026-10-14T08:00:00.0000000", "timeZone": "UTC"},
                     "end": {"dateTime": "2026-10-14T09:00:00.0000000", "timeZone": "UTC"}, "subject": "Kundentermin"},
                ],
            }]})
        if path == "/me/events":
            return httpx.Response(201, json={"id": "event-9"})
        return httpx.Response(404, json={"error": {"code": "NotFound", "message": path}})


@pytest.fixture()
def graph(tmp_path: Path):
    cfg = replace(
        settings,
        mode="graph",
        graph_tenant_id="tenant",
        graph_client_id="client",
        graph_auth="delegated",
        token_cache_file=tmp_path / "cache.json",
    )
    fake = FakeGraph()
    provider = GraphProvider.__new__(GraphProvider)
    # Initialisierung ohne MSAL-Netzaufruf nachbauen
    provider.cfg = cfg
    provider.delegated = True
    provider._http = httpx.Client(transport=httpx.MockTransport(fake.handler))
    provider._employees = []
    provider._employees_loaded_at = 0.0
    provider._me = None
    provider._device_flow = None
    provider._device_flow_result = None
    provider._token = lambda: "test-token"  # type: ignore[method-assign]
    return provider, fake


def test_directory_excludes_self_and_mailless(graph):
    provider, _ = graph
    employees = provider.list_employees()
    assert [e.id for e in employees] == [EMP.id]


def test_get_schedule_parses_utc(graph):
    provider, fake = graph
    start = datetime(2026, 10, 14, tzinfo=timezone.utc)
    blocks = provider.get_busy(EMP, start, start + timedelta(days=1))
    assert len(blocks) == 1
    assert blocks[0].start == datetime(2026, 10, 14, 8, 0, tzinfo=timezone.utc)
    assert blocks[0].subject == "Kundentermin"
    method, path, body = fake.calls[-1]
    assert body["schedules"] == [EMP.email]
    assert body["startTime"] == {"dateTime": "2026-10-14T00:00:00", "timeZone": "UTC"}


def test_graph_provider_delegates_notifications_to_smtp(graph, monkeypatch):
    provider, fake = graph
    calls = []

    def send_email(cfg, employee, subject, html, text):
        calls.append((employee, subject, html, text))
        return graph_provider.NotificationResult(sent=True, channel="email", detail="sent")

    monkeypatch.setattr(graph_provider, "notify_email", send_email)
    result = provider.notify(EMP, "Betreff", "<p>Hallo</p>", "Hallo")
    assert result.sent and result.channel == "email"
    assert calls == [(EMP, "Betreff", "<p>Hallo</p>", "Hallo")]
    assert not fake.calls


def test_create_appointment_invites_employee(graph):
    provider, fake = graph
    start = datetime(2026, 10, 15, 8, 0, tzinfo=timezone.utc)
    event_id = provider.create_appointment(EMP, start, start + timedelta(minutes=30), "Besuch: Max", "<p>x</p>")
    assert event_id == "event-9"
    body = next(c for c in fake.calls if c[1] == "/me/events")[2]
    assert body["attendees"][0]["emailAddress"]["address"] == EMP.email
    assert body["start"] == {"dateTime": "2026-10-15T08:00:00", "timeZone": "UTC"}

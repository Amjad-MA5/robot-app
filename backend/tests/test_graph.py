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
        if path == "/chats" and request.method == "POST":
            return httpx.Response(201, json={"id": "chat-123"})
        if path == "/chats/chat-123/messages":
            return httpx.Response(201, json={"id": "msg-1"})
        if path == "/me/sendMail":
            return httpx.Response(202)
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
        notify_channels=("teams", "email"),
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
    provider._chat_ids = {}
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


def test_teams_notification_creates_one_on_one_chat(graph):
    provider, fake = graph
    result = provider.notify(EMP, "Betreff", "<p>Hallo</p>", "Hallo")
    assert result.sent and result.channel == "teams"
    chat_call = next(c for c in fake.calls if c[1] == "/chats")
    members = chat_call[2]["members"]
    assert chat_call[2]["chatType"] == "oneOnOne"
    assert {m["user@odata.bind"] for m in members} == {
        f"https://graph.microsoft.com/v1.0/users('{ME_ID}')",
        f"https://graph.microsoft.com/v1.0/users('{EMP.id}')",
    }
    msg_call = next(c for c in fake.calls if c[1] == "/chats/chat-123/messages")
    assert msg_call[2] == {"body": {"contentType": "html", "content": "<p>Hallo</p>"}}
    # zweite Nachricht nutzt den gemerkten Chat
    provider.notify(EMP, "Betreff", "<p>Nochmal</p>", "Nochmal")
    assert sum(1 for c in fake.calls if c[1] == "/chats") == 1


def test_fallback_to_email_when_teams_fails(graph):
    provider, fake = graph
    original = fake.handler

    def failing(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/chats"):
            return httpx.Response(403, json={"error": {"code": "Forbidden", "message": "Teams-Lizenz fehlt"}})
        return original(request)

    provider._http = httpx.Client(transport=httpx.MockTransport(failing))
    result = provider.notify(EMP, "Betreff", "<p>Hallo</p>", "Hallo")
    assert result.sent and result.channel == "email"


def test_create_appointment_invites_employee(graph):
    provider, fake = graph
    start = datetime(2026, 10, 15, 8, 0, tzinfo=timezone.utc)
    event_id = provider.create_appointment(EMP, start, start + timedelta(minutes=30), "Besuch: Max", "<p>x</p>")
    assert event_id == "event-9"
    body = next(c for c in fake.calls if c[1] == "/me/events")[2]
    assert body["attendees"][0]["emailAddress"]["address"] == EMP.email
    assert body["start"] == {"dateTime": "2026-10-15T08:00:00", "timeZone": "UTC"}

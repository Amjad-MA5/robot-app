"""Tests for mock employees with optional real email notifications."""
from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

from app.config import settings
from app.models import Employee, NotificationResult
from app.providers import mock as mock_provider
from app.providers.mock import MockProvider


def make_provider(tmp_path: Path, **overrides) -> MockProvider:
    data_file = tmp_path / "employees.json"
    data_file.write_text(
        json.dumps(
            {
                "employees": [
                    {
                        "id": "mock-anna",
                        "name": "Anna Schmidt",
                        "email": "anna.schmidt@example.com",
                        "department": "Sales",
                        "job_title": "Manager",
                        "calendar": [],
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    return MockProvider(replace(settings, mode="mock", mock_data_file=data_file, **overrides))


def test_configured_smtp_sends_email_to_employee(tmp_path: Path, monkeypatch):
    provider = make_provider(tmp_path, smtp_host="smtp.example.net", smtp_from="reception@example.net")
    employee = provider.list_employees()[0]
    expected = NotificationResult(sent=True, channel="email", detail="sent")
    calls = []

    def send_email(cfg, recipient, subject, html, text):
        calls.append((cfg.smtp_host, recipient, subject, html, text))
        return expected

    monkeypatch.setattr(mock_provider, "notify_email", send_email)

    result = provider.notify(employee, "Subject", "<p>Hello</p>", "Hello")

    assert result == expected
    assert calls == [("smtp.example.net", employee, "Subject", "<p>Hello</p>", "Hello")]
    assert provider.inbox == []


def test_mock_mode_keeps_simulated_email_when_graph_is_disabled(tmp_path: Path):
    provider = make_provider(tmp_path)
    employee = provider.list_employees()[0]

    result = provider.notify(employee, "Subject", "<p>Hello</p>", "Hello")

    assert result.sent and result.channel == "mock"
    assert len(provider.inbox) == 1
    assert provider.inbox[0]["email"] == employee.email
    assert provider.inbox[0]["channel"] == "email (simuliert)"


def test_email_delivery_failure_is_not_reported_as_mock_success(tmp_path: Path, monkeypatch):
    provider = make_provider(tmp_path, smtp_host="smtp.example.net", smtp_from="reception@example.net")
    employee = provider.list_employees()[0]
    expected = NotificationResult(sent=False, channel="email", detail="not signed in")

    def send_email(cfg, recipient, subject, html, text):
        return expected

    monkeypatch.setattr(mock_provider, "notify_email", send_email)

    result = provider.notify(employee, "Subject", "<p>Hello</p>", "Hello")

    assert result == expected
    assert provider.inbox == []

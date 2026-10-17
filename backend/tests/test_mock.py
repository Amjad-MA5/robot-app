"""Tests for mock employees with optional real Teams notifications."""
from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

from app.config import settings
from app.models import Employee, NotificationResult
from app.providers.mock import MockProvider


class TeamsNotifierStub:
    def __init__(self, result: NotificationResult | None) -> None:
        self.result = result
        self.calls: list[tuple[Employee, str]] = []

    def notify_teams_by_email(self, employee: Employee, html: str) -> NotificationResult | None:
        self.calls.append((employee, html))
        return self.result


def make_provider(tmp_path: Path) -> MockProvider:
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
    return MockProvider(replace(settings, mode="mock", mock_teams=False, mock_data_file=data_file))


def test_matching_employee_uses_real_teams_notification(tmp_path: Path):
    provider = make_provider(tmp_path)
    employee = provider.list_employees()[0]
    expected = NotificationResult(sent=True, channel="teams", detail="sent")
    notifier = TeamsNotifierStub(expected)
    provider._teams_notifier = notifier

    result = provider.notify(employee, "Subject", "<p>Hello</p>", "Hello")

    assert result == expected
    assert notifier.calls == [(employee, "<p>Hello</p>")]
    assert provider.inbox == []


def test_unmatched_employee_email_keeps_mock_notification(tmp_path: Path):
    provider = make_provider(tmp_path)
    employee = provider.list_employees()[0]
    notifier = TeamsNotifierStub(None)
    provider._teams_notifier = notifier

    result = provider.notify(employee, "Subject", "<p>Hello</p>", "Hello")

    assert result.sent and result.channel == "mock"
    assert len(provider.inbox) == 1
    assert provider.inbox[0]["email"] == employee.email


def test_teams_delivery_failure_is_not_reported_as_mock_success(tmp_path: Path):
    provider = make_provider(tmp_path)
    employee = provider.list_employees()[0]
    expected = NotificationResult(sent=False, channel="teams", detail="not signed in")
    provider._teams_notifier = TeamsNotifierStub(expected)

    result = provider.notify(employee, "Subject", "<p>Hello</p>", "Hello")

    assert result == expected
    assert provider.inbox == []

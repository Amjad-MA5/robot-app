"""Demo employee/calendar provider with optional real email notifications.

Employees and calendars come from data/mock_employees.json. Without SMTP
configured, notifications are stored in the dashboard's simulated inbox.
"""
from __future__ import annotations

import json
import threading
import uuid
from datetime import datetime, timedelta

from .. import clock
from ..config import Settings
from ..models import BusyBlock, Employee, NotificationResult
from .base import Provider
from .smtp import notify_email


class MockProvider(Provider):
    name = "mock"

    def __init__(self, cfg: Settings) -> None:
        self.cfg = cfg
        self._lock = threading.Lock()
        self.inbox: list[dict] = []
        self.created_events: dict[str, list[BusyBlock]] = {}
        raw = json.loads(cfg.mock_data_file.read_text(encoding="utf-8"))
        self._raw_employees: list[dict] = raw["employees"]
        self._employees = [
            Employee(
                id=e["id"],
                name=e["name"],
                email=e.get("email", ""),
                department=e.get("department", ""),
                job_title=e.get("job_title", ""),
            )
            for e in self._raw_employees
        ]
    def list_employees(self) -> list[Employee]:
        return list(self._employees)

    def _calendar_entries(self, employee_id: str) -> list[dict]:
        for e in self._raw_employees:
            if e["id"] == employee_id:
                return e.get("calendar", [])
        return []

    def get_busy(self, employee: Employee, start: datetime, end: datetime) -> list[BusyBlock]:
        tz = self.cfg.timezone
        now = clock.now(tz)
        today = now.date()
        blocks: list[BusyBlock] = []
        for entry in self._calendar_entries(employee.id):
            subject = entry.get("subject", "Termin")
            if "rel_start_min" in entry:
                # Relativ zu "jetzt" auf volle 5 Minuten gerundet → Demo funktioniert jederzeit
                base = now.replace(second=0, microsecond=0)
                base -= timedelta(minutes=base.minute % 5)
                b_start = base + timedelta(minutes=entry["rel_start_min"])
                b_end = b_start + timedelta(minutes=entry.get("duration_min", 30))
            else:
                day = today + timedelta(days=entry.get("day", 0))
                if entry.get("all_day"):
                    b_start = datetime.combine(day, datetime.min.time(), tzinfo=tz)
                    b_end = b_start + timedelta(days=1)
                else:
                    sh, sm = (int(x) for x in entry["start"].split(":"))
                    eh, em = (int(x) for x in entry["end"].split(":"))
                    b_start = datetime.combine(day, datetime.min.time(), tzinfo=tz).replace(hour=sh, minute=sm)
                    b_end = datetime.combine(day, datetime.min.time(), tzinfo=tz).replace(hour=eh, minute=em)
            blocks.append(BusyBlock(start=b_start, end=b_end, status="busy", subject=subject))
        with self._lock:
            blocks.extend(self.created_events.get(employee.id, []))
        return [b for b in blocks if b.start < end and b.end > start]

    def notify(self, employee: Employee, subject: str, html: str, text: str) -> NotificationResult:
        if self.cfg.smtp_host:
            return notify_email(self.cfg, employee, subject, html, text)
        with self._lock:
            self.inbox.insert(
                0,
                {
                    "id": uuid.uuid4().hex[:8],
                    "time": clock.now(self.cfg.timezone).isoformat(timespec="seconds"),
                    "to": employee.name,
                    "email": employee.email,
                    "channel": "email (simuliert)",
                    "subject": subject,
                    "text": text,
                    "html": html,
                },
            )
            del self.inbox[100:]
        return NotificationResult(sent=True, channel="mock", detail=f"Mock-E-Mail an {employee.name}")

    def create_appointment(
        self, employee: Employee, start: datetime, end: datetime, subject: str, body_html: str
    ) -> str:
        event_id = "mock-" + uuid.uuid4().hex[:10]
        with self._lock:
            self.created_events.setdefault(employee.id, []).append(
                BusyBlock(start=start, end=end, status="busy", subject=subject)
            )
        return event_id

    def status(self) -> dict:
        return {
            "provider": self.name,
            "ready": True,
            "employees": len(self._employees),
            "email_configured": bool(self.cfg.smtp_host),
        }

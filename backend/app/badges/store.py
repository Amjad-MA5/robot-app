"""Storage for active visitor badges - deliberately minimal (privacy).

Only what is needed for access and traceability during the visit is stored:
name, host, validity, zones and the scan log. NEVER the photo.
On checkout or when the badge expires, the entry is deleted completely.
"""
from __future__ import annotations

import json
import logging
import secrets
import threading
from datetime import datetime, timedelta, tzinfo
from pathlib import Path

from pydantic import BaseModel, Field

log = logging.getLogger("smart-receptionist.badges")


class ScanEvent(BaseModel):
    time: datetime
    kind: str = "scan"  # scan | access
    door: str = ""
    allowed: bool | None = None


class PrintInfo(BaseModel):
    sent: bool = False
    method: str = ""
    detail: str = ""
    job_id: int | None = None


class Badge(BaseModel):
    id: str
    token: str
    visitor_name: str
    employee_id: str = ""
    employee_name: str = ""
    purpose: str = ""
    company: str = ""
    created_at: datetime
    valid_until: datetime
    zones: list[str] = Field(default_factory=list)
    has_photo: bool = False
    print_info: PrintInfo = Field(default_factory=PrintInfo)
    scans: list[ScanEvent] = Field(default_factory=list)

    def is_valid(self, now: datetime) -> bool:
        return now < self.valid_until


class BadgeStore:
    def __init__(self, file: Path | None = None) -> None:
        self._file = file
        self._lock = threading.RLock()
        self._badges: dict[str, Badge] = {}
        # Event log without personal data (badge IDs and events only)
        self.events: list[dict] = []
        self._load()

    # ---------------------------------------------------------------- Persistence
    def _load(self) -> None:
        if not self._file or not self._file.exists():
            return
        try:
            raw = json.loads(self._file.read_text(encoding="utf-8"))
            for item in raw.get("badges", []):
                badge = Badge.model_validate(item)
                self._badges[badge.id] = badge
        except Exception as exc:  # corrupt file -> start empty rather than crash
            log.warning("Badge file unreadable (%s) - starting empty", exc)

    def _save(self) -> None:
        if not self._file:
            return
        self._file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._file.with_suffix(".tmp")
        data = {"badges": [b.model_dump(mode="json") for b in self._badges.values()]}
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(self._file)

    # ------------------------------------------------------------------ Lifecycle
    def _new_id(self, now: datetime) -> str:
        while True:
            badge_id = f"V-{now:%m%d}-{secrets.randbelow(9000) + 1000}"
            if badge_id not in self._badges:
                return badge_id

    def create(
        self,
        *,
        visitor_name: str,
        employee_id: str,
        employee_name: str,
        purpose: str,
        company: str,
        zones: list[str],
        now: datetime,
        valid_hours: float,
        has_photo: bool,
    ) -> Badge:
        with self._lock:
            end_of_day = now.replace(hour=23, minute=59, second=0, microsecond=0)
            valid_until = min(now + timedelta(hours=valid_hours), end_of_day)
            if valid_until <= now:  # shortly before midnight: still one hour of validity
                valid_until = now + timedelta(hours=1)
            badge = Badge(
                id=self._new_id(now),
                token=secrets.token_urlsafe(12),
                visitor_name=visitor_name,
                employee_id=employee_id,
                employee_name=employee_name,
                purpose=purpose,
                company=company,
                created_at=now,
                valid_until=valid_until.replace(microsecond=0),
                zones=zones,
                has_photo=has_photo,
            )
            self._badges[badge.id] = badge
            self._event(now, badge.id, "issued")
            self._save()
            return badge

    def update_print(self, badge_id: str, info: PrintInfo) -> None:
        with self._lock:
            badge = self._badges.get(badge_id)
            if badge:
                badge.print_info = info
                self._save()

    def get(self, badge_id: str) -> Badge | None:
        return self._badges.get(badge_id)

    def by_token(self, token: str) -> Badge | None:
        with self._lock:
            return next((b for b in self._badges.values() if secrets.compare_digest(b.token, token)), None)

    def active(self) -> list[Badge]:
        with self._lock:
            return sorted(self._badges.values(), key=lambda b: b.created_at, reverse=True)

    def record_scan(self, badge: Badge, event: ScanEvent) -> None:
        with self._lock:
            badge.scans.append(event)
            del badge.scans[:-50]  # do not grow forever
            self._event(event.time, badge.id, f"{event.kind} {event.door}".strip()
                        + ("" if event.allowed is None else (" ✔" if event.allowed else " ✖")))
            self._save()

    def checkout(self, badge_id: str, now: datetime) -> bool:
        """Visit finished -> delete the badge and all of its data."""
        with self._lock:
            if self._badges.pop(badge_id, None) is None:
                return False
            self._event(now, badge_id, "checked out, data deleted")
            self._save()
            return True

    def purge_expired(self, now: datetime) -> list[str]:
        """Delete expired badges completely. Returns the deleted IDs."""
        with self._lock:
            expired = [b.id for b in self._badges.values() if not b.is_valid(now)]
            for badge_id in expired:
                del self._badges[badge_id]
                self._event(now, badge_id, "expired, data deleted")
            if expired:
                self._save()
            return expired

    def clear(self) -> None:
        with self._lock:
            self._badges.clear()
            self.events.clear()
            self._save()

    def _event(self, time: datetime, badge_id: str, what: str) -> None:
        self.events.insert(0, {"time": time.isoformat(timespec="seconds"), "badge": badge_id, "event": what})
        del self.events[200:]


def localize(dt: datetime, tz: tzinfo) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=tz)

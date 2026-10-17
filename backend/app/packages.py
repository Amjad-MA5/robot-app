"""Protokoll der am Empfang abgegebenen Pakete (im Speicher, Fotos auf der Platte)."""
from __future__ import annotations

import base64
import binascii
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

MAX_PHOTO_BYTES = 4 * 1024 * 1024


@dataclass
class Package:
    id: str
    created: datetime
    employee_id: str
    employee_name: str
    carrier: str
    handling: str
    status: str = "waiting"  # waiting | collected
    reminded: bool = False
    has_photo: bool = False

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "time": self.created.isoformat(timespec="seconds"),
            "employee": self.employee_name,
            "employee_id": self.employee_id,
            "carrier": self.carrier,
            "handling": self.handling,
            "status": self.status,
            "reminded": self.reminded,
            "has_photo": self.has_photo,
        }


class PackageLog:
    def __init__(self, photo_dir: Path) -> None:
        self.photo_dir = photo_dir
        self._lock = threading.Lock()
        self._items: dict[str, Package] = {}

    def add(self, created: datetime, employee_id: str, employee_name: str, carrier: str, handling: str) -> Package:
        pkg = Package(uuid.uuid4().hex[:12], created, employee_id, employee_name, carrier, handling)
        with self._lock:
            self._items[pkg.id] = pkg
            while len(self._items) > 500:
                self._items.pop(next(iter(self._items)))
        return pkg

    def get(self, package_id: str) -> Package | None:
        return self._items.get(package_id)

    def list(self) -> list[Package]:
        with self._lock:
            return sorted(self._items.values(), key=lambda p: p.created, reverse=True)

    def mark_collected(self, package_id: str) -> Package | None:
        pkg = self.get(package_id)
        if pkg:
            pkg.status = "collected"
        return pkg

    def due_reminders(self, now: datetime, after_min: int) -> list[Package]:
        if after_min <= 0:
            return []
        limit = timedelta(minutes=after_min)
        return [p for p in self.list() if p.status == "waiting" and not p.reminded and now - p.created >= limit]

    # ----------------------------------------------------------------- Fotos
    def photo_path(self, package_id: str) -> Path:
        return self.photo_dir / f"{package_id}.jpg"

    def save_photo(self, pkg: Package, photo_base64: str) -> bool:
        """Speichert ein JPEG; gibt False zurück, wenn die Daten ungültig sind."""
        try:
            data = base64.b64decode(photo_base64, validate=False)
        except (binascii.Error, ValueError):
            return False
        if not data.startswith(b"\xff\xd8") or len(data) > MAX_PHOTO_BYTES:
            return False
        self.photo_dir.mkdir(parents=True, exist_ok=True)
        self.photo_path(pkg.id).write_bytes(data)
        pkg.has_photo = True
        return True

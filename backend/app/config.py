"""Konfiguration des Smart-Receptionist-Backends.

Alle Werte kommen aus Umgebungsvariablen (optional über eine .env-Datei im
backend-Ordner). Siehe .env.example für eine kommentierte Vorlage.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import time
from pathlib import Path
from zoneinfo import ZoneInfo

from dotenv import load_dotenv

BACKEND_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BACKEND_DIR / ".env")


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    return int(raw) if raw else default


def _env_bool(name: str, default: bool = False) -> bool:
    raw = _env(name)
    if not raw:
        return default
    value = raw.lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean value")


def _parse_time(raw: str) -> time:
    hours, minutes = raw.split(":")
    return time(int(hours), int(minutes))


def _resolve(path: str) -> Path:
    p = Path(path)
    return p if p.is_absolute() else (BACKEND_DIR / p)


@dataclass
class Settings:
    # Gemeinsamer Schlüssel zwischen Roboter-App und Backend (Header "X-Api-Key").
    api_key: str = field(default_factory=lambda: _env("SR_API_KEY", "hinterland-demo"))
    # "mock" = alles simuliert, "graph" = echtes Microsoft 365 über MS Graph
    mode: str = field(default_factory=lambda: _env("SR_MODE", "mock").lower())
    # Sprache der Benachrichtigungen an Mitarbeiter: en | de
    language: str = field(default_factory=lambda: _env("SR_LANGUAGE", "en").lower())
    company_name: str = field(default_factory=lambda: _env("SR_COMPANY_NAME", "Founders Foundation"))
    # Was mit Paketen passiert: leave_at_desk | wait_for_recipient
    delivery_handling: str = field(default_factory=lambda: _env("SR_DELIVERY_HANDLING", "leave_at_desk"))
    # Erinnerung, wenn ein Paket nach so vielen Minuten nicht abgeholt wurde (0 = aus)
    package_reminder_min: int = field(default_factory=lambda: _env_int("SR_PACKAGE_REMINDER_MIN", 120))
    # Besucherprotokoll (eine JSON-Zeile pro Besuch) - enthält Namen, darf nicht ins Git
    visit_log_file: Path = field(default_factory=lambda: _resolve(_env("SR_VISIT_LOG", "data/visits.jsonl")))
    package_photo_dir: Path = field(default_factory=lambda: _resolve(_env("SR_PACKAGE_PHOTO_DIR", "data/package_photos")))

    timezone: ZoneInfo = field(default_factory=lambda: ZoneInfo(_env("SR_TIMEZONE", "Europe/Berlin")))
    work_start: time = field(default_factory=lambda: _parse_time(_env("SR_WORK_START", "09:00")))
    work_end: time = field(default_factory=lambda: _parse_time(_env("SR_WORK_END", "17:00")))
    # ISO-Wochentage: 1 = Montag … 7 = Sonntag
    work_days: tuple[int, ...] = field(
        default_factory=lambda: tuple(int(d) for d in _env("SR_WORK_DAYS", "1,2,3,4,5").split(",") if d)
    )
    slot_minutes: int = field(default_factory=lambda: _env_int("SR_SLOT_MINUTES", 30))
    slot_step_minutes: int = field(default_factory=lambda: _env_int("SR_SLOT_STEP_MINUTES", 30))
    lead_minutes: int = field(default_factory=lambda: _env_int("SR_LEAD_MINUTES", 15))
    search_days: int = field(default_factory=lambda: _env_int("SR_SEARCH_DAYS", 10))
    max_slots: int = field(default_factory=lambda: _env_int("SR_MAX_SLOTS", 3))
    max_slots_per_day: int = field(default_factory=lambda: _env_int("SR_MAX_SLOTS_PER_DAY", 2))
    # Zeitfenster, in dem ein Kalendereintrag als "der Termin des Besuchers" gilt
    appointment_window_before_min: int = field(default_factory=lambda: _env_int("SR_APPT_BEFORE_MIN", 60))
    appointment_window_after_min: int = field(default_factory=lambda: _env_int("SR_APPT_AFTER_MIN", 90))

    mock_data_file: Path = field(
        default_factory=lambda: _resolve(_env("SR_MOCK_DATA", "data/mock_employees.json"))
    )
    apk_dir: Path = field(default_factory=lambda: _resolve(_env("SR_APK_DIR", "apk")))

    # --- Microsoft Graph -------------------------------------------------
    graph_tenant_id: str = field(default_factory=lambda: _env("SR_GRAPH_TENANT_ID"))
    graph_client_id: str = field(default_factory=lambda: _env("SR_GRAPH_CLIENT_ID"))
    graph_client_secret: str = field(default_factory=lambda: _env("SR_GRAPH_CLIENT_SECRET"))
    # "delegated" = Backend meldet sich als Empfangs-Konto an (Device Code)
    # "app" = App-Berechtigungen mit Client Secret
    graph_auth: str = field(default_factory=lambda: _env("SR_GRAPH_AUTH", "delegated").lower())
    # Optional: nur Mitglieder dieser Entra-Gruppe sind als Ansprechpartner wählbar
    employee_group_id: str = field(default_factory=lambda: _env("SR_EMPLOYEE_GROUP_ID"))
    smtp_host: str = field(default_factory=lambda: _env("SR_SMTP_HOST"))
    smtp_port: int = field(default_factory=lambda: _env_int("SR_SMTP_PORT", 587))
    smtp_username: str = field(default_factory=lambda: _env("SR_SMTP_USERNAME"))
    smtp_password: str = field(default_factory=lambda: _env("SR_SMTP_PASSWORD"))
    smtp_from: str = field(default_factory=lambda: _env("SR_SMTP_FROM"))
    smtp_security: str = field(default_factory=lambda: _env("SR_SMTP_SECURITY", "starttls").lower())
    token_cache_file: Path = field(
        default_factory=lambda: _resolve(_env("SR_TOKEN_CACHE", "data/msal_token_cache.json"))
    )

    def __post_init__(self) -> None:
        if self.smtp_security not in {"starttls", "ssl", "none"}:
            raise ValueError("SR_SMTP_SECURITY must be starttls, ssl, or none")
        if not 1 <= self.smtp_port <= 65535:
            raise ValueError("SR_SMTP_PORT must be between 1 and 65535")
        if self.smtp_host:
            if not self.smtp_from:
                raise ValueError("SR_SMTP_FROM is required when SR_SMTP_HOST is configured")
            if bool(self.smtp_username) != bool(self.smtp_password):
                raise ValueError("SR_SMTP_USERNAME and SR_SMTP_PASSWORD must be configured together")
            if self.smtp_security == "none" and self.smtp_username:
                raise ValueError("SMTP credentials require TLS; set SR_SMTP_SECURITY to starttls or ssl")
        elif self.smtp_username or self.smtp_password or self.smtp_from:
            raise ValueError("SR_SMTP_HOST is required when SMTP settings are configured")

        # Im Demo-Modus sollen die Szenarien auch am Wochenende oder abends
        # (Hackathon!) funktionieren – solange nichts anderes eingestellt ist.
        if self.mode == "mock":
            if not _env("SR_WORK_DAYS"):
                self.work_days = (1, 2, 3, 4, 5, 6, 7)
            if not _env("SR_WORK_START"):
                self.work_start = time(8, 0)
            if not _env("SR_WORK_END"):
                self.work_end = time(20, 0)


settings = Settings()

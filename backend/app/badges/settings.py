"""Einstellungen für Besucherausweise und Drucker (Umgebungsvariablen, siehe .env.example)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from ..config import BACKEND_DIR


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_list(name: str, default: str) -> tuple[str, ...]:
    return tuple(p.strip() for p in _env(name, default).split(",") if p.strip())


@dataclass
class BadgeSettings:
    # Druckwege in dieser Reihenfolge probieren: ipp, ftp, mock (nur Dashboard), off
    printer_methods: tuple[str, ...] = field(default_factory=lambda: _env_list("SR_PRINTER_METHODS", "ipp"))
    # IPP-Adresse des Druckers (bizhub: http://<IP>/ipp über Port 80; alternativ ipp://<IP>:631/ipp)
    printer_url: str = field(default_factory=lambda: _env("SR_PRINTER_URL", "http://192.168.0.233/ipp"))
    printer_ftp_host: str = field(default_factory=lambda: _env("SR_PRINTER_FTP_HOST", "192.168.0.233"))
    printer_ftp_user: str = field(default_factory=lambda: _env("SR_PRINTER_FTP_USER", "anonymous"))
    printer_ftp_password: str = field(default_factory=lambda: _env("SR_PRINTER_FTP_PASSWORD", ""))
    # Name, unter dem der Druckauftrag im Drucker erscheint
    printer_user: str = field(default_factory=lambda: _env("SR_PRINTER_USER", "Nova-Empfang"))
    printer_timeout_s: float = field(default_factory=lambda: float(_env("SR_PRINTER_TIMEOUT", "20")))
    # Farbe: color | monochrome
    print_color: str = field(default_factory=lambda: _env("SR_PRINT_COLOR", "color"))

    # Ausweis gilt so viele Stunden (spätestens bis Mitternacht), danach werden alle Daten gelöscht
    valid_hours: float = field(default_factory=lambda: float(_env("SR_BADGE_VALID_HOURS", "8")))
    # Zonen, die ein Besucherausweis öffnet (für Türleser / Pitch)
    zones: tuple[str, ...] = field(
        default_factory=lambda: _env_list("SR_BADGE_ZONES", "Empfang,Warteraum,Besprechungsräume")
    )
    # Öffentliche Adresse des Backends für den QR-Code (leer = Adresse, unter der der Roboter es erreicht)
    public_url: str = field(default_factory=lambda: _env("SR_PUBLIC_URL").rstrip("/"))
    # Ausweisdaten (ohne Foto!) überleben einen Neustart des Backends
    store_file: Path = field(
        default_factory=lambda: Path(_env("SR_BADGE_STORE", str(BACKEND_DIR / "data" / "badges.json")))
    )


badge_settings = BadgeSettings()

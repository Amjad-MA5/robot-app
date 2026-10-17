"""Settings for visitor badges and the printer (environment variables, see .env.example)."""
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
    # Print methods tried in this order: ipp, ftp, mock (dashboard only), off
    printer_methods: tuple[str, ...] = field(default_factory=lambda: _env_list("SR_PRINTER_METHODS", "ipp"))
    # IPP address of the printer (bizhub: http://<IP>/ipp on port 80, or ipp://<IP>:631/ipp)
    printer_url: str = field(default_factory=lambda: _env("SR_PRINTER_URL", "http://192.168.0.233/ipp"))
    printer_ftp_host: str = field(default_factory=lambda: _env("SR_PRINTER_FTP_HOST", "192.168.0.233"))
    printer_ftp_user: str = field(default_factory=lambda: _env("SR_PRINTER_FTP_USER", "anonymous"))
    printer_ftp_password: str = field(default_factory=lambda: _env("SR_PRINTER_FTP_PASSWORD", ""))
    # User name shown for the job on the printer
    printer_user: str = field(default_factory=lambda: _env("SR_PRINTER_USER", "Nova-Reception"))
    printer_timeout_s: float = field(default_factory=lambda: float(_env("SR_PRINTER_TIMEOUT", "20")))
    # color | monochrome
    print_color: str = field(default_factory=lambda: _env("SR_PRINT_COLOR", "color"))

    # A badge is valid for this many hours (at most until midnight); afterwards all data is deleted
    valid_hours: float = field(default_factory=lambda: float(_env("SR_BADGE_VALID_HOURS", "8")))
    # Zones a visitor badge opens (for door readers)
    zones: tuple[str, ...] = field(
        default_factory=lambda: _env_list("SR_BADGE_ZONES", "Reception,Waiting room,Meeting rooms")
    )
    # Public backend address used in the QR code (empty = the address the robot used)
    public_url: str = field(default_factory=lambda: _env("SR_PUBLIC_URL").rstrip("/"))
    # Badge data (never the photo) survives a backend restart
    store_file: Path = field(
        default_factory=lambda: Path(_env("SR_BADGE_STORE", str(BACKEND_DIR / "data" / "badges.json")))
    )


badge_settings = BadgeSettings()

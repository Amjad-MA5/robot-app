"""Zentrale Zeitquelle – in Tests austauschbar, damit Szenarien zeitunabhängig prüfbar sind."""
from __future__ import annotations

from datetime import datetime, tzinfo

_fixed: datetime | None = None


def now(tz: tzinfo) -> datetime:
    if _fixed is not None:
        return _fixed.astimezone(tz)
    return datetime.now(tz)


def freeze(value: datetime | None) -> None:
    """Nur für Tests: feste Uhrzeit setzen (None = echte Zeit)."""
    global _fixed
    _fixed = value

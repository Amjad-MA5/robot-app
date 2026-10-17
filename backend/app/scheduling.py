"""Berechnung freier Termine aus belegten Kalenderblöcken.

Unabhängig davon, ob die Belegung aus dem Mock-Kalender oder aus Outlook
(MS Graph getSchedule) kommt, wird hier dieselbe Logik verwendet.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from .config import Settings
from .models import BusyBlock, Slot

# Diese Status zählen als "nicht verfügbar". "free" zählt als frei.
BLOCKING_STATUS = {"busy", "tentative", "oof", "workingelsewhere", "unknown"}


def _ceil_to_step(dt: datetime, step_minutes: int) -> datetime:
    dt = dt.replace(second=0, microsecond=0)
    remainder = dt.minute % step_minutes
    if remainder:
        dt += timedelta(minutes=step_minutes - remainder)
    return dt


def _overlaps(start: datetime, end: datetime, busy: list[BusyBlock]) -> bool:
    for block in busy:
        if block.status.lower() not in BLOCKING_STATUS:
            continue
        if block.start < end and block.end > start:
            return True
    return False


def working_window(day: date, cfg: Settings) -> tuple[datetime, datetime] | None:
    if day.isoweekday() not in cfg.work_days:
        return None
    start = datetime.combine(day, cfg.work_start, tzinfo=cfg.timezone)
    end = datetime.combine(day, cfg.work_end, tzinfo=cfg.timezone)
    return start, end


def find_free_slots(
    busy: list[BusyBlock],
    now: datetime,
    cfg: Settings,
    *,
    days: int | None = None,
    max_slots: int | None = None,
    max_per_day: int | None = None,
    start_day_offset: int = 0,
) -> list[Slot]:
    """Liefert freie Termine ab "jetzt + Vorlaufzeit" innerhalb der Arbeitszeiten.

    Pro Tag werden höchstens ``max_per_day`` Vorschläge gemacht, mit mindestens
    90 Minuten Abstand – so bekommt der Besucher abwechslungsreiche Optionen
    statt "10:00, 10:30, 11:00".
    """
    days = cfg.search_days if days is None else days
    max_slots = cfg.max_slots if max_slots is None else max_slots
    max_per_day = cfg.max_slots_per_day if max_per_day is None else max_per_day
    duration = timedelta(minutes=cfg.slot_minutes)
    step = timedelta(minutes=cfg.slot_step_minutes)
    min_gap = timedelta(minutes=90)
    earliest = _ceil_to_step(now.astimezone(cfg.timezone) + timedelta(minutes=cfg.lead_minutes), cfg.slot_step_minutes)

    result: list[Slot] = []
    today = now.astimezone(cfg.timezone).date()
    for offset in range(start_day_offset, start_day_offset + days):
        day = today + timedelta(days=offset)
        window = working_window(day, cfg)
        if window is None:
            continue
        cursor = max(window[0], earliest)
        cursor = _ceil_to_step(cursor, cfg.slot_step_minutes)
        per_day = 0
        last_pick: datetime | None = None
        while cursor + duration <= window[1]:
            if not _overlaps(cursor, cursor + duration, busy):
                if last_pick is None or cursor - last_pick >= min_gap:
                    result.append(Slot(start=cursor, end=cursor + duration))
                    per_day += 1
                    last_pick = cursor
                    if len(result) >= max_slots:
                        return result
                    if per_day >= max_per_day:
                        break
            cursor += step
    return result


def is_free_now(busy: list[BusyBlock], now: datetime, cfg: Settings) -> bool:
    local_now = now.astimezone(cfg.timezone)
    window = working_window(local_now.date(), cfg)
    if window is None or not (window[0] <= local_now < window[1]):
        return False
    return not _overlaps(local_now, local_now + timedelta(minutes=15), busy)


def current_appointment(busy: list[BusyBlock], now: datetime, cfg: Settings) -> BusyBlock | None:
    """Kalendereintrag, der am besten zu einem gerade eintreffenden Besuch passt."""
    lo = now - timedelta(minutes=cfg.appointment_window_before_min)
    hi = now + timedelta(minutes=cfg.appointment_window_after_min)
    candidates = [b for b in busy if lo <= b.start <= hi and b.status.lower() != "free"]
    if not candidates:
        return None
    return min(candidates, key=lambda b: abs((b.start - now).total_seconds()))

"""Texte der Benachrichtigungen an Mitarbeiter (Teams/E-Mail)."""
from __future__ import annotations

from datetime import datetime
from html import escape

from .models import BusyBlock

WEEKDAYS_DE = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]


def _hm(dt: datetime) -> str:
    return dt.strftime("%H:%M")


def _date_long(dt: datetime) -> str:
    return f"{WEEKDAYS_DE[dt.weekday()]}, {dt.strftime('%d.%m.%Y')}"


def _purpose_html(purpose: str) -> str:
    return f"<p>Anliegen: {escape(purpose)}</p>" if purpose.strip() else ""


def _purpose_text(purpose: str) -> str:
    return f"\nAnliegen: {purpose}" if purpose.strip() else ""


def arrival(visitor: str, now: datetime, appointment: BusyBlock | None, purpose: str) -> tuple[str, str, str]:
    v = escape(visitor)
    appt_html = appt_text = ""
    if appointment is not None:
        local_start = appointment.start.astimezone(now.tzinfo)
        subj = f" – {appointment.subject}" if appointment.subject else ""
        appt_text = f" (Termin laut Kalender: {_hm(local_start)} Uhr{subj})"
        appt_html = escape(appt_text)
    subject = f"Ihr Besuch ist eingetroffen: {visitor}"
    html = (
        f"<p>🔔 <b>Ihr Besuch ist eingetroffen</b></p>"
        f"<p><b>{v}</b> ist um {_hm(now)} Uhr am Empfang angekommen{appt_html}.</p>"
        f"{_purpose_html(purpose)}"
        f"<p>Der Empfangsroboter begleitet {v} jetzt in den Warteraum.</p>"
    )
    text = (
        f"Ihr Besuch ist eingetroffen: {visitor} ist um {_hm(now)} Uhr am Empfang angekommen{appt_text}."
        f"{_purpose_text(purpose)}\nDer Empfangsroboter begleitet den Gast in den Warteraum."
    )
    return subject, html, text


def walk_in(visitor: str, now: datetime, free_now: bool, first_free: datetime | None, purpose: str) -> tuple[str, str, str]:
    v = escape(visitor)
    if free_now:
        when = "Laut Kalender sind Sie gerade frei."
    elif first_free is not None:
        when = f"Laut Kalender sind Sie heute ab {_hm(first_free)} Uhr frei."
    else:
        when = ""
    subject = f"Besuch ohne Termin am Empfang: {visitor}"
    html = (
        f"<p>👋 <b>Jemand möchte Sie sprechen</b></p>"
        f"<p><b>{v}</b> ist um {_hm(now)} Uhr ohne Termin am Empfang und hat nach einem Gespräch mit Ihnen gefragt.</p>"
        f"{_purpose_html(purpose)}"
        f"<p>{escape(when)} Bitte melden Sie sich am Empfang, wenn Sie Zeit haben.</p>"
    )
    text = (
        f"Jemand möchte Sie sprechen: {visitor} ist ohne Termin am Empfang und hat nach einem Gespräch "
        f"mit Ihnen gefragt.{_purpose_text(purpose)}\n{when} Bitte melden Sie sich am Empfang."
    )
    return subject, html, text


def appointment_booked(visitor: str, start: datetime, end: datetime, purpose: str, visitor_email: str) -> tuple[str, str, str]:
    v = escape(visitor)
    contact_html = f"<p>Kontakt: {escape(visitor_email)}</p>" if visitor_email else ""
    contact_text = f"\nKontakt: {visitor_email}" if visitor_email else ""
    subject = f"Neuer Termin: {visitor} am {start.strftime('%d.%m.')} um {_hm(start)} Uhr"
    html = (
        f"<p>📅 <b>Neuer Termin über den Empfang</b></p>"
        f"<p><b>{v}</b> hat am Empfang einen Termin mit Ihnen vereinbart: "
        f"<b>{_date_long(start)}, {_hm(start)}–{_hm(end)} Uhr</b>.</p>"
        f"{_purpose_html(purpose)}{contact_html}"
        f"<p>Der Termin wurde in Ihren Kalender eingetragen.</p>"
    )
    text = (
        f"Neuer Termin über den Empfang: {visitor} am {_date_long(start)}, {_hm(start)}–{_hm(end)} Uhr."
        f"{_purpose_text(purpose)}{contact_text}"
    )
    return subject, html, text


def event_body(visitor: str, purpose: str, visitor_email: str) -> tuple[str, str]:
    subject = f"Besuch: {visitor}"
    parts = [f"<p>Termin vereinbart über den Empfangsroboter.</p><p>Besucher: <b>{escape(visitor)}</b></p>"]
    if purpose.strip():
        parts.append(f"<p>Anliegen: {escape(purpose)}</p>")
    if visitor_email:
        parts.append(f"<p>Kontakt: {escape(visitor_email)}</p>")
    return subject, "".join(parts)

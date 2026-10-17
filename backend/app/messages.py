"""Notification texts for employees (email/Teams) in English and German (SR_LANGUAGE)."""
from __future__ import annotations

from datetime import datetime
from html import escape

from .models import BusyBlock

WEEKDAYS = {
    "de": ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"],
    "en": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
}

# Alle Satzbausteine; {…} werden mit .format() gefüllt.
T = {
    "de": {
        "clock": "{t} Uhr",
        "purpose": "Anliegen: {p}",
        "unknown_carrier": "ein Zusteller",
        # Ankunft (Termin vorhanden)
        "arrival_subject": "Ihr Besuch ist eingetroffen: {v}",
        "arrival_title": "Ihr Besuch ist eingetroffen",
        "arrival_body": "{v} ist um {t} am Empfang angekommen{appt}.",
        "arrival_appt": " (Termin laut Kalender: {t}{subj})",
        "arrival_escort": "Der Empfangsroboter begleitet {v} jetzt in den Warteraum.",
        "arrival_text": "Ihr Besuch ist eingetroffen: {v} ist um {t} am Empfang angekommen{appt}.{purpose}\n"
                        "Der Empfangsroboter begleitet den Gast in den Warteraum.",
        # Besuch ohne Termin
        "walkin_free_now": "Laut Kalender sind Sie gerade frei.",
        "walkin_free_from": "Laut Kalender sind Sie heute ab {t} frei.",
        "walkin_subject": "Besuch ohne Termin am Empfang: {v}",
        "walkin_title": "Jemand möchte Sie sprechen",
        "walkin_body": "{v} ist um {t} ohne Termin am Empfang und hat nach einem Gespräch mit Ihnen gefragt.",
        "walkin_ask": "Bitte melden Sie sich am Empfang, wenn Sie Zeit haben.",
        "walkin_text": "Jemand möchte Sie sprechen: {v} ist ohne Termin am Empfang und hat nach einem Gespräch "
                       "mit Ihnen gefragt.{purpose}\n{when} Bitte melden Sie sich am Empfang.",
        # Neuer Termin
        "booked_contact": "Kontakt: {c}",
        "booked_subject": "Neuer Termin: {v} am {d} um {t}",
        "booked_title": "Neuer Termin über den Empfang",
        "booked_body": "{v} hat am Empfang einen Termin mit Ihnen vereinbart: {when}.",
        "booked_calendar": "Der Termin wurde in Ihren Kalender eingetragen.",
        "booked_text": "Neuer Termin über den Empfang: {v} am {when}.{purpose}{contact}",
        # Kalendereintrag
        "event_subject": "Besuch: {v}",
        "event_intro": "Termin vereinbart über den Empfangsroboter.",
        "event_visitor": "Besucher: {v}",
        # Paket
        "pkg_wait": "Für das Paket ist Ihre Unterschrift nötig. Der Zusteller wartet im Warteraum auf Sie.",
        "pkg_leave": "Das Paket wird am Empfang für Sie hinterlegt.",
        "pkg_note": "Hinweis: {n}",
        "pkg_photo": "📷 Foto des Pakets",
        "pkg_photo_text": "Foto: {u}",
        "pkg_collect": "✅ Als abgeholt markieren",
        "pkg_collect_text": "Als abgeholt markieren: {u}",
        "pkg_subject": "Paket für Sie am Empfang ({c})",
        "pkg_title": "Paket für Sie",
        "pkg_body": "{c} hat um {t} ein Paket für Sie am Empfang abgegeben.",
        "pkg_text": "Paket für Sie: {c} hat um {t} ein Paket am Empfang abgegeben.{note}\n{action}{links}",
        "rem_subject": "Erinnerung: Paket am Empfang ({c})",
        "rem_title": "Erinnerung: Ihr Paket wartet noch",
        "rem_body": "Das Paket von {c} (angekommen um {t}) wurde noch nicht abgeholt.",
        "rem_text": "Erinnerung: Das Paket von {c} (angekommen um {t}) wurde noch nicht abgeholt.{links}",
        # Test
        "test_subject": "Smart Receptionist: Test",
        "test_text": "Testnachricht vom Smart Receptionist – die Verbindung funktioniert.",
        # Webseiten
        "page_collected": "Dieses Paket wurde als abgeholt markiert.",
        "page_package": "Paket von {c} für {e}.",
        "page_button": "Als abgeholt markieren",
        "page_thanks": "Danke, das Paket ist als abgeholt markiert.",
    },
    "en": {
        "clock": "{t}",
        "purpose": "Reason: {p}",
        "unknown_carrier": "a courier",
        "arrival_subject": "Your visitor has arrived: {v}",
        "arrival_title": "Your visitor has arrived",
        "arrival_body": "{v} arrived at reception at {t}{appt}.",
        "arrival_appt": " (appointment in your calendar: {t}{subj})",
        "arrival_escort": "The reception robot is now taking {v} to the waiting room.",
        "arrival_text": "Your visitor has arrived: {v} arrived at reception at {t}{appt}.{purpose}\n"
                        "The reception robot is taking the guest to the waiting room.",
        "walkin_free_now": "According to your calendar you are free right now.",
        "walkin_free_from": "According to your calendar you are free today from {t}.",
        "walkin_subject": "Visitor without appointment at reception: {v}",
        "walkin_title": "Someone would like to speak with you",
        "walkin_body": "{v} is at reception since {t} without an appointment and asked to talk to you.",
        "walkin_ask": "Please come to reception when you have time.",
        "walkin_text": "Someone would like to speak with you: {v} is at reception without an appointment and asked "
                       "to talk to you.{purpose}\n{when} Please come to reception.",
        "booked_contact": "Contact: {c}",
        "booked_subject": "New appointment: {v} on {d} at {t}",
        "booked_title": "New appointment made at reception",
        "booked_body": "{v} made an appointment with you at reception: {when}.",
        "booked_calendar": "The appointment has been added to your calendar.",
        "booked_text": "New appointment made at reception: {v} on {when}.{purpose}{contact}",
        "event_subject": "Visit: {v}",
        "event_intro": "Appointment made via the reception robot.",
        "event_visitor": "Visitor: {v}",
        "pkg_wait": "The package needs your signature. The courier is waiting for you in the waiting room.",
        "pkg_leave": "The package will be left at reception for you.",
        "pkg_note": "Note: {n}",
        "pkg_photo": "📷 Photo of the package",
        "pkg_photo_text": "Photo: {u}",
        "pkg_collect": "✅ Mark as collected",
        "pkg_collect_text": "Mark as collected: {u}",
        "pkg_subject": "Package for you at reception ({c})",
        "pkg_title": "Package for you",
        "pkg_body": "{c} dropped off a package for you at reception at {t}.",
        "pkg_text": "Package for you: {c} dropped off a package at reception at {t}.{note}\n{action}{links}",
        "rem_subject": "Reminder: package at reception ({c})",
        "rem_title": "Reminder: your package is still waiting",
        "rem_body": "The package from {c} (arrived at {t}) has not been collected yet.",
        "rem_text": "Reminder: the package from {c} (arrived at {t}) has not been collected yet.{links}",
        "test_subject": "Smart Receptionist: Test",
        "test_text": "Test message from the Smart Receptionist – the connection works.",
        "page_collected": "This package has been marked as collected.",
        "page_package": "Package from {c} for {e}.",
        "page_button": "Mark as collected",
        "page_thanks": "Thank you, the package is marked as collected.",
    },
}


def tr(lang: str, key: str, **kw) -> str:
    return T.get(lang, T["en"])[key].format(**kw)


def _hm(dt: datetime) -> str:
    return dt.strftime("%H:%M")


def _clock(dt: datetime, lang: str) -> str:
    return tr(lang, "clock", t=_hm(dt))


def _date_long(dt: datetime, lang: str) -> str:
    return f"{WEEKDAYS.get(lang, WEEKDAYS['en'])[dt.weekday()]}, {dt.strftime('%d.%m.%Y')}"


def _purpose_html(purpose: str, lang: str) -> str:
    return f"<p>{escape(tr(lang, 'purpose', p=purpose))}</p>" if purpose.strip() else ""


def _purpose_text(purpose: str, lang: str) -> str:
    return "\n" + tr(lang, "purpose", p=purpose) if purpose.strip() else ""


def arrival(visitor: str, now: datetime, appointment: BusyBlock | None, purpose: str, lang: str = "en") -> tuple[str, str, str]:
    v = escape(visitor)
    appt_html = appt_text = ""
    if appointment is not None:
        local_start = appointment.start.astimezone(now.tzinfo)
        subj = f" – {appointment.subject}" if appointment.subject else ""
        appt_text = tr(lang, "arrival_appt", t=_clock(local_start, lang), subj=subj)
        appt_html = escape(appt_text)
    subject = tr(lang, "arrival_subject", v=visitor)
    html = (
        f"<p>🔔 <b>{escape(tr(lang, 'arrival_title'))}</b></p>"
        f"<p>{tr(lang, 'arrival_body', v=f'<b>{v}</b>', t=_clock(now, lang), appt=appt_html)}</p>"
        f"{_purpose_html(purpose, lang)}"
        f"<p>{tr(lang, 'arrival_escort', v=v)}</p>"
    )
    text = tr(lang, "arrival_text", v=visitor, t=_clock(now, lang), appt=appt_text, purpose=_purpose_text(purpose, lang))
    return subject, html, text


def walk_in(
    visitor: str, now: datetime, free_now: bool, first_free: datetime | None, purpose: str, lang: str = "en"
) -> tuple[str, str, str]:
    v = escape(visitor)
    if free_now:
        when = tr(lang, "walkin_free_now")
    elif first_free is not None:
        when = tr(lang, "walkin_free_from", t=_clock(first_free, lang))
    else:
        when = ""
    subject = tr(lang, "walkin_subject", v=visitor)
    html = (
        f"<p>👋 <b>{escape(tr(lang, 'walkin_title'))}</b></p>"
        f"<p>{tr(lang, 'walkin_body', v=f'<b>{v}</b>', t=_clock(now, lang))}</p>"
        f"{_purpose_html(purpose, lang)}"
        f"<p>{escape(when)} {escape(tr(lang, 'walkin_ask'))}</p>"
    )
    text = tr(lang, "walkin_text", v=visitor, purpose=_purpose_text(purpose, lang), when=when)
    return subject, html, text


def appointment_booked(
    visitor: str, start: datetime, end: datetime, purpose: str, visitor_email: str, lang: str = "en"
) -> tuple[str, str, str]:
    v = escape(visitor)
    contact_html = f"<p>{escape(tr(lang, 'booked_contact', c=visitor_email))}</p>" if visitor_email else ""
    contact_text = "\n" + tr(lang, "booked_contact", c=visitor_email) if visitor_email else ""
    when = f"{_date_long(start, lang)}, {_hm(start)}–{_clock(end, lang)}"
    subject = tr(lang, "booked_subject", v=visitor, d=start.strftime("%d.%m."), t=_clock(start, lang))
    html = (
        f"<p>📅 <b>{escape(tr(lang, 'booked_title'))}</b></p>"
        f"<p>{tr(lang, 'booked_body', v=f'<b>{v}</b>', when=f'<b>{when}</b>')}</p>"
        f"{_purpose_html(purpose, lang)}{contact_html}"
        f"<p>{escape(tr(lang, 'booked_calendar'))}</p>"
    )
    text = tr(lang, "booked_text", v=visitor, when=when, purpose=_purpose_text(purpose, lang), contact=contact_text)
    return subject, html, text


def event_body(visitor: str, purpose: str, visitor_email: str, lang: str = "en") -> tuple[str, str]:
    subject = tr(lang, "event_subject", v=visitor)
    parts = [f"<p>{escape(tr(lang, 'event_intro'))}</p><p>{escape(tr(lang, 'event_visitor', v=''))}<b>{escape(visitor)}</b></p>"]
    if purpose.strip():
        parts.append(_purpose_html(purpose, lang))
    if visitor_email:
        parts.append(f"<p>{escape(tr(lang, 'booked_contact', c=visitor_email))}</p>")
    return subject, "".join(parts)


def _package_links(photo_url: str, collect_url: str, lang: str) -> tuple[str, str]:
    html = text = ""
    if photo_url:
        html += f'<p><a href="{escape(photo_url)}">{escape(tr(lang, "pkg_photo"))}</a></p>'
        text += "\n" + tr(lang, "pkg_photo_text", u=photo_url)
    if collect_url:
        html += f'<p><a href="{escape(collect_url)}">{escape(tr(lang, "pkg_collect"))}</a></p>'
        text += "\n" + tr(lang, "pkg_collect_text", u=collect_url)
    return html, text


def delivery(
    carrier: str, now: datetime, note: str, handling: str, photo_url: str = "", collect_url: str = "", lang: str = "en"
) -> tuple[str, str, str]:
    who = carrier.strip() or tr(lang, "unknown_carrier")
    action = tr(lang, "pkg_wait" if handling == "wait_for_recipient" else "pkg_leave")
    note_html = f"<p>{escape(tr(lang, 'pkg_note', n=note))}</p>" if note.strip() else ""
    note_text = "\n" + tr(lang, "pkg_note", n=note) if note.strip() else ""
    links_html, links_text = _package_links(photo_url, collect_url, lang)
    subject = tr(lang, "pkg_subject", c=who)
    html = (
        f"<p>📦 <b>{escape(tr(lang, 'pkg_title'))}</b></p>"
        f"<p>{tr(lang, 'pkg_body', c=escape(who), t=_clock(now, lang))}</p>"
        f"{note_html}<p>{escape(action)}</p>{links_html}"
    )
    text = tr(lang, "pkg_text", c=who, t=_clock(now, lang), note=note_text, action=action, links=links_text)
    return subject, html, text


def delivery_reminder(
    carrier: str, delivered: datetime, handling: str, photo_url: str = "", collect_url: str = "", lang: str = "en"
) -> tuple[str, str, str]:
    who = carrier.strip() or tr(lang, "unknown_carrier")
    links_html, links_text = _package_links(photo_url, collect_url, lang)
    subject = tr(lang, "rem_subject", c=who)
    html = (
        f"<p>⏰ <b>{escape(tr(lang, 'rem_title'))}</b></p>"
        f"<p>{tr(lang, 'rem_body', c=escape(who), t=_clock(delivered, lang))}</p>{links_html}"
    )
    text = tr(lang, "rem_text", c=who, t=_clock(delivered, lang), links=links_text)
    return subject, html, text

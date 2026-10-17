"""REST-Schnittstelle für Besucherausweise, QR-Scans und Zutrittsprüfung."""
from __future__ import annotations

import base64
import binascii
import html
import logging
import threading
import time
from datetime import datetime

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, Response
from pydantic import BaseModel, Field

from .. import clock
from ..config import BACKEND_DIR, settings
from . import render
from .printer import BadgePrinter
from .settings import badge_settings
from .store import BadgeStore, PrintInfo, ScanEvent

log = logging.getLogger("smart-receptionist.badges")

store = BadgeStore(badge_settings.store_file)
printer = BadgePrinter(badge_settings)
router = APIRouter()

MAX_PHOTO_BYTES = 3_000_000


def require_key(x_api_key: str | None = Header(default=None)) -> None:
    if settings.api_key and x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="Ungültiger oder fehlender X-Api-Key")


def now_local() -> datetime:
    return clock.now(settings.timezone)


def purge() -> None:
    for badge_id in store.purge_expired(now_local()):
        printer.forget(badge_id)
        log.info("Ausweis %s abgelaufen – Daten gelöscht", badge_id)


def _purge_loop() -> None:
    while True:
        time.sleep(60)
        try:
            purge()
        except Exception:  # pragma: no cover - Hintergrund darf nie sterben
            log.exception("Aufräumen fehlgeschlagen")


threading.Thread(target=_purge_loop, name="badge-purge", daemon=True).start()


def base_url(request: Request) -> str:
    return badge_settings.public_url or str(request.base_url).rstrip("/")


# ------------------------------------------------------------------ Modelle
class BadgeRequest(BaseModel):
    visitor_name: str = Field(min_length=1, max_length=120)
    employee_id: str = ""
    employee_name: str = ""
    purpose: str = ""
    language: str = "de"
    # JPEG als Base64 – optional (ohne Foto gibt es einen Platzhalter mit Initialen)
    photo_jpeg_base64: str | None = None
    print: bool = True


class BadgePrint(BaseModel):
    sent: bool
    method: str = ""
    detail: str = ""
    job_id: int | None = None


class BadgeResponse(BaseModel):
    id: str
    qr_url: str
    valid_until: datetime
    zones: list[str]
    has_photo: bool
    print: BadgePrint
    # Vorschau der Karte (JPEG, Base64) zum Anzeigen auf dem Roboter – wird nirgends gespeichert
    preview_jpeg_base64: str


class AccessRequest(BaseModel):
    token: str
    door: str = Field(default="", max_length=80)


# --------------------------------------------------------------- Endpunkte
@router.post("/api/badges", response_model=BadgeResponse, dependencies=[Depends(require_key)])
def create_badge(req: BadgeRequest, request: Request) -> BadgeResponse:
    purge()
    visitor = " ".join(req.visitor_name.split())[:120]
    if not visitor:
        raise HTTPException(status_code=422, detail="visitor_name darf nicht leer sein")
    photo: bytes | None = None
    if req.photo_jpeg_base64:
        try:
            photo = base64.b64decode(req.photo_jpeg_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise HTTPException(status_code=422, detail="Foto ist kein gültiges Base64") from exc
        if len(photo) > MAX_PHOTO_BYTES:
            raise HTTPException(status_code=413, detail="Foto zu groß")
    now = now_local()
    badge = store.create(
        visitor_name=visitor,
        employee_id=req.employee_id,
        employee_name=req.employee_name.strip(),
        purpose=req.purpose.strip()[:200],
        company=settings.company_name,
        zones=list(badge_settings.zones),
        now=now,
        valid_hours=badge_settings.valid_hours,
        has_photo=photo is not None,
    )
    qr_url = f"{base_url(request)}/v/{badge.token}"
    card = render.render_card(
        visitor_name=badge.visitor_name,
        badge_id=badge.id,
        qr_url=qr_url,
        company=badge.company,
        host_name=badge.employee_name,
        valid_until=badge.valid_until,
        zones=badge.zones,
        photo_jpeg=photo,
        language=req.language,
    )
    preview = render.to_jpeg(card, max_width=640, quality=82)
    if req.print:
        pdf = render.to_pdf(render.render_print_page(card, req.language))
        result = printer.print_badge(badge.id, pdf, preview, now)
        info = PrintInfo(sent=result.sent, method=result.method, detail=result.detail, job_id=result.job_id)
        del pdf
    else:
        info = PrintInfo(sent=False, method="", detail="Druck nicht angefordert")
    store.update_print(badge.id, info)
    # Datenschutz: Foto und Karte (enthält das Foto) existieren ab hier nur noch in der Antwort
    del photo, card
    return BadgeResponse(
        id=badge.id,
        qr_url=qr_url,
        valid_until=badge.valid_until,
        zones=badge.zones,
        has_photo=badge.has_photo,
        print=BadgePrint(**info.model_dump()),
        preview_jpeg_base64=base64.b64encode(preview).decode("ascii"),
    )


@router.get("/api/badges", dependencies=[Depends(require_key)])
def list_badges() -> list[dict]:
    purge()
    return [
        b.model_dump(mode="json", exclude={"token"}) | {"scan_count": len(b.scans)}
        for b in store.active()
    ]


@router.post("/api/badges/{badge_id}/checkout", dependencies=[Depends(require_key)])
def checkout(badge_id: str) -> dict:
    """Besuch beendet: Ausweis ungültig machen und alle Daten löschen."""
    if not store.checkout(badge_id, now_local()):
        raise HTTPException(status_code=404, detail="Ausweis nicht gefunden (evtl. schon gelöscht)")
    printer.forget(badge_id)
    return {"ok": True, "deleted": badge_id}


@router.get("/api/badges/events", dependencies=[Depends(require_key)])
def badge_events() -> list[dict]:
    return store.events[:100]


@router.post("/api/access/check", dependencies=[Depends(require_key)])
def access_check(req: AccessRequest) -> dict:
    """Für Türleser: Darf dieser Ausweis in diese Zone? (Jede Prüfung wird protokolliert.)"""
    purge()
    badge = store.by_token(req.token.strip())
    if badge is None:
        return {"allowed": False, "reason": "Ausweis unbekannt oder abgelaufen"}
    door = req.door.strip()
    allowed = not door or any(door.lower() == z.lower() for z in badge.zones)
    store.record_scan(badge, ScanEvent(time=now_local(), kind="access", door=door, allowed=allowed))
    return {
        "allowed": allowed,
        "reason": "ok" if allowed else f"kein Zutritt zu „{door}“",
        "badge_id": badge.id,
        "visitor_name": badge.visitor_name,
        "valid_until": badge.valid_until.isoformat(timespec="minutes"),
    }


@router.get("/api/printer/status", dependencies=[Depends(require_key)])
def printer_status() -> dict:
    return printer.status()


@router.post("/api/printer/validate", dependencies=[Depends(require_key)])
def printer_validate() -> dict:
    """Prüft einen Druckauftrag beim Drucker, ohne zu drucken."""
    r = printer.validate()
    return {"ok": r.sent, "method": r.method, "detail": r.detail}


@router.post("/api/printer/test", dependencies=[Depends(require_key)])
def printer_test(request: Request) -> dict:
    """Druckt einen Beispielausweis (ohne Foto, ohne gespeicherte Daten)."""
    now = now_local()
    card = render.render_card(
        visitor_name="Test Besucher",
        badge_id="V-TEST-0000",
        qr_url=f"{base_url(request)}/v/test",
        company=settings.company_name,
        host_name="Empfang",
        valid_until=now.replace(hour=23, minute=59, second=0, microsecond=0),
        zones=list(badge_settings.zones),
        photo_jpeg=None,
    )
    result = printer.print_badge("V-TEST-0000", render.to_pdf(render.render_print_page(card)), render.to_jpeg(card, 640), now)
    return {"sent": result.sent, "method": result.method, "detail": result.detail, "job_id": result.job_id}


@router.get("/api/printer/outbox", dependencies=[Depends(require_key)])
def printer_outbox() -> list[dict]:
    """Nur Demo-Modus (SR_PRINTER_METHODS=mock): was „gedruckt“ worden wäre."""
    return [
        {
            "time": m.time.isoformat(timespec="seconds"),
            "badge_id": m.badge_id,
            "preview_jpeg_base64": base64.b64encode(m.preview_jpeg).decode("ascii"),
        }
        for m in printer.mock_outbox
    ]


@router.get("/badges", response_class=HTMLResponse, include_in_schema=False)
def badges_page() -> HTMLResponse:
    return HTMLResponse((BACKEND_DIR / "app" / "static" / "badges.html").read_text(encoding="utf-8"))


@router.get("/v/{token}", response_class=HTMLResponse, include_in_schema=False)
def scan_page(token: str) -> HTMLResponse:
    """Ziel des QR-Codes. Zeigt, ob der Ausweis gültig ist, und protokolliert den Scan."""
    purge()
    badge = store.by_token(token)
    if badge is None:
        body = (
            "<div class='state bad'>✖ Ungültig</div>"
            "<p>Dieser Ausweis ist abgelaufen oder wurde zurückgegeben. Alle Daten dazu wurden gelöscht.</p>"
        )
    else:
        now = now_local()
        store.record_scan(badge, ScanEvent(time=now, kind="scan"))
        e = html.escape
        body = (
            "<div class='state ok'>✔ Gültiger Besucherausweis</div>"
            f"<h1>{e(badge.visitor_name)}</h1>"
            f"<table><tr><th>Ausweis-ID</th><td>{e(badge.id)}</td></tr>"
            f"<tr><th>Gastgeber</th><td>{e(badge.employee_name or '–')}</td></tr>"
            f"<tr><th>Ausgestellt</th><td>{badge.created_at:%d.%m.%Y %H:%M}</td></tr>"
            f"<tr><th>Gültig bis</th><td>{badge.valid_until:%d.%m.%Y %H:%M}</td></tr>"
            f"<tr><th>Zutritt</th><td>{e(' · '.join(badge.zones))}</td></tr>"
            f"<tr><th>Scans</th><td>{len(badge.scans)}</td></tr></table>"
            "<p class='note'>Dieser Scan wurde protokolliert. Daten werden nach dem Besuch automatisch gelöscht.</p>"
        )
    page = f"""<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Besucherausweis</title>
<style>body{{font-family:system-ui,sans-serif;background:#0b1e3f;color:#14213d;margin:0;padding:16px}}
.card{{background:#fff;border-radius:16px;max-width:480px;margin:24px auto;padding:20px 22px}}
.state{{font-weight:700;font-size:20px;padding:10px 14px;border-radius:10px;margin-bottom:12px}}
.ok{{background:#e3f8f5;color:#0f7a6e}}.bad{{background:#fde8e8;color:#a61b1b}}
h1{{margin:6px 0 12px;font-size:26px}}table{{width:100%;border-collapse:collapse}}
th{{text-align:left;color:#606c80;font-weight:500;padding:6px 8px 6px 0;width:40%}}td{{padding:6px 0;font-weight:600}}
.note{{color:#606c80;font-size:13px;margin-top:14px}}</style></head>
<body><div class="card"><div style="color:#606c80;font-size:13px">{html.escape(settings.company_name)} · Besucherausweis</div>{body}</div></body></html>"""
    return HTMLResponse(page)


@router.get("/api/badges/{badge_id}/qr.svg", dependencies=[Depends(require_key)], include_in_schema=False)
def badge_qr(badge_id: str, request: Request) -> Response:
    badge = store.get(badge_id)
    if badge is None:
        raise HTTPException(status_code=404, detail="Ausweis nicht gefunden")
    import segno

    svg = segno.make(f"{base_url(request)}/v/{badge.token}", error="m").svg_inline(scale=5, dark="#0b1e3f")
    return Response(content=svg, media_type="image/svg+xml")

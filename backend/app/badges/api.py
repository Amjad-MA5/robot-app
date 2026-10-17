"""REST API for visitor badges, QR scans and access checks."""
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
        raise HTTPException(status_code=401, detail="Invalid or missing X-Api-Key")


def now_local() -> datetime:
    return clock.now(settings.timezone)


def purge() -> None:
    for badge_id in store.purge_expired(now_local()):
        printer.forget(badge_id)
        log.info("Badge %s expired - data deleted", badge_id)


def _purge_loop() -> None:
    while True:
        time.sleep(60)
        try:
            purge()
        except Exception:  # pragma: no cover - the background thread must never die
            log.exception("Badge cleanup failed")


threading.Thread(target=_purge_loop, name="badge-purge", daemon=True).start()


def base_url(request: Request) -> str:
    return badge_settings.public_url or str(request.base_url).rstrip("/")


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


# ------------------------------------------------------------------- Models
class BadgeRequest(BaseModel):
    visitor_name: str = Field(min_length=1, max_length=120)
    employee_id: str = ""
    employee_name: str = ""
    purpose: str = ""
    language: str = "en"
    # JPEG as Base64 - optional (without a photo the card shows the initials)
    photo_jpeg_base64: str | None = None
    # True = the backend sends the badge to the printer itself
    print: bool = True
    # True = return the print-ready PDF so the robot can send it to the printer (backend in the cloud)
    return_pdf: bool = False


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
    # Card preview (JPEG, Base64) for the robot's screen - never stored
    preview_jpeg_base64: str
    # Print-ready A4 page (PDF, Base64), only with return_pdf=true - never stored
    print_pdf_base64: str | None = None


class AccessRequest(BaseModel):
    token: str
    door: str = Field(default="", max_length=80)


class PrinterTestRequest(BaseModel):
    print: bool = True
    return_pdf: bool = False


# ---------------------------------------------------------------- Endpoints
@router.post("/api/badges", response_model=BadgeResponse, dependencies=[Depends(require_key)])
def create_badge(req: BadgeRequest, request: Request) -> BadgeResponse:
    purge()
    visitor = " ".join(req.visitor_name.split())[:120]
    if not visitor:
        raise HTTPException(status_code=422, detail="visitor_name must not be empty")
    photo: bytes | None = None
    if req.photo_jpeg_base64:
        try:
            photo = base64.b64decode(req.photo_jpeg_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise HTTPException(status_code=422, detail="Photo is not valid Base64") from exc
        if len(photo) > MAX_PHOTO_BYTES:
            raise HTTPException(status_code=413, detail="Photo too large")
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
    pdf: bytes | None = None
    if req.print or req.return_pdf:
        pdf = render.to_pdf(render.render_print_page(card, req.language))
    if req.print and pdf is not None:
        result = printer.print_badge(badge.id, pdf, preview, now)
        info = PrintInfo(sent=result.sent, method=result.method, detail=result.detail, job_id=result.job_id)
    else:
        info = PrintInfo(sent=False, method="", detail="sent to the robot for printing" if req.return_pdf else "printing not requested")
    store.update_print(badge.id, info)
    # Privacy: from here on, the photo and the card (which contains it) only exist in the response
    del photo, card
    return BadgeResponse(
        id=badge.id,
        qr_url=qr_url,
        valid_until=badge.valid_until,
        zones=badge.zones,
        has_photo=badge.has_photo,
        print=BadgePrint(**info.model_dump()),
        preview_jpeg_base64=_b64(preview),
        print_pdf_base64=_b64(pdf) if req.return_pdf and pdf is not None else None,
    )


@router.post("/api/badges/{badge_id}/printed", dependencies=[Depends(require_key)])
def badge_printed(badge_id: str, info: BadgePrint) -> dict:
    """The robot reports the result after sending the badge to the printer itself."""
    if store.get(badge_id) is None:
        raise HTTPException(status_code=404, detail="Badge not found (maybe already deleted)")
    store.update_print(badge_id, PrintInfo(**info.model_dump()))
    return {"ok": True}


@router.get("/api/badges", dependencies=[Depends(require_key)])
def list_badges() -> list[dict]:
    purge()
    return [
        b.model_dump(mode="json", exclude={"token"}) | {"scan_count": len(b.scans)}
        for b in store.active()
    ]


@router.post("/api/badges/{badge_id}/checkout", dependencies=[Depends(require_key)])
def checkout(badge_id: str) -> dict:
    """Visit finished: invalidate the badge and delete all of its data."""
    if not store.checkout(badge_id, now_local()):
        raise HTTPException(status_code=404, detail="Badge not found (maybe already deleted)")
    printer.forget(badge_id)
    return {"ok": True, "deleted": badge_id}


@router.get("/api/badges/events", dependencies=[Depends(require_key)])
def badge_events() -> list[dict]:
    return store.events[:100]


@router.post("/api/access/check", dependencies=[Depends(require_key)])
def access_check(req: AccessRequest) -> dict:
    """For door readers: may this badge enter this zone? (Every check is logged.)"""
    purge()
    badge = store.by_token(req.token.strip())
    if badge is None:
        return {"allowed": False, "reason": "badge unknown or expired"}
    door = req.door.strip()
    allowed = not door or any(door.lower() == z.lower() for z in badge.zones)
    store.record_scan(badge, ScanEvent(time=now_local(), kind="access", door=door, allowed=allowed))
    return {
        "allowed": allowed,
        "reason": "ok" if allowed else f"no access to \"{door}\"",
        "badge_id": badge.id,
        "visitor_name": badge.visitor_name,
        "valid_until": badge.valid_until.isoformat(timespec="minutes"),
    }


@router.get("/api/printer/status", dependencies=[Depends(require_key)])
def printer_status() -> dict:
    return printer.status()


@router.post("/api/printer/validate", dependencies=[Depends(require_key)])
def printer_validate() -> dict:
    """Asks the printer whether it would accept a badge job - without printing."""
    r = printer.validate()
    return {"ok": r.sent, "method": r.method, "detail": r.detail}


@router.post("/api/printer/test", dependencies=[Depends(require_key)])
def printer_test(request: Request, req: PrinterTestRequest | None = None) -> dict:
    """Prints a sample badge (no photo, nothing stored)."""
    req = req or PrinterTestRequest()
    now = now_local()
    card = render.render_card(
        visitor_name="Test Visitor",
        badge_id="V-TEST-0000",
        qr_url=f"{base_url(request)}/v/test",
        company=settings.company_name,
        host_name="Reception",
        valid_until=now.replace(hour=23, minute=59, second=0, microsecond=0),
        zones=list(badge_settings.zones),
        photo_jpeg=None,
    )
    pdf = render.to_pdf(render.render_print_page(card))
    out: dict = {"sent": False, "method": "", "detail": "printing not requested", "job_id": None}
    if req.print:
        result = printer.print_badge("V-TEST-0000", pdf, render.to_jpeg(card, 640), now)
        out = {"sent": result.sent, "method": result.method, "detail": result.detail, "job_id": result.job_id}
    if req.return_pdf:
        out["print_pdf_base64"] = _b64(pdf)
    return out


@router.get("/api/printer/outbox", dependencies=[Depends(require_key)])
def printer_outbox() -> list[dict]:
    """Demo mode only (SR_PRINTER_METHODS=mock): what would have been printed."""
    return [
        {
            "time": m.time.isoformat(timespec="seconds"),
            "badge_id": m.badge_id,
            "preview_jpeg_base64": _b64(m.preview_jpeg),
        }
        for m in printer.mock_outbox
    ]


@router.get("/badges", response_class=HTMLResponse, include_in_schema=False)
def badges_page() -> HTMLResponse:
    return HTMLResponse((BACKEND_DIR / "app" / "static" / "badges.html").read_text(encoding="utf-8"))


@router.get("/v/{token}", response_class=HTMLResponse, include_in_schema=False)
def scan_page(token: str) -> HTMLResponse:
    """Target of the QR code. Shows whether the badge is valid and logs the scan."""
    purge()
    badge = store.by_token(token)
    if badge is None:
        body = (
            "<div class='state bad'>✖ Invalid</div>"
            "<p>This badge has expired or was returned. All of its data has been deleted.</p>"
        )
    else:
        now = now_local()
        store.record_scan(badge, ScanEvent(time=now, kind="scan"))
        e = html.escape
        body = (
            "<div class='state ok'>✔ Valid visitor badge</div>"
            f"<h1>{e(badge.visitor_name)}</h1>"
            f"<table><tr><th>Badge ID</th><td>{e(badge.id)}</td></tr>"
            f"<tr><th>Host</th><td>{e(badge.employee_name or '–')}</td></tr>"
            f"<tr><th>Issued</th><td>{badge.created_at:%d.%m.%Y %H:%M}</td></tr>"
            f"<tr><th>Valid until</th><td>{badge.valid_until:%d.%m.%Y %H:%M}</td></tr>"
            f"<tr><th>Access</th><td>{e(' · '.join(badge.zones))}</td></tr>"
            f"<tr><th>Scans</th><td>{len(badge.scans)}</td></tr></table>"
            "<p class='note'>This scan was logged. All data is deleted automatically after the visit.</p>"
        )
    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Visitor badge</title>
<style>body{{font-family:system-ui,sans-serif;background:#0b1e3f;color:#14213d;margin:0;padding:16px}}
.card{{background:#fff;border-radius:16px;max-width:480px;margin:24px auto;padding:20px 22px}}
.state{{font-weight:700;font-size:20px;padding:10px 14px;border-radius:10px;margin-bottom:12px}}
.ok{{background:#e3f8f5;color:#0f7a6e}}.bad{{background:#fde8e8;color:#a61b1b}}
h1{{margin:6px 0 12px;font-size:26px}}table{{width:100%;border-collapse:collapse}}
th{{text-align:left;color:#606c80;font-weight:500;padding:6px 8px 6px 0;width:40%}}td{{padding:6px 0;font-weight:600}}
.note{{color:#606c80;font-size:13px;margin-top:14px}}</style></head>
<body><div class="card"><div style="color:#606c80;font-size:13px">{html.escape(settings.company_name)} · Visitor badge</div>{body}</div></body></html>"""
    return HTMLResponse(page)


@router.get("/api/badges/{badge_id}/qr.svg", dependencies=[Depends(require_key)], include_in_schema=False)
def badge_qr(badge_id: str, request: Request) -> Response:
    badge = store.get(badge_id)
    if badge is None:
        raise HTTPException(status_code=404, detail="Badge not found")
    import segno

    svg = segno.make(f"{base_url(request)}/v/{badge.token}", error="m").svg_inline(scale=5, dark="#0b1e3f")
    return Response(content=svg, media_type="image/svg+xml")

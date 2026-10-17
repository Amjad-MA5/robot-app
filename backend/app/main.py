"""Smart Receptionist – Backend für den Orionstar GreetingBot Nova.

Start (im backend-Ordner):
    uvicorn app.main:app --host 0.0.0.0 --port 8080
"""
from __future__ import annotations

import json
import logging
from collections import deque
from datetime import datetime, timedelta

import segno
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel

from . import clock, directory, messages, scheduling
from .config import BACKEND_DIR, Settings, settings
from .models import (
    AppointmentRequest,
    AppointmentResponse,
    AppVersion,
    ArrivalRequest,
    ArrivalResponse,
    Availability,
    BusyBlock,
    Employee,
    EmployeeMatch,
    NotificationResult,
    Slot,
    WalkInRequest,
    WalkInResponse,
)
from .providers.base import Provider, ProviderError

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("smart-receptionist")

APK_FILE_NAME = "smart-receptionist.apk"


def build_provider(cfg: Settings) -> Provider:
    if cfg.mode == "graph":
        from .providers.graph import GraphProvider

        return GraphProvider(cfg)
    from .providers.mock import MockProvider

    return MockProvider(cfg)


provider: Provider = build_provider(settings)
visit_log: deque[dict] = deque(maxlen=200)

app = FastAPI(title="Smart Receptionist Backend", version="1.0.0")


# ----------------------------------------------------------------- Hilfsfunktionen
def require_key(x_api_key: str | None = Header(default=None)) -> None:
    if settings.api_key and x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="Ungültiger oder fehlender X-Api-Key")


def now_local() -> datetime:
    return clock.now(settings.timezone)


def get_employee_or_404(employee_id: str) -> Employee:
    try:
        employee = provider.get_employee(employee_id)
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    if employee is None:
        raise HTTPException(status_code=404, detail=f"Mitarbeiter '{employee_id}' nicht gefunden")
    return employee


def busy_range(employee: Employee, days: int | None = None) -> list[BusyBlock]:
    now = now_local()
    start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + timedelta(days=(days or settings.search_days) + 1)
    try:
        return provider.get_busy(employee, start, end)
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


def log_visit(kind: str, visitor: str, employee: Employee, result: str) -> None:
    visit_log.appendleft(
        {
            "time": now_local().isoformat(timespec="seconds"),
            "type": kind,
            "visitor": visitor,
            "employee": employee.name,
            "result": result,
        }
    )


def do_notify(employee: Employee, subject: str, html: str, text: str) -> NotificationResult:
    try:
        return provider.notify(employee, subject, html, text)
    except ProviderError as exc:
        log.warning("Benachrichtigung fehlgeschlagen: %s", exc)
        return NotificationResult(sent=False, detail=str(exc))


def clean_name(raw: str) -> str:
    name = " ".join(raw.split())
    if not name:
        raise HTTPException(status_code=422, detail="visitor_name darf nicht leer sein")
    return name[:120]


# ------------------------------------------------------------------- Öffentlich
@app.get("/", response_class=HTMLResponse, include_in_schema=False)
def index() -> HTMLResponse:
    return HTMLResponse((BACKEND_DIR / "app" / "static" / "index.html").read_text(encoding="utf-8"))


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "mode": settings.mode,
        "company": settings.company_name,
        "time": now_local().isoformat(timespec="seconds"),
    }


def _apk_info() -> AppVersion:
    apk = settings.apk_dir / APK_FILE_NAME
    if not apk.exists():
        return AppVersion(available=False)
    meta_file = settings.apk_dir / "latest.json"
    meta = json.loads(meta_file.read_text(encoding="utf-8")) if meta_file.exists() else {}
    return AppVersion(
        available=True,
        version_code=int(meta.get("version_code", 0)),
        version_name=str(meta.get("version_name", "")),
        download_path=f"/download/{APK_FILE_NAME}",
        size_bytes=apk.stat().st_size,
    )


@app.get("/api/app/latest", response_model=AppVersion)
def app_latest() -> AppVersion:
    return _apk_info()


@app.get(f"/download/{APK_FILE_NAME}", include_in_schema=False)
def download_apk() -> FileResponse:
    apk = settings.apk_dir / APK_FILE_NAME
    if not apk.exists():
        raise HTTPException(status_code=404, detail="Noch keine APK im Ordner backend/apk hinterlegt")
    return FileResponse(apk, media_type="application/vnd.android.package-archive", filename=APK_FILE_NAME)


@app.get("/download/qr.svg", include_in_schema=False)
def download_qr(request: Request) -> Response:
    url = str(request.base_url).rstrip("/") + f"/download/{APK_FILE_NAME}"
    svg = segno.make(url, error="m").svg_inline(scale=6, dark="#14213d")
    return Response(content=svg, media_type="image/svg+xml")


# --------------------------------------------------------------- Roboter-API
@app.get("/api/status", dependencies=[Depends(require_key)])
def api_status() -> dict:
    return {
        "mode": settings.mode,
        "company": settings.company_name,
        "time": now_local().isoformat(timespec="seconds"),
        "work_hours": f"{settings.work_start:%H:%M}–{settings.work_end:%H:%M}",
        "work_days": list(settings.work_days),
        "provider": provider.status(),
        "app": _apk_info().model_dump(),
    }


@app.get("/api/employees", response_model=list[EmployeeMatch], dependencies=[Depends(require_key)])
def employees(q: str = Query(default="", max_length=120), limit: int = Query(default=5, ge=1, le=200)) -> list[EmployeeMatch]:
    try:
        all_employees = provider.list_employees()
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    if q.strip():
        return directory.search(all_employees, q, limit=limit)
    ordered = sorted(all_employees, key=lambda e: e.name.split()[-1].lower() if e.name else "")
    return [EmployeeMatch(**e.model_dump(), score=100.0) for e in ordered[:limit]]


@app.get("/api/employees/{employee_id}/availability", response_model=Availability, dependencies=[Depends(require_key)])
def availability(employee_id: str) -> Availability:
    employee = get_employee_or_404(employee_id)
    busy = busy_range(employee)
    now = now_local()
    today_slots = scheduling.find_free_slots(busy, now, settings, days=1, max_slots=3, max_per_day=3)
    free_now = scheduling.is_free_now(busy, now, settings)
    return Availability(
        employee=employee,
        free_now=free_now,
        available_today=free_now or bool(today_slots),
        today_slots=today_slots,
        next_slots=scheduling.find_free_slots(busy, now, settings, start_day_offset=1),
    )


@app.get("/api/employees/{employee_id}/slots", response_model=list[Slot], dependencies=[Depends(require_key)])
def slots(
    employee_id: str,
    days: int = Query(default=0, ge=0, le=30),
    max: int = Query(default=0, ge=0, le=10),
    from_day: int = Query(default=0, ge=0, le=30),
) -> list[Slot]:
    employee = get_employee_or_404(employee_id)
    search_days = days or settings.search_days
    busy = busy_range(employee, from_day + search_days)
    return scheduling.find_free_slots(
        busy, now_local(), settings, days=search_days, max_slots=max or settings.max_slots, start_day_offset=from_day
    )


@app.post("/api/visits/arrival", response_model=ArrivalResponse, dependencies=[Depends(require_key)])
def visit_arrival(req: ArrivalRequest) -> ArrivalResponse:
    """Szenario 1: Besucher hat einen Termin → Mitarbeiter sofort benachrichtigen."""
    visitor = clean_name(req.visitor_name)
    employee = get_employee_or_404(req.employee_id)
    now = now_local()
    appointment = None
    try:
        busy = provider.get_busy(employee, now - timedelta(hours=2), now + timedelta(hours=3))
        appointment = scheduling.current_appointment(busy, now, settings)
    except ProviderError as exc:
        # Kalender ist nur Zusatzinfo – die Benachrichtigung soll trotzdem rausgehen.
        log.info("Kalender für Ankunft nicht lesbar: %s", exc)
    subject, html, text = messages.arrival(visitor, now, appointment, req.purpose)
    result = do_notify(employee, subject, html, text)
    log_visit("Termin", visitor, employee, "benachrichtigt" if result.sent else f"Fehler: {result.detail}")
    return ArrivalResponse(employee=employee, notification=result, appointment=appointment)


@app.post("/api/visits/walk-in", response_model=WalkInResponse, dependencies=[Depends(require_key)])
def visit_walk_in(req: WalkInRequest) -> WalkInResponse:
    """Szenario 2: kein Termin, möchte aber sprechen → bei freier Zeit heute Mitarbeiter informieren.
    Ist heute nichts mehr frei, kommen Terminvorschläge für die Folgetage zurück (Szenario 3)."""
    visitor = clean_name(req.visitor_name)
    employee = get_employee_or_404(req.employee_id)
    busy = busy_range(employee)
    now = now_local()
    free_now = scheduling.is_free_now(busy, now, settings)
    today_slots = scheduling.find_free_slots(busy, now, settings, days=1, max_slots=3, max_per_day=3)
    available_today = free_now or bool(today_slots)
    if available_today:
        first_free = today_slots[0].start if today_slots else None
        subject, html, text = messages.walk_in(visitor, now, free_now, first_free, req.purpose)
        result = do_notify(employee, subject, html, text)
        log_visit("Ohne Termin", visitor, employee, "benachrichtigt" if result.sent else f"Fehler: {result.detail}")
        return WalkInResponse(
            employee=employee,
            free_now=free_now,
            available_today=True,
            notified=result.sent,
            notification=result,
            today_slots=today_slots,
        )
    next_slots = scheduling.find_free_slots(busy, now, settings, start_day_offset=1)
    log_visit("Ohne Termin", visitor, employee, "heute nicht verfügbar – Terminvorschläge")
    return WalkInResponse(
        employee=employee,
        free_now=False,
        available_today=False,
        notified=False,
        next_slots=next_slots,
    )


@app.post("/api/appointments", response_model=AppointmentResponse, dependencies=[Depends(require_key)])
def create_appointment(req: AppointmentRequest) -> AppointmentResponse:
    """Szenario 3: gewählten freien Termin im Kalender des Mitarbeiters eintragen."""
    visitor = clean_name(req.visitor_name)
    employee = get_employee_or_404(req.employee_id)
    start = req.start if req.start.tzinfo else req.start.replace(tzinfo=settings.timezone)
    end = req.end if req.end.tzinfo else req.end.replace(tzinfo=settings.timezone)
    start, end = start.astimezone(settings.timezone), end.astimezone(settings.timezone)
    if end <= start or end - start > timedelta(hours=4):
        raise HTTPException(status_code=422, detail="Ungültiger Zeitraum")
    if start < now_local() - timedelta(minutes=5):
        raise HTTPException(status_code=422, detail="Termin liegt in der Vergangenheit")
    try:
        busy = provider.get_busy(employee, start - timedelta(minutes=1), end + timedelta(minutes=1))
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    if any(
        b.status.lower() in scheduling.BLOCKING_STATUS and b.start < end and b.end > start for b in busy
    ):
        raise HTTPException(status_code=409, detail="Der Termin ist inzwischen nicht mehr frei")
    event_subject, event_html = messages.event_body(visitor, req.purpose, req.visitor_email)
    try:
        event_id = provider.create_appointment(employee, start, end, event_subject, event_html)
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    subject, html, text = messages.appointment_booked(visitor, start, end, req.purpose, req.visitor_email)
    result = do_notify(employee, subject, html, text)
    log_visit("Neuer Termin", visitor, employee, f"{start:%d.%m. %H:%M} eingetragen")
    return AppointmentResponse(
        created=True, employee=employee, slot=Slot(start=start, end=end), event_id=event_id, notification=result
    )


# ----------------------------------------------------------- Dashboard / Admin
@app.get("/api/visits/log", dependencies=[Depends(require_key)])
def visits_log() -> list[dict]:
    return list(visit_log)


@app.get("/api/mock/inbox", dependencies=[Depends(require_key)])
def mock_inbox() -> list[dict]:
    return list(getattr(provider, "inbox", []))


class TestNotifyRequest(BaseModel):
    employee_id: str


@app.post("/api/notify/test", response_model=NotificationResult, dependencies=[Depends(require_key)])
def notify_test(req: TestNotifyRequest) -> NotificationResult:
    employee = get_employee_or_404(req.employee_id)
    text = "Testnachricht vom Smart Receptionist – die Verbindung funktioniert."
    return do_notify(employee, "Smart Receptionist: Test", f"<p>✅ {text}</p>", text)


@app.post("/api/auth/device-login", dependencies=[Depends(require_key)])
def auth_device_login() -> dict:
    start = getattr(provider, "start_device_login", None)
    if start is None:
        raise HTTPException(status_code=400, detail="Anmeldung nur im Modus SR_MODE=graph nötig")
    try:
        return start()
    except ProviderError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/auth/logout", dependencies=[Depends(require_key)])
def auth_logout() -> dict:
    logout = getattr(provider, "logout", None)
    if logout is None:
        raise HTTPException(status_code=400, detail="Nur im Modus SR_MODE=graph verfügbar")
    logout()
    return {"ok": True}

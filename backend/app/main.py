"""Smart Receptionist – Backend für den Orionstar GreetingBot Nova.

Start (im backend-Ordner):
    uvicorn app.main:app --host 0.0.0.0 --port 8080
"""
from __future__ import annotations

import asyncio
import json
import logging
from collections import deque
from contextlib import asynccontextmanager
from datetime import datetime, timedelta

import segno
from html import escape
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel

from . import clock, directory, messages, scheduling
from .packages import PackageLog
from .config import BACKEND_DIR, Settings, settings
from .models import (
    AppointmentRequest,
    AppointmentResponse,
    AppVersion,
    ArrivalRequest,
    ArrivalResponse,
    Availability,
    DeliveryRequest,
    DeliveryResponse,
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
visit_log: deque[dict] = deque(maxlen=1000)


def _load_visit_log() -> None:
    """Letzte Besuche aus der Datei laden, damit das Protokoll einen Neustart überlebt."""
    try:
        lines = settings.visit_log_file.read_text(encoding="utf-8").splitlines()
    except OSError:
        return
    for line in lines[-visit_log.maxlen:]:
        try:
            visit_log.appendleft(json.loads(line))
        except ValueError:
            continue


_load_visit_log()

package_log = PackageLog(settings.package_photo_dir)


def send_due_reminders(base_url: str = "") -> int:
    """Erinnert Empfänger an Pakete, die nach SR_PACKAGE_REMINDER_MIN noch nicht abgeholt wurden."""
    sent = 0
    for pkg in package_log.due_reminders(now_local(), settings.package_reminder_min):
        employee = provider.get_employee(pkg.employee_id)
        if employee is None:
            pkg.reminded = True
            continue
        photo_url = f"{base_url}/packages/{pkg.id}/photo" if pkg.has_photo and base_url else ""
        collect_url = f"{base_url}/packages/{pkg.id}" if base_url else ""
        subject, html, text = messages.delivery_reminder(pkg.carrier, pkg.created, pkg.handling, photo_url, collect_url, settings.language)
        result = do_notify(employee, subject, html, text)
        pkg.reminded = result.sent or pkg.reminded
        sent += int(result.sent)
    return sent


@asynccontextmanager
async def lifespan(_: FastAPI):
    async def reminder_loop() -> None:
        while True:
            await asyncio.sleep(60)
            try:
                await asyncio.to_thread(send_due_reminders, public_base_url[0])
            except Exception:  # noqa: BLE001 - Erinnerungen dürfen den Server nie stoppen
                log.exception("Paket-Erinnerung fehlgeschlagen")

    task = asyncio.create_task(reminder_loop())
    yield
    task.cancel()


# Letzte bekannte Adresse, unter der das Backend erreichbar war (für Links in Erinnerungen)
public_base_url: list[str] = [""]

app = FastAPI(title="Smart Receptionist Backend", version="1.0.0", lifespan=lifespan)


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
    entry = {
        "time": now_local().isoformat(timespec="seconds"),
        "type": kind,
        "visitor": visitor,
        "employee": employee.name,
        "result": result,
    }
    visit_log.appendleft(entry)
    try:
        settings.visit_log_file.parent.mkdir(parents=True, exist_ok=True)
        with settings.visit_log_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError as exc:
        log.warning("Besucherprotokoll konnte nicht gespeichert werden: %s", exc)


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
    subject, html, text = messages.arrival(visitor, now, appointment, req.purpose, settings.language)
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
        subject, html, text = messages.walk_in(visitor, now, free_now, first_free, req.purpose, settings.language)
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


@app.post("/api/visits/delivery", response_model=DeliveryResponse, dependencies=[Depends(require_key)])
def visit_delivery(req: DeliveryRequest, request: Request) -> DeliveryResponse:
    """Paketbote am Empfang → Empfänger benachrichtigen, Roboter sagt dem Boten, was zu tun ist."""
    employee = get_employee_or_404(req.employee_id)
    carrier = " ".join(req.carrier.split())[:60]
    if req.signature_required is None:
        handling = settings.delivery_handling
    else:
        handling = "wait_for_recipient" if req.signature_required else "leave_at_desk"
    base = str(request.base_url).rstrip("/")
    public_base_url[0] = base
    pkg = package_log.add(now_local(), employee.id, employee.name, carrier, handling)
    if req.photo_base64 and not package_log.save_photo(pkg, req.photo_base64):
        log.info("Paketfoto verworfen (kein gültiges JPEG)")
    photo_url = f"{base}/packages/{pkg.id}/photo" if pkg.has_photo else ""
    subject, html, text = messages.delivery(carrier, now_local(), req.note, handling, photo_url, f"{base}/packages/{pkg.id}", settings.language)
    result = do_notify(employee, subject, html, text)
    log_visit("Lieferung", carrier or "Zusteller", employee, "benachrichtigt" if result.sent else f"Fehler: {result.detail}")
    return DeliveryResponse(employee=employee, notification=result, handling=handling, package_id=pkg.id)


@app.get("/api/packages", dependencies=[Depends(require_key)])
def list_packages() -> list[dict]:
    return [p.as_dict() for p in package_log.list()]


@app.post("/api/packages/{package_id}/collected", dependencies=[Depends(require_key)])
def package_collected(package_id: str) -> dict:
    pkg = package_log.mark_collected(package_id)
    if pkg is None:
        raise HTTPException(status_code=404, detail="Paket nicht gefunden")
    return pkg.as_dict()


# Öffentliche Links aus der Benachrichtigung (die ID ist nicht erratbar)
@app.get("/packages/{package_id}/photo", include_in_schema=False)
def package_photo(package_id: str) -> FileResponse:
    pkg = package_log.get(package_id)
    if pkg is None or not pkg.has_photo:
        raise HTTPException(status_code=404, detail="Kein Foto vorhanden")
    return FileResponse(package_log.photo_path(package_id), media_type="image/jpeg")


@app.get("/packages/{package_id}", response_class=HTMLResponse, include_in_schema=False)
def package_page(package_id: str) -> HTMLResponse:
    pkg = package_log.get(package_id)
    if pkg is None:
        raise HTTPException(status_code=404, detail="Paket nicht gefunden")
    lang = settings.language
    if pkg.status == "collected":
        body = f"<p>✅ {escape(messages.tr(lang, 'page_collected'))}</p>"
    else:
        who = pkg.carrier or messages.tr(lang, "unknown_carrier")
        body = (
            f"<p>📦 {escape(messages.tr(lang, 'page_package', c=who, e=pkg.employee_name))}</p>"
            f'<form method="post" action="/packages/{pkg.id}/collect"><button style="font-size:1.2em;padding:.6em 1em">'
            f"{escape(messages.tr(lang, 'page_button'))}</button></form>"
        )
    return HTMLResponse(f"<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:1.5em'>{body}")


@app.post("/packages/{package_id}/collect", response_class=HTMLResponse, include_in_schema=False)
def package_collect_form(package_id: str) -> HTMLResponse:
    if package_log.mark_collected(package_id) is None:
        raise HTTPException(status_code=404, detail="Paket nicht gefunden")
    return HTMLResponse("<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:1.5em'><p>✅ " + escape(messages.tr(settings.language, "page_thanks")) + "</p>")


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
    event_subject, event_html = messages.event_body(visitor, req.purpose, req.visitor_email, settings.language)
    try:
        event_id = provider.create_appointment(employee, start, end, event_subject, event_html)
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    subject, html, text = messages.appointment_booked(visitor, start, end, req.purpose, req.visitor_email, settings.language)
    result = do_notify(employee, subject, html, text)
    log_visit("Neuer Termin", visitor, employee, f"{start:%d.%m. %H:%M} eingetragen")
    return AppointmentResponse(
        created=True, employee=employee, slot=Slot(start=start, end=end), event_id=event_id, notification=result
    )


# ----------------------------------------------------------- Dashboard / Admin
@app.get("/api/visits/log", dependencies=[Depends(require_key)])
def visits_log(
    day: str = Query("", alias="date", description="YYYY-MM-DD, leer = alle"),
    q: str = Query("", description="Suche in Besucher und Mitarbeiter"),
) -> list[dict]:
    needle = q.strip().lower()
    return [
        v
        for v in visit_log
        if (not day or v["time"].startswith(day))
        and (not needle or needle in v["visitor"].lower() or needle in v["employee"].lower())
    ]


@app.get("/api/mock/inbox", dependencies=[Depends(require_key)])
def mock_inbox() -> list[dict]:
    return list(getattr(provider, "inbox", []))


class TestNotifyRequest(BaseModel):
    employee_id: str


@app.post("/api/notify/test", response_model=NotificationResult, dependencies=[Depends(require_key)])
def notify_test(req: TestNotifyRequest) -> NotificationResult:
    employee = get_employee_or_404(req.employee_id)
    text = messages.tr(settings.language, "test_text")
    return do_notify(employee, messages.tr(settings.language, "test_subject"), f"<p>✅ {text}</p>", text)


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


# ------------------------------------------------------------ Visitor badges
from .badges.api import router as badges_router  # noqa: E402

app.include_router(badges_router)

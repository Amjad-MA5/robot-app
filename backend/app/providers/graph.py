"""Microsoft 365 directory and calendar integration using Microsoft Graph.

Two authentication modes (SR_GRAPH_AUTH):

* ``delegated``: The backend signs in once as the reception mailbox using a
  device code. Delegated permissions: User.Read, User.ReadBasic.All,
  Calendars.ReadWrite.

* ``app``: Application permissions with client secret and admin consent:
  User.Read.All, Calendars.ReadWrite.
"""
from __future__ import annotations

import json
import logging
import threading
import time
from datetime import datetime, timezone

import httpx
import msal

from ..config import Settings
from ..models import BusyBlock, Employee, NotificationResult
from .base import Provider, ProviderError
from .smtp import notify_email

log = logging.getLogger(__name__)

GRAPH = "https://graph.microsoft.com/v1.0"
DELEGATED_SCOPES = [
    "User.Read",
    "User.ReadBasic.All",
    "Calendars.ReadWrite",
]
APP_SCOPES = ["https://graph.microsoft.com/.default"]
EMPLOYEE_CACHE_SECONDS = 600


def _utc_naive_iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds")


def _parse_graph_dt(value: dict) -> datetime:
    # Graph liefert z. B. "2026-10-17T08:00:00.0000000" + timeZone "UTC"
    raw = value["dateTime"][:19]
    return datetime.fromisoformat(raw).replace(tzinfo=timezone.utc)


class GraphProvider(Provider):
    name = "graph"

    def __init__(self, cfg: Settings) -> None:
        if not cfg.graph_tenant_id or not cfg.graph_client_id:
            raise ProviderError("SR_GRAPH_TENANT_ID und SR_GRAPH_CLIENT_ID müssen gesetzt sein")
        if cfg.graph_auth == "app" and not cfg.graph_client_secret:
            raise ProviderError("Für SR_GRAPH_AUTH=app wird SR_GRAPH_CLIENT_SECRET benötigt")
        self.cfg = cfg
        self.delegated = cfg.graph_auth != "app"
        self._lock = threading.Lock()
        self._cache = msal.SerializableTokenCache()
        if cfg.token_cache_file.exists():
            self._cache.deserialize(cfg.token_cache_file.read_text(encoding="utf-8"))
        authority = f"https://login.microsoftonline.com/{cfg.graph_tenant_id}"
        if self.delegated:
            self._msal = msal.PublicClientApplication(
                cfg.graph_client_id, authority=authority, token_cache=self._cache
            )
        else:
            self._msal = msal.ConfidentialClientApplication(
                cfg.graph_client_id,
                authority=authority,
                client_credential=cfg.graph_client_secret,
                token_cache=self._cache,
            )
        self._http = httpx.Client(timeout=20.0)
        self._employees: list[Employee] = []
        self._employees_loaded_at = 0.0
        self._me: dict | None = None
        self._device_flow: dict | None = None
        self._device_flow_result: dict | None = None

    # ------------------------------------------------------------------ Auth
    def _persist_cache(self) -> None:
        if self._cache.has_state_changed:
            self.cfg.token_cache_file.parent.mkdir(parents=True, exist_ok=True)
            self.cfg.token_cache_file.write_text(self._cache.serialize(), encoding="utf-8")
            try:
                self.cfg.token_cache_file.chmod(0o600)
            except OSError:
                pass

    def _token(self) -> str:
        with self._lock:
            if self.delegated:
                accounts = self._msal.get_accounts()
                if not accounts:
                    raise ProviderError(
                        "Backend ist nicht bei Microsoft angemeldet – bitte im Dashboard 'Mit Microsoft anmelden'"
                    )
                result = self._msal.acquire_token_silent(DELEGATED_SCOPES, account=accounts[0])
            else:
                result = self._msal.acquire_token_for_client(scopes=APP_SCOPES)
            self._persist_cache()
        if not result or "access_token" not in result:
            detail = (result or {}).get("error_description", "kein Token erhalten")
            raise ProviderError(f"Microsoft-Anmeldung fehlgeschlagen: {detail}")
        return result["access_token"]

    def start_device_login(self) -> dict:
        if not self.delegated:
            raise ProviderError("Device-Login gibt es nur im Modus SR_GRAPH_AUTH=delegated")
        flow = self._msal.initiate_device_flow(scopes=DELEGATED_SCOPES)
        if "user_code" not in flow:
            raise ProviderError(f"Device-Login konnte nicht gestartet werden: {json.dumps(flow)}")
        self._device_flow = flow
        self._device_flow_result = None

        def _wait() -> None:
            result = self._msal.acquire_token_by_device_flow(flow)
            with self._lock:
                self._persist_cache()
            self._device_flow_result = result
            self._me = None
            self._device_flow = None

        threading.Thread(target=_wait, daemon=True).start()
        return {
            "user_code": flow["user_code"],
            "verification_uri": flow["verification_uri"],
            "message": flow.get("message", ""),
            "expires_in": flow.get("expires_in", 900),
        }

    def logout(self) -> None:
        with self._lock:
            for account in self._msal.get_accounts():
                self._msal.remove_account(account)
            self._persist_cache()
        self._me = None

    # ------------------------------------------------------------------ HTTP
    def _request(self, method: str, path: str, *, json_body: dict | None = None,
                 params: dict | None = None, headers: dict | None = None) -> dict:
        url = path if path.startswith("https://") else GRAPH + path
        all_headers = {"Authorization": f"Bearer {self._token()}"}
        if headers:
            all_headers.update(headers)
        for attempt in range(3):
            resp = self._http.request(method, url, json=json_body, params=params, headers=all_headers)
            if resp.status_code == 429 and attempt < 2:
                time.sleep(min(int(resp.headers.get("Retry-After", "2")), 10))
                continue
            break
        if resp.status_code >= 400:
            try:
                err = resp.json().get("error", {})
                message = f"{err.get('code', resp.status_code)}: {err.get('message', resp.text[:200])}"
            except ValueError:
                message = f"HTTP {resp.status_code}: {resp.text[:200]}"
            raise ProviderError(f"Graph {method} {path} → {message}")
        if resp.status_code == 204 or not resp.content:
            return {}
        return resp.json()

    def _me_info(self) -> dict:
        if self._me is None:
            self._me = self._request("GET", "/me", params={"$select": "id,displayName,userPrincipalName,mail"})
        return self._me

    # ------------------------------------------------------------ Verzeichnis
    def list_employees(self) -> list[Employee]:
        if self._employees and time.time() - self._employees_loaded_at < EMPLOYEE_CACHE_SECONDS:
            return list(self._employees)
        select = "id,displayName,mail,userPrincipalName"
        if not self.delegated:
            select += ",jobTitle,department"
        if self.cfg.employee_group_id:
            path = f"/groups/{self.cfg.employee_group_id}/members/microsoft.graph.user"
        else:
            path = "/users"
        page = self._request("GET", path, params={"$select": select, "$top": "999"})
        users = list(page.get("value", []))
        while page.get("@odata.nextLink"):
            page = self._request("GET", page["@odata.nextLink"])
            users.extend(page.get("value", []))
        me_id = self._me_info()["id"] if self.delegated else ""
        employees = [
            Employee(
                id=u["id"],
                name=u.get("displayName") or u.get("userPrincipalName", ""),
                email=u.get("mail") or "",
                department=u.get("department") or "",
                job_title=u.get("jobTitle") or "",
            )
            for u in users
            if u.get("mail") and u["id"] != me_id
        ]
        self._employees = employees
        self._employees_loaded_at = time.time()
        return list(employees)

    def get_employee(self, employee_id: str) -> Employee | None:
        found = super().get_employee(employee_id)
        if found:
            return found
        try:
            u = self._request("GET", f"/users/{employee_id}", params={"$select": "id,displayName,mail"})
        except ProviderError:
            return None
        return Employee(id=u["id"], name=u.get("displayName", ""), email=u.get("mail") or "")

    # ---------------------------------------------------------------- Kalender
    def get_busy(self, employee: Employee, start: datetime, end: datetime) -> list[BusyBlock]:
        if not employee.email:
            raise ProviderError(f"{employee.name} hat keine E-Mail-Adresse – Kalender nicht abfragbar")
        path = "/me/calendar/getSchedule" if self.delegated else f"/users/{employee.id}/calendar/getSchedule"
        body = {
            "schedules": [employee.email],
            "startTime": {"dateTime": _utc_naive_iso(start), "timeZone": "UTC"},
            "endTime": {"dateTime": _utc_naive_iso(end), "timeZone": "UTC"},
            "availabilityViewInterval": 15,
        }
        data = self._request("POST", path, json_body=body, headers={"Prefer": 'outlook.timezone="UTC"'})
        schedules = data.get("value", [])
        if not schedules:
            return []
        schedule = schedules[0]
        if "error" in schedule and schedule["error"]:
            raise ProviderError(f"Kalender von {employee.name} nicht lesbar: {schedule['error'].get('message')}")
        blocks = []
        for item in schedule.get("scheduleItems", []):
            blocks.append(
                BusyBlock(
                    start=_parse_graph_dt(item["start"]),
                    end=_parse_graph_dt(item["end"]),
                    status=item.get("status", "busy"),
                    subject=item.get("subject", "") or "",
                )
            )
        return blocks

    def create_appointment(
        self, employee: Employee, start: datetime, end: datetime, subject: str, body_html: str
    ) -> str:
        event = {
            "subject": subject,
            "body": {"contentType": "HTML", "content": body_html},
            "start": {"dateTime": _utc_naive_iso(start), "timeZone": "UTC"},
            "end": {"dateTime": _utc_naive_iso(end), "timeZone": "UTC"},
            "location": {"displayName": f"{self.cfg.company_name} – Empfang"},
            "showAs": "busy",
            "isReminderOn": True,
            "reminderMinutesBeforeStart": 15,
        }
        if self.delegated:
            # Das Empfangs-Konto lädt den Mitarbeiter ein → er bekommt eine normale Terminanfrage.
            event["attendees"] = [
                {"emailAddress": {"address": employee.email, "name": employee.name}, "type": "required"}
            ]
            created = self._request("POST", "/me/events", json_body=event)
        else:
            created = self._request("POST", f"/users/{employee.id}/events", json_body=event)
        return created.get("id", "")

    # --------------------------------------------------------------- Notifications
    def notify(self, employee: Employee, subject: str, html: str, text: str) -> NotificationResult:
        return notify_email(self.cfg, employee, subject, html, text)

    # ------------------------------------------------------------------ Status
    def status(self) -> dict:
        info: dict = {
            "provider": self.name,
            "auth": "delegated" if self.delegated else "app",
            "email_configured": bool(self.cfg.smtp_host),
        }
        if self._device_flow:
            info["device_login_pending"] = {
                "user_code": self._device_flow.get("user_code"),
                "verification_uri": self._device_flow.get("verification_uri"),
            }
        if self._device_flow_result and "error" in self._device_flow_result:
            info["last_login_error"] = self._device_flow_result.get("error_description")
        try:
            self._token()
            if self.delegated:
                me = self._me_info()
                info["signed_in_as"] = me.get("userPrincipalName") or me.get("mail")
            info["ready"] = True
        except ProviderError as exc:
            info["ready"] = False
            info["error"] = str(exc)
        return info

"""Druck an den Firmendrucker – ohne Treiber, direkt per IPP (Internet Printing Protocol).

Geprüft am Konica Minolta bizhub C361i (192.168.0.233): IPP läuft dort über normales HTTP
(Port 80) unter http://192.168.0.233/ipp und nimmt application/pdf und image/jpeg an.
IPP ist ein einfaches Binärformat, das per HTTP-POST verschickt wird – dafür reicht httpx.

Weitere Wege (SR_PRINTER_METHODS): "ftp" lädt die PDF auf den FTP-Server des Druckers
(bei vielen Multifunktionsgeräten = Direktdruck), "mock" druckt nur ins Dashboard.
"""
from __future__ import annotations

import ftplib
import io
import logging
import struct
import threading
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any
from urllib.parse import urlparse

import httpx

from .settings import BadgeSettings

log = logging.getLogger("smart-receptionist.printer")

# --------------------------------------------------------------------------- IPP
# Werte-Tags (RFC 8010)
TAG_OPERATION, TAG_JOB, TAG_END, TAG_PRINTER = 0x01, 0x02, 0x03, 0x04
TAG_INTEGER, TAG_BOOLEAN, TAG_ENUM = 0x21, 0x22, 0x23
TAG_TEXT, TAG_NAME, TAG_KEYWORD, TAG_URI, TAG_CHARSET, TAG_LANGUAGE, TAG_MIME = 0x41, 0x42, 0x44, 0x45, 0x47, 0x48, 0x49

OP_PRINT_JOB, OP_VALIDATE_JOB, OP_GET_PRINTER_ATTRIBUTES = 0x0002, 0x0004, 0x000B

PRINTER_STATES = {3: "bereit", 4: "druckt", 5: "angehalten"}


def _attr(tag: int, name: str, value: Any) -> bytes:
    n = name.encode("utf-8")
    if tag in (TAG_INTEGER, TAG_ENUM):
        v = struct.pack(">i", int(value))
    elif tag == TAG_BOOLEAN:
        v = b"\x01" if value else b"\x00"
    else:
        v = str(value).encode("utf-8")
    return struct.pack(">bH", tag, len(n)) + n + struct.pack(">H", len(v)) + v


def ipp_uri(url: str) -> str:
    """http://host/ipp → ipp://host/ipp (so steht es in printer-uri-supported)."""
    p = urlparse(url)
    if p.scheme in ("http", "https"):
        port = f":{p.port}" if p.port and p.port not in (80, 443, 631) else ""
        return f"{'ipps' if p.scheme == 'https' else 'ipp'}://{p.hostname}{port}{p.path or '/ipp'}"
    return url


def build_request(
    operation: int,
    printer_url: str,
    *,
    request_id: int = 1,
    user: str = "Nova-Empfang",
    job_name: str | None = None,
    document_format: str | None = None,
    job_attributes: list[tuple[int, str, Any]] | None = None,
    requested: list[str] | None = None,
) -> bytes:
    out = bytearray(struct.pack(">bbhi", 2, 0, operation, request_id))
    out.append(TAG_OPERATION)
    out += _attr(TAG_CHARSET, "attributes-charset", "utf-8")
    out += _attr(TAG_LANGUAGE, "attributes-natural-language", "de")
    out += _attr(TAG_URI, "printer-uri", ipp_uri(printer_url))
    out += _attr(TAG_NAME, "requesting-user-name", user)
    if job_name:
        out += _attr(TAG_NAME, "job-name", job_name)
    if document_format:
        out += _attr(TAG_MIME, "document-format", document_format)
    for i, name in enumerate(requested or []):
        out += _attr(TAG_KEYWORD, "requested-attributes" if i == 0 else "", name)
    if job_attributes:
        out.append(TAG_JOB)
        for tag, name, value in job_attributes:
            out += _attr(tag, name, value)
    out.append(TAG_END)
    return bytes(out)


def parse_response(data: bytes) -> tuple[int, dict[str, list[Any]]]:
    """Statuscode + Attribute (Name → Werte) einer IPP-Antwort."""
    if len(data) < 8:
        raise ValueError("Antwort zu kurz")
    status = struct.unpack(">H", data[2:4])[0]
    attrs: dict[str, list[Any]] = {}
    i, last = 8, ""
    while i < len(data):
        tag = data[i]
        i += 1
        if tag == TAG_END:
            break
        if tag < 0x10:  # Gruppen-Trenner
            continue
        n_len = struct.unpack(">H", data[i : i + 2])[0]
        i += 2
        name = data[i : i + n_len].decode("utf-8", "replace")
        i += n_len
        v_len = struct.unpack(">H", data[i : i + 2])[0]
        i += 2
        raw = data[i : i + v_len]
        i += v_len
        if name:
            last = name
        if tag in (TAG_INTEGER, TAG_ENUM) and v_len == 4:
            value: Any = struct.unpack(">i", raw)[0]
        elif tag == TAG_BOOLEAN:
            value = raw[:1] == b"\x01"
        elif tag >= 0x40:
            value = raw.decode("utf-8", "replace")
        else:
            value = raw.hex()
        attrs.setdefault(last, []).append(value)
    return status, attrs


def status_text(code: int) -> str:
    known = {
        0x0000: "ok", 0x0001: "ok (Attribute angepasst)", 0x0002: "ok (Konflikt ignoriert)",
        0x0400: "ungültige Anfrage", 0x0401: "verboten", 0x0402: "Anmeldung nötig",
        0x040A: "Dokumentformat nicht unterstützt", 0x040B: "Attribute nicht unterstützt",
        0x0500: "Druckerfehler", 0x0506: "Drucker nimmt keine Aufträge an", 0x0507: "Drucker beschäftigt",
    }
    return known.get(code, f"IPP-Status 0x{code:04x}")


# ----------------------------------------------------------------------- Ergebnis
@dataclass
class PrintResult:
    sent: bool
    method: str
    detail: str = ""
    job_id: int | None = None


@dataclass
class MockPrint:
    time: datetime
    badge_id: str
    preview_jpeg: bytes = field(repr=False, default=b"")


class BadgePrinter:
    def __init__(self, cfg: BadgeSettings, transport: httpx.BaseTransport | None = None) -> None:
        self.cfg = cfg
        self._transport = transport
        self._lock = threading.Lock()
        # Nur im mock-Modus: letzte „Ausdrucke“ fürs Dashboard (werden beim Checkout gelöscht)
        self.mock_outbox: list[MockPrint] = []

    def _client(self) -> httpx.Client:
        return httpx.Client(timeout=self.cfg.printer_timeout_s, transport=self._transport, verify=False)

    def _ipp(self, payload: bytes) -> tuple[int, dict[str, list[Any]]]:
        with self._client() as client:
            r = client.post(self.cfg.printer_url, content=payload, headers={"Content-Type": "application/ipp"})
            r.raise_for_status()
            return parse_response(r.content)

    def _job_attributes(self) -> list[tuple[int, str, Any]]:
        return [
            (TAG_INTEGER, "copies", 1),
            (TAG_KEYWORD, "media", "iso_a4_210x297mm"),
            (TAG_KEYWORD, "sides", "one-sided"),
            (TAG_KEYWORD, "print-color-mode", self.cfg.print_color or "color"),
            # Originalgröße – sonst wird die Karte auf Seitengröße „angepasst“
            (TAG_KEYWORD, "print-scaling", "none"),
        ]

    # ------------------------------------------------------------- Abfragen
    def status(self) -> dict:
        if "ipp" not in self.cfg.printer_methods:
            return {"reachable": None, "methods": list(self.cfg.printer_methods), "detail": "IPP nicht aktiviert"}
        try:
            code, attrs = self._ipp(
                build_request(
                    OP_GET_PRINTER_ATTRIBUTES,
                    self.cfg.printer_url,
                    user=self.cfg.printer_user,
                    requested=[
                        "printer-make-and-model", "printer-state", "printer-state-reasons",
                        "printer-is-accepting-jobs", "document-format-supported", "printer-location",
                    ],
                )
            )
            state = (attrs.get("printer-state") or [0])[0]
            return {
                "reachable": code < 0x0100,
                "methods": list(self.cfg.printer_methods),
                "url": self.cfg.printer_url,
                "model": (attrs.get("printer-make-and-model") or [""])[0],
                "state": PRINTER_STATES.get(state, str(state)),
                "state_reasons": attrs.get("printer-state-reasons", []),
                "accepting_jobs": (attrs.get("printer-is-accepting-jobs") or [None])[0],
                "pdf_supported": "application/pdf" in attrs.get("document-format-supported", []),
            }
        except Exception as exc:
            return {"reachable": False, "methods": list(self.cfg.printer_methods), "url": self.cfg.printer_url, "detail": str(exc)}

    def validate(self) -> PrintResult:
        """Validate-Job: prüft Auftrag und Attribute, ohne Papier zu verbrauchen."""
        try:
            code, attrs = self._ipp(
                build_request(
                    OP_VALIDATE_JOB,
                    self.cfg.printer_url,
                    user=self.cfg.printer_user,
                    job_name="Besucherausweis (Test)",
                    document_format="application/pdf",
                    job_attributes=self._job_attributes(),
                )
            )
            return PrintResult(code < 0x0100, "ipp", status_text(code))
        except Exception as exc:
            return PrintResult(False, "ipp", str(exc))

    # ----------------------------------------------------------------- Drucken
    def print_badge(self, badge_id: str, pdf: bytes, preview_jpeg: bytes, now: datetime) -> PrintResult:
        """Probiert die konfigurierten Wege der Reihe nach, bis einer klappt."""
        errors: list[str] = []
        with self._lock:  # ein Auftrag nach dem anderen
            for method in self.cfg.printer_methods:
                try:
                    if method == "ipp":
                        result = self._print_ipp(badge_id, pdf)
                    elif method == "ftp":
                        result = self._print_ftp(badge_id, pdf)
                    elif method == "mock":
                        self.mock_outbox.insert(0, MockPrint(now, badge_id, preview_jpeg))
                        del self.mock_outbox[10:]
                        result = PrintResult(True, "mock", "Demo-Modus: im Dashboard angezeigt")
                    elif method == "off":
                        return PrintResult(False, "off", "Druck ausgeschaltet")
                    else:
                        errors.append(f"{method}: unbekannter Druckweg")
                        continue
                except Exception as exc:
                    result = PrintResult(False, method, str(exc) or exc.__class__.__name__)
                if result.sent:
                    log.info("Ausweis %s gedruckt über %s (%s)", badge_id, result.method, result.detail)
                    return result
                errors.append(f"{method}: {result.detail}")
                log.warning("Druck über %s fehlgeschlagen: %s", method, result.detail)
        return PrintResult(False, "", "; ".join(errors) or "kein Druckweg konfiguriert")

    def _print_ipp(self, badge_id: str, pdf: bytes) -> PrintResult:
        payload = build_request(
            OP_PRINT_JOB,
            self.cfg.printer_url,
            request_id=2,
            user=self.cfg.printer_user,
            job_name=f"Besucherausweis {badge_id}",
            document_format="application/pdf",
            job_attributes=self._job_attributes(),
        )
        code, attrs = self._ipp(payload + pdf)
        job_id = (attrs.get("job-id") or [None])[0]
        return PrintResult(code < 0x0100, "ipp", status_text(code), job_id)

    def _print_ftp(self, badge_id: str, pdf: bytes) -> PrintResult:
        with ftplib.FTP(self.cfg.printer_ftp_host, timeout=self.cfg.printer_timeout_s) as ftp:
            ftp.login(self.cfg.printer_ftp_user, self.cfg.printer_ftp_password)
            ftp.storbinary(f"STOR Besucherausweis-{badge_id}.pdf", io.BytesIO(pdf))
        return PrintResult(True, "ftp", f"an {self.cfg.printer_ftp_host} übertragen")

    def forget(self, badge_id: str) -> None:
        """Nach Checkout/Ablauf auch die Demo-Vorschau (mit Foto) löschen."""
        self.mock_outbox[:] = [m for m in self.mock_outbox if m.badge_id != badge_id]

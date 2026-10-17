"""Printing on the company printer - no driver, straight over IPP (Internet Printing Protocol).

Verified on the Konica Minolta bizhub C361i (192.168.0.233): IPP is served over plain HTTP on
port 80 at http://192.168.0.233/ipp (and on port 631) and accepts application/pdf and image/jpeg.
IPP is a small binary format sent with an HTTP POST, so httpx is all we need.

Other methods (SR_PRINTER_METHODS): "ftp" uploads the PDF to the printer's FTP server
(direct print on many multifunction devices), "mock" only shows the badge in the dashboard.
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
# Value tags (RFC 8010)
TAG_OPERATION, TAG_JOB, TAG_END, TAG_PRINTER = 0x01, 0x02, 0x03, 0x04
TAG_INTEGER, TAG_BOOLEAN, TAG_ENUM = 0x21, 0x22, 0x23
TAG_TEXT, TAG_NAME, TAG_KEYWORD, TAG_URI, TAG_CHARSET, TAG_LANGUAGE, TAG_MIME = 0x41, 0x42, 0x44, 0x45, 0x47, 0x48, 0x49

OP_PRINT_JOB, OP_VALIDATE_JOB, OP_GET_PRINTER_ATTRIBUTES = 0x0002, 0x0004, 0x000B

PRINTER_STATES = {3: "idle", 4: "printing", 5: "stopped"}


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
    """http://host/ipp -> ipp://host/ipp (the form listed in printer-uri-supported)."""
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
    user: str = "Nova-Reception",
    job_name: str | None = None,
    document_format: str | None = None,
    job_attributes: list[tuple[int, str, Any]] | None = None,
    requested: list[str] | None = None,
) -> bytes:
    out = bytearray(struct.pack(">bbhi", 2, 0, operation, request_id))
    out.append(TAG_OPERATION)
    out += _attr(TAG_CHARSET, "attributes-charset", "utf-8")
    out += _attr(TAG_LANGUAGE, "attributes-natural-language", "en")
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
    """Status code + attributes (name -> values) of an IPP response."""
    if len(data) < 8:
        raise ValueError("IPP response too short")
    status = struct.unpack(">H", data[2:4])[0]
    attrs: dict[str, list[Any]] = {}
    i, last = 8, ""
    while i < len(data):
        tag = data[i]
        i += 1
        if tag == TAG_END:
            break
        if tag < 0x10:  # group delimiter
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
        0x0000: "ok", 0x0001: "ok (attributes adjusted)", 0x0002: "ok (conflicts ignored)",
        0x0400: "bad request", 0x0401: "forbidden", 0x0402: "authentication required",
        0x040A: "document format not supported", 0x040B: "attributes not supported",
        0x0500: "printer error", 0x0506: "printer is not accepting jobs", 0x0507: "printer busy",
    }
    return known.get(code, f"IPP status 0x{code:04x}")


# ------------------------------------------------------------------------ Result
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
        # mock mode only: the latest "printouts" for the dashboard (removed on checkout)
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
            # Original size - otherwise the printer scales the card to the page
            (TAG_KEYWORD, "print-scaling", "none"),
        ]

    # ---------------------------------------------------------------- Queries
    def status(self) -> dict:
        if "ipp" not in self.cfg.printer_methods:
            return {"reachable": None, "methods": list(self.cfg.printer_methods), "detail": "IPP not enabled"}
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
        """Validate-Job: checks the job and its attributes without using paper."""
        try:
            code, attrs = self._ipp(
                build_request(
                    OP_VALIDATE_JOB,
                    self.cfg.printer_url,
                    user=self.cfg.printer_user,
                    job_name="Visitor badge (test)",
                    document_format="application/pdf",
                    job_attributes=self._job_attributes(),
                )
            )
            return PrintResult(code < 0x0100, "ipp", status_text(code))
        except Exception as exc:
            return PrintResult(False, "ipp", str(exc))

    # ---------------------------------------------------------------- Printing
    def print_badge(self, badge_id: str, pdf: bytes, preview_jpeg: bytes, now: datetime) -> PrintResult:
        """Tries the configured methods in order until one succeeds."""
        errors: list[str] = []
        with self._lock:  # one job at a time
            for method in self.cfg.printer_methods:
                try:
                    if method == "ipp":
                        result = self._print_ipp(badge_id, pdf)
                    elif method == "ftp":
                        result = self._print_ftp(badge_id, pdf)
                    elif method == "mock":
                        self.mock_outbox.insert(0, MockPrint(now, badge_id, preview_jpeg))
                        del self.mock_outbox[10:]
                        result = PrintResult(True, "mock", "demo mode: shown in the dashboard")
                    elif method == "off":
                        return PrintResult(False, "off", "printing is turned off")
                    else:
                        errors.append(f"{method}: unknown print method")
                        continue
                except Exception as exc:
                    result = PrintResult(False, method, str(exc) or exc.__class__.__name__)
                if result.sent:
                    log.info("Badge %s printed via %s (%s)", badge_id, result.method, result.detail)
                    return result
                errors.append(f"{method}: {result.detail}")
                log.warning("Printing via %s failed: %s", method, result.detail)
        return PrintResult(False, "", "; ".join(errors) or "no print method configured")

    def _print_ipp(self, badge_id: str, pdf: bytes) -> PrintResult:
        payload = build_request(
            OP_PRINT_JOB,
            self.cfg.printer_url,
            request_id=2,
            user=self.cfg.printer_user,
            job_name=f"Visitor badge {badge_id}",
            document_format="application/pdf",
            job_attributes=self._job_attributes(),
        )
        code, attrs = self._ipp(payload + pdf)
        job_id = (attrs.get("job-id") or [None])[0]
        return PrintResult(code < 0x0100, "ipp", status_text(code), job_id)

    def _print_ftp(self, badge_id: str, pdf: bytes) -> PrintResult:
        with ftplib.FTP(self.cfg.printer_ftp_host, timeout=self.cfg.printer_timeout_s) as ftp:
            ftp.login(self.cfg.printer_ftp_user, self.cfg.printer_ftp_password)
            ftp.storbinary(f"STOR visitor-badge-{badge_id}.pdf", io.BytesIO(pdf))
        return PrintResult(True, "ftp", f"uploaded to {self.cfg.printer_ftp_host}")

    def forget(self, badge_id: str) -> None:
        """After checkout/expiry also delete the demo preview (it contains the photo)."""
        self.mock_outbox[:] = [m for m in self.mock_outbox if m.badge_id != badge_id]

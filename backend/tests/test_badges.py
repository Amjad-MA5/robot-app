"""Tests for visitor badges, printing (IPP) and privacy (deletion)."""
from __future__ import annotations

import base64
import io
import os
from dataclasses import replace
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

os.environ.setdefault("SR_MODE", "mock")
os.environ.setdefault("SR_API_KEY", "test-key")

import httpx  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from PIL import Image  # noqa: E402

from app import clock  # noqa: E402
from app.badges import api as badges_api  # noqa: E402
from app.badges import printer as ipp  # noqa: E402
from app.badges import render  # noqa: E402
from app.badges.settings import badge_settings  # noqa: E402
from app.badges.store import BadgeStore  # noqa: E402
from app.config import settings  # noqa: E402
from app.main import app  # noqa: E402

BERLIN = ZoneInfo("Europe/Berlin")
NOW = datetime(2026, 10, 14, 10, 0, tzinfo=BERLIN)
HEADERS = {"X-Api-Key": settings.api_key}


def jpeg(color=(120, 160, 200), size=(480, 640)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    clock.freeze(NOW)
    monkeypatch.setattr(badges_api, "store", BadgeStore(tmp_path / "badges.json"))
    cfg = replace(badge_settings, printer_methods=("mock",))
    monkeypatch.setattr(badges_api, "printer", ipp.BadgePrinter(cfg))
    yield
    clock.freeze(None)


@pytest.fixture()
def client():
    return TestClient(app)


def create(client, **extra):
    body = {"visitor_name": "Max Mustermann", "employee_id": "anna.schmidt", "employee_name": "Anna Schmidt"} | extra
    r = client.post("/api/badges", json=body, headers=HEADERS)
    assert r.status_code == 200, r.text
    return r.json()


# -------------------------------------------------------------------- Badge
def test_badge_requires_api_key(client):
    assert client.post("/api/badges", json={"visitor_name": "X"}).status_code == 401


def test_create_badge_with_photo_prints_and_returns_preview(client):
    data = create(client, photo_jpeg_base64=base64.b64encode(jpeg()).decode())
    assert data["id"].startswith("V-1014-")
    assert data["has_photo"] is True
    assert data["print"]["sent"] is True and data["print"]["method"] == "mock"
    assert "/v/" in data["qr_url"]
    preview = Image.open(io.BytesIO(base64.b64decode(data["preview_jpeg_base64"])))
    assert preview.size == (640, round(render.CARD_H * 640 / render.CARD_W))
    # Valid for 8 hours from now
    assert datetime.fromisoformat(data["valid_until"]) == NOW + timedelta(hours=8)
    assert data["print_pdf_base64"] is None


def test_robot_printing_returns_pdf_and_skips_backend_printer(client):
    data = create(client, print=False, return_pdf=True)
    assert data["print"]["sent"] is False
    assert base64.b64decode(data["print_pdf_base64"]).startswith(b"%PDF")
    assert client.get("/api/printer/outbox", headers=HEADERS).json() == []
    r = client.post(f"/api/badges/{data['id']}/printed", json={"sent": True, "method": "robot-ipp", "detail": "ok", "job_id": 7},
                    headers=HEADERS)
    assert r.status_code == 200
    info = client.get("/api/badges", headers=HEADERS).json()[0]["print_info"]
    assert info == {"sent": True, "method": "robot-ipp", "detail": "ok", "job_id": 7}


def test_printed_report_for_unknown_badge_is_404(client):
    r = client.post("/api/badges/V-0000-0000/printed", json={"sent": True}, headers=HEADERS)
    assert r.status_code == 404


def test_printer_test_can_return_pdf_without_printing(client):
    r = client.post("/api/printer/test", json={"print": False, "return_pdf": True}, headers=HEADERS).json()
    assert r["sent"] is False and base64.b64decode(r["print_pdf_base64"]).startswith(b"%PDF")
    assert client.get("/api/printer/outbox", headers=HEADERS).json() == []
    assert client.post("/api/printer/test", headers=HEADERS).json()["sent"] is True


def test_badge_without_photo_still_works(client):
    data = create(client)
    assert data["has_photo"] is False
    assert data["print"]["sent"] is True


def test_photo_is_never_stored(client, tmp_path):
    create(client, photo_jpeg_base64=base64.b64encode(jpeg()).decode())
    stored = (tmp_path / "badges.json").read_text(encoding="utf-8")
    assert "Max Mustermann" in stored
    assert "photo" not in stored.replace('"has_photo"', "")
    assert "base64" not in stored and len(stored) < 5000


def test_invalid_photo_is_rejected(client):
    r = client.post("/api/badges", json={"visitor_name": "X", "photo_jpeg_base64": "%%%"}, headers=HEADERS)
    assert r.status_code == 422


def test_scan_page_shows_badge_and_logs_scan(client):
    data = create(client)
    token = data["qr_url"].rsplit("/", 1)[1]
    page = client.get(f"/v/{token}")
    assert page.status_code == 200 and "Valid visitor badge" in page.text and "Max Mustermann" in page.text
    listed = client.get("/api/badges", headers=HEADERS).json()
    assert listed[0]["scan_count"] == 1
    assert "token" not in listed[0]  # the token is only in the QR code, never in lists


def test_unknown_token_is_invalid(client):
    page = client.get("/v/does-not-exist")
    assert "Invalid" in page.text


def test_access_check_respects_zones(client):
    token = create(client)["qr_url"].rsplit("/", 1)[1]
    ok = client.post("/api/access/check", json={"token": token, "door": "Waiting room"}, headers=HEADERS).json()
    assert ok["allowed"] is True
    denied = client.post("/api/access/check", json={"token": token, "door": "Server room"}, headers=HEADERS).json()
    assert denied["allowed"] is False
    assert client.get("/api/badges", headers=HEADERS).json()[0]["scan_count"] == 2


def test_checkout_deletes_everything(client, tmp_path):
    data = create(client, photo_jpeg_base64=base64.b64encode(jpeg()).decode())
    token = data["qr_url"].rsplit("/", 1)[1]
    assert len(client.get("/api/printer/outbox", headers=HEADERS).json()) == 1
    r = client.post(f"/api/badges/{data['id']}/checkout", headers=HEADERS)
    assert r.status_code == 200
    assert client.get("/api/badges", headers=HEADERS).json() == []
    assert client.get("/api/printer/outbox", headers=HEADERS).json() == []  # preview with photo is gone
    assert "Max Mustermann" not in (tmp_path / "badges.json").read_text(encoding="utf-8")
    assert "Invalid" in client.get(f"/v/{token}").text
    events = client.get("/api/badges/events", headers=HEADERS).json()
    assert all("Max" not in e["event"] for e in events)  # event log without names


def test_expired_badges_are_purged(client):
    data = create(client)
    clock.freeze(NOW + timedelta(hours=8, minutes=1))
    assert client.get("/api/badges", headers=HEADERS).json() == []
    token = data["qr_url"].rsplit("/", 1)[1]
    assert client.post("/api/access/check", json={"token": token}, headers=HEADERS).json()["allowed"] is False


def test_validity_ends_at_midnight(client):
    clock.freeze(datetime(2026, 10, 14, 21, 0, tzinfo=BERLIN))
    data = create(client)
    assert datetime.fromisoformat(data["valid_until"]) == datetime(2026, 10, 14, 23, 59, tzinfo=BERLIN)


def test_store_survives_restart(tmp_path):
    file = tmp_path / "b.json"
    s1 = BadgeStore(file)
    b = s1.create(visitor_name="A B", employee_id="", employee_name="", purpose="", company="C",
                  zones=["Reception"], now=NOW, valid_hours=8, has_photo=True)
    s2 = BadgeStore(file)
    assert s2.get(b.id).visitor_name == "A B"
    assert s2.by_token(b.token).id == b.id


# ------------------------------------------------------------------ Drawing
def test_render_card_and_a4_page():
    card = render.render_card(visitor_name="Dr. Maximiliane Mustermann-Schneiderhausen", badge_id="V-1014-1234",
                              qr_url="http://x/v/abc", company="Hinterland Hack", host_name="Anna Schmidt",
                              valid_until=NOW, zones=["Reception", "Waiting room"], photo_jpeg=jpeg())
    assert card.size == (1011, 638)
    page = render.render_print_page(card)
    assert page.size == (2480, 3508)  # A4 at 300 dpi
    pdf = render.to_pdf(page)
    assert pdf.startswith(b"%PDF") and b"/MediaBox [ 0 0 595.2" in pdf  # 210 mm = 595.3 pt


# -------------------------------------------------------------------- IPP
class FakeIppPrinter:
    """Simulated printer - answers like the bizhub (status ok, job-id 42)."""

    def __init__(self, status=0x0000):
        self.status = status
        self.requests: list[bytes] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        assert request.headers["content-type"] == "application/ipp"
        assert request.url.path == "/ipp"
        body = request.read()
        self.requests.append(body)
        op = int.from_bytes(body[2:4], "big")
        out = bytearray(b"\x02\x00" + self.status.to_bytes(2, "big") + body[4:8])
        out.append(ipp.TAG_OPERATION)
        out += ipp._attr(ipp.TAG_CHARSET, "attributes-charset", "utf-8")
        out += ipp._attr(ipp.TAG_LANGUAGE, "attributes-natural-language", "de")
        if op == ipp.OP_PRINT_JOB:
            out.append(ipp.TAG_JOB)
            out += ipp._attr(ipp.TAG_INTEGER, "job-id", 42)
            out += ipp._attr(ipp.TAG_ENUM, "job-state", 3)
        if op == ipp.OP_GET_PRINTER_ATTRIBUTES:
            out.append(ipp.TAG_PRINTER)
            out += ipp._attr(ipp.TAG_TEXT, "printer-make-and-model", "KONICA MINOLTA bizhub C361i")
            out += ipp._attr(ipp.TAG_ENUM, "printer-state", 3)
            out += ipp._attr(ipp.TAG_BOOLEAN, "printer-is-accepting-jobs", True)
            out += ipp._attr(ipp.TAG_MIME, "document-format-supported", "application/pdf")
            out += ipp._attr(ipp.TAG_MIME, "", "image/jpeg")
        out.append(ipp.TAG_END)
        return httpx.Response(200, content=bytes(out), headers={"Content-Type": "application/ipp"})


def make_printer(fake: FakeIppPrinter, methods=("ipp",)) -> ipp.BadgePrinter:
    cfg = replace(badge_settings, printer_methods=methods, printer_url="http://192.168.0.233/ipp")
    return ipp.BadgePrinter(cfg, transport=httpx.MockTransport(fake.handler))


def test_ipp_print_job_encoding():
    fake = FakeIppPrinter()
    result = make_printer(fake).print_badge("V-1", b"%PDF-1.4 test", b"", NOW)
    assert result.sent and result.method == "ipp" and result.job_id == 42
    body = fake.requests[0]
    assert body[:4] == b"\x02\x00\x00\x02"  # IPP 2.0, Print-Job
    assert b"ipp://192.168.0.233/ipp" in body
    assert b"application/pdf" in body and b"iso_a4_210x297mm" in body and b"print-scaling" in body
    assert body.endswith(b"\x03%PDF-1.4 test")  # the document follows end-of-attributes directly
    status, attrs = ipp.parse_response(body[: body.index(b"%PDF")])
    assert attrs["job-name"] == ["Visitor badge V-1"]
    assert attrs["copies"] == [1]


def test_ipp_status_and_validate():
    fake = FakeIppPrinter()
    p = make_printer(fake)
    s = p.status()
    assert s["reachable"] is True and s["model"] == "KONICA MINOLTA bizhub C361i" and s["pdf_supported"] is True
    assert s["state"] == "idle"
    assert p.validate().sent is True
    assert fake.requests[-1][2:4] == b"\x00\x04"


def test_ipp_error_falls_back_to_next_method():
    fake = FakeIppPrinter(status=0x0506)  # not accepting jobs
    p = make_printer(fake, methods=("ipp", "mock"))
    result = p.print_badge("V-2", b"%PDF", b"jpg", NOW)
    assert result.sent and result.method == "mock"


def test_unreachable_printer_reports_error():
    def down(request):
        raise httpx.ConnectError("Connection refused")

    cfg = replace(badge_settings, printer_methods=("ipp",))
    p = ipp.BadgePrinter(cfg, transport=httpx.MockTransport(down))
    result = p.print_badge("V-3", b"%PDF", b"", NOW)
    assert not result.sent and "refused" in result.detail


def test_ipp_uri_conversion():
    assert ipp.ipp_uri("http://192.168.0.233/ipp") == "ipp://192.168.0.233/ipp"
    assert ipp.ipp_uri("https://printer.local/ipp/print") == "ipps://printer.local/ipp/print"
    assert ipp.ipp_uri("http://10.0.0.5:8631/ipp") == "ipp://10.0.0.5:8631/ipp"

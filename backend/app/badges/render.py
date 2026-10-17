"""Zeichnet den Besucherausweis (Pillow) – als Karte und als druckfertige A4-Seite (PDF).

Die Karte hat Scheckkartenformat (ID-1, 85,6 × 54 mm) bei 300 dpi. Auf der A4-Seite liegt sie
in Originalgröße mit gestrichelter Schnittlinie – gedruckt wird ohne Skalierung.
"""
from __future__ import annotations

import io
from datetime import datetime
from pathlib import Path

import segno
from PIL import Image, ImageDraw, ImageFont, ImageOps

DPI = 300
MM = DPI / 25.4
CARD_W, CARD_H = round(85.6 * MM), round(54.0 * MM)  # 1011 × 638 px
A4_W, A4_H = round(210 * MM), round(297 * MM)  # 2480 × 3508 px

NAVY = (11, 30, 63)
TEAL = (46, 196, 182)
INK = (20, 33, 61)
MUTED = (96, 108, 128)
LIGHT = (234, 239, 246)

_FONT_CANDIDATES = {
    False: [
        "DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/segoeui.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf", "/Library/Fonts/Arial.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    ],
    True: [
        "DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/segoeuib.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/Library/Fonts/Arial Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ],
}
_font_cache: dict[tuple[int, bool], ImageFont.FreeTypeFont | ImageFont.ImageFont] = {}


def font(size: int, bold: bool = False):
    """Systemschrift (DejaVu/Arial …), sonst die in Pillow eingebaute skalierbare Schrift."""
    key = (size, bold)
    if key not in _font_cache:
        loaded = None
        for candidate in _FONT_CANDIDATES[bold]:
            try:
                loaded = ImageFont.truetype(candidate, size)
                break
            except OSError:
                continue
        _font_cache[key] = loaded or ImageFont.load_default(size=size)
    return _font_cache[key]


def _text_width(draw: ImageDraw.ImageDraw, text: str, f) -> float:
    return draw.textlength(text, font=f)


def _fit(draw: ImageDraw.ImageDraw, text: str, max_width: float, start: int, minimum: int, bold: bool = False):
    """Größte Schriftgröße, bei der [text] in [max_width] passt (sonst kürzen mit …)."""
    size = start
    while size > minimum and _text_width(draw, text, font(size, bold)) > max_width:
        size -= 2
    f = font(size, bold)
    if _text_width(draw, text, f) <= max_width:
        return text, f
    while len(text) > 1 and _text_width(draw, text + "…", f) > max_width:
        text = text[:-1]
    return text.rstrip() + "…", f


def _wrap_name(draw: ImageDraw.ImageDraw, name: str, max_width: float):
    """Name einzeilig groß – oder auf zwei Zeilen verteilt, wenn er sehr lang ist."""
    one_line, f = _fit(draw, name, max_width, 64, 44, bold=True)
    if not one_line.endswith("…"):
        return [one_line], f
    words = name.split()
    if len(words) >= 2:
        best = None
        for i in range(1, len(words)):
            first, second = " ".join(words[:i]), " ".join(words[i:])
            longest = max(first, second, key=lambda s: _text_width(draw, s, font(44, True)))
            text, f2 = _fit(draw, longest, max_width, 52, 30, bold=True)
            if not text.endswith("…") and (best is None or f2.size > best[1].size):
                best = ([first, second], f2)
        if best:
            return best
    text, f = _fit(draw, name, max_width, 44, 26, bold=True)
    return [text], f


def _initials(name: str) -> str:
    parts = [p for p in name.split() if p[:1].isalpha()]
    return "".join(p[0].upper() for p in parts[:2]) or "?"


def _photo_box(photo_jpeg: bytes | None, name: str, size: tuple[int, int]) -> Image.Image:
    w, h = size
    if photo_jpeg:
        try:
            img = Image.open(io.BytesIO(photo_jpeg))
            img = ImageOps.exif_transpose(img).convert("RGB")
            return ImageOps.fit(img, (w, h), method=Image.Resampling.LANCZOS, centering=(0.5, 0.4))
        except Exception:
            pass  # kaputtes Bild → Platzhalter
    box = Image.new("RGB", (w, h), LIGHT)
    d = ImageDraw.Draw(box)
    initials = _initials(name)
    f = font(int(h * 0.32), True)
    tw = d.textlength(initials, font=f)
    d.text(((w - tw) / 2, h * 0.28), initials, font=f, fill=(150, 162, 182))
    small = font(24)
    label = "ohne Foto"
    d.text(((w - d.textlength(label, font=small)) / 2, h * 0.78), label, font=small, fill=MUTED)
    return box


def _qr_image(url: str, size: int) -> Image.Image:
    buf = io.BytesIO()
    segno.make(url, error="m").save(buf, kind="png", scale=10, border=1, dark="#0b1e3f")
    buf.seek(0)
    return Image.open(buf).convert("RGB").resize((size, size), Image.Resampling.NEAREST)


def render_card(
    *,
    visitor_name: str,
    badge_id: str,
    qr_url: str,
    company: str,
    host_name: str,
    valid_until: datetime,
    zones: list[str],
    photo_jpeg: bytes | None,
    language: str = "de",
) -> Image.Image:
    de = language != "en"
    card = Image.new("RGB", (CARD_W, CARD_H), "white")
    d = ImageDraw.Draw(card)

    # Kopfzeile
    band = 118
    d.rectangle([0, 0, CARD_W, band], fill=NAVY)
    d.text((40, 30), "BESUCHER · VISITOR" if de else "VISITOR · BESUCHER", font=font(46, True), fill="white")
    comp, cf = _fit(d, company, 330, 30, 20)
    d.text((CARD_W - 40 - d.textlength(comp, font=cf), 44), comp, font=cf, fill=(190, 210, 235))
    d.rectangle([0, CARD_H - 22, CARD_W, CARD_H], fill=TEAL)

    # Foto (3:4)
    px, py, pw, ph = 40, band + 32, 270, 360
    photo = _photo_box(photo_jpeg, visitor_name, (pw, ph))
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, pw - 1, ph - 1], radius=22, fill=255)
    card.paste(photo, (px, py), mask)
    d.rounded_rectangle([px, py, px + pw - 1, py + ph - 1], radius=22, outline=(200, 208, 220), width=3)

    # Name
    x = px + pw + 36
    right = CARD_W - 40
    lines, nf = _wrap_name(d, visitor_name, right - x)
    y = py + 2
    for line in lines:
        d.text((x, y), line, font=nf, fill=INK)
        y += int(nf.size * 1.18)
    y += 14

    # Details (links vom QR-Code)
    qr_size = 196
    qr_x, qr_y = CARD_W - 40 - qr_size, CARD_H - 22 - 20 - qr_size
    detail_width = qr_x - 20 - x
    label_f = font(22)
    details = [
        ("Gastgeber · Host" if de else "Host · Gastgeber", host_name or "–"),
        ("Gültig bis · Valid until" if de else "Valid until · Gültig bis", f"{valid_until:%d.%m.%Y, %H:%M}"),
    ]
    for label, value in details:
        # Zeilen, die auf Höhe des QR-Codes liegen, dürfen nur bis zu ihm reichen
        limit = detail_width if y + 60 > qr_y else right - x
        d.text((x, y), label, font=label_f, fill=MUTED)
        v, vf = _fit(d, value, limit, 28, 20, bold=True)
        d.text((x, y + 26), v, font=vf, fill=INK)
        y += 68

    zone_text = " · ".join(zones) if zones else "–"
    d.text((x, y), "Zutritt · Access" if de else "Access · Zutritt", font=label_f, fill=MUTED)
    zt, zf = _fit(d, zone_text, detail_width, 24, 16)
    d.text((x, y + 26), zt, font=zf, fill=INK)

    # Ausweis-ID + QR
    id_f = font(34, True)
    d.text((x, qr_y + qr_size - 40), f"ID {badge_id}", font=id_f, fill=NAVY)
    card.paste(_qr_image(qr_url, qr_size), (qr_x, qr_y))
    return card


def render_print_page(card: Image.Image, language: str = "de") -> Image.Image:
    """A4-Seite: Karte in Originalgröße oben mittig, gestrichelte Schnittlinie, kurzer Hinweis."""
    de = language != "en"
    page = Image.new("RGB", (A4_W, A4_H), "white")
    x, y = (A4_W - CARD_W) // 2, round(25 * MM)
    page.paste(card, (x, y))
    d = ImageDraw.Draw(page)
    pad = 10
    left, top, right, bottom = x - pad, y - pad, x + CARD_W + pad, y + CARD_H + pad
    dash, gap = 26, 14
    for sx in range(left, right, dash + gap):
        d.line([(sx, top), (min(sx + dash, right), top)], fill=(140, 140, 140), width=3)
        d.line([(sx, bottom), (min(sx + dash, right), bottom)], fill=(140, 140, 140), width=3)
    for sy in range(top, bottom, dash + gap):
        d.line([(left, sy), (left, min(sy + dash, bottom))], fill=(140, 140, 140), width=3)
        d.line([(right, sy), (right, min(sy + dash, bottom))], fill=(140, 140, 140), width=3)

    hint = (
        [
            "Ihr Besucherausweis – bitte entlang der gestrichelten Linie ausschneiden und gut sichtbar tragen.",
            "Ihre Daten und Ihr Foto werden nach dem Besuch automatisch gelöscht. Bitte geben Sie den Ausweis beim Gehen ab.",
        ]
        if de
        else [
            "Your visitor badge – please cut along the dashed line and wear it visibly.",
            "Your data and photo are deleted automatically after your visit. Please return the badge when you leave.",
        ]
    )
    ty = bottom + 50
    for line in hint:
        text, f = _fit(d, line, A4_W - 2 * round(20 * MM), 34, 22)
        d.text(((A4_W - d.textlength(text, font=f)) / 2, ty), text, font=f, fill=(90, 90, 90))
        ty += 52
    return page


def to_pdf(page: Image.Image) -> bytes:
    buf = io.BytesIO()
    page.save(buf, format="PDF", resolution=DPI, title="Besucherausweis")
    return buf.getvalue()


def to_jpeg(img: Image.Image, max_width: int = 0, quality: int = 85) -> bytes:
    if max_width and img.width > max_width:
        img = img.resize((max_width, round(img.height * max_width / img.width)), Image.Resampling.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def save_debug(img: Image.Image, path: Path) -> None:  # nur für Tests/Entwicklung
    img.save(path)

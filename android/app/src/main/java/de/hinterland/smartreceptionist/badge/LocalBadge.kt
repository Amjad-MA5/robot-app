package de.hinterland.smartreceptionist.badge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A badge drawn on the robot: card preview (JPEG) and print-ready A4 page (PDF). */
class RenderedBadge(val id: String, val previewJpeg: ByteArray, val pdf: ByteArray)

/**
 * Fallback when the backend cannot be reached: draws the visitor badge on the robot with the same
 * layout as backend/app/badges/render.py (credit card size at 300 dpi on an A4 page), but without
 * the QR code, because only the backend can verify it.
 */
object LocalBadge {
    private const val CARD_W = 1011
    private const val CARD_H = 638
    private const val MM_TO_PT = 72f / 25.4f
    private val NAVY = Color.rgb(11, 30, 63)
    private val TEAL = Color.rgb(46, 196, 182)
    private val INK = Color.rgb(20, 33, 61)
    private val MUTED = Color.rgb(96, 108, 128)
    private val LIGHT = Color.rgb(234, 239, 246)

    fun newId(now: ZonedDateTime = ZonedDateTime.now()): String =
        "V-%02d%02d-%04d".format(now.monthValue, now.dayOfMonth, Random.nextInt(1000, 10000))

    /** Same rule as the backend: 8 hours, at most until 23:59 (but at least one hour). */
    fun validUntil(now: ZonedDateTime = ZonedDateTime.now(), hours: Long = 8): ZonedDateTime {
        val endOfDay = now.withHour(23).withMinute(59).withSecond(0).withNano(0)
        val until = minOf(now.plusHours(hours), endOfDay)
        return if (until.isAfter(now)) until.withNano(0) else now.plusHours(1).withNano(0)
    }

    fun render(
        visitorName: String,
        hostName: String,
        company: String,
        photoJpeg: ByteArray?,
        english: Boolean,
        badgeId: String = newId(),
        validUntil: ZonedDateTime = validUntil(),
    ): RenderedBadge {
        val card = drawCard(visitorName, hostName, company, photoJpeg, english, badgeId, validUntil)
        try {
            val small = Bitmap.createScaledBitmap(card, 640, 640 * CARD_H / CARD_W, true)
            val preview = ByteArrayOutputStream().use { out ->
                small.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
            small.recycle()
            return RenderedBadge(badgeId, preview, toPdf(card, english))
        } finally {
            card.recycle()
        }
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Shrinks the font until [text] fits into [maxWidth]; shortens with an ellipsis if it still does not. */
    private fun fit(text: String, maxWidth: Float, start: Float, minimum: Float, color: Int, bold: Boolean): Pair<String, Paint> {
        val p = paint(start, color, bold)
        while (p.textSize > minimum && p.measureText(text) > maxWidth) p.textSize -= 2f
        if (p.measureText(text) <= maxWidth) return text to p
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "${t.trimEnd()}…" to p
    }

    private fun initials(name: String): String =
        name.split(" ").filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    private fun drawCard(
        visitorName: String,
        hostName: String,
        company: String,
        photoJpeg: ByteArray?,
        english: Boolean,
        badgeId: String,
        validUntil: ZonedDateTime,
    ): Bitmap {
        val card = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        c.drawColor(Color.WHITE)

        // Header band
        val band = 118f
        c.drawRect(0f, 0f, CARD_W.toFloat(), band, Paint().apply { color = NAVY })
        c.drawText(if (english) "VISITOR" else "BESUCHER", 40f, 30f + 46f, paint(46f, Color.WHITE, bold = true))
        val (comp, compPaint) = fit(company, 330f, 30f, 20f, Color.rgb(190, 210, 235), bold = false)
        c.drawText(comp, CARD_W - 40f - compPaint.measureText(comp), 44f + 30f, compPaint)
        c.drawRect(0f, CARD_H - 22f, CARD_W.toFloat(), CARD_H.toFloat(), Paint().apply { color = TEAL })

        // Photo (3:4) with rounded corners, or the initials as placeholder
        val photoRect = RectF(40f, band + 32f, 40f + 270f, band + 32f + 360f)
        val clip = Path().apply { addRoundRect(photoRect, 22f, 22f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        val photo = photoJpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (photo != null) {
            // Centre crop to 3:4, slightly towards the top (like a passport photo)
            val targetRatio = 3f / 4f
            val src = if (photo.width.toFloat() / photo.height > targetRatio) {
                val w = (photo.height * targetRatio).toInt()
                Rect((photo.width - w) / 2, 0, (photo.width - w) / 2 + w, photo.height)
            } else {
                val h = min((photo.width / targetRatio).toInt(), photo.height)
                val top = ((photo.height - h) * 0.4f).toInt()
                Rect(0, top, photo.width, top + h)
            }
            c.drawBitmap(photo, src, photoRect, Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
        } else {
            c.drawRect(photoRect, Paint().apply { color = LIGHT })
            val ip = paint(360f * 0.32f, Color.rgb(150, 162, 182), bold = true)
            val text = initials(visitorName)
            c.drawText(text, photoRect.centerX() - ip.measureText(text) / 2, photoRect.top + 360f * 0.28f + ip.textSize, ip)
            val sp = paint(24f, MUTED)
            val label = if (english) "no photo" else "ohne Foto"
            c.drawText(label, photoRect.centerX() - sp.measureText(label) / 2, photoRect.top + 360f * 0.78f + 24f, sp)
        }
        c.restore()
        c.drawRoundRect(photoRect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(200, 208, 220)
        })

        // Name: one big line, or two lines when it is long
        val x = photoRect.right + 36f
        val right = CARD_W - 40f
        var y = photoRect.top + 2f
        val nameLines = nameLines(visitorName, right - x)
        for ((line, p) in nameLines) {
            c.drawText(line, x, y + p.textSize, p)
            y += p.textSize * 1.18f
        }
        y += 14f

        // Details
        val labelPaint = paint(22f, MUTED)
        val details = listOf(
            (if (english) "Host" else "Gastgeber") to hostName.ifBlank { "–" },
            (if (english) "Valid until" else "Gültig bis") to validUntil.format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")),
        )
        for ((label, value) in details) {
            c.drawText(label, x, y + 22f, labelPaint)
            val (v, vp) = fit(value, right - x, 28f, 20f, INK, bold = true)
            c.drawText(v, x, y + 26f + vp.textSize, vp)
            y += 68f
        }

        // Badge ID
        val idPaint = paint(40f, NAVY, bold = true)
        c.drawText("ID $badgeId", x, CARD_H - 22f - 34f, idPaint)
        return card
    }

    private fun nameLines(name: String, maxWidth: Float): List<Pair<String, Paint>> {
        val (one, p) = fit(name, maxWidth, 64f, 44f, INK, bold = true)
        if (!one.endsWith("…")) return listOf(one to p)
        val words = name.split(" ").filter { it.isNotBlank() }
        var best: List<Pair<String, Paint>>? = null
        for (i in 1 until words.size) {
            val first = words.take(i).joinToString(" ")
            val second = words.drop(i).joinToString(" ")
            val longest = if (paint(44f, INK, true).measureText(first) >= paint(44f, INK, true).measureText(second)) first else second
            val (t, fp) = fit(longest, maxWidth, 52f, 30f, INK, bold = true)
            if (!t.endsWith("…") && (best == null || fp.textSize > best.first().second.textSize)) {
                best = listOf(first to paint(fp.textSize, INK, true), second to paint(fp.textSize, INK, true))
            }
        }
        return best ?: listOf(fit(name, maxWidth, 44f, 26f, INK, bold = true))
    }

    /** A4 page with the card at its original size (85.6 x 54 mm), a dashed cutting line and a short note. */
    private fun toPdf(card: Bitmap, english: Boolean): ByteArray {
        val pageW = 595
        val pageH = 842
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
            val c = page.canvas
            val cardW = 85.6f * MM_TO_PT
            val cardH = 54f * MM_TO_PT
            val left = (pageW - cardW) / 2f
            val top = 25f * MM_TO_PT
            c.drawBitmap(card, null, RectF(left, top, left + cardW, top + cardH), Paint(Paint.FILTER_BITMAP_FLAG))
            val pad = 2.5f
            c.drawRect(left - pad, top - pad, left + cardW + pad, top + cardH + pad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.7f
                color = Color.rgb(140, 140, 140)
                pathEffect = DashPathEffect(floatArrayOf(6f, 3.5f), 0f)
            })
            val hint = if (english) {
                listOf(
                    "Your visitor badge - please cut along the dashed line and wear it visibly.",
                    "Your data and photo are deleted automatically after your visit. Please return the badge when you leave.",
                )
            } else {
                listOf(
                    "Ihr Besucherausweis - bitte entlang der gestrichelten Linie ausschneiden und gut sichtbar tragen.",
                    "Ihre Daten und Ihr Foto werden nach dem Besuch automatisch gelöscht. Bitte geben Sie den Ausweis beim Gehen ab.",
                )
            }
            var ty = top + cardH + pad + 14f
            for (line in hint) {
                val (t, p) = fit(line, pageW - 2 * 20f * MM_TO_PT, 8.5f, 6f, Color.rgb(90, 90, 90), bold = false)
                ty += max(p.textSize * 1.5f, 12f)
                c.drawText(t, (pageW - p.measureText(t)) / 2f, ty, p)
            }
            doc.finishPage(page)
            return ByteArrayOutputStream().use { out ->
                doc.writeTo(out)
                out.toByteArray()
            }
        } finally {
            doc.close()
        }
    }
}

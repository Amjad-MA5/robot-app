package de.hinterland.smartreceptionist.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import de.hinterland.smartreceptionist.flow.Mood
import de.hinterland.smartreceptionist.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animierter Roboter-Avatar „Nova“: weißes Gehäuse, dunkles Visier mit leuchtenden Augen, Antenne.
 *
 * Augen blinzeln zufällig und schauen sich um, der Mund bewegt sich beim Sprechen,
 * bei Freude werden die Augen zu lachenden Bögen, beim Nachdenken wandern sie nach oben.
 */
@Composable
fun RobotFace(mood: Mood, modifier: Modifier = Modifier) {
    val blink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2200, 5200))
            blink.animateTo(1f, tween(80))
            blink.animateTo(0f, tween(130))
            if (Random.nextInt(5) == 0) { // ab und zu doppelt blinzeln
                delay(110)
                blink.animateTo(1f, tween(80))
                blink.animateTo(0f, tween(130))
            }
        }
    }

    val look = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(mood) {
        when (mood) {
            Mood.THINKING -> look.animateTo(Offset(0.55f, -0.85f), tween(450))
            Mood.LISTENING, Mood.SPEAKING -> look.animateTo(Offset.Zero, tween(300))
            Mood.SAD -> look.animateTo(Offset(0f, 0.6f), tween(500))
            else -> while (true) {
                look.animateTo(
                    Offset(Random.nextFloat() * 1.6f - 0.8f, Random.nextFloat() * 1.0f - 0.5f),
                    tween(650, easing = FastOutSlowInEasing),
                )
                delay(Random.nextLong(1400, 3600))
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "face")
    val talk by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(190, easing = LinearEasing), RepeatMode.Reverse), label = "talk")
    val talkSlow by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "talk2")
    val pulse by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "pulse")
    val bob by infinite.animateFloat(-1f, 1f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val dots by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "dots")

    val smile by animateFloatAsState(
        when (mood) {
            Mood.JOY -> 1.25f
            Mood.SAD -> -0.55f
            Mood.THINKING -> 0.15f
            Mood.LISTENING -> 0.6f
            else -> 0.85f
        },
        tween(400),
        label = "smile",
    )
    val joyEyes by animateFloatAsState(if (mood == Mood.JOY) 1f else 0f, tween(300), label = "joy")
    val speaking by animateFloatAsState(if (mood == Mood.SPEAKING) 1f else 0f, tween(200), label = "speak")
    val listening by animateFloatAsState(if (mood == Mood.LISTENING) 1f else 0f, tween(300), label = "listen")
    val thinking by animateFloatAsState(if (mood == Mood.THINKING) 1f else 0f, tween(300), label = "think")
    val sad by animateFloatAsState(if (mood == Mood.SAD) 1f else 0f, tween(400), label = "sad")

    Canvas(modifier) {
        val s = min(size.width, size.height)
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f + bob * s * 0.008f
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        val shell = Color(0xFF8FA6D1)
        
        // Zuhör-Ringe
        if (listening > 0.01f) {
            for (i in 0..1) {
                val phase = (pulse + i * 0.5f) % 1f
                drawCircle(
                    color = Palette.Accent.copy(alpha = (1f - phase) * 0.35f * listening),
                    radius = s * (0.44f + phase * 0.10f),
                    center = p(0.5f, 0.54f),
                    style = Stroke(width = s * 0.012f),
                )
            }
        }

        // Schatten / Glow unter dem Kopf
        drawRoundRect(
            color = Palette.Accent.copy(alpha = 0.10f + 0.08f * listening),
            topLeft = p(0.06f, 0.17f),
            size = Size(s * 0.88f, s * 0.76f),
            cornerRadius = CornerRadius(s * 0.26f, s * 0.26f),
        )

        // Ohr-Module
        listOf(0.045f, 0.895f).forEach { x ->
            drawRoundRect(shell, p(x, 0.41f), Size(s * 0.06f, s * 0.24f), CornerRadius(s * 0.03f, s * 0.03f))
            drawRoundRect(
                Palette.Accent.copy(alpha = 0.55f + 0.3f * listening),
                p(x + 0.018f, 0.47f), Size(s * 0.024f, s * 0.12f), CornerRadius(s * 0.012f, s * 0.012f),
            )
        }

        // Gehäuse
        val headTop = p(0.10f, 0.20f)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC9D7F0)), startY = headTop.y, endY = headTop.y + s * 0.68f),
            topLeft = headTop,
            size = Size(s * 0.80f, s * 0.68f),
            cornerRadius = CornerRadius(s * 0.22f, s * 0.22f),
        )
        // Glanzkante oben
        drawRoundRect(
            Color.White.copy(alpha = 0.7f), p(0.32f, 0.225f), Size(s * 0.36f, s * 0.016f), CornerRadius(s * 0.009f, s * 0.009f),
        )

        // Visier
        val visorTop = p(0.17f, 0.30f)
        val visorSize = Size(s * 0.66f, s * 0.47f)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF12294F), Color(0xFF06122B)), startY = visorTop.y, endY = visorTop.y + visorSize.height),
            topLeft = visorTop, size = visorSize, cornerRadius = CornerRadius(s * 0.16f, s * 0.16f),
        )
        drawRoundRect(
            Palette.Accent.copy(alpha = 0.25f), visorTop, visorSize, CornerRadius(s * 0.16f, s * 0.16f),
            style = Stroke(width = s * 0.007f),
        )
        drawRoundRect(
            Color.White.copy(alpha = 0.07f), p(0.22f, 0.32f), Size(s * 0.56f, s * 0.07f), CornerRadius(s * 0.035f, s * 0.035f),
        )

        // Wangen
        drawCircle(Palette.Cheek.copy(alpha = 0.22f + 0.15f * joyEyes), s * 0.038f, p(0.255f, 0.665f))
        drawCircle(Palette.Cheek.copy(alpha = 0.22f + 0.15f * joyEyes), s * 0.038f, p(0.745f, 0.665f))

        // Augen
        listOf(0.34f, 0.66f).forEach { ex ->
            drawEye(p(ex, 0.52f), s, blink.value, look.value, joyEyes, sad, ex < 0.5f)
        }

        // Mund
        drawMouth(p(0.5f, 0.665f), s, smile, speaking * (0.35f + 0.65f * (0.6f * talk + 0.4f * talkSlow)))

        // Denk-Punkte
        if (thinking > 0.01f) {
            for (i in 0..2) {
                val phase = ((dots * 3f) - i).coerceIn(0f, 1f)
                val lift = sin(phase * PI.toFloat()) * s * 0.03f
                drawCircle(
                    Palette.Accent.copy(alpha = thinking * (0.45f + 0.55f * sin(phase * PI.toFloat()))),
                    s * 0.02f,
                    p(0.70f + i * 0.065f, 0.14f) - Offset(0f, lift),
                )
            }
        }
    }
}

private fun DrawScope.drawEye(
    center: Offset,
    s: Float,
    blink: Float,
    look: Offset,
    joy: Float,
    sad: Float,
    left: Boolean,
) {
    val w = s * 0.13f
    val h = s * 0.19f * (1f - 0.92f * blink) * (1f - 0.85f * joy)
    val c = Offset(center.x + look.x * s * 0.022f, center.y + look.y * s * 0.02f + sad * s * 0.015f)
    val tilt = sad * (if (left) 14f else -14f)
    if (joy < 0.95f) {
        rotate(tilt, c) {
            val r = CornerRadius(w / 2, w / 2)
            // Glow
            drawRoundRect(Palette.Accent.copy(alpha = 0.16f * (1f - joy)), Offset(c.x - w * 0.8f, c.y - h * 0.7f), Size(w * 1.6f, h * 1.4f), CornerRadius(w * 0.8f, w * 0.8f))
            drawRoundRect(Palette.Accent.copy(alpha = 1f - joy), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), r)
            if (h > s * 0.06f) {
                drawCircle(Color.White.copy(alpha = 0.85f * (1f - joy)), w * 0.17f, Offset(c.x - w * 0.15f, c.y - h * 0.27f))
            }
        }
    }
    if (joy > 0.05f) {
        // Lachende Augen: ∩-Bogen
        val arc = Path().apply {
            moveTo(c.x - w * 0.62f, c.y + s * 0.03f)
            quadraticTo(c.x, c.y - s * 0.10f, c.x + w * 0.62f, c.y + s * 0.03f)
        }
        drawPath(arc, Palette.Accent.copy(alpha = 0.25f * joy), style = Stroke(width = s * 0.07f, cap = StrokeCap.Round))
        drawPath(arc, Palette.Accent.copy(alpha = joy), style = Stroke(width = s * 0.034f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawMouth(center: Offset, s: Float, smile: Float, open: Float) {
    val halfW = s * 0.075f
    val curve = smile * s * 0.04f
    if (open > 0.03f) {
        val openH = s * 0.06f * open
        val mouth = Path().apply {
            moveTo(center.x - halfW, center.y)
            quadraticTo(center.x, center.y + curve * 0.4f, center.x + halfW, center.y)
            quadraticTo(center.x, center.y + curve + openH * 1.6f, center.x - halfW, center.y)
            close()
        }
        drawPath(mouth, Palette.Accent.copy(alpha = 0.9f))
        drawPath(mouth, Palette.Accent, style = Stroke(width = s * 0.016f, cap = StrokeCap.Round))
    } else {
        val mouth = Path().apply {
            moveTo(center.x - halfW, center.y)
            quadraticTo(center.x, center.y + curve * 2f, center.x + halfW, center.y)
        }
        drawPath(mouth, Palette.Accent, style = Stroke(width = s * 0.024f, cap = StrokeCap.Round))
    }
}

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import de.hinterland.smartreceptionist.flow.Mood
import de.hinterland.smartreceptionist.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animiertes, freundliches Roboter-Gesicht.
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

        // Zuhör-Ringe
        if (listening > 0.01f) {
            for (i in 0..1) {
                val phase = (pulse + i * 0.5f) % 1f
                drawCircle(
                    color = Palette.Accent.copy(alpha = (1f - phase) * 0.35f * listening),
                    radius = s * (0.40f + phase * 0.12f),
                    center = p(0.5f, 0.52f),
                    style = Stroke(width = s * 0.012f),
                )
            }
        }

        // Kopf-Platte
        drawRoundRect(
            color = Color.White.copy(alpha = 0.07f),
            topLeft = p(0.08f, 0.14f),
            size = Size(s * 0.84f, s * 0.76f),
            cornerRadius = CornerRadius(s * 0.18f, s * 0.18f),
        )

        // Wangen
        drawCircle(Palette.Cheek.copy(alpha = 0.28f + 0.15f * joyEyes), s * 0.055f, p(0.22f, 0.62f))
        drawCircle(Palette.Cheek.copy(alpha = 0.28f + 0.15f * joyEyes), s * 0.055f, p(0.78f, 0.62f))

        // Augen
        listOf(0.32f, 0.68f).forEach { ex ->
            drawEye(p(ex, 0.43f), s, blink.value, look.value, joyEyes, sad, ex < 0.5f)
        }

        // Mund
        drawMouth(p(0.5f, 0.68f), s, smile, speaking * (0.35f + 0.65f * (0.6f * talk + 0.4f * talkSlow)))

        // Denk-Punkte
        if (thinking > 0.01f) {
            for (i in 0..2) {
                val phase = ((dots * 3f) - i).coerceIn(0f, 1f)
                val lift = sin(phase * PI.toFloat()) * s * 0.03f
                drawCircle(
                    Palette.Accent.copy(alpha = thinking * (0.45f + 0.55f * sin(phase * PI.toFloat()))),
                    s * 0.022f,
                    p(0.78f + i * 0.07f, 0.12f) - Offset(0f, lift),
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
    val w = s * 0.17f
    val h = s * 0.21f * (1f - 0.92f * blink) * (1f - 0.85f * joy)
    if (joy < 0.95f) {
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(center.x - w / 2, center.y - h / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(w / 2, w / 2),
            alpha = 1f - joy,
        )
        if (h > s * 0.04f) {
            val pupilR = s * 0.048f
            val pupil = Offset(center.x + look.x * w * 0.22f, center.y + look.y * h * 0.22f)
            drawCircle(Palette.Pupil, pupilR, pupil, alpha = 1f - joy)
            drawCircle(Color.White, pupilR * 0.35f, pupil + Offset(-pupilR * 0.35f, -pupilR * 0.4f), alpha = 1f - joy)
        }
    }
    if (joy > 0.05f) {
        // Lachende Augen: ∩-Bogen
        val arc = Path().apply {
            moveTo(center.x - w * 0.55f, center.y + s * 0.02f)
            quadraticTo(center.x, center.y - s * 0.09f, center.x + w * 0.55f, center.y + s * 0.02f)
        }
        drawPath(arc, Color.White.copy(alpha = joy), style = Stroke(width = s * 0.035f, cap = StrokeCap.Round))
    }
    if (sad > 0.05f) {
        // Traurige Augenbrauen
        val inner = if (left) 1f else -1f
        drawLine(
            Color.White.copy(alpha = sad),
            Offset(center.x - inner * w * 0.6f, center.y - s * 0.15f),
            Offset(center.x + inner * w * 0.5f, center.y - s * 0.19f),
            strokeWidth = s * 0.022f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawMouth(center: Offset, s: Float, smile: Float, open: Float) {
    val halfW = s * 0.13f
    val curve = smile * s * 0.075f
    if (open > 0.03f) {
        val openH = s * 0.085f * open
        val mouth = Path().apply {
            moveTo(center.x - halfW, center.y)
            quadraticTo(center.x, center.y + curve * 0.4f, center.x + halfW, center.y)
            quadraticTo(center.x, center.y + curve + openH * 1.6f, center.x - halfW, center.y)
            close()
        }
        drawPath(mouth, Color(0xFF07142B))
        drawPath(mouth, Color.White, style = Stroke(width = s * 0.022f, cap = StrokeCap.Round))
        // Zunge
        drawCircle(Palette.Cheek.copy(alpha = 0.8f), s * 0.03f * open, Offset(center.x, center.y + curve * 0.5f + openH * 0.9f))
    } else {
        val mouth = Path().apply {
            moveTo(center.x - halfW, center.y)
            quadraticTo(center.x, center.y + curve * 2f, center.x + halfW, center.y)
        }
        drawPath(mouth, Color.White, style = Stroke(width = s * 0.03f, cap = StrokeCap.Round))
    }
}

package de.hinterland.wayfinder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
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
import de.hinterland.wayfinder.flow.Mood
import de.hinterland.wayfinder.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * The robot's animated face: white shell, dark visor, glowing eyes that blink and look around,
 * a mouth that moves while speaking, happy eyes on arrival, raised eyes while thinking.
 */
@Composable
fun RobotFace(mood: Mood, listening: Boolean, modifier: Modifier = Modifier) {
    val blink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2_200, 5_200))
            blink.animateTo(1f, tween(70))
            blink.animateTo(0f, tween(120))
            if (Random.nextInt(4) == 0) {
                delay(120)
                blink.animateTo(1f, tween(70))
                blink.animateTo(0f, tween(120))
            }
        }
    }

    val look = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(mood) {
        when (mood) {
            Mood.THINKING -> look.animateTo(Offset(0.5f, -0.9f), tween(400))
            Mood.SAD -> look.animateTo(Offset(0f, 0.7f), tween(500))
            Mood.LISTENING, Mood.SPEAKING -> look.animateTo(Offset.Zero, tween(250))
            else -> while (true) {
                look.animateTo(Offset(Random.nextFloat() * 1.6f - 0.8f, Random.nextFloat() * 0.9f - 0.45f), tween(600, easing = FastOutSlowInEasing))
                delay(Random.nextLong(1_200, 3_400))
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "face")
    val talk by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(170, easing = LinearEasing), RepeatMode.Reverse), label = "talk")
    val talkSlow by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(470, easing = LinearEasing), RepeatMode.Reverse), label = "talk2")
    val bob by infinite.animateFloat(-1f, 1f, infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pulse by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "pulse")
    val dots by infinite.animateFloat(0f, 3f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "dots")

    Canvas(modifier) {
        val s = min(size.width, size.height)
        val cx = size.width / 2
        val cy = size.height / 2 + bob * s * 0.012f

        // Antenna (pulses while listening)
        val antennaTop = Offset(cx, cy - s * 0.47f)
        drawLine(Palette.Shell, Offset(cx, cy - s * 0.36f), antennaTop, strokeWidth = s * 0.025f, cap = StrokeCap.Round)
        val glow = if (listening) Palette.Go else Palette.Accent
        if (listening) drawCircle(glow.copy(alpha = 0.35f * (1f - pulse)), radius = s * (0.04f + 0.06f * pulse), center = antennaTop)
        drawCircle(glow, radius = s * 0.035f, center = antennaTop)

        // Shell and visor
        val shellSize = Size(s * 0.92f, s * 0.74f)
        val shellTopLeft = Offset(cx - shellSize.width / 2, cy - shellSize.height / 2)
        drawRoundRect(Palette.Shell, shellTopLeft, shellSize, CornerRadius(s * 0.26f))
        val visorSize = Size(s * 0.78f, s * 0.56f)
        val visorTopLeft = Offset(cx - visorSize.width / 2, cy - visorSize.height / 2)
        drawRoundRect(
            Brush.verticalGradient(listOf(Palette.Visor, Color(0xFF0E2A4D)), startY = visorTopLeft.y, endY = visorTopLeft.y + visorSize.height),
            visorTopLeft, visorSize, CornerRadius(s * 0.2f),
        )

        // Eyes
        val eyeColor = when (mood) {
            Mood.SAD -> Color(0xFF8FB8FF)
            Mood.LISTENING -> Palette.Go
            else -> Palette.Accent
        }
        val eyeDx = s * 0.17f
        val eyeY = cy - s * 0.05f + look.value.y * s * 0.035f
        val eyeShift = look.value.x * s * 0.05f
        for (side in listOf(-1f, 1f)) {
            val center = Offset(cx + side * eyeDx + eyeShift, eyeY)
            when (mood) {
                Mood.JOY -> happyEye(center, s, eyeColor)
                Mood.SLEEPY -> drawLine(eyeColor, center - Offset(s * 0.06f, 0f), center + Offset(s * 0.06f, 0f), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
                else -> {
                    val w = s * 0.11f
                    val h = s * 0.15f * (1f - 0.92f * blink.value)
                    drawRoundRect(eyeColor.copy(alpha = 0.25f), Offset(center.x - w * 0.75f, center.y - h * 0.75f), Size(w * 1.5f, h * 1.5f), CornerRadius(w))
                    drawRoundRect(eyeColor, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), CornerRadius(w / 2))
                    if (h > s * 0.05f) drawCircle(Color.White.copy(alpha = 0.85f), radius = s * 0.016f, center = Offset(center.x + w * 0.18f, center.y - h * 0.22f))
                }
            }
        }

        // Blushing cheeks when happy about something
        if (mood == Mood.JOY) {
            drawCircle(Palette.Cheek.copy(alpha = 0.8f), radius = s * 0.035f, center = Offset(cx - s * 0.25f, cy + s * 0.08f))
            drawCircle(Palette.Cheek.copy(alpha = 0.8f), radius = s * 0.035f, center = Offset(cx + s * 0.25f, cy + s * 0.08f))
        }

        // Mouth
        val mouthY = cy + s * 0.13f
        when (mood) {
            Mood.SPEAKING -> {
                val open = (0.25f + 0.75f * (talk * 0.6f + talkSlow * 0.4f))
                val w = s * 0.14f
                val h = s * 0.07f * open
                drawRoundRect(eyeColor, Offset(cx - w / 2, mouthY - h / 2), Size(w, h.coerceAtLeast(s * 0.012f)), CornerRadius(h / 2))
            }
            Mood.THINKING -> for (i in 0 until 3) {
                val active = dots.toInt() == i
                drawCircle(eyeColor.copy(alpha = if (active) 1f else 0.35f), radius = s * 0.018f, center = Offset(cx - s * 0.06f + i * s * 0.06f, mouthY))
            }
            Mood.SAD -> arc(Offset(cx, mouthY + s * 0.03f), s * 0.07f, up = false, color = eyeColor, width = s * 0.022f)
            Mood.SLEEPY -> drawLine(eyeColor, Offset(cx - s * 0.04f, mouthY), Offset(cx + s * 0.04f, mouthY), strokeWidth = s * 0.02f, cap = StrokeCap.Round)
            else -> arc(Offset(cx, mouthY - s * 0.02f), if (mood == Mood.JOY) s * 0.09f else s * 0.07f, up = true, color = eyeColor, width = s * 0.022f)
        }
    }
}

/** "^" shaped happy eye */
private fun DrawScope.happyEye(center: Offset, s: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x - s * 0.06f, center.y + s * 0.025f)
        quadraticTo(center.x, center.y - s * 0.07f, center.x + s * 0.06f, center.y + s * 0.025f)
    }
    drawPath(path, color, style = Stroke(width = s * 0.03f, cap = StrokeCap.Round))
}

/** Smile (up = true) or frown */
private fun DrawScope.arc(center: Offset, halfWidth: Float, up: Boolean, color: Color, width: Float) {
    val depth = halfWidth * 0.6f * (if (up) 1f else -1f)
    val path = Path().apply {
        moveTo(center.x - halfWidth, center.y)
        quadraticTo(center.x, center.y + depth, center.x + halfWidth, center.y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

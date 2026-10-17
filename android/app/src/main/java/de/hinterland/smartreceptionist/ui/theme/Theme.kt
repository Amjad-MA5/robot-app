package de.hinterland.smartreceptionist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Palette {
    val NightTop = Color(0xFF0B1E3F)
    val NightBottom = Color(0xFF143E80)
    val Panel = Color(0x22FFFFFF)
    val PanelStrong = Color(0x33FFFFFF)
    val Text = Color(0xFFF2F6FF)
    val Muted = Color(0xFFB4C3E6)
    val Accent = Color(0xFF4FD1FF)
    val AccentDark = Color(0xFF0B1E3F)
    val Ok = Color(0xFF4FE08B)
    val Warn = Color(0xFFFFC14D)
    val Error = Color(0xFFFF7A7A)
    val Cheek = Color(0xFFFF8FB1)
    val Pupil = Color(0xFF0B1E3F)

    val background = Brush.verticalGradient(listOf(NightTop, NightBottom))
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.AccentDark,
    secondary = Palette.Accent,
    background = Palette.NightTop,
    surface = Palette.NightTop,
    onSurface = Palette.Text,
    onBackground = Palette.Text,
    error = Palette.Error,
)

@Composable
fun ReceptionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

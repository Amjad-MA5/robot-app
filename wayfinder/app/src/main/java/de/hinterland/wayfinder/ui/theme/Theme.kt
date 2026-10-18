package de.hinterland.wayfinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Palette {
    val Night = Color(0xFF0A1A33)
    val NightLight = Color(0xFF12335E)
    val Tile = Color(0x1FFFFFFF)
    val TileStrong = Color(0x33FFFFFF)
    val Line = Color(0x40FFFFFF)
    val Text = Color(0xFFF3F7FF)
    val Muted = Color(0xFFAFC3E8)
    val Accent = Color(0xFF4FD1FF)
    val AccentDark = Color(0xFF06223F)
    val Floor = Color(0xFFFFC94D)
    val Go = Color(0xFF4FE08B)
    val Stop = Color(0xFFFF5C6C)
    val Warn = Color(0xFFFFB347)
    val Visor = Color(0xFF071427)
    val Shell = Color(0xFFF4F7FC)
    val Cheek = Color(0xFFFF8FB1)

    val background = Brush.verticalGradient(listOf(Night, NightLight))
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.AccentDark,
    secondary = Palette.Floor,
    background = Palette.Night,
    surface = Palette.Night,
    surfaceVariant = Palette.NightLight,
    onSurface = Palette.Text,
    onSurfaceVariant = Palette.Muted,
    onBackground = Palette.Text,
    error = Palette.Stop,
)

@Composable
fun WayfinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

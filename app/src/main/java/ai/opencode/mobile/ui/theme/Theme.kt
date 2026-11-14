package ai.opencode.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette lifted from the OpenCode web design tokens (grey-1200 / grey-1100 / blue-400). */
object OpenCodeColors {
    val Background = Color(0xFF080808)
    val Surface = Color(0xFF101010)
    val SurfaceRaised = Color(0xFF161616)
    val Border = Color(0xFF262626)
    val Accent = Color(0xFFA2BCFF)
    val AccentDeep = Color(0xFF3B5CF6)
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF9A9A9A)
    val Danger = Color(0xFFFF8A80)
}

private val OpenCodeColorScheme = darkColorScheme(
    primary = OpenCodeColors.Accent,
    onPrimary = Color(0xFF06122E),
    secondary = OpenCodeColors.AccentDeep,
    onSecondary = Color(0xFFFFFFFF),
    background = OpenCodeColors.Background,
    onBackground = OpenCodeColors.TextPrimary,
    surface = OpenCodeColors.Surface,
    onSurface = OpenCodeColors.TextPrimary,
    surfaceVariant = OpenCodeColors.SurfaceRaised,
    onSurfaceVariant = OpenCodeColors.TextSecondary,
    outline = OpenCodeColors.Border,
    outlineVariant = OpenCodeColors.Border,
    error = OpenCodeColors.Danger,
)

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OpenCodeColorScheme, content = content)
}

package dev.jaredhq.dashboardandroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app's visual identity is the dashboard's: a warm, dark-first palette built
 * on a charcoal/brown base with a terracotta accent and a heather-violet
 * "knowledge" secondary. These values mirror the dashboard's design tokens
 * (`src/app/globals.css`) so the companion reads as the same product.
 *
 * `dynamicColor` defaults to FALSE on purpose — Material You wallpaper theming
 * would wash out the dashboard's deliberate brand hues. A caller can still opt
 * in (e.g. a future setting). See [Brand] for the raw tokens used by the widget
 * and launcher icon, which can't read a Compose theme.
 */
object Brand {
    // Warm dark base.
    val Bg = Color(0xFF191512)
    val BgElev1 = Color(0xFF211C18)
    val BgElev2 = Color(0xFF2A2420)
    val BgElev3 = Color(0xFF352E28)
    val Border = Color(0xFF383027)
    val BorderStrong = Color(0xFF4A4136)
    val Fg = Color(0xFFEFE9E1)
    val FgMuted = Color(0xFFB6ADA1)

    // Terracotta accent (the daily-dashboard hue) + heather violet (knowledge).
    val Accent = Color(0xFFCC7A5C)
    val AccentFg = Color(0xFF1C1714)
    val AccentSoft = Color(0xFF3A2920)
    val Knowledge = Color(0xFFB095D8)
    val KnowledgeSoft = Color(0xFF2C2535)

    val Success = Color(0xFF7FB98E)
    val Warning = Color(0xFFE0A458)
    val Danger = Color(0xFFE0726A)

    // Warm light surfaces (for devices/users on a light system theme).
    val LightBg = Color(0xFFFBF6EF)
    val LightSurface = Color(0xFFFFFDF9)
    val LightAccent = Color(0xFFB5613F)
    val LightViolet = Color(0xFF6F55A3)
}

private val DarkColors = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Brand.AccentFg,
    primaryContainer = Brand.AccentSoft,
    onPrimaryContainer = Color(0xFFF0DCD0),
    secondary = Brand.Knowledge,
    onSecondary = Brand.AccentFg,
    secondaryContainer = Brand.KnowledgeSoft,
    onSecondaryContainer = Color(0xFFE7DCF5),
    tertiary = Brand.Success,
    onTertiary = Brand.AccentFg,
    background = Brand.Bg,
    onBackground = Brand.Fg,
    surface = Brand.BgElev1,
    onSurface = Brand.Fg,
    surfaceVariant = Brand.BgElev2,
    onSurfaceVariant = Brand.FgMuted,
    outline = Brand.BorderStrong,
    outlineVariant = Brand.Border,
    error = Brand.Danger,
    onError = Brand.AccentFg,
    errorContainer = Color(0xFF3A2024),
    onErrorContainer = Color(0xFFF4C9C4),
)

private val LightColors = lightColorScheme(
    primary = Brand.LightAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6D9CC),
    onPrimaryContainer = Color(0xFF3A1C10),
    secondary = Brand.LightViolet,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF271046),
    tertiary = Color(0xFF3F7D52),
    onTertiary = Color(0xFFFFFFFF),
    background = Brand.LightBg,
    onBackground = Color(0xFF211C18),
    surface = Brand.LightSurface,
    onSurface = Color(0xFF211C18),
    surfaceVariant = Color(0xFFECE2D4),
    onSurfaceVariant = Color(0xFF5C5246),
    outline = Color(0xFF8A7D6C),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun DashboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

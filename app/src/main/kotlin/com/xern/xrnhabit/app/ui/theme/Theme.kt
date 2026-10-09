package com.xern.xrnhabit.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Still a restrained system — one accent, soft surfaces, no loud chrome — but block state now
 * carries a colour so the three states read at a glance (see [StatusStyle]). Everything else
 * stays close to monochrome so the status colours are the thing that pops.
 */

private val Accent = Color(0xFF3355E1)
private val AccentDark = Color(0xFFAFBEFF)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF071A6B),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E7F2),
    onSecondaryContainer = Color(0xFF191A26),
    background = Color(0xFFFBFAF8),
    onBackground = Color(0xFF1B1B1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1E),
    surfaceVariant = Color(0xFFF1F0F4),
    onSurfaceVariant = Color(0xFF61616B),
    outline = Color(0xFFC5C4CC),
    outlineVariant = Color(0x14000000),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF0A1550),
    primaryContainer = Color(0xFF23347F),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFBFC1D6),
    onSecondary = Color(0xFF2A2C3B),
    secondaryContainer = Color(0xFF2E3040),
    onSecondaryContainer = Color(0xFFE6E7F2),
    background = Color(0xFF121214),
    onBackground = Color(0xFFECECEE),
    surface = Color(0xFF1A1A1D),
    onSurface = Color(0xFFECECEE),
    surfaceVariant = Color(0xFF25252A),
    onSurfaceVariant = Color(0xFFA9A9B2),
    outline = Color(0xFF48484F),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** Container / content / accent colours for a block state. Read in both themes. */
data class StatusStyle(val container: Color, val content: Color, val accent: Color)

object StatusPalette {
    // ARMED — "locked". A calm clay red: this is a stop, not an alarm.
    val lockedLight = StatusStyle(Color(0xFFF7E0DC), Color(0xFF7A2E22), Color(0xFFB5442F))
    val lockedDark = StatusStyle(Color(0xFF41211B), Color(0xFFF3C1B8), Color(0xFFE0806E))

    // EARNED — "ready". Amber: something good is waiting for you.
    val readyLight = StatusStyle(Color(0xFFFBEBC8), Color(0xFF6B4E12), Color(0xFFB07D14))
    val readyDark = StatusStyle(Color(0xFF3E320F), Color(0xFFF3D9A0), Color(0xFFD8B25A))

    // UNLOCKED — "open". Green: you're through.
    val openLight = StatusStyle(Color(0xFFD9EEDD), Color(0xFF1E5130), Color(0xFF2E7D45))
    val openDark = StatusStyle(Color(0xFF17331F), Color(0xFFB4DFBF), Color(0xFF6FB784))
}

/**
 * Single-series data colour for charts. Light is the brand accent; dark is NOT `AccentDark` —
 * that pastel sits above the dark-mode lightness band and below the chroma floor for marks.
 * Both validated against their card surface (#FFFFFF / #1A1A1D): in band, >= 3:1 contrast.
 */
object ChartPalette {
    val seriesLight = Color(0xFF3355E1)
    val seriesDark = Color(0xFF7A8DEB)
}

@Composable
fun XrnhabitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

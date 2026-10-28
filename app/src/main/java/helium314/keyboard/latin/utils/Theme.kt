// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fixed TEKLADO palette in a Linear-like style: near-black surfaces, hairline borders,
// small corner radii, tight semibold type and one muted indigo accent.
// Dynamic color is intentionally not used, so settings look the same on every device.

private val Accent = Color(0xFF5E6AD2)
private val AccentSoft = Color(0xFF7C85E0)
private val Ink = Color(0xFF1B1C1F)
private val Page = Color(0xFFF7F8F8)
private val Card = Color(0xFFFFFFFF)
private val Muted = Color(0xFF6F7480)
private val PageDark = Color(0xFF08090A)
private val CardDark = Color(0xFF101113)
private val InkDark = Color(0xFFF7F8F8)
private val MutedDark = Color(0xFF8A8F98)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EBFA),
    onPrimaryContainer = Accent,
    inversePrimary = AccentSoft,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EBFA),
    onSecondaryContainer = Accent,
    tertiary = Accent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9EBFA),
    onTertiaryContainer = Accent,
    background = Page,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEEF0),
    onSurfaceVariant = Muted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF1B1C1F),
    inverseOnSurface = Color.White,
    outline = Color(0xFFD7D9DD),
    outlineVariant = Color(0xFFE6E7EA),
    scrim = Color(0x99000000),
    surfaceBright = Card,
    surfaceDim = Page,
    surfaceContainerLowest = Card,
    surfaceContainerLow = Card,
    surfaceContainer = Page,
    surfaceContainerHigh = Color(0xFFF0F1F3),
    surfaceContainerHighest = Color(0xFFE9EAED),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B1E3A),
    onPrimaryContainer = Color(0xFFB4BAF0),
    inversePrimary = Accent,
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1B1E3A),
    onSecondaryContainer = Color(0xFFB4BAF0),
    tertiary = Accent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF1B1E3A),
    onTertiaryContainer = Color(0xFFB4BAF0),
    background = PageDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF1C1E21),
    onSurfaceVariant = MutedDark,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF7F8F8),
    inverseOnSurface = Ink,
    outline = Color(0xFF2E3035),
    outlineVariant = Color(0xFF1F2124),
    scrim = Color(0xCC000000),
    surfaceBright = Color(0xFF191A1D),
    surfaceDim = PageDark,
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = CardDark,
    surfaceContainer = PageDark,
    surfaceContainerHigh = Color(0xFF17181B),
    surfaceContainerHighest = Color(0xFF202225),
)

private val TekladoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
private fun tekladoTypography(): Typography {
    val base = Typography()
    fun TextStyle.t(size: Int, weight: FontWeight, lh: Int = (size * 1.35).toInt(), ls: Double = 0.0) =
        copy(fontSize = size.sp, lineHeight = lh.sp, fontWeight = weight, letterSpacing = ls.sp)
    return Typography(
        displayLarge = base.displayLarge.t(32, FontWeight.SemiBold, ls = -0.6),
        displayMedium = base.displayMedium.t(28, FontWeight.SemiBold, ls = -0.5),
        displaySmall = base.displaySmall.t(24, FontWeight.SemiBold, ls = -0.4),
        headlineLarge = base.headlineLarge.t(24, FontWeight.SemiBold, ls = -0.4),
        headlineMedium = base.headlineMedium.t(20, FontWeight.SemiBold, ls = -0.3),
        headlineSmall = base.headlineSmall.t(18, FontWeight.SemiBold, ls = -0.2),
        titleLarge = base.titleLarge.t(17, FontWeight.SemiBold, ls = -0.2),
        titleMedium = base.titleMedium.t(15, FontWeight.Medium, ls = -0.1),
        titleSmall = base.titleSmall.t(13, FontWeight.Medium),
        bodyLarge = base.bodyLarge.t(15, FontWeight.Normal),
        bodyMedium = base.bodyMedium.t(13, FontWeight.Normal),
        bodySmall = base.bodySmall.t(12, FontWeight.Normal),
        labelLarge = base.labelLarge.t(13, FontWeight.Medium),
        labelMedium = base.labelMedium.t(12, FontWeight.Medium),
        labelSmall = base.labelSmall.t(11, FontWeight.Medium),
    )
}

@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = tekladoTypography(),
        shapes = TekladoShapes,
        content = content,
    )
}

const val previewDark = true

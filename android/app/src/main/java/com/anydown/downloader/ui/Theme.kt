package com.anydown.downloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Light-only, monochrome.
 *
 * Material's scheme is filled in mostly so its own components (ripples, text
 * selection handles, the odd indicator) inherit ink rather than purple. The
 * actual design lives in [Palette] and [Type] — screens reference those
 * directly instead of MaterialTheme, which keeps the look from drifting back
 * toward stock Material.
 *
 * No dark theme. A dark variant of a paper-white design isn't a palette swap,
 * it's a second design, and shipping a half-considered one is worse than
 * shipping none.
 */
private val Scheme = lightColorScheme(
    primary = Palette.ink,
    onPrimary = Palette.inkInverse,
    secondary = Palette.ink,
    onSecondary = Palette.inkInverse,
    background = Palette.paper,
    onBackground = Palette.ink,
    surface = Palette.paper,
    onSurface = Palette.ink,
    surfaceVariant = Palette.surface,
    onSurfaceVariant = Palette.inkSecondary,
    outline = Palette.hairlineStrong,
    outlineVariant = Palette.hairline,
    error = Palette.danger,
    onError = Palette.inkInverse,
)

private val AppTypography = Typography(
    bodyLarge = Type.body,
    bodyMedium = Type.small,
    labelLarge = Type.button,
)

@Composable
fun AnyDownTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}

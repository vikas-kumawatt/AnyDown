package com.anydown.downloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark only.
 *
 * Material's scheme is filled in so its own bits — ripples, selection handles,
 * indicators — inherit this palette rather than defaulting to purple. The real
 * design lives in [Palette] and [Type]; screens reference those directly, which
 * keeps the look from drifting back toward stock Material.
 */
private val Scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    secondary = Palette.accent,
    onSecondary = Palette.onAccent,
    background = Palette.base,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceRaised,
    onSurfaceVariant = Palette.textSecondary,
    outline = Palette.borderStrong,
    outlineVariant = Palette.border,
    error = Palette.danger,
    onError = Palette.onAccent,
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

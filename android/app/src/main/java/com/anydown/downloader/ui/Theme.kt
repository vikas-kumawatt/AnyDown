package com.anydown.downloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the web app (frontend/src/index.css), so the two feel like
// one product. Dark only: this is a dark-first design and a light variant would
// need its own contrast pass to be worth shipping.
private val Ink = Color(0xFF070B18)
private val Panel = Color(0xFF101729)
private val Edge = Color(0xFF1F2A44)
private val Accent = Color(0xFF5B8CFF)
private val OnDark = Color(0xFFE8ECF7)
private val Muted = Color(0xFF8E9AB8)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Ink,
    onBackground = OnDark,
    surface = Panel,
    onSurface = OnDark,
    surfaceVariant = Edge,
    onSurfaceVariant = Muted,
    outline = Edge,
    error = Color(0xFFFF6B81),
)

@Composable
fun AnyDownTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

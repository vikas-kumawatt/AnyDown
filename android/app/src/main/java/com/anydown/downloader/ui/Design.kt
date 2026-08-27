package com.anydown.downloader.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anydown.downloader.R

/**
 * Dark, near-monochrome, built on Plus Jakarta Sans.
 *
 * Weight and spacing do the work that colour usually does. Elevation comes from
 * a small number of surface steps rather than shadows, and the only saturated
 * colour on screen is a status marker.
 */
object Palette {
    /** Page. Not pure black — pure black on OLED makes edges disappear. */
    val base = Color(0xFF0D0D0F)
    /** Cards, wells, the input field. */
    val surface = Color(0xFF16161A)
    val surfaceRaised = Color(0xFF1E1E23)
    val surfacePressed = Color(0xFF26262C)

    val text = Color(0xFFF4F4F6)
    val textSecondary = Color(0xFF9E9EA8)
    val textTertiary = Color(0xFF6B6B76)
    val onAccent = Color(0xFF0D0D0F)

    /** The one bright surface: primary buttons are near-white on dark. */
    val accent = Color(0xFFF4F4F6)

    val border = Color(0xFF26262C)
    val borderStrong = Color(0xFF3A3A43)

    val danger = Color(0xFFFF6B6B)
    val success = Color(0xFF4ADE80)
    val warning = Color(0xFFFBBF24)
}

/**
 * Plus Jakarta Sans, one variable file covering every weight.
 *
 * `FontVariation` needs API 26; below that Android ignores the axis and renders
 * the default weight, so the hierarchy flattens slightly on very old devices but
 * nothing breaks.
 *
 * The opt-in is required because `FontVariation` is still marked experimental in
 * Compose. It's confined to this one function, so if the API is renamed later
 * this is the only place that changes.
 */
@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: FontWeight) = Font(
    R.font.plus_jakarta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Jakarta = FontFamily(
    jakarta(FontWeight.Light),
    jakarta(FontWeight.Normal),
    jakarta(FontWeight.Medium),
    jakarta(FontWeight.SemiBold),
    jakarta(FontWeight.Bold),
    jakarta(FontWeight.ExtraBold),
)

object Type {
    /** Section labels and the wordmark. Small, wide, uppercase. */
    val label = TextStyle(
        fontFamily = Jakarta,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = Palette.textTertiary,
    )

    val labelBright = label.copy(color = Palette.text, letterSpacing = 1.6.sp)

    /** Screen-opening statements. */
    val display = TextStyle(
        fontFamily = Jakarta,
        fontSize = 32.sp,
        lineHeight = 39.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.8).sp,
        color = Palette.text,
    )

    val title = TextStyle(
        fontFamily = Jakarta,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = Palette.text,
    )

    val body = TextStyle(
        fontFamily = Jakarta,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        color = Palette.textSecondary,
    )

    val bodyBright = body.copy(color = Palette.text, fontWeight = FontWeight.Medium)

    val small = TextStyle(
        fontFamily = Jakarta,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = Palette.textSecondary,
    )

    /** Resolutions and other figures that headline a row. */
    val figure = TextStyle(
        fontFamily = Jakarta,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        color = Palette.text,
    )

    /** Sizes and percentages. Tabular so digits don't shift as they change. */
    val figureSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Palette.textSecondary,
    )

    /** Raw yt-dlp output. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = Palette.textSecondary,
    )

    val button = TextStyle(
        fontFamily = Jakarta,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
    )

    val inputText = TextStyle(
        fontFamily = Jakarta,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        color = Palette.text,
    )
}

/**
 * The AnyDown logo — the supplied artwork itself.
 *
 * There is deliberately no drawn or recoloured variant of this. One image file
 * (`artwork/icon-source.png`) is the source for the launcher icon at every
 * density and for every appearance inside the app, so the two can't drift apart
 * and nothing is a second-hand approximation of the real thing.
 */
@Composable
fun AnyDownLogo(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(
        painter = painterResource(R.drawable.anydown_logo),
        contentDescription = null,
        modifier = modifier,
        alpha = alpha,
    )
}

/** Page gutter. */
val Gutter = 20.dp

/** Shared corner radius. Soft enough to feel modern, not a pill. */
val CardRadius = 16.dp
val ControlRadius = 14.dp

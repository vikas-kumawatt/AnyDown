package com.anydown.downloader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design language: paper white, ink black, and nothing else but status.
 *
 * Three deliberate choices carry the whole look, and they're the reason it reads
 * as designed rather than assembled from defaults:
 *
 *  1. **A serif for anything editorial** — titles, statements, numbers that
 *     matter. Compose ships one, so it costs no dependency and instantly breaks
 *     the generic-Material-sans feel.
 *  2. **Monospace for every measurement** — resolutions, file sizes,
 *     percentages. Figures line up in columns and stop jumping as they change.
 *  3. **Letterspaced micro-caps for labels**, paired with hairline rules instead
 *     of boxes, cards or elevation. Structure comes from alignment and 1px
 *     lines, not from containers.
 *
 * Colour is monochrome by rule. [Palette.danger], [Palette.success] and
 * [Palette.warning] exist only to mark state, never to decorate, and appear as
 * a small dot or a 2dp edge rather than a filled surface.
 */
object Palette {
    val paper = Color(0xFFFFFFFF)
    /** Barely-there fill for wells and placeholders. */
    val surface = Color(0xFFF7F7F6)
    val surfacePressed = Color(0xFFEFEFED)

    val ink = Color(0xFF0B0B0C)
    val inkSecondary = Color(0xFF6A6A70)
    val inkTertiary = Color(0xFF9C9CA3)
    val inkInverse = Color(0xFFFFFFFF)

    val hairline = Color(0xFFE5E5E3)
    val hairlineStrong = Color(0xFFD2D2CF)

    // Status only. Muted on purpose: saturated colour on white looks cheap.
    val danger = Color(0xFFB4271F)
    val success = Color(0xFF1B7A45)
    val warning = Color(0xFFA85B08)
}

object Type {
    /** Wordmark and section labels. Tiny, wide, uppercase. */
    val label = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
        color = Palette.inkTertiary,
    )

    val labelInk = label.copy(color = Palette.ink, fontWeight = FontWeight.SemiBold)

    /** Editorial statement type. */
    val display = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.4).sp,
        color = Palette.ink,
    )

    val title = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.2).sp,
        color = Palette.ink,
    )

    val body = TextStyle(
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        color = Palette.inkSecondary,
    )

    val bodyInk = body.copy(color = Palette.ink)

    val small = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = Palette.inkSecondary,
    )

    /** Every figure the user reads: resolution, size, percentage. */
    val numeral = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.3).sp,
        color = Palette.ink,
    )

    val numeralSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Palette.inkSecondary,
    )

    /** Raw yt-dlp output. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = Palette.inkSecondary,
    )

    val button = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

/** A 1px rule. The primary structural device in this design. */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = Palette.hairline,
) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * A section heading: micro-caps followed by a rule that runs to the margin.
 * Borrowed from print, and does the job a Card usually does with none of the
 * visual weight.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(text.uppercase(), style = Type.label)
        Spacer(Modifier.width(12.dp))
        Hairline(Modifier.weight(1f))
    }
}

/**
 * The AnyDown mark, drawn rather than shipped as a drawable so it stays crisp at
 * any size and can take any colour. Same geometry as the launcher icon: an arrow
 * descending into an open tray, with motion lines to its left.
 */
@Composable
fun Mark(
    modifier: Modifier = Modifier,
    color: Color = Palette.ink,
    withMotionLines: Boolean = true,
) {
    BoxWithConstraints(modifier) {
        Canvas(Modifier.size(maxWidth, maxHeight)) {
            val w = size.width
            val h = size.height

            // Geometry is expressed as fractions of `art`, identical to
            // android/scripts/generate-icons.py, so the in-app mark and the
            // launcher icon are the same drawing.
            //
            // The mark spans 1.305 * art horizontally (motion lines reach well
            // left of the arrow) and 0.82 * art vertically. Solving for both
            // keeps it centred and inside the box at any aspect ratio.
            val art = if (withMotionLines) {
                minOf(w / 1.305f, h / 0.82f)
            } else {
                minOf(w / 0.80f, h / 0.82f)
            }
            val cx = w / 2f + if (withMotionLines) art * 0.2525f else 0f
            val top = h / 2f - art * 0.51f

            val stemW = art * 0.17f
            val stemTop = top + art * 0.10f
            val headTop = top + art * 0.44f
            val headHalf = art * 0.325f
            val tipY = top + art * 0.67f

            drawRoundRect(
                color = color,
                topLeft = Offset(cx - stemW / 2f, stemTop),
                size = Size(stemW, headTop - stemTop + art * 0.02f),
                cornerRadius = CornerRadius(stemW / 2f),
            )

            drawPath(
                path = Path().apply {
                    moveTo(cx - headHalf, headTop)
                    lineTo(cx + headHalf, headTop)
                    lineTo(cx, tipY)
                    close()
                },
                color = color,
            )

            // Tray: an open-topped U, stroked with round caps.
            val trayHalf = art * 0.40f
            val trayTop = top + art * 0.58f
            val trayBottom = top + art * 0.92f
            val strokeW = art * 0.125f
            val inset = strokeW / 2f
            drawPath(
                path = Path().apply {
                    moveTo(cx - trayHalf + inset, trayTop)
                    lineTo(cx - trayHalf + inset, trayBottom - inset - strokeW / 2f)
                    quadraticBezierTo(
                        cx - trayHalf + inset, trayBottom - inset,
                        cx - trayHalf + inset + strokeW, trayBottom - inset,
                    )
                    lineTo(cx + trayHalf - inset - strokeW, trayBottom - inset)
                    quadraticBezierTo(
                        cx + trayHalf - inset, trayBottom - inset,
                        cx + trayHalf - inset, trayBottom - inset - strokeW / 2f,
                    )
                    lineTo(cx + trayHalf - inset, trayTop)
                },
                color = color,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )

            if (withMotionLines) {
                val lineH = art * 0.095f
                val right = cx - headHalf - art * 0.08f
                listOf(
                    Triple(0.24f, 0.50f, 1f),
                    Triple(0.39f, 0.35f, 0.70f),
                    Triple(0.53f, 0.14f, 0.42f),
                ).forEach { (yFrac, lenFrac, alpha) ->
                    val len = art * lenFrac
                    drawRoundRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(right - len, top + art * yFrac),
                        size = Size(len, lineH),
                        cornerRadius = CornerRadius(lineH / 2f),
                    )
                }
            }
        }
    }
}

/** Standard page gutter. Wide, because whitespace is doing real work here. */
val Gutter = 24.dp

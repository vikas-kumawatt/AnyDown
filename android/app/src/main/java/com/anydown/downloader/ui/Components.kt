package com.anydown.downloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The full-width primary action. Solid ink, minimal radius, wide-tracked caps.
 * Not a Material Button — those bring their own elevation, shape and colour
 * opinions, and overriding all three is more code than drawing it directly.
 */
@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = label,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.28f
            pressed -> 0.82f
            else -> 1f
        },
        animationSpec = tween(120),
        label = "actionAlpha",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.ink.copy(alpha = alpha))
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.5.dp,
                    color = Palette.inkInverse,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                (if (loading) loadingLabel else label).uppercase(),
                style = Type.button,
                color = Palette.inkInverse,
            )
        }
    }
}

/** A restrained secondary action: micro-caps text under a hairline. */
@Composable
fun QuietAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.ink,
) {
    Text(
        label.uppercase(),
        style = Type.label.copy(color = color),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

enum class NoticeTone { NEUTRAL, ERROR, SUCCESS, WARNING }

private fun NoticeTone.color(): Color = when (this) {
    NoticeTone.NEUTRAL -> Palette.ink
    NoticeTone.ERROR -> Palette.danger
    NoticeTone.SUCCESS -> Palette.success
    NoticeTone.WARNING -> Palette.warning
}

/**
 * A message marked by a 2dp edge in its status colour.
 *
 * Deliberately not a tinted card: on a paper-white page a filled colour block
 * shouts, while a single coloured edge reads as an annotation. This is the only
 * place non-monochrome colour appears in the app.
 */
@Composable
fun Notice(
    message: String,
    tone: NoticeTone = NoticeTone.NEUTRAL,
    detail: String? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    val accent = tone.color()

    // IntrinsicSize.Min lets the accent edge measure to the text's own height
    // instead of a guessed constant, so it stays flush as details expand.
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(message, style = Type.small.copy(color = Palette.ink))

            if (detail != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (showDetail) "Hide details" else "Show details",
                    style = Type.label.copy(color = accent),
                    modifier = Modifier.clickable { showDetail = !showDetail },
                )
                AnimatedVisibility(showDetail) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Palette.surface)
                                .padding(12.dp)
                        ) {
                            // yt-dlp's own words, verbatim. When a platform
                            // breaks this is the only useful thing on screen.
                            Text(detail, style = Type.mono)
                        }
                    }
                }
            }
        }
        if (onDismiss != null) {
            QuietAction("Close", onDismiss, color = Palette.inkTertiary)
        }
    }
}

/** Status dot plus micro-caps label, used in the top bar. */
@Composable
fun StatusChip(label: String, tone: NoticeTone, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(tone.color())
        )
        Spacer(Modifier.width(7.dp))
        Text(label.uppercase(), style = Type.label)
    }
}

/**
 * A row in the quality table. Big monospace figure on the left, size on the
 * right, hairline underneath — a table, not a stack of buttons.
 */
@Composable
fun QualityRow(
    headline: String,
    note: String?,
    trailing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (pressed) Palette.surfacePressed else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(headline, style = Type.numeral)
                if (note != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(note.uppercase(), style = Type.label)
                }
            }
            Text(trailing, style = Type.numeralSmall)
            Spacer(Modifier.width(16.dp))
            DownGlyph()
        }
        Hairline()
    }
}

/**
 * The row affordance: a small stroked down-arrow. Drawn rather than reusing
 * [Mark], which has too much detail to survive at this size.
 */
@Composable
fun DownGlyph(modifier: Modifier = Modifier, color: Color = Palette.inkTertiary) {
    androidx.compose.foundation.Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w * 0.13f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.10f),
            end = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.86f),
            strokeWidth = stroke.width,
            cap = stroke.cap,
        )
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.20f, h * 0.55f)
                lineTo(w / 2f, h * 0.88f)
                lineTo(w * 0.80f, h * 0.55f)
            },
            color = color,
            style = stroke,
        )
    }
}

/**
 * Thin ink progress line.
 *
 * A null fraction means "started, no percentage yet" and animates a travelling
 * segment — a static partial bar reads as a stuck download.
 */
@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Palette.hairline)
    ) {
        val target = fraction?.coerceIn(0f, 1f)
        if (target == null) {
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val travel by transition.animateFloat(
                initialValue = -0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "travel",
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .offset(x = maxWidth * travel)
                        .width(maxWidth * 0.3f)
                        .height(2.dp)
                        .background(Palette.ink)
                )
            }
        } else {
            val animated by animateFloatAsState(target, tween(220), label = "progress")
            Box(Modifier.fillMaxWidth(animated).height(2.dp).background(Palette.ink))
        }
    }
}

/** Key/value line: label left in micro-caps, value right in mono. */
@Composable
fun MetaRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = Type.label)
        Text(
            value,
            style = Type.numeralSmall.copy(color = Palette.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

/** Placeholder shown when a link has no thumbnail. */
@Composable
fun ThumbnailPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Palette.surface)
            .border(1.dp, Palette.hairline),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp)) {
            Mark(color = Palette.inkTertiary)
        }
    }
}

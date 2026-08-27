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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Primary action: a solid near-white slab that presses in slightly.
 *
 * Not a Material Button — those bring their own elevation, shape and colour
 * opinions, and overriding all three costs more than drawing it.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = label,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.975f else 1f, tween(110), label = "press"
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(ControlRadius))
            .background(if (enabled) Palette.accent else Palette.surfaceRaised)
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
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Palette.onAccent,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                if (loading) loadingLabel else label,
                style = Type.button,
                color = if (enabled) Palette.onAccent else Palette.textTertiary,
            )
        }
    }
}

/** Bordered secondary action, sized to its label. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.textSecondary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) Palette.surfacePressed else Color.Transparent)
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, style = Type.label.copy(color = color, letterSpacing = 0.8.sp))
    }
}

/** Text-only action for low-weight controls inside rows. */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.textSecondary,
) {
    Text(
        label,
        style = Type.label.copy(color = color),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** A circular icon button. Used for clearing the input. */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (pressed) Palette.surfacePressed else Palette.surfaceRaised)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            val stroke = size.width * 0.16f
            drawLine(
                Palette.textSecondary, Offset(0f, 0f), Offset(size.width, size.height),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
            drawLine(
                Palette.textSecondary, Offset(size.width, 0f), Offset(0f, size.height),
                strokeWidth = stroke, cap = StrokeCap.Round,
            )
        }
    }
}

enum class NoticeTone { NEUTRAL, ERROR, SUCCESS, WARNING }

private fun NoticeTone.color(): Color = when (this) {
    NoticeTone.NEUTRAL -> Palette.textSecondary
    NoticeTone.ERROR -> Palette.danger
    NoticeTone.SUCCESS -> Palette.success
    NoticeTone.WARNING -> Palette.warning
}

/**
 * A message on a raised surface with a small status dot.
 *
 * The dot is the only saturated colour; the surface stays neutral. A fully
 * tinted panel would shout on a dark page.
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

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(Palette.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = Type.small.copy(color = Palette.text),
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Spacer(Modifier.width(8.dp))
                CloseButton(onDismiss)
            }
        }

        if (detail != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (showDetail) "Hide details" else "Show details",
                style = Type.label.copy(color = accent),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showDetail = !showDetail }
                    .padding(vertical = 4.dp),
            )
            AnimatedVisibility(showDetail) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.base)
                            .padding(12.dp)
                    ) {
                        // yt-dlp's own words, verbatim. When a platform breaks,
                        // this is the only useful thing on screen.
                        Text(detail, style = Type.mono)
                    }
                }
            }
        }
    }
}

/** Status dot plus label, for the top bar. */
@Composable
fun StatusChip(label: String, tone: NoticeTone, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surface)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(tone.color()))
        Spacer(Modifier.width(7.dp))
        Text(label.uppercase(), style = Type.label)
    }
}

/**
 * A quality row: a tappable card, not a table line.
 *
 * Cards give each option a real hit target and let the whole row respond to
 * touch, which a hairline-separated list never quite does on a phone.
 */
@Composable
fun QualityCard(
    headline: String,
    note: String,
    trailing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(if (pressed) Palette.surfacePressed else Palette.surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(headline, style = Type.figure)
            Spacer(Modifier.height(3.dp))
            Text(note, style = Type.small.copy(color = Palette.textTertiary))
        }
        Text(trailing, style = Type.figureSmall)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Palette.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            DownGlyph(color = Palette.text)
        }
    }
}

/** Small stroked down-arrow. */
@Composable
fun DownGlyph(modifier: Modifier = Modifier, color: Color = Palette.textSecondary) {
    androidx.compose.foundation.Canvas(modifier.size(13.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        drawLine(
            color, Offset(w / 2f, h * 0.08f), Offset(w / 2f, h * 0.84f),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        drawLine(
            color, Offset(w * 0.18f, h * 0.52f), Offset(w / 2f, h * 0.88f),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        drawLine(
            color, Offset(w * 0.82f, h * 0.52f), Offset(w / 2f, h * 0.88f),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
    }
}

/**
 * Progress bar. A null fraction means "started, no percentage yet" and animates
 * a travelling segment — a static partial bar reads as a stuck download.
 */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.surfaceRaised)
    ) {
        val target = fraction?.coerceIn(0f, 1f)
        if (target == null) {
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val travel by transition.animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
                label = "travel",
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .offset(x = maxWidth * travel)
                        .width(maxWidth * 0.35f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Palette.text)
                )
            }
        } else {
            val animated by animateFloatAsState(target, tween(240), label = "progress")
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.text)
            )
        }
    }
}

/** Label left, value right. */
@Composable
fun MetaRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = Type.label)
        Text(
            value,
            style = Type.small.copy(color = Palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

/** Shown when a link has no thumbnail. */
@Composable
fun ThumbnailPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.background(Palette.surface),
        contentAlignment = Alignment.Center,
    ) {
        AnyDownLogo(Modifier.size(52.dp), alpha = 0.35f)
    }
}

package com.anydown.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.anydown.downloader.data.YtDlpSource
import com.anydown.downloader.domain.Filenames
import com.anydown.downloader.domain.FormatPlanner
import com.anydown.downloader.domain.Platforms
import com.anydown.downloader.service.DownloadBus

/* ------------------------------------------------------------------------ */
/* Acknowledgement                                                          */
/* ------------------------------------------------------------------------ */

@Composable
fun AcknowledgementScreen(onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.base)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(64.dp))
        AnyDownLogo(Modifier.size(56.dp))

        Spacer(Modifier.height(36.dp))
        Text("Before you\nbegin", style = Type.display)

        Spacer(Modifier.height(18.dp))
        Text(
            "This tool is for personal use. Downloading may breach a platform's " +
                "terms of service, and most content is protected by copyright.",
            style = Type.body,
        )

        Spacer(Modifier.height(32.dp))
        listOf(
            "Public content only" to "Nothing private or login-walled.",
            "No DRM" to "Paid and protected content is out of scope.",
            "Private viewing" to "Never redistribution.",
        ).forEachIndexed { index, (heading, detail) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CardRadius))
                    .background(Palette.surface)
                    .padding(16.dp)
            ) {
                Text(
                    "0${index + 1}",
                    style = Type.figureSmall.copy(color = Palette.textTertiary),
                    modifier = Modifier.padding(end = 16.dp, top = 2.dp),
                )
                Column {
                    Text(heading, style = Type.bodyBright)
                    Spacer(Modifier.height(2.dp))
                    Text(detail, style = Type.small.copy(color = Palette.textTertiary))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(26.dp))
        PrimaryButton("I have the right to download", onAccept)

        Spacer(Modifier.height(14.dp))
        Text(
            "Shown once.",
            style = Type.small.copy(color = Palette.textTertiary),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(48.dp))
    }
}

/* ------------------------------------------------------------------------ */
/* Home                                                                     */
/* ------------------------------------------------------------------------ */

@Composable
fun HomeScreen(
    state: DownloaderViewModel.UiState,
    jobs: List<DownloadBus.Job>,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    onDownload: (FormatPlanner.Option) -> Unit,
    onUpdateEngine: () -> Unit,
    onDismissMessages: () -> Unit,
    onCancelJob: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    val busy = state.stage is DownloaderViewModel.Stage.Resolving

    Column(Modifier.fillMaxSize().background(Palette.base)) {
        TopBar(state.engineReady, state.canMerge, onUpdateEngine)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter),
        ) {
            Spacer(Modifier.height(26.dp))
            UrlField(
                url = state.url,
                onUrlChange = onUrlChange,
                onSubmit = onFetch,
                enabled = !busy,
            )

            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                label = "Fetch",
                onClick = onFetch,
                enabled = state.url.isNotBlank(),
                loading = busy,
                loadingLabel = "Reading link",
            )

            if (!state.engineReady) {
                Spacer(Modifier.height(16.dp))
                Notice("Unpacking the download engine. First launch only.")
            } else if (!state.canMerge) {
                Spacer(Modifier.height(16.dp))
                Notice(
                    "ffmpeg didn't load, so video and audio can't be combined. " +
                        "Quality is capped and some sites won't work at all.",
                    tone = NoticeTone.WARNING,
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Notice(
                    message = error.message,
                    tone = NoticeTone.ERROR,
                    detail = error.detail,
                    onDismiss = onDismissMessages,
                )
            }

            state.notice?.let { notice ->
                Spacer(Modifier.height(16.dp))
                Notice(notice, NoticeTone.SUCCESS, onDismiss = onDismissMessages)
            }

            (state.stage as? DownloaderViewModel.Stage.Ready)?.let { ready ->
                Spacer(Modifier.height(34.dp))
                ResultBlock(ready.media, onDownload)
            }

            if (jobs.isNotEmpty()) {
                Spacer(Modifier.height(36.dp))
                ActivityBlock(jobs, onCancelJob, onClearFinished)
            }

            Spacer(Modifier.height(40.dp))
            Text(
                "Public content only  ·  Personal use",
                style = Type.label,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TopBar(engineReady: Boolean, canMerge: Boolean, onUpdateEngine: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The artwork is already a rounded dark tile, so it needs no container.
        AnyDownLogo(Modifier.size(34.dp))
        Spacer(Modifier.width(11.dp))
        Text("AnyDown", style = Type.title.copy(fontWeight = FontWeight.ExtraBold))

        Spacer(Modifier.weight(1f))

        StatusChip(
            label = when {
                !engineReady -> "Setting up"
                !canMerge -> "Limited"
                else -> "Ready"
            },
            tone = when {
                !engineReady -> NoticeTone.NEUTRAL
                !canMerge -> NoticeTone.WARNING
                else -> NoticeTone.SUCCESS
            },
        )
        Spacer(Modifier.width(8.dp))
        GhostButton("Update", onUpdateEngine)
    }
}

/**
 * The paste field, on its own raised surface with a clear button.
 *
 * A Material OutlinedTextField would put a rounded outline and a floating label
 * around this — the most recognisable stock-Android form tell there is.
 */
@Composable
private fun UrlField(
    url: String,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val platform = remember(url) {
        url.takeIf { it.isNotBlank() }?.let(Platforms::detectPlatform)
    }

    val selectionColors = TextSelectionColors(
        handleColor = Palette.text,
        backgroundColor = Palette.text.copy(alpha = 0.24f),
    )

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(Palette.surface)
                .border(
                    width = 1.dp,
                    color = if (focused) Palette.borderStrong else Palette.border,
                    shape = RoundedCornerShape(CardRadius),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                BasicTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = Type.inputText,
                    cursorBrush = SolidColor(Palette.text),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box {
                            if (url.isEmpty()) {
                                Text(
                                    "Paste a link",
                                    style = Type.inputText.copy(color = Palette.textTertiary),
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            if (url.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                CloseButton(onClick = { onUrlChange("") })
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (platform != null) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Palette.success)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when {
                    platform != null -> platform.label
                    url.isBlank() -> "Any site yt-dlp supports"
                    else -> "Unrecognised site — will still try"
                },
                style = Type.label.copy(
                    color = if (platform != null) Palette.textSecondary else Palette.textTertiary,
                    letterSpacing = 0.6.sp,
                ),
            )
        }
    }
}

@Composable
private fun ResultBlock(
    media: YtDlpSource.Resolved,
    onDownload: (FormatPlanner.Option) -> Unit,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(CardRadius))
        ) {
            if (media.thumbnail != null) {
                SubcomposeAsyncImage(
                    model = media.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = { ThumbnailPlaceholder(Modifier.fillMaxSize()) },
                    error = { ThumbnailPlaceholder(Modifier.fillMaxSize()) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ThumbnailPlaceholder(Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(media.title, style = Type.title, maxLines = 3, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(Palette.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            media.uploader?.let { MetaRow("Source", it) }
            Filenames.formatDuration(media.durationSeconds)?.let { MetaRow("Length", it) }
            MetaRow("Options", media.options.size.toString())
        }

        Spacer(Modifier.height(28.dp))
        Text("CHOOSE QUALITY", style = Type.labelBright)
        Spacer(Modifier.height(12.dp))

        media.options.forEach { option ->
            QualityCard(
                headline = option.label,
                note = when (option.kind) {
                    FormatPlanner.Kind.MERGE -> "Video + audio, combined on device"
                    FormatPlanner.Kind.AUDIO -> "Audio only"
                    FormatPlanner.Kind.IMAGE -> "Still image"
                    FormatPlanner.Kind.PROGRESSIVE -> "Single stream"
                },
                trailing = Filenames.formatBytes(option.sizeBytes) ?: "—",
                onClick = { onDownload(option) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Sizes are the platform's estimate. Files save to Downloads/AnyDown.",
            style = Type.small.copy(color = Palette.textTertiary),
        )
    }
}

@Composable
private fun ActivityBlock(
    jobs: List<DownloadBus.Job>,
    onCancelJob: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    val hasFinished = jobs.any {
        it.status != DownloadBus.Status.RUNNING && it.status != DownloadBus.Status.QUEUED
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ACTIVITY", style = Type.labelBright, modifier = Modifier.weight(1f))
            if (hasFinished) TextAction("Clear", onClearFinished, color = Palette.textTertiary)
        }
        Spacer(Modifier.height(12.dp))

        jobs.forEach { job ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CardRadius))
                    .background(Palette.surface)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            job.title,
                            style = Type.bodyBright,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            job.message ?: job.label,
                            style = Type.small.copy(
                                color = when (job.status) {
                                    DownloadBus.Status.FAILED -> Palette.danger
                                    DownloadBus.Status.DONE -> Palette.success
                                    DownloadBus.Status.CANCELLED -> Palette.warning
                                    else -> Palette.textTertiary
                                }
                            ),
                        )
                    }

                    if (job.status == DownloadBus.Status.RUNNING ||
                        job.status == DownloadBus.Status.QUEUED
                    ) {
                        Text(
                            if (job.percent > 0f) "${job.percent.toInt()}%" else "···",
                            style = Type.figureSmall.copy(color = Palette.text),
                        )
                        TextAction("Stop", { onCancelJob(job.id) }, color = Palette.danger)
                    }
                }

                if (job.status == DownloadBus.Status.RUNNING ||
                    job.status == DownloadBus.Status.QUEUED
                ) {
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(job.percent.takeIf { it > 0f }?.div(100f))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

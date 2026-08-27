package com.anydown.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
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
            .background(Palette.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(72.dp))

        Box(Modifier.size(40.dp)) { Mark() }

        Spacer(Modifier.height(40.dp))
        Text("Before you begin", style = Type.display)

        Spacer(Modifier.height(20.dp))
        Text(
            "This tool is for personal use. Downloading may breach a platform's " +
                "terms of service, and most content is protected by copyright.",
            style = Type.body,
        )

        Spacer(Modifier.height(40.dp))
        SectionLabel("The rules")
        Spacer(Modifier.height(4.dp))

        listOf(
            "01" to "Public content only. Nothing private or login-walled.",
            "02" to "No DRM-protected or paid content.",
            "03" to "For private viewing. Never redistribution.",
        ).forEach { (index, line) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text(index, style = Type.numeralSmall.copy(color = Palette.inkTertiary))
                Spacer(Modifier.width(18.dp))
                Text(line, style = Type.bodyInk, modifier = Modifier.weight(1f))
            }
            Hairline()
        }

        Spacer(Modifier.height(40.dp))
        PrimaryAction("I have the right to download", onAccept)

        Spacer(Modifier.height(16.dp))
        Text(
            "Shown once. You can't undo this from inside the app.",
            style = Type.small.copy(color = Palette.inkTertiary),
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.paper)
    ) {
        TopBar(
            engineReady = state.engineReady,
            canMerge = state.canMerge,
            onUpdateEngine = onUpdateEngine,
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter),
        ) {
            Spacer(Modifier.height(36.dp))
            UrlField(
                url = state.url,
                onUrlChange = onUrlChange,
                onSubmit = onFetch,
                enabled = !busy,
            )

            Spacer(Modifier.height(28.dp))
            PrimaryAction(
                label = "Fetch",
                onClick = onFetch,
                enabled = state.url.isNotBlank(),
                loading = busy,
                loadingLabel = "Reading",
            )

            if (!state.engineReady) {
                Spacer(Modifier.height(24.dp))
                Notice(
                    "Unpacking the download engine. First launch only.",
                    tone = NoticeTone.NEUTRAL,
                )
            } else if (!state.canMerge) {
                Spacer(Modifier.height(24.dp))
                Notice(
                    "ffmpeg didn't load, so video and audio can't be combined. " +
                        "Quality is capped and some sites won't work at all.",
                    tone = NoticeTone.WARNING,
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(24.dp))
                Notice(
                    message = error.message,
                    tone = NoticeTone.ERROR,
                    detail = error.detail,
                    onDismiss = onDismissMessages,
                )
            }

            state.notice?.let { notice ->
                Spacer(Modifier.height(24.dp))
                Notice(notice, tone = NoticeTone.SUCCESS, onDismiss = onDismissMessages)
            }

            (state.stage as? DownloaderViewModel.Stage.Ready)?.let { ready ->
                Spacer(Modifier.height(44.dp))
                ResultBlock(media = ready.media, onDownload = onDownload)
            }

            if (jobs.isNotEmpty()) {
                Spacer(Modifier.height(48.dp))
                ActivityBlock(
                    jobs = jobs,
                    onCancelJob = onCancelJob,
                    onClearFinished = onClearFinished,
                )
            }

            Spacer(Modifier.height(56.dp))
            Footer()
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TopBar(
    engineReady: Boolean,
    canMerge: Boolean,
    onUpdateEngine: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Gutter, end = Gutter - 8.dp, top = 20.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp)) { Mark() }
            Spacer(Modifier.width(11.dp))
            Text("ANYDOWN", style = Type.labelInk)

            Spacer(Modifier.weight(1f))

            StatusChip(
                label = when {
                    !engineReady -> "Preparing"
                    !canMerge -> "Limited"
                    else -> "Ready"
                },
                tone = when {
                    !engineReady -> NoticeTone.NEUTRAL
                    !canMerge -> NoticeTone.WARNING
                    else -> NoticeTone.SUCCESS
                },
            )
            Spacer(Modifier.width(4.dp))
            QuietAction("Update", onUpdateEngine, color = Palette.inkSecondary)
        }
        Hairline()
    }
}

/**
 * Borderless input on a hairline that thickens to ink on focus.
 *
 * A Material OutlinedTextField would put a rounded box with a floating label
 * around this, which is the single most recognisable "stock Android form" tell.
 */
@Composable
private fun UrlField(
    url: String,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val platform = remember(url) { url.takeIf { it.isNotBlank() }?.let(Platforms::detectPlatform) }

    val selectionColors = TextSelectionColors(
        handleColor = Palette.ink,
        backgroundColor = Palette.ink.copy(alpha = 0.16f),
    )

    Column {
        Text("Paste a link", style = Type.label)
        Spacer(Modifier.height(14.dp))

        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                enabled = enabled,
                singleLine = true,
                textStyle = Type.body.copy(
                    color = Palette.ink,
                    fontSize = 18.sp,
                ),
                cursorBrush = SolidColor(Palette.ink),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (url.isEmpty()) {
                            Text(
                                "https://",
                                style = Type.body.copy(
                                    color = Palette.inkTertiary,
                                    fontSize = 18.sp,
                                ),
                            )
                        }
                        inner()
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        Hairline(color = if (focused) Palette.ink else Palette.hairlineStrong)

        Spacer(Modifier.height(10.dp))
        Text(
            when {
                platform != null -> platform.label.uppercase()
                url.isBlank() -> "ANY SITE YT-DLP SUPPORTS"
                else -> "UNRECOGNISED SITE — WILL STILL TRY"
            },
            style = Type.label.copy(
                color = if (platform != null) Palette.ink else Palette.inkTertiary
            ),
        )
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
        ) {
            if (media.thumbnail != null) {
                SubcomposeAsyncImage(
                    model = media.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = { ThumbnailPlaceholder(Modifier.fillMaxSize()) },
                    error = { ThumbnailPlaceholder(Modifier.fillMaxSize()) },
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Palette.hairline),
                )
            } else {
                ThumbnailPlaceholder(Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            media.title,
            style = Type.title,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(18.dp))
        Hairline()
        media.uploader?.let { MetaRow("Source", it) }
        Filenames.formatDuration(media.durationSeconds)?.let { MetaRow("Length", it) }
        MetaRow("Options", media.options.size.toString())
        Hairline()

        Spacer(Modifier.height(36.dp))
        SectionLabel("Choose quality")
        Hairline()

        media.options.forEach { option ->
            QualityRow(
                headline = option.label,
                note = when (option.kind) {
                    FormatPlanner.Kind.MERGE -> "video + audio combined"
                    FormatPlanner.Kind.AUDIO -> "audio only"
                    FormatPlanner.Kind.PROGRESSIVE -> "single stream"
                },
                trailing = Filenames.formatBytes(option.sizeBytes) ?: "—",
                onClick = { onDownload(option) },
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Sizes are the platform's estimate. Files save to Downloads/AnyDown.",
            style = Type.small.copy(color = Palette.inkTertiary),
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
            SectionLabel("Activity", Modifier.weight(1f))
            if (hasFinished) {
                Spacer(Modifier.width(8.dp))
                QuietAction("Clear", onClearFinished, color = Palette.inkTertiary)
            }
        }
        Hairline()

        jobs.forEach { job ->
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            job.title,
                            style = Type.bodyInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            (job.message ?: job.label).uppercase(),
                            style = Type.label.copy(
                                color = when (job.status) {
                                    DownloadBus.Status.FAILED -> Palette.danger
                                    DownloadBus.Status.DONE -> Palette.success
                                    DownloadBus.Status.CANCELLED -> Palette.warning
                                    else -> Palette.inkTertiary
                                }
                            ),
                        )
                    }

                    when (job.status) {
                        DownloadBus.Status.RUNNING, DownloadBus.Status.QUEUED -> {
                            Text(
                                if (job.percent > 0f) "${job.percent.toInt()}%" else "···",
                                style = Type.numeralSmall.copy(color = Palette.ink),
                            )
                            QuietAction("Stop", { onCancelJob(job.id) }, color = Palette.danger)
                        }
                        else -> Unit
                    }
                }

                if (job.status == DownloadBus.Status.RUNNING ||
                    job.status == DownloadBus.Status.QUEUED
                ) {
                    Spacer(Modifier.height(14.dp))
                    ProgressLine(job.percent.takeIf { it > 0f }?.div(100f))
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun Footer() {
    Column {
        Hairline()
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("PUBLIC CONTENT ONLY", style = Type.label)
            Text("PERSONAL USE", style = Type.label)
        }
    }
}

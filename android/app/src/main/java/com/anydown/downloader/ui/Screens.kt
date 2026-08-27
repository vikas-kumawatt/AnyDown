package com.anydown.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anydown.downloader.data.YtDlpSource
import com.anydown.downloader.domain.Filenames
import com.anydown.downloader.domain.FormatPlanner
import com.anydown.downloader.domain.Platforms
import com.anydown.downloader.service.DownloadBus

@Composable
fun AcknowledgementScreen(onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Before you start",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "This tool is for personal use only. Downloading may breach a " +
                "platform's terms of service, and most content is protected by " +
                "copyright.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "Public content only — nothing private or login-walled.",
            "No DRM-protected or paid content.",
            "For private viewing. Not for redistribution.",
        ).forEach { line ->
            Text(
                "•  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("I have the right to download this content")
        }
    }
}

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
) {
    val platform = state.url.takeIf { it.isNotBlank() }?.let(Platforms::detectPlatform)
    val unknown = state.url.trim().length > 8 && platform == null
    val busy = state.stage is DownloaderViewModel.Stage.Resolving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "AnyDown",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Public content only. Personal use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUpdateEngine) { Text("Update") }
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text("Paste a link") },
            placeholder = { Text("https://…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { if (!busy) onFetch() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))
        when {
            platform != null -> Text(
                "Detected: ${platform.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            unknown -> Text(
                "Not a recognised platform link.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onFetch,
            enabled = !busy && state.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Fetching…")
            } else {
                Text("Fetch")
            }
        }

        if (!state.engineReady) {
            Spacer(Modifier.height(12.dp))
            NoticeCard("Preparing the download engine — first run only.", isError = false)
        }

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            NoticeCard(it, isError = true, onDismiss = onDismissMessages)
        }
        state.notice?.let {
            Spacer(Modifier.height(12.dp))
            NoticeCard(it, isError = false, onDismiss = onDismissMessages)
        }

        (state.stage as? DownloaderViewModel.Stage.Ready)?.let { ready ->
            Spacer(Modifier.height(16.dp))
            MediaCard(media = ready.media, onDownload = onDownload)
        }

        if (jobs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Downloads",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            jobs.forEach { job ->
                JobRow(job = job, onCancel = { onCancelJob(job.id) })
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            Platforms.ALL.joinToString(" · ") { it.label },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaCard(
    media: YtDlpSource.Resolved,
    onDownload: (FormatPlanner.Option) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                media.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                media.uploader,
                Filenames.formatDuration(media.durationSeconds),
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            media.options.forEach { option ->
                OutlinedButton(
                    onClick = { onDownload(option) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, fontWeight = FontWeight.Medium)
                        val note = when (option.kind) {
                            FormatPlanner.Kind.MERGE -> "merged on the fly"
                            FormatPlanner.Kind.AUDIO -> "audio only"
                            FormatPlanner.Kind.PROGRESSIVE -> null
                        }
                        note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        Filenames.formatBytes(option.sizeBytes) ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Sizes are estimates from the platform. Files save to " +
                    "Downloads/AnyDown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JobRow(job: DownloadBus.Job, onCancel: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        job.message ?: "${job.label} · ${job.status.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (job.status == DownloadBus.Status.RUNNING ||
                    job.status == DownloadBus.Status.QUEUED
                ) {
                    TextButton(onClick = onCancel) { Text("Stop") }
                }
            }
            if (job.status == DownloadBus.Status.RUNNING) {
                Spacer(Modifier.height(8.dp))
                if (job.percent > 0f) {
                    LinearProgressIndicator(
                        progress = { (job.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null,
) {
    val container = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container.copy(alpha = if (isError) 0.16f else 1f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            onDismiss?.let {
                TextButton(onClick = it) { Text("Dismiss") }
            }
        }
    }
}

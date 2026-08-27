package com.anydown.downloader

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.anydown.downloader.service.DownloadBus
import com.anydown.downloader.service.DownloadService
import com.anydown.downloader.ui.AcknowledgementScreen
import com.anydown.downloader.ui.AnyDownTheme
import com.anydown.downloader.ui.DownloaderViewModel
import com.anydown.downloader.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private val viewModel: DownloaderViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Declined just means no progress notification. Downloads still run,
            // so there's nothing to recover from here.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Explicitly dark, not `auto`. The default picks bar icon colour from
        // the system dark-mode setting, so a phone in light mode would get dark
        // icons on this dark page — invisible. The app is dark-only, so the
        // bars are pinned dark regardless of the system setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // Android 13+ needs this before the foreground service notification can
        // be shown, and a foreground service is what keeps downloads alive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleShareIntent(intent)

        setContent {
            AnyDownTheme {
                val state by viewModel.state.collectAsState()
                val jobs by DownloadBus.jobs.collectAsState()

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing)
                ) {
                    if (!state.acknowledged) {
                        AcknowledgementScreen(onAccept = viewModel::acknowledge)
                    } else {
                        HomeScreen(
                            state = state,
                            jobs = jobs,
                            onUrlChange = viewModel::onUrlChange,
                            onFetch = viewModel::resolve,
                            onDownload = viewModel::download,
                            onUpdateEngine = viewModel::updateEngine,
                            onDismissMessages = viewModel::dismissMessages,
                            onCancelJob = { jobId ->
                                DownloadService.cancel(this@MainActivity, jobId)
                            },
                            onClearFinished = viewModel::clearFinishedJobs,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Handles Share → AnyDown from another app, so you don't have to copy and
     * paste the link. Shared text often has a title and other noise around the
     * URL, so pull the first URL out rather than taking it wholesale.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        viewModel.onUrlChange(url)
        viewModel.resolve()
    }
}

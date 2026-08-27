package com.anydown.downloader

import android.app.Application
import android.util.Log
import com.anydown.downloader.data.YtDlpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownloaderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // First init unpacks the bundled Python runtime and yt-dlp into app
        // storage, which takes a few seconds. Warming it here off the main
        // thread means the first paste-and-fetch isn't the one that pays for it.
        // The UI still awaits the same suspend function, so a slow device just
        // shows "Preparing" instead of racing.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { YtDlpSource.ensureInitialised(this@DownloaderApplication) }
                .onFailure { Log.w("DownloaderApplication", "warm-up failed", it) }

            // The yt-dlp bundled in the library is already months stale by the
            // time an APK is built, which is why TikTok and Dailymotion fail on
            // a fresh install. Refresh it quietly, at most once a day, instead
            // of waiting for the user to find the Update button.
            runCatching { YtDlpSource.updateIfStale(this@DownloaderApplication) }
                .onFailure { Log.i("DownloaderApplication", "update check skipped", it) }
        }
    }
}

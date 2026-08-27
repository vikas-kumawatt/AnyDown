package com.anydown.downloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anydown.downloader.MainActivity
import com.anydown.downloader.R
import com.anydown.downloader.data.YtDlpSource
import com.anydown.downloader.domain.FormatPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs downloads in a foreground service.
 *
 * This isn't optional polish: Android freezes or kills background processes, and
 * a long video takes minutes. Without a foreground service and its notification,
 * downloads die the moment the app leaves the screen.
 *
 * Jobs are handled one at a time through a channel. A phone on mobile data
 * gains nothing from parallel downloads, and serialising keeps the notification
 * honest about what's happening.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val FOREGROUND_ID = 1

        const val ACTION_ENQUEUE = "com.anydown.downloader.ENQUEUE"
        const val ACTION_CANCEL = "com.anydown.downloader.CANCEL"

        private const val EXTRA_JOB_ID = "jobId"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SELECTOR = "selector"
        private const val EXTRA_MERGE = "merge"
        private const val EXTRA_DIRECT_URL = "directUrl"
        private const val EXTRA_HEADERS = "headers"

        fun enqueue(
            context: Context,
            url: String,
            title: String,
            option: FormatPlanner.Option,
        ): String {
            val jobId = "job-${System.currentTimeMillis()}-${option.id}"
            DownloadBus.put(
                DownloadBus.Job(
                    id = jobId,
                    title = title,
                    label = option.label,
                    status = DownloadBus.Status.QUEUED,
                )
            )
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_LABEL, option.label)
                putExtra(EXTRA_SELECTOR, option.selector)
                putExtra(EXTRA_MERGE, option.mergeContainer)
                // Custom-resolver media carries its own URL and headers.
                putExtra(EXTRA_DIRECT_URL, option.directUrl)
                putExtra(EXTRA_HEADERS, option.headerBlob)
            }
            // startForegroundService requires us to call startForeground within
            // ~5 seconds, which onStartCommand does immediately.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return jobId
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }
    }

    private data class Task(
        val jobId: String,
        val url: String,
        val title: String,
        val label: String,
        val selector: String,
        val mergeContainer: String?,
        val directUrl: String?,
        val headerBlob: String?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private var activeJobs = 0

    private val notifications: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { consume() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { jobId ->
                    YtDlpSource.cancel(jobId)
                    DownloadBus.update(jobId) {
                        it.copy(status = DownloadBus.Status.CANCELLED, message = "Cancelled")
                    }
                }
            }

            ACTION_ENQUEUE -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                val url = intent.getStringExtra(EXTRA_URL)
                val selector = intent.getStringExtra(EXTRA_SELECTOR)
                if (jobId == null || url == null || selector == null) {
                    Log.w(TAG, "malformed enqueue intent")
                } else {
                    activeJobs++
                    startForeground(FOREGROUND_ID, buildProgressNotification(
                        intent.getStringExtra(EXTRA_TITLE) ?: "Downloading",
                        indeterminate = true,
                        percent = 0f,
                    ))
                    queue.trySend(
                        Task(
                            jobId = jobId,
                            url = url,
                            title = intent.getStringExtra(EXTRA_TITLE) ?: "download",
                            label = intent.getStringExtra(EXTRA_LABEL) ?: "",
                            selector = selector,
                            mergeContainer = intent.getStringExtra(EXTRA_MERGE),
                            directUrl = intent.getStringExtra(EXTRA_DIRECT_URL),
                            headerBlob = intent.getStringExtra(EXTRA_HEADERS),
                        )
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun consume() {
        for (task in queue) {
            runTask(task)
            activeJobs--
            if (activeJobs <= 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runTask(task: Task) {
        DownloadBus.update(task.jobId) {
            it.copy(status = DownloadBus.Status.RUNNING, percent = 0f)
        }

        try {
            val option = FormatPlanner.Option(
                id = task.jobId,
                label = task.label,
                ext = task.mergeContainer ?: "",
                sizeBytes = null,
                kind = if (task.mergeContainer != null) FormatPlanner.Kind.MERGE
                else FormatPlanner.Kind.PROGRESSIVE,
                selector = task.selector,
                mergeContainer = task.mergeContainer,
                directUrl = task.directUrl,
                headerBlob = task.headerBlob,
            )

            val file = YtDlpSource.download(
                context = applicationContext,
                rawUrl = task.url,
                title = task.title,
                option = option,
                processId = task.jobId,
            ) { progress ->
                DownloadBus.update(task.jobId) {
                    it.copy(percent = progress.percent, etaSeconds = progress.etaSeconds)
                }
                notifications.notify(
                    FOREGROUND_ID,
                    buildProgressNotification(
                        task.title,
                        indeterminate = progress.percent <= 0f,
                        percent = progress.percent,
                    ),
                )
            }

            DownloadBus.update(task.jobId) {
                it.copy(
                    status = DownloadBus.Status.DONE,
                    percent = 100f,
                    message = "Saved to Downloads/AnyDown",
                    filePath = file.absolutePath,
                )
            }
            notifyFinished(task, "Saved to Downloads/AnyDown")
        } catch (e: Exception) {
            // A cancel arrives as a process kill, which surfaces here as a
            // failure. Don't overwrite the cancelled state with an error.
            val wasCancelled =
                DownloadBus.get(task.jobId)?.status == DownloadBus.Status.CANCELLED
            if (wasCancelled) {
                Log.i(TAG, "job ${task.jobId} cancelled")
                notifications.cancel(task.jobId.hashCode())
                return
            }

            val message = (e as? YtDlpSource.SourceException)?.classified?.message
                ?: e.message ?: "Download failed."
            Log.w(TAG, "job ${task.jobId} failed", e)
            DownloadBus.update(task.jobId) {
                it.copy(status = DownloadBus.Status.FAILED, message = message)
            }
            notifyFinished(task, message)
        }
    }

    // ----------------------------------------------------------------------
    // Notifications
    // ----------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW, // silent: progress shouldn't buzz
        ).apply {
            description = getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildProgressNotification(
        title: String,
        indeterminate: Boolean,
        percent: Float,
    ): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(
            if (indeterminate) "Starting…" else "${percent.toInt()}%"
        )
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent.toInt().coerceIn(0, 100), indeterminate)
        .setContentIntent(contentIntent())
        .build()

    private fun notifyFinished(task: Task, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(task.title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        notifications.notify(task.jobId.hashCode(), notification)
    }

    override fun onDestroy() {
        queue.close()
        scope.cancel()
        super.onDestroy()
    }
}

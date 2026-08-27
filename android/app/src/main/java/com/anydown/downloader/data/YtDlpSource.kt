package com.anydown.downloader.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.anydown.downloader.domain.Errors
import com.anydown.downloader.domain.Filenames
import com.anydown.downloader.domain.FormatPlanner
import com.anydown.downloader.domain.UrlNormalizer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only file that talks to youtubedl-android.
 *
 * Everything else in the app works on this module's own types, so if the
 * library's field names or method signatures differ from what's written here,
 * this is the single place to fix — nothing downstream needs to change.
 *
 * The library bundles a Python runtime and a lazy-extractor build of yt-dlp,
 * extracting them into app storage on first init. That takes a few seconds, so
 * [ensureInitialised] is lazy and the UI shows a "Preparing" state rather than
 * blocking app start.
 *
 * Three symbols here were written against the library's documented usage rather
 * than a local compile, so check these first if the build complains:
 *  1. `YoutubeDL.UpdateChannel.STABLE` — some versions name the constants
 *     `_STABLE` / `_NIGHTLY`, or expose `UpdateChannel` as a top-level type.
 *  2. `execute(request, processId) { progress, eta, line -> }` — older versions
 *     take the callback before the process id and omit the `line` argument.
 *  3. [VideoFormat] field names in [toRawFormat] — `fileSize`, `tbr`, `abr`.
 */
object YtDlpSource {

    private const val TAG = "YtDlpSource"
    private const val OUTPUT_SUBDIR = "AnyDown"
    private const val PREFS = "anydown"
    private const val KEY_LAST_UPDATE = "ytdlp_last_update"
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val REDIRECT_TIMEOUT_MS = 12_000

    private val initMutex = Mutex()
    @Volatile private var initialised = false
    @Volatile private var ffmpegAvailable = false

    /** True once init succeeded and yt-dlp can merge separate streams. */
    val canMerge: Boolean get() = ffmpegAvailable

    class SourceException(
        val classified: Errors.Classified,
        cause: Throwable? = null,
    ) : Exception(classified.message, cause)

    data class Resolved(
        val title: String,
        val thumbnail: String?,
        val durationSeconds: Int?,
        val uploader: String?,
        val options: List<FormatPlanner.Option>,
    )

    data class Progress(val percent: Float, val etaSeconds: Long, val line: String)

    // ----------------------------------------------------------------------
    // Setup
    // ----------------------------------------------------------------------

    suspend fun ensureInitialised(context: Context) {
        if (initialised) return
        initMutex.withLock {
            if (initialised) return
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(context)
                } catch (e: YoutubeDLException) {
                    throw SourceException(
                        Errors.Classified(
                            Errors.Code.FAILED,
                            "Couldn't start the download engine. Reinstalling the app " +
                                "usually fixes this.",
                        ),
                        e,
                    )
                }
                // ffmpeg is a separate artifact. Without it yt-dlp can't merge
                // separate video+audio streams, which caps most platforms near
                // 720p — so treat its absence as a degraded mode, not a crash.
                ffmpegAvailable = try {
                    FFmpeg.getInstance().init(context)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "ffmpeg unavailable; merged formats disabled", e)
                    false
                }
                initialised = true
            }
        }
    }

    /**
     * Updates the bundled yt-dlp in place.
     *
     * This is the app's answer to extractor rot: platforms change, extractors
     * break, and this fixes it without shipping a new APK. The version bundled
     * in the library is however old the library release is — which is why
     * TikTok and Dailymotion fail out of the box with "Unable to extract
     * webpage video data" and "No video formats found".
     *
     * Nightly, not stable: extractor repairs land there first, and for a
     * personal tool a fresher extractor beats a slower-moving one.
     */
    suspend fun updateEngine(context: Context): String = withContext(Dispatchers.IO) {
        ensureInitialised(context)
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            when (status?.name) {
                "ALREADY_UP_TO_DATE" -> "yt-dlp is already up to date."
                else -> "yt-dlp updated. Retry your link."
            }
        } catch (e: Exception) {
            throw SourceException(
                Errors.Classified(
                    Errors.Code.NETWORK,
                    "Update failed. Check your connection and try again.",
                    e.message,
                ),
                e,
            )
        }
    }

    /**
     * Refreshes yt-dlp in the background if it hasn't been done in a while.
     *
     * The bundled extractors are stale the day the APK is built, so waiting for
     * the user to discover the Update button means the first few links they try
     * fail for no visible reason. Silent by design: it either quietly helps or
     * quietly does nothing.
     */
    suspend fun updateIfStale(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        val age = System.currentTimeMillis() - last
        if (last != 0L && age < UPDATE_INTERVAL_MS) return@withContext
        runCatching { updateEngine(context) }
            .onFailure { Log.i(TAG, "background yt-dlp update skipped: ${it.message}") }
        Unit
    }

    // ----------------------------------------------------------------------
    // Resolve
    // ----------------------------------------------------------------------

    /**
     * Follows a share/short link to its destination.
     *
     * Threads share links land on `threads.com`, which yt-dlp's extractor
     * doesn't match — so the redirect has to be resolved here, before
     * [UrlNormalizer] can rewrite the host. Best-effort: on any failure the
     * original URL is returned and yt-dlp gets its usual shot at it.
     */
    private fun followRedirects(url: String): String {
        var current = url
        repeat(5) {
            val connection = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    instanceFollowRedirects = false
                    connectTimeout = REDIRECT_TIMEOUT_MS
                    readTimeout = REDIRECT_TIMEOUT_MS
                    // Some share endpoints only redirect for a real browser.
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    )
                }
            } catch (e: Exception) {
                Log.i(TAG, "redirect resolve failed for $current: ${e.message}")
                return current
            }

            val location = try {
                val code = connection.responseCode
                if (code !in 300..399) return current
                connection.getHeaderField("Location")
            } catch (e: Exception) {
                Log.i(TAG, "redirect read failed: ${e.message}")
                return current
            } finally {
                connection.disconnect()
            }

            if (location.isNullOrBlank()) return current
            current = try {
                URL(URL(current), location).toString()
            } catch (e: Exception) {
                return current
            }
        }
        return current
    }

    /** Resolve short links, then rewrite renamed hosts and strip tracking junk. */
    private fun prepareUrl(raw: String): String {
        val resolved =
            if (UrlNormalizer.isShortLink(raw)) followRedirects(raw.trim()) else raw.trim()
        return UrlNormalizer.normalize(resolved)
    }

    suspend fun resolve(context: Context, rawUrl: String): Resolved =
        withContext(Dispatchers.IO) {
            ensureInitialised(context)
            val url = prepareUrl(rawUrl)
            if (url != rawUrl.trim()) Log.i(TAG, "normalised to $url")

            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
            }

            val info: VideoInfo = try {
                YoutubeDL.getInstance().getInfo(request)
            } catch (e: Exception) {
                throw SourceException(Errors.classify(e.message), e)
            }

            val duration = info.duration.takeIf { it > 0 }
            val raw = (info.formats ?: emptyList()).mapNotNull(::toRawFormat)
            val planned = FormatPlanner.plan(raw, duration?.toDouble())
            val options = planned.filter { canMerge || it.kind != FormatPlanner.Kind.MERGE }

            if (options.isEmpty()) {
                // Distinguish "nothing here" from "everything here needs ffmpeg
                // and ffmpeg is missing". The second case silently broke sites
                // that only offer separate video and audio renditions —
                // Dailymotion and Threads among them — and reported it as if the
                // link had no media at all.
                val blockedByFfmpeg = planned.isNotEmpty() && !canMerge
                throw SourceException(
                    if (blockedByFfmpeg) {
                        Errors.Classified(
                            Errors.Code.NEEDS_FFMPEG,
                            "This link only offers separate video and audio tracks, " +
                                "which need ffmpeg to combine — and ffmpeg didn't load. " +
                                "Reinstall the app to fix it.",
                            "${planned.size} format(s) found, all requiring a merge.",
                        )
                    } else {
                        Errors.Classified(
                            Errors.Code.FAILED,
                            "yt-dlp read the page but found no downloadable media on it.",
                            "${raw.size} raw format(s) reported.",
                        )
                    }
                )
            }

            Resolved(
                title = info.title ?: "Untitled",
                thumbnail = info.thumbnail,
                durationSeconds = duration,
                uploader = info.uploader,
                options = options,
            )
        }

    /**
     * Maps the library's [VideoFormat] onto our own model.
     *
     * Numeric fields are primitives that default to 0 when yt-dlp omitted them,
     * so 0 is treated as "unknown" rather than a real value.
     */
    private fun toRawFormat(format: VideoFormat): FormatPlanner.RawFormat? {
        val id = format.formatId ?: return null
        val bitrate = format.tbr.takeIf { it > 0f } ?: format.abr.takeIf { it > 0f }
        return FormatPlanner.RawFormat(
            formatId = id,
            ext = format.ext,
            height = format.height.takeIf { it > 0 },
            vcodec = format.vcodec,
            acodec = format.acodec,
            fileSize = format.fileSize.takeIf { it > 0 },
            bitrateKbps = bitrate?.toDouble(),
        )
    }

    // ----------------------------------------------------------------------
    // Download
    // ----------------------------------------------------------------------

    /** `Download/AnyDown/`, created if needed. */
    fun outputDirectory(): File {
        @Suppress("DEPRECATION")
        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, OUTPUT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Runs the download. Returns the finished file.
     *
     * The output template uses a base name we generated, so the finished file is
     * found by globbing rather than by parsing yt-dlp's stdout.
     */
    suspend fun download(
        context: Context,
        rawUrl: String,
        title: String?,
        option: FormatPlanner.Option,
        processId: String,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureInitialised(context)

        // Same normalisation as resolve, or the format ids won't match the page
        // that produced them.
        val url = prepareUrl(rawUrl)
        val dir = outputDirectory()
        val base = uniqueBase(dir, Filenames.sanitizeBase(title))

        val request = YoutubeDLRequest(url).apply {
            addOption("-f", option.selector)
            option.mergeContainer?.let { addOption("--merge-output-format", it) }
            addOption("-o", File(dir, "$base.%(ext)s").absolutePath)
            addOption("--no-playlist")
            addOption("--no-warnings")
            // Keep the download timestamp rather than the upload date, so new
            // files sort to the top of the Downloads list.
            addOption("--no-mtime")
        }

        try {
            YoutubeDL.getInstance().execute(request, processId) { progress, etaSeconds, line ->
                onProgress(Progress(progress, etaSeconds, line))
            }
        } catch (e: Exception) {
            cleanUpPartials(dir, base)
            throw SourceException(Errors.classify(e.message), e)
        }

        val finished = dir.listFiles { file ->
            file.name.startsWith("$base.") && !file.name.endsWith(".part")
        }?.maxByOrNull { it.length() }
            ?: throw SourceException(
                Errors.Classified(
                    Errors.Code.FAILED,
                    "The download finished but the file couldn't be found.",
                )
            )

        // Files written with direct file I/O don't show up in the Downloads UI
        // or the gallery until the media scanner indexes them.
        indexForGallery(context, finished)
        finished
    }

    fun cancel(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            Log.w(TAG, "failed to cancel $processId", e)
        }
    }

    /** Avoids silently overwriting a file downloaded earlier. */
    private fun uniqueBase(dir: File, base: String): String {
        if (dir.listFiles { f -> f.name.startsWith("$base.") }?.isEmpty() != false) return base
        var suffix = 2
        while (suffix < 1000) {
            val candidate = "$base ($suffix)"
            if (dir.listFiles { f -> f.name.startsWith("$candidate.") }?.isEmpty() != false) {
                return candidate
            }
            suffix++
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private fun cleanUpPartials(dir: File, base: String) {
        dir.listFiles { file -> file.name.startsWith("$base.") }?.forEach { file ->
            if (!file.delete()) Log.w(TAG, "couldn't remove partial ${file.name}")
        }
    }

    private fun indexForGallery(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "media scan failed for ${file.name}", e)
        }
    }
}

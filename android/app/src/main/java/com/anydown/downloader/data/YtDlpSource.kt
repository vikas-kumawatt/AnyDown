package com.anydown.downloader.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.anydown.downloader.domain.Errors
import com.anydown.downloader.domain.Filenames
import com.anydown.downloader.domain.FormatPlanner
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
     * This is the app's answer to extractor rot (PRD section 13): platforms
     * change and extractors break, and this fixes it without shipping a new APK.
     */
    suspend fun updateEngine(context: Context): String = withContext(Dispatchers.IO) {
        ensureInitialised(context)
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            when (status?.name) {
                "ALREADY_UP_TO_DATE" -> "Already up to date."
                else -> "yt-dlp updated."
            }
        } catch (e: Exception) {
            throw SourceException(
                Errors.Classified(Errors.Code.NETWORK, "Update failed: ${e.message}"),
                e,
            )
        }
    }

    // ----------------------------------------------------------------------
    // Resolve
    // ----------------------------------------------------------------------

    suspend fun resolve(context: Context, url: String): Resolved =
        withContext(Dispatchers.IO) {
            ensureInitialised(context)

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
            val options = FormatPlanner.plan(raw, duration?.toDouble())
                .filter { canMerge || it.kind != FormatPlanner.Kind.MERGE }

            if (options.isEmpty()) {
                throw SourceException(
                    Errors.Classified(
                        Errors.Code.FAILED,
                        "No downloadable media was found at that link.",
                    )
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
            protocol = format.protocol,
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
        url: String,
        title: String?,
        option: FormatPlanner.Option,
        processId: String,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureInitialised(context)

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

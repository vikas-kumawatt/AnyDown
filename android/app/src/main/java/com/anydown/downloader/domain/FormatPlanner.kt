package com.anydown.downloader.domain

/**
 * Decides which qualities to offer.
 *
 * Produces a **yt-dlp format selector** (`"137+140"`) and lets yt-dlp do the
 * download and any merge on-device. All the streaming and pipe plumbing the
 * server version needed is gone — that only existed because bytes had to cross
 * a network.
 *
 * Free of Android and library imports so CI can unit test it on the JVM.
 * [RawFormat] is this module's own model; mapping the library's `VideoFormat`
 * onto it happens in one place, in `YtDlpSource`.
 */
object FormatPlanner {

    /** One entry from yt-dlp's `formats` array, reduced to what we use. */
    data class RawFormat(
        val formatId: String,
        val ext: String?,
        val height: Int?,
        val vcodec: String?,
        val acodec: String?,
        val fileSize: Long?,
        val bitrateKbps: Double?,
    )

    enum class Kind { PROGRESSIVE, MERGE, AUDIO, IMAGE }

    /** One row in the UI's quality list. */
    data class Option(
        val id: String,
        val label: String,
        val ext: String,
        val sizeBytes: Long?,
        val kind: Kind,
        /** Passed to yt-dlp as `-f`. */
        val selector: String,
        /** Passed as `--merge-output-format` when this is a merge. */
        val mergeContainer: String?,
    )

    private val MP4_COMPATIBLE = setOf("mp4", "m4a", "m4v", "mov", "3gp", "aac")

    private val AUDIO_EXT_PREFERENCE = mapOf("m4a" to 3, "mp4" to 3, "webm" to 2, "opus" to 2, "mp3" to 1)
    private val VIDEO_EXT_PREFERENCE = mapOf("mp4" to 3, "mov" to 2, "webm" to 1)

    /** Pins, and any other extractor that serves stills. */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

    /**
     * Storyboard sheets — thumbnail contact strips, never the media itself.
     * This is the *only* thing dropped outright.
     */
    private val JUNK_EXTS = setOf("mhtml")

    /** More image sizes than this is noise rather than choice. */
    private const val MAX_IMAGE_OPTIONS = 3

    /**
     * Build the offered options, best first.
     *
     * One entry per distinct resolution, preferring a single progressive stream
     * over a merge, plus one audio-only entry and any still images.
     *
     * No resolution cap: it's your device and your storage, so every resolution
     * the platform reports is offered. Above 1080p that usually means VP9 or
     * AV1, which MP4 can't hold reliably, so the label will say MKV.
     */
    fun plan(formats: List<RawFormat>, durationSeconds: Double?): List<Option> {
        val usable = formats.filter { format ->
            format.formatId.isNotBlank() && (format.ext?.lowercase() ?: "") !in JUNK_EXTS
        }

        val progressive = LinkedHashMap<Int, RawFormat>()
        val videoOnly = LinkedHashMap<Int, RawFormat>()
        val audioOnly = mutableListOf<RawFormat>()
        val images = LinkedHashMap<Int, RawFormat>()

        for (format in usable) {
            val ext = format.ext?.lowercase() ?: ""
            val vcodec = codec(format.vcodec)
            val acodec = codec(format.acodec)
            val height = format.height?.takeIf { it > 0 } ?: 0

            val bucket = when {
                ext in IMAGE_EXTS -> images
                vcodec != null && acodec != null -> progressive
                vcodec != null -> videoOnly
                // Audio-only doesn't dedupe by height, so it's collected apart.
                acodec != null -> null
                // Neither codec reported, or both reported as "none", on a
                // non-image format. Older builds threw these away, which is
                // what silently broke Pinterest — it reports pin media with no
                // codec information at all. yt-dlp can still fetch them, so
                // assume a self-contained stream and let it decide.
                else -> progressive
            }

            if (bucket == null) {
                audioOnly += format
                continue
            }

            val incumbent = bucket[height]
            if (incumbent == null || videoRank(format) > videoRank(incumbent)) {
                bucket[height] = format
            }
        }

        val bestAudio = audioOnly.maxWithOrNull(
            compareBy<RawFormat> { audioRank(it) }.thenBy { it.bitrateKbps ?: 0.0 }
        )

        val options = mutableListOf<Option>()

        for (height in (progressive.keys + videoOnly.keys).sortedDescending()) {
            val prog = progressive[height]
            if (prog != null) {
                val ext = prog.ext?.lowercase() ?: "mp4"
                options += Option(
                    id = "p-${prog.formatId}",
                    label = labelFor(height, ext),
                    ext = ext,
                    sizeBytes = estimateSize(prog, durationSeconds),
                    kind = Kind.PROGRESSIVE,
                    selector = prog.formatId,
                    mergeContainer = null,
                )
                continue
            }

            val video = videoOnly[height] ?: continue
            if (bestAudio == null) continue

            val container = containerFor(video.ext, bestAudio.ext)
            val videoSize = estimateSize(video, durationSeconds)
            val audioSize = estimateSize(bestAudio, durationSeconds)
            options += Option(
                id = "m-${video.formatId}+${bestAudio.formatId}",
                label = labelFor(height, container),
                ext = container,
                sizeBytes = if (videoSize != null && audioSize != null) videoSize + audioSize else null,
                kind = Kind.MERGE,
                selector = "${video.formatId}+${bestAudio.formatId}",
                mergeContainer = container,
            )
        }

        if (bestAudio != null) {
            val ext = bestAudio.ext?.lowercase() ?: "m4a"
            options += Option(
                id = "a-${bestAudio.formatId}",
                label = "Audio only (${ext.uppercase()})",
                ext = ext,
                sizeBytes = estimateSize(bestAudio, durationSeconds),
                kind = Kind.AUDIO,
                selector = bestAudio.formatId,
                mergeContainer = null,
            )
        }

        images.keys.sortedDescending().take(MAX_IMAGE_OPTIONS).forEach { height ->
            val image = images.getValue(height)
            val ext = image.ext?.lowercase() ?: "jpg"
            options += Option(
                id = "i-${image.formatId}",
                label = if (height > 0) "Image ${height}px (${ext.uppercase()})"
                else "Image (${ext.uppercase()})",
                ext = ext,
                sizeBytes = image.fileSize?.takeIf { it > 0 },
                kind = Kind.IMAGE,
                selector = image.formatId,
                mergeContainer = null,
            )
        }

        return options
    }

    /** yt-dlp writes the literal string "none" for an absent codec. */
    private fun codec(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it != "none" }

    private fun videoRank(format: RawFormat): Int {
        val extScore = VIDEO_EXT_PREFERENCE[format.ext?.lowercase()] ?: 0
        return extScore * 1_000_000 + ((format.bitrateKbps ?: 0.0).toInt())
    }

    private fun audioRank(format: RawFormat): Int =
        AUDIO_EXT_PREFERENCE[format.ext?.lowercase()] ?: 0

    private fun estimateSize(format: RawFormat, durationSeconds: Double?): Long? {
        format.fileSize?.takeIf { it > 0 }?.let { return it }
        val bitrate = format.bitrateKbps ?: return null
        if (durationSeconds == null || durationSeconds <= 0 || bitrate <= 0) return null
        return (bitrate * 1000 / 8 * durationSeconds).toLong()
    }

    private fun labelFor(height: Int, ext: String): String =
        if (height > 0) "${height}p ${ext.uppercase()}"
        else "Original quality (${ext.uppercase()})"

    /**
     * yt-dlp accepts both as `--merge-output-format`, and in both cases the
     * container name is also the file extension.
     */
    private fun containerFor(vararg exts: String?): String =
        if (exts.all { (it?.lowercase() ?: "") in MP4_COMPATIBLE }) "mp4" else "mkv"
}

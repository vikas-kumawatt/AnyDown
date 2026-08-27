package com.anydown.downloader.domain

/**
 * Decides which qualities to offer, ported from the web version's
 * `backend/app/extractor.py`.
 *
 * The important difference from the server build: this does not stream or mux
 * anything itself. It produces a **yt-dlp format selector** (`"137+140"`) and
 * lets yt-dlp do the download and the merge on-device. All the streaming,
 * fragmented-MP4 and pipe plumbing the server needed is gone — it only existed
 * because bytes had to cross a network.
 *
 * Deliberately free of Android and youtubedl-android imports so it can be unit
 * tested on the JVM in CI. [RawFormat] is this module's own model; mapping the
 * library's `VideoFormat` onto it happens in one place, in `YtDlpSource`.
 */
object FormatPlanner {

    /**
     * One entry from yt-dlp's `formats` array, reduced to what we use.
     *
     * No `protocol` field, unlike the server version's equivalent: there it
     * decided between proxying bytes directly and routing through ffmpeg. On
     * device, yt-dlp handles HLS/DASH itself, so the distinction never surfaces.
     */
    data class RawFormat(
        val formatId: String,
        val ext: String?,
        val height: Int?,
        val vcodec: String?,
        val acodec: String?,
        val fileSize: Long?,
        val bitrateKbps: Double?,
    )

    enum class Kind { PROGRESSIVE, MERGE, AUDIO }

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

    // Containers that survive `-c copy` into an MP4. Anything else (VP9/Opus/AV1
    // in WebM) goes to Matroska instead, which accepts them.
    private val MP4_COMPATIBLE = setOf("mp4", "m4a", "m4v", "mov", "3gp", "aac")

    private val AUDIO_EXT_PREFERENCE = mapOf("m4a" to 3, "mp4" to 3, "webm" to 2, "opus" to 2, "mp3" to 1)
    private val VIDEO_EXT_PREFERENCE = mapOf("mp4" to 3, "mov" to 2, "webm" to 1)

    // Storyboard/thumbnail pseudo-formats yt-dlp reports that aren't media.
    private val JUNK_EXTS = setOf("mhtml", "none")

    /**
     * Build the offered options, best first.
     *
     * One entry per distinct resolution, preferring a single progressive stream
     * over a merge (no ffmpeg needed, and no chance of a container mismatch),
     * plus one audio-only entry.
     *
     * Unlike the server build there is no resolution cap: it's your own device
     * and your own storage, so 1440p/2160p are offered when the platform has
     * them. Those are usually VP9/AV1, so the label will say MKV.
     */
    fun plan(formats: List<RawFormat>, durationSeconds: Double?): List<Option> {
        val usable = formats.filter { format ->
            format.formatId.isNotBlank() &&
                (format.ext?.lowercase() ?: "") !in JUNK_EXTS &&
                !isNotMedia(format)
        }

        val progressive = LinkedHashMap<Int, RawFormat>()
        val videoOnly = LinkedHashMap<Int, RawFormat>()
        val audioOnly = mutableListOf<RawFormat>()

        for (format in usable) {
            val vcodec = codec(format.vcodec)
            val acodec = codec(format.acodec)
            val reportsCodecs = format.vcodec != null || format.acodec != null

            val bucket = when {
                vcodec != null && acodec != null -> progressive
                vcodec != null -> videoOnly
                acodec != null -> {
                    audioOnly += format
                    continue
                }
                // Extractor reported no codec info at all (common on TikTok and
                // Pinterest). Those serve self-contained files.
                !reportsCodecs -> progressive
                else -> continue
            }

            val height = format.height?.takeIf { it > 0 } ?: 0
            val incumbent = bucket[height]
            if (incumbent == null || videoRank(format) > videoRank(incumbent)) {
                bucket[height] = format
            }
        }

        val bestAudio = audioOnly.maxWithOrNull(
            compareBy<RawFormat> { audioRank(it) }.thenBy { it.bitrateKbps ?: 0.0 }
        )

        val options = mutableListOf<Option>()

        val heights = (progressive.keys + videoOnly.keys).sortedDescending()
        for (height in heights) {
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
            if (bestAudio == null) continue // video with no audio track to pair

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

        return options
    }

    /** yt-dlp writes the literal string "none" for an absent codec. */
    private fun codec(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it != "none" }

    /** Both codecs explicitly reported as absent: not a playable stream. */
    private fun isNotMedia(format: RawFormat): Boolean =
        format.vcodec != null && format.acodec != null &&
            codec(format.vcodec) == null && codec(format.acodec) == null

    private fun videoRank(format: RawFormat): Int {
        val extScore = VIDEO_EXT_PREFERENCE[format.ext?.lowercase()] ?: 0
        // Bitrate breaks ties within a container preference.
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

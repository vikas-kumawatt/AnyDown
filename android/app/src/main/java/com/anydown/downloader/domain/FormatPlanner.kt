package com.anydown.downloader.domain

/**
 * Decides which qualities to offer.
 *
 * Produces a **yt-dlp format selector** and lets yt-dlp do the download and any
 * merge on-device.
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

    enum class Kind { BEST, PROGRESSIVE, MERGE, AUDIO, IMAGE }

    /** One row in the UI's quality list. */
    data class Option(
        val id: String,
        val label: String,
        val ext: String,
        val sizeBytes: Long?,
        val kind: Kind,
        /** Passed to yt-dlp as `-f`. Ignored when [directUrl] is set. */
        val selector: String,
        /** Passed as `--merge-output-format` when a merge is involved. */
        val mergeContainer: String?,
        /**
         * Set when a custom resolver found this media itself, because yt-dlp has
         * no extractor for the site. yt-dlp still does the fetching — it's
         * pointed at this URL rather than the page.
         */
        val directUrl: String? = null,
        /** Headers the host requires, as `K: V` lines. */
        val headerBlob: String? = null,
    )

    /** Turns custom-resolver output into offerable options. */
    fun fromDirect(result: DirectResult): List<Option> =
        result.media.mapIndexed { index, media ->
            Option(
                id = "d-$index",
                label = media.label,
                ext = media.ext,
                sizeBytes = media.sizeBytes,
                kind = media.kind,
                selector = "b",
                mergeContainer = null,
                directUrl = media.url,
                headerBlob = HeaderBlob.encode(media.headers),
            )
        }

    private val MP4_COMPATIBLE = setOf("mp4", "m4a", "m4v", "mov", "3gp", "aac")

    private val AUDIO_EXT_PREFERENCE = mapOf("m4a" to 3, "mp4" to 3, "webm" to 2, "opus" to 2, "mp3" to 1)
    private val VIDEO_EXT_PREFERENCE = mapOf("mp4" to 3, "mov" to 2, "webm" to 1)

    /** Extensions that are audio even when the extractor reports no codecs. */
    private val AUDIO_EXTS = setOf("m4a", "mp3", "opus", "aac", "ogg", "oga", "wav", "flac")

    /** Pins, and any other extractor that serves stills. */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

    /** Storyboard contact sheets — never the media itself. The only hard drop. */
    private val JUNK_EXTS = setOf("mhtml")

    private const val MAX_IMAGE_OPTIONS = 3

    /**
     * Build the offered options, best first.
     *
     * [canMerge] is false when ffmpeg didn't load; merge-dependent options are
     * then omitted rather than offered and failing.
     */
    fun plan(
        formats: List<RawFormat>,
        durationSeconds: Double?,
        canMerge: Boolean = true,
    ): List<Option> {
        val usable = formats.filter { format ->
            format.formatId.isNotBlank() && (format.ext?.lowercase() ?: "") !in JUNK_EXTS
        }

        // Keyed by resolution where known, and by format id where it isn't.
        //
        // Keying everything by height was the Pinterest bug: its HLS renditions
        // all report no height, so every one of them landed in the same bucket,
        // only one survived, and the winner could be an audio-only rendition —
        // which is exactly what "Original quality (MP4)" was downloading.
        val progressive = LinkedHashMap<String, RawFormat>()
        val videoOnly = LinkedHashMap<String, RawFormat>()
        val audioOnly = mutableListOf<RawFormat>()
        val images = LinkedHashMap<String, RawFormat>()

        for (format in usable) {
            val ext = format.ext?.lowercase() ?: ""
            val vcodec = codec(format.vcodec)
            val acodec = codec(format.acodec)

            val bucket = when {
                ext in IMAGE_EXTS -> images
                vcodec != null && acodec != null -> progressive
                vcodec != null -> videoOnly
                acodec != null -> null
                // No codec information at all. Trust the extension: an .m4a with
                // unknown codecs is audio, not a video we should headline.
                ext in AUDIO_EXTS -> null
                else -> progressive
            }

            if (bucket == null) {
                audioOnly += format
                continue
            }
            val key = bucketKey(format)
            val incumbent = bucket[key]
            if (incumbent == null || videoRank(format) > videoRank(incumbent)) {
                bucket[key] = format
            }
        }

        val bestAudio = audioOnly.maxWithOrNull(
            compareBy<RawFormat> { audioRank(it) }.thenBy { it.bitrateKbps ?: 0.0 }
        )

        val options = mutableListOf<Option>()
        val hasVideo = progressive.isNotEmpty() || videoOnly.isNotEmpty()

        // A selector yt-dlp resolves for itself, offered first.
        //
        // On extractors that report partial metadata, hand-picking a format id
        // is guesswork; `bv*+ba/b` asks yt-dlp for the best video plus the best
        // audio and falls back to the best single stream. It is the option most
        // likely to simply work, on any site.
        if (hasVideo || bestAudio != null) {
            options += Option(
                id = "best",
                label = "Best available",
                ext = "mp4",
                sizeBytes = null,
                kind = Kind.BEST,
                selector = if (canMerge) "bv*+ba/b" else "b",
                mergeContainer = if (canMerge) "mp4" else null,
            )
        }

        // Known resolutions descending, then bitrate descending.
        //
        // The bitrate tie-break matters: when an extractor reports no heights at
        // all (Pinterest), every key ties on height, and falling back to
        // insertion order would put the *worst* rendition first — yt-dlp lists
        // formats worst-first.
        val ordered = (progressive.keys + videoOnly.keys).sortedWith(
            compareByDescending<String> { key ->
                (progressive[key] ?: videoOnly.getValue(key)).height ?: 0
            }.thenByDescending { key ->
                (progressive[key] ?: videoOnly.getValue(key)).bitrateKbps ?: 0.0
            }
        )

        for (key in ordered) {
            val prog = progressive[key]
            if (prog != null) {
                val ext = prog.ext?.lowercase() ?: "mp4"
                options += Option(
                    id = "p-${prog.formatId}",
                    label = labelFor(prog.height, ext),
                    ext = ext,
                    sizeBytes = estimateSize(prog, durationSeconds),
                    kind = Kind.PROGRESSIVE,
                    selector = prog.formatId,
                    mergeContainer = null,
                )
                continue
            }

            if (!canMerge) continue
            val video = videoOnly[key] ?: continue
            if (bestAudio == null) continue

            val container = containerFor(video.ext, bestAudio.ext)
            val videoSize = estimateSize(video, durationSeconds)
            val audioSize = estimateSize(bestAudio, durationSeconds)
            options += Option(
                id = "m-${video.formatId}+${bestAudio.formatId}",
                label = labelFor(video.height, container),
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

        images.values
            .sortedByDescending { it.height ?: 0 }
            .take(MAX_IMAGE_OPTIONS)
            .forEach { image ->
                val ext = image.ext?.lowercase() ?: "jpg"
                val height = image.height ?: 0
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

    /** True when any option at all would exist given a working ffmpeg. */
    fun couldPlanWithMerging(formats: List<RawFormat>, durationSeconds: Double?): Boolean =
        plan(formats, durationSeconds, canMerge = true).isNotEmpty()

    private fun bucketKey(format: RawFormat): String {
        val height = format.height?.takeIf { it > 0 }
        return if (height != null) "h$height" else "f${format.formatId}"
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

    private fun labelFor(height: Int?, ext: String): String {
        val h = height?.takeIf { it > 0 }
        return if (h != null) "${h}p ${ext.uppercase()}"
        else "Original quality (${ext.uppercase()})"
    }

    /**
     * yt-dlp accepts both as `--merge-output-format`, and in both cases the
     * container name is also the file extension.
     */
    private fun containerFor(vararg exts: String?): String =
        if (exts.all { (it?.lowercase() ?: "") in MP4_COMPATIBLE }) "mp4" else "mkv"
}

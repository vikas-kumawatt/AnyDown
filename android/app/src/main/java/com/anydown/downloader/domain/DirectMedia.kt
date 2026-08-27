package com.anydown.downloader.domain

/**
 * What a custom resolver produces: media we located ourselves, as direct URLs.
 *
 * These exist because yt-dlp has no extractor for the site. Threads has been
 * an open request upstream since 2024, and TeraBox isn't a media page at all —
 * it's a file-share API.
 *
 * The URLs still go through yt-dlp to be *fetched*, so downloads keep the same
 * progress reporting, notifications, and output handling as everything else.
 * Only the finding is custom.
 */
data class DirectMedia(
    val url: String,
    val label: String,
    val ext: String,
    val height: Int?,
    val sizeBytes: Long?,
    val kind: FormatPlanner.Kind,
    /** Headers the host requires — Referer for TeraBox, a browser UA for both. */
    val headers: Map<String, String> = emptyMap(),
)

data class DirectResult(
    val title: String,
    val thumbnail: String? = null,
    val durationSeconds: Int? = null,
    val uploader: String? = null,
    val media: List<DirectMedia>,
)

/** Serialises headers for transport through a Service Intent. */
object HeaderBlob {

    fun encode(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    fun decode(blob: String?): Map<String, String> {
        if (blob.isNullOrBlank()) return emptyMap()
        return blob.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf(':')
                if (at <= 0) null
                else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toMap()
    }
}

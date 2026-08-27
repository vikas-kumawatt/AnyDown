package com.anydown.downloader.domain

/**
 * Finds the media in a Threads post's HTML.
 *
 * yt-dlp has no Threads extractor — [an open request since 2024](
 * https://github.com/yt-dlp/yt-dlp/issues/10133) — so the post page is read
 * directly. Threads is Instagram's sibling and ships the same embedded JSON, so
 * the media is in `video_versions` and `image_versions2.candidates` blobs inside
 * the served HTML.
 *
 * Written defensively on purpose. The markup couldn't be inspected while
 * building this, so it tries the structured path first and falls back to
 * scanning for any CDN media URL in the document. Meta reshapes these payloads
 * regularly; the fallback is what keeps it working through a rename.
 */
object ThreadsParser {

    private val VIDEO_URL = Regex(
        """https?://[^"'\\\s]+?\.mp4[^"'\\\s]*""",
        RegexOption.IGNORE_CASE,
    )
    private val META = Regex(
        """<meta[^>]+(?:property|name)=["']([^"']+)["'][^>]+content=["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_REVERSED = Regex(
        """<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    /** Meta's media CDN hosts. Used to tell post media from site furniture. */
    private val CDN_HINTS = listOf("cdninstagram", "fbcdn", "scontent")

    fun parse(html: String, fallbackTitle: String = "Threads post"): DirectResult? {
        val text = JsonLite.unescape(html)
        val meta = metaTags(html)

        val videos = collectVideos(text)
        val images = if (videos.isEmpty()) collectImages(text, meta) else emptyList()
        if (videos.isEmpty() && images.isEmpty()) return null

        val title = (meta["og:title"] ?: meta["twitter:title"])
            ?.let(::cleanTitle)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle

        return DirectResult(
            title = title,
            thumbnail = meta["og:image"],
            uploader = meta["og:title"]?.let(::uploaderFrom),
            media = videos + images,
        )
    }

    /**
     * Structured pass over `video_versions`, then a plain sweep for .mp4 URLs.
     *
     * Threads serves progressive MP4s at a few sizes; the structured pass gets
     * their heights, which the sweep can't.
     */
    private fun collectVideos(text: String): List<DirectMedia> {
        val found = LinkedHashMap<String, DirectMedia>()

        for (entry in JsonLite.allObjectsIn(text, "video_versions")) {
            val url = JsonLite.string(entry, "url") ?: continue
            if (!url.contains(".mp4", ignoreCase = true)) continue
            val height = JsonLite.int(entry, "height")?.takeIf { it > 0 }
            found.putIfAbsent(stripQuery(url), media(url, height, FormatPlanner.Kind.PROGRESSIVE))
        }

        if (found.isEmpty()) {
            VIDEO_URL.findAll(text)
                .map { it.value.trimEnd('\\', ',', ')') }
                .filter { candidate -> CDN_HINTS.any { it in candidate } }
                .forEach { url ->
                    found.putIfAbsent(stripQuery(url), media(url, null, FormatPlanner.Kind.PROGRESSIVE))
                }
        }

        // Highest resolution first; unknown heights last.
        return found.values.sortedByDescending { it.height ?: 0 }
    }

    private fun collectImages(text: String, meta: Map<String, String>): List<DirectMedia> {
        val found = LinkedHashMap<String, DirectMedia>()

        for (entry in JsonLite.allObjectsIn(text, "candidates")) {
            val url = JsonLite.string(entry, "url") ?: continue
            if (CDN_HINTS.none { it in url }) continue
            val height = JsonLite.int(entry, "height")?.takeIf { it > 0 }
            found.putIfAbsent(stripQuery(url), media(url, height, FormatPlanner.Kind.IMAGE))
        }

        if (found.isEmpty()) {
            // Only accept og:image when it's on Meta's media CDN. Matching any
            // image URL would defeat the point — a site logo or an OpenGraph
            // placeholder is not the post's media.
            val fromMeta = meta["og:image"]?.takeIf { url ->
                CDN_HINTS.any { it in url }
            }
            if (fromMeta != null) {
                found.putIfAbsent(stripQuery(fromMeta), media(fromMeta, null, FormatPlanner.Kind.IMAGE))
            }
        }

        // A post's image ladder is the same picture at several sizes; the
        // largest three are plenty.
        return found.values.sortedByDescending { it.height ?: 0 }.take(3)
    }

    private fun media(url: String, height: Int?, kind: FormatPlanner.Kind): DirectMedia {
        val ext = extensionOf(url, if (kind == FormatPlanner.Kind.IMAGE) "jpg" else "mp4")
        val label = when {
            kind == FormatPlanner.Kind.IMAGE && height != null -> "Image ${height}px (${ext.uppercase()})"
            kind == FormatPlanner.Kind.IMAGE -> "Image (${ext.uppercase()})"
            height != null -> "${height}p ${ext.uppercase()}"
            else -> "Original quality (${ext.uppercase()})"
        }
        return DirectMedia(
            url = url,
            label = label,
            ext = ext,
            height = height,
            sizeBytes = null,
            kind = kind,
            headers = REQUIRED_HEADERS,
        )
    }

    /** Meta's CDN rejects requests without a browser UA and a Threads referer. */
    val REQUIRED_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer" to "https://www.threads.net/",
    )

    private fun metaTags(html: String): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        META.findAll(html).forEach { m ->
            tags.putIfAbsent(m.groupValues[1].lowercase(), decodeEntities(m.groupValues[2]))
        }
        META_REVERSED.findAll(html).forEach { m ->
            tags.putIfAbsent(m.groupValues[2].lowercase(), decodeEntities(m.groupValues[1]))
        }
        return tags
    }

    private fun decodeEntities(text: String): String = text
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    /**
     * og:title reads `Name (@handle) on Threads`. Keep only the name — the
     * handle is reported separately as the uploader, so repeating it in the
     * title just makes the filename worse.
     */
    private fun cleanTitle(raw: String): String {
        val withoutSuffix = raw.substringBefore(" on Threads")
        val withoutHandle = withoutSuffix.replace(Regex("""\s*\(@[A-Za-z0-9._]+\)"""), "")
        return withoutHandle.trim().ifEmpty { withoutSuffix.trim().ifEmpty { raw.trim() } }
    }

    private fun uploaderFrom(ogTitle: String): String? =
        Regex("""\(@([A-Za-z0-9._]+)\)""").find(ogTitle)?.groupValues?.get(1)

    private fun stripQuery(url: String): String = url.substringBefore('?')

    private fun extensionOf(url: String, fallback: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext.lowercase()
        else fallback
    }
}

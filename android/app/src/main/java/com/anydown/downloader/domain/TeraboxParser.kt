package com.anydown.downloader.domain

/**
 * Reads TeraBox share links.
 *
 * TeraBox isn't a media site — it's file storage, and yt-dlp has no extractor
 * for it ([open request](https://github.com/yt-dlp/yt-dlp/issues/5484)). A share
 * link resolves in two steps:
 *
 *  1. Load the share page. It embeds a `jsToken`, an anti-CSRF value.
 *  2. Call `/api/shorturlinfo?shorturl=…&jsToken=…&root=1`, which returns the
 *     file list with `server_filename`, `size`, and a `dlink` direct URL.
 *
 * **This is undocumented and will break.** It's a private API with no
 * compatibility promise, it rate-limits, and the token's location moves. The
 * parsing here is pure and tested; whether TeraBox still answers this way on any
 * given day isn't something the app can guarantee.
 */
object TeraboxParser {

    /** Every domain TeraBox serves shares from. They rotate these often. */
    private val HOSTS = listOf(
        "terabox.com", "terabox.app", "1024terabox.com", "teraboxapp.com",
        "teraboxlink.com", "terasharelink.com", "teraboxshare.com",
        "4funbox.com", "mirrobox.com", "nephobox.com", "momerybox.com",
        "tibibox.com", "freeterabox.com", "www.freeterabox.com", "terafileshare.com",
    )

    fun handles(url: String): Boolean {
        val host = hostOf(url)?.removePrefix("www.") ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") || host == "www.$it" }
    }

    /**
     * The share id. `terabox.com/s/1AbCdEf` and `?surl=AbCdEf` both appear.
     *
     * The `/s/` form carries a leading `1` that the API doesn't want, so it's
     * stripped — the returned value is what goes in `shorturl`.
     */
    fun extractSurl(url: String): String? {
        Regex("""[?&]surl=([A-Za-z0-9_-]+)""").find(url)?.let {
            return it.groupValues[1]
        }
        Regex("""/s/([A-Za-z0-9_-]+)""").find(url)?.let {
            val raw = it.groupValues[1]
            return if (raw.startsWith("1") && raw.length > 1) raw.substring(1) else raw
        }
        return null
    }

    /**
     * The anti-CSRF token from the share page.
     *
     * It shows up plainly as `jsToken":"…"`, percent-encoded inside a script, or
     * wrapped in a `fn("…")` call, depending on which variant of the page is
     * served. All three are tried.
     */
    fun extractJsToken(html: String): String? {
        Regex("""jsToken["']?\s*[:=]\s*["']([0-9A-Fa-f]{20,})["']""").find(html)?.let {
            return it.groupValues[1]
        }
        Regex("""jsToken%22%3A%22([0-9A-Fa-f]{20,})%22""").find(html)?.let {
            return it.groupValues[1]
        }
        Regex("""fn%28%22([0-9A-Fa-f]{20,})%22%29""").find(html)?.let {
            return it.groupValues[1]
        }
        Regex("""fn\("([0-9A-Fa-f]{20,})"\)""").find(html)?.let {
            return it.groupValues[1]
        }
        return null
    }

    /**
     * Turn a `shorturlinfo` response into offerable media.
     *
     * Returns null when the response carries no usable `dlink` — typically
     * `errno` is non-zero because the token expired or the share is private.
     */
    fun parseShareInfo(json: String, fallbackTitle: String = "TeraBox file"): DirectResult? {
        val entries = JsonLite.objectsIn(json, "list")
            .ifEmpty { JsonLite.allObjectsIn(json, "list") }
        if (entries.isEmpty()) return null

        val media = mutableListOf<DirectMedia>()
        var title: String? = null
        var thumbnail: String? = null

        for (entry in entries) {
            // Directories have isdir 1 and no dlink; skip them rather than
            // offering a download that can't work.
            if (JsonLite.int(entry, "isdir") == 1) continue
            val dlink = JsonLite.string(entry, "dlink")?.takeIf { it.startsWith("http") }
                ?: continue

            val name = JsonLite.string(entry, "server_filename")
                ?: JsonLite.string(entry, "filename")
            val size = JsonLite.long(entry, "size")?.takeIf { it > 0 }
            if (title == null) title = name
            if (thumbnail == null) thumbnail = thumbnailFrom(entry)

            val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf {
                it.length in 2..5 && it.all(Char::isLetterOrDigit)
            } ?: "mp4"

            media += DirectMedia(
                url = dlink,
                label = name ?: "Original file (${ext.uppercase()})",
                ext = ext,
                height = null,
                sizeBytes = size,
                kind = if (ext in IMAGE_EXTS) FormatPlanner.Kind.IMAGE
                else FormatPlanner.Kind.PROGRESSIVE,
                headers = REQUIRED_HEADERS,
            )
        }

        if (media.isEmpty()) return null
        return DirectResult(
            title = title ?: fallbackTitle,
            thumbnail = thumbnail,
            media = media,
        )
    }

    /** The dlink refuses requests without a browser UA and a TeraBox referer. */
    val REQUIRED_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer" to "https://www.terabox.com/",
    )

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")

    /** `thumbs` holds url1/url2/url3 at increasing sizes; take the largest. */
    private fun thumbnailFrom(entry: String): String? {
        val thumbs = entry.substringAfter("\"thumbs\"", "").takeIf { it.isNotEmpty() }
            ?: return null
        return listOf("url3", "url2", "url1")
            .firstNotNullOfOrNull { JsonLite.string(thumbs, it) }
    }

    private fun hostOf(url: String): String? {
        val match = Regex("^(https?)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        val host = match.groupValues[2].substringAfterLast('@').substringBefore(':')
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }

    /** The endpoints, kept here so the resolver has no URL literals of its own. */
    fun sharePageUrl(surl: String): String =
        "https://www.terabox.com/wap/share/filelist?surl=$surl"

    fun shareInfoUrl(surl: String, jsToken: String): String =
        "https://www.terabox.com/api/shorturlinfo" +
            "?app_id=250528&web=1&channel=dubox&clienttype=0" +
            "&jsToken=$jsToken&shorturl=$surl&root=1"
}

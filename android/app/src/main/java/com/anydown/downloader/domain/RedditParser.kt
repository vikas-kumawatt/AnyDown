package com.anydown.downloader.domain

/**
 * Finds the video in a public Reddit post.
 *
 * yt-dlp does have a Reddit extractor, but it reads the post through Reddit's
 * `.json` API, and Reddit answers clients whose TLS doesn't look like a browser
 * with a block page instead. yt-dlp's way past that is impersonation, which the
 * bundled Python can't do, so a public post failed as "Account authentication
 * is required". Android's own network stack isn't blocked, so the post is read
 * here and yt-dlp is only handed the video's stream manifest.
 *
 * Two sources, best first: the post's JSON, which names the manifests outright,
 * and the post page's HTML, which embeds them in its video player.
 */
object RedditParser {

    private val HOSTS = listOf("reddit.com", "redd.it")
    private val POST_ID = Regex("""/comments/([A-Za-z0-9]+)""")
    private val HLS = Regex("""https://v\.redd\.it/[A-Za-z0-9]+/HLSPlaylist\.m3u8[^"'\s<>\\]*""")
    private val DASH = Regex("""https://v\.redd\.it/[A-Za-z0-9]+/DASHPlaylist\.mpd[^"'\s<>\\]*""")
    private val VIDEO_ID = Regex("""https://v\.redd\.it/([A-Za-z0-9]+)""")
    private val DASH_HEIGHT = Regex("""DASH_(\d{3,4})""")
    private val IMAGE = Regex("""^https://i\.redd\.it/[^"'\s<>]+\.(?:jpe?g|png|gif|webp)$""", RegexOption.IGNORE_CASE)
    private val META = Regex(
        """<meta[^>]+property=["']([^"']+)["'][^>]+content=["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    /** Reddit's video CDN serves without these, but they cost nothing and match a browser. */
    val REQUIRED_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        "Referer" to "https://www.reddit.com/",
    )

    fun handles(url: String): Boolean {
        val host = Regex("^https?://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?.groupValues?.get(1)?.substringAfterLast('@')?.substringBefore(':')
            ?.lowercase()?.trimEnd('.')
            ?: return false
        // v.redd.it is the media host itself, not a post.
        if (host == "v.redd.it" || host == "i.redd.it") return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** The post id in `/comments/<id>/`, or null for a share link that needs following. */
    fun postId(url: String): String? = POST_ID.find(url)?.groupValues?.get(1)

    /** `raw_json=1` keeps URLs as written rather than HTML-escaped (`&amp;`). */
    fun jsonUrl(postId: String, host: String = "www.reddit.com"): String =
        "https://$host/comments/$postId/.json?raw_json=1"

    /** The post page, which redirects to its full `/r/<sub>/comments/<id>/<slug>/` form. */
    fun pageUrl(postId: String, host: String): String = "https://$host/comments/$postId/"

    /** Reads the post listing from [jsonUrl]. Null when it isn't JSON or has no media. */
    fun parseJson(body: String): DirectResult? {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return null

        val title = JsonLite.string(body, "title")?.takeIf { it.isNotBlank() } ?: "Reddit post"
        val uploader = JsonLite.string(body, "author")
        val thumbnail = JsonLite.string(body, "thumbnail")?.takeIf { it.startsWith("http") }

        val manifest = JsonLite.string(body, "hls_url") ?: JsonLite.string(body, "dash_url")
        if (manifest != null) {
            val height = JsonLite.string(body, "fallback_url")?.let(::heightIn)
            return DirectResult(
                title = title,
                thumbnail = thumbnail,
                durationSeconds = JsonLite.int(body, "duration")?.takeIf { it > 0 },
                uploader = uploader,
                media = listOf(video(manifest, height)),
            )
        }

        val image = JsonLite.string(body, "url_overridden_by_dest")?.takeIf { IMAGE.matches(it) }
            ?: return null
        return DirectResult(
            title = title,
            thumbnail = thumbnail ?: image,
            uploader = uploader,
            media = listOf(image(image)),
        )
    }

    /**
     * Fallback when the JSON is blocked too: the player embeds the same
     * manifests — as `src` on new Reddit, `data-hls-url` on old Reddit.
     */
    fun parseHtml(html: String): DirectResult? {
        val text = decodeEntities(JsonLite.unescape(html))
        val manifest = HLS.find(text)?.value
            ?: DASH.find(text)?.value
            ?: VIDEO_ID.find(text)?.let { "https://v.redd.it/${it.groupValues[1]}/DASHPlaylist.mpd" }
            ?: return null

        val meta = LinkedHashMap<String, String>()
        META.findAll(html).forEach { m ->
            meta.putIfAbsent(m.groupValues[1].lowercase(), decodeEntities(m.groupValues[2]))
        }
        return DirectResult(
            title = meta["og:title"]?.takeIf { it.isNotBlank() } ?: "Reddit post",
            thumbnail = meta["og:image"],
            media = listOf(video(manifest, null)),
        )
    }

    /**
     * The whole post as one option: yt-dlp reads the manifest, picks the best
     * video and audio renditions and merges them, just as it would for the page.
     */
    private fun video(manifest: String, height: Int?) = DirectMedia(
        url = manifest,
        label = if (height != null) "${height}p MP4" else "Best quality (MP4)",
        ext = "mp4",
        height = height,
        sizeBytes = null,
        kind = FormatPlanner.Kind.PROGRESSIVE,
        headers = REQUIRED_HEADERS,
    )

    private fun image(url: String): DirectMedia {
        val ext = url.substringAfterLast('.').lowercase()
        return DirectMedia(
            url = url,
            label = "Image (${ext.uppercase()})",
            ext = ext,
            height = null,
            sizeBytes = null,
            kind = FormatPlanner.Kind.IMAGE,
            headers = REQUIRED_HEADERS,
        )
    }

    /** `fallback_url` is `…/DASH_720.mp4`: the highest rendition's height. */
    private fun heightIn(url: String): Int? = DASH_HEIGHT.find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun decodeEntities(text: String): String = text
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}

package com.anydown.downloader.domain

/**
 * Cleans up a pasted link before yt-dlp sees it.
 *
 * Two real failures drove this:
 *
 *  * **Threads** moved to `threads.com`, but yt-dlp's extractor pattern still
 *    matches `threads.net`, so a perfectly valid post came back as
 *    "Unsupported URL". Rewriting the host makes it match.
 *  * Share sheets bolt on tracking parameters — `?xmt=AQGO1SlEq5mH…` on Threads,
 *    `?igsh=` on Instagram, `?si=` on YouTube. Most extractors tolerate them,
 *    some don't, and none of them need them.
 *
 * Pure and unit tested. Redirect following for short links needs the network,
 * so it lives in `YtDlpSource` instead.
 */
object UrlNormalizer {

    /** Hosts yt-dlp knows under a different name than the site now uses. */
    private val HOST_REWRITES = mapOf(
        "threads.com" to "threads.net",
        "www.threads.com" to "www.threads.net",
    )

    /**
     * Query parameters that only ever carry analytics.
     *
     * Deliberately a deny-list, not an allow-list: `v` on YouTube, `list`,
     * `t`, and countless per-site parameters are load-bearing, and stripping
     * an unknown parameter is far more likely to break a link than to fix one.
     */
    private val TRACKING_PARAMS = setOf(
        "igsh", "igshid", "xmt", "fbclid", "gclid", "si", "share_id", "share_app_id",
        "_nc_ht", "_r", "_d", "ref_src", "ref_url", "spm", "mibextid", "rdid",
        "slof", "app", "is_from_webapp", "sender_device", "sender_web_id",
        "web_id", "share_link_id", "social_share", "trk", "trkemail",
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    )

    /** Short/share hosts worth resolving before extraction. */
    private val SHORTENERS = setOf(
        "pin.it", "vm.tiktok.com", "vt.tiktok.com", "redd.it", "fb.watch",
        "lnkd.in", "dai.ly", "youtu.be", "t.co", "snd.sc", "ig.me", "instagr.am",
    )

    /** True when the URL is a redirect stub whose target we should resolve. */
    fun isShortLink(url: String): Boolean {
        val host = hostOf(url)?.removePrefix("www.") ?: return false
        if (host in SHORTENERS) return true
        // Threads share links redirect to the real post. Exact match or a real
        // subdomain — a bare endsWith would accept "notthreads.net".
        return listOf("threads.net", "threads.com").any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    /**
     * `vimeo.com/<id>` and `vimeo.com/<id>/<hash>` for unlisted videos.
     *
     * vimeo.com itself now refuses anonymous extraction ("The web client only
     * works when logged-in"), but player.vimeo.com serves the embed
     * configuration and is built to answer anonymous requests, so it still
     * works without credentials. The unlisted-link hash moves to `?h=`, which is
     * what the player endpoint expects.
     */
    private val VIMEO_PATH = Regex("^/(\\d{6,})(?:/([0-9a-zA-Z]+))?/?$")

    private fun rewriteVimeo(host: String, path: String): Pair<String, String>? {
        val bare = host.removePrefix("www.")
        if (bare != "vimeo.com") return null
        val match = VIMEO_PATH.find(path) ?: return null
        val id = match.groupValues[1]
        val hash = match.groupValues[2]
        val query = if (hash.isNotEmpty()) "h=$hash" else ""
        return "player.vimeo.com" to "/video/$id" + (if (query.isNotEmpty()) "?$query" else "")
    }

    /** Rewrite known-renamed hosts and drop tracking parameters. */
    fun normalize(rawUrl: String): String {
        val url = rawUrl.trim()
        val match = SPLIT.find(url) ?: return url

        val (scheme, authority, path, query, _) = destructure(match)

        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()

        // Vimeo replaces the host and the path together, and brings its own
        // query, so it short-circuits the generic handling below.
        rewriteVimeo(host, path)?.let { (newHost, newPathAndQuery) ->
            return "$scheme://$newHost$newPathAndQuery"
        }

        val rewrittenHost = HOST_REWRITES[host] ?: host
        val port = authority.substringAfterLast('@').substringAfter(':', "")
        val rebuiltAuthority = if (port.isEmpty()) rewrittenHost else "$rewrittenHost:$port"

        val keptQuery = query
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot { param -> param.substringBefore('=').lowercase() in TRACKING_PARAMS }
            .joinToString("&")

        // The fragment is dropped: no extractor reads it, and share sheets
        // sometimes append junk there too.
        return buildString {
            append(scheme).append("://").append(rebuiltAuthority).append(path)
            if (keptQuery.isNotEmpty()) append('?').append(keptQuery)
        }
    }

    private val SPLIT =
        Regex("^(https?)://([^/?#\\s]+)([^?#\\s]*)(?:\\?([^#\\s]*))?(?:#(\\S*))?$", RegexOption.IGNORE_CASE)

    // A data class already provides componentN(), which is what makes the
    // destructuring above work — declaring them by hand would collide.
    private data class Parts(
        val scheme: String,
        val authority: String,
        val path: String,
        val query: String,
        val fragment: String,
    )

    private fun destructure(match: MatchResult): Parts = Parts(
        scheme = match.groupValues[1].lowercase(),
        authority = match.groupValues[2],
        path = match.groupValues[3],
        query = match.groupValues[4],
        fragment = match.groupValues[5],
    )

    private fun hostOf(url: String): String? {
        val match = Regex("^(https?)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        val host = match.groupValues[2].substringAfterLast('@').substringBefore(':')
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }
}

package com.anydown.downloader.domain

/**
 * Platform allow-list, ported from the web version's `backend/app/platforms.py`.
 *
 * On-device the original security rationale is gone — there's no server to
 * point at its own metadata service, so this is no longer an SSRF control. It
 * survives purely as product scope: the PRD limits this tool to nine platforms,
 * and rejecting anything else up front gives a clear error instead of a
 * confusing yt-dlp failure thirty seconds later.
 *
 * yt-dlp itself supports well over a thousand sites. To allow all of them, set
 * [RESTRICT_TO_ALLOW_LIST] to false — [detectPlatform] still drives the
 * "Detected: YouTube" hint, it just stops gating.
 */
object Platforms {

    const val RESTRICT_TO_ALLOW_LIST = true

    data class Platform(val id: String, val label: String, val domains: List<String>)

    val ALL: List<Platform> = listOf(
        Platform("youtube", "YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com")),
        Platform("tiktok", "TikTok", listOf("tiktok.com")),
        Platform("twitter", "X / Twitter", listOf("twitter.com", "x.com", "t.co")),
        Platform("dailymotion", "Dailymotion", listOf("dailymotion.com", "dai.ly")),
        Platform("instagram", "Instagram", listOf("instagram.com", "instagr.am", "ig.me")),
        Platform("facebook", "Facebook", listOf("facebook.com", "fb.watch", "fb.com")),
        // pinterest.com is listed explicitly so regional subdomains like
        // in.pinterest.com match; the prefix rule below covers country TLDs.
        Platform("pinterest", "Pinterest", listOf("pinterest.com", "pin.it")),
        Platform("threads", "Threads", listOf("threads.net", "threads.com")),
        Platform("snapchat", "Snapchat", listOf("snapchat.com")),
    )

    private const val PINTEREST_PREFIX = "pinterest."

    private const val MAX_URL_LENGTH = 2048

    /**
     * Returns the platform for [rawUrl], or null if it isn't recognised.
     *
     * Hosts are matched against registered domains either exactly or with a
     * leading dot, so a look-alike like `youtube.com.evil.com` never matches.
     */
    fun detectPlatform(rawUrl: String): Platform? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null

        val host = hostOf(trimmed) ?: return null
        val bare = host.removePrefix("www.")

        ALL.firstOrNull { platform ->
            platform.domains.any { domain -> bare == domain || bare.endsWith(".$domain") }
        }?.let { return it }

        // Pinterest also serves country TLDs: pinterest.co.uk, pinterest.de, ...
        if (bare.startsWith(PINTEREST_PREFIX) && bare.count { it == '.' } <= 2) {
            return ALL.first { it.id == "pinterest" }
        }
        return null
    }

    /** Extracts a lowercase hostname, or null if this isn't an http(s) URL. */
    private fun hostOf(url: String): String? {
        val match = Regex("^(https?)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        val authority = match.groupValues[2]
        // Strip credentials and port.
        val host = authority.substringAfterLast('@').substringBefore(':')
        if (host.isEmpty()) return null
        return host.lowercase().trimEnd('.')
    }

    /** Null when the URL is acceptable, otherwise a user-facing reason. */
    fun rejectionReason(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return "Paste a link first."
        if (trimmed.length > MAX_URL_LENGTH) return "That URL is too long."
        if (hostOf(trimmed) == null) return "That doesn't look like a web link."
        if (RESTRICT_TO_ALLOW_LIST && detectPlatform(trimmed) == null) {
            return "Only these platforms are supported: " +
                ALL.joinToString(", ") { it.label } + "."
        }
        return null
    }
}

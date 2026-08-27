package com.anydown.downloader.domain

/**
 * Recognises the site a link belongs to, for labelling.
 *
 * This used to be an allow-list that *rejected* anything unlisted — a rule
 * inherited from the web version, where it doubled as an SSRF control (a server
 * must not be talked into fetching arbitrary addresses). On-device there is no
 * server and no SSRF surface, so the gate was doing nothing but blocking
 * perfectly good links: Reddit, Vimeo, VK and LinkedIn never reached yt-dlp at
 * all.
 *
 * Now anything that parses as an http(s) URL is handed to yt-dlp, which supports
 * well over a thousand sites. [ALL] only drives the "Detected: …" hint.
 */
object Platforms {

    data class Platform(val id: String, val label: String, val domains: List<String>)

    /**
     * Sites we can name on sight. Not exhaustive and not a restriction — an
     * unlisted host still works, it just shows no label.
     */
    val ALL: List<Platform> = listOf(
        Platform("youtube", "YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com")),
        Platform("tiktok", "TikTok", listOf("tiktok.com")),
        Platform("twitter", "X", listOf("twitter.com", "x.com", "t.co")),
        Platform("instagram", "Instagram", listOf("instagram.com", "instagr.am", "ig.me")),
        Platform("facebook", "Facebook", listOf("facebook.com", "fb.watch", "fb.com")),
        Platform("snapchat", "Snapchat", listOf("snapchat.com")),
        Platform("threads", "Threads", listOf("threads.net", "threads.com")),
        Platform("reddit", "Reddit", listOf("reddit.com", "redd.it", "redditmedia.com")),
        Platform("vimeo", "Vimeo", listOf("vimeo.com")),
        Platform("dailymotion", "Dailymotion", listOf("dailymotion.com", "dai.ly")),
        Platform("vk", "VK", listOf("vk.com", "vkvideo.ru", "vk.ru")),
        Platform("linkedin", "LinkedIn", listOf("linkedin.com", "lnkd.in")),
        Platform("pinterest", "Pinterest", listOf("pinterest.com", "pin.it")),
        Platform("twitch", "Twitch", listOf("twitch.tv")),
        Platform("tumblr", "Tumblr", listOf("tumblr.com")),
        Platform("soundcloud", "SoundCloud", listOf("soundcloud.com", "snd.sc")),
    )

    private const val PINTEREST_PREFIX = "pinterest."
    private const val MAX_URL_LENGTH = 2048

    /**
     * Names the site behind [rawUrl], or null if we don't recognise it.
     *
     * Hosts match a registered domain either exactly or with a leading dot, so a
     * look-alike such as `youtube.com.evil.com` is never mistaken for YouTube.
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

    /**
     * Null when the link is worth trying, otherwise a user-facing reason.
     *
     * Only rejects things yt-dlp could never use. Whether the *site* is
     * supported is yt-dlp's call, not ours.
     */
    fun rejectionReason(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.isEmpty() -> "Paste a link first."
            trimmed.length > MAX_URL_LENGTH -> "That link is too long."
            hostOf(trimmed) == null -> "That doesn't look like a web link."
            else -> null
        }
    }

    /** Lowercase hostname, or null if this isn't an http(s) URL. */
    private fun hostOf(url: String): String? {
        val match = Regex("^(https?)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        // Strip credentials and port.
        val host = match.groupValues[2].substringAfterLast('@').substringBefore(':')
        if (host.isEmpty()) return null
        return host.lowercase().trimEnd('.')
    }
}

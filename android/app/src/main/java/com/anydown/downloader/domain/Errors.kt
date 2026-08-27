package com.anydown.downloader.domain

/**
 * Turns yt-dlp's stderr into something worth showing a person.
 *
 * Ported from `backend/app/errors.py`. yt-dlp has no structured error types, so
 * string matching is the only option; anything unrecognised stays generic
 * rather than guessing.
 */
object Errors {

    enum class Code { UNSUPPORTED_URL, PRIVATE_CONTENT, GONE, NETWORK, NEEDS_FFMPEG, FAILED }

    /**
     * [detail] carries yt-dlp's own words. The friendly [message] is what the UI
     * leads with, but a personal tool should never hide the real error — when a
     * platform breaks, that raw line is the only thing that tells you why.
     */
    data class Classified(val code: Code, val message: String, val detail: String? = null)

    private val PRIVATE_PATTERNS = listOf(
        "private", "login required", "log in", "sign in", "requires authentication",
        "members-only", "members only", "subscribe to this channel", "age-restricted",
        "age restricted", "confirm your age", "cookies", "not authorized",
        "this account is", "follow this account",
    )

    private val UNSUPPORTED_PATTERNS = listOf(
        "unsupported url", "no suitable extractor", "is not a valid url",
    )

    private val GONE_PATTERNS = listOf(
        "video unavailable", "does not exist", "not found", "has been removed",
        "no longer available", "account has been terminated",
    )

    private val NETWORK_PATTERNS = listOf(
        "unable to download", "connection reset", "timed out", "timeout",
        "temporary failure in name resolution", "network is unreachable",
        "failed to resolve",
    )

    private val ANSI = Regex("\\[[0-9;]*m")

    fun classify(raw: String?): Classified {
        val clean = ANSI.replace(raw ?: "", "").trim().ifEmpty { null }
        val text = (clean ?: "").lowercase()

        UNSUPPORTED_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(
                Code.UNSUPPORTED_URL,
                "yt-dlp has no extractor for this site, or this isn't a media page.",
                clean,
            )
        }
        PRIVATE_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(
                Code.PRIVATE_CONTENT,
                "This content isn't publicly viewable. Only public content works.",
                clean,
            )
        }
        GONE_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(
                Code.GONE,
                "The platform says this content no longer exists.",
                clean,
            )
        }
        NETWORK_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(
                Code.NETWORK,
                "Network problem. Check your connection and retry.",
                clean,
            )
        }
        return Classified(
            Code.FAILED,
            "Couldn't read this link. If the site changed, tap Update to refresh yt-dlp.",
            clean,
        )
    }
}

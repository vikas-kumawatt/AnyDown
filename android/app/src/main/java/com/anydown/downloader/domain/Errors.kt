package com.anydown.downloader.domain

/**
 * Turns yt-dlp's stderr into something worth showing a person.
 *
 * Ported from `backend/app/errors.py`. yt-dlp has no structured error types, so
 * string matching is the only option; anything unrecognised stays generic
 * rather than guessing.
 */
object Errors {

    enum class Code { UNSUPPORTED_URL, PRIVATE_CONTENT, GONE, NETWORK, FAILED }

    data class Classified(val code: Code, val message: String)

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
        val text = ANSI.replace(raw ?: "", "").lowercase()

        UNSUPPORTED_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(
                Code.UNSUPPORTED_URL,
                "This link isn't from a supported platform, or isn't a media page.",
            )
        }
        PRIVATE_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(
                Code.PRIVATE_CONTENT,
                "This content isn't publicly viewable. Only public content is supported.",
            )
        }
        GONE_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(Code.GONE, "The platform says this content no longer exists.")
        }
        NETWORK_PATTERNS.firstOrNull { text.contains(it) }?.let {
            return Classified(Code.NETWORK, "Network problem. Check your connection and retry.")
        }
        return Classified(
            Code.FAILED,
            "Couldn't read this link. The platform may have changed — try updating " +
                "yt-dlp from the menu.",
        )
    }
}

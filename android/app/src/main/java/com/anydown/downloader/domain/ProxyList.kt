package com.anydown.downloader.domain

/**
 * Reads the public proxy lists the proxy finder downloads.
 *
 * They're plain text, one proxy per line, written either as `ip:port` (the
 * list's own protocol implied) or `scheme://ip:port`. Anything else — headers,
 * comments, `https://` proxies that need TLS to the proxy itself, which neither
 * Java nor yt-dlp's proxy settings speak — is skipped rather than guessed at.
 */
object ProxyList {

    private val SUPPORTED = setOf("http", "socks4", "socks5")
    private val ENTRY = Regex("""^(?:([a-z0-9]+)://)?(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$""")

    /** Distinct `scheme://ip:port` entries, in list order. */
    fun parse(text: String, defaultScheme: String): List<String> =
        text.lineSequence()
            .map { it.trim().lowercase() }
            .mapNotNull { line ->
                val match = ENTRY.matchEntire(line) ?: return@mapNotNull null
                val (scheme, ip, port) = match.destructured
                val resolved = scheme.ifEmpty { defaultScheme }
                val portNumber = port.toInt()
                if (resolved !in SUPPORTED || portNumber !in 1..65535) return@mapNotNull null
                if (ip.split('.').any { it.toInt() > 255 }) return@mapNotNull null
                "$resolved://$ip:$portNumber"
            }
            .distinct()
            .toList()

    /** Where to test a candidate: the site the user is trying to reach, when known. */
    fun testUrlFor(link: String): String {
        val host = Regex("^https?://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(link.trim())
            ?.groupValues?.get(1)?.substringAfterLast('@')?.substringBefore(':')
            ?.lowercase()
        return if (host.isNullOrBlank()) FALLBACK_TEST_URL else "https://$host/"
    }

    const val FALLBACK_TEST_URL = "https://www.google.com/generate_204"
}

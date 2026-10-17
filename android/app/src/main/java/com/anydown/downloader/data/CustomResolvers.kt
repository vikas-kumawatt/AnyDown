package com.anydown.downloader.data

import android.util.Log
import com.anydown.downloader.domain.DirectResult
import com.anydown.downloader.domain.RedditParser
import com.anydown.downloader.domain.TeraboxParser
import com.anydown.downloader.domain.ThreadsParser
import java.net.Proxy

/**
 * Sites yt-dlp can't handle, resolved here instead.
 *
 * Runs *before* yt-dlp and only for URLs it recognises; everything else falls
 * straight through. A resolver returning null also falls through, so a failure
 * here never makes a link worse than it was.
 *
 * The parsing lives in `domain` as pure functions so CI can test it. This class
 * only does the fetching and sequencing.
 */
object CustomResolvers {

    private const val TAG = "CustomResolvers"

    fun handles(url: String): Boolean =
        isThreads(url) || TeraboxParser.handles(url) || RedditParser.handles(url)

    /** Null means "not mine, or I couldn't do it" — the caller then tries yt-dlp. */
    fun resolve(url: String, proxyUrl: String): DirectResult? = try {
        val proxy = Http.proxyOf(proxyUrl)
        when {
            isThreads(url) -> resolveThreads(url, proxy)
            TeraboxParser.handles(url) -> resolveTerabox(url, proxy)
            RedditParser.handles(url) -> resolveReddit(url, proxy)
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "custom resolve failed for $url", e)
        null
    }

    // ----------------------------------------------------------------------
    // Threads
    // ----------------------------------------------------------------------

    private fun isThreads(url: String): Boolean {
        val host = hostOf(url)?.removePrefix("www.") ?: return false
        return host == "threads.net" || host.endsWith(".threads.net") ||
            host == "threads.com" || host.endsWith(".threads.com")
    }

    private fun resolveThreads(url: String, proxy: Proxy?): DirectResult? {
        // Ask for the .net host: it serves the same post and is the form Meta's
        // own embeds use, so it's the more stable of the two.
        val target = url.replace("threads.com", "threads.net")
        val response = Http.getText(
            target,
            headers = mapOf(
                // Without a desktop-ish Accept header Threads serves a shell
                // with no embedded media JSON at all.
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Mode" to "navigate",
            ),
            proxy = proxy,
        )
        return ThreadsParser.parse(response.body)?.also {
            Log.i(TAG, "threads: ${it.media.size} media found")
        }
    }

    // ----------------------------------------------------------------------
    // TeraBox
    // ----------------------------------------------------------------------

    private fun resolveTerabox(url: String, proxy: Proxy?): DirectResult? {
        val surl = TeraboxParser.extractSurl(url) ?: run {
            Log.w(TAG, "terabox: no share id in $url")
            return null
        }

        val page = Http.getText(TeraboxParser.sharePageUrl(surl), proxy = proxy)
        val token = TeraboxParser.extractJsToken(page.body) ?: run {
            Log.w(TAG, "terabox: no jsToken on the share page")
            return null
        }

        val info = Http.getText(
            TeraboxParser.shareInfoUrl(surl, token),
            headers = mapOf("Referer" to TeraboxParser.sharePageUrl(surl)),
            // The API only answers with the cookies the share page just set.
            cookies = page.cookies,
            proxy = proxy,
        )
        return TeraboxParser.parseShareInfo(info.body)?.also {
            Log.i(TAG, "terabox: ${it.media.size} file(s) found")
        }
    }

    // ----------------------------------------------------------------------
    // Reddit
    // ----------------------------------------------------------------------

    private val BROWSER_PAGE = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Sec-Fetch-Mode" to "navigate",
    )

    private fun resolveReddit(url: String, proxy: Proxy?): DirectResult? {
        // Share links (`/r/<sub>/s/<code>`) only become a post URL by following
        // them. The page they land on is kept: it's the fallback source below.
        var postUrl = url
        var page: String? = null
        if (RedditParser.postId(url) == null) {
            val landed = Http.getText(url, headers = BROWSER_PAGE, proxy = proxy)
            postUrl = landed.finalUrl
            page = landed.body
        }

        RedditParser.postId(postUrl)?.let { id ->
            runCatching {
                Http.getText(
                    RedditParser.jsonUrl(id),
                    headers = mapOf("Accept" to "application/json"),
                    proxy = proxy,
                )
            }.getOrNull()?.let { RedditParser.parseJson(it.body) }?.let {
                Log.i(TAG, "reddit: media found via json")
                return it
            }
        }

        val html = page ?: Http.getText(postUrl, headers = BROWSER_PAGE, proxy = proxy).body
        return RedditParser.parseHtml(html)?.also { Log.i(TAG, "reddit: media found via page") }
    }

    // ----------------------------------------------------------------------

    private fun hostOf(url: String): String? {
        val match = Regex("^(https?)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        val host = match.groupValues[2].substringAfterLast('@').substringBefore(':')
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }
}

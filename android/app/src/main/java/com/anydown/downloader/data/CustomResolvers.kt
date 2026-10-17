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

    /**
     * Null means "not mine, or I couldn't do it" — the caller then tries yt-dlp.
     *
     * [trace] collects why each step failed. A resolver failing silently and
     * yt-dlp then failing in its own words hid the real cause; the caller adds
     * these lines to the error details instead.
     */
    fun resolve(
        url: String,
        proxyUrl: String,
        trace: MutableList<String> = mutableListOf(),
    ): DirectResult? = try {
        val proxy = Http.proxyOf(proxyUrl)
        when {
            isThreads(url) -> resolveThreads(url, proxy)
            TeraboxParser.handles(url) -> resolveTerabox(url, proxy)
            RedditParser.handles(url) -> resolveReddit(url, proxy, trace)
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "custom resolve failed for $url", e)
        trace += "${e.javaClass.simpleName}: ${e.message}"
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

    /**
     * Mirrors the steps yt-dlp's own Reddit extractor takes, over Android's
     * network stack, which Reddit doesn't block the way it blocks yt-dlp's.
     */
    private fun resolveReddit(url: String, proxy: Proxy?, trace: MutableList<String>): DirectResult? {
        // Reddit's JSON only answers anonymous callers holding the `loid`
        // session cookie, which old.reddit.com hands out. over18 opts in to
        // NSFW posts, as yt-dlp does.
        val session = step(trace, "session") {
            Http.getText("https://old.reddit.com/", headers = BROWSER_PAGE, proxy = proxy).cookies
        }
        val cookies = listOfNotNull(session, "over18=1").joinToString("; ")

        // Share links (`/r/<sub>/s/<code>`) name the post only in their redirect.
        val postUrl = if (RedditParser.postId(url) != null) url else {
            step(trace, "share link") {
                Http.finalUrl(url, headers = BROWSER_PAGE, cookies = cookies, proxy = proxy)
            } ?: url
        }
        val id = RedditParser.postId(postUrl) ?: run {
            trace += "no post id in $postUrl"
            return null
        }

        for (host in listOf("www.reddit.com", "old.reddit.com")) {
            step(trace, "$host json") {
                val body = Http.getText(
                    RedditParser.jsonUrl(id, host),
                    headers = mapOf("Accept" to "application/json"),
                    cookies = cookies,
                    proxy = proxy,
                ).body
                RedditParser.parseJson(body) ?: error(
                    if (body.trimStart().startsWith("<")) "got a web page, not JSON"
                    else "no video or image in the post"
                )
            }?.let {
                Log.i(TAG, "reddit: media found via $host json")
                return it
            }
        }

        for (host in listOf("old.reddit.com", "www.reddit.com")) {
            step(trace, "$host page") {
                val html = Http.getText(
                    RedditParser.pageUrl(id, host),
                    headers = BROWSER_PAGE,
                    cookies = cookies,
                    proxy = proxy,
                ).body
                RedditParser.parseHtml(html) ?: error("no video player in the page")
            }?.let {
                Log.i(TAG, "reddit: media found via $host page")
                return it
            }
        }
        return null
    }

    /** Runs one resolver step; a failure is recorded in [trace] instead of thrown. */
    private inline fun <T> step(trace: MutableList<String>, name: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            trace += "$name: ${e.message ?: e.javaClass.simpleName}"
            null
        }

    // ----------------------------------------------------------------------

    private fun hostOf(url: String): String? {
        val match = Regex("^(https?)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        val host = match.groupValues[2].substringAfterLast('@').substringBefore(':')
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }
}

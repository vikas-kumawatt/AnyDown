package com.anydown.downloader.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A minimal GET helper for the custom resolvers.
 *
 * `HttpURLConnection` rather than a new dependency: two resolvers fetching a
 * page each doesn't justify pulling in OkHttp, and the app already uses this
 * class for following share redirects.
 */
object Http {

    private const val TAG = "Http"
    private const val TIMEOUT_MS = 15_000
    /** Share pages are large; past a couple of MB we're reading the wrong thing. */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    class HttpException(message: String) : IOException(message)

    /**
     * Fetch [url] as text, following redirects.
     *
     * Cookies from a previous response can be threaded back in via [cookies],
     * which TeraBox needs between the share page and the API call.
     */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookies: String? = null,
        proxy: java.net.Proxy? = null,
    ): Response {
        var current = url
        var lastCookies = cookies
        repeat(6) {
            val connection = open(current, headers, lastCookies, proxy)
            try {
                val code = connection.responseCode
                val setCookies = connection.headerFields["Set-Cookie"]
                    ?.joinToString("; ") { it.substringBefore(';') }
                if (!setCookies.isNullOrBlank()) {
                    lastCookies = listOfNotNull(lastCookies, setCookies).joinToString("; ")
                }

                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw HttpException("redirect with no Location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw HttpException("HTTP $code for $current")
                }

                val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
                val body = stream.use { it.readNBytes(MAX_BODY_BYTES).toString(Charsets.UTF_8) }
                return Response(body = body, finalUrl = current, cookies = lastCookies)
            } finally {
                connection.disconnect()
            }
        }
        throw HttpException("too many redirects for $url")
    }

    data class Response(val body: String, val finalUrl: String, val cookies: String?)

    private fun open(
        url: String,
        headers: Map<String, String>,
        cookies: String?,
        proxy: java.net.Proxy?,
    ): HttpURLConnection {
        val connection = (
            if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
            ) as HttpURLConnection
        connection.requestMethod = "GET"
        // Redirects are followed by hand so cookies can be carried across them.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", BROWSER_UA)
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        cookies?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("Cookie", it)
        }
        Log.d(TAG, "GET $url")
        return connection
    }

    /**
     * `readNBytes` needs API 33, and minSdk here is 24.
     */
    private fun java.io.InputStream.readNBytes(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) throw HttpException("response larger than $limit bytes")
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}

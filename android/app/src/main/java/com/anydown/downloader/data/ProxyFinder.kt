package com.anydown.downloader.data

import android.util.Log
import com.anydown.downloader.domain.ProxyList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a free public proxy that can reach a given site right now.
 *
 * Fixed proxy addresses would be dead within days, so nothing is built in:
 * fresh public lists are downloaded, a random sample is tested in parallel
 * against the site the user is actually trying to reach, and the fastest that
 * answers wins.
 *
 * The test is an HTTPS request through the proxy, so a candidate only passes if
 * it really tunnels to the site — a proxy can't fake a valid certificate. The
 * same holds for downloads: a public proxy sees which hosts you connect to, but
 * not what you fetch from them.
 */
object ProxyFinder {

    private const val TAG = "ProxyFinder"

    /** Plain `ip:port` lists, refreshed by their maintainers several times a day. */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt" to "http",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt" to "socks5",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt" to "http",
    )

    private const val MAX_CANDIDATES = 80
    private const val PARALLEL = 24
    private const val PROBE_TIMEOUT_MS = 5_000
    private const val SEARCH_DEADLINE_MS = 25_000L
    /** Stop once this many work: the fastest of a few beats waiting for all. */
    private const val ENOUGH = 3

    data class Found(val proxy: String, val latencyMs: Long)

    suspend fun find(link: String): Found = withContext(Dispatchers.IO) {
        val testUrl = ProxyList.testUrlFor(link)
        val candidates = candidates().shuffled().take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            throw IOException("Couldn't download a proxy list. Check your connection.")
        }
        Log.i(TAG, "testing ${candidates.size} proxies against $testUrl")

        val hits = Channel<Found>(Channel.UNLIMITED)
        val found = mutableListOf<Found>()
        coroutineScope {
            val gate = Semaphore(PARALLEL)
            val probes = candidates.map { proxy ->
                launch { gate.withPermit { probe(proxy, testUrl)?.let { hits.trySend(it) } } }
            }
            launch { probes.joinAll(); hits.close() }

            withTimeoutOrNull(SEARCH_DEADLINE_MS) {
                for (hit in hits) {
                    found += hit
                    if (found.size >= ENOUGH) break
                }
            }
            // Probes already connecting finish within PROBE_TIMEOUT_MS; queued
            // ones never start.
            coroutineContext.cancelChildren()
        }

        found.minByOrNull { it.latencyMs }
            ?: throw IOException(
                "None of ${candidates.size} public proxies could reach " +
                    "${testUrl.removePrefix("https://").trimEnd('/')}. Try again in a minute.",
            )
    }

    private suspend fun candidates(): List<String> = coroutineScope {
        SOURCES.map { (url, scheme) ->
            async {
                runCatching { ProxyList.parse(Http.getText(url).body, scheme) }
                    .onFailure { Log.i(TAG, "proxy list $url unavailable: ${it.message}") }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinct()
    }

    private fun probe(proxyUrl: String, testUrl: String): Found? {
        val proxy = Http.proxyOf(proxyUrl) ?: return null
        val started = System.nanoTime()
        return try {
            val connection = URL(testUrl).openConnection(proxy) as HttpURLConnection
            try {
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", Http.BROWSER_UA)
                val code = connection.responseCode
                // A redirect still proves the tunnel reached the real site.
                if (code in 200..399) Found(proxyUrl, (System.nanoTime() - started) / 1_000_000)
                else null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}

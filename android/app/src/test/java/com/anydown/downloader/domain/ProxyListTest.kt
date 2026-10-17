package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyListTest {

    @Test
    fun `bare entries take the list's own protocol`() {
        val text = "193.181.35.205:8118\n5.129.254.154:8888\n"
        assertEquals(
            listOf("http://193.181.35.205:8118", "http://5.129.254.154:8888"),
            ProxyList.parse(text, "http"),
        )
    }

    @Test
    fun `explicit schemes are kept, unsupported ones dropped`() {
        val text = """
            socks5://208.102.51.6:58208
            SOCKS4://1.2.3.4:1080
            https://5.6.7.8:443
            http://9.9.9.9:80
        """.trimIndent()
        assertEquals(
            listOf("socks5://208.102.51.6:58208", "socks4://1.2.3.4:1080", "http://9.9.9.9:80"),
            ProxyList.parse(text, "http"),
        )
    }

    @Test
    fun `junk lines, bad addresses and duplicates are skipped`() {
        val text = """
            # updated hourly
            not a proxy
            300.1.1.1:80
            1.1.1.1:70000
            1.1.1.1:80
            1.1.1.1:80
            example.com:8080
        """.trimIndent()
        assertEquals(listOf("http://1.1.1.1:80"), ProxyList.parse(text, "http"))
    }

    @Test
    fun `candidates are tested against the site being fetched`() {
        assertEquals(
            "https://www.tiktok.com/",
            ProxyList.testUrlFor("https://www.tiktok.com/@a/video/123?lang=en"),
        )
        assertEquals(ProxyList.FALLBACK_TEST_URL, ProxyList.testUrlFor(""))
        assertEquals(ProxyList.FALLBACK_TEST_URL, ProxyList.testUrlFor("not a link"))
    }
}

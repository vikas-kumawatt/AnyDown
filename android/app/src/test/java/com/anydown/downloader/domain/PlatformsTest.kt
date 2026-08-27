package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors backend/tests/test_platforms.py so the two allow-lists can't drift. */
class PlatformsTest {

    @Test
    fun `recognises supported links`() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=abc" to "youtube",
            "https://youtu.be/abc" to "youtube",
            "https://m.youtube.com/watch?v=abc" to "youtube",
            "https://www.tiktok.com/@u/video/1" to "tiktok",
            "https://vm.tiktok.com/ZM123/" to "tiktok",
            "https://x.com/u/status/1" to "twitter",
            "https://twitter.com/u/status/1" to "twitter",
            "https://www.dailymotion.com/video/x1" to "dailymotion",
            "https://dai.ly/x1" to "dailymotion",
            "https://www.instagram.com/reel/abc/" to "instagram",
            "https://www.facebook.com/watch?v=1" to "facebook",
            "https://fb.watch/abc/" to "facebook",
            "https://www.pinterest.com/pin/1/" to "pinterest",
            "https://in.pinterest.com/pin/1/" to "pinterest",
            "https://pinterest.co.uk/pin/1/" to "pinterest",
            "https://pin.it/abc" to "pinterest",
            "https://www.threads.net/@u/post/1" to "threads",
            "https://www.snapchat.com/spotlight/abc" to "snapchat",
        )
        cases.forEach { (url, expected) ->
            assertEquals(url, expected, Platforms.detectPlatform(url)?.id)
        }
    }

    @Test
    fun `rejects look-alike and non-http hosts`() {
        listOf(
            "https://evil.com/video",
            "http://localhost:8080/admin",
            "file:///etc/passwd",
            "ftp://youtube.com/x",
            // Substring matching would wrongly accept these.
            "https://youtube.com.evil.com/watch?v=1",
            "https://notyoutube.com/watch?v=1",
            "https://tiktok.com.attacker.net/v/1",
            "",
            "not a url",
        ).forEach { url ->
            assertNull(url, Platforms.detectPlatform(url))
        }
    }

    @Test
    fun `strips credentials and port before matching`() {
        assertNotNull(Platforms.detectPlatform("https://user:pw@www.youtube.com:443/watch?v=a"))
        assertNull(Platforms.detectPlatform("https://user@evil.com:443/x"))
    }

    @Test
    fun `normalises a trailing dot host`() {
        assertEquals("youtube", Platforms.detectPlatform("https://www.youtube.com./watch?v=a")?.id)
    }

    @Test
    fun `rejection reasons cover the failure cases`() {
        assertEquals("Paste a link first.", Platforms.rejectionReason("   "))
        assertNull(Platforms.rejectionReason("https://youtu.be/abc"))

        val tooLong = "https://youtube.com/watch?v=" + "a".repeat(3000)
        assertEquals("That URL is too long.", Platforms.rejectionReason(tooLong))

        assertEquals(
            "That doesn't look like a web link.",
            Platforms.rejectionReason("just some text"),
        )
        assertNotNull(Platforms.rejectionReason("https://evil.com/v"))
    }

    @Test
    fun `nine platforms are offered`() {
        assertEquals(9, Platforms.ALL.size)
    }
}

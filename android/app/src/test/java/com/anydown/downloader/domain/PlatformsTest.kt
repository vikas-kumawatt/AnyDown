package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformsTest {

    @Test
    fun `names the sites we recognise`() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=abc" to "youtube",
            "https://youtu.be/abc" to "youtube",
            "https://m.youtube.com/watch?v=abc" to "youtube",
            "https://www.tiktok.com/@u/video/1" to "tiktok",
            "https://vm.tiktok.com/ZM123/" to "tiktok",
            "https://x.com/u/status/1" to "twitter",
            "https://twitter.com/u/status/1" to "twitter",
            "https://www.instagram.com/reel/abc/" to "instagram",
            "https://www.facebook.com/watch?v=1" to "facebook",
            "https://fb.watch/abc/" to "facebook",
            "https://www.snapchat.com/spotlight/abc" to "snapchat",
            "https://www.threads.net/@u/post/1" to "threads",
            // The four that the old allow-list silently rejected.
            "https://www.reddit.com/r/x/comments/1/y/" to "reddit",
            "https://v.redd.it/abc" to "reddit",
            "https://vimeo.com/22439234" to "vimeo",
            "https://vk.com/video-1_2" to "vk",
            "https://www.linkedin.com/posts/x" to "linkedin",
            "https://www.dailymotion.com/video/x1" to "dailymotion",
            "https://dai.ly/x1" to "dailymotion",
            "https://www.pinterest.com/pin/1/" to "pinterest",
            "https://in.pinterest.com/pin/1/" to "pinterest",
            "https://pinterest.co.uk/pin/1/" to "pinterest",
            "https://pin.it/abc" to "pinterest",
            "https://www.twitch.tv/videos/1" to "twitch",
            "https://soundcloud.com/a/b" to "soundcloud",
        )
        cases.forEach { (url, expected) ->
            assertEquals(url, expected, Platforms.detectPlatform(url)?.id)
        }
    }

    @Test
    fun `does not mistake look-alike hosts for a known platform`() {
        listOf(
            "https://youtube.com.evil.com/watch?v=1",
            "https://notyoutube.com/watch?v=1",
            "https://tiktok.com.attacker.net/v/1",
            "https://evil.com/video",
            "file:///etc/passwd",
            "ftp://youtube.com/x",
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

    /**
     * The behaviour change that fixed Reddit, Vimeo, VK and LinkedIn: an
     * unrecognised host is no longer rejected, it's simply unlabelled. Whether
     * the site works is yt-dlp's call.
     */
    @Test
    fun `unknown sites are allowed through to yt-dlp`() {
        assertNull(Platforms.rejectionReason("https://some-obscure-video-site.example/v/1"))
        assertNull(Platforms.detectPlatform("https://some-obscure-video-site.example/v/1"))
    }

    @Test
    fun `only rejects what yt-dlp could never use`() {
        assertEquals("Paste a link first.", Platforms.rejectionReason("   "))
        assertEquals(
            "That doesn't look like a web link.",
            Platforms.rejectionReason("just some text"),
        )
        assertEquals(
            "That doesn't look like a web link.",
            Platforms.rejectionReason("file:///etc/passwd"),
        )
        assertEquals(
            "That link is too long.",
            Platforms.rejectionReason("https://youtube.com/watch?v=" + "a".repeat(3000)),
        )
        assertNull(Platforms.rejectionReason("https://youtu.be/abc"))
    }

    @Test
    fun `every listed platform has an id, label and at least one domain`() {
        Platforms.ALL.forEach { platform ->
            assertNotNull(platform.id.ifBlank { null })
            assertNotNull(platform.label.ifBlank { null })
            assertNotNull(platform.domains.firstOrNull())
        }
        // Ids must be unique, or detection results become ambiguous.
        assertEquals(Platforms.ALL.size, Platforms.ALL.map { it.id }.distinct().size)
    }
}

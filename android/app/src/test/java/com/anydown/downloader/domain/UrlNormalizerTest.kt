package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    /** The exact failure seen on device: threads.com came back "Unsupported URL". */
    @Test
    fun `rewrites threads_com to the host yt-dlp matches`() {
        assertEquals(
            "https://www.threads.net/@tanisha.xoco/post/DcgXiF7H9WH",
            UrlNormalizer.normalize(
                "https://www.threads.com/@tanisha.xoco/post/DcgXiF7H9WH" +
                    "?xmt=AQGO1SlEq5mH_5JC5wUVN4hGLYvCU3Y4TxYwdAvZ&slof=1"
            ),
        )
        assertEquals(
            "https://threads.net/@u/post/1",
            UrlNormalizer.normalize("https://threads.com/@u/post/1"),
        )
    }

    @Test
    fun `strips tracking parameters`() {
        assertEquals(
            "https://www.instagram.com/reel/abc/",
            UrlNormalizer.normalize("https://www.instagram.com/reel/abc/?igsh=MXY&utm_source=ig"),
        )
        assertEquals(
            "https://vm.tiktok.com/ZM123/",
            UrlNormalizer.normalize(
                "https://vm.tiktok.com/ZM123/?is_from_webapp=1&sender_device=pc"
            ),
        )
    }

    /** Stripping an unknown parameter breaks far more links than it fixes. */
    @Test
    fun `keeps load-bearing parameters`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            UrlNormalizer.normalize("https://www.youtube.com/watch?v=abc123&si=TRACK"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc&list=PL1&index=2",
            UrlNormalizer.normalize("https://www.youtube.com/watch?v=abc&list=PL1&index=2"),
        )
        assertEquals(
            "https://www.dailymotion.com/video/x8k2p",
            UrlNormalizer.normalize("https://www.dailymotion.com/video/x8k2p"),
        )
    }

    /**
     * vimeo.com refuses anonymous extraction now ("The web client only works
     * when logged-in"); player.vimeo.com still answers without credentials.
     */
    @Test
    fun `routes vimeo through the player endpoint`() {
        assertEquals(
            "https://player.vimeo.com/video/1219875917",
            UrlNormalizer.normalize("https://vimeo.com/1219875917?share=copy&fl=cl&fe=ci"),
        )
        assertEquals(
            "https://player.vimeo.com/video/1219875917",
            UrlNormalizer.normalize("https://www.vimeo.com/1219875917"),
        )
        // Unlisted videos carry a hash, which the player endpoint takes as ?h=
        assertEquals(
            "https://player.vimeo.com/video/76979871?h=8272103a63",
            UrlNormalizer.normalize("https://vimeo.com/76979871/8272103a63"),
        )
    }

    @Test
    fun `leaves non-video vimeo paths alone`() {
        assertEquals(
            "https://vimeo.com/channels/staffpicks",
            UrlNormalizer.normalize("https://vimeo.com/channels/staffpicks"),
        )
    }

    @Test
    fun `drops the fragment and trims`() {
        assertEquals(
            "https://example.com/a",
            UrlNormalizer.normalize("  https://example.com/a#anchor  "),
        )
    }

    @Test
    fun `leaves anything it cannot parse alone`() {
        assertEquals("not a url", UrlNormalizer.normalize("not a url"))
        assertEquals("", UrlNormalizer.normalize(""))
        assertEquals("file:///etc/passwd", UrlNormalizer.normalize("file:///etc/passwd"))
    }

    @Test
    fun `preserves port and path`() {
        assertEquals(
            "https://example.com:8443/deep/path.mp4",
            UrlNormalizer.normalize("https://example.com:8443/deep/path.mp4?utm_medium=x"),
        )
    }

    @Test
    fun `recognises links worth resolving first`() {
        listOf(
            "https://pin.it/6jSSzAZ95",
            "https://vm.tiktok.com/ZM123/",
            "https://www.threads.com/share/_vqVsdeS1/",
            "https://fb.watch/abc/",
            "https://youtu.be/abc",
            "https://lnkd.in/xyz",
        ).forEach { assertTrue(it, UrlNormalizer.isShortLink(it)) }

        listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://www.dailymotion.com/video/x1",
            "not a url",
        ).forEach { assertFalse(it, UrlNormalizer.isShortLink(it)) }
    }
}

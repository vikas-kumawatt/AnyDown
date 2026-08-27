package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsParserTest {

    private fun page(body: String) = """
        <html><head>
        <meta property="og:title" content="Tanisha (@tanisha.xoco) on Threads" />
        <meta property="og:image" content="https://scontent.cdninstagram.com/v/thumb.jpg" />
        </head><body><script>$body</script></body></html>
    """.trimIndent()

    @Test
    fun `pulls progressive videos out of video_versions, best first`() {
        val html = page(
            """
            {"video_versions":[
              {"type":101,"width":720,"height":1280,"url":"https:\/\/scontent.cdninstagram.com\/v\/hi.mp4?oe=1"},
              {"type":102,"width":480,"height":852,"url":"https:\/\/scontent.cdninstagram.com\/v\/lo.mp4?oe=2"}
            ]}
            """
        )
        val result = ThreadsParser.parse(html)!!

        assertEquals("Tanisha", result.title)
        assertEquals("tanisha.xoco", result.uploader)
        assertEquals(2, result.media.size)
        assertEquals(1280, result.media[0].height)
        assertEquals("1280p MP4", result.media[0].label)
        // Escaped slashes in the embedded JSON must be resolved.
        assertTrue(result.media[0].url.startsWith("https://scontent.cdninstagram.com/v/hi.mp4"))
        assertEquals(852, result.media[1].height)
        assertTrue(result.media.all { it.kind == FormatPlanner.Kind.PROGRESSIVE })
    }

    /**
     * Meta reshapes these payloads regularly, so an unknown structure must still
     * yield the media rather than nothing.
     */
    @Test
    fun `falls back to scanning for cdn mp4 urls`() {
        val html = page(
            """{"some_new_shape":{"playable":"https:\/\/scontent.cdninstagram.com\/v\/clip.mp4?x=1"}}"""
        )
        val result = ThreadsParser.parse(html)!!
        assertEquals(1, result.media.size)
        assertEquals(FormatPlanner.Kind.PROGRESSIVE, result.media[0].kind)
        assertEquals("Original quality (MP4)", result.media[0].label)
    }

    /**
     * An .mp4 on some unrelated host is not the post's video. Falling back to
     * the post's own image is correct here — what must not happen is a tracking
     * pixel being offered as the video.
     */
    @Test
    fun `does not mistake an off-cdn mp4 for the post video`() {
        val html = page("""{"tracking":"https://example.com/analytics/beacon.mp4"}""")
        val result = ThreadsParser.parse(html)!!
        assertTrue(result.media.none { it.kind == FormatPlanner.Kind.PROGRESSIVE })
        assertTrue(result.media.none { "example.com" in it.url })
    }

    @Test
    fun `returns null when neither video nor image is on a media cdn`() {
        val html = """
            <html><head>
            <meta property="og:image" content="https://example.com/logo.png" />
            </head><body><script>{"x":"https://example.com/a.mp4"}</script></body></html>
        """.trimIndent()
        assertNull(ThreadsParser.parse(html))
    }

    @Test
    fun `offers images when the post has no video`() {
        val html = page(
            """
            {"image_versions2":{"candidates":[
              {"width":1080,"height":1350,"url":"https:\/\/scontent.cdninstagram.com\/v\/big.jpg"},
              {"width":640,"height":800,"url":"https:\/\/scontent.cdninstagram.com\/v\/mid.jpg"},
              {"width":320,"height":400,"url":"https:\/\/scontent.cdninstagram.com\/v\/small.jpg"},
              {"width":150,"height":190,"url":"https:\/\/scontent.cdninstagram.com\/v\/tiny.jpg"}
            ]}}
            """
        )
        val result = ThreadsParser.parse(html)!!
        // Capped at three; the same picture at ten sizes isn't a choice.
        assertEquals(3, result.media.size)
        assertTrue(result.media.all { it.kind == FormatPlanner.Kind.IMAGE })
        assertEquals("Image 1350px (JPG)", result.media[0].label)
    }

    @Test
    fun `prefers video over images when both are present`() {
        val html = page(
            """
            {"video_versions":[{"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.mp4"}],
             "image_versions2":{"candidates":[{"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.jpg"}]}}
            """
        )
        val result = ThreadsParser.parse(html)!!
        assertEquals(1, result.media.size)
        assertEquals(FormatPlanner.Kind.PROGRESSIVE, result.media[0].kind)
    }

    @Test
    fun `deduplicates the same video listed twice`() {
        val html = page(
            """
            {"video_versions":[
              {"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.mp4?token=1"},
              {"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.mp4?token=2"}
            ]}
            """
        )
        assertEquals(1, ThreadsParser.parse(html)!!.media.size)
    }

    @Test
    fun `returns null for a page with no media`() {
        assertNull(ThreadsParser.parse("<html><body>nothing here</body></html>"))
    }

    @Test
    fun `carries the headers the cdn requires`() {
        val html = page("""{"video_versions":[{"height":720,"url":"https://scontent.cdninstagram.com/v/a.mp4"}]}""")
        val headers = ThreadsParser.parse(html)!!.media[0].headers
        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers.containsKey("Referer"))
    }

    @Test
    fun `falls back to a generic title when meta is absent`() {
        val html = """<html><body><script>{"video_versions":[{"url":"https://scontent.cdninstagram.com/a.mp4"}]}</script></body></html>"""
        assertEquals("Threads post", ThreadsParser.parse(html)!!.title)
    }
}

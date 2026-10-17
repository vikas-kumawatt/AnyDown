package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditParserTest {

    /** Trimmed shape of `/comments/<id>/.json?raw_json=1` for a video post. */
    private val videoJson = """
        [{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
          "title":"Samay's best roast","author":"latentfan","thumbnail":"https://b.thumbs.redditmedia.com/t.jpg",
          "is_video":true,
          "secure_media":{"reddit_video":{"bitrate_kbps":2400,
            "fallback_url":"https://v.redd.it/abc123xyz/DASH_720.mp4?source=fallback",
            "height":720,"width":1280,"duration":42,
            "dash_url":"https://v.redd.it/abc123xyz/DASHPlaylist.mpd?a=SIGNED&v=1&f=sd",
            "hls_url":"https://v.redd.it/abc123xyz/HLSPlaylist.m3u8?a=SIGNED&v=1&f=sd"}}
        }}]}},{"kind":"Listing","data":{"children":[{"kind":"t1","data":{"author":"commenter"}}]}}]
    """.trimIndent()

    @Test
    fun `recognises reddit post hosts but not its media hosts`() {
        assertTrue(RedditParser.handles("https://www.reddit.com/r/IndiasGotLatent/s/2hQKgRoad1"))
        assertTrue(RedditParser.handles("https://old.reddit.com/r/videos/comments/abc/x/"))
        assertTrue(RedditParser.handles("https://redd.it/abc"))
        assertFalse(RedditParser.handles("https://v.redd.it/abc123xyz"))
        assertFalse(RedditParser.handles("https://notreddit.com/r/x"))
        assertFalse(RedditParser.handles("https://reddit.com.evil.example/r/x"))
    }

    @Test
    fun `share links have no post id until followed`() {
        assertNull(RedditParser.postId("https://www.reddit.com/r/IndiasGotLatent/s/2hQKgRoad1"))
        assertEquals(
            "1x5ylwl",
            RedditParser.postId("https://www.reddit.com/r/IndiasGotLatent/comments/1x5ylwl/title/"),
        )
    }

    @Test
    fun `video post yields the signed hls manifest with its height`() {
        val result = RedditParser.parseJson(videoJson)!!

        assertEquals("Samay's best roast", result.title)
        assertEquals("latentfan", result.uploader)
        assertEquals(42, result.durationSeconds)
        assertEquals(1, result.media.size)
        val media = result.media[0]
        assertEquals("https://v.redd.it/abc123xyz/HLSPlaylist.m3u8?a=SIGNED&v=1&f=sd", media.url)
        assertEquals(720, media.height)
        assertEquals("720p MP4", media.label)
        assertEquals(FormatPlanner.Kind.PROGRESSIVE, media.kind)
        assertEquals("https://www.reddit.com/", media.headers["Referer"])
    }

    @Test
    fun `image post yields the image`() {
        val json = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
            "title":"Sunset","author":"a","thumbnail":"self",
            "url_overridden_by_dest":"https://i.redd.it/sunset.jpeg"}}]}}]"""
        val result = RedditParser.parseJson(json)!!

        assertEquals(FormatPlanner.Kind.IMAGE, result.media[0].kind)
        assertEquals("jpeg", result.media[0].ext)
        // "self" is Reddit's placeholder, not a URL.
        assertEquals("https://i.redd.it/sunset.jpeg", result.thumbnail)
    }

    @Test
    fun `a block page instead of json is not mistaken for a post`() {
        assertNull(RedditParser.parseJson("<html><body>You've been blocked by network security.</body></html>"))
    }

    @Test
    fun `link posts to other sites are left for yt-dlp`() {
        val json = """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{
            "title":"Look","url_overridden_by_dest":"https://www.youtube.com/watch?v=x"}}]}}]"""
        assertNull(RedditParser.parseJson(json))
    }

    @Test
    fun `html fallback reads the player's manifest and decodes entities`() {
        val html = """
            <html><head><meta property="og:title" content="Samay&#39;s best roast" />
            <meta property="og:image" content="https://external-preview.redd.it/p.jpg" /></head>
            <body><shreddit-player src="https://v.redd.it/abc123xyz/HLSPlaylist.m3u8?a=SIGNED&amp;v=1&amp;f=sd">
            </shreddit-player></body></html>
        """.trimIndent()
        val result = RedditParser.parseHtml(html)!!

        assertEquals("Samay's best roast", result.title)
        assertEquals("https://v.redd.it/abc123xyz/HLSPlaylist.m3u8?a=SIGNED&v=1&f=sd", result.media[0].url)
    }

    @Test
    fun `html with only a bare video id still gets a manifest`() {
        val result = RedditParser.parseHtml("""<div data-url="https://v.redd.it/abc123xyz"></div>""")!!
        assertEquals("https://v.redd.it/abc123xyz/DASHPlaylist.mpd", result.media[0].url)
    }

    @Test
    fun `html without any reddit video yields nothing`() {
        assertNull(RedditParser.parseHtml("<html><body>A text post.</body></html>"))
    }
}

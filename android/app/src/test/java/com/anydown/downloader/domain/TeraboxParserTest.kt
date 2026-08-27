package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeraboxParserTest {

    @Test
    fun `recognises the domains terabox rotates through`() {
        listOf(
            "https://terabox.com/s/1abc",
            "https://www.terabox.com/s/1abc",
            "https://1024terabox.com/s/1abc",
            "https://www.4funbox.com/s/1abc",
            "https://nephobox.com/s/1abc",
            "https://terasharelink.com/s/1abc",
            "https://freeterabox.com/s/1abc",
        ).forEach { assertTrue(it, TeraboxParser.handles(it)) }

        listOf(
            "https://youtube.com/watch?v=1",
            "https://notterabox.com/s/1abc",
            "https://terabox.com.evil.net/s/1abc",
            "not a url",
        ).forEach { assertFalse(it, TeraboxParser.handles(it)) }
    }

    /** The /s/ form carries a leading "1" the API doesn't want. */
    @Test
    fun `extracts the share id from both link shapes`() {
        assertEquals("AbCdEf", TeraboxParser.extractSurl("https://terabox.com/s/1AbCdEf"))
        assertEquals("AbCdEf", TeraboxParser.extractSurl("https://terabox.com/s/1AbCdEf?x=1"))
        assertEquals("AbCdEf", TeraboxParser.extractSurl("https://terabox.com/wap/share/filelist?surl=AbCdEf"))
        assertNull(TeraboxParser.extractSurl("https://terabox.com/main?category=all"))
    }

    @Test
    fun `finds jsToken in each form the share page uses`() {
        val token = "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6E7F8A9B0"
        listOf(
            """<script>var x = {"jsToken":"$token"};</script>""",
            """<script>fn%28%22$token%22%29</script>""",
            """<script>fn("$token")</script>""",
            """window.locals = {jsToken: '$token'}""",
        ).forEach { html ->
            assertEquals(html, token, TeraboxParser.extractJsToken(html))
        }
        assertNull(TeraboxParser.extractJsToken("<html>no token here</html>"))
    }

    @Test
    fun `parses the file list into downloadable media`() {
        val json = """
        {"errno":0,"shareid":123,"uk":456,"list":[
          {"fs_id":9,"isdir":0,"server_filename":"Holiday clip.mp4","size":"48211234",
           "thumbs":{"url1":"https://thumb/s.jpg","url3":"https://thumb/l.jpg"},
           "dlink":"https://d.terabox.com/file/abc?fid=9"}
        ]}
        """.trimIndent()

        val result = TeraboxParser.parseShareInfo(json)!!
        assertEquals("Holiday clip.mp4", result.title)
        // Largest thumbnail wins.
        assertEquals("https://thumb/l.jpg", result.thumbnail)
        assertEquals(1, result.media.size)

        val media = result.media[0]
        assertEquals("https://d.terabox.com/file/abc?fid=9", media.url)
        assertEquals("mp4", media.ext)
        // Size arrives as a quoted string, not a number.
        assertEquals(48_211_234L, media.sizeBytes)
        assertEquals(FormatPlanner.Kind.PROGRESSIVE, media.kind)
        assertEquals("https://www.terabox.com/", media.headers["Referer"])
    }

    @Test
    fun `skips folders, which have no dlink`() {
        val json = """
        {"errno":0,"list":[
          {"fs_id":1,"isdir":1,"server_filename":"My folder"},
          {"fs_id":2,"isdir":0,"server_filename":"clip.mp4","size":100,
           "dlink":"https://d.terabox.com/file/x"}
        ]}
        """.trimIndent()
        val result = TeraboxParser.parseShareInfo(json)!!
        assertEquals(1, result.media.size)
        assertEquals("clip.mp4", result.media[0].label)
    }

    @Test
    fun `classifies images by extension`() {
        val json = """
        {"list":[{"isdir":0,"server_filename":"photo.JPG","size":900,
                  "dlink":"https://d.terabox.com/file/p"}]}
        """.trimIndent()
        val media = TeraboxParser.parseShareInfo(json)!!.media[0]
        assertEquals("jpg", media.ext)
        assertEquals(FormatPlanner.Kind.IMAGE, media.kind)
    }

    @Test
    fun `handles multiple files in one share`() {
        val json = """
        {"list":[
          {"isdir":0,"server_filename":"one.mp4","size":10,"dlink":"https://d/1"},
          {"isdir":0,"server_filename":"two.mkv","size":20,"dlink":"https://d/2"}
        ]}
        """.trimIndent()
        val result = TeraboxParser.parseShareInfo(json)!!
        assertEquals(2, result.media.size)
        assertEquals("one.mp4", result.title)
        assertEquals(listOf("one.mp4", "two.mkv"), result.media.map { it.label })
    }

    /** An expired token or private share returns a body with no dlink. */
    @Test
    fun `returns null when there is nothing downloadable`() {
        assertNull(TeraboxParser.parseShareInfo("""{"errno":2,"list":[]}"""))
        assertNull(TeraboxParser.parseShareInfo("""{"errno":-9,"show_msg":"expired"}"""))
        assertNull(
            TeraboxParser.parseShareInfo(
                """{"list":[{"isdir":0,"server_filename":"x.mp4","size":1}]}"""
            )
        )
    }

    @Test
    fun `builds the two endpoints it needs`() {
        assertTrue(TeraboxParser.sharePageUrl("abc").contains("surl=abc"))
        val info = TeraboxParser.shareInfoUrl("abc", "TOKEN")
        assertTrue(info.contains("shorturl=abc"))
        assertTrue(info.contains("jsToken=TOKEN"))
        assertTrue(info.contains("root=1"))
    }
}

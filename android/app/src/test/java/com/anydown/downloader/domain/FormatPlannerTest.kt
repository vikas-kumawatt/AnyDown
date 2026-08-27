package com.anydown.downloader.domain

import com.anydown.downloader.domain.FormatPlanner.Kind
import com.anydown.downloader.domain.FormatPlanner.RawFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors backend/tests/test_planner.py — same rules, different language. */
class FormatPlannerTest {

    private fun youtubeFormats() = listOf(
        RawFormat("140", "m4a", null, "none", "mp4a.40.2", 3_400_000, 129.0),
        RawFormat("251", "webm", null, "none", "opus", 3_200_000, 120.0),
        RawFormat("137", "mp4", 1080, "avc1.640028", "none", 60_000_000, 4200.0),
        RawFormat("248", "webm", 1080, "vp9", "none", 45_000_000, 3000.0),
        RawFormat("18", "mp4", 360, "avc1.42001E", "mp4a.40.2", 18_000_000, 700.0),
        RawFormat("271", "webm", 1440, "vp9", "none", 150_000_000, 9000.0),
        // Storyboard pseudo-format: must be ignored.
        RawFormat("sb0", "mhtml", 90, "none", "none", null, null),
    )

    @Test
    fun `one entry per resolution, best first`() {
        val heights = FormatPlanner.plan(youtubeFormats(), 212.0)
            .mapNotNull { option -> Regex("^(\\d+)p").find(option.label)?.groupValues?.get(1)?.toInt() }
        assertEquals(heights.sortedDescending(), heights)
        assertEquals(heights.distinct().size, heights.size)
    }

    @Test
    fun `no resolution cap on device, unlike the server build`() {
        val labels = FormatPlanner.plan(youtubeFormats(), 212.0).map { it.label }
        assertTrue("1440p should be offered locally", labels.any { it.startsWith("1440p") })
    }

    @Test
    fun `storyboards are dropped`() {
        assertTrue(FormatPlanner.plan(youtubeFormats(), 212.0).none { it.selector == "sb0" })
    }

    @Test
    fun `1080p pairs avc1 with m4a into an mp4 merge`() {
        val option = FormatPlanner.plan(youtubeFormats(), 212.0).first { it.label.startsWith("1080p") }
        assertEquals(Kind.MERGE, option.kind)
        // avc1/mp4 must beat vp9/webm so the container stays MP4.
        assertEquals("137+140", option.selector)
        assertEquals("mp4", option.mergeContainer)
        assertEquals("mp4", option.ext)
        assertEquals(60_000_000L + 3_400_000L, option.sizeBytes)
    }

    @Test
    fun `progressive is preferred and needs no merge`() {
        val option = FormatPlanner.plan(youtubeFormats(), 212.0).first { it.label.startsWith("360p") }
        assertEquals(Kind.PROGRESSIVE, option.kind)
        assertEquals("18", option.selector)
        assertNull(option.mergeContainer)
    }

    @Test
    fun `audio option prefers m4a over opus`() {
        val option = FormatPlanner.plan(youtubeFormats(), 212.0).first { it.kind == Kind.AUDIO }
        assertEquals("140", option.selector)
        assertEquals("m4a", option.ext)
        assertEquals("Audio only (M4A)", option.label)
    }

    @Test
    fun `webm only sources merge into matroska`() {
        val formats = youtubeFormats().filter { it.formatId == "248" || it.formatId == "251" }
        val option = FormatPlanner.plan(formats, 212.0).first { it.kind == Kind.MERGE }
        assertEquals("mkv", option.mergeContainer)
        assertEquals("mkv", option.ext)
        assertEquals("248+251", option.selector)
    }

    @Test
    fun `missing codec fields are treated as a self-contained stream`() {
        val tiktok = listOf(
            RawFormat("download", "mp4", 1024, null, null, 2_500_000, null)
        )
        val options = FormatPlanner.plan(tiktok, 15.0)
        assertEquals(1, options.size)
        assertEquals(Kind.PROGRESSIVE, options[0].kind)
        assertEquals(2_500_000L, options[0].sizeBytes)
    }

    @Test
    fun `size is estimated from bitrate when absent`() {
        val formats = listOf(
            RawFormat("hls-720", "mp4", 720, "avc1.4d401f", "mp4a.40.2", null, 1800.0)
        )
        val option = FormatPlanner.plan(formats, 60.0).first()
        assertEquals((1800.0 * 1000 / 8 * 60).toLong(), option.sizeBytes)
    }

    @Test
    fun `size is null when neither filesize nor bitrate is known`() {
        val formats = listOf(RawFormat("x", "mp4", 720, "avc1", "mp4a", null, null))
        assertNull(FormatPlanner.plan(formats, null).first().sizeBytes)
    }

    @Test
    fun `video with no audio to pair is skipped`() {
        val formats = listOf(
            RawFormat("137", "mp4", 1080, "avc1", "none", 60_000_000, 4200.0)
        )
        assertTrue(FormatPlanner.plan(formats, 212.0).isEmpty())
    }

    /**
     * The Pinterest regression: a pin reported six formats with no codec
     * information and every one of them was discarded, surfacing as "found no
     * downloadable media". Unknown codecs now mean "let yt-dlp decide", not
     * "throw it away".
     */
    @Test
    fun `formats with no codec information are still offered`() {
        val pinterest = listOf(
            RawFormat("hls-1080", "mp4", 1080, "none", "none", null, 2400.0),
            RawFormat("hls-720", "mp4", 720, "none", "none", null, 1200.0),
        )
        val options = FormatPlanner.plan(pinterest, 30.0)
        assertEquals(2, options.size)
        assertTrue(options.all { it.kind == Kind.PROGRESSIVE })
        assertEquals("hls-1080", options[0].selector)
    }

    @Test
    fun `image pins are offered as images, largest first`() {
        val pin = listOf(
            RawFormat("orig", "jpg", 1200, "none", "none", 480_000, null),
            RawFormat("med", "jpg", 600, "none", "none", 120_000, null),
            RawFormat("small", "jpg", 236, "none", "none", 20_000, null),
            RawFormat("tiny", "jpg", 75, "none", "none", 4_000, null),
        )
        val options = FormatPlanner.plan(pin, null)
        // Capped at three so a pin's thumbnail ladder isn't dumped on screen.
        assertEquals(3, options.size)
        assertTrue(options.all { it.kind == Kind.IMAGE })
        assertEquals("Image 1200px (JPG)", options[0].label)
        assertEquals("orig", options[0].selector)
        assertEquals(480_000L, options[0].sizeBytes)
        assertNull(options[0].mergeContainer)
    }

    @Test
    fun `images and video can coexist`() {
        val mixed = listOf(
            RawFormat("v", "mp4", 720, "avc1", "mp4a", 5_000_000, null),
            RawFormat("thumb", "jpg", 1200, "none", "none", 400_000, null),
        )
        val options = FormatPlanner.plan(mixed, 10.0)
        assertEquals(listOf(Kind.PROGRESSIVE, Kind.IMAGE), options.map { it.kind })
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(FormatPlanner.plan(emptyList(), 10.0).isEmpty())
    }

    @Test
    fun `unknown resolution still gets offered`() {
        val formats = listOf(RawFormat("only", "mp4", null, "avc1", "mp4a", 500, null))
        val option = FormatPlanner.plan(formats, null).first()
        assertEquals("Original quality (MP4)", option.label)
    }

    @Test
    fun `plan is deterministic`() {
        val first = FormatPlanner.plan(youtubeFormats(), 212.0).map { it.id }
        val second = FormatPlanner.plan(youtubeFormats(), 212.0).map { it.id }
        assertEquals(first, second)
        assertNotNull(first.firstOrNull())
    }
}

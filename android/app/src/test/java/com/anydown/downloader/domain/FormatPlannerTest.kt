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
    fun `best available is offered first and lets yt-dlp choose`() {
        val option = FormatPlanner.plan(youtubeFormats(), 212.0).first()
        assertEquals(Kind.BEST, option.kind)
        assertEquals("bv*+ba/b", option.selector)
        assertEquals("mp4", option.mergeContainer)
    }

    /** Without ffmpeg the merge branch can't run, so ask for a single stream. */
    @Test
    fun `best available degrades when ffmpeg is missing`() {
        val options = FormatPlanner.plan(youtubeFormats(), 212.0, canMerge = false)
        assertEquals("b", options.first().selector)
        assertNull(options.first().mergeContainer)
        assertTrue(options.none { it.kind == Kind.MERGE })
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
        val options = FormatPlanner.plan(tiktok, 15.0).filter { it.kind != Kind.BEST }
        assertEquals(1, options.size)
        assertEquals(Kind.PROGRESSIVE, options[0].kind)
        assertEquals(2_500_000L, options[0].sizeBytes)
    }

    @Test
    fun `size is estimated from bitrate when absent`() {
        val formats = listOf(
            RawFormat("hls-720", "mp4", 720, "avc1.4d401f", "mp4a.40.2", null, 1800.0)
        )
        val option = FormatPlanner.plan(formats, 60.0).first { it.kind != Kind.BEST }
        assertEquals((1800.0 * 1000 / 8 * 60).toLong(), option.sizeBytes)
    }

    @Test
    fun `size is null when neither filesize nor bitrate is known`() {
        val formats = listOf(RawFormat("x", "mp4", 720, "avc1", "mp4a", null, null))
        val option = FormatPlanner.plan(formats, null).first { it.kind != Kind.BEST }
        assertNull(option.sizeBytes)
    }

    /**
     * A video-only stream has nothing to merge with, so no merge row appears —
     * but Best available still does, because `bv*+ba/b` falls through to the
     * best single stream when there's no audio to pair.
     */
    @Test
    fun `video with no audio produces no merge option`() {
        val formats = listOf(
            RawFormat("137", "mp4", 1080, "avc1", "none", 60_000_000, 4200.0)
        )
        val options = FormatPlanner.plan(formats, 212.0)
        assertTrue(options.none { it.kind == Kind.MERGE })
        assertEquals(listOf(Kind.BEST), options.map { it.kind })
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
        val options = FormatPlanner.plan(pinterest, 30.0).filter { it.kind != Kind.BEST }
        assertEquals(2, options.size)
        assertTrue(options.all { it.kind == Kind.PROGRESSIVE })
        assertEquals("hls-1080", options[0].selector)
    }

    /**
     * The second Pinterest bug. Its renditions report no height at all, so
     * keying buckets by height collapsed every one of them into a single entry
     * and an arbitrary winner — in practice an audio-only rendition, which is
     * why "Original quality (MP4)" downloaded audio. Unknown heights now key by
     * format id so each stays distinct, and an .m4a with unknown codecs is
     * classified as audio rather than headlined as video.
     */
    @Test
    fun `formats with unknown height do not collapse into one`() {
        val pinterest = listOf(
            RawFormat("hls-audio", "m4a", null, "none", "none", null, 64.0),
            RawFormat("hls-video-hi", "mp4", null, "none", "none", null, 2400.0),
            RawFormat("hls-video-lo", "mp4", null, "none", "none", null, 800.0),
        )
        val options = FormatPlanner.plan(pinterest, 16.0)

        // Best available, two video renditions, one audio — not a single entry.
        assertEquals(Kind.BEST, options.first().kind)
        val video = options.filter { it.kind == Kind.PROGRESSIVE }
        assertEquals(2, video.size)
        assertEquals("hls-video-hi", video[0].selector)
        assertEquals("hls-video-lo", video[1].selector)

        // The audio rendition is labelled as audio, never offered as the video.
        val audio = options.single { it.kind == Kind.AUDIO }
        assertEquals("hls-audio", audio.selector)
        assertTrue(video.none { it.selector == "hls-audio" })
    }

    @Test
    fun `audio extensions are trusted when codecs are unreported`() {
        listOf("m4a", "mp3", "opus", "aac", "wav", "flac").forEach { ext ->
            val options = FormatPlanner.plan(
                listOf(RawFormat("a", ext, null, null, null, 1_000, null)), 10.0
            )
            assertEquals(ext, Kind.AUDIO, options.last().kind)
            assertTrue(ext, options.none { it.kind == Kind.PROGRESSIVE })
        }
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
        assertEquals(listOf(Kind.BEST, Kind.PROGRESSIVE, Kind.IMAGE), options.map { it.kind })
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(FormatPlanner.plan(emptyList(), 10.0).isEmpty())
    }

    @Test
    fun `unknown resolution still gets offered`() {
        val formats = listOf(RawFormat("only", "mp4", null, "avc1", "mp4a", 500, null))
        val option = FormatPlanner.plan(formats, null).first { it.kind != Kind.BEST }
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

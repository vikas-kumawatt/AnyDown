package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenamesTest {

    @Test
    fun `strips characters that break android filesystems`() {
        assertEquals("Example _ Video_ _test", Filenames.sanitizeBase("Example / Video: <test>"))
        assertEquals("a_b", Filenames.sanitizeBase("a|b"))
        // A run of unsafe characters collapses to one "_", which is then
        // trimmed off the end.
        assertEquals("q", Filenames.sanitizeBase("q?*"))
    }

    @Test
    fun `falls back when nothing usable survives`() {
        assertEquals("download", Filenames.sanitizeBase(null))
        assertEquals("download", Filenames.sanitizeBase("   "))
        assertEquals("download", Filenames.sanitizeBase("///"))
        assertEquals("download", Filenames.sanitizeBase("..."))
    }

    @Test
    fun `collapses whitespace and truncates`() {
        assertEquals("a b", Filenames.sanitizeBase("a    b"))
        assertEquals(120, Filenames.sanitizeBase("x".repeat(500)).length)
    }

    @Test
    fun `never ends in a dot, which would confuse the extension`() {
        assertTrue(!Filenames.sanitizeBase("name.").endsWith("."))
    }

    @Test
    fun `formats sizes`() {
        assertNull(Filenames.formatBytes(null))
        assertNull(Filenames.formatBytes(0))
        assertEquals("512 B", Filenames.formatBytes(512))
        assertEquals("7.8 MB", Filenames.formatBytes(8_200_000))
    }

    @Test
    fun `formats durations`() {
        assertNull(Filenames.formatDuration(null))
        assertNull(Filenames.formatDuration(0))
        assertEquals("0:34", Filenames.formatDuration(34))
        assertEquals("3:32", Filenames.formatDuration(212))
        assertEquals("1:02:05", Filenames.formatDuration(3725))
    }
}

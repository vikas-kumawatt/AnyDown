package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonLiteTest {

    @Test
    fun `resolves the escapes embedded json actually uses`() {
        assertEquals("https://a/b.mp4", JsonLite.unescape("""https:\/\/a\/b.mp4"""))
        assertEquals("a&b", JsonLite.unescape("""a&b"""))
        assertEquals("""say "hi"""", JsonLite.unescape("""say \"hi\""""))
        assertEquals("a\nb", JsonLite.unescape("""a\nb"""))
        assertEquals("back\\slash", JsonLite.unescape("""back\\slash"""))
        // Untouched when there's nothing to do.
        assertEquals("plain", JsonLite.unescape("plain"))
        // A malformed trailing escape must not crash.
        assertEquals("""x\""", JsonLite.unescape("""x\"""))
    }

    @Test
    fun `reads string fields`() {
        val json = """{"a":"one","b" : "two","c":123}"""
        assertEquals("one", JsonLite.string(json, "a"))
        assertEquals("two", JsonLite.string(json, "b"))
        assertNull(JsonLite.string(json, "c"))
        assertNull(JsonLite.string(json, "missing"))
    }

    /** A `}` or `"key":` inside a string value must not be mistaken for syntax. */
    @Test
    fun `is not fooled by json-like text inside strings`() {
        val json = """{"caption":"look: {\"url\":\"fake\"} ","url":"real"}"""
        assertEquals("real", JsonLite.string(json, "url"))
    }

    @Test
    fun `reads numbers, quoted or not`() {
        assertEquals(48_211_234L, JsonLite.long("""{"size":"48211234"}""", "size"))
        assertEquals(42L, JsonLite.long("""{"size":42}""", "size"))
        assertEquals(-1L, JsonLite.long("""{"errno":-1}""", "errno"))
        assertEquals(720, JsonLite.int("""{"height":720}""", "height"))
        assertNull(JsonLite.long("""{"size":null}""", "size"))
    }

    @Test
    fun `extracts objects from a named array`() {
        val json = """{"list":[{"id":1},{"id":2},{"id":3}]}"""
        val objects = JsonLite.objectsIn(json, "list")
        assertEquals(3, objects.size)
        assertEquals(listOf(1, 2, 3), objects.map { JsonLite.int(it, "id") })
    }

    @Test
    fun `handles nested objects and arrays inside entries`() {
        val json = """
            {"list":[
              {"id":1,"thumbs":{"url1":"a","url3":"b"},"tags":[1,2,3]},
              {"id":2,"thumbs":{"url1":"c"}}
            ]}
        """.trimIndent()
        val objects = JsonLite.objectsIn(json, "list")
        assertEquals(2, objects.size)
        assertEquals("b", JsonLite.string(objects[0], "url3"))
        assertEquals(2, JsonLite.int(objects[1], "id"))
    }

    @Test
    fun `collects every occurrence of a repeated array`() {
        val json = """
            {"a":{"video_versions":[{"url":"x"}]},
             "b":{"video_versions":[{"url":"y"},{"url":"z"}]}}
        """.trimIndent()
        val objects = JsonLite.allObjectsIn(json, "video_versions")
        assertEquals(listOf("x", "y", "z"), objects.map { JsonLite.string(it, "url") })
    }

    @Test
    fun `returns nothing for absent or malformed arrays`() {
        assertEquals(emptyList<String>(), JsonLite.objectsIn("""{"a":1}""", "list"))
        assertEquals(emptyList<String>(), JsonLite.objectsIn("""{"list":"notanarray"}""", "list"))
        assertEquals(emptyList<String>(), JsonLite.objectsIn("""{"list":[""", "list"))
    }

    @Test
    fun `splits top-level objects only`() {
        val body = """{"a":{"b":1}},{"c":[{"d":2}]}"""
        val parts = JsonLite.splitObjects(body)
        assertEquals(2, parts.size)
        assertEquals("""{"a":{"b":1}}""", parts[0])
    }
}

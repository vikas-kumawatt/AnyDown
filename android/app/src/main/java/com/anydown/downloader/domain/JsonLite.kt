package com.anydown.downloader.domain

/**
 * Just enough JSON handling to pull fields out of embedded blobs.
 *
 * Deliberately not `org.json`: that class is a stub in Android's JVM unit-test
 * classpath, so every method returns a default and the parsers become
 * untestable. These helpers are pure Kotlin, so the resolvers they support can
 * be tested properly in CI — which matters more here than elsewhere, because
 * scraped markup is the part most likely to be wrong.
 *
 * Not a general JSON parser and not trying to be. It reads specific fields out
 * of documents whose shape is known.
 */
object JsonLite {

    /** Resolve JSON/JS string escapes: `\/`, `\uXXXX`, `\"`, `\\` and friends. */
    fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i == text.lastIndex) {
                out.append(c); i++; continue
            }
            when (val next = text[i + 1]) {
                '/' -> { out.append('/'); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> {
                    val hex = text.substring(i + 2, minOf(i + 6, text.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        out.append(code.toChar()); i += 6
                    } else {
                        out.append(next); i += 2
                    }
                }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /** First `"key":"value"` in [json], unescaped. Null when absent. */
    fun string(json: String, key: String): String? {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return null
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') { from = at + marker.length; continue }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') { from = at + marker.length; continue }
            val end = endOfString(json, i)
            if (end < 0) return null
            return unescape(json.substring(i + 1, end))
        }
    }

    /** First numeric `"key":123` in [json]. Also accepts `"key":"123"`. */
    fun long(json: String, key: String): Long? {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return null
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') { from = at + marker.length; continue }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == '"') {
                val end = endOfString(json, i)
                if (end < 0) return null
                return json.substring(i + 1, end).trim().toLongOrNull()
            }
            val start = i
            while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
            return json.substring(start, i).toLongOrNull()
        }
    }

    fun int(json: String, key: String): Int? = long(json, key)?.toInt()

    /**
     * The objects inside the array at `"key":[ … ]`, each as raw JSON text.
     *
     * Nesting- and string-aware, so an object containing its own arrays or a
     * `}` inside a string value doesn't split it in the wrong place.
     */
    fun objectsIn(json: String, key: String): List<String> {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return emptyList()
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') { from = at + marker.length; continue }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '[') { from = at + marker.length; continue }
            val close = matchingBracket(json, i)
            if (close < 0) return emptyList()
            return splitObjects(json.substring(i + 1, close))
        }
    }

    /** Every occurrence of the array at `"key":[…]`, flattened. */
    fun allObjectsIn(json: String, key: String): List<String> {
        val result = mutableListOf<String>()
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return result
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == '[') {
                    val close = matchingBracket(json, i)
                    if (close > 0) {
                        result += splitObjects(json.substring(i + 1, close))
                        from = close
                        continue
                    }
                }
            }
            from = at + marker.length
        }
    }

    /** Top-level `{…}` objects within an array body. */
    fun splitObjects(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inString = false
                i++; continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += body.substring(start, i + 1)
                        start = -1
                    }
                }
            }
            i++
        }
        return out
    }

    /** Index of the closing bracket matching the `[` or `{` at [open]. */
    private fun matchingBracket(text: String, open: Int): Int {
        val opener = text[open]
        val closer = if (opener == '[') ']' else '}'
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inString = false
                i++; continue
            }
            when (c) {
                '"' -> inString = true
                opener -> depth++
                closer -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Index of the quote closing the string that opens at [open]. */
    private fun endOfString(text: String, open: Int): Int {
        var i = open + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }
}

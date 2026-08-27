package com.anydown.downloader.domain

/**
 * Builds the base filename we hand to yt-dlp's `-o` template.
 *
 * Ported from `build_filename` in `backend/app/streaming.py`. We generate the
 * base ourselves rather than letting yt-dlp interpolate `%(title)s` so the
 * resulting path is predictable — the download code then finds the finished
 * file by globbing `base.*` instead of parsing yt-dlp's stdout for it.
 */
object Filenames {

    private const val MAX_BASE_LENGTH = 120

    // Keep it conservative: this has to be safe on internal ext4 storage and on
    // a FAT/exFAT SD card, so ':' '*' '?' '"' '<' '>' '|' '/' '\' are all out.
    private val UNSAFE = Regex("[^A-Za-z0-9 \\-._()\\[\\]&'!,]+")
    private val WHITESPACE = Regex("\\s+")

    fun sanitizeBase(title: String?): String {
        var base = (title ?: "").trim()
        base = UNSAFE.replace(base, "_")
        base = WHITESPACE.replace(base, " ").trim()
        base = base.trim('.', '_', ' ')
        if (base.isEmpty()) base = "download"
        if (base.length > MAX_BASE_LENGTH) base = base.substring(0, MAX_BASE_LENGTH).trim()
        // A trailing dot would make the extension ambiguous.
        return base.trimEnd('.', ' ').ifEmpty { "download" }
    }

    /** Human-readable size, or null when yt-dlp didn't report one. */
    fun formatBytes(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (value < 10 && unit > 0) {
            String.format("%.1f %s", value, units[unit])
        } else {
            "${value.toInt()} ${units[unit]}"
        }
    }

    /** mm:ss, or h:mm:ss past an hour. Null when duration is unknown. */
    fun formatDuration(seconds: Int?): String? {
        if (seconds == null || seconds <= 0) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }
}

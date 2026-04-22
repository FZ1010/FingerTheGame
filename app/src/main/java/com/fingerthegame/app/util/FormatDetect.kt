package com.fingerthegame.app.util

enum class Format { TEXT, JSON, XML, NRBF, SQLITE, BINARY }

object FormatDetect {

    private val SQLITE_MAGIC = "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1)
    private val NRBF_MAGIC = byteArrayOf(
        0x00, 0x01, 0x00, 0x00, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )

    fun detect(bytes: ByteArray): Format {
        if (bytes.size >= SQLITE_MAGIC.size &&
            bytes.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) {
            return Format.SQLITE
        }
        if (bytes.size >= NRBF_MAGIC.size &&
            bytes.copyOfRange(0, NRBF_MAGIC.size).contentEquals(NRBF_MAGIC)) {
            return Format.NRBF
        }
        if (looksLikeText(bytes)) {
            val trimmed = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrNull()
            if (trimmed != null) {
                if (trimmed.startsWith("<?xml") ||
                    (trimmed.startsWith("<") && trimmed.endsWith(">"))) return Format.XML
                if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("[") && trimmed.endsWith("]"))) return Format.JSON
            }
            return Format.TEXT
        }
        return Format.BINARY
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.take(minOf(bytes.size, 4096))
        var printable = 0
        for (b in sample) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0x80) printable++
        }
        return printable.toDouble() / sample.size > 0.95
    }
}

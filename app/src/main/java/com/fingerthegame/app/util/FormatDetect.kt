package com.fingerthegame.app.util

import android.util.Base64

enum class Format { TEXT, JSON, XML, NRBF, SQLITE, PROTOBUF, BINARY }

/**
 * Result of unwrapping a save's bytes through outer encodings (currently
 * just base64). [effective] is what the editors should parse; [rewrap]
 * re-applies the outer encodings on save so writes round-trip cleanly.
 */
data class Unwrapped(
    val effective: ByteArray,
    val format: Format,
    val wrappedAsBase64: Boolean = false,
) {
    fun rewrap(modified: ByteArray): ByteArray {
        if (!wrappedAsBase64) return modified
        // NO_WRAP: no line breaks; matches single-blob storage common in saves.
        return Base64.encodeToString(modified, Base64.NO_WRAP).toByteArray(Charsets.US_ASCII)
    }
}

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
        // Last resort before declaring BINARY: protobuf has no magic bytes,
        // but a successful end-to-end parse is a very strong signal — small
        // false-positive rate vs raw binary.
        if (looksLikeProtobuf(bytes)) return Format.PROTOBUF
        return Format.BINARY
    }

    private fun looksLikeProtobuf(bytes: ByteArray): Boolean {
        // Skip tiny / huge files — parsing huge binaries to test one detector
        // is expensive; if a multi-MB file isn't NRBF/SQLite/text it's almost
        // never protobuf in practice.
        if (bytes.size < 16 || bytes.size > 8 * 1024 * 1024) return false
        val doc = ProtobufDocument(bytes)
        if (!doc.parsedToEnd) return false
        if (doc.fields.isEmpty()) return false
        // Most fields should have small, sane field numbers. Random binary
        // data tends to produce wildly variable numbers.
        val sane = doc.fields.count { it.fieldNumber in 1..2048 }
        return sane.toDouble() / doc.fields.size >= 0.95
    }

    /**
     * Detect base64-wrapped content (a popular "obfuscation" trick — game
     * stores its real save format inside `Convert.ToBase64String(bytes)`).
     * If the file's content is plausibly base64 AND the decoded bytes
     * detect as something more interesting than BINARY, return the inner
     * format with [Unwrapped.wrappedAsBase64]=true so the editor can
     * re-encode on save.
     */
    fun unwrap(bytes: ByteArray): Unwrapped {
        val raw = Unwrapped(bytes, detect(bytes), wrappedAsBase64 = false)
        if (raw.format != Format.TEXT && raw.format != Format.BINARY) return raw
        if (!looksLikeBase64(bytes)) return raw
        val decoded = runCatching { Base64.decode(bytes, Base64.NO_WRAP) }.getOrNull()
            ?: return raw
        if (decoded.size < 16) return raw
        val innerFormat = detect(decoded)
        // Only commit to the unwrap if the inner is a recognised binary format
        // or structured text — otherwise it was probably just text that happened
        // to look base64-ish.
        if (innerFormat == Format.BINARY || innerFormat == Format.TEXT) return raw
        return Unwrapped(decoded, innerFormat, wrappedAsBase64 = true)
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

    /** Strict-ish base64 sniff: minimum length, padding multiple of 4 if any
     *  '=' characters exist, only the base64 alphabet plus optional trailing
     *  whitespace. Allows both standard and url-safe alphabets. */
    private fun looksLikeBase64(bytes: ByteArray): Boolean {
        if (bytes.size < 64) return false
        // Trim trailing whitespace.
        var end = bytes.size
        while (end > 0) {
            val c = bytes[end - 1].toInt() and 0xFF
            if (c != 9 && c != 10 && c != 13 && c != 32) break
            end--
        }
        val core = end
        if (core < 64) return false

        var pad = 0
        var sawNonAlpha = false
        for (i in 0 until core) {
            val c = bytes[i].toInt() and 0xFF
            val ok = (c in 0x41..0x5A) ||           // A-Z
                     (c in 0x61..0x7A) ||           // a-z
                     (c in 0x30..0x39) ||           // 0-9
                     c == 0x2B || c == 0x2F ||      // + /
                     c == 0x2D || c == 0x5F ||      // - _ (url-safe)
                     c == 0x3D                      // =
            if (!ok) {
                // Allow internal newlines for line-broken base64.
                if (c == 9 || c == 10 || c == 13 || c == 32) continue
                sawNonAlpha = true
                break
            }
            if (c == 0x3D) pad++
        }
        if (sawNonAlpha) return false
        // If there's any padding it must align the total to a multiple of 4.
        if (pad > 0 && (core - pad) % 4 != (4 - pad) % 4) return false
        return true
    }
}

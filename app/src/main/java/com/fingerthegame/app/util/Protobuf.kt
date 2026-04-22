package com.fingerthegame.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Schema-less Protobuf wire-format walker. Lets us inspect and edit
 * primitive fields in any `.pb` save without requiring the original
 * `.proto` definitions — handy because most mobile games ship neither
 * the proto file nor the field names.
 *
 *  - Varint (wire 0): int/uint/sint/bool/enum
 *  - Fixed32 (wire 5): fixed32/sfixed32/float
 *  - Fixed64 (wire 1): fixed64/sfixed64/double
 *  - Length-delimited (wire 2): string/bytes/embedded message/packed-repeated
 *
 * In-place edits are safe for fixed-width (always) and varints (when the
 * new value re-encodes to the same byte length). Length-delimited blobs
 * can change byte count — the editor disallows that.
 */
enum class ProtoWireType(val code: Int) {
    VARINT(0), FIXED64(1), LENGTH_DELIMITED(2), FIXED32(5);

    companion object {
        fun ofCode(c: Int): ProtoWireType? = values().firstOrNull { it.code == c }
    }
}

/** A single decoded field from the wire stream. Retains both the
 *  numeric/byte payload and the byte offsets needed to patch it. */
data class ProtoField(
    val id: Long,
    val fieldNumber: Int,
    val wireType: ProtoWireType,
    /** Offset of the tag varint (start of this field's record). */
    val tagOffset: Int,
    /** Offset of the payload bytes (after the tag, after the length-prefix
     *  varint for LD types). */
    val payloadOffset: Int,
    /** Length in bytes of the payload as it sits in the buffer. */
    val payloadLength: Int,
    /** Decoded value: Long for VARINT/FIXED64, Int for FIXED32, ByteArray
     *  for LENGTH_DELIMITED. */
    val value: Any,
)

class ProtobufDocument(val bytes: ByteArray) {
    val fields: List<ProtoField>
    val parseError: String?
    /** True when the parse consumed the entire buffer cleanly — a strong
     *  signal that the file really is protobuf rather than something else
     *  the heuristic happened to match. */
    val parsedToEnd: Boolean

    init {
        val w = ProtobufWalker(bytes)
        w.run()
        fields = w.fields
        parseError = w.error
        parsedToEnd = w.error == null && w.pos == bytes.size
    }

    fun applyPatches(patches: Map<Int, Any>): ByteArray {
        val out = bytes.copyOf()
        val byOffset = fields.associateBy { it.tagOffset }
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for ((off, raw) in patches) {
            val f = byOffset[off] ?: continue
            try { writeValue(bb, out, f, raw) } catch (_: Throwable) { /* skip bad input */ }
        }
        return out
    }

    private fun writeValue(bb: ByteBuffer, out: ByteArray, f: ProtoField, raw: Any) {
        when (f.wireType) {
            ProtoWireType.FIXED32 -> {
                val v = raw.toLongOrZero().toInt()
                bb.putInt(f.payloadOffset, v)
            }
            ProtoWireType.FIXED64 -> {
                val v = raw.toLongOrZero()
                bb.putLong(f.payloadOffset, v)
            }
            ProtoWireType.VARINT -> {
                val newVal = raw.toLongOrZero()
                val originalLen = f.payloadLength
                val encoded = encodeVarint(newVal)
                require(encoded.size == originalLen) {
                    "varint $newVal would be ${encoded.size} bytes but allocation is $originalLen"
                }
                for (i in encoded.indices) out[f.payloadOffset + i] = encoded[i]
            }
            ProtoWireType.LENGTH_DELIMITED -> {
                val payload = when (raw) {
                    is ByteArray -> raw
                    is String -> raw.toByteArray(Charsets.UTF_8)
                    else -> raw.toString().toByteArray(Charsets.UTF_8)
                }
                require(payload.size == f.payloadLength) {
                    "length-delimited payload changed size (${payload.size} vs ${f.payloadLength})"
                }
                for (i in payload.indices) out[f.payloadOffset + i] = payload[i]
            }
        }
    }

    private fun Any.toLongOrZero(): Long = when (this) {
        is Number -> this.toLong()
        is Boolean -> if (this) 1L else 0L
        is String -> this.trim().toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun encodeVarint(value: Long): ByteArray {
        val out = ArrayList<Byte>(10)
        var v = value
        while (true) {
            val b = (v and 0x7F).toByte()
            v = v ushr 7
            if (v == 0L) {
                out.add(b)
                break
            }
            out.add((b.toInt() or 0x80).toByte())
        }
        return out.toByteArray()
    }
}

private class ProtobufWalker(val buf: ByteArray) {
    var pos = 0
    val fields = mutableListOf<ProtoField>()
    var nextId = 0L
    var error: String? = null

    fun run() {
        try {
            while (pos < buf.size) {
                val tagOff = pos
                val tag = readVarint()
                val fieldNum = (tag ushr 3).toInt()
                val wt = ProtoWireType.ofCode((tag and 7).toInt())
                    ?: error("unknown wire type at 0x${tagOff.toString(16)}")
                if (fieldNum <= 0 || fieldNum > 536_870_911)        // proto's stated max
                    error("field number $fieldNum out of range at 0x${tagOff.toString(16)}")

                val payloadOff = pos
                val payload: Any
                val payloadLen: Int
                when (wt) {
                    ProtoWireType.VARINT -> {
                        val v = readVarint()
                        payload = v
                        payloadLen = pos - payloadOff
                    }
                    ProtoWireType.FIXED64 -> {
                        if (pos + 8 > buf.size) error("fixed64 truncated at 0x${pos.toString(16)}")
                        payload = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
                        pos += 8; payloadLen = 8
                    }
                    ProtoWireType.FIXED32 -> {
                        if (pos + 4 > buf.size) error("fixed32 truncated at 0x${pos.toString(16)}")
                        payload = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        pos += 4; payloadLen = 4
                    }
                    ProtoWireType.LENGTH_DELIMITED -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > buf.size) error("LD length $len overflows")
                        // Re-bind offsets to point past the length-prefix varint.
                        val dataStart = pos
                        val bytes = buf.copyOfRange(dataStart, dataStart + len)
                        pos += len
                        fields.add(ProtoField(
                            id = nextId++,
                            fieldNumber = fieldNum,
                            wireType = wt,
                            tagOffset = tagOff,
                            payloadOffset = dataStart,
                            payloadLength = len,
                            value = bytes,
                        ))
                        continue
                    }
                }
                fields.add(ProtoField(
                    id = nextId++,
                    fieldNumber = fieldNum,
                    wireType = wt,
                    tagOffset = tagOff,
                    payloadOffset = payloadOff,
                    payloadLength = payloadLen,
                    value = payload,
                ))
            }
        } catch (e: Throwable) {
            error = e.message ?: "parse failed at 0x${pos.toString(16)}"
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= buf.size) error("varint truncated at 0x${pos.toString(16)}")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) error("varint > 10 bytes at 0x${pos.toString(16)}")
        }
    }
}

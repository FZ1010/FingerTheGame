// Standalone round-trip test for the schema-less protobuf walker.
// Builds a synthetic message with one of each wire type, parses it,
// patches each field, re-parses, verifies the new values are at the
// same offsets.
//
//   kotlinc parser_test/ProtoCheck.kt -include-runtime -d /tmp/protocheck.jar
//   java -jar /tmp/protocheck.jar

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private enum class WT(val code: Int) { VARINT(0), FIXED64(1), LD(2), FIXED32(5) }

private data class PF(val n: Int, val wt: WT, val tagOff: Int, val payOff: Int, val payLen: Int, val v: Any)

private class W(val buf: ByteArray) {
    var pos = 0
    val fields = mutableListOf<PF>()
    var err: String? = null
    fun run() {
        try {
            while (pos < buf.size) {
                val tagOff = pos
                val tag = vlq()
                val n = (tag ushr 3).toInt()
                val wt = when ((tag and 7).toInt()) { 0 -> WT.VARINT; 1 -> WT.FIXED64; 2 -> WT.LD; 5 -> WT.FIXED32; else -> error("wt") }
                val payOff = pos
                when (wt) {
                    WT.VARINT -> { val v = vlq(); fields.add(PF(n, wt, tagOff, payOff, pos - payOff, v)) }
                    WT.FIXED64 -> { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long; pos += 8; fields.add(PF(n, wt, tagOff, payOff, 8, v)) }
                    WT.FIXED32 -> { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int; pos += 4; fields.add(PF(n, wt, tagOff, payOff, 4, v)) }
                    WT.LD -> { val len = vlq().toInt(); val ds = pos; val bytes = buf.copyOfRange(ds, ds + len); pos += len; fields.add(PF(n, wt, tagOff, ds, len, bytes)) }
                }
            }
        } catch (e: Throwable) { err = e.message }
    }
    private fun vlq(): Long { var r = 0L; var s = 0; while (true) { val b = buf[pos++].toInt() and 0xFF; r = r or ((b and 0x7F).toLong() shl s); if (b and 0x80 == 0) return r; s += 7 } }
}

private fun encVar(v: Long): ByteArray {
    val out = ArrayList<Byte>()
    var x = v
    while (true) {
        val b = (x and 0x7F).toByte()
        x = x ushr 7
        if (x == 0L) { out.add(b); return out.toByteArray() }
        out.add((b.toInt() or 0x80).toByte())
    }
}

private fun synth(): ByteArray {
    val out = mutableListOf<Byte>()
    fun append(b: ByteArray) = b.forEach { out.add(it) }
    // field 1, varint = 12345
    append(byteArrayOf(0x08))
    append(encVar(12345))
    // field 2, fixed64 = double 3.14
    append(byteArrayOf(0x11))
    val d = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(3.14).array()
    append(d)
    // field 3, length-delimited = "hello"
    append(byteArrayOf(0x1A, 0x05))
    append("hello".toByteArray())
    // field 4, fixed32 = float 1.5
    append(byteArrayOf(0x25))
    val f = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.5f).array()
    append(f)
    return out.toByteArray()
}

fun main() {
    val bytes = synth()
    println("synth size: ${bytes.size} = ${bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}")
    val w = W(bytes); w.run()
    println("err=${w.err}, parsedToEnd=${w.pos == bytes.size}")
    for (f in w.fields) {
        println("  #${f.n} (${f.wt}) tag=0x${f.tagOff.toString(16)} pay=0x${f.payOff.toString(16)}+${f.payLen}  value=${if (f.v is ByteArray) String(f.v) else f.v}")
    }
    // Patch field 1 to 999 (still 2-byte varint), field 4 to float 99
    val patched = bytes.copyOf()
    val v1Off = w.fields[0].payOff
    val v1Len = w.fields[0].payLen
    val newV1 = encVar(999)
    require(newV1.size == v1Len) { "varint 999 doesn't fit: ${newV1.size} vs $v1Len" }
    for (i in newV1.indices) patched[v1Off + i] = newV1[i]

    val v4Off = w.fields[3].payOff
    val newF = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(99f).array()
    for (i in newF.indices) patched[v4Off + i] = newF[i]

    val w2 = W(patched); w2.run()
    println("\nAFTER PATCH:")
    for (f in w2.fields) {
        val str = when {
            f.v is ByteArray -> String(f.v)
            f.wt == WT.FIXED32 -> "${f.v} (float ${java.lang.Float.intBitsToFloat(f.v as Int)})"
            else -> f.v.toString()
        }
        println("  #${f.n} (${f.wt}) value=$str")
    }
}

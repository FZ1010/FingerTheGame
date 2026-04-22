// Standalone JVM test for the NRBF parser. Copies a trimmed version of
// com.fingerthegame.app.util.Nrbf.kt inline so we can run with just kotlinc,
// no Android dependencies.
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class NrbfType {
    BOOL, BYTE, SBYTE, I16, U16, I32, U32, I64, U64, F32, F64, STRING;
}

data class NrbfField(
    val id: Long, val className: String, val memberName: String,
    val offset: Int, val type: NrbfType, val originalValue: Any,
)

data class ClassDefn(
    val name: String, val memberNames: List<String>,
    val binaryTypes: IntArray, val addlPrim: IntArray,
)

class Walker(val buf: ByteArray) {
    var pos = 0
    val classes = HashMap<Long, ClassDefn>()
    val fields = ArrayList<NrbfField>()
    val classCounts = HashMap<String, Int>()
    var nextId = 0L

    fun run() {
        while (pos < buf.size) {
            val rt = u8()
            if (rt == 11) break
            handleRecord(rt)
        }
    }

    private fun handleRecord(rt: Int) {
        when (rt) {
            0 -> { i32(); i32(); i32(); i32() }
            12 -> { u32(); lpString() }
            5 -> readClass(false)
            4 -> readClass(true)
            6 -> { u32(); lpString() }
            1 -> readClassWithId()
            7 -> readBinaryArray()
            15 -> readArraySinglePrim()
            16 -> readArraySingleObject()
            17 -> readArraySingleString()
            8 -> { val pc = u8(); readInlinePrimitive("Untyped", "value", pc) }
            9 -> u32()
            10 -> {}
            13 -> u8()
            14 -> u32()
            else -> error("unknown rt $rt @0x${(pos - 1).toString(16)}")
        }
    }

    private fun readClass(systemLib: Boolean) {
        val objId = u32()
        val name = lpString()
        val mcount = i32()
        val mnames = (0 until mcount).map { lpString() }
        val btypes = IntArray(mcount) { u8() }
        val addl = IntArray(mcount) { -1 }
        for (i in 0 until mcount) consumeAddlInfo(btypes[i], addl, i)
        if (!systemLib) u32()
        val cls = ClassDefn(name, mnames, btypes, addl)
        classes[objId] = cls
        readInstance(cls)
    }

    private fun readClassWithId() {
        u32(); val meta = u32()
        val cls = classes[meta] ?: error("unknown class $meta")
        readInstance(cls)
    }

    private fun consumeAddlInfo(bt: Int, addl: IntArray, i: Int) {
        when (bt) {
            0 -> addl[i] = u8()
            1, 2, 5, 6 -> {}
            3 -> lpString()
            4 -> { lpString(); u32() }
            7 -> addl[i] = u8()
            else -> error("bt $bt")
        }
    }

    private fun readInstance(cls: ClassDefn) {
        classCounts.merge(cls.name, 1) { a, _ -> a + 1 }
        for (i in cls.memberNames.indices) {
            readMemberValue(cls.name, cls.memberNames[i], cls.binaryTypes[i], cls.addlPrim[i])
        }
    }

    private fun readMemberValue(cls: String, mname: String, bt: Int, addlPrim: Int) {
        when (bt) {
            0 -> readInlinePrimitive(cls, mname, addlPrim)
            1, 2, 3, 4, 5, 6, 7 -> {
                val rt = u8()
                if (rt == 11) return
                handleRecord(rt)
            }
            else -> error("bad bt $bt")
        }
    }

    private fun readInlinePrimitive(cls: String, mname: String, pc: Int) {
        val off = pos
        when (pc) {
            1 -> { val v = u8() != 0; add(cls, mname, off, NrbfType.BOOL, v) }
            2 -> { val v = u8(); add(cls, mname, off, NrbfType.BYTE, v.toLong()) }
            10 -> { val v = buf[pos].toLong(); pos++; add(cls, mname, off, NrbfType.SBYTE, v) }
            7 -> { val v = i16(); add(cls, mname, off, NrbfType.I16, v.toLong()) }
            14 -> { val v = u16(); add(cls, mname, off, NrbfType.U16, v.toLong()) }
            8 -> { val v = i32(); add(cls, mname, off, NrbfType.I32, v.toLong()) }
            15 -> { val v = u32(); add(cls, mname, off, NrbfType.U32, v) }
            9 -> { val v = i64(); add(cls, mname, off, NrbfType.I64, v) }
            16 -> { val v = i64(); add(cls, mname, off, NrbfType.U64, v) }
            11 -> { val v = f32(); add(cls, mname, off, NrbfType.F32, v.toDouble()) }
            6 -> { val v = f64(); add(cls, mname, off, NrbfType.F64, v) }
            3 -> pos += 1
            5 -> pos += 16
            12 -> pos += 8
            13 -> pos += 8
            18 -> { val s = lpString(); add(cls, mname, off, NrbfType.STRING, s) }
            else -> error("pc $pc")
        }
    }

    private fun readBinaryArray() {
        u32(); val arrayType = u8(); val rank = u32().toInt()
        val lengths = IntArray(rank) { u32().toInt() }
        if (arrayType in setOf(3, 4, 5)) for (i in 0 until rank) u32()
        val bt = u8()
        val tempAddl = IntArray(1) { -1 }
        consumeAddlInfo(bt, tempAddl, 0)
        var total = 1L
        for (l in lengths) total *= l
        for (n in 0 until total) readMemberValue("Array", "item", bt, tempAddl[0])
    }

    private fun readArraySinglePrim() {
        u32(); val length = u32().toInt(); val pc = u8()
        val w = when (pc) { 1, 2, 10 -> 1; 3, 7, 14 -> 2; 8, 15, 11 -> 4; 9, 16, 6 -> 8; 12, 13 -> 8; 5 -> 16; else -> error("pw $pc") }
        pos += length * w
    }

    private fun readArraySingleObject() {
        u32(); val length = u32().toInt()
        for (i in 0 until length) { val rt = u8(); if (rt == 11) return; handleRecord(rt) }
    }

    private fun readArraySingleString() {
        u32(); val length = u32().toInt()
        for (i in 0 until length) { val rt = u8(); if (rt == 11) return; handleRecord(rt) }
    }

    private fun add(cls: String, mn: String, off: Int, t: NrbfType, v: Any) {
        fields.add(NrbfField(nextId++, cls, mn, off, t, v))
    }

    private fun u8(): Int = buf[pos++].toInt() and 0xFF
    private fun i16(): Int { val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt(); pos += 2; return v }
    private fun u16(): Int { val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF; pos += 2; return v }
    private fun i32(): Int { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int; pos += 4; return v }
    private fun u32(): Long { return i32().toLong() and 0xFFFFFFFFL }
    private fun i64(): Long { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long; pos += 8; return v }
    private fun f32(): Float { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float; pos += 4; return v }
    private fun f64(): Double { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double; pos += 8; return v }
    private fun vlq(): Int { var n = 0; var s = 0; while (true) { val b = u8(); n = n or ((b and 0x7F) shl s); if (b and 0x80 == 0) return n; s += 7 } }
    private fun lpString(): String { val n = vlq(); val s = String(buf, pos, n, Charsets.UTF_8); pos += n; return s }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) error("usage: java -jar main.jar <save.bin> [keyword ...]")
    val buf = File(args[0]).readBytes()
    val w = Walker(buf)
    val err = runCatching { w.run() }.exceptionOrNull()
    println("parsed=${w.fields.size}, classes=${w.classCounts.size}, error=$err")
    for (k in args.drop(1)) {
        val match = w.fields.firstOrNull { it.memberName.contains(k) }
        if (match != null) {
            println("  $k -> ${match.originalValue} @0x${match.offset.toString(16)} (${match.type}) in ${match.className}")
        } else println("  $k NOT FOUND")
    }
}

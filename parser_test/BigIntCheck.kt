// Quick standalone sanity check for the BigInteger collapsing logic. Mirrors
// the Android-side parser in app/src/main/java/com/fingerthegame/app/util/Nrbf.kt
// well enough to verify that wrapped numerics surface with their semantic
// outer-member names rather than `_sign` / `_value` / `ObservedValue`.
//
// Usage:
//   kotlinc parser_test/BigIntCheck.kt -include-runtime -d bigint.jar
//   java -jar bigint.jar path/to/save.bin
//
// What it prints: every collapsed BIGINT field (className, memberName,
// decoded value, layout) and a count of how many "value"-named primitives
// remain after the un-burying pass.

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val GENERIC = setOf(
    "value", "_value", "Value",
    "ObservedValue", "observedValue", "_observedValue",
    "item", "Item", "_item", "current", "Current",
)

private enum class T { BOOL, BYTE, SBYTE, I16, U16, I32, U32, I64, U64, F32, F64, STRING, BIGINT, DECIMAL }

private data class BigLayout(val signOff: Int, val bitsOff: Int, val bitsLen: Int)
private data class DecLayout(val flagsOff: Int, val hiOff: Int, val midOff: Int, val loOff: Int)

private data class Field(
    val id: Long, val cls: String, val name: String, val off: Int,
    val type: T, val value: Any, val meta: Any? = null,
)

private data class CD(
    val name: String, val mnames: List<String>,
    val btypes: IntArray, val addl: IntArray,
)

private data class Edge(val cls: String, val mn: String, val ownerObjId: Long)

private class W(val buf: ByteArray) {
    var pos = 0
    val classes = HashMap<Long, CD>()
    val fields = ArrayList<Field>()
    val ctx = ArrayDeque<Pair<String, String>>()
    val refOwners = HashMap<Long, Edge>()
    var pendingObjId: Long = 0L
    var nextId = 0L

    private fun isGen(n: String) = n in GENERIC
    private fun best(cls: String, name: String): Pair<String, String> {
        if (!isGen(name)) return cls to name
        for (i in ctx.indices.reversed()) {
            val (c, m) = ctx[i]
            if (!isGen(m)) return c to m
        }
        var cursor = pendingObjId
        val seen = HashSet<Long>()
        while (cursor != 0L && seen.add(cursor)) {
            val e = refOwners[cursor] ?: break
            if (!isGen(e.mn)) return e.cls to e.mn
            cursor = e.ownerObjId
        }
        return cls to name
    }

    fun run() {
        while (pos < buf.size) {
            val rt = u8()
            if (rt == 11) break
            handle(rt)
        }
    }

    private fun handle(rt: Int) {
        when (rt) {
            0 -> { i32(); i32(); i32(); i32() }
            12 -> { u32(); lp() }
            5 -> readClass(false)
            4 -> readClass(true)
            6 -> { u32(); lp() }
            1 -> readClassWithId()
            7 -> readBinaryArray()
            15 -> readArrayPrim()
            16 -> readArrayObj()
            17 -> readArrayStr()
            8 -> { val pc = u8(); readPrim("Untyped", "value", pc) }
            9 -> {
                val targetId = u32()
                val owner = ctx.lastOrNull()
                if (owner != null) refOwners[targetId] = Edge(owner.first, owner.second, pendingObjId)
            }
            10 -> {}
            13 -> u8()
            14 -> u32()
            else -> error("rt=$rt @0x${(pos - 1).toString(16)}")
        }
    }

    private fun readClass(systemLib: Boolean) {
        val id = u32()
        val name = lp()
        val mc = i32()
        val mn = (0 until mc).map { lp() }
        val bt = IntArray(mc) { u8() }
        val ad = IntArray(mc) { -1 }
        for (i in 0 until mc) addl(bt[i], ad, i)
        if (!systemLib) u32()
        val cd = CD(name, mn, bt, ad)
        classes[id] = cd
        recordOwnership(id)
        val saved = pendingObjId
        pendingObjId = id
        try { readInstance(cd) } finally { pendingObjId = saved }
    }

    private fun readClassWithId() {
        val newId = u32(); val meta = u32()
        val cd = classes[meta] ?: error("unknown class id $meta")
        recordOwnership(newId)
        val saved = pendingObjId
        pendingObjId = newId
        try { readInstance(cd) } finally { pendingObjId = saved }
    }

    private fun recordOwnership(newId: Long) {
        val owner = ctx.lastOrNull() ?: return
        refOwners.putIfAbsent(newId, Edge(owner.first, owner.second, pendingObjId))
    }

    private fun addl(bt: Int, ad: IntArray, i: Int) {
        when (bt) {
            0 -> ad[i] = u8()
            1, 2, 5, 6 -> {}
            3 -> lp()
            4 -> { lp(); u32() }
            7 -> ad[i] = u8()
            else -> error("bt $bt")
        }
    }

    private fun isBigInt(cd: CD) = cd.mnames.size == 2 &&
            cd.mnames[0] == "_sign" && cd.mnames[1] == "_bits" &&
            cd.btypes[0] == 0 && cd.addl[0] == 8 &&
            cd.btypes[1] == 7 && cd.addl[1] == 15

    private fun isDecimal(cd: CD): Boolean {
        if (cd.name != "System.Decimal") return false
        if (cd.mnames.size != 4) return false
        for (i in 0 until 4) {
            if (cd.btypes[i] != 0 || cd.addl[i] != 8) return false
        }
        return true
    }

    private fun readInstance(cd: CD) {
        if (isBigInt(cd)) { readBigInt(cd); return }
        if (isDecimal(cd)) { readDecimal(cd); return }
        for (i in cd.mnames.indices) readMember(cd.name, cd.mnames[i], cd.btypes[i], cd.addl[i])
    }

    private fun readDecimal(cd: CD) {
        val offs = IntArray(4); val vs = IntArray(4)
        for (i in 0 until 4) { offs[i] = pos; vs[i] = i32() }
        fun idx(vararg cands: String): Int {
            for (c in cands) {
                val i = cd.mnames.indexOfFirst { it.equals(c, true) }
                if (i >= 0) return i
            }
            return -1
        }
        val fi = idx("flags", "_flags"); val hi = idx("hi", "_hi")
        val lo = idx("lo", "_lo"); val mi = idx("mid", "_mid")
        if (fi < 0 || hi < 0 || lo < 0 || mi < 0) {
            for (i in 0 until 4) rec(cd.name, cd.mnames[i], offs[i], T.I32, vs[i].toLong())
            return
        }
        val sign = (vs[fi] ushr 31) and 1
        val scale = (vs[fi] ushr 16) and 0xFF
        val mask = java.math.BigInteger.valueOf(0xFFFFFFFFL)
        val unscaled = java.math.BigInteger.valueOf(vs[hi].toLong() and 0xFFFFFFFFL).shiftLeft(64)
            .or(java.math.BigInteger.valueOf(vs[mi].toLong() and 0xFFFFFFFFL).shiftLeft(32))
            .or(java.math.BigInteger.valueOf(vs[lo].toLong() and 0xFFFFFFFFL))
        val signed = if (sign != 0) unscaled.negate() else unscaled
        val value = java.math.BigDecimal(signed, scale)
        val (eCls, eName) = best(cd.name, "value")
        fields.add(Field(nextId++, eCls, eName, offs[fi], T.DECIMAL, value, DecLayout(offs[fi], offs[hi], offs[mi], offs[lo])))
    }

    private fun readMember(cls: String, mn: String, bt: Int, ap: Int) {
        when (bt) {
            0 -> readPrim(cls, mn, ap)
            1, 2, 3, 4, 5, 6, 7 -> {
                ctx.addLast(cls to mn)
                System.err.println("PUSH $cls.$mn → ctx.size=${ctx.size}")
                try {
                    val rt = u8()
                    if (rt == 11) return
                    handle(rt)
                } finally {
                    ctx.removeLast()
                    System.err.println("POP  $cls.$mn → ctx.size=${ctx.size}")
                }
            }
            else -> error("bad bt $bt for $cls.$mn")
        }
    }

    private fun readBigInt(cd: CD) {
        System.err.println("readBigInt: ctx=$ctx")
        val signOff = pos
        val sign = i32()
        val rt = u8()
        var bitsOff = -1
        var bitsLen = 0
        val v: BigInteger = when (rt) {
            10 -> BigInteger.valueOf(sign.toLong())
            15 -> {
                u32(); bitsLen = u32().toInt(); u8()
                bitsOff = pos
                val mag = decodeLE(buf, pos, bitsLen * 4)
                pos += bitsLen * 4
                if (sign < 0) mag.negate() else mag
            }
            9 -> { u32(); BigInteger.valueOf(sign.toLong()) }
            else -> { runCatching { handle(rt) }; BigInteger.valueOf(sign.toLong()) }
        }
        val (eCls, eName) = best(cd.name, "value")
        fields.add(Field(nextId++, eCls, eName, signOff, T.BIGINT, v, BigLayout(signOff, bitsOff, bitsLen)))
    }

    private fun decodeLE(buf: ByteArray, off: Int, len: Int): BigInteger {
        if (len == 0) return BigInteger.ZERO
        var m = BigInteger.ZERO
        val s = BigInteger.valueOf(256)
        for (i in len - 1 downTo 0) {
            val b = (buf[off + i].toInt() and 0xFF).toLong()
            m = m.multiply(s).add(BigInteger.valueOf(b))
        }
        return m
    }

    private fun readPrim(cls: String, mn: String, pc: Int) {
        if (mn == "value") System.err.println("readPrim($cls, $mn): ctx=$ctx")
        val (eCls, eName) = best(cls, mn)
        val off = pos
        when (pc) {
            1 -> { val v = u8() != 0; rec(eCls, eName, off, T.BOOL, v) }
            2 -> { val v = u8(); rec(eCls, eName, off, T.BYTE, v.toLong()) }
            10 -> { val v = buf[pos].toLong(); pos++; rec(eCls, eName, off, T.SBYTE, v) }
            7 -> { val v = i16(); rec(eCls, eName, off, T.I16, v.toLong()) }
            14 -> { val v = u16(); rec(eCls, eName, off, T.U16, v.toLong()) }
            8 -> { val v = i32(); rec(eCls, eName, off, T.I32, v.toLong()) }
            15 -> { val v = u32(); rec(eCls, eName, off, T.U32, v) }
            9 -> { val v = i64(); rec(eCls, eName, off, T.I64, v) }
            16 -> { val v = i64(); rec(eCls, eName, off, T.U64, v) }
            11 -> { val v = f32(); rec(eCls, eName, off, T.F32, v.toDouble()) }
            6 -> { val v = f64(); rec(eCls, eName, off, T.F64, v) }
            3 -> pos += 1
            5 -> pos += 16
            12 -> pos += 8
            13 -> pos += 8
            18 -> { val s = lp(); rec(eCls, eName, off, T.STRING, s) }
            else -> error("pc $pc")
        }
    }

    private fun readBinaryArray() {
        u32(); val at = u8(); val rk = u32().toInt()
        val ls = IntArray(rk) { u32().toInt() }
        if (at in setOf(3, 4, 5)) for (i in 0 until rk) u32()
        val bt = u8()
        val ta = IntArray(1) { -1 }
        addl(bt, ta, 0)
        var t = 1L; for (l in ls) t *= l
        for (i in 0 until t) readMember("Array", "item", bt, ta[0])
    }

    private fun readArrayPrim() {
        u32(); val len = u32().toInt(); val pc = u8()
        val w = when (pc) { 1, 2, 10 -> 1; 3, 7, 14 -> 2; 8, 15, 11 -> 4; 9, 16, 6 -> 8; 12, 13 -> 8; 5 -> 16; else -> error("pw $pc") }
        pos += len * w
    }
    private fun readArrayObj() { u32(); val l = u32().toInt(); for (i in 0 until l) { val rt = u8(); if (rt == 11) return; handle(rt) } }
    private fun readArrayStr() { u32(); val l = u32().toInt(); for (i in 0 until l) { val rt = u8(); if (rt == 11) return; handle(rt) } }

    private fun rec(cls: String, mn: String, off: Int, t: T, v: Any) {
        fields.add(Field(nextId++, cls, mn, off, t, v))
    }

    private fun u8() = buf[pos++].toInt() and 0xFF
    private fun i16(): Int { val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt(); pos += 2; return v }
    private fun u16() = i16() and 0xFFFF
    private fun i32(): Int { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int; pos += 4; return v }
    private fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    private fun i64(): Long { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long; pos += 8; return v }
    private fun f32(): Float { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float; pos += 4; return v }
    private fun f64(): Double { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double; pos += 8; return v }
    private fun vlq(): Int { var n = 0; var s = 0; while (true) { val b = u8(); n = n or ((b and 0x7F) shl s); if (b and 0x80 == 0) return n; s += 7 } }
    private fun lp(): String { val n = vlq(); val s = String(buf, pos, n, Charsets.UTF_8); pos += n; return s }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) error("usage: java -jar bigint.jar <save.bin>")
    val buf = File(args[0]).readBytes()
    val w = W(buf)
    val err = runCatching { w.run() }.exceptionOrNull()
    println("parsed=${w.fields.size}, error=$err")

    println("\n=== CLASS DEFINITIONS ===")
    for ((id, cd) in w.classes) {
        println("[$id] ${cd.name}")
        for (i in cd.mnames.indices) {
            println("    .${cd.mnames[i]}  bt=${cd.btypes[i]}  ad=${cd.addl[i]}")
        }
    }

    val bigInts = w.fields.filter { it.type == T.BIGINT }
    println("\nBIGINT fields (${bigInts.size}):")
    for (f in bigInts) {
        val layout = f.meta as? BigLayout
        println("  [${f.cls}] ${f.name} = ${f.value}  (signOff=0x${f.off.toString(16)}, bitsLen=${layout?.bitsLen ?: 0})")
    }

    val decs = w.fields.filter { it.type == T.DECIMAL }
    println("\nDECIMAL fields (${decs.size}):")
    for (f in decs.take(20)) {
        println("  [${f.cls}] ${f.name} = ${f.value}  (flagsOff=0x${f.off.toString(16)})")
    }

    val stillGeneric = w.fields.filter { it.name in GENERIC }
    println("\nFields STILL named generically (${stillGeneric.size}) — should be 0 if context is doing its job:")
    for (f in stillGeneric.take(10)) {
        println("  [${f.cls}] ${f.name} = ${f.value}  (${f.type})")
    }
}

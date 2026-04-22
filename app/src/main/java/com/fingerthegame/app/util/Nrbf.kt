package com.fingerthegame.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generic MS-NRBF (.NET BinaryFormatter) walker. Parses ANY NRBF file, records
 * every member primitive value's byte offset so the UI can patch it in place
 * without re-serializing the whole structure.
 *
 * The walker is best-effort: when it encounters an unknown record type it
 * stops gracefully and exposes whatever fields it found up to that point.
 */
enum class NrbfType {
    BOOL, BYTE, SBYTE, I16, U16, I32, U32, I64, U64, F32, F64, STRING;

    val byteWidth: Int get() = when (this) {
        BOOL, BYTE, SBYTE -> 1
        I16, U16 -> 2
        I32, U32, F32 -> 4
        I64, U64, F64 -> 8
        STRING -> -1
    }
}

data class NrbfField(
    val id: Long,
    val className: String,
    val memberName: String,
    val offset: Int,
    val type: NrbfType,
    val originalValue: Any,    // Long / Double / Boolean / String
) {
    /** The "<Name>k__BackingField" wrapper makes Unity backing fields ugly; clean it. */
    val displayName: String get() {
        val m = memberName
        return when {
            m.startsWith("<") -> m.removePrefix("<").substringBefore(">").ifEmpty { m }
            else -> m
        }
    }
}

class NrbfDocument(val bytes: ByteArray) {
    val fields: List<NrbfField>
    val classCounts: Map<String, Int>
    val parseError: String?

    init {
        val p = NrbfWalker(bytes)
        val err = runCatching { p.run() }.exceptionOrNull()?.message
        fields = p.fields
        classCounts = p.classCounts.toMap()
        parseError = err
    }

    /** Returns a copy of [bytes] with each (offset -> new value) applied. */
    fun applyPatches(patches: Map<Int, Any>): ByteArray {
        val out = bytes.copyOf()
        val byOffset = fields.associateBy { it.offset }
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for ((off, raw) in patches) {
            val f = byOffset[off] ?: continue
            try {
                writeValue(bb, off, f.type, raw)
            } catch (_: Throwable) { /* skip bad input */ }
        }
        return out
    }

    private fun writeValue(bb: ByteBuffer, off: Int, type: NrbfType, raw: Any) {
        when (type) {
            NrbfType.BOOL -> bb.put(off, if (raw.asBoolean()) 1 else 0)
            NrbfType.BYTE -> bb.put(off, (raw.asLong() and 0xFF).toByte())
            NrbfType.SBYTE -> bb.put(off, raw.asLong().toByte())
            NrbfType.I16 -> bb.putShort(off, raw.asLong().toShort())
            NrbfType.U16 -> bb.putShort(off, (raw.asLong() and 0xFFFF).toShort())
            NrbfType.I32 -> bb.putInt(off, raw.asLong().toInt())
            NrbfType.U32 -> bb.putInt(off, (raw.asLong() and 0xFFFFFFFFL).toInt())
            NrbfType.I64 -> bb.putLong(off, raw.asLong())
            NrbfType.U64 -> bb.putLong(off, raw.asLong())
            NrbfType.F32 -> bb.putFloat(off, raw.asDouble().toFloat())
            NrbfType.F64 -> bb.putDouble(off, raw.asDouble())
            NrbfType.STRING -> { /* string patching requires re-layout; not supported here */ }
        }
    }

    private fun Any.asLong(): Long = when (this) {
        is Number -> this.toLong()
        is Boolean -> if (this) 1 else 0
        is String -> this.toLongOrNull() ?: this.toDouble().toLong()
        else -> 0L
    }
    private fun Any.asDouble(): Double = when (this) {
        is Number -> this.toDouble()
        is Boolean -> if (this) 1.0 else 0.0
        is String -> this.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    private fun Any.asBoolean(): Boolean = when (this) {
        is Boolean -> this
        is Number -> this.toLong() != 0L
        is String -> this.equals("true", ignoreCase = true) || this == "1"
        else -> false
    }
}

private data class ClassDefn(
    val name: String,
    val memberNames: List<String>,
    val binaryTypes: IntArray,
    val addlPrim: IntArray,    // primitive type code or -1
)

private class NrbfWalker(val buf: ByteArray) {
    var pos = 0
    val classes = mutableMapOf<Long, ClassDefn>()
    val fields = mutableListOf<NrbfField>()
    val classCounts = mutableMapOf<String, Int>()
    var nextId = 0L

    fun run() {
        while (pos < buf.size) {
            val rt = u8()
            if (rt == 11) break          // MessageEnd
            try { handleRecord(rt) }
            catch (e: Exception) { throw IllegalStateException("@0x${(pos-1).toString(16)} rt=$rt: ${e.message}", e) }
        }
    }

    private fun handleRecord(rt: Int) {
        when (rt) {
            0 -> { i32(); i32(); i32(); i32() }      // SerializationHeader
            12 -> { u32(); lpString() }              // BinaryLibrary
            5 -> readClassWithMembersAndTypes(systemLib = false)
            4 -> readClassWithMembersAndTypes(systemLib = true)
            6 -> { u32(); lpString() }               // BinaryObjectString
            1 -> readClassWithId()
            7 -> readBinaryArray()
            15 -> readArraySinglePrimitive()
            16 -> readArraySingleObject()
            17 -> readArraySingleString()
            8 -> readMemberPrimitiveTyped()
            9 -> u32()                               // MemberReference
            10 -> {}                                  // ObjectNull
            13 -> u8()                                // ObjectNullMultiple256
            14 -> u32()                               // ObjectNullMultiple
            else -> error("unknown record type")
        }
    }

    private fun readClassWithMembersAndTypes(systemLib: Boolean) {
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
        u32()                           // new obj id (we don't use it)
        val metaId = u32()
        val cls = classes[metaId] ?: error("ClassWithId references unknown class $metaId")
        readInstance(cls)
    }

    private fun consumeAddlInfo(bt: Int, addl: IntArray, i: Int) {
        when (bt) {
            0 -> addl[i] = u8()         // Primitive: PrimitiveTypeEnum
            1 -> {}                     // String
            2 -> {}                     // Object
            3 -> lpString()             // SystemClass: ClassName
            4 -> { lpString(); u32() } // Class: ClassName + LibraryId
            5 -> {}                     // ObjectArray
            6 -> {}                     // StringArray
            7 -> addl[i] = u8()         // PrimitiveArray: PrimitiveTypeEnum
            else -> error("unknown binary type $bt")
        }
    }

    private fun readInstance(cls: ClassDefn) {
        classCounts.merge(cls.name, 1) { a, _ -> a + 1 }
        for (i in cls.memberNames.indices) {
            val bt = cls.binaryTypes[i]
            val mname = cls.memberNames[i]
            readMemberValue(cls.name, mname, bt, cls.addlPrim[i])
        }
    }

    private fun readMemberValue(className: String, memberName: String, bt: Int, addlPrim: Int) {
        when (bt) {
            0 -> readInlinePrimitive(className, memberName, addlPrim)
            1, 2, 3, 4, 5, 6, 7 -> {
                // Each non-primitive member is its own record
                val rt = u8()
                if (rt == 11) return            // MessageEnd in middle = bail out
                handleRecord(rt)
            }
            else -> error("bad bt $bt for $className.$memberName")
        }
    }

    /** Inline primitive members (the common case for ints/floats/bools). */
    private fun readInlinePrimitive(cls: String, mname: String, pc: Int) {
        val off = pos
        when (pc) {
            1 -> { val v = u8() != 0; record(cls, mname, off, NrbfType.BOOL, v) }
            2 -> { val v = u8(); record(cls, mname, off, NrbfType.BYTE, v.toLong()) }
            10 -> { val v = buf[pos].toInt(); pos++; record(cls, mname, off, NrbfType.SBYTE, v.toLong()) }
            7 -> { val v = i16(); record(cls, mname, off, NrbfType.I16, v.toLong()) }
            14 -> { val v = u16(); record(cls, mname, off, NrbfType.U16, v.toLong()) }
            8 -> { val v = i32(); record(cls, mname, off, NrbfType.I32, v.toLong()) }
            15 -> { val v = u32(); record(cls, mname, off, NrbfType.U32, v) }
            9 -> { val v = i64(); record(cls, mname, off, NrbfType.I64, v) }
            16 -> { val v = i64(); record(cls, mname, off, NrbfType.U64, v) }
            11 -> { val v = f32(); record(cls, mname, off, NrbfType.F32, v.toDouble()) }
            6 -> { val v = f64(); record(cls, mname, off, NrbfType.F64, v) }
            3 -> { pos += 1 }            // char (1 byte UTF-8 fragment, simplified)
            5 -> { pos += 16 }           // decimal (skip)
            12 -> { pos += 8 }           // timespan
            13 -> { pos += 8 }           // datetime
            18 -> {                       // string inline
                val s = lpString()
                record(cls, mname, off, NrbfType.STRING, s)
            }
            else -> error("primitive $pc")
        }
    }

    private fun readBinaryArray() {
        u32()                                          // obj id
        val arrayType = u8()                            // Single, Jagged, Rectangular, ...
        val rank = u32().toInt()
        val lengths = IntArray(rank) { u32().toInt() }
        if (arrayType in setOf(3, 4, 5)) {
            for (i in 0 until rank) u32()              // lower bounds
        }
        val bt = u8()
        val tempAddl = IntArray(1) { -1 }
        consumeAddlInfo(bt, tempAddl, 0)
        var total = 1L
        for (l in lengths) total *= l
        for (n in 0 until total) {
            readMemberValue("Array", "item", bt, tempAddl[0])
        }
    }

    private fun readArraySinglePrimitive() {
        u32()
        val length = u32().toInt()
        val pc = u8()
        val w = primWidth(pc)
        pos += length * w
    }

    private fun readArraySingleObject() {
        u32(); val length = u32().toInt()
        for (i in 0 until length) {
            val rt = u8()
            if (rt == 11) return
            handleRecord(rt)
        }
    }

    private fun readArraySingleString() {
        u32(); val length = u32().toInt()
        for (i in 0 until length) {
            val rt = u8()
            if (rt == 11) return
            handleRecord(rt)
        }
    }

    private fun readMemberPrimitiveTyped() {
        val pc = u8()
        readInlinePrimitive("Untyped", "value", pc)
    }

    private fun primWidth(pc: Int): Int = when (pc) {
        1, 2, 10 -> 1; 3, 7, 14 -> 2
        8, 15, 11 -> 4; 9, 16, 6 -> 8
        12, 13 -> 8; 5 -> 16
        else -> error("primWidth $pc")
    }

    private fun record(className: String, memberName: String, off: Int, type: NrbfType, value: Any) {
        fields.add(NrbfField(nextId++, className, memberName, off, type, value))
    }

    // --- byte readers ---
    private fun u8(): Int = buf[pos++].toInt() and 0xFF
    private fun i16(): Int { val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt(); pos += 2; return v }
    private fun u16(): Int { val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF; pos += 2; return v }
    private fun i32(): Int { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int; pos += 4; return v }
    private fun u32(): Long { val v = i32().toLong() and 0xFFFFFFFFL; return v }
    private fun i64(): Long { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long; pos += 8; return v }
    private fun f32(): Float { val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float; pos += 4; return v }
    private fun f64(): Double { val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double; pos += 8; return v }
    private fun vlq(): Int { var n=0; var s=0; while(true){val b=u8(); n=n or ((b and 0x7F) shl s); if(b and 0x80==0) return n; s+=7 } }
    private fun lpString(): String { val n = vlq(); val s = String(buf, pos, n, Charsets.UTF_8); pos += n; return s }
}

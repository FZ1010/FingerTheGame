package com.fingerthegame.app.util

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generic MS-NRBF (.NET BinaryFormatter) walker. Parses ANY NRBF file, records
 * every member primitive value's byte offset so the UI can patch it in place
 * without re-serializing the whole structure.
 *
 * Special-cases System.Numerics.BigInteger: collapses its `_sign + _bits` pair
 * into a single virtual BIGINT field carrying the decoded numeric value, named
 * after the outermost wrapping member so search keeps working.
 *
 * The walker is best-effort: when it encounters an unknown record type it
 * stops gracefully and exposes whatever fields it found up to that point.
 */
enum class NrbfType {
    BOOL, BYTE, SBYTE, I16, U16, I32, U32, I64, U64, F32, F64,
    STRING, BIGINT, DECIMAL, DATETIME, TIMESPAN;

    val byteWidth: Int get() = when (this) {
        BOOL, BYTE, SBYTE -> 1
        I16, U16 -> 2
        I32, U32, F32 -> 4
        I64, U64, F64, DATETIME, TIMESPAN -> 8
        STRING, BIGINT, DECIMAL -> -1
    }
}

/**
 * Layout metadata for a synthesized BigInteger field, so the writer knows
 * where the sign and (optional) bits array live in the source bytes.
 *
 * `bitsOffset == -1` means the original `_bits` was null (value fit in i32 and
 * `_sign` *is* the value). `bitsLength` counts UInt32 elements (4 bytes each).
 */
data class BigIntLayout(val signOffset: Int, val bitsOffset: Int, val bitsLength: Int)

/**
 * Layout metadata for a synthesized System.Decimal field. .NET serializes
 * Decimal as 4 Int32 fields (`flags`, `hi`, `lo`, `mid` — order in the
 * stream depends on the runtime's field declaration order, so we capture
 * each by individual offset rather than assuming).
 *
 *  - `flags`: bit 31 = sign, bits 16–23 = scale (decimal places, 0–28)
 *  - `(hi, mid, lo)`: 96-bit unsigned mantissa, little-endian by uint32
 */
data class DecimalLayout(val flagsOff: Int, val hiOff: Int, val midOff: Int, val loOff: Int)

/** Layout for a synthesized DateTime field. Kind bits live in the top 2
 *  bits of dateData; we preserve them across edits. */
data class DateTimeLayout(val dateDataOff: Int, val originalKindBits: Long)

/** Layout for a synthesized TimeSpan field — just the int64 offset. */
data class TimeSpanLayout(val ticksOff: Int)

data class NrbfField(
    val id: Long,
    val className: String,
    val memberName: String,
    val offset: Int,
    val type: NrbfType,
    val originalValue: Any,    // Long / Double / Boolean / String / BigInteger
    val meta: Any? = null,     // BigIntLayout for BIGINT, null otherwise
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
                writeValue(bb, f, raw)
            } catch (_: Throwable) { /* skip bad input — silently */ }
        }
        return out
    }

    private fun writeValue(bb: ByteBuffer, f: NrbfField, raw: Any) {
        val off = f.offset
        when (f.type) {
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
            NrbfType.BIGINT -> {
                val layout = f.meta as? BigIntLayout ?: return
                writeBigInt(bb, layout, raw.asBigInteger())
            }
            NrbfType.DECIMAL -> {
                val layout = f.meta as? DecimalLayout ?: return
                writeDecimal(bb, layout, raw.asBigDecimal())
            }
            NrbfType.DATETIME -> {
                val layout = f.meta as? DateTimeLayout ?: return
                val ticks = parseDateTimeToTicks(raw.toString())
                // Re-pack with the original Kind bits so a UTC save stays UTC.
                bb.putLong(layout.dateDataOff, layout.originalKindBits or (ticks and 0x3FFFFFFFFFFFFFFFL))
            }
            NrbfType.TIMESPAN -> {
                val layout = f.meta as? TimeSpanLayout ?: return
                bb.putLong(layout.ticksOff, parseTimeSpanToTicks(raw.toString()))
            }
        }
    }

    /** Parse an ISO-8601 timestamp back into .NET ticks (100-ns units since
     *  0001-01-01 UTC). Tries Instant first, then LocalDateTime as UTC. */
    private fun parseDateTimeToTicks(text: String): Long {
        val s = text.trim()
        val instant = runCatching { java.time.Instant.parse(s) }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC)
            }.getOrNull()
            ?: error("can't parse '$s' as ISO-8601 datetime")
        // .NET epoch = 0001-01-01 UTC; Unix epoch = 1970-01-01 UTC.
        val secondsBetween = 62135596800L
        return (instant.epochSecond + secondsBetween) * 10_000_000L + (instant.nano / 100L)
    }

    /** Accept ISO-8601 duration ("PT1H30M"), or "1d 02:34:56(.789)", or
     *  raw long ticks. */
    private fun parseTimeSpanToTicks(text: String): Long {
        val s = text.trim()
        s.toLongOrNull()?.let { return it }
        runCatching { java.time.Duration.parse(s) }.getOrNull()
            ?.let { return it.seconds * 10_000_000L + it.nano / 100L }
        // Loose "Xd HH:MM:SS(.fraction)" parser.
        val re = Regex("""(?:(\d+)d\s+)?(\d+):(\d+)(?::(\d+)(?:\.(\d+))?)?""")
        val m = re.matchEntire(s) ?: error("can't parse '$s' as duration")
        val (d, h, mi, sec, frac) = m.destructured
        val days = d.toLongOrNull() ?: 0L
        val hours = h.toLong()
        val mins = mi.toLong()
        val secs = sec.toLongOrNull() ?: 0L
        val fracTicks = if (frac.isNotEmpty()) {
            val padded = frac.padEnd(7, '0').take(7)
            padded.toLong()
        } else 0L
        val totalSecs = days * 86_400 + hours * 3_600 + mins * 60 + secs
        return totalSecs * 10_000_000L + fracTicks
    }

    private fun writeDecimal(bb: ByteBuffer, layout: DecimalLayout, value: BigDecimal) {
        val unscaled = value.unscaledValue()
        val scale = value.scale()
        require(scale in 0..28) { "Decimal scale $scale out of [0,28]" }
        val mag = unscaled.abs()
        require(mag.bitLength() <= 96) { "Decimal mantissa exceeds 96 bits" }
        val mask32 = BigInteger.valueOf(0xFFFFFFFFL)
        val lo = mag.and(mask32).toLong().toInt()
        val mid = mag.shiftRight(32).and(mask32).toLong().toInt()
        val hi = mag.shiftRight(64).and(mask32).toLong().toInt()
        val sign = if (unscaled.signum() < 0) 1 else 0
        val flags = (sign shl 31) or (scale shl 16)
        bb.putInt(layout.flagsOff, flags)
        bb.putInt(layout.hiOff, hi)
        bb.putInt(layout.midOff, mid)
        bb.putInt(layout.loOff, lo)
    }

    private fun writeBigInt(bb: ByteBuffer, layout: BigIntLayout, value: BigInteger) {
        if (layout.bitsLength == 0) {
            // Original `_bits` was null/empty — `_sign` IS the value. New value
            // must therefore fit in Int32 or we'd need to grow the file.
            val asInt32 = value.toLongOrThrow().let {
                require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    "$value exceeds Int32 range and original allocation has no _bits array"
                }
                it.toInt()
            }
            bb.putInt(layout.signOffset, asInt32)
        } else {
            // Original had a _bits array of `bitsLength` UInt32s. Re-encode
            // sign + magnitude into the same allocation; reject if too big.
            val mag = value.abs()
            val capacityBits = layout.bitsLength * 32
            require(mag.bitLength() <= capacityBits) {
                "$value needs ${mag.bitLength()} bits but only $capacityBits are allocated"
            }
            bb.putInt(layout.signOffset, value.signum())
            val le = magnitudeToLittleEndianBytes(mag, layout.bitsLength * 4)
            for (i in le.indices) bb.put(layout.bitsOffset + i, le[i])
        }
    }

    private fun BigInteger.toLongOrThrow(): Long {
        require(bitLength() <= 63) { "$this doesn't fit in a 64-bit signed long" }
        return toLong()
    }

    private fun magnitudeToLittleEndianBytes(value: BigInteger, byteLen: Int): ByteArray {
        val out = ByteArray(byteLen)
        var v = value
        val mask = BigInteger.valueOf(0xFF)
        for (i in 0 until byteLen) {
            out[i] = v.and(mask).toInt().toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    private fun Any.asLong(): Long = when (this) {
        is BigInteger -> this.toLong()
        is Number -> this.toLong()
        is Boolean -> if (this) 1 else 0
        is String -> this.toLongOrNull() ?: this.toDouble().toLong()
        else -> 0L
    }
    private fun Any.asDouble(): Double = when (this) {
        is BigInteger -> this.toDouble()
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
    private fun Any.asBigInteger(): BigInteger = when (this) {
        is BigInteger -> this
        is BigDecimal -> this.toBigInteger()
        is Number -> BigInteger.valueOf(this.toLong())
        is Boolean -> if (this) BigInteger.ONE else BigInteger.ZERO
        is String -> this.trim().toBigIntegerOrNull() ?: BigInteger.ZERO
        else -> BigInteger.ZERO
    }
    private fun Any.asBigDecimal(): BigDecimal = when (this) {
        is BigDecimal -> this
        is BigInteger -> BigDecimal(this)
        is Number -> BigDecimal.valueOf(this.toDouble())
        is Boolean -> if (this) BigDecimal.ONE else BigDecimal.ZERO
        is String -> this.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
        else -> BigDecimal.ZERO
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

    /** Stack of (parent class name, member name) pairs traversed via inline
     *  records — used as a first attempt to un-bury values stuck inside
     *  generic wrapper accessors. */
    private val context = ArrayDeque<Pair<String, String>>()

    /** A back-reference edge: the target object id was reached from
     *  [ownerClass]'s [ownerMember] field, and the instance that held that
     *  field had object id [ownerObjId]. Walking [ownerObjId] back through
     *  [refOwners] reconstructs the semantic chain through nested wrappers. */
    private data class RefEdge(val ownerClass: String, val ownerMember: String, val ownerObjId: Long)

    /** Object-graph back-references for forward-serialized payloads. Real
     *  War lays out PersistentData first with its members as
     *  MemberReferences and dumps the referenced wrappers afterwards as
     *  standalone records. By the time we parse the inner BigInteger the
     *  inline [context] stack is empty — refOwners lets us walk back. */
    private val refOwners = HashMap<Long, RefEdge>()

    /** Object id of the next record we're about to parse. Set when a record
     *  type that carries an obj id is read; read by [readBigIntegerInstance]
     *  and similar synthesizers. */
    private var pendingObjId: Long = 0L

    /** Wrapper-internal names that carry no semantic information on their
     *  own — when a recorded field uses one of these we should look further
     *  out for a better display name. */
    private val genericFieldNames = setOf(
        "value", "_value", "Value",
        "ObservedValue", "observedValue", "_observedValue",
        "item", "Item", "_item",
        "current", "Current",
    )

    private fun isGenericName(name: String): Boolean = name in genericFieldNames

    /** Resolve a field's effective (class, member) pair. Strategy:
     *  1. If the supplied [memberName] is non-generic, keep it.
     *  2. Walk up the inline [context] stack for a meaningful name.
     *  3. Walk up the back-reference chain in [refOwners] — handles the
     *     forward-reference layout where the inline stack is empty by the
     *     time the value is parsed (Real War / Unity Observable<T>). */
    private fun bestField(parentClass: String, memberName: String): Pair<String, String> {
        if (!isGenericName(memberName)) return parentClass to memberName
        for (i in context.indices.reversed()) {
            val (cls, mn) = context[i]
            if (!isGenericName(mn)) return cls to mn
        }
        var cursor: Long = pendingObjId
        val seen = HashSet<Long>()
        while (cursor != 0L && seen.add(cursor)) {
            val edge = refOwners[cursor] ?: break
            if (!isGenericName(edge.ownerMember)) return edge.ownerClass to edge.ownerMember
            cursor = edge.ownerObjId
        }
        return parentClass to memberName
    }

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
            9 -> {                                    // MemberReference
                val targetId = u32()
                val owner = context.lastOrNull()
                if (owner != null) {
                    refOwners[targetId] = RefEdge(owner.first, owner.second, pendingObjId)
                }
            }
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
        recordOwnership(objId)
        val saved = pendingObjId
        pendingObjId = objId
        try { readInstance(cls) } finally { pendingObjId = saved }
    }

    private fun readClassWithId() {
        val newId = u32()
        val metaId = u32()
        val cls = classes[metaId] ?: error("ClassWithId references unknown class $metaId")
        recordOwnership(newId)
        val saved = pendingObjId
        pendingObjId = newId
        try { readInstance(cls) } finally { pendingObjId = saved }
    }

    /** When a new object is parsed inline inside a member traversal, link it
     *  back to the containing (class, member) so the bestField chain walk
     *  can climb out of nested wrapper classes. */
    private fun recordOwnership(newObjId: Long) {
        val owner = context.lastOrNull() ?: return
        // Don't clobber an existing entry recorded earlier (e.g. via MemberRef).
        refOwners.putIfAbsent(newObjId, RefEdge(owner.first, owner.second, pendingObjId))
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
        if (isBigIntegerShape(cls)) { readBigIntegerInstance(cls); return }
        if (isDecimalShape(cls)) { readDecimalInstance(cls); return }
        if (isDateTimeShape(cls)) { readDateTimeInstance(cls); return }
        if (isTimeSpanShape(cls)) { readTimeSpanInstance(cls); return }
        for (i in cls.memberNames.indices) {
            val bt = cls.binaryTypes[i]
            val mname = cls.memberNames[i]
            readMemberValue(cls.name, mname, bt, cls.addlPrim[i])
        }
    }

    /** .NET DateTime is a single Int64 `dateData` (top 2 bits = Kind,
     *  bottom 62 = ticks since 0001-01-01). Some serializers use `ticks`
     *  as the field name instead — accept both. */
    private fun isDateTimeShape(cls: ClassDefn): Boolean {
        if (cls.name != "System.DateTime") return false
        if (cls.memberNames.size != 1) return false
        val n = cls.memberNames[0].lowercase()
        if (n != "datedata" && n != "_datedata" && n != "ticks" && n != "_ticks") return false
        return cls.binaryTypes[0] == 0 && cls.addlPrim[0] == 16     // PrimitiveTypeEnum 16 = UInt64 (also matches Int64 use of 9)
                || cls.binaryTypes[0] == 0 && cls.addlPrim[0] == 9
    }

    private fun readDateTimeInstance(cls: ClassDefn) {
        val off = pos
        val raw = i64()                                              // dateData
        val kindBits = raw and 0xC000000000000000UL.toLong()
        val ticks = raw and 0x3FFFFFFFFFFFFFFFL
        val display = formatDotNetTicksAsIso(ticks)
        val (eCls, eName) = bestField(cls.name, "value")
        fields.add(NrbfField(
            id = nextId++,
            className = eCls,
            memberName = eName,
            offset = off,
            type = NrbfType.DATETIME,
            originalValue = display,
            meta = DateTimeLayout(off, kindBits),
        ))
    }

    /** .NET TimeSpan is a single Int64 `_ticks` (100-ns intervals). */
    private fun isTimeSpanShape(cls: ClassDefn): Boolean {
        if (cls.name != "System.TimeSpan") return false
        if (cls.memberNames.size != 1) return false
        val n = cls.memberNames[0].lowercase()
        if (n != "_ticks" && n != "ticks") return false
        return cls.binaryTypes[0] == 0 && cls.addlPrim[0] == 9
    }

    private fun readTimeSpanInstance(cls: ClassDefn) {
        val off = pos
        val ticks = i64()
        val display = formatTicksAsDuration(ticks)
        val (eCls, eName) = bestField(cls.name, "value")
        fields.add(NrbfField(
            id = nextId++,
            className = eCls,
            memberName = eName,
            offset = off,
            type = NrbfType.TIMESPAN,
            originalValue = display,
            meta = TimeSpanLayout(off),
        ))
    }

    private fun formatDotNetTicksAsIso(ticks: Long): String {
        if (ticks <= 0) return "0001-01-01T00:00:00Z"
        val secondsTotal = ticks / 10_000_000L
        val nanos = ((ticks % 10_000_000L) * 100L).toInt()
        val secondsSinceUnix = secondsTotal - 62135596800L
        return runCatching {
            java.time.Instant.ofEpochSecond(secondsSinceUnix, nanos.toLong()).toString()
        }.getOrDefault("ticks=$ticks")
    }

    private fun formatTicksAsDuration(ticks: Long): String {
        val totalNanos = ticks * 100L
        return runCatching { java.time.Duration.ofNanos(totalNanos).toString() }
            .getOrDefault("ticks=$ticks")
    }

    /** .NET Decimal serializes as 4 Int32 fields. The runtime field order
     *  depends on .NET version (`flags,hi,lo,mid` historically), so we
     *  match by class name + count + types and identify each field by name
     *  later in [readDecimalInstance]. */
    private fun isDecimalShape(cls: ClassDefn): Boolean {
        if (cls.name != "System.Decimal") return false
        if (cls.memberNames.size != 4) return false
        for (i in 0 until 4) {
            if (cls.binaryTypes[i] != 0) return false       // primitive
            if (cls.addlPrim[i] != 8) return false           // Int32
        }
        return true
    }

    private fun readDecimalInstance(cls: ClassDefn) {
        // Walk the four Int32s in stream order, capturing each offset+value;
        // then map them to (flags, hi, mid, lo) by member name (case-
        // insensitive, with optional underscore prefix).
        val offs = IntArray(4)
        val vals = IntArray(4)
        for (i in 0 until 4) {
            offs[i] = pos
            vals[i] = i32()
        }
        fun indexOf(vararg candidates: String): Int {
            for (c in candidates) {
                val i = cls.memberNames.indexOfFirst { it.equals(c, ignoreCase = true) }
                if (i >= 0) return i
            }
            return -1
        }
        val fi = indexOf("flags", "_flags")
        val hi = indexOf("hi", "_hi")
        val lo = indexOf("lo", "_lo")
        val mi = indexOf("mid", "_mid")
        if (fi < 0 || hi < 0 || lo < 0 || mi < 0) {
            // Field naming we don't recognise — fall back to recording the
            // four ints as-is so the user can still see them.
            for (i in 0 until 4) record(cls.name, cls.memberNames[i], offs[i], NrbfType.I32, vals[i].toLong())
            return
        }

        val value = decodeDecimal(vals[fi], vals[hi], vals[lo], vals[mi])
        val (eCls, eName) = bestField(cls.name, "value")
        val layout = DecimalLayout(flagsOff = offs[fi], hiOff = offs[hi], midOff = offs[mi], loOff = offs[lo])
        fields.add(NrbfField(
            id = nextId++,
            className = eCls,
            memberName = eName,
            offset = offs[fi],
            type = NrbfType.DECIMAL,
            originalValue = value,
            meta = layout,
        ))
    }

    private fun decodeDecimal(flags: Int, hi: Int, lo: Int, mid: Int): BigDecimal {
        val sign = (flags ushr 31) and 1
        val scale = (flags ushr 16) and 0xFF
        val mask32 = BigInteger.valueOf(0xFFFFFFFFL)
        val unscaled = BigInteger.valueOf(hi.toLong() and 0xFFFFFFFFL).shiftLeft(64)
            .or(BigInteger.valueOf(mid.toLong() and 0xFFFFFFFFL).shiftLeft(32))
            .or(BigInteger.valueOf(lo.toLong() and 0xFFFFFFFFL))
        val signed = if (sign != 0) unscaled.negate() else unscaled
        return BigDecimal(signed, scale)
    }

    private fun isBigIntegerShape(cls: ClassDefn): Boolean {
        // Match by structure, not just name — covers the common case of
        // System.Numerics.BigInteger and any compatible reimplementation.
        return cls.memberNames.size == 2 &&
               cls.memberNames[0] == "_sign" &&
               cls.memberNames[1] == "_bits" &&
               cls.binaryTypes[0] == 0 &&        // _sign is primitive
               cls.addlPrim[0] == 8 &&            // ...specifically Int32
               cls.binaryTypes[1] == 7 &&        // _bits is PrimitiveArray
               cls.addlPrim[1] == 15              // ...of UInt32
    }

    private fun readBigIntegerInstance(cls: ClassDefn) {
        val signOff = pos
        val sign = i32()                   // consume 4 bytes for _sign

        // _bits is a PrimitiveArray member, so its value is the next record.
        val rt = u8()
        var bitsDataOff = -1
        var bitsLen = 0
        val value: BigInteger = when (rt) {
            10 -> {
                // ObjectNull: value fits in Int32, sign IS the value (signed).
                BigInteger.valueOf(sign.toLong())
            }
            15 -> {
                // ArraySinglePrimitive — decode the magnitude inline.
                u32()                                    // obj id
                bitsLen = u32().toInt()
                u8()                                     // pc, expected 15 (UInt32)
                bitsDataOff = pos
                val mag = decodeLittleEndianMagnitude(buf, pos, bitsLen * 4)
                pos += bitsLen * 4
                if (sign < 0) mag.negate() else mag
            }
            9 -> {
                // MemberReference — bits array is elsewhere; we can't follow
                // it without a full object graph. Best-effort: surface sign as
                // the value. (Rare in practice for BigInteger.)
                u32()                                    // ref id
                BigInteger.valueOf(sign.toLong())
            }
            else -> {
                // Unexpected — let the generic handler swallow whatever this
                // is, then expose sign as the value.
                runCatching { handleRecord(rt) }
                BigInteger.valueOf(sign.toLong())
            }
        }

        // Steal the most meaningful ancestor name (e.g. `coinsAmountBigInteger`)
        // from the context — `value`/`ObservedValue`/`_value` are dead
        // weight in the UI.
        val (effectiveClass, effectiveName) = bestField(cls.name, "value")
        fields.add(NrbfField(
            id = nextId++,
            className = effectiveClass,
            memberName = effectiveName,
            offset = signOff,
            type = NrbfType.BIGINT,
            originalValue = value,
            meta = BigIntLayout(signOff, bitsDataOff, bitsLen),
        ))
    }

    private fun decodeLittleEndianMagnitude(buf: ByteArray, off: Int, len: Int): BigInteger {
        if (len == 0) return BigInteger.ZERO
        var mag = BigInteger.ZERO
        val shift8 = BigInteger.valueOf(256)
        // Walk from MSB end inwards to assemble the big-endian magnitude.
        for (i in len - 1 downTo 0) {
            val b = (buf[off + i].toInt() and 0xFF).toLong()
            mag = mag.multiply(shift8).add(BigInteger.valueOf(b))
        }
        return mag
    }

    private fun readMemberValue(className: String, memberName: String, bt: Int, addlPrim: Int) {
        when (bt) {
            0 -> readInlinePrimitive(className, memberName, addlPrim)
            1, 2, 3, 4, 5, 6, 7 -> {
                // Each non-primitive member is its own record. Push the
                // (parent class, member name) so any field recorded deeper in
                // the tree can borrow this name when its own is generic.
                context.addLast(className to memberName)
                try {
                    val rt = u8()
                    if (rt == 11) return            // MessageEnd in middle = bail out
                    handleRecord(rt)
                } finally {
                    context.removeLast()
                }
            }
            else -> error("bad bt $bt for $className.$memberName")
        }
    }

    /** Inline primitive members (the common case for ints/floats/bools). */
    private fun readInlinePrimitive(cls: String, mname: String, pc: Int) {
        // Un-bury the value if it's stuck inside a generic wrapper accessor.
        val (eCls, eName) = bestField(cls, mname)
        val off = pos
        when (pc) {
            1 -> { val v = u8() != 0; record(eCls, eName, off, NrbfType.BOOL, v) }
            2 -> { val v = u8(); record(eCls, eName, off, NrbfType.BYTE, v.toLong()) }
            10 -> { val v = buf[pos].toInt(); pos++; record(eCls, eName, off, NrbfType.SBYTE, v.toLong()) }
            7 -> { val v = i16(); record(eCls, eName, off, NrbfType.I16, v.toLong()) }
            14 -> { val v = u16(); record(eCls, eName, off, NrbfType.U16, v.toLong()) }
            8 -> { val v = i32(); record(eCls, eName, off, NrbfType.I32, v.toLong()) }
            15 -> { val v = u32(); record(eCls, eName, off, NrbfType.U32, v) }
            9 -> { val v = i64(); record(eCls, eName, off, NrbfType.I64, v) }
            16 -> { val v = i64(); record(eCls, eName, off, NrbfType.U64, v) }
            11 -> { val v = f32(); record(eCls, eName, off, NrbfType.F32, v.toDouble()) }
            6 -> { val v = f64(); record(eCls, eName, off, NrbfType.F64, v) }
            3 -> { pos += 1 }            // char (1 byte UTF-8 fragment, simplified)
            5 -> { pos += 16 }           // decimal (skip)
            12 -> { pos += 8 }           // timespan
            13 -> { pos += 8 }           // datetime
            18 -> {                       // string inline
                val s = lpString()
                record(eCls, eName, off, NrbfType.STRING, s)
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

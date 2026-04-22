// Round-trip patch test: parse an NRBF save, patch a few primitive fields by
// offset, re-parse, confirm the new values appear at the same offsets and
// the field count is unchanged.
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    if (args.size < 2) error("usage: java -jar patch.jar <save.bin> <member-substring> [<member-substring> ...]")
    val srcPath = args[0]
    val targets = args.drop(1)
    val original = File(srcPath).readBytes()

    val w1 = Walker(original); w1.run()

    val hits = targets.mapNotNull { name ->
        val f = w1.fields.firstOrNull { it.memberName.contains(name) }
        if (f == null) { println("  $name NOT FOUND"); null } else name to f
    }
    if (hits.isEmpty()) { println("nothing to patch"); return }

    println("BEFORE:")
    for ((name, f) in hits) {
        println("  $name @0x${f.offset.toString(16)} = ${f.originalValue} (${f.type})")
    }

    val patched = original.copyOf()
    val bb = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
    for ((_, f) in hits) {
        when (f.type) {
            NrbfType.F64 -> bb.putDouble(f.offset, 999_999_999.0)
            NrbfType.F32 -> bb.putFloat(f.offset, 100f)
            NrbfType.I64, NrbfType.U64 -> bb.putLong(f.offset, 999_999_999L)
            NrbfType.I32, NrbfType.U32 -> bb.putInt(f.offset, 999_999_999)
            else -> { /* leave alone */ }
        }
    }

    val w2 = Walker(patched); w2.run()
    println("AFTER:")
    for ((name, f) in hits) {
        val f2 = w2.fields.firstOrNull { it.offset == f.offset }
        println("  $name @0x${f.offset.toString(16)} = ${f2?.originalValue} (${f2?.type})")
    }

    println("Total fields: ${w2.fields.size} (was ${w1.fields.size})")

    val out = File(srcPath.removeSuffix(".bin") + ".patched.bin")
    out.writeBytes(patched)
    println("Wrote $out")
}

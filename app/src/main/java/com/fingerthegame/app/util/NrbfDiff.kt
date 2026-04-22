package com.fingerthegame.app.util

/**
 * One field that differs between two parsed NRBF documents.
 *
 * Pairing is by byte offset — for two saves of the same game (same class
 * graph, same field declaration order) offsets are identical, so this is
 * a fast and reliable join key.
 */
data class NrbfDiffEntry(
    val field: NrbfField,
    val newValue: Any,
)

object NrbfDiff {
    fun compute(current: NrbfDocument, other: NrbfDocument): List<NrbfDiffEntry> {
        val byOffset = other.fields.associateBy { it.offset }
        return current.fields.mapNotNull { f ->
            val o = byOffset[f.offset] ?: return@mapNotNull null
            if (f.type != o.type) return@mapNotNull null
            if (sameValue(f.originalValue, o.originalValue)) null
            else NrbfDiffEntry(f, o.originalValue)
        }
    }

    private fun sameValue(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == b
        // BigInteger/BigDecimal need value-aware comparison; primitives
        // already compare correctly via equals.
        return a == b
    }
}

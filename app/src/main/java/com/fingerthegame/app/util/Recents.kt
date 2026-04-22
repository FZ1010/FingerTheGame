package com.fingerthegame.app.util

import android.content.Context

/**
 * Tiny recently-opened-files list. Persisted as TSV in SharedPreferences
 * because we want simple atomic load/save and the entire list is always
 * read at once.
 */
data class RecentEntry(val pkg: String, val label: String, val path: String, val openedAt: Long) {
    val fileName: String get() = path.substringAfterLast('/')
}

object Recents {
    private const val PREFS = "fingerthegame_recents"
    private const val KEY = "entries"
    private const val MAX = 20

    fun read(ctx: Context): List<RecentEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 4) return@mapNotNull null
                RecentEntry(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0L)
            }
            .sortedByDescending { it.openedAt }
            .toList()
    }

    fun add(ctx: Context, entry: RecentEntry) {
        val merged = (listOf(entry) + read(ctx).filter { it.path != entry.path })
            .take(MAX)
        val raw = merged.joinToString("\n") {
            "${it.pkg}\t${it.label}\t${it.path}\t${it.openedAt}"
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, raw)
            .apply()
    }

    fun remove(ctx: Context, path: String) {
        val merged = read(ctx).filter { it.path != path }
        val raw = merged.joinToString("\n") {
            "${it.pkg}\t${it.label}\t${it.path}\t${it.openedAt}"
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, raw)
            .apply()
    }
}

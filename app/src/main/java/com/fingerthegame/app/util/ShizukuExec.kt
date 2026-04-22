package com.fingerthegame.app.util

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Thin wrapper around Shizuku.newProcess. Shizuku runs the commands as the `shell`
 * user (uid 2000), so we get access to /sdcard/Android/data/<other-pkg>/... which
 * a regular app UID cannot read on Android 11+.
 */
object ShizukuExec {
    const val PERM_REQ_CODE = 4242

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private fun newProcess(cmd: Array<String>): Process {
        return newProcessMethod.invoke(null, cmd, null, null) as Process
    }

    fun status(): Status = try {
        when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NEEDS_PERMISSION
            else -> Status.READY
        }
    } catch (_: IllegalStateException) { Status.NOT_INSTALLED }
      catch (_: NoClassDefFoundError) { Status.NOT_INSTALLED }

    fun requestPermission() = Shizuku.requestPermission(PERM_REQ_CODE)

    fun readFile(path: String): ByteArray {
        val proc = newProcess(arrayOf("cat", path))
        val bytes = proc.inputStream.readBytes()
        val err = proc.errorStream.readBytes()
        if (proc.waitFor() != 0) error("read $path: ${String(err).trim()}")
        return bytes
    }

    fun writeFile(path: String, data: ByteArray) {
        // Pipe raw bytes through stdin. Embedding in argv would hit ARG_MAX
        // (~128KB) on multi-MB saves.
        val proc = newProcess(arrayOf("sh", "-c", "cat > ${shellQuote(path)}"))
        proc.outputStream.use { it.write(data) }
        val err = proc.errorStream.readBytes()
        if (proc.waitFor() != 0) {
            val msg = String(err).trim().ifEmpty { "unknown shell error" }
            error("write $path: $msg")
        }
    }

    fun forceStop(pkg: String) {
        newProcess(arrayOf("am", "force-stop", pkg)).waitFor()
    }

    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val mtimeEpoch: Long,
    )

    fun listDir(path: String): List<Entry> {
        // Samsung/toybox `find -printf` lacks GNU's format string, so use a
        // portable shell loop. Each line: <type=d|f>\t<size>\t<mtime_epoch>\t<name>.
        val script = """
            cd ${shellQuote(path)} 2>/dev/null || exit 0
            for f in * .[!.]* ..?*; do
                [ -e "${'$'}f" ] || continue
                if [ -d "${'$'}f" ]; then
                    mt=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null); [ -z "${'$'}mt" ] && mt=0
                    printf 'd\t0\t%s\t%s\n' "${'$'}mt" "${'$'}f"
                else
                    sz=${'$'}(stat -c %s "${'$'}f" 2>/dev/null); [ -z "${'$'}sz" ] && sz=0
                    mt=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null); [ -z "${'$'}mt" ] && mt=0
                    printf 'f\t%s\t%s\t%s\n' "${'$'}sz" "${'$'}mt" "${'$'}f"
                fi
            done
        """.trimIndent()
        val proc = newProcess(arrayOf("sh", "-c", script))
        val raw = String(proc.inputStream.readBytes())
        proc.waitFor()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val type = parts[0]
                val size = parts[1].toLongOrNull() ?: 0L
                val mtime = parts[2].toLongOrNull() ?: 0L
                val name = parts[3]
                val full = if (path.endsWith("/")) "$path$name" else "$path/$name"
                Entry(name, full, isDir = type == "d", size = size, mtimeEpoch = mtime)
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            .toList()
    }

    data class SearchHit(val path: String, val snippet: String?)

    /** Recursive filename search; uses `-iname` when [caseInsensitive], else `-name`. */
    fun searchFilenames(root: String, query: String, caseInsensitive: Boolean, max: Int = 300): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val flag = if (caseInsensitive) "-iname" else "-name"
        val pat = shellQuote("*$query*")
        val script = "find ${shellQuote(root)} -type f $flag $pat 2>/dev/null | head -$max"
        val proc = newProcess(arrayOf("sh", "-c", script))
        val raw = String(proc.inputStream.readBytes())
        proc.waitFor()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { SearchHit(it.trim(), null) }
            .toList()
    }

    /**
     * Recursive content search using grep -F (fixed-string, binary-safe via -a).
     * Returns up to [max] hits with a small snippet of context per hit.
     */
    fun searchContent(root: String, query: String, caseInsensitive: Boolean, max: Int = 200): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val iflag = if (caseInsensitive) "i" else ""
        // grep -arHnm 1 -F[i] PATTERN ROOT
        val script =
            "grep -arHnm 1 -F$iflag ${shellQuote(query)} ${shellQuote(root)} 2>/dev/null | head -$max"
        val proc = newProcess(arrayOf("sh", "-c", script))
        val raw = String(proc.inputStream.readBytes())
        proc.waitFor()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                // file:lineno:content  (filenames can contain ':', so split right-twice)
                val firstColon = line.indexOf(':')
                if (firstColon <= 0) return@mapNotNull null
                val secondColon = line.indexOf(':', firstColon + 1)
                if (secondColon < 0) return@mapNotNull SearchHit(line.substring(0, firstColon), null)
                val path = line.substring(0, firstColon)
                val snippet = line.substring(secondColon + 1)
                    .replace(Regex("[^\\x20-\\x7e]"), "·")
                    .take(80)
                SearchHit(path, snippet)
            }
            .distinctBy { it.path + (it.snippet ?: "") }
            .toList()
    }

    fun pathExists(path: String): Boolean {
        val proc = newProcess(arrayOf("sh", "-c",
            "[ -e ${shellQuote(path)} ] && echo yes || echo no"))
        val out = String(proc.inputStream.readBytes()).trim()
        proc.waitFor()
        return out == "yes"
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    fun backupLocal(originalPath: String, data: ByteArray, localDir: File): File {
        val safe = originalPath.replace('/', '_').trim('_')
        val stamp = System.currentTimeMillis()
        val out = File(localDir, "backup-$stamp-$safe")
        out.writeBytes(data)
        return out
    }
}

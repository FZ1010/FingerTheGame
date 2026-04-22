package com.fingerthegame.app.util

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Attempt to crack an Easy Save 3-style AES-CBC encrypted save by trial-
 * decrypting it with passwords scraped from the owning game's APK.
 *
 * Works for the meaningful fraction of games that use ES3 with a literal
 * password embedded in C# code — the password ends up as a UTF-8 string
 * literal in the IL2CPP `global-metadata.dat` (or a Mono `.dll`), and
 * trial decryption with PBKDF2-derived keys finds it in seconds.
 *
 * Does NOT crack games that derive keys at runtime (device-id-based),
 * use ChaCha20, GCM, hardware-backed keys, or otherwise diverge from
 * the ES3 default crypto. Surfaces a honest "tried N candidates, none
 * worked" result for those.
 */
data class Es3Layout(val saltSize: Int, val ivSize: Int)

data class Es3Result(
    val plain: ByteArray? = null,
    val password: String? = null,
    val keyBits: Int = 0,
    val iters: Int = 0,
    val prf: String = "",
    val layout: Es3Layout? = null,
    val attempts: Int = 0,
    val message: String = "",
) {
    val ok: Boolean get() = plain != null
}

object Es3Crack {

    /** Headers ES3 has used across versions; pick whichever makes the
     *  remaining ciphertext a multiple of 16 bytes (AES-CBC requirement). */
    private val LAYOUTS = listOf(Es3Layout(8, 16), Es3Layout(16, 16), Es3Layout(0, 16))

    /** PBKDF2 / AES variants ES3 has shipped over the years. We sweep all
     *  of them; a single cipher.doFinal is on the order of microseconds. */
    private val PARAM_SETS = listOf(
        Triple(128, 100, "PBKDF2WithHmacSHA1"),    // ES3 default
        Triple(256, 100, "PBKDF2WithHmacSHA1"),
        Triple(128, 1000, "PBKDF2WithHmacSHA1"),
        Triple(256, 1000, "PBKDF2WithHmacSHA1"),
        Triple(128, 10000, "PBKDF2WithHmacSHA1"),
        Triple(256, 10000, "PBKDF2WithHmacSHA1"),
        Triple(128, 100, "PBKDF2WithHmacSHA256"),
        Triple(256, 100, "PBKDF2WithHmacSHA256"),
    )

    private val PRIORITY_GUESSES = listOf(
        "ironcrypt", "password", "easysave", "EasySave3", "Es3", "secretkey",
    )

    fun looksEncrypted(blob: ByteArray): Boolean {
        if (blob.size < 32) return false
        // Quick entropy + alignment check. Real entropy would compare
        // Shannon to ~7.9 but that's expensive — a coarse byte-distribution
        // proxy is enough for "likely encrypted" gating.
        val histo = IntArray(256)
        val sample = minOf(blob.size, 4096)
        for (i in 0 until sample) histo[blob[i].toInt() and 0xFF]++
        val unique = histo.count { it > 0 }
        if (unique < 200) return false                    // not high-entropy
        // ES3 ciphertext has to align with one of our known headers.
        return LAYOUTS.any { (blob.size - it.saltSize - it.ivSize) % 16 == 0 }
    }

    fun crack(blob: ByteArray, dictionary: Sequence<String>): Es3Result {
        val layout = LAYOUTS.firstOrNull { (blob.size - it.saltSize - it.ivSize) % 16 == 0 }
            ?: return Es3Result(message = "file size doesn't match any ES3 header layout")

        val salt = blob.copyOfRange(0, layout.saltSize)
        val iv = blob.copyOfRange(layout.saltSize, layout.saltSize + layout.ivSize)
        val cipherText = blob.copyOfRange(layout.saltSize + layout.ivSize, blob.size)

        val seen = HashSet<String>()
        val ordered = (PRIORITY_GUESSES.asSequence() + dictionary).filter { seen.add(it) }
        var attempts = 0

        for ((bits, iters, prf) in PARAM_SETS) {
            for (pw in ordered) {
                attempts++
                val plain = tryOne(salt, iv, cipherText, pw, bits, iters, prf) ?: continue
                if (!looksPlausiblePlaintext(plain)) continue
                return Es3Result(plain, pw, bits, iters, prf, layout, attempts,
                    "decrypted with '$pw' (AES-$bits, $iters iters, $prf)")
            }
        }
        return Es3Result(attempts = attempts,
            message = "tried $attempts password × cipher combos, none decrypted to plausible plaintext")
    }

    /** Trial-decrypt with one set of params. Returns null on padding error. */
    private fun tryOne(salt: ByteArray, iv: ByteArray, cipherText: ByteArray,
                       password: String, keyBits: Int, iters: Int, prf: String): ByteArray? {
        val key = try {
            SecretKeyFactory.getInstance(prf)
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iters, keyBits))
                .encoded
        } catch (_: Throwable) { return null }
        return runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(cipherText)
            }
        }.getOrNull()
    }

    private fun looksPlausiblePlaintext(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val sample = b.take(minOf(b.size, 256))
        val printable = sample.count { x ->
            val c = x.toInt() and 0xFF
            c == 9 || c == 10 || c == 13 || c in 32..126
        }
        // Decrypted ES3 payload is either JSON (mostly printable) or NRBF
        // (which starts with magic 00 01 00 00 00 FF FF FF FF — has nulls).
        // Use a relaxed threshold and *also* recognise the NRBF magic.
        if (printable.toDouble() / sample.size > 0.6) return true
        if (b.size >= 9 && b[0] == 0.toByte() && b[1] == 1.toByte() &&
            b[5] == 0xFF.toByte() && b[6] == 0xFF.toByte()) return true
        return false
    }

    /** Pull the picked package's APK split files via PackageManager.
     *  Returns local files we can scan for embedded password literals. */
    fun pullApkFiles(ctx: Context, pkg: String): List<File> {
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }
            .getOrNull() ?: return emptyList()
        val paths = mutableListOf<String>().apply {
            add(ai.sourceDir)
            ai.splitSourceDirs?.let { addAll(it) }
        }
        return paths.mapNotNull { p -> runCatching { File(p) }.getOrNull()?.takeIf { it.exists() } }
    }

    /** Walk every APK split + extracted IL2CPP/Mono binaries, yielding
     *  every plausible-password ASCII string. */
    fun candidatesFromApks(apks: List<File>, scratch: File): Sequence<String> = sequence {
        for (apk in apks) {
            // APKs are ZIPs. Pull the most likely password-bearing entries.
            try {
                val zip = java.util.zip.ZipFile(apk)
                try {
                    val want = setOf(
                        "assets/bin/Data/Managed/Metadata/global-metadata.dat",
                        "lib/arm64-v8a/libil2cpp.so",
                        "lib/armeabi-v7a/libil2cpp.so",
                        "classes.dex", "classes2.dex", "classes3.dex",
                    )
                    for (e in zip.entries()) {
                        if (e.name !in want && !e.name.endsWith(".dll")) continue
                        val cap = minOf(e.size, 80L * 1024 * 1024)        // 80MB cap per blob
                        val out = File(scratch, "scan-${e.name.hashCode()}.bin")
                        zip.getInputStream(e).use { ins ->
                            out.outputStream().use { os ->
                                val buf = ByteArray(64 * 1024)
                                var copied = 0L
                                while (copied < cap) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, minOf(n.toLong(), cap - copied).toInt())
                                    copied += n
                                }
                            }
                        }
                        yieldAll(extractAsciiCandidates(out.readBytes()))
                        out.delete()
                    }
                } finally { zip.close() }
            } catch (_: Throwable) { /* skip unreadable splits */ }
        }
    }.distinct()

    private fun extractAsciiCandidates(blob: ByteArray): Sequence<String> = sequence {
        var start = -1
        for (i in blob.indices) {
            val c = blob[i].toInt() and 0xFF
            val printableNonSpace = c in 0x21..0x7E
            if (printableNonSpace) {
                if (start < 0) start = i
            } else {
                if (start >= 0 && i - start in 4..40) {
                    val s = String(blob, start, i - start, Charsets.US_ASCII)
                    if (filterCandidate(s)) yield(s)
                }
                start = -1
            }
        }
    }

    private fun filterCandidate(s: String): Boolean {
        // Junk-filter: skip obvious non-passwords (paths, type names, mangled symbols).
        if (s.contains('/') || s.contains('\\')) return false
        if (s.contains("System.") || s.contains("UnityEngine.")) return false
        if (s.contains(".dll") || s.contains(".so") || s.contains(".cs")) return false
        if (s.startsWith("get_") || s.startsWith("set_") || s.startsWith("op_")) return false
        if (s.startsWith("_ZN") || s.startsWith("__")) return false
        if (s.contains("::") || s.contains("<>") || s.contains("k__")) return false
        return true
    }
}

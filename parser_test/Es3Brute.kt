// Trial-decrypt an ES3-encrypted file with every reasonable string
// extracted from a Unity IL2CPP global-metadata.dat (or any binary).
// First password whose decryption (a) succeeds without a padding error
// AND (b) yields plausible plaintext wins.
//
//   kotlinc parser_test/Es3Brute.kt -include-runtime -d /tmp/es3brute.jar
//   java -jar /tmp/es3brute.jar <encrypted.es3> <metadata-or-libil2cpp.so>

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val PRIORITY_GUESSES = listOf(
    // Known ES3 / Unity defaults seen in the wild
    "ironcrypt", "password", "JinSaltJoint", "easysave", "EasySave3", "es3",
    "EasySave", "Es3", "secret", "secretkey",
)

private fun deriveKey(password: String, salt: ByteArray, iters: Int = 100, keyBits: Int = 128): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iters, keyBits)
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
}

/** Try a single (password, params) combination. */
private fun tryDecrypt(salt: ByteArray, iv: ByteArray, cipherText: ByteArray,
                       password: String, keyBits: Int, iters: Int, prf: String): ByteArray? {
    val key = try {
        val spec = PBEKeySpec(password.toCharArray(), salt, iters, keyBits)
        SecretKeyFactory.getInstance(prf).generateSecret(spec).encoded
    } catch (_: Throwable) { return null }
    val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
    return runCatching {
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        aes.doFinal(cipherText)
    }.getOrNull()
}

/** Sweep reasonable PBKDF2 parameter combinations. */
private val PARAM_SETS = listOf(
    Triple(128, 100,   "PBKDF2WithHmacSHA1"),    // ES3 default
    Triple(256, 100,   "PBKDF2WithHmacSHA1"),    // ES3 256-bit option
    Triple(128, 1000,  "PBKDF2WithHmacSHA1"),
    Triple(256, 1000,  "PBKDF2WithHmacSHA1"),
    Triple(128, 10000, "PBKDF2WithHmacSHA1"),
    Triple(256, 10000, "PBKDF2WithHmacSHA1"),
    Triple(128, 100,   "PBKDF2WithHmacSHA256"),
    Triple(256, 100,   "PBKDF2WithHmacSHA256"),
    Triple(128, 1000,  "PBKDF2WithHmacSHA256"),
    Triple(256, 1000,  "PBKDF2WithHmacSHA256"),
)

private fun looksPlausible(b: ByteArray): Boolean {
    if (b.size < 4) return false
    // ES3 wraps either a JSON-style header ({"...":...) or its binary
    // tag stream that starts with non-zero ASCII or specific tag bytes.
    val sample = b.take(minOf(b.size, 256))
    val printable = sample.count {
        val c = it.toInt() and 0xFF
        c == 9 || c == 10 || c == 13 || c in 32..126
    }
    return printable.toDouble() / sample.size > 0.6
}

private fun extractCandidates(metadata: ByteArray): Set<String> {
    val out = LinkedHashSet<String>()
    var start = -1
    for (i in metadata.indices) {
        val c = metadata[i].toInt() and 0xFF
        val printableNonSpace = c in 0x21..0x7E
        if (printableNonSpace) {
            if (start < 0) start = i
        } else {
            if (start >= 0 && i - start in 4..32) {
                val s = String(metadata, start, i - start, Charsets.US_ASCII)
                // Filter out obvious non-password junk
                if (s.none { it == '<' || it == '>' || it == '{' || it == '}' } &&
                    !s.contains("System.") && !s.contains("UnityEngine.") &&
                    !s.contains(".dll") && !s.contains("/") && !s.contains("\\") &&
                    !s.startsWith("get_") && !s.startsWith("set_") &&
                    !s.startsWith("op_") && !s.startsWith("_ZN")) {
                    out.add(s)
                }
            }
            start = -1
        }
    }
    return out
}

fun main(args: Array<String>) {
    if (args.size < 2) error("usage: java -jar es3brute.jar <encrypted.es3> <metadata-or-libil2cpp.so>")
    val blob = File(args[0]).readBytes()
    require(blob.size > 32) { "file too small" }
    // Probe common ES3 header layouts: pick whichever makes the ciphertext a
    // multiple of 16 (AES-CBC requirement). Older ES3 = 8B salt + 16B IV;
    // newer = 16 + 16; some pre-PBKDF2 builds are just 16B IV.
    data class Layout(val saltSize: Int, val ivSize: Int)
    val layouts = listOf(Layout(8, 16), Layout(16, 16), Layout(0, 16))
    val pick = layouts.firstOrNull { (blob.size - it.saltSize - it.ivSize) % 16 == 0 }
        ?: error("no ES3-shaped header makes ciphertext block-aligned (size ${blob.size})")
    val salt = blob.copyOfRange(0, pick.saltSize)
    val iv = blob.copyOfRange(pick.saltSize, pick.saltSize + pick.ivSize)
    val cipherText = blob.copyOfRange(pick.saltSize + pick.ivSize, blob.size)
    println("encrypted: ${blob.size}B, header=${pick.saltSize}+${pick.ivSize}, ciphertext=${cipherText.size}B")

    val metaPath = args[1]
    val meta = File(metaPath).readBytes()
    println("scanning $metaPath: ${meta.size}B")

    val candidates = LinkedHashSet<String>().apply {
        addAll(PRIORITY_GUESSES)
        addAll(extractCandidates(meta))
    }
    println("trying ${candidates.size} password candidates…")

    var tried = 0
    for ((keyBits, iters, prf) in PARAM_SETS) {
        println("  · trying ${candidates.size} pwds × AES-$keyBits / $iters iters / $prf …")
        for (pw in candidates) {
            tried++
            val plain = tryDecrypt(salt, iv, cipherText, pw, keyBits, iters, prf) ?: continue
            if (!looksPlausible(plain)) continue
            println("\n✓ HIT after $tried tries: password='$pw', AES-$keyBits, $iters iters, $prf")
            println("  decrypted ${plain.size}B")
            val text = runCatching { String(plain, Charsets.UTF_8) }.getOrNull()
            if (text != null && text.all { it.code in 9..0x7e || it.code >= 0x80 }) {
                println("  plaintext head: ${text.take(400)}")
            } else {
                println("  binary head: ${plain.take(64).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}")
            }
            return
        }
    }
    println("\nNo candidate decrypted across $tried trials. Game likely uses runtime-derived keys, or a non-AES algorithm.")
}

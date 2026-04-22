// Try Easy Save 3's default-password decryption on a captured save.
// ES3 default: AES-128-CBC, PBKDF2-SHA1, 100 iterations, password "ironcrypt".
// Format on disk: 8-byte salt | 16-byte IV | ciphertext (PKCS7 padded).
//
//   kotlinc parser_test/Es3Try.kt -include-runtime -d /tmp/es3try.jar
//   java -jar /tmp/es3try.jar path/to/encrypted.es3

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val DEFAULT_PASSWORDS = listOf(
    "ironcrypt",          // Easy Save 3 default
    "JinSaltJoint",       // sometimes seen in tutorials
    "password",           // lazy default
    "easysave",           // common alt
    "EasySave3",
    "secretkey",
)

private fun deriveKey(password: String, salt: ByteArray, iters: Int = 100, keyBits: Int = 128): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iters, keyBits)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    return factory.generateSecret(spec).encoded
}

private fun tryDecrypt(blob: ByteArray, password: String): ByteArray? {
    if (blob.size <= 24) return null
    val salt = blob.copyOfRange(0, 8)
    val iv = blob.copyOfRange(8, 24)
    val cipherText = blob.copyOfRange(24, blob.size)
    val key = deriveKey(password, salt)
    val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
    aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return runCatching { aes.doFinal(cipherText) }.getOrNull()
}

private fun looksLikeText(b: ByteArray): Boolean {
    if (b.isEmpty()) return false
    val sample = b.take(minOf(b.size, 256))
    var ok = 0
    for (x in sample) {
        val c = x.toInt() and 0xFF
        if (c == 0) return false
        if (c == 9 || c == 10 || c == 13 || c in 32..126) ok++
    }
    return ok.toDouble() / sample.size > 0.9
}

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("usage: java -jar es3try.jar path/to/encrypted.es3")
    val blob = File(path).readBytes()
    println("file: ${blob.size} bytes")
    println("first 24 bytes (salt|iv): ${blob.take(24).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}")

    for (pw in DEFAULT_PASSWORDS) {
        val plain = tryDecrypt(blob, pw)
        if (plain != null) {
            println("\n✓ DECRYPTED with password '$pw' → ${plain.size} bytes")
            val text = if (looksLikeText(plain)) String(plain, Charsets.UTF_8) else null
            if (text != null) {
                println("Plaintext (first 600 chars):\n${text.take(600)}")
            } else {
                println("Plaintext is binary. First 64 bytes:")
                println(plain.take(64).joinToString(" ") { "%02x".format(it.toInt() and 0xff) })
            }
            return
        } else {
            println("× '$pw' didn't decrypt (or padding error)")
        }
    }
    println("\nNone of the default passwords worked. APK key extraction is the next step.")
}

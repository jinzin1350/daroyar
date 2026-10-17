package com.daroyar.app

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing code is the only secret. From it we derive:
 *  - a topic id (so the broker topic does not reveal the code)
 *  - an AES-256-GCM key (so the public broker only ever sees ciphertext)
 */
object Crypto {
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LEN = 20
    private val rnd = SecureRandom()
    private val CODE_RE = Regex("[A-Z2-9]{4}(-[A-Z2-9]{4}){4}")

    fun newCode(): String {
        val sb = StringBuilder(LEN)
        repeat(LEN) { sb.append(ALPHA[rnd.nextInt(ALPHA.length)]) }
        return sb.toString()
    }

    fun normalize(input: String): String = input.uppercase().filter { it in ALPHA }

    fun isValid(code: String): Boolean = normalize(code).length == LEN

    /** Finds a code inside a pasted message, e.g. one shared from the parent phone. */
    fun extract(text: String): String? {
        val m = CODE_RE.find(text.uppercase())
        if (m != null) return normalize(m.value)
        val n = normalize(text)
        return if (n.length == LEN) n else null
    }

    fun pretty(code: String): String = normalize(code).chunked(4).joinToString("-")

    private fun sha256(label: String, code: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(label.toByteArray(Charsets.UTF_8))
        md.update(normalize(code).toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    fun topicId(code: String): String {
        val h = sha256("daroyar/topic/v1/", code)
        val sb = StringBuilder()
        for (i in 0 until 12) sb.append(((h[i].toInt() and 0xff) + 0x100).toString(16).substring(1))
        return sb.toString()
    }

    private fun key(code: String) = SecretKeySpec(sha256("daroyar/key/v1/", code), "AES")

    fun encrypt(code: String, plain: String): ByteArray {
        val iv = ByteArray(12)
        rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = 1
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return out
    }

    fun decrypt(code: String, data: ByteArray): String? {
        try {
            if (data.size < 1 + 12 + 16 || data[0].toInt() != 1) return null
            val iv = data.copyOfRange(1, 13)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, iv))
            val plain = c.doFinal(data, 13, data.size - 13)
            return String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        }
    }
}

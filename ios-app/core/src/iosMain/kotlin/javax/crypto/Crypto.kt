// javax.crypto for iOS: AES (CBC, ECB, GCM) over CommonCrypto, and HMAC-SHA256.
package javax.crypto

import java.security.GeneralSecurityException
import java.security.Key
import java.security.NoSuchAlgorithmException
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import kotlinx.cinterop.*
import platform.CoreCrypto.*

interface SecretKey : Key
class AEADBadTagException(message: String?) : GeneralSecurityException(message)
class BadPaddingException(message: String?) : GeneralSecurityException(message)

internal fun aesBlocks(encrypt: Boolean, key: ByteArray, iv: ByteArray?, input: ByteArray, padding: Boolean): ByteArray {
    val out = ByteArray(input.size + 16)
    val moved = ULongArray(1)
    val options = (if (iv == null) kCCOptionECBMode else 0u) or (if (padding) kCCOptionPKCS7Padding else 0u)
    val src = if (input.isEmpty()) byteArrayOf(0) else input
    val status = key.usePinned { k -> src.usePinned { i -> out.usePinned { o -> moved.usePinned { m ->
        val ivPin = iv?.let { it.pin() }
        try {
            CCCrypt(if (encrypt) kCCEncrypt else kCCDecrypt, kCCAlgorithmAES, options, k.addressOf(0), key.size.convert(),
                ivPin?.addressOf(0), i.addressOf(0), input.size.convert(), o.addressOf(0), out.size.convert(), m.addressOf(0).reinterpret())
        } finally { ivPin?.unpin() }
    } } } }
    if (status != kCCSuccess) throw if (padding && !encrypt) BadPaddingException("Bad padding ($status)") else GeneralSecurityException("AES failed ($status)")
    return out.copyOf(moved[0].toInt())
}

class Cipher private constructor(private val mode: String, private val padding: Boolean) {
    private var encrypt = true
    private lateinit var key: ByteArray
    private var iv: ByteArray? = null
    private var tagBits = 128
    private val aad = java.io.ByteArrayOutputStream()
    private val buffer = java.io.ByteArrayOutputStream()

    fun init(opmode: Int, key: Key) = init(opmode, key, null)
    fun init(opmode: Int, key: Key, params: AlgorithmParameterSpec?) {
        encrypt = opmode == ENCRYPT_MODE
        this.key = key.encoded ?: throw GeneralSecurityException("Key has no bytes")
        when (params) {
            is IvParameterSpec -> iv = params.iv
            is GCMParameterSpec -> { iv = params.iv; tagBits = params.tLen }
            null -> iv = if (mode == "ECB") null else ByteArray(16)
        }
        aad.reset(); buffer.reset()
    }
    fun updateAAD(b: ByteArray) = aad.write(b, 0, b.size)
    fun update(input: ByteArray): ByteArray = update(input, 0, input.size)
    fun update(input: ByteArray, off: Int, len: Int): ByteArray {
        if (mode == "GCM") { buffer.write(input, off, len); return ByteArray(0) }
        // CBC and ECB without padding are block-aligned in the shared code: process whole blocks now.
        buffer.write(input, off, len)
        val all = buffer.toByteArray(); val whole = if (padding) maxOf(0, (all.size - 1) / 16 * 16) else all.size / 16 * 16
        if (whole == 0) return ByteArray(0)
        buffer.reset(); buffer.write(all, whole, all.size - whole)
        val chunk = all.copyOf(whole)
        val result = aesBlocks(encrypt, key, iv, chunk, false)
        if (mode == "CBC") iv = (if (encrypt) result else chunk).copyOfRange(whole - 16, whole)
        return result
    }
    fun update(input: ByteArray, off: Int, len: Int, output: ByteArray, outOff: Int): Int {
        val r = update(input, off, len); r.copyInto(output, outOff); return r.size
    }
    fun doFinal(): ByteArray = doFinal(ByteArray(0))
    fun doFinal(input: ByteArray): ByteArray = doFinal(input, 0, input.size)
    fun doFinal(input: ByteArray, off: Int, len: Int): ByteArray {
        buffer.write(input, off, len)
        val all = buffer.toByteArray(); buffer.reset()
        return when (mode) {
            "GCM" -> Gcm(key, iv!!, tagBits / 8).let { if (encrypt) it.seal(all, aad.toByteArray()) else it.open(all, aad.toByteArray()) }.also { aad.reset() }
            else -> {
                require(padding || all.size % 16 == 0) { "Input length not multiple of 16 bytes" }
                aesBlocks(encrypt, key, iv, all, padding)
            }
        }
    }
    fun doFinal(input: ByteArray, off: Int, len: Int, output: ByteArray, outOff: Int): Int {
        val r = doFinal(input, off, len); r.copyInto(output, outOff); return r.size
    }
    val blockSize get() = 16

    companion object {
        const val ENCRYPT_MODE = 1
        const val DECRYPT_MODE = 2
        fun getInstance(transformation: String): Cipher {
            val parts = transformation.split('/')
            if (parts[0] != "AES") throw NoSuchAlgorithmException(transformation)
            val mode = parts.getOrElse(1) { "ECB" }
            val padding = parts.getOrElse(2) { "PKCS5Padding" }.let { it == "PKCS5Padding" || it == "PKCS7Padding" }
            if (mode !in setOf("CBC", "ECB", "GCM")) throw NoSuchAlgorithmException(transformation)
            return Cipher(mode, padding && mode != "GCM")
        }
    }
}

/** AES-GCM (NIST SP 800-38D) from AES-ECB blocks, since CommonCrypto has no public GCM. */
internal class Gcm(private val key: ByteArray, private val iv: ByteArray, private val tagLen: Int) {
    private fun block(b: ByteArray) = aesBlocks(true, key, null, b, false)
    private val h = block(ByteArray(16))

    private fun mul(x: ByteArray, y: ByteArray): ByteArray {
        val z = ByteArray(16); val v = y.copyOf()
        for (i in 0 until 128) {
            if ((x[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) for (j in 0 until 16) z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
            val lsb = v[15].toInt() and 1
            for (j in 15 downTo 1) v[j] = (((v[j].toInt() and 0xff) ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
            v[0] = ((v[0].toInt() and 0xff) ushr 1).toByte()
            if (lsb == 1) v[0] = (v[0].toInt() xor 0xe1).toByte()
        }
        return z
    }
    private fun ghash(aad: ByteArray, c: ByteArray): ByteArray {
        var y = ByteArray(16)
        fun absorb(data: ByteArray) {
            var i = 0
            while (i < data.size) {
                val n = minOf(16, data.size - i)
                for (j in 0 until n) y[j] = (y[j].toInt() xor data[i + j].toInt()).toByte()
                y = mul(y, h); i += 16
            }
        }
        absorb(aad); absorb(c)
        val lens = java.nio.ByteBuffer.allocate(16).putLong(aad.size * 8L).putLong(c.size * 8L).array()
        for (j in 0 until 16) y[j] = (y[j].toInt() xor lens[j].toInt()).toByte()
        return mul(y, h)
    }
    private fun j0(): ByteArray = if (iv.size == 12) iv + byteArrayOf(0, 0, 0, 1) else ghash(ByteArray(0), iv)
    private fun inc(c: ByteArray) { for (i in 15 downTo 12) { c[i] = (c[i] + 1).toByte(); if (c[i] != 0.toByte()) break } }
    private fun ctr(start: ByteArray, data: ByteArray): ByteArray {
        val counter = start.copyOf(); val out = ByteArray(data.size)
        val blocks = (data.size + 15) / 16
        val stream = ByteArray(blocks * 16)
        for (b in 0 until blocks) { inc(counter); counter.copyInto(stream, b * 16) }
        val ks = if (blocks == 0) ByteArray(0) else block(stream)
        for (i in data.indices) out[i] = (data[i].toInt() xor ks[i].toInt()).toByte()
        return out
    }
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val j = j0(); val c = ctr(j, plain)
        val s = ghash(aad, c); val ej = block(j)
        val tag = ByteArray(tagLen) { (s[it].toInt() xor ej[it].toInt()).toByte() }
        return c + tag
    }
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < tagLen) throw AEADBadTagException("Input too short")
        val c = sealed.copyOf(sealed.size - tagLen); val tag = sealed.copyOfRange(sealed.size - tagLen, sealed.size)
        val j = j0(); val s = ghash(aad, c); val ej = block(j)
        var diff = 0
        for (i in 0 until tagLen) diff = diff or ((s[i].toInt() xor ej[i].toInt()) xor tag[i].toInt())
        if (diff != 0) throw AEADBadTagException("Tag mismatch")
        return ctr(j, c)
    }
}

class Mac private constructor() {
    private lateinit var key: ByteArray
    private val data = java.io.ByteArrayOutputStream()
    fun init(key: Key) { this.key = key.encoded ?: ByteArray(0); data.reset() }
    fun update(b: ByteArray) = data.write(b, 0, b.size)
    fun doFinal(b: ByteArray): ByteArray { update(b); return doFinal() }
    fun doFinal(): ByteArray {
        val input = data.toByteArray(); data.reset()
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        val k = if (key.isEmpty()) byteArrayOf(0) else key; val i = if (input.isEmpty()) byteArrayOf(0) else input
        k.usePinned { kp -> i.usePinned { ip -> out.usePinned { op ->
            CCHmac(kCCHmacAlgSHA256, kp.addressOf(0), key.size.convert(), ip.addressOf(0), input.size.convert(), op.addressOf(0))
        } } }
        return out
    }
    companion object {
        fun getInstance(algorithm: String): Mac { if (algorithm != "HmacSHA256") throw NoSuchAlgorithmException(algorithm); return Mac() }
    }
}

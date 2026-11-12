// java.security over CommonCrypto and the Security framework, for iOS.
package java.security

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

open class GeneralSecurityException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
class NoSuchAlgorithmException(message: String?) : GeneralSecurityException(message)
class InvalidKeyException(message: String?) : GeneralSecurityException(message)
class SignatureException(message: String?) : GeneralSecurityException(message)

interface Key { val algorithm: String; val encoded: ByteArray?; fun getAlgorithm() = algorithm; fun getEncoded() = encoded }
interface PrivateKey : Key
interface PublicKey : Key

class MessageDigest private constructor(private val algorithm: String) {
    private val data = java.io.ByteArrayOutputStream()
    fun update(b: Byte) = data.write(b.toInt())
    fun update(b: ByteArray) = data.write(b, 0, b.size)
    fun update(b: ByteArray, off: Int, len: Int) = data.write(b, off, len)
    fun digest(b: ByteArray): ByteArray { update(b); return digest() }
    fun digest(): ByteArray {
        val input = data.toByteArray(); data.reset()
        return hash(algorithm, input)
    }
    fun reset() = data.reset()
    companion object {
        fun getInstance(algorithm: String): MessageDigest {
            val a = algorithm.uppercase().replace("-", "")
            if (a !in setOf("SHA1", "SHA256", "SHA512", "MD5")) throw NoSuchAlgorithmException(algorithm)
            return MessageDigest(a)
        }
        internal fun hash(algorithm: String, input: ByteArray): ByteArray {
            val size = when (algorithm) { "SHA1" -> CC_SHA1_DIGEST_LENGTH; "SHA256" -> CC_SHA256_DIGEST_LENGTH; "SHA512" -> CC_SHA512_DIGEST_LENGTH; else -> CC_MD5_DIGEST_LENGTH }
            val out = UByteArray(size)
            val src = if (input.isEmpty()) byteArrayOf(0) else input
            src.usePinned { i -> out.usePinned { o ->
                val p = i.addressOf(0); val len = input.size.convert<CC_LONG>()
                when (algorithm) {
                    "SHA1" -> CC_SHA1(p, len, o.addressOf(0))
                    "SHA256" -> CC_SHA256(p, len, o.addressOf(0))
                    "SHA512" -> CC_SHA512(p, len, o.addressOf(0))
                    else -> @Suppress("DEPRECATION") CC_MD5(p, len, o.addressOf(0))
                }
            } }
            return out.asByteArray()
        }
    }
}

open class SecureRandom {
    fun nextBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val rc = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0)) }
        check(rc == errSecSuccess) { "SecRandomCopyBytes failed: $rc" }
    }
    fun nextInt(bound: Int): Int = kotlin.random.Random.nextInt(bound)
    fun nextInt(): Int = ByteArray(4).also(::nextBytes).let { (it[0].toInt() shl 24) or ((it[1].toInt() and 0xff) shl 16) or ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff) }
    fun nextLong(): Long = (nextInt().toLong() shl 32) or (nextInt().toLong() and 0xffffffffL)
}

/** An RSA private key held as PKCS#1 DER, the form SecKeyCreateWithData reads. */
class RsaPrivateKey internal constructor(internal val pkcs1: ByteArray) : PrivateKey {
    override val algorithm = "RSA"
    override val encoded: ByteArray? = null
}

class KeyFactory private constructor() {
    fun generatePrivate(spec: java.security.spec.KeySpec): PrivateKey = when (spec) {
        is java.security.spec.PKCS8EncodedKeySpec -> RsaPrivateKey(unwrapPkcs8(spec.encoded))
        else -> throw InvalidKeyException("Unsupported key spec")
    }
    companion object {
        fun getInstance(algorithm: String): KeyFactory { if (algorithm != "RSA") throw NoSuchAlgorithmException(algorithm); return KeyFactory() }

        /** PrivateKeyInfo ::= SEQUENCE { INTEGER, AlgorithmIdentifier, OCTET STRING { RSAPrivateKey } } */
        private fun unwrapPkcs8(der: ByteArray): ByteArray {
            var p = 0
            fun header(expected: Int): Int {
                require(der[p].toInt() and 0xff == expected) { "Bad key" }; p++
                var len = der[p++].toInt() and 0xff
                if (len and 0x80 != 0) { val n = len and 0x7f; len = 0; repeat(n) { len = (len shl 8) or (der[p++].toInt() and 0xff) } }
                return len
            }
            header(0x30)
            p += header(0x02)
            p += header(0x30)
            val len = header(0x04)
            return der.copyOfRange(p, p + len)
        }
    }
}

class Signature private constructor() {
    private var key: RsaPrivateKey? = null
    private val data = java.io.ByteArrayOutputStream()
    fun initSign(privateKey: PrivateKey) { key = privateKey as? RsaPrivateKey ?: throw InvalidKeyException("Not an RSA key"); data.reset() }
    fun update(b: ByteArray) = data.write(b, 0, b.size)
    fun sign(): ByteArray = memScoped {
        val k = key ?: throw SignatureException("not initialized")
        val attrs = CFDictionaryCreateMutable(null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeRSA)
        CFDictionaryAddValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
        val keyData = k.pkcs1.toNSData()
        val error = alloc<CFErrorRefVar>()
        val secKey = SecKeyCreateWithData(CFBridgingRetain(keyData) as CFDataRef, attrs, error.ptr)
            ?: throw InvalidKeyException("The device key could not be read")
        val message = data.toByteArray().toNSData()
        val sig = SecKeyCreateSignature(secKey, kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256, CFBridgingRetain(message) as CFDataRef, error.ptr)
            ?: throw SignatureException("Signing failed")
        val result = (CFBridgingRelease(sig) as NSData).toByteArray()
        CFRelease(secKey); CFRelease(attrs)
        data.reset()
        result
    }
    companion object {
        fun getInstance(algorithm: String): Signature { if (algorithm != "SHA256withRSA") throw NoSuchAlgorithmException(algorithm); return Signature() }
    }
}

internal fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }
internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt(); if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
    return out
}

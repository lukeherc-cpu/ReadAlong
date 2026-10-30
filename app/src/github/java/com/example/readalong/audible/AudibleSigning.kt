package com.example.readalong.audible

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Audible's request signing: SHA256withRSA over "method\npath?query\ndate\nbody\nadpToken" with the
 * private key Amazon issued this device. Ported from LibriSync's native/rust-core/src/api/signing.rs.
 */
internal object AudibleSigning {
    fun headers(account: AudibleAccount, method: String, pathAndQuery: String, body: String = "", now: Date = Date()): Map<String, String> {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey(account.devicePrivateKey))
        signer.update("$method\n$pathAndQuery\n$date\n$body\n${account.adpToken}".toByteArray())
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        return mapOf("x-adp-token" to account.adpToken, "x-adp-alg" to "SHA256withRSA:1.0", "x-adp-signature" to "$signature:$date")
    }

    /** Amazon issues a PKCS#1 PEM ("BEGIN RSA PRIVATE KEY"); Java only reads PKCS#8, so PKCS#1 is wrapped first. */
    fun privateKey(pem: String): PrivateKey {
        val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString("").replace("\\n", ""))
        val pkcs8 = if ("BEGIN RSA PRIVATE KEY" in pem) wrapPkcs1(der) else der
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        // SEQUENCE { INTEGER 0, SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }, OCTET STRING { pkcs1 } }
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val content = byteArrayOf(0x02, 0x01, 0x00) + algorithm + der(0x04, pkcs1)
        return der(0x30, content)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag)
        val size = content.size
        when {
            size < 0x80 -> write(size)
            size < 0x100 -> { write(0x81); write(size) }
            size < 0x10000 -> { write(0x82); write(size shr 8); write(size and 0xff) }
            else -> { write(0x83); write(size shr 16); write((size shr 8) and 0xff); write(size and 0xff) }
        }
        write(content)
    }.toByteArray()
}

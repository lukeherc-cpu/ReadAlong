package com.example.readalong.audible

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Audible engine shared with the phone: everything that can be checked without an Audible account. */
class AudibleEngineTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pkcs1Pem: String = run {
        // A PKCS#8 RSA key is a fixed 26-byte header around the PKCS#1 key Amazon sends.
        val pkcs1 = keys.private.encoded.copyOfRange(26, keys.private.encoded.size)
        "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1) + "\n-----END RSA PRIVATE KEY-----\n"
    }
    private val account = AudibleAccount(AudibleLocale.UK, "amzn1.account.TEST", "Test", "SERIAL1234", AudibleAuth.DEVICE_TYPE,
        "{enc:token}", pkcs1Pem, "access", 0, "refresh")

    @Test fun signsTheDocumentedStringWithTheDeviceKey() {
        val headers = AudibleSigning.headers(account, "POST", "/1.0/content/B0TEST/licenserequest", "{\"a\":1}", Date(1_788_000_000_000))
        val (signature, date) = headers.getValue("x-adp-signature").split(':', limit = 2)
        assertEquals("2026-08-29T10:40:00.000Z", date)
        assertEquals("SHA256withRSA:1.0", headers["x-adp-alg"])
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(keys.public) }
        verifier.update("POST\n/1.0/content/B0TEST/licenserequest\n$date\n{\"a\":1}\n{enc:token}".toByteArray())
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)))
    }

    @Test fun loginUrlCarriesTheDeviceAndChallenge() {
        val login = AudibleAuth.startLogin(AudibleLocale.UK)
        assertEquals(40, login.deviceSerial.length)
        assertTrue(login.url.startsWith("https://www.amazon.co.uk/ap/signin?"))
        assertTrue("amzn_audible_ios_uk" in login.url)
        assertTrue(AudibleAuth.clientId(login.deviceSerial) in login.url)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(login.codeVerifier.toByteArray()))
        assertTrue("code_challenge=$challenge" in login.url)
        assertEquals("53455249414c23413243", AudibleAuth.clientId("SERIAL").take(20))
    }

    @Test fun readsTheCodeFromAmazonsFinalRedirect() {
        val redirect = "https://www.amazon.com/ap/maplanding?openid.assoc_handle=amzn_audible_ios_us&openid.oa2.authorization_code=ANcode%2B1&openid.mode=id_res"
        assertTrue(AudibleAuth.isFinalRedirect(redirect))
        assertEquals("ANcode+1", AudibleAuth.authorizationCode(redirect))
        assertFailsWith<AudibleException> { AudibleAuth.authorizationCode("https://www.amazon.com/ap/maplanding?error=access_denied") }
        assertFailsWith<AudibleException> { AudibleAuth.authorizationCode("not a url") }
    }

    @Test fun accountsSurviveStorage() {
        val stored = AudibleAccount.fromJson(account.copy(activationBytes = "1a2b3c4d").toJson())
        assertEquals(account.copy(activationBytes = "1a2b3c4d"), stored)
        assertTrue("token" !in stored.toString())
    }

    @Test fun decryptsTheLicenceVoucher() {
        val hash = MessageDigest.getInstance("SHA-256").digest("${account.deviceType}${account.deviceSerial}${account.customerId}B0TEST".toByteArray())
        val voucher = """{"key":"00112233445566778899aabbccddeeff","iv":"ffeeddccbbaa99887766554433221100","refreshDate":"x"}""".toByteArray()
        val padded = voucher.copyOf((voucher.size + 15) / 16 * 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(hash.copyOf(16), "AES"), IvParameterSpec(hash.copyOfRange(16, 32))) }
        val json = AudibleClient(account).decryptVoucher(Base64.getEncoder().encodeToString(cipher.doFinal(padded)), "B0TEST")
        assertEquals("00112233445566778899aabbccddeeff", json.getString("key"))
    }

    @Test fun readsLibraryItems() {
        val item = JSONObject("""{"asin":"B0PARENT","title":"Long Book","content_delivery_type":"MultiPartBook","runtime_length_min":900,
            "authors":[{"name":"A. Writer"},{"name":"B. Writer"}],"narrators":[{"name":"N. Reader"}],"product_images":{"500":"https://img/500.jpg"},
            "relationships":[{"asin":"B0P2","relationship_to_product":"child","relationship_type":"component","sort":"2"},
                             {"asin":"B0P1","relationship_to_product":"child","relationship_type":"component","sort":"1"},
                             {"asin":"B0SER","relationship_to_product":"parent","relationship_type":"series","sort":"1"}]}""")
        val book = AudibleClient.parseBook(item)!!
        assertEquals("A. Writer, B. Writer", book.authors)
        assertEquals(listOf("B0P1", "B0P2"), book.downloadAsins)
        assertEquals(listOf(book), AudibleBook.listFromJson(AudibleBook.listToJson(listOf(book))))
        assertEquals(null, AudibleClient.parseBook(JSONObject("""{"asin":"B0POD","content_delivery_type":"PodcastParent"}""")))
        assertEquals("Book One Part 1 of 2", AudibleDownloader.safeName("Book: One / Part 1 of 2?"))
    }

    @Test fun unlocksAnAaxcFileInPlace() {
        val key = Random(1).nextBytes(16)
        val iv = Random(2).nextBytes(16)
        val samples = listOf(37, 16, 5, 64, 33).map { Random(it).nextBytes(it) }
        val file = File.createTempFile("book", ".aaxc").apply { deleteOnExit() }
        file.writeBytes(audibleMp4(samples, key, iv, adrm = null))
        AudibleDecrypter.decrypt(file, AudibleLicense("", AudibleLicense.Format.AAXC, key, iv))
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("mp4a" in text && "aavd" !in text && text.substring(8, 12) == "M4B ")
        assertContentEquals(samples.reduce(ByteArray::plus), bytes.copyOfRange(bytes.size - samples.sumOf { it.size }, bytes.size))
    }

    @Test fun derivesTheAaxKeyFromActivationBytes() {
        val activation = byteArrayOf(0x1a, 0x2b, 0x3c, 0x4d)
        val fileKey = Random(3).nextBytes(16)
        val adrm = adrmBox(activation, fileKey)
        val (key, iv) = AudibleDecrypter.aaxKey(adrm, activation)
        assertContentEquals(fileKey, key)
        assertEquals(16, iv.size)
        assertFailsWith<AudibleException> { AudibleDecrypter.aaxKey(adrm, byteArrayOf(1, 2, 3, 4)) }

        // And a whole AAX file decrypts with them.
        val samples = listOf(40, 7, 128).map { Random(it).nextBytes(it) }
        val file = File.createTempFile("book", ".aax").apply { deleteOnExit() }
        file.writeBytes(audibleMp4(samples, key, iv, adrm))
        AudibleDecrypter.decrypt(file, AudibleLicense("", AudibleLicense.Format.AAX, activationBytes = activation))
        val bytes = file.readBytes()
        assertContentEquals(samples.reduce(ByteArray::plus), bytes.copyOfRange(bytes.size - samples.sumOf { it.size }, bytes.size))
        assertTrue("adrm" !in String(bytes, Charsets.ISO_8859_1))
    }

    @Test fun refusesAnUnencryptedFile() {
        val file = File.createTempFile("book", ".m4b").apply { deleteOnExit(); writeBytes(box("ftyp", "M4B ".toByteArray() + ByteArray(4)) + box("moov", ByteArray(0))) }
        assertFailsWith<java.io.IOException> { AudibleDecrypter.decrypt(file, AudibleLicense("", AudibleLicense.Format.AAXC, ByteArray(16), ByteArray(16))) }
    }

    // A real AAX "adrm" payload: 8 bytes, the 56-byte encrypted blob, 4 bytes, then the 20-byte checksum.
    private fun adrmBox(activation: ByteArray, fileKey: ByteArray): ByteArray {
        val fixed = "77214d4b196a87cd520045fd20a51d67".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun sha1(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-1").run { parts.forEach(::update); digest() }
        val intermediateKey = sha1(fixed, activation)
        val intermediateIv = sha1(fixed, intermediateKey, activation)
        val plain = ByteArray(48).also { blob ->
            for (i in 0 until 4) blob[3 - i] = activation[i]
            fileKey.copyInto(blob, 8)
            Random(4).nextBytes(16).copyInto(blob, 26)
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(intermediateKey.copyOf(16), "AES"), IvParameterSpec(intermediateIv.copyOf(16))) }
        return ByteArray(8) + cipher.doFinal(plain) + ByteArray(8) + ByteArray(4) + sha1(intermediateKey.copyOf(16), intermediateIv.copyOf(16))
    }

    private fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
    private fun ints(vararg values: Int) = ByteBuffer.allocate(4 * values.size).apply { values.forEach(::putInt) }.array()

    /** An Audible-style MP4: an "aavd" track whose samples are encrypted the way Audible does it. */
    private fun audibleMp4(samples: List<ByteArray>, key: ByteArray, iv: ByteArray, adrm: ByteArray?): ByteArray {
        val chunks = listOf(samples.take(2), samples.drop(2).take(2), samples.drop(4)).filter { it.isNotEmpty() }
        fun moov(offsets: IntArray): ByteArray {
            val audioFields = ByteArray(6) + byteArrayOf(0, 1) + ByteArray(8) + byteArrayOf(0, 2, 0, 16, 0, 0, 0, 0) + ints(44100 shl 16)
            val entry = box("aavd", audioFields + box("esds", ByteArray(12)) + (adrm?.let { box("adrm", it) } ?: ByteArray(0)))
            val stbl = box("stbl", box("stsd", ints(0, 1) + entry) + box("stsz", ints(0, 0, samples.size, *samples.map { it.size }.toIntArray())) +
                box("stsc", ints(0, 2, 1, 2, 1, chunks.size, 1, 1)) + box("stco", ints(0, offsets.size, *offsets)))
            return box("moov", box("trak", box("mdia", box("minf", stbl))))
        }
        val ftyp = box("ftyp", "aax ".toByteArray() + ByteArray(4) + "aax M4B ".toByteArray())
        val mdatStart = ftyp.size + moov(IntArray(chunks.size)).size + 8
        val offsets = chunks.runningFold(mdatStart) { at, chunk -> at + chunk.sumOf { it.size } }.dropLast(1).toIntArray()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val media = ByteArrayOutputStream()
        for (sample in samples) {
            val whole = sample.size / 16 * 16
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            media.write(if (whole > 0) cipher.doFinal(sample, 0, whole) else ByteArray(0))
            media.write(sample, whole, sample.size - whole)
        }
        return ftyp + moov(offsets) + box("mdat", media.toByteArray())
    }
}

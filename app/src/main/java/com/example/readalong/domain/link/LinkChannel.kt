package com.example.readalong.domain.link

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted conversation between a linked phone and computer over the home network.
 *
 * Both sides hold the same 32-byte key, handed over once in the pairing QR code. Each connection opens
 * with a plain greeting ([MAGIC], the key's [keyId] and a random nonce from each side), and both derive
 * a fresh session key from the shared key and the two nonces. Every frame after that is AES-GCM with a
 * per-direction counter as its nonce, so a frame can't be read, altered, replayed or reordered without
 * the key. Shared by the phone (client) and ReadAlong Desktop (server).
 */
class LinkChannel private constructor(private val input: DataInputStream, private val output: DataOutputStream, sessionKey: ByteArray, private val client: Boolean) {
    private val key = SecretKeySpec(sessionKey, "AES")
    private var sent = 0L
    private var received = 0L

    fun send(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        require(length <= MAX_FRAME) { "Frame too large." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(if (client) 1 else 2, sent++)))
        val sealed = cipher.doFinal(bytes, offset, length)
        output.writeInt(sealed.size); output.write(sealed); output.flush()
    }

    fun receive(): ByteArray {
        val size = input.readInt()
        if (size < 16 || size > MAX_FRAME + 16) throw IOException("The other device sent a damaged message.")
        val sealed = ByteArray(size).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(if (client) 2 else 1, received++)))
        return try { cipher.doFinal(sealed) } catch (_: javax.crypto.AEADBadTagException) { throw IOException("The other device isn't linked with this key.") }
    }

    fun sendJson(json: JSONObject) = send(json.toString().toByteArray(Charsets.UTF_8))
    fun receiveJson(): JSONObject = JSONObject(String(receive(), Charsets.UTF_8)).also { reply ->
        // An error reply ends the request on both sides; the message is meant for people.
        if (reply.has("error")) throw LinkException(reply.getString("error"))
    }

    /** Streams [size] bytes from [source] as frames. */
    fun sendStream(source: InputStream, size: Long, onProgress: (Long) -> Unit = {}) {
        val buffer = ByteArray(CHUNK); var left = size
        while (left > 0) {
            val read = source.read(buffer, 0, minOf(CHUNK.toLong(), left).toInt())
            if (read < 0) throw IOException("The file got shorter while it was being sent.")
            send(buffer, 0, read); left -= read; onProgress(size - left)
        }
    }

    /** Receives [size] bytes of frames into [target]. */
    fun receiveStream(target: OutputStream, size: Long, onProgress: (Long) -> Unit = {}) {
        var left = size
        while (left > 0) {
            val frame = receive()
            if (frame.size > left) throw IOException("The other device sent more than it said it would.")
            target.write(frame); left -= frame.size; onProgress(size - left)
        }
    }

    companion object {
        const val MAGIC = "RALINK1"
        const val TCP_PORT = 47810
        const val DISCOVERY_PORT = 47811
        const val MAX_FRAME = 1 shl 20
        /**
         * The request set this build speaks. Both sides send it with their app version in "hello" and its
         * reply; a side that sends none is protocol 1 (0.44.1 and earlier). New requests are added to [OPS]
         * and the protocol number goes up, and a side only sends a request the other lists (or, for an older
         * peer, one in [V1_OPS]), so older and newer phones and computers keep syncing what they both know.
         * A request a peer doesn't know gets an "error" reply, never a dropped connection.
         */
        const val PROTOCOL = 2
        val V1_OPS = listOf("hello", "folders", "list", "get", "put", "create", "done")
        /**
         * Protocol 2 (0.54.0): shared settings; "transcribe" (the phone asks the computer to make a book's ReadAlong,
         * or to stop) and "jobs" (what the computer is making, and how far along).
         */
        val OPS = V1_OPS + listOf(SETTINGS, SET_SETTINGS, TRANSCRIBE, JOBS, RESUME)
        const val SETTINGS = "settings"
        const val SET_SETTINGS = "set-settings"
        const val TRANSCRIBE = "transcribe"
        const val JOBS = "jobs"
        /** Not a request of its own: "get" may carry "offset" (carry on a copy that was cut off) and "verify" (a SHA-256 of the whole file follows it). */
        const val RESUME = "resume"

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The requests a peer understands, from its hello or reply. */
        fun peerOps(json: org.json.JSONObject): Set<String> =
            json.optJSONArray("ops")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet() } ?: V1_OPS.toSet()
        private const val CHUNK = 256 * 1024
        /** How much of a file each frame carries (all but the last of a file are full). */
        const val FRAME_DATA = CHUNK.toLong()
        private val random = SecureRandom()

        fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)
        fun newId(): String = ByteArray(9).also(random::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        /** Names a key without revealing it: the first 8 bytes of its SHA-256, in hex. */
        fun keyId(key: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(key).take(8).joinToString("") { "%02x".format(it) }

        /** The phone's side of the greeting. */
        fun connect(input: InputStream, output: OutputStream, key: ByteArray): LinkChannel {
            val data = DataInputStream(input.buffered()); val out = DataOutputStream(output.buffered())
            val mine = ByteArray(16).also(random::nextBytes)
            out.writeUTF(MAGIC); out.writeUTF(keyId(key)); out.write(mine); out.flush()
            val theirs = ByteArray(16).also { data.readFully(it) }
            return LinkChannel(data, out, sessionKey(key, mine, theirs), client = true)
        }

        /** The computer's side: reads the greeting and asks [keyFor] for the key it names (null refuses). */
        fun accept(input: InputStream, output: OutputStream, keyFor: (String) -> ByteArray?): Pair<LinkChannel, String> {
            val data = DataInputStream(input.buffered()); val out = DataOutputStream(output.buffered())
            if (data.readUTF() != MAGIC) throw IOException("Not a ReadAlong device.")
            val id = data.readUTF()
            val key = keyFor(id) ?: throw IOException("Unknown device.")
            val theirs = ByteArray(16).also { data.readFully(it) }
            val mine = ByteArray(16).also(random::nextBytes)
            out.write(mine); out.flush()
            return LinkChannel(data, out, sessionKey(key, theirs, mine), client = false) to id
        }

        private fun sessionKey(key: ByteArray, client: ByteArray, server: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("readalong-link-v1".toByteArray() + client + server)

        private fun nonce(direction: Int, counter: Long) = ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()
    }
}

/** A failure the other device explained; its message is fit to show. */
class LinkException(message: String) : IOException(message)

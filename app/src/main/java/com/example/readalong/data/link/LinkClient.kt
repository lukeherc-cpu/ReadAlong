package com.example.readalong.data.link

import com.example.readalong.domain.link.LinkChannel
import com.example.readalong.domain.link.SyncFile
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A folder the computer offers. Only [shared] ones can be synced. */
data class RemoteFolder(val id: String, val name: String, val shared: Boolean)

/**
 * One conversation with the linked computer (see ReadAlong Desktop's DeviceLink). [open] tries the
 * addresses it was last reached at, then asks the home network where it went.
 */
class LinkClient private constructor(private val socket: Socket, private val channel: LinkChannel, val computerName: String, val computerId: String, val host: String, val port: Int) : Closeable {

    fun folders(): List<RemoteFolder> {
        channel.sendJson(JSONObject().put("op", "folders"))
        val array = channel.receiveJson().getJSONArray("folders")
        return List(array.length()) { i -> array.getJSONObject(i).let { RemoteFolder(it.getString("id"), it.getString("name"), it.optBoolean("shared")) } }
    }

    fun list(folder: String): List<SyncFile> {
        channel.sendJson(JSONObject().put("op", "list").put("folder", folder))
        val pages = channel.receiveJson().getInt("pages")
        return (0 until pages).flatMap {
            val files = channel.receiveJson().getJSONArray("files")
            List(files.length()) { i -> SyncFile.fromJson(files.getJSONObject(i)) }
        }
    }

    /** Copies one of the computer's files into [target]; returns its size and time there. */
    fun get(folder: String, path: String, target: OutputStream, progress: (Long, Long) -> Unit): SyncFile {
        channel.sendJson(JSONObject().put("op", "get").put("folder", folder).put("path", path))
        val head = channel.receiveJson()
        val size = head.getLong("size")
        channel.receiveStream(target, size) { progress(it, size) }
        return SyncFile(path, size, head.getLong("modified"))
    }

    /** Sends [size] bytes of [source] to the computer; returns the file as the computer now has it. */
    fun put(folder: String, path: String, size: Long, source: InputStream, progress: (Long, Long) -> Unit): SyncFile {
        channel.sendJson(JSONObject().put("op", "put").put("folder", folder).put("path", path).put("size", size))
        channel.receiveJson() // ready, or an error such as a full disk
        channel.sendStream(source, size) { progress(it, size) }
        val done = channel.receiveJson()
        return SyncFile(path, done.getLong("size"), done.getLong("modified"))
    }

    /** Makes a folder on the computer for books that are only on this phone. */
    fun create(name: String): RemoteFolder {
        channel.sendJson(JSONObject().put("op", "create").put("name", name))
        return channel.receiveJson().let { RemoteFolder(it.getString("id"), it.getString("name"), true) }
    }

    override fun close() {
        // "done" lets the computer rescan its library right away.
        runCatching { channel.sendJson(JSONObject().put("op", "done")); channel.receive() }
        runCatching { socket.close() }
    }

    companion object {
        /** Connects with [key] and introduces this phone. Throws when the computer can't be reached. */
        fun open(hosts: List<String>, port: Int, key: ByteArray, phoneId: String, phoneName: String): LinkClient {
            val keyId = LinkChannel.keyId(key)
            val tried = LinkedHashSet<Pair<String, Int>>()
            fun attempt(host: String, at: Int): LinkClient? {
                if (!tried.add(host to at)) return null
                val socket = Socket()
                return try {
                    socket.connect(InetSocketAddress(host, at), 3_000)
                    socket.soTimeout = 60_000
                    val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), key)
                    channel.sendJson(JSONObject().put("op", "hello").put("device", phoneId).put("name", phoneName))
                    val reply = channel.receiveJson()
                    LinkClient(socket, channel, reply.optString("name", "Computer"), reply.getString("device"), host, at)
                } catch (_: IOException) { runCatching { socket.close() }; null }
            }
            hosts.firstNotNullOfOrNull { attempt(it, port) }?.let { return it }
            discover(keyId)?.let { (host, at) -> attempt(host, at)?.let { return it } }
            throw IOException("Couldn't reach your computer. Check that ReadAlong is open on it and both are on the same Wi-Fi.")
        }

        /** Broadcasts "RALINK1?<key id>" on the home network; the linked computer answers with its port. */
        private fun discover(keyId: String): Pair<String, Int>? = runCatching {
            DatagramSocket().use { udp ->
                udp.broadcast = true
                udp.soTimeout = 1_500
                val ask = "${LinkChannel.MAGIC}?$keyId".toByteArray()
                repeat(2) {
                    udp.send(DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), LinkChannel.DISCOVERY_PORT))
                    val buffer = ByteArray(64)
                    val answer = DatagramPacket(buffer, buffer.size)
                    val heard = runCatching { udp.receive(answer) }.isSuccess
                    val text = if (heard) String(answer.data, 0, answer.length) else ""
                    if (text.startsWith("${LinkChannel.MAGIC}!")) return@runCatching answer.address.hostAddress!! to text.substringAfter('!').trim().toInt()
                }
                null
            }
        }.getOrNull()
    }
}

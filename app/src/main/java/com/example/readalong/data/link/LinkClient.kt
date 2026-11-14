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
class LinkClient private constructor(private val socket: Socket, private val channel: LinkChannel, val computerName: String, val computerId: String, val host: String, val port: Int,
    /** The computer's ReadAlong version; null for ReadAlong Desktop 0.44.1 and earlier, which didn't say. */
    val computerVersion: String? = null,
    /** The requests the computer understands (see [LinkChannel.PROTOCOL]). */
    val computerOps: Set<String> = LinkChannel.V1_OPS.toSet(),
    /** When the computer last had something new for the phone ("Sync to phone", a finished ReadAlong, an edit); 0 from older versions. */
    val computerChanged: Long = 0) : Closeable {

    /** Whether the computer knows [op]; check before sending a request newer than protocol 1. */
    fun supports(op: String) = op in computerOps

    fun folders(): List<RemoteFolder> {
        channel.sendJson(JSONObject().put("op", "folders"))
        val array = channel.receiveJson().getJSONArray("folders")
        return List(array.length()) { i -> array.getJSONObject(i).let { RemoteFolder(it.getString("id"), it.getString("name"), it.optBoolean("shared")) } }
    }

    /** The kinds of file the computer was told not to sync (FolderSyncPlan.Kind ids); empty from older versions. */
    var computerSkips: Set<String> = emptySet()
        private set

    fun list(folder: String): List<SyncFile> {
        channel.sendJson(JSONObject().put("op", "list").put("folder", folder))
        val head = channel.receiveJson()
        computerSkips = com.example.readalong.domain.link.FolderSyncPlan.decodeKinds(head.optJSONArray("skip"))
        val pages = head.getInt("pages")
        return (0 until pages).flatMap {
            val files = channel.receiveJson().getJSONArray("files")
            List(files.length()) { i -> SyncFile.fromJson(files.getJSONObject(i)) }
        }
    }

    /** The computer's shared settings, or null when it has them turned off (or is too old to share them). */
    fun settings(): Map<String, String>? = try {
        channel.sendJson(JSONObject().put("op", "settings"))
        val reply = channel.receiveJson()
        if (com.example.readalong.domain.link.FolderSyncPlan.Kind.SETTINGS.id in com.example.readalong.domain.link.FolderSyncPlan.decodeKinds(reply.optJSONArray("skip"))) null
        else com.example.readalong.domain.link.SettingsSync.decode(reply.optJSONObject("values"))
    } catch (_: com.example.readalong.domain.link.LinkException) { null }

    fun setSettings(values: Map<String, String>) {
        channel.sendJson(JSONObject().put("op", "set-settings").put("values", com.example.readalong.domain.link.SettingsSync.encode(values)))
        channel.receiveJson()
    }

    /**
     * Copies one of the computer's files; returns its size and time there. [have] bytes of it are already here from a copy
     * that was cut off, hashed into [prefix]: the computer carries on from there when it can. [open] is given where the
     * computer really starts (0: from the beginning) and returns where to write. A computer that knows how sends the
     * file's SHA-256 after it; a copy that doesn't match throws [CorruptCopy].
     */
    fun get(folder: String, path: String, have: Long = 0, prefix: java.security.MessageDigest? = null, progress: (Long, Long) -> Unit, open: (Long) -> OutputStream): SyncFile {
        val verify = supports(LinkChannel.RESUME)
        val request = JSONObject().put("op", "get").put("folder", folder).put("path", path)
        if (verify) request.put("verify", true).put("offset", if (prefix != null) have else 0)
        channel.sendJson(request)
        val head = channel.receiveJson()
        val size = head.getLong("size")
        val from = head.optLong("offset", 0).coerceIn(0, size)
        val digest = if (!verify) null else if (from > 0 && from == have && prefix != null) prefix else java.security.MessageDigest.getInstance("SHA-256")
        if (from > 0 && digest !== prefix) throw IOException("The computer answered for a different part of ${path.substringAfterLast('/')}.")
        open(from).use { target ->
            val sink = if (digest != null) java.security.DigestOutputStream(target, digest) else target
            channel.receiveStream(sink, size - from) { progress(from + it, size) }
            sink.flush()
        }
        if (digest != null && channel.receiveJson().optString("sha256") != LinkChannel.hex(digest.digest())) throw CorruptCopy(path.substringAfterLast('/'))
        return SyncFile(path, size, head.getLong("modified"))
    }

    /** What arrived isn't what the computer has: the copy is thrown away and made again from the start next time. */
    class CorruptCopy(name: String) : IOException("$name didn't arrive intact. It will be copied again.")

    /** Sends [size] bytes of [source] to the computer; returns the file as the computer now has it. */
    fun put(folder: String, path: String, size: Long, source: InputStream, progress: (Long, Long) -> Unit): SyncFile {
        channel.sendJson(JSONObject().put("op", "put").put("folder", folder).put("path", path).put("size", size))
        channel.receiveJson() // ready, or an error such as a full disk
        channel.sendStream(source, size) { progress(it, size) }
        val done = channel.receiveJson()
        return SyncFile(path, done.getLong("size"), done.getLong("modified"))
    }

    /**
     * Asks the computer to make the ReadAlong of the book whose first audio file is [path] in the shared [folder]
     * (or, with [remove], to take it off its queue). Returns a ComputerJob state: "missing" until the audio has synced there.
     */
    fun transcribe(folder: String, path: String, remove: Boolean = false): String {
        channel.sendJson(JSONObject().put("op", LinkChannel.TRANSCRIBE).put("folder", folder).put("files", org.json.JSONArray(listOf(path))).put("remove", remove))
        return channel.receiveJson().optString("state")
    }

    /** The ReadAlongs the computer is making, or has finished or stopped, for books in shared folders. */
    fun jobs(): List<com.example.readalong.domain.link.ComputerJob> {
        channel.sendJson(JSONObject().put("op", LinkChannel.JOBS))
        val array = channel.receiveJson().getJSONArray("jobs")
        return List(array.length()) { com.example.readalong.domain.link.ComputerJob.fromJson(array.getJSONObject(it)) }
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
        fun open(hosts: List<String>, port: Int, key: ByteArray, phoneId: String, phoneName: String, appVersion: String? = null): LinkClient {
            val keyId = LinkChannel.keyId(key)
            val tried = LinkedHashSet<Pair<String, Int>>()
            fun attempt(host: String, at: Int): LinkClient? {
                if (!tried.add(host to at)) return null
                val socket = Socket()
                return try {
                    socket.connect(InetSocketAddress(host, at), 3_000)
                    socket.soTimeout = 60_000
                    val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), key)
                    // Older computers ignore the extra fields.
                    channel.sendJson(JSONObject().put("op", "hello").put("device", phoneId).put("name", phoneName)
                        .put("protocol", LinkChannel.PROTOCOL).put("ops", org.json.JSONArray(LinkChannel.OPS)).apply { appVersion?.let { put("app", it) } })
                    val reply = channel.receiveJson()
                    LinkClient(socket, channel, reply.optString("name", "Computer"), reply.getString("device"), host, at,
                        reply.optString("app").take(40).ifBlank { null }, LinkChannel.peerOps(reply), reply.optLong("changed", 0))
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

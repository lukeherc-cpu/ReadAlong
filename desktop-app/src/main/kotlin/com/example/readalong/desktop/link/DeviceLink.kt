package com.example.readalong.desktop.link

import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.writeAtomically
import com.example.readalong.domain.link.FolderSyncPlan
import com.example.readalong.domain.link.LinkChannel
import com.example.readalong.domain.link.LinkCode
import com.example.readalong.domain.link.SyncFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.net.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.concurrent.thread

/** A phone linked to this computer. [key] is the secret the two share. */
data class LinkedDevice(val id: String, val name: String, val key: ByteArray, val linkedAt: Long, val lastSeen: Long? = null)

data class LinkState(val deviceId: String, val name: String, val devices: List<LinkedDevice> = emptyList(),
    /** Library folders the phone may sync with; asked right after linking. */
    val shared: List<String> = emptyList())

/**
 * Links phones to this computer and serves the folders chosen for syncing to them over the home network.
 *
 * Pairing shows a [LinkCode] as a QR code; the phone that connects with its key becomes a [LinkedDevice].
 * After that the phone drives each sync (see the phone's FolderLinkSync): it lists a shared folder here,
 * fetches and sends files, and says when it is done, which rescans the library so new books, transcripts and
 * listening progress show up. Nothing outside a shared folder can be read or written, and nothing is deleted.
 */
class DeviceLink(private val scope: CoroutineScope, private val library: LibraryStore, private val onSynced: suspend () -> Unit,
    private val file: File = File(AppPaths.data, "link.json")) {
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<LinkState> = mutableState
    private val mutablePairing = MutableStateFlow<LinkCode?>(null)
    /** The code shown while waiting for a phone to scan it. */
    val pairing: StateFlow<LinkCode?> = mutablePairing
    private val mutableJustLinked = MutableStateFlow<LinkedDevice?>(null)
    /** Set when a phone has just linked, so the window asks which folders to sync. */
    val justLinked: StateFlow<LinkedDevice?> = mutableJustLinked
    private val mutableActivity = MutableStateFlow<String?>(null)
    /** What a phone is doing right now, for Settings. */
    val activity: StateFlow<String?> = mutableActivity

    @Volatile private var server: ServerSocket? = null
    @Volatile private var discovery: DatagramSocket? = null

    /** Starts listening when a phone is already linked. */
    fun start() { if (mutableState.value.devices.isNotEmpty()) listen() }

    fun startPairing(): LinkCode {
        val port = listen()
        return LinkCode(mutableState.value.deviceId, mutableState.value.name, localAddresses(), port, LinkChannel.newKey()).also { mutablePairing.value = it }
    }

    fun cancelPairing() { mutablePairing.value = null; if (mutableState.value.devices.isEmpty()) stop() }
    fun folderPromptAnswered() { mutableJustLinked.value = null }

    fun setShared(paths: List<String>) = change { it.copy(shared = paths.distinct()) }

    fun unlink(id: String) {
        change { s -> s.copy(devices = s.devices.filterNot { it.id == id }) }
        if (mutableState.value.devices.isEmpty() && mutablePairing.value == null) stop()
    }

    fun stop() {
        runCatching { server?.close() }; runCatching { discovery?.close() }
        server = null; discovery = null
    }

    @Synchronized private fun listen(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        // The usual port, so the phone can find this computer again at the address it remembers; any free port otherwise.
        val socket = runCatching { ServerSocket(LinkChannel.TCP_PORT) }.getOrElse { ServerSocket(0) }
        server = socket
        thread(isDaemon = true, name = "ReadAlong link") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "ReadAlong link connection") { connection.use { serve(it) } }
            }
        }
        runCatching { DatagramSocket(LinkChannel.DISCOVERY_PORT) }.getOrNull()?.let { udp ->
            discovery = udp
            thread(isDaemon = true, name = "ReadAlong link discovery") { answerDiscovery(udp, socket.localPort) }
        }
        return socket.localPort
    }

    /** A linked phone that lost track of this computer's address broadcasts "RALINK1?<key id>"; only a known key gets an answer. */
    private fun answerDiscovery(udp: DatagramSocket, port: Int) {
        val buffer = ByteArray(256)
        while (!udp.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { udp.receive(packet) }.getOrNull() ?: break
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (!text.startsWith("${LinkChannel.MAGIC}?") || keyFor(text.substringAfter('?').trim()) == null) continue
            val reply = "${LinkChannel.MAGIC}!$port".toByteArray()
            runCatching { udp.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
        }
    }

    private fun keyFor(keyId: String): ByteArray? =
        mutablePairing.value?.key?.takeIf { LinkChannel.keyId(it) == keyId } ?: mutableState.value.devices.firstOrNull { LinkChannel.keyId(it.key) == keyId }?.key

    private fun serve(socket: Socket) {
        socket.soTimeout = 120_000
        val (channel, keyId) = runCatching { LinkChannel.accept(socket.getInputStream(), socket.getOutputStream(), ::keyFor) }.getOrNull() ?: return
        var device: LinkedDevice? = null
        var changed = false
        try {
            val hello = channel.receiveJson()
            require(hello.optString("op") == "hello")
            device = welcome(keyId, hello.getString("device"), hello.optString("name").ifBlank { "Phone" })
            channel.sendJson(JSONObject().put("device", mutableState.value.deviceId).put("name", mutableState.value.name).put("platform", AppPaths.platformName))
            while (true) {
                val request = try { channel.receiveJson() } catch (_: EOFException) { break }
                try {
                    when (request.optString("op")) {
                        "folders" -> channel.sendJson(JSONObject().put("folders", JSONArray(folders())))
                        "list" -> list(channel, shared(request))
                        "get" -> send(channel, device.name, shared(request), request.getString("path"))
                        "put" -> { receive(channel, device.name, shared(request), request.getString("path"), request.getLong("size")); changed = true }
                        "create" -> channel.sendJson(create(request.getString("name")))
                        "done" -> { channel.sendJson(JSONObject().put("ok", true)); break }
                        else -> channel.sendJson(JSONObject().put("error", "This version of ReadAlong Desktop doesn't know how to do that. Update it to sync."))
                    }
                } catch (refused: IllegalArgumentException) { channel.sendJson(JSONObject().put("error", refused.message ?: "Not allowed.")) }
            }
        } catch (_: Exception) { /* The phone went away or the network dropped; it tries again next time. */ }
        finally {
            mutableActivity.value = null
            if (device != null) change { s -> s.copy(devices = s.devices.map { if (it.id == device.id) it.copy(lastSeen = System.currentTimeMillis()) else it }) }
            if (changed) scope.launch { runCatching { onSynced() } }
        }
    }

    /** The phone that just said hello: a new link if it used the pairing code, otherwise the device that owns the key. */
    private fun welcome(keyId: String, phoneId: String, name: String): LinkedDevice {
        val pending = mutablePairing.value?.key?.takeIf { LinkChannel.keyId(it) == keyId }
        if (pending != null) {
            val linked = LinkedDevice(phoneId, name, pending, System.currentTimeMillis())
            // Linking the same phone again replaces its old key.
            change { s -> s.copy(devices = s.devices.filterNot { it.id == phoneId } + linked) }
            mutablePairing.value = null
            mutableJustLinked.value = linked
            return linked
        }
        return mutableState.value.devices.first { LinkChannel.keyId(it.key) == keyId }
    }

    private fun folders() = library.folders.value.map(::File).filter { it.isDirectory }.map { folder ->
        JSONObject().put("id", folderId(folder.absolutePath)).put("name", folder.name).put("shared", folder.absolutePath in mutableState.value.shared)
    }

    /** The shared folder a request names; anything else is refused. */
    private fun shared(request: JSONObject): File {
        val id = request.getString("folder")
        val path = mutableState.value.shared.firstOrNull { folderId(it) == id } ?: throw IllegalArgumentException("That folder isn't shared on the computer any more.")
        return File(path).takeIf { it.isDirectory } ?: throw IllegalArgumentException("That folder can't be found on the computer.")
    }

    private fun list(channel: LinkChannel, root: File) {
        val base = root.toPath()
        val files = Files.walk(base).use { paths ->
            paths.filter { Files.isRegularFile(it) }.map { path -> SyncFile(base.relativize(path).joinToString("/"), Files.size(path), Files.getLastModifiedTime(path).toMillis()) }
                .filter { FolderSyncPlan.syncable(it.path) }.toList()
        }
        // In pages, so a large library never makes a frame too big.
        val pages = files.chunked(500)
        channel.sendJson(JSONObject().put("pages", pages.size))
        pages.forEach { page -> channel.sendJson(JSONObject().put("files", JSONArray(page.map { it.toJson() }))) }
    }

    private fun send(channel: LinkChannel, phone: String, root: File, path: String) {
        val source = resolve(root, path).takeIf { it.isFile } ?: throw IllegalArgumentException("$path isn't on the computer any more.")
        val size = source.length()
        channel.sendJson(JSONObject().put("size", size).put("modified", source.lastModified()))
        mutableActivity.value = "Sending ${source.name} to $phone"
        source.inputStream().use { channel.sendStream(it, size) }
    }

    private fun receive(channel: LinkChannel, phone: String, root: File, path: String, size: Long) {
        val target = resolve(root, path)
        target.parentFile.mkdirs()
        if (target.parentFile.usableSpace < size + 50_000_000) throw IllegalArgumentException("The computer is out of space for ${target.name}.")
        val temporary = File(target.parentFile, ".${target.name}.readalong-sync")
        channel.sendJson(JSONObject().put("ready", true))
        mutableActivity.value = "Receiving ${target.name} from $phone"
        try {
            temporary.outputStream().use { channel.receiveStream(it, size) }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        channel.sendJson(JSONObject().put("size", target.length()).put("modified", target.lastModified()))
    }

    /** A new folder for books that so far are only on the phone: "<home>/Audiobooks/<name>", added to the library and shared. */
    private fun create(name: String): JSONObject {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().trimStart('.').ifBlank { "Audiobooks from phone" }
        val home = File(System.getProperty("user.home"), "Audiobooks")
        val existing = (mutableState.value.shared + library.folders.value).map(::File).firstOrNull { it.name.equals(clean, ignoreCase = true) && it.isDirectory }
        val folder = existing ?: generateSequence(1) { it + 1 }.map { File(home, if (it == 1) clean else "$clean ($it)") }.first { !it.exists() }.apply { mkdirs() }
        library.setFolders(library.folders.value + folder.absolutePath)
        change { it.copy(shared = it.shared + folder.absolutePath) }
        return JSONObject().put("id", folderId(folder.absolutePath)).put("name", folder.name).put("shared", true)
    }

    private fun change(update: (LinkState) -> LinkState) {
        mutableState.update(update)
        save(mutableState.value)
    }

    private fun save(s: LinkState) = runCatching {
        file.writeAtomically(JSONObject().put("deviceId", s.deviceId).put("name", s.name).put("shared", JSONArray(s.shared))
            .put("devices", JSONArray(s.devices.map { d ->
                JSONObject().put("id", d.id).put("name", d.name).put("key", Base64.getEncoder().encodeToString(d.key)).put("linkedAt", d.linkedAt).put("lastSeen", d.lastSeen ?: JSONObject.NULL)
            })).toString(1))
    }

    private fun load(): LinkState {
        val fresh = LinkState(LinkChannel.newId(), computerName())
        if (!file.isFile) return fresh.also { save(it) }
        return runCatching {
            val o = JSONObject(file.readText())
            val devices = o.optJSONArray("devices") ?: JSONArray()
            LinkState(o.getString("deviceId"), o.optString("name").ifBlank { computerName() },
                List(devices.length()) { i -> devices.getJSONObject(i).let { d ->
                    LinkedDevice(d.getString("id"), d.optString("name", "Phone"), Base64.getDecoder().decode(d.getString("key")), d.optLong("linkedAt"),
                        if (d.isNull("lastSeen")) null else d.optLong("lastSeen"))
                } },
                o.optJSONArray("shared")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty())
        }.getOrDefault(fresh)
    }

    companion object {
        /** Names a folder to the phone without sending its full path. */
        fun folderId(path: String): String = LinkChannel.keyId(File(path).absolutePath.toByteArray())

        /** Resolves [path] inside [root], refusing anything that would land outside it. */
        fun resolve(root: File, path: String): File {
            require(FolderSyncPlan.syncable(path)) { "That file name isn't allowed." }
            val base = root.canonicalFile
            val target = File(base, path).canonicalFile
            require(target.path.startsWith(base.path + File.separator)) { "That file name isn't allowed." }
            return target
        }

        fun computerName(): String = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            ?.substringBefore('.')?.takeIf { it.isNotBlank() && it != "localhost" } ?: "${AppPaths.platformName} computer"

        /** This computer's addresses on the home network, Wi-Fi and Ethernet first; virtual adapters (VPNs, Docker, WSL) last. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .sortedBy { i -> val n = (i.displayName + " " + i.name).lowercase(); if (listOf("virtual", "vmware", "vbox", "docker", "wsl", "hyper-v", "br-", "veth", "tailscale", "zerotier").any { it in n }) 1 else 0 }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress } }
                .distinct()
        }.getOrDefault(emptyList())
    }
}

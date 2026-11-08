package com.example.readalong.desktop.link

import com.example.readalong.compat.*
import com.example.readalong.data.link.LinkClient
import com.example.readalong.data.link.RemoteFolder
import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.readTextRetrying
import com.example.readalong.desktop.core.writeAtomically
import com.example.readalong.domain.link.FolderSyncPlan
import com.example.readalong.domain.link.LinkChannel
import com.example.readalong.domain.link.LinkCode
import com.example.readalong.domain.link.LinkException
import com.example.readalong.domain.link.SettingsSync
import com.example.readalong.domain.link.SyncAction
import com.example.readalong.domain.link.SyncFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.coroutineContext

/** The computer this iPhone is linked to, and where it was last reached. [key] is the secret the two share. */
data class LinkedDevice(val id: String, val name: String, val key: ByteArray, val linkedAt: Long, val lastSeen: Long? = null,
    val appVersion: String? = null, val hosts: List<String> = emptyList(), val port: Int = LinkChannel.TCP_PORT)

/** A folder on the computer kept in step with one in this iPhone's Audiobooks folder. */
data class FolderPair(val remoteId: String, val remoteName: String, val local: String)

data class LinkState(val deviceId: String, val name: String,
    /** The linked computer, if any (a list so the desktop screens that count linked devices work unchanged). */
    val devices: List<LinkedDevice> = emptyList(),
    /** The local folders being synced (the [pairs]' local side). */
    val shared: List<String> = emptyList(),
    /** Kinds of companion file left out of syncing (FolderSyncPlan.Kind ids). Empty: everything syncs. */
    val skipped: Set<String> = emptySet(),
    val pairs: List<FolderPair> = emptyList(),
    val lastSyncAt: Long? = null,
    /** A one-line outcome of the last sync, for Settings. */
    val lastResult: String? = null,
    /** The computer's "something new" time as of the last sync. */
    val seenChange: Long = 0)

/**
 * The iPhone's side of ReadAlong's computer link: the same protocol and folder sync as the Android app's
 * FolderLinkSync, over plain folders in the app's Documents. Linking takes the code ReadAlong Desktop shows
 * (scanned or pasted); each folder the computer shares lands in Documents/Audiobooks/<folder>, which the
 * library scans. While ReadAlong is open, [start] checks every minute and syncs when the computer has news.
 * Nothing is ever deleted on either side.
 *
 * Keeps the desktop DeviceLink's members that the shared screens use, so they compile unchanged.
 */
class DeviceLink(private val scope: CoroutineScope, private val library: LibraryStore, private val onSynced: suspend () -> Unit,
    private val settings: () -> Map<String, String> = { emptyMap() }, private val applySettings: (Map<String, String>) -> Unit = {},
    private val file: File = File(File(AppPaths.data, "link").apply { mkdirs() }, "link.json")) {
    private val directory = file.parentFile
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<LinkState> = mutableState
    /** The desktop shows a code here while pairing; the iPhone scans one instead, so this stays empty. */
    val pairing: StateFlow<LinkCode?> = MutableStateFlow(null)
    private val mutableJustLinked = MutableStateFlow<LinkedDevice?>(null)
    /** Set right after linking, with [offered], so Settings asks which of the computer's folders to sync. */
    val justLinked: StateFlow<LinkedDevice?> = mutableJustLinked
    private val mutableOffered = MutableStateFlow<List<RemoteFolder>>(emptyList())
    val offered: StateFlow<List<RemoteFolder>> = mutableOffered
    private val mutableActivity = MutableStateFlow<String?>(null)
    /** What the sync is doing right now. */
    val activity: StateFlow<String?> = mutableActivity
    private val mutableFraction = MutableStateFlow<Float?>(null)
    val fraction: StateFlow<Float?> = mutableFraction
    private val mutableSyncRequested = MutableStateFlow<Long?>(null)
    /** Set while a sync asked for with "Sync now" is running. */
    val syncRequested: StateFlow<Long?> = mutableSyncRequested

    private val lock = Mutex()
    private var watcher: Job? = null
    /** Something changed here (a finished ReadAlong, an edit): the next check syncs. */
    @kotlin.concurrent.Volatile private var localChange = false

    private val phoneName: String get() = runCatching { platform.UIKit.UIDevice.currentDevice.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "iPhone"
    private val appVersion: String? get() = System.getProperty("readalong.version")

    fun changed() { localChange = true }

    /** "Sync now". */
    fun requestSync() {
        mutableSyncRequested.value = System.currentTimeMillis()
        scope.launch {
            try { report(runCatching { sync() }) } finally { mutableSyncRequested.value = null }
        }
    }

    private fun report(outcome: Result<String>) {
        val text = outcome.getOrElse { it.message ?: "Couldn't sync." }
        mutableState.update { it.copy(lastResult = text) }
    }

    /** Checks every minute while ReadAlong is open; syncs when the computer or this iPhone has something new. */
    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch(IoDispatcher) {
            while (isActive) {
                val s = mutableState.value
                if (s.devices.isNotEmpty() && s.pairs.isNotEmpty() && !lock.isLocked) {
                    val change = runCatching { connect().use { it.computerChanged } }.getOrNull()
                    val stale = (s.lastSyncAt ?: 0) < System.currentTimeMillis() - 30 * 60_000
                    if (change != null && (change > s.seenChange || stale || localChange)) report(runCatching { sync() })
                }
                delay(60_000)
            }
        }
    }

    fun stop() { watcher?.cancel(); watcher = null }

    /** Links with the computer whose code is [text]. Returns null when linked, or why it didn't work. */
    suspend fun pair(text: String): String? = withContext(IoDispatcher) {
        val code = LinkCode.parse(text) ?: return@withContext "That isn't a ReadAlong link code. On the computer, open Settings › Link a phone."
        try {
            LinkClient.open(code.hosts, code.port, code.key, mutableState.value.deviceId, phoneName, appVersion).use { client ->
                val computer = LinkedDevice(client.computerId, client.computerName, code.key, System.currentTimeMillis(), System.currentTimeMillis(),
                    client.computerVersion, listOf(client.host) + (code.hosts - client.host), client.port)
                change { it.copy(devices = listOf(computer), pairs = emptyList(), shared = emptyList(), lastSyncAt = null, lastResult = null, seenChange = 0) }
                directory.listFiles { f -> f.name.startsWith("synced-") || f.name == "settings.json" }?.forEach { it.delete() }
                mutableOffered.value = client.folders()
                mutableJustLinked.value = computer
            }
            start()
            null
        } catch (failure: Exception) {
            failure.message ?: "Couldn't reach your computer. Check that ReadAlong is open on it and both are on the same Wi-Fi."
        }
    }

    /** Syncs [chosen] of the computer's folders (null: not now), each into Documents/Audiobooks/<name>, then syncs. */
    fun choose(chosen: List<RemoteFolder>?) {
        mutableJustLinked.value = null
        if (chosen.isNullOrEmpty()) return
        val pairs = chosen.map { remote ->
            val folder = File(AppPaths.audiobooks, safeName(remote.name)).apply { mkdirs() }
            FolderPair(remote.id, remote.name, folder.absolutePath)
        }
        change { s -> s.copy(pairs = (s.pairs.filterNot { p -> pairs.any { it.remoteId == p.remoteId } } + pairs), shared = (s.pairs + pairs).map { it.local }.distinct()) }
        val root = AppPaths.audiobooks.absolutePath
        if (root !in library.folders.value) library.setFolders(library.folders.value + root)
        requestSync()
    }

    /** The computer's folders, for adding another one later. */
    suspend fun remoteFolders(): List<RemoteFolder> = withContext(IoDispatcher) { connect().use { it.folders() } }
    fun offer(folders: List<RemoteFolder>) { mutableOffered.value = folders; mutableJustLinked.value = mutableState.value.devices.firstOrNull() }

    fun cancelPairing() {}
    fun startPairing(): LinkCode? = null
    fun folderPromptAnswered() { mutableJustLinked.value = null }
    fun setShared(paths: List<String>) = change { s -> s.copy(pairs = s.pairs.filter { it.local in paths }, shared = paths.filter { p -> s.pairs.any { it.local == p } }) }
    fun setSkipped(kinds: Set<String>) = change { it.copy(skipped = kinds) }

    fun unlink(id: String) {
        stop()
        change { s -> LinkState(s.deviceId, s.name, skipped = s.skipped) }
        directory.listFiles { f -> f.name.startsWith("synced-") || f.name == "settings.json" }?.forEach { it.delete() }
    }

    /** Syncs every pair; returns a one-line summary. Throws when the computer can't be reached. */
    suspend fun sync(): String = lock.withLock {
        withContext(IoDispatcher) {
            val s = mutableState.value
            val computer = s.devices.firstOrNull() ?: return@withContext "This iPhone isn't linked to a computer."
            if (s.pairs.isEmpty()) return@withContext "Choose a folder to sync first."
            localChange = false
            mutableActivity.value = "Connecting to ${computer.name}…"
            try {
                var copied = 0; var failed = 0
                val conflicts = mutableListOf<String>()
                var news = 0L
                connect().use { client ->
                    news = client.computerChanged
                    for (pair in s.pairs) {
                        val (done, failures) = syncPair(client, pair, conflicts)
                        copied += done; failed += failures
                    }
                    syncSettings(client)
                }
                mutableActivity.value = "Updating your library…"
                runCatching { onSynced() }
                val result = when {
                    failed > 0 -> "Copied $copied ${files(copied)}; $failed couldn't be copied and will be tried again."
                    copied == 0 -> "Up to date."
                    else -> "Copied $copied ${files(copied)}."
                } + when (conflicts.size) {
                    0 -> ""
                    1 -> " ${conflicts.single().substringAfterLast('/')} changed on both devices and was left as it is on each."
                    else -> " ${conflicts.size} audio files changed on both devices and were left alone."
                }
                change { it.copy(lastSyncAt = System.currentTimeMillis(), lastResult = result, seenChange = maxOf(it.seenChange, news),
                    devices = it.devices.map { d -> d.copy(lastSeen = System.currentTimeMillis()) }) }
                result
            } finally { mutableActivity.value = null; mutableFraction.value = null }
        }
    }

    private fun files(count: Int) = if (count == 1) "file" else "files"

    private fun connect(): LinkClient {
        val computer = mutableState.value.devices.firstOrNull() ?: throw IOException("This iPhone isn't linked to a computer.")
        val client = LinkClient.open(computer.hosts, computer.port, computer.key, mutableState.value.deviceId, phoneName, appVersion)
        if (client.host != computer.hosts.firstOrNull() || client.port != computer.port || client.computerName != computer.name)
            change { s -> s.copy(devices = s.devices.map { it.copy(name = client.computerName, hosts = listOf(client.host) + (computer.hosts - client.host).take(4), port = client.port, appVersion = client.computerVersion ?: it.appVersion) }) }
        return client
    }

    private suspend fun syncSettings(client: LinkClient) {
        if (FolderSyncPlan.Kind.SETTINGS.id in mutableState.value.skipped) return
        mutableActivity.value = "Syncing settings…"
        val theirs = client.settings() ?: return
        val mine = settings()
        val agreedFile = File(directory, "settings.json")
        val before = agreedFile.takeIf { it.isFile }?.let { runCatching { SettingsSync.decode(JSONObject(it.readText())) }.getOrNull() }
        val agreed = SettingsSync.merge(mine, theirs, before)
        if (agreed.any { (key, value) -> mine[key] != value }) applySettings(agreed)
        if (agreed.any { (key, value) -> theirs[key] != value }) client.setSettings(agreed)
        agreedFile.writeAtomically(SettingsSync.encode(agreed).toString())
    }

    private suspend fun syncPair(client: LinkClient, pair: FolderPair, conflicts: MutableList<String>): Pair<Int, Int> {
        val root = File(pair.local).apply { mkdirs() }
        mutableActivity.value = "Comparing ${pair.remoteName}…"
        val remote = client.list(pair.remoteId).toMutableList()
        val skipped = mutableState.value.skipped + client.computerSkips
        val local = walk(root).filterKeys { FolderSyncPlan.included(it, skipped) }
        remote.retainAll { FolderSyncPlan.included(it.path, skipped) }
        val recorded = synced(pair)
        val held = recorded.filterKeys { !FolderSyncPlan.included(it, skipped) }
        val previous = recorded - held.keys
        val actions = FolderSyncPlan.plan(local.values.toList(), remote, previous)
        conflicts += FolderSyncPlan.conflicts(local.values.toList(), remote, previous).map { "${pair.remoteName}/$it" }
        val localNow = local.toMutableMap()
        val remoteNow = remote.associateBy { it.path }.toMutableMap()
        var copied = 0; var failed = 0
        actions.forEachIndexed { index, action ->
            coroutineContext.ensureActive()
            val name = action.path.substringAfterLast('/')
            val label = "${index + 1} of ${actions.size}: $name"
            try {
                when (action) {
                    is SyncAction.Download -> {
                        mutableActivity.value = "Copying from computer $label"
                        val target = resolve(root, action.path)
                        target.parentFile.mkdirs()
                        val temporary = File(target.parentFile, ".${target.name}.readalong-sync")
                        try {
                            val received = temporary.outputStream().buffered(256 * 1024).use { out -> client.get(pair.remoteId, action.path, out) { done, size -> mutableFraction.value = fraction(done, size) } }
                            if (temporary.length() != received.size) throw IOException("Only ${temporary.length()} of ${received.size} bytes of $name arrived.")
                            if (target.exists()) target.delete()
                            if (!temporary.renameTo(target)) throw IOException("Couldn't save $name.")
                            remoteNow[action.path] = received
                            localNow[action.path] = SyncFile(action.path, target.length(), target.lastModified())
                        } finally { if (temporary.exists()) temporary.delete() }
                    }
                    is SyncAction.Upload -> {
                        mutableActivity.value = "Sending to computer $label"
                        val source = resolve(root, action.path)
                        val sent = source.inputStream().buffered(256 * 1024).use { input ->
                            client.put(pair.remoteId, action.path, source.length(), input) { done, size -> mutableFraction.value = fraction(done, size) }
                        }
                        remoteNow[action.path] = sent
                    }
                }
                copied++
            } catch (refused: LinkException) { failed++; System.err.println("ReadAlong: link sync skipped a file: ${refused.message}") }
            catch (io: IOException) {
                saveSynced(pair, held + FolderSyncPlan.settled(localNow.values.toList(), remoteNow.values.toList(), previous))
                throw io
            } finally { mutableFraction.value = null }
        }
        saveSynced(pair, held + FolderSyncPlan.settled(localNow.values.toList(), remoteNow.values.toList(), previous))
        return copied to failed
    }

    private fun fraction(done: Long, size: Long) = if (size <= 0) 1f else (done.toFloat() / size).coerceIn(0f, 1f)

    /** Every syncable file under [root], by path relative to it. Hidden files and folders are left out. */
    private fun walk(root: File): Map<String, SyncFile> {
        val found = HashMap<String, SyncFile>()
        val pending = ArrayDeque(listOf(root to ""))
        while (pending.isNotEmpty()) {
            val (folder, prefix) = pending.removeFirst()
            val children = folder.listFiles() ?: throw IOException("ReadAlong can't open ${folder.name}.")
            for (child in children) {
                if (child.name.startsWith(".")) continue
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) pending.addLast(child to path)
                else if (FolderSyncPlan.syncable(path)) found[path] = SyncFile(path, child.length(), child.lastModified())
            }
        }
        return found
    }

    private fun syncedFile(pair: FolderPair) = File(directory, "synced-" + LinkChannel.keyId((pair.remoteId + "\n" + pair.local).encodeToByteArray()) + ".json")
    private fun synced(pair: FolderPair) = syncedFile(pair).takeIf { it.isFile }?.let { FolderSyncPlan.decode(it.readText()) }.orEmpty()
    private fun saveSynced(pair: FolderPair, state: Map<String, com.example.readalong.domain.link.SyncedState>) = syncedFile(pair).writeAtomically(FolderSyncPlan.encode(state))

    private fun change(update: (LinkState) -> LinkState) {
        mutableState.update(update)
        save(mutableState.value)
    }

    private fun save(s: LinkState) = runCatching {
        file.writeAtomically(JSONObject().put("deviceId", s.deviceId).put("name", s.name).put("skipped", FolderSyncPlan.encodeKinds(s.skipped))
            .put("devices", JSONArray(s.devices.map { d ->
                JSONObject().put("id", d.id).put("name", d.name).put("key", Base64.getEncoder().encodeToString(d.key)).put("linkedAt", d.linkedAt)
                    .put("lastSeen", d.lastSeen ?: JSONObject.NULL).put("appVersion", d.appVersion ?: JSONObject.NULL).put("hosts", JSONArray(d.hosts)).put("port", d.port)
            }))
            .put("pairs", JSONArray(s.pairs.map { JSONObject().put("remoteId", it.remoteId).put("remoteName", it.remoteName).put("local", it.local) }))
            .put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL).put("lastResult", s.lastResult ?: JSONObject.NULL).put("seenChange", s.seenChange).toString(1))
    }

    private fun load(): LinkState {
        val fresh = LinkState(LinkChannel.newId(), "iPhone")
        if (!file.isFile) return fresh.also { save(it) }
        return runCatching {
            val o = JSONObject(file.readTextRetrying())
            val devices = o.optJSONArray("devices") ?: JSONArray()
            val pairs = o.optJSONArray("pairs") ?: JSONArray()
            val pairList = List(pairs.length()) { i -> pairs.getJSONObject(i).let { FolderPair(it.getString("remoteId"), it.getString("remoteName"), it.getString("local")) } }
            LinkState(o.getString("deviceId"), o.optString("name").ifBlank { "iPhone" },
                List(devices.length()) { i -> devices.getJSONObject(i).let { d ->
                    val hosts = d.optJSONArray("hosts") ?: JSONArray()
                    LinkedDevice(d.getString("id"), d.optString("name", "Computer"), Base64.getDecoder().decode(d.getString("key")), d.optLong("linkedAt"),
                        if (d.isNull("lastSeen")) null else d.optLong("lastSeen"), if (d.isNull("appVersion")) null else d.optString("appVersion"),
                        List(hosts.length()) { hosts.getString(it) }, d.optInt("port", LinkChannel.TCP_PORT))
                } },
                pairList.map { it.local }, FolderSyncPlan.decodeKinds(o.optJSONArray("skipped")), pairList,
                if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"), if (o.isNull("lastResult")) null else o.optString("lastResult"), o.optLong("seenChange"))
        }.getOrElse { fresh }
    }

    companion object {
        fun folderId(path: String): String = LinkChannel.keyId(File(path).absolutePath.encodeToByteArray())

        /** Resolves [path] inside [root], refusing anything that would land outside it. */
        fun resolve(root: File, path: String): File {
            require(FolderSyncPlan.syncable(path)) { "That file name isn't allowed." }
            val base = root.canonicalFile
            val target = File(base, path).canonicalFile
            require(target.path.startsWith(base.path + "/")) { "That file name isn't allowed." }
            return target
        }

        private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().trimStart('.').ifBlank { "From computer" }

        fun computerName() = "iPhone"
        fun localAddresses(): List<String> = emptyList()
    }
}

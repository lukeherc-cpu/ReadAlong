package com.example.readalong.data.link

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.Settings
import com.example.readalong.domain.link.FolderSyncPlan
import com.example.readalong.domain.link.LinkCode
import com.example.readalong.domain.link.SyncAction
import com.example.readalong.domain.link.SyncFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** What a running sync is doing, for Settings and the notification. */
data class SyncProgress(val text: String, val fraction: Float? = null)

/**
 * Links this phone to ReadAlong Desktop and keeps paired folders in step with it over the home network.
 *
 * Each sync lists both sides of every [FolderPair], copies what [FolderSyncPlan] says, and then rescans
 * every paired phone folder, so new books, `.readalong` transcripts (which show as transcribed in the queue),
 * covers, edited titles and listening positions take effect exactly as if the folder had been synced any other
 * way. While ReadAlong is open, [watch] checks every minute whether the computer is reachable and syncs when it
 * has something new (a finished ReadAlong, an edit, or "Sync to phone" pressed there) or the last sync is old. Nothing is ever deleted on either side. Kinds of companion file
 * turned off in [LinkState.skipped] (or on the computer) aren't copied. Before each sync,
 * [beforeSync] writes the `.readalong` of each book transcribed on the phone into its folder (TranscriptPackageWriter).
 */
/** This phone's side of the shared app and reader settings (SettingsSync keys). */
interface LinkSettings {
    suspend fun read(): Map<String, String>
    suspend fun apply(values: Map<String, String>)
}

class FolderLinkSync(private val context: Context, val store: LinkStore, private val beforeSync: suspend () -> Unit = {}, private val settings: LinkSettings? = null,
    /** Rescans a phone folder; returns "<book>: <why>" for each ReadAlong there that couldn't be used. */
    private val rescan: suspend (Uri) -> List<String>) {
    private val resolver = context.contentResolver
    private val lock = Mutex()
    private val mutableProgress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = mutableProgress

    val phoneName: String get() = runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /** Links with the computer that showed [code]. Returns the folders it offers. */
    suspend fun link(code: LinkCode): List<RemoteFolder> = withContext(Dispatchers.IO) {
        LinkClient.open(code.hosts, code.port, code.key, store.value.phoneId, phoneName, com.example.readalong.BuildConfig.VERSION_NAME).use { client ->
            store.update { it.copy(computer = LinkedComputer(client.computerId, client.computerName, code.key, listOf(client.host) + (code.hosts - client.host), client.port, System.currentTimeMillis()),
                pairs = emptyList(), foldersAsked = false, lastSyncAt = null, lastResult = null) }
            client.folders()
        }
    }

    /** The computer's folders, for choosing which to sync. */
    suspend fun remoteFolders(): List<RemoteFolder> = withContext(Dispatchers.IO) { connect().use { it.folders() } }

    /** Makes a folder on the computer to receive a phone folder's books. */
    suspend fun createRemote(name: String): RemoteFolder = withContext(Dispatchers.IO) { connect().use { it.create(name) } }

    /** Syncs every pair; returns a one-line summary. Throws when the computer can't be reached. */
    suspend fun sync(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val pairs = store.value.pairs
            if (store.value.computer == null || pairs.isEmpty()) return@withContext "Nothing to sync yet."
            // Books transcribed here get their .readalong beside them first, so this sync carries them to the computer.
            mutableProgress.value = SyncProgress("Saving ReadAlong files…")
            try { beforeSync() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                android.util.Log.i("ReadAlong", "ReadAlong files not saved before sync: ${failure.javaClass.simpleName}")
            }
            mutableProgress.value = SyncProgress("Connecting to ${store.value.computer?.name}…")
            try {
                var copied = 0; var failed = 0
                val problems = mutableListOf<String>()
                var change = 0L
                connect().use { client ->
                    change = client.computerChanged
                    for (pair in pairs) {
                        val (done, failures) = syncPair(client, pair)
                        copied += done; failed += failures
                    }
                    syncSettings(client)
                }
                // Every paired folder, even when nothing was copied: a ReadAlong that couldn't be used before is tried
                // again, and books, covers and titles that arrived some other way show up too.
                for (pair in pairs) {
                    coroutineContext.ensureActive()
                    mutableProgress.value = SyncProgress("Updating your library from ${pair.localName}…")
                    try { problems += rescan(Uri.parse(pair.localUri)) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { problems += "${pair.localName}: ${failure.message ?: "couldn't be scanned"}" }
                }
                val result = when {
                    failed > 0 -> "Copied $copied ${files(copied)}; $failed couldn't be copied and will be tried again."
                    copied == 0 -> "Up to date."
                    else -> "Copied $copied ${files(copied)}."
                } + when (problems.size) {
                    0 -> ""
                    1 -> " A ReadAlong couldn't be used: ${problems.single()}"
                    else -> " ${problems.size} ReadAlongs couldn't be used, e.g. ${problems.first()}"
                }
                store.update { it.copy(lastSyncAt = System.currentTimeMillis(), lastResult = result, seenChange = maxOf(it.seenChange, change)) }
                result
            } finally { mutableProgress.value = null }
        }
    }

    /**
     * While ReadAlong is open: every minute, sees whether the computer is reachable and syncs when it says it has
     * something new since the last sync, or the last sync is more than ten minutes old. Runs until cancelled.
     */
    suspend fun watch(syncNow: () -> Unit) = withContext(Dispatchers.IO) {
        while (true) {
            val s = store.value
            if (s.computer != null && s.pairs.isNotEmpty() && mutableProgress.value == null && !lock.isLocked) {
                val change = runCatching { connect().use { it.computerChanged } }.getOrNull()
                val stale = (s.lastSyncAt ?: 0) < System.currentTimeMillis() - 10 * 60_000
                if (change != null && (change > s.seenChange || stale)) syncNow()
            }
            kotlinx.coroutines.delay(60_000)
        }
    }

    /** App and reader settings, unless "App and reader settings" is unticked here or on the computer. */
    private suspend fun syncSettings(client: LinkClient) {
        val mine = settings ?: return
        if (com.example.readalong.domain.link.FolderSyncPlan.Kind.SETTINGS.id in store.value.skipped) return
        mutableProgress.value = SyncProgress("Syncing settings…")
        val theirs = client.settings() ?: return
        val phone = mine.read()
        val agreed = com.example.readalong.domain.link.SettingsSync.merge(phone, theirs, store.agreedSettings())
        if (agreed.any { (key, value) -> phone[key] != value }) mine.apply(agreed)
        if (agreed.any { (key, value) -> theirs[key] != value }) client.setSettings(agreed)
        store.saveAgreedSettings(agreed)
    }

    private fun files(count: Int) = if (count == 1) "file" else "files"

    /** Connects to the linked computer and remembers where it was found. */
    private fun connect(): LinkClient {
        val computer = store.value.computer ?: throw IOException("This phone isn't linked to a computer.")
        val client = LinkClient.open(computer.hosts, computer.port, computer.key, store.value.phoneId, phoneName, com.example.readalong.BuildConfig.VERSION_NAME)
        if (client.host != computer.hosts.firstOrNull() || client.port != computer.port || client.computerName != computer.name)
            store.update { s -> s.copy(computer = s.computer?.copy(name = client.computerName, hosts = listOf(client.host) + (computer.hosts - client.host).take(4), port = client.port)) }
        return client
    }

    private suspend fun syncPair(client: LinkClient, pair: FolderPair): Pair<Int, Int> {
        val tree = Uri.parse(pair.localUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        mutableProgress.value = SyncProgress("Comparing ${pair.localName}…")
        val remote = client.list(pair.remoteId).toMutableList()
        // Kinds turned off on either side (Settings › Sync) are left alone on both.
        val skipped = store.value.skipped + client.computerSkips
        val local = walk(tree, rootId).filterKeys { FolderSyncPlan.included(it, skipped) }
        remote.retainAll { FolderSyncPlan.included(it.path, skipped) }
        val recorded = store.synced(pair)
        // Their records are kept, so turning a kind back on carries on from where it was.
        val held = recorded.filterKeys { !FolderSyncPlan.included(it, skipped) }
        val previous = recorded - held.keys
        val actions = FolderSyncPlan.plan(local.values.map { it.file }, remote, previous)
        val localNow = local.mapValues { it.value.file }.toMutableMap()
        val remoteNow = remote.associateBy { it.path }.toMutableMap()
        var copied = 0; var failed = 0
        actions.forEachIndexed { index, action ->
            coroutineContext.ensureActive()
            val name = action.path.substringAfterLast('/')
            val label = "${index + 1} of ${actions.size}: $name"
            try {
                when (action) {
                    is SyncAction.Download -> {
                        mutableProgress.value = SyncProgress("Copying from computer $label", 0f)
                        val (received, stored) = download(client, pair, tree, rootId, action.path) { done, size -> mutableProgress.value = SyncProgress("Copying from computer $label", fraction(done, size)) }
                        remoteNow[action.path] = received; localNow[action.path] = stored
                    }
                    is SyncAction.Upload -> {
                        val source = local.getValue(action.path)
                        mutableProgress.value = SyncProgress("Sending to computer $label", 0f)
                        val sent = resolver.openInputStream(source.uri)?.use { input ->
                            client.put(pair.remoteId, action.path, source.file.size, input.buffered(256 * 1024)) { done, size -> mutableProgress.value = SyncProgress("Sending to computer $label", fraction(done, size)) }
                        } ?: throw FileNotFoundException(name)
                        remoteNow[action.path] = sent
                    }
                }
                copied++
            } catch (refused: com.example.readalong.domain.link.LinkException) { failed++; android.util.Log.w("ReadAlong", "Link sync skipped a file: ${refused.message}") }
            catch (io: IOException) {
                // A dropped connection ends this sync; what was copied so far is kept and recorded.
                store.saveSynced(pair, held + FolderSyncPlan.settled(localNow.values.toList(), remoteNow.values.toList(), previous))
                throw io
            }
        }
        store.saveSynced(pair, held + FolderSyncPlan.settled(localNow.values.toList(), remoteNow.values.toList(), previous))
        return copied to failed
    }

    private fun fraction(done: Long, size: Long) = if (size <= 0) 1f else (done.toFloat() / size).coerceIn(0f, 1f)

    private data class LocalFile(val file: SyncFile, val uri: Uri)

    /** Every file under a document tree, by path relative to it. */
    private fun walk(tree: Uri, rootId: String): Map<String, LocalFile> {
        val found = HashMap<String, LocalFile>()
        val pending = ArrayDeque(listOf(rootId to ""))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        while (pending.isNotEmpty()) {
            val (id, prefix) = pending.removeFirst()
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val child = cursor.getString(0); val name = cursor.getString(1) ?: continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (name.startsWith(".")) continue
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) pending.addLast(child to path)
                    else if (FolderSyncPlan.syncable(path)) found[path] = LocalFile(SyncFile(path, cursor.getLong(3), cursor.getLong(4)), DocumentsContract.buildDocumentUriUsingTree(tree, child))
                }
            }
        }
        return found
    }

    /** Copies a computer file into the tree through a hidden temporary file, so a half copy never looks like a changed book. */
    private fun download(client: LinkClient, pair: FolderPair, tree: Uri, rootId: String, path: String, progress: (Long, Long) -> Unit): Pair<SyncFile, SyncFile> {
        val parts = path.split('/')
        var parent = rootId
        for (folder in parts.dropLast(1)) parent = child(tree, parent, folder)?.takeIf { it.second }?.first
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), Document.MIME_TYPE_DIR, folder)
                ?.let(DocumentsContract::getDocumentId) ?: throw IOException("Couldn't make the folder $folder.")
        val name = parts.last()
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        val temporary = DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", ".$name.readalong-sync") ?: throw IOException("Couldn't write $name.")
        try {
            // Closing the buffered stream flushes it: without that, the last part of every file never reached the phone.
            val received = resolver.openOutputStream(temporary, "w")?.buffered(256 * 1024)?.use { output -> client.get(pair.remoteId, path, output, progress) } ?: throw IOException("Couldn't write $name.")
            // Never put a short copy in place of the real file: the next sync tries again.
            stat(temporary)?.let { if (it.size != received.size) throw IOException("Only ${it.size} of ${received.size} bytes of $name arrived.") }
            child(tree, parent, name)?.let { (existing, _) -> DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, existing)) }
            val final = runCatching { DocumentsContract.renameDocument(resolver, temporary, name) }.getOrNull()
                ?: copyInto(parentUri, temporary, name)
            val stat = stat(final) ?: SyncFile(path, received.size, System.currentTimeMillis())
            return received to stat.copy(path = path)
        } finally { runCatching { if (stat(temporary) != null) DocumentsContract.deleteDocument(resolver, temporary) } }
    }

    /** Where a provider can't rename: copy the finished temporary file to its real name. */
    private fun copyInto(parent: Uri, temporary: Uri, name: String): Uri {
        val target = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name) ?: throw IOException("Couldn't write $name.")
        resolver.openInputStream(temporary)!!.use { input -> resolver.openOutputStream(target, "w")!!.use { input.copyTo(it, 256 * 1024) } }
        return target
    }

    private fun child(tree: Uri, parent: String, name: String): Pair<String, Boolean>? =
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) return@use cursor.getString(0) to (cursor.getString(2) == Document.MIME_TYPE_DIR)
            null
        }

    private fun stat(uri: Uri): SyncFile? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) SyncFile(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)) else null
        }
    }.getOrNull()
}

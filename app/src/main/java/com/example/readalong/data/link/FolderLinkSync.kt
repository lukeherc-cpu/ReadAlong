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
 * Each sync lists both sides of every [FolderPair], copies what [FolderSyncPlan] says, and rescans the
 * phone folder, so new books, `.readalong` transcripts and `.readalong-progress` positions are picked up
 * exactly as if the folder had been synced any other way. Nothing is ever deleted on either side.
 */
class FolderLinkSync(private val context: Context, val store: LinkStore, private val rescan: suspend (Uri) -> Unit) {
    private val resolver = context.contentResolver
    private val lock = Mutex()
    private val mutableProgress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = mutableProgress

    val phoneName: String get() = runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /** Links with the computer that showed [code]. Returns the folders it offers. */
    suspend fun link(code: LinkCode): List<RemoteFolder> = withContext(Dispatchers.IO) {
        LinkClient.open(code.hosts, code.port, code.key, store.value.phoneId, phoneName).use { client ->
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
            mutableProgress.value = SyncProgress("Connecting to ${store.value.computer?.name}…")
            try {
                var copied = 0; var failed = 0
                connect().use { client ->
                    for (pair in pairs) {
                        val first = store.synced(pair).isEmpty()
                        val (done, failures) = syncPair(client, pair)
                        copied += done; failed += failures
                        if (done > 0 || first) runCatching { rescan(Uri.parse(pair.localUri)) }
                    }
                }
                val result = when {
                    failed > 0 -> "Copied $copied ${files(copied)}; $failed couldn't be copied and will be tried again."
                    copied == 0 -> "Up to date."
                    else -> "Copied $copied ${files(copied)}."
                }
                store.update { it.copy(lastSyncAt = System.currentTimeMillis(), lastResult = result) }
                result
            } finally { mutableProgress.value = null }
        }
    }

    private fun files(count: Int) = if (count == 1) "file" else "files"

    /** Connects to the linked computer and remembers where it was found. */
    private fun connect(): LinkClient {
        val computer = store.value.computer ?: throw IOException("This phone isn't linked to a computer.")
        val client = LinkClient.open(computer.hosts, computer.port, computer.key, store.value.phoneId, phoneName)
        if (client.host != computer.hosts.firstOrNull() || client.port != computer.port || client.computerName != computer.name)
            store.update { s -> s.copy(computer = s.computer?.copy(name = client.computerName, hosts = listOf(client.host) + (computer.hosts - client.host).take(4), port = client.port)) }
        return client
    }

    private suspend fun syncPair(client: LinkClient, pair: FolderPair): Pair<Int, Int> {
        val tree = Uri.parse(pair.localUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        mutableProgress.value = SyncProgress("Comparing ${pair.localName}…")
        val local = walk(tree, rootId)
        val remote = client.list(pair.remoteId)
        val previous = store.synced(pair)
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
                        val (received, local) = download(client, pair, tree, rootId, action.path) { done, size -> mutableProgress.value = SyncProgress("Copying from computer $label", fraction(done, size)) }
                        remoteNow[action.path] = received; localNow[action.path] = local
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
                store.saveSynced(pair, FolderSyncPlan.settled(localNow.values.toList(), remoteNow.values.toList(), previous))
                throw io
            }
        }
        store.saveSynced(pair, FolderSyncPlan.settled(localNow.values.toList(), remoteNow.values.toList(), previous))
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
            val received = resolver.openOutputStream(temporary, "w")?.use { output -> client.get(pair.remoteId, path, output.buffered(256 * 1024), progress) } ?: throw IOException("Couldn't write $name.")
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

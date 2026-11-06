package com.example.readalong.data.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.example.readalong.domain.link.BookStateFiles
import com.example.readalong.domain.link.BookStateFiles.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A book's "<book>.readalong-state" beside it (see [BookStateFiles]): its listening progress, bookmarks and
 * edited title and author in one file, which ReadAlong Desktop reads and writes too. [FolderProgressSync],
 * [FolderBookmarks] and [BookFolderFiles] each read and write their own part here.
 *
 * Books synced by older versions have a separate "<book>.readalong-progress", ".readalong-bookmarks" and
 * ".readalong-info" instead. Each read or write folds those into the state file and deletes them; in folders
 * the phone can only read they stay and are read as they are.
 */
class FolderBookState(context: Context) {
    private val resolver = context.contentResolver

    /** [part] as text in its own format, or null when neither the state file nor an old file has it. */
    suspend fun read(location: FolderProgressLocation, part: Part): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val folder = Uri.parse(location.folder); val base = BookFolderFiles.baseName(location)
            val files = convert(folder, base)
            BookStateFiles.section(files[(base + BookStateFiles.EXTENSION).lowercase()]?.let(::text), part)
                ?: files[(base + part.oldExtension).lowercase()]?.let(::text)
        }
    }

    /** Writes [section] as the book's [part], keeping the other parts. False where the folder can't be written. */
    suspend fun write(location: FolderProgressLocation, part: Part, section: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val folder = Uri.parse(location.folder); val base = BookFolderFiles.baseName(location)
            val files = convert(folder, base)
            val name = base + BookStateFiles.EXTENSION
            val existing = files[name.lowercase()]
            try {
                val updated = BookStateFiles.withSection(existing?.let(::text), part, section, DEVICE) ?: return@withLock false
                save(folder, existing, name, updated)
                files[(base + part.oldExtension).lowercase()]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                true
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // Folders added before write access was asked for, or a provider that refuses: this phone keeps its own copy.
                android.util.Log.i("ReadAlong", "$name not written: ${failure.javaClass.simpleName}")
                false
            }
        }
    }

    /** Folds the book's old separate files into its state file and deletes them. Returns the folder's files afterwards. */
    private fun convert(folder: Uri, base: String): Map<String, Uri> {
        val files = list(folder)
        val old = Part.entries.mapNotNull { part ->
            val uri = files[(base + part.oldExtension).lowercase()] ?: return@mapNotNull null
            text(uri)?.takeIf { runCatching { JSONObject(it).optString("format") == part.format }.getOrDefault(false) }?.let { part to (uri to it) }
        }.toMap()
        if (old.isEmpty()) return files
        val name = base + BookStateFiles.EXTENSION
        try {
            val existing = files[name.lowercase()]
            val updated = BookStateFiles.absorb(existing?.let(::text), old.mapValues { it.value.second }, DEVICE) ?: return files
            save(folder, existing, name, updated)
            old.values.forEach { (uri, _) -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            android.util.Log.i("ReadAlong", "Old sync files of $base not combined: ${failure.javaClass.simpleName}")
            return files
        }
        return list(folder)
    }

    private fun save(folder: Uri, existing: Uri?, name: String, text: String) {
        val target = existing ?: DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name) ?: error("$name couldn't be created.")
        val stream = try { resolver.openOutputStream(target, "wt") } catch (_: IllegalArgumentException) { resolver.openOutputStream(target, "w") }
        requireNotNull(stream).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun text(uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > BookStateFiles.MAX_BYTES) return@use null }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    /** The folder's files by lower-case name. */
    private fun list(folder: Uri): Map<String, Uri> = runCatching {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder)),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildMap { while (cursor.moveToNext()) cursor.getString(1)?.let { put(it.lowercase(), DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))) } }
        }
    }.getOrNull().orEmpty()

    private companion object {
        const val DEVICE = "Android"
        /** One lock for every book: reads and writes of different parts of the same file must not interleave. */
        val lock = Mutex()
    }
}

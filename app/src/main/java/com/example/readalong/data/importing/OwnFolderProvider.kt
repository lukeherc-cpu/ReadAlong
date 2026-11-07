package com.example.readalong.data.importing

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * ReadAlong's own "Audible Audiobooks" folder, served as a document tree so it is a library folder
 * like any the person chose: it is scanned, listed on the folders page and can hold ReadAlongs and
 * EPUBs beside the books. It also shows in the system Files app under ReadAlong once it exists.
 *
 * ReadAlong reaches its own provider without a permission grant, so the folder never needs choosing.
 */
class OwnFolderProvider : DocumentsProvider() {
    override fun onCreate() = true

    private fun base() = base(requireNotNull(context))

    private fun file(documentId: String): File {
        if (documentId == ROOT) return base()
        require(documentId.startsWith("$ROOT/")) { "Unknown document $documentId" }
        val file = File(base(), documentId.removePrefix("$ROOT/"))
        // Never step outside the folder, whatever an id says.
        if (!file.canonicalPath.startsWith(base().canonicalPath + File.separator)) throw FileNotFoundException(documentId)
        return file
    }

    private fun id(file: File) = if (file == base()) ROOT else "$ROOT/" + file.relativeTo(base()).path

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        if (!base().isDirectory) return@apply
        newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT).add(Root.COLUMN_TITLE, NAME)
            .add(Root.COLUMN_SUMMARY, "ReadAlong").add(Root.COLUMN_ICON, context?.applicationInfo?.icon)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_AVAILABLE_BYTES, base().freeSpace)
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { row(this, file(documentId).also { if (!it.exists()) throw FileNotFoundException(documentId) }) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { file(parentDocumentId).listFiles().orEmpty().filterNot { it.name.endsWith(PARTIAL) }.forEach { row(this, it) } }

    private fun row(cursor: MatrixCursor, file: File) {
        val flags = (if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id(file)).add(Document.COLUMN_DISPLAY_NAME, if (file == base()) NAME else file.name)
            .add(Document.COLUMN_MIME_TYPE, mime(file)).add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified()).add(Document.COLUMN_FLAGS, flags)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        runCatching { file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator) }.getOrDefault(false)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        var target = File(parent, displayName)
        val stem = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var copy = 1
        while (target.exists()) target = File(parent, "$stem (${copy++})$extension")
        val made = if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        if (!made) throw FileNotFoundException("Couldn't create $displayName")
        return id(target)
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT || !file(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val from = file(documentId)
        val to = File(from.parentFile, displayName)
        if (documentId == ROOT || to.exists() || !from.renameTo(to)) throw FileNotFoundException(documentId)
        return id(to)
    }

    private fun mime(file: File) = if (file.isDirectory) Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (file.extension.equals("m4b", true)) "audio/mp4" else "application/octet-stream"

    companion object {
        const val NAME = "Audible Audiobooks"
        private const val ROOT = "audible"
        /** Files still being written; kept out of listings so a scan never sees half a book. */
        const val PARTIAL = ".part"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        fun authority(context: Context) = "${context.packageName}.audiobooks"
        /** Where the folder's books live, in ReadAlong's own storage. */
        fun base(context: Context) = File(context.filesDir, NAME)
        /** The folder as a library folder uri, the way a chosen folder is saved. */
        fun treeUri(context: Context): Uri = DocumentsContract.buildTreeDocumentUri(authority(context), ROOT)
        /** ReadAlong's own folder needs no permission grant, so it never shows as lost. */
        fun isOwn(context: Context, uri: String) = Uri.parse(uri).authority == authority(context)
    }
}

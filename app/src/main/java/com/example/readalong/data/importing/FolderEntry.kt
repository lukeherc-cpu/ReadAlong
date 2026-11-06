package com.example.readalong.data.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * One file or folder inside a library folder, with its name, type, size and date read in the single
 * query that lists its folder.
 *
 * `DocumentFile.listFiles()` returns bare URIs: each later `name`, `isFile`, `isDirectory`, `length()` or
 * `lastModified()` is another query to the storage provider (a cross-process call). A scan asks these
 * many times per file, so a library of a few thousand files made tens of thousands of calls. This makes
 * one call per folder. URIs are built exactly as `DocumentFile` builds them, so book identities that
 * were saved from earlier scans stay the same.
 */
internal class FolderEntry(val uri: Uri, val name: String?, private val mime: String?, private val size: Long = 0, private val modified: Long = 0) {
    val isDirectory get() = mime == Document.MIME_TYPE_DIR
    val isFile get() = !isDirectory && !mime.isNullOrEmpty()
    fun length() = size
    fun lastModified() = modified

    companion object {
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

        /** A folder already known to be one (the chosen tree's root). */
        fun folder(uri: Uri, name: String?) = FolderEntry(uri, name, Document.MIME_TYPE_DIR)

        /** [directory]'s children; empty when the provider can't list it (as `DocumentFile.listFiles()`). */
        fun children(resolver: ContentResolver, directory: Uri): List<FolderEntry> = try {
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory)), COLUMNS, null, null, null)?.use { cursor ->
                val id = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
                val name = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                val mime = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val size = cursor.getColumnIndex(Document.COLUMN_SIZE)
                val modified = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
                fun long(column: Int) = if (column < 0 || cursor.isNull(column)) 0L else runCatching { cursor.getLong(column) }.getOrDefault(0L)
                buildList {
                    while (cursor.moveToNext()) {
                        val documentId = if (id < 0) null else cursor.getString(id)
                        if (documentId == null) continue
                        add(FolderEntry(DocumentsContract.buildDocumentUriUsingTree(directory, documentId),
                            if (name < 0) null else cursor.getString(name), if (mime < 0) null else cursor.getString(mime), long(size), long(modified)))
                    }
                }
            }.orEmpty()
        } catch (failure: Exception) {
            android.util.Log.w("ReadAlong", "Couldn't list $directory: ${failure.javaClass.simpleName}")
            emptyList()
        }
    }
}

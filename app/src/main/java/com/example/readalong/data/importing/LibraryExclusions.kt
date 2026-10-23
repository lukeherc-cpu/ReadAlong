package com.example.readalong.data.importing

import android.net.Uri
import android.provider.DocumentsContract
import com.example.readalong.data.local.*
import java.security.MessageDigest

/** Persisted separately from book metadata so a source rescan cannot undo Delete book. */
internal object LibraryExclusions {
    fun source(uri: Uri): String = try {
        if (uri.scheme == "content" && "document" in uri.pathSegments)
            "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
        else uri.normalizeScheme().toString()
    } catch (_: Exception) { uri.normalizeScheme().toString() }
    private fun key(kind: String, value: String) = "excluded_${kind}_" +
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun excluded(dao: BookDao, group: String, sources: List<Uri>): Boolean =
        dao.metadata(key("group", group)) != null || sources.any { dao.metadata(key("source", source(it))) != null }
    suspend fun restore(dao: BookDao, group: String, sources: List<Uri>) {
        dao.removeMetadata(key("group", group))
        sources.forEach { dao.removeMetadata(key("source", source(it))) }
    }
    suspend fun exclude(database: ReadAlongDatabase, bookId: String) {
        val dao = database.bookDao()
        dao.metadata("import_group:$bookId")?.let { dao.putMetadata(LibraryMetadataEntity(key("group", it), "deleted")) }
        database.mediaDao().audioFiles(bookId).forEach {
            dao.putMetadata(LibraryMetadataEntity(key("source", source(Uri.parse(it.uri))), "deleted"))
        }
    }
}

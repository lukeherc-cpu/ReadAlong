package com.example.readalong.data.book

import android.net.Uri
import android.provider.DocumentsContract
import com.example.readalong.data.local.BookFileRow
import com.example.readalong.domain.model.BookFileLocation

/** Turns stored audio file URIs into folder names for the library's folder view. */
object BookFolderPlaces {
    /**
     * The folders between [row]'s library folder and the file. Storage providers that expose paths in
     * their document IDs ("primary:Audiobooks/Fantasy/Book/01.mp3") give the full path. Providers with
     * opaque IDs give none, and the file's book is listed directly in its library folder.
     */
    fun location(row: BookFileRow): BookFileLocation {
        val dirs = runCatching {
            val folder = row.folderUri ?: return@runCatching emptyList()
            val tree = DocumentsContract.getTreeDocumentId(Uri.parse(folder))
            val document = DocumentsContract.getDocumentId(Uri.parse(row.uri))
            relativeDirs(tree, document)
        }.getOrDefault(emptyList())
        return BookFileLocation(row.bookId, row.folderUri, row.folderName, dirs)
    }

    /** The folder names from [treeId] down to the folder holding [documentId], or none when one isn't inside the other. */
    fun relativeDirs(treeId: String, documentId: String): List<String> {
        val prefix = if (treeId.endsWith(":") || treeId.endsWith("/")) treeId else "$treeId/"
        if (!documentId.startsWith(prefix)) return emptyList()
        return documentId.removePrefix(prefix).split('/').filter { it.isNotEmpty() }.dropLast(1)
    }
}

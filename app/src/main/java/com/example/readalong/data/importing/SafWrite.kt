package com.example.readalong.data.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream

/**
 * Replaces a file in a document tree. "wt" truncates; some providers refuse it, and their plain "w" can leave the
 * end of a longer old file behind, so a shorter new file read back as broken JSON or a broken EPUB. Where "wt"
 * is refused the new content goes into a hidden file first, then takes the old file's place.
 */
internal object SafWrite {
    fun replace(resolver: ContentResolver, folder: Uri, existing: Uri?, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        if (existing == null) {
            val created = DocumentsContract.createDocument(resolver, folder, mime, name) ?: throw IOException("$name couldn't be created.")
            (resolver.openOutputStream(created, "w") ?: throw IOException("$name couldn't be written.")).use(write)
            return created
        }
        val truncating = try { resolver.openOutputStream(existing, "wt") } catch (_: IllegalArgumentException) { null } catch (_: UnsupportedOperationException) { null }
        if (truncating != null) { truncating.use(write); return existing }
        val fresh = DocumentsContract.createDocument(resolver, folder, mime, ".$name.readalong-new") ?: throw IOException("$name couldn't be written.")
        try {
            (resolver.openOutputStream(fresh, "w") ?: throw IOException("$name couldn't be written.")).use(write)
        } catch (failure: Exception) { runCatching { DocumentsContract.deleteDocument(resolver, fresh) }; throw failure }
        DocumentsContract.deleteDocument(resolver, existing)
        return runCatching { DocumentsContract.renameDocument(resolver, fresh, name) }.getOrNull() ?: run {
            // No rename here either: copy the finished file to the real name.
            val target = DocumentsContract.createDocument(resolver, folder, mime, name) ?: throw IOException("$name couldn't be created.")
            resolver.openInputStream(fresh)!!.use { input -> resolver.openOutputStream(target, "w")!!.use { input.copyTo(it) } }
            runCatching { DocumentsContract.deleteDocument(resolver, fresh) }
            target
        }
    }
}

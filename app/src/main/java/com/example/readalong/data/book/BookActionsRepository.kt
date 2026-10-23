package com.example.readalong.data.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.example.readalong.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class BookActionsRepository(private val context: Context, private val database: ReadAlongDatabase) {
    suspend fun edit(bookId: String, title: String? = null, author: String? = null, cover: Uri? = null) = withContext(Dispatchers.IO) {
        val replacement = cover?.let { copyCover(it) }
        try {
            database.withTransaction {
                val dao = database.bookDao()
                val book = requireNotNull(dao.findBook(bookId)) { "This book is no longer in your library." }
                if (title != null) require(title.trim().isNotEmpty()) { "Enter a title." }
                val updated = book.copy(title = title?.trim()?.take(500) ?: book.title,
                    author = if (author != null) author.trim().take(500).ifEmpty { null } else book.author,
                    coverUri = replacement?.second ?: book.coverUri)
                dao.upsertBooks(listOf(updated))
                if (title != null) dao.putMetadata(LibraryMetadataEntity("override:$bookId:title", updated.title))
                if (author != null) dao.putMetadata(LibraryMetadataEntity("override:$bookId:author", updated.author.orEmpty()))
                if (replacement != null) dao.putMetadata(LibraryMetadataEntity("override:$bookId:cover", replacement.second))
            }
        } catch (failure: Exception) { replacement?.first?.delete(); throw failure }
    }

    private fun copyCover(uri: Uri): Pair<File, String> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "Choose a readable image file." }
        var sample = 1
        while (options.outWidth / sample > 1600 || options.outHeight / sample > 1600) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample
        val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("This cover image couldn't be opened.")
        val directory = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(directory, "custom-${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
        } catch (failure: Exception) { file.delete(); throw failure }
        finally { bitmap.recycle() }
    }

    suspend fun notStarted(bookId: String) = database.withTransaction {
        requireNotNull(database.bookDao().findBook(bookId))
        database.bookDao().insertProgress(listOf(BookProgressEntity(bookId)))
        database.bookDao().markNotStarted(bookId)
        database.bookDao().removeMetadata("file:$bookId")
        database.bookDao().removeMetadata("file_position:$bookId")
        database.bookDao().removeMetadata("live_reading_revision:$bookId")
    }

    suspend fun delete(bookId: String, excludeFromScans: Boolean = true) = withContext(Dispatchers.IO) {
        val prefix = com.example.readalong.domain.epub.epubFragment(bookId)
        val cover = database.bookDao().findBook(bookId)?.coverUri
        val edition = database.importedEpubDao().find(bookId)?.editionId
        database.withTransaction {
        if (excludeFromScans) com.example.readalong.data.importing.LibraryExclusions.exclude(database, bookId)
        database.bookDao().removeBook(bookId)
        database.bookDao().removeMetadata("epub_alignment_version:$bookId")
        database.bookDao().removeMetadata("preparation_retry:$bookId")
        database.bookDao().removeMetadata("keep_queue_order:$bookId")
        listOf("title", "author", "cover").forEach { database.bookDao().removeMetadata("override:$bookId:$it") }
        database.bookDao().removeMetadata("file:$bookId")
        database.bookDao().removeMetadata("file_position:$bookId")
        database.bookDao().removeMetadata("live_reading_revision:$bookId")
        if (database.bookDao().metadata("current_book") == bookId) database.bookDao().removeMetadata("current_book")
        if (database.bookDao().metadata("paused_transcription_notification") == bookId) database.bookDao().removeMetadata("paused_transcription_notification")
        database.bookDao().removeBookMetadata(bookId)
        edition?.let { id ->
            database.bookDao().removeMetadata("epub_contents:$id")
            database.bookDao().removeMetadata("epub_presentation:$id")
        }
        }
        File(context.filesDir, "editions").listFiles()?.filter { it.name.startsWith("$prefix-") || it.name == "$prefix.epub" }?.forEach { it.delete() }
        // Only application-owned cover copies; never remove the original image/audio documents.
        cover?.let { uri -> runCatching {
            val parsed = Uri.parse(uri)
            if (parsed.authority == "${context.packageName}.files") {
                val name = parsed.lastPathSegment ?: return@runCatching
                File(context.filesDir, "covers/$name").takeIf { it.parentFile?.canonicalFile == File(context.filesDir, "covers").canonicalFile }?.delete()
            }
        } }
    }
}

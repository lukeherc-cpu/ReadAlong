package com.example.readalong.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.model.LibrarySection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What Android Auto (and other media browsers) can browse: Started, New, Finished and Collections, as on the phone.
 * Books are "book:<id>"; picking one hands that id to the player, which loads the whole book.
 */
internal class AutoLibrary(private val app: ReadAlongApplication) {
    private val art = HashMap<String, ByteArray?>()

    fun root(): MediaItem = folder(ROOT, "ReadAlong")

    suspend fun children(parent: String): List<MediaItem> {
        if (parent == ROOT) return listOf(folder(STARTED, "Started"), folder(NEW, "New"), folder(FINISHED, "Finished"), folder(COLLECTIONS, "Collections"))
        val books = books()
        if (parent == COLLECTIONS) return com.example.readalong.domain.model.collectionRows(app.collections.all.first(), books, com.example.readalong.domain.model.LibrarySort.TITLE)
            .map { row -> folder(COLLECTION + (row.collection?.id ?: LOOSE), row.name) }
        if (parent.startsWith(COLLECTION)) {
            val id = parent.removePrefix(COLLECTION)
            val rows = com.example.readalong.domain.model.collectionRows(app.collections.all.first(), books, com.example.readalong.domain.model.LibrarySort.TITLE)
            return rows.firstOrNull { (it.collection?.id ?: LOOSE) == id }?.books.orEmpty().take(200).map { item(it) }
        }
        val chosen = when (parent) {
            STARTED -> books.filter { it.section == LibrarySection.CURRENT }.sortedByDescending { it.lastPlayedAt ?: 0 }
            NEW -> books.filter { it.section == LibrarySection.NOT_STARTED }.sortedByDescending { it.addedAt }
            FINISHED -> books.filter { it.section == LibrarySection.FINISHED }.sortedByDescending { it.lastPlayedAt ?: 0 }
            else -> return emptyList()
        }
        return chosen.take(200).map { item(it) }
    }

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT -> root()
        STARTED -> folder(STARTED, "Started")
        NEW -> folder(NEW, "New")
        FINISHED -> folder(FINISHED, "Finished")
        COLLECTIONS -> folder(COLLECTIONS, "Collections")
        else -> mediaId.removePrefix(BOOK).takeIf { mediaId.startsWith(BOOK) }?.let { id -> books().firstOrNull { it.id == id } }?.let { item(it) }
    }

    /** The book to resume when the car asks to play with nothing chosen: the last one played. */
    suspend fun current(): MediaItem? = app.database.bookDao().metadata("current_book")?.let { item(BOOK + it) }

    private suspend fun books(): List<Book> = app.database.bookDao().observeBooks().first().map { it.toBook() }.filter { !it.isDemo }

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
        .setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) })
        .build()).build()

    private suspend fun item(book: Book): MediaItem {
        val duration = book.durationMs ?: 0
        val done = if (duration > 0) (book.positionMs.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
        val status = when (book.section) {
            LibrarySection.FINISHED -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
            LibrarySection.CURRENT -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
            else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        }
        val subtitle = listOfNotNull(book.author?.takeIf { it.isNotBlank() }, if (book.section == LibrarySection.CURRENT && duration > 0) "${(done * 100).toInt()}%" else null).joinToString(" · ")
        val metadata = MediaMetadata.Builder().setTitle(book.title).setArtist(book.author).setSubtitle(subtitle)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
                if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, done)
            })
        cover(book)?.let { metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(BOOK + book.id).setMediaMetadata(metadata.build()).build()
    }

    /** A small JPEG of the cover: the car can't open the phone's own cover files. */
    private suspend fun cover(book: Book): ByteArray? {
        val key = book.id + "|" + book.coverUri
        if (art.containsKey(key)) return art[key]
        val bytes = withContext(Dispatchers.IO) {
            val bitmap = book.coverUri?.let { com.example.readalong.ui.widget.Widgets.loadCover(app, it, 256) } ?: return@withContext null
            java.io.ByteArrayOutputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
        }
        art[key] = bytes
        return bytes
    }

    companion object {
        const val ROOT = "root"
        const val STARTED = "started"
        const val NEW = "new"
        const val FINISHED = "finished"
        const val BOOK = "book:"
        const val COLLECTIONS = "collections"
        const val COLLECTION = "collection:"
        private const val LOOSE = "-"
    }
}

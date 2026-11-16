package com.example.readalong.data.book

import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.model.SoundSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Sound settings live in library_metadata: "sound_default" for every book (Settings → Sound), and
 * "sound:<book>" for a book changed from its own Sound sheet, which wins until it's reset.
 */
class SoundRepository(private val database: ReadAlongDatabase) {
    private val dao get() = database.bookDao()

    val defaults: Flow<SoundSettings> = dao.observeMetadata(DEFAULT_KEY).map { SoundSettings.decode(it) ?: SoundSettings.DEFAULT }.distinctUntilChanged()

    /** The book's own settings, or null when it follows the defaults. */
    fun own(bookId: String): Flow<SoundSettings?> = dao.observeMetadata(key(bookId)).map { SoundSettings.decode(it) }.distinctUntilChanged()

    /** What the book plays with. */
    fun effective(bookId: String?): Flow<SoundSettings> =
        if (bookId == null) defaults else combine(defaults, own(bookId)) { default, own -> own ?: default }.distinctUntilChanged()

    suspend fun saveDefaults(value: SoundSettings) = dao.putMetadata(LibraryMetadataEntity(DEFAULT_KEY, value.encode()))
    suspend fun saveForBook(bookId: String, value: SoundSettings) = dao.putMetadata(LibraryMetadataEntity(key(bookId), value.encode()))
    /** Back to the defaults from Settings. */
    suspend fun resetBook(bookId: String) = dao.removeMetadata(key(bookId))

    private fun key(bookId: String) = "sound:$bookId"
    private companion object { const val DEFAULT_KEY = "sound_default" }
}

package com.example.readalong.data.book

import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.model.CharacterEntry
import com.example.readalong.domain.model.CharacterList
import com.example.readalong.domain.model.HistoryEntry
import com.example.readalong.domain.model.ListeningHistory
import com.example.readalong.domain.model.NameSuggestion
import com.example.readalong.domain.model.suggestNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Each book's listening history and Character List, kept in `library_metadata` as `history:<book>` and
 * `characters:<book>`, so they go when the book is deleted (keys end in `:<book>`) and need no new table.
 */
class BookNotesRepository(private val database: ReadAlongDatabase) {
    private val dao = database.bookDao()
    private val lock = Mutex()

    fun history(bookId: String): Flow<List<HistoryEntry>> = dao.observeMetadata(historyKey(bookId)).map(ListeningHistory::decode)

    suspend fun addHistory(bookId: String, entry: HistoryEntry) = lock.withLock {
        val now = ListeningHistory.decode(dao.metadata(historyKey(bookId)))
        dao.putMetadata(LibraryMetadataEntity(historyKey(bookId), ListeningHistory.encode(ListeningHistory.add(now, entry))))
    }

    fun characters(bookId: String): Flow<List<CharacterEntry>> = dao.observeMetadata(charactersKey(bookId)).map { CharacterList.sorted(CharacterList.decode(it)) }

    /** Adds or replaces [entry] (by id); a new name with no first mention gets one from the transcript. */
    suspend fun saveCharacter(bookId: String, entry: CharacterEntry) = lock.withLock {
        val firstMs = entry.firstMs ?: firstMention(bookId, entry.name)
        val list = CharacterList.decode(dao.metadata(charactersKey(bookId))).filter { it.id != entry.id } + entry.copy(firstMs = firstMs)
        dao.putMetadata(LibraryMetadataEntity(charactersKey(bookId), CharacterList.encode(list)))
    }

    suspend fun removeCharacter(bookId: String, id: String) = lock.withLock {
        val list = CharacterList.decode(dao.metadata(charactersKey(bookId))).filter { it.id != id }
        dao.putMetadata(LibraryMetadataEntity(charactersKey(bookId), CharacterList.encode(list)))
    }

    /** Names worth adding, from the transcript up to [positionMs] only, so nothing ahead is given away. */
    suspend fun suggestions(bookId: String, positionMs: Long, known: Collection<String>): List<NameSuggestion> = withContext(Dispatchers.Default) {
        suggestNames(database.readingDao().textUntil(bookId, positionMs).map { it.startMs to it.text }, known)
    }

    private suspend fun firstMention(bookId: String, name: String): Long? = withContext(Dispatchers.Default) {
        val needle = name.trim().lowercase(Locale.ROOT).takeIf { it.length >= 2 } ?: return@withContext null
        database.readingDao().textUntil(bookId, Long.MAX_VALUE).firstOrNull { it.text.lowercase(Locale.ROOT).contains(needle) }?.startMs
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        private fun historyKey(bookId: String) = "history:$bookId"
        private fun charactersKey(bookId: String) = "characters:$bookId"
    }
}

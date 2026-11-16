package com.example.readalong.data.book

import com.example.readalong.data.local.BookDao
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.domain.model.BookCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The listener's collections, one `collection:<id>` row each in the library's key-value table. */
class CollectionStore(private val books: BookDao) {
    val all: Flow<List<BookCollection>> = books.observeMetadataWithPrefix(PREFIX).map { rows ->
        rows.mapNotNull { BookCollection.decode(it.key.removePrefix(PREFIX), it.value) }
    }

    /** Makes a collection holding [bookIds], and returns it. */
    suspend fun create(name: String, bookIds: List<String> = emptyList()): BookCollection {
        val collection = BookCollection(java.util.UUID.randomUUID().toString(), BookCollection.cleanName(name), bookIds.distinct(), System.currentTimeMillis())
        require(collection.name.isNotBlank()) { "Give the collection a name." }
        save(collection)
        return collection
    }

    suspend fun save(collection: BookCollection) = books.putMetadata(LibraryMetadataEntity(PREFIX + collection.id, collection.encode()))
    suspend fun rename(collection: BookCollection, name: String) = BookCollection.cleanName(name).takeIf { it.isNotBlank() }?.let { save(collection.copy(name = it)) }
    suspend fun setBooks(collection: BookCollection, bookIds: List<String>) = save(collection.copy(bookIds = bookIds.distinct()))
    suspend fun setInside(collection: BookCollection, bookId: String, inside: Boolean) = save(collection.with(bookId, inside))
    /** Removes the collection only; its books stay in the library. */
    suspend fun delete(collection: BookCollection) = books.removeMetadata(PREFIX + collection.id)

    companion object { const val PREFIX = "collection:" }
}

package com.example.readalong.desktop.core

import com.example.readalong.domain.model.Book
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One audio file of a book. [offsetMs] is where it starts on the book's timeline. */
data class AudioPart(val path: String, val durationMs: Long, val offsetMs: Long, val size: Long, val modified: Long) {
    val endMs get() = offsetMs + durationMs
    val name: String get() = File(path).name
}
data class Chapter(val title: String, val startMs: Long)
data class Bookmark(val positionMs: Long, val name: String, val createdAt: Long)

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String? = null,
    /** The scan key ("folder:<path>" or "file:<path>") that keeps the id stable across rescans. */
    val key: String,
    val folder: String? = null,
    val parts: List<AudioPart>,
    val chapters: List<Chapter> = emptyList(),
    val coverPath: String? = null,
    val epubPath: String? = null,
    val positionMs: Long = 0,
    val finished: Boolean = false,
    val lastPlayedAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val bookmarks: List<Bookmark> = emptyList(),
    /** "GENERATED" reads the transcript; "EPUB" reads the matched EPUB. */
    val textSource: String = "GENERATED",
    val speed: Float = 1f,
) {
    val durationMs: Long get() = parts.sumOf { it.durationMs }
    fun asBook() = Book(id, title, author, coverPath, null, durationMs, positionMs, finished, lastPlayedAt, addedAt)
    fun partAt(positionMs: Long): Int = parts.indexOfLast { it.offsetMs <= positionMs }.coerceAtLeast(0)
    fun chapterAt(positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }
    val remainingMs get() = if (finished) 0 else (durationMs - positionMs).coerceAtLeast(0)
    val progress: Float get() = if (finished) 1f else if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** The library: books and the folders they were found in, saved as JSON in [AppPaths.library]. */
class LibraryStore(private val file: File = AppPaths.library, private val scope: CoroutineScope) {
    private val mutableBooks = MutableStateFlow(emptyList<LibraryBook>())
    val books: StateFlow<List<LibraryBook>> = mutableBooks
    private val mutableFolders = MutableStateFlow(emptyList<String>())
    val folders: StateFlow<List<String>> = mutableFolders
    private val saves = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        load()
        // Positions change several times a second while playing; write at most once every 2 s.
        scope.launch(Dispatchers.IO) { saves.conflate().collect { save(); delay(2000) } }
    }

    fun book(id: String) = mutableBooks.value.firstOrNull { it.id == id }
    fun observe(id: String) = books.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun update(id: String, change: (LibraryBook) -> LibraryBook) {
        mutableBooks.update { list -> list.map { if (it.id == id) change(it) else it } }
        saves.tryEmit(Unit)
    }
    fun replaceAll(change: (List<LibraryBook>) -> List<LibraryBook>) { mutableBooks.update(change); saves.tryEmit(Unit) }
    fun setFolders(folders: List<String>) { mutableFolders.value = folders.distinct(); saves.tryEmit(Unit) }
    fun remove(id: String) {
        mutableBooks.update { list -> list.filterNot { it.id == id } }
        AppPaths.book(id).deleteRecursively()
        saves.tryEmit(Unit)
    }
    fun flush() = save()

    @Synchronized private fun save() {
        val root = JSONObject().put("version", 1).put("folders", JSONArray(mutableFolders.value))
            .put("books", JSONArray(mutableBooks.value.map { encode(it) }))
        file.writeAtomically(root.toString(1))
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            mutableFolders.value = root.optJSONArray("folders")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            mutableBooks.value = root.getJSONArray("books").let { a -> List(a.length()) { decode(a.getJSONObject(it)) } }
        }.onFailure { System.err.println("ReadAlong: the library file couldn't be read (${it.message}); starting empty.") }
    }

    companion object {
        fun encode(b: LibraryBook): JSONObject = JSONObject().put("id", b.id).put("title", b.title).put("author", b.author)
            .put("key", b.key).put("folder", b.folder).put("cover", b.coverPath).put("epub", b.epubPath)
            .put("position", b.positionMs).put("finished", b.finished).put("lastPlayed", b.lastPlayedAt).put("added", b.addedAt)
            .put("textSource", b.textSource).put("speed", b.speed.toDouble())
            .put("parts", JSONArray(b.parts.map { JSONObject().put("path", it.path).put("duration", it.durationMs).put("offset", it.offsetMs).put("size", it.size).put("modified", it.modified) }))
            .put("chapters", JSONArray(b.chapters.map { JSONObject().put("title", it.title).put("start", it.startMs) }))
            .put("bookmarks", JSONArray(b.bookmarks.map { JSONObject().put("position", it.positionMs).put("name", it.name).put("created", it.createdAt) }))

        fun decode(o: JSONObject): LibraryBook {
            fun JSONObject.text(name: String) = if (has(name) && !isNull(name)) getString(name) else null
            fun <T> JSONObject.list(name: String, item: (JSONObject) -> T) = optJSONArray(name)?.let { a -> List(a.length()) { item(a.getJSONObject(it)) } }.orEmpty()
            return LibraryBook(o.getString("id"), o.getString("title"), o.text("author"), o.getString("key"), o.text("folder"),
                o.list("parts") { AudioPart(it.getString("path"), it.getLong("duration"), it.getLong("offset"), it.optLong("size"), it.optLong("modified")) },
                o.list("chapters") { Chapter(it.getString("title"), it.getLong("start")) }, o.text("cover"), o.text("epub"),
                o.optLong("position"), o.optBoolean("finished"), if (o.isNull("lastPlayed") || !o.has("lastPlayed")) null else o.getLong("lastPlayed"),
                o.optLong("added"), o.list("bookmarks") { Bookmark(it.getLong("position"), it.getString("name"), it.optLong("created")) },
                o.optString("textSource", "GENERATED"), o.optDouble("speed", 1.0).toFloat())
        }
    }
}

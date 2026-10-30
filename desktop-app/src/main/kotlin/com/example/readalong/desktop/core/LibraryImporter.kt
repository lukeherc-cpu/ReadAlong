package com.example.readalong.desktop.core

import com.example.readalong.desktop.transcription.TranscriptStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File

/** [adding]: books are being added from a newly chosen folder or file, rather than a library rescan. */
data class ScanState(val running: Boolean, val label: String, val found: Int = 0, val adding: Boolean = false)

/** Adds books from folders and files, and keeps saved library folders up to date. */
class LibraryImporter(private val library: LibraryStore, private val transcripts: TranscriptStore) {
    private val mutableState = MutableStateFlow(ScanState(false, ""))
    val state: StateFlow<ScanState> = mutableState
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Adds [paths]: library folders are remembered and rescanned; single books are added once. Returns the added book ids. */
    suspend fun add(paths: List<File>, remember: Boolean = true): List<String> = locked {
        if (remember) library.setFolders(library.folders.value + paths.filter { it.isDirectory }.map { it.absolutePath })
        importFound(paths.flatMap { Scanner.scan(it) }, scannedRoots = emptyList(), adding = true)
    }

    /** Rescans every saved library folder: new books appear, books whose files are gone are removed. */
    suspend fun rescan(): List<String> = locked {
        val roots = library.folders.value.map(::File).filter { it.isDirectory }
        importFound(roots.flatMap { Scanner.scan(it) }, roots, adding = false)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { try { block() } finally { mutableState.value = ScanState(false, "") } }
    }

    private suspend fun importFound(found: List<FoundBook>, scannedRoots: List<File>, adding: Boolean): List<String> {
        val added = mutableListOf<String>()
        mutableState.value = ScanState(true, "Looking for audiobooks…", found.size, adding)
        val keys = found.map { it.key }.toSet()
        // Books under a rescanned folder whose files are gone leave the library.
        library.replaceAll { list -> list.filterNot { book -> scannedRoots.any { root -> book.parts.firstOrNull()?.path?.startsWith(root.path) == true } && book.key !in keys && book.parts.none { File(it.path).isFile } } }
        for ((index, item) in found.withIndex()) {
            currentCoroutineContext().ensureActive()
            val id = Scanner.bookId(item.key)
            val existing = library.book(id)
            val unchanged = existing != null && existing.parts.map { it.path } == item.audio.map { it.path } &&
                existing.parts.zip(item.audio).all { (part, file) -> part.size == file.length() && part.modified == file.lastModified() }
            if (unchanged) { adoptCompanions(existing!!, item); continue }
            mutableState.value = ScanState(true, "Adding ${item.name} (${index + 1} of ${found.size})", found.size, adding)
            val book = runCatching { build(id, item, existing) }.getOrElse {
                System.err.println("ReadAlong: skipped ${item.name}: ${it.message}"); null
            } ?: continue
            if (existing == null) { library.replaceAll { it + book }; added += id } else library.update(id) { book }
            adoptCompanions(book, item)
        }
        return added
    }

    /** An EPUB or a .readalong package beside the audio is picked up automatically. */
    private fun adoptCompanions(book: LibraryBook, item: FoundBook) {
        if (book.epubPath == null) item.epubs.firstOrNull()?.let { epub -> library.update(book.id) { it.copy(epubPath = epub.path, textSource = "EPUB") } }
        if (transcripts.info(book.id) == null) item.transcripts.firstOrNull()?.let { file ->
            runCatching { transcripts.importPackage(library.book(book.id) ?: book, file) }
                .onFailure { System.err.println("ReadAlong: ${file.name} wasn't imported: ${it.message}") }
        }
    }

    private fun build(id: String, item: FoundBook, existing: LibraryBook?): LibraryBook {
        val probed = item.audio.mapIndexed { i, file -> MediaProbe.probe(file, wantCover = i == 0 && item.images.isEmpty()) }
        var offset = 0L
        val parts = probed.map { p -> AudioPart(p.file.path, p.durationMs, offset, p.file.length(), p.file.lastModified()).also { offset += p.durationMs } }
        require(parts.isNotEmpty() && parts.all { it.durationMs > 0 }) { "The audio length couldn't be read." }
        val tags = probed.first().tags
        val embedded = probed.flatMapIndexed { i, p -> p.chapters.map { Chapter(it.title.ifBlank { "Chapter" }, parts[i].offsetMs + it.startMs) } }
        // Without chapter marks, each file of a multi-file book is a chapter, named by its title tag or file name.
        val chapters = embedded.ifEmpty {
            if (parts.size > 1) probed.mapIndexed { i, p -> Chapter(p.tags["title"]?.takeIf { it.isNotBlank() } ?: p.file.nameWithoutExtension, parts[i].offsetMs) } else emptyList()
        }
        val title = (if (item.key.startsWith("folder:")) tags["album"] else tags["album"] ?: tags["title"])?.takeIf { it.isNotBlank() } ?: item.name
        val author = listOf("album_artist", "artist", "composer").firstNotNullOfOrNull { tags[it]?.takeIf(String::isNotBlank) }
        val cover = item.images.maxByOrNull { image ->
            val name = image.nameWithoutExtension.lowercase()
            (if (name in setOf("cover", "folder", "front")) 1L shl 40 else 0L) + image.length()
        }?.path ?: probed.first().cover?.let { bytes -> File(AppPaths.covers, "$id.img").apply { writeBytes(bytes) }.path }
        return LibraryBook(id, existing?.title ?: title, existing?.author ?: author, item.key, item.folder?.path, parts, chapters,
            existing?.coverPath ?: cover, existing?.epubPath ?: item.epubs.firstOrNull()?.path,
            positionMs = existing?.positionMs?.coerceAtMost(offset) ?: 0, finished = existing?.finished ?: false,
            lastPlayedAt = existing?.lastPlayedAt, addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            bookmarks = existing?.bookmarks.orEmpty(), textSource = existing?.textSource ?: if (item.epubs.isNotEmpty()) "EPUB" else "GENERATED",
            speed = existing?.speed ?: 1f)
    }
}

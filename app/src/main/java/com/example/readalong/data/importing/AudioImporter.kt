package com.example.readalong.data.importing

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.example.readalong.data.local.*
import com.example.readalong.domain.model.audioNameComparator
import com.example.readalong.domain.audio.AudioChapter
import com.example.readalong.domain.audio.Mp4Chapters
import com.example.readalong.domain.audio.Id3Chapters
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class FolderMode { ONE_BOOK, LIBRARY }
/** One book found by [AudioImporter.preview]; [deleted] when it was deleted from ReadAlong earlier. */
data class PreviewBook(val name: String, val deleted: Boolean, val cover: Uri? = null, val firstAudio: Uri? = null)
/** What a chosen folder holds, shown before it is added. */
data class FolderPreview(val uri: Uri, val name: String, val books: List<PreviewBook>, val suggested: FolderMode, val deletedAsOneBook: Boolean, val emptyMessage: String?) {
    val deletedBooks get() = books.count { it.deleted }
}
data class ImportResult(val bookIds: List<String>, val message: String, val newBookIds: List<String> = emptyList())

/** Saved on a folder while it is scanned; it stays only if the scan was cut short. */
const val SCAN_INCOMPLETE = "Scan incomplete. Rescan to continue."

class AudioImporter(private val context: Context, private val database: ReadAlongDatabase, private val onEpubFound: suspend (String, Uri) -> Unit = { _, _ -> }, private val onImported: suspend (String) -> Unit = {}, private val onTranscriptFound: suspend (String, Uri) -> Unit = { _, _ -> },
    private val onProgressFound: suspend (String, FolderProgressLocation) -> Unit = { _, _ -> }) {
    private val resolver = context.contentResolver
    private val media = database.mediaDao()
    private val books = database.bookDao()
    private val folderLock = kotlinx.coroutines.sync.Mutex()
    private val importLock = kotlinx.coroutines.sync.Mutex()
    val folders = media.observeFolders()
    /** Book ids by saved folder uri. */
    val folderBooks = media.observeFolderBooks().map { rows -> rows.groupBy({ it.folderUri }, { it.bookId }) }

    suspend fun matchedEpub(bookId: String): Boolean {
        if (database.importedEpubDao().find(bookId) != null) return true
        val uri = books.metadata("folder_epub:$bookId") ?: return false
        onEpubFound(bookId, Uri.parse(uri))
        return database.importedEpubDao().find(bookId) != null
    }

    suspend fun title(bookId: String) = books.findBook(bookId)?.title ?: "Audiobook"
    suspend fun duration(bookId: String) = books.findBook(bookId)?.durationMs
    /** An EPUB was added or found beside the audio; unlike [matchedEpub] this doesn't import it. */
    suspend fun hasFolderEpub(bookId: String) = database.importedEpubDao().find(bookId) != null || books.metadata("folder_epub:$bookId") != null

    fun persist(uri: Uri) {
        // Write access too where it was granted, so listening progress can be saved beside the book.
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { error("This provider cannot keep access to the selection. Choose a file or folder from device storage.") }
        }
    }

    suspend fun importFiles(uris: List<Uri>, persistAccess: Boolean = true): ImportResult = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "No audio files selected." }
        if (persistAccess) uris.forEach(::persist)
        val selected = uris.distinct().map { Source(it, displayName(it)) }.filter { supported(it.name) }
        require(selected.isNotEmpty()) { "Choose MP3, M4A, M4B, AAC, OGG, FLAC or WAV audio files." }
        val key = selected.map { it.uri.toString() }.sorted().joinToString("\n")
        val (id, added) = requireNotNull(importGroup(selected, "files:$key", null, null, explicit = true))
        ImportResult(listOf(id), "Audiobook added. Open it to listen.", if (added) listOf(id) else emptyList())
    }

    /**
     * [restoreDeleted]: the person chose this folder themselves, so books in it they deleted earlier
     * come back. Choosing a folder in the other mode than it was saved with replaces its books, e.g.
     * a whole library that was added by mistake as one book is split into its books.
     */
    suspend fun addFolder(uri: Uri, mode: FolderMode, persistAccess: Boolean = true, restoreDeleted: Boolean = mode == FolderMode.ONE_BOOK): ImportResult = withContext(Dispatchers.IO) {
        if (persistAccess) persist(uri)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Couldn't open this folder." }
        folderLock.withLock {
            media.folder(uri.toString())?.takeIf { it.mode != mode.name }?.let { dropFolderBooks(it.uri) }
            media.putFolder(FolderEntity(uri.toString(), root.name ?: "Audiobooks", mode.name, null, SCAN_INCOMPLETE))
            scanLocked(uri, explicit = restoreDeleted)
        }
    }

    /**
     * Looks inside a folder before it is added, for the Add audiobooks preview: the books a library
     * scan would find, whether it looks like one book or a library, and which books were deleted earlier.
     */
    suspend fun preview(uri: Uri): FolderPreview = withContext(Dispatchers.IO) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Couldn't open this folder." }
        check(root.exists() && root.canRead()) { "Couldn't read this folder. Choose it again." }
        val walk = Walk()
        val groups = libraryGroups(root, walk).filter { it.found.audio.isNotEmpty() }
        val name = root.name ?: "Audiobooks"
        val children = walk.list(root)
        val looseAudio = children.any { it.isFile && supported(it.name.orEmpty()) }
        val directories = children.filter { it.isDirectory }
        // "CD1", "CD2" folders straight inside: one book in parts. "Chapter 01", "Chapter 02" (names
        // that differ only by their numbers) too. Either guess can be switched in the preview.
        val discs = !looseAudio && directories.isNotEmpty() && directories.all { DISC.matches(it.name.orEmpty()) }
        val numbered = groups.size >= 2 && groups.map { it.name.orEmpty().replace(Regex("\\d+"), "").trim().lowercase() }.distinct().size == 1
        val suggested = if (groups.size >= 2 && !discs && !numbered) FolderMode.LIBRARY else FolderMode.ONE_BOOK
        val found = groups.map { group -> PreviewBook(group.name ?: "Audiobook", LibraryExclusions.excluded(books, group.key, group.found.audio.map { it.uri }),
            coverImage(group.found.images)?.uri, group.found.audio.firstOrNull()?.uri) }
        FolderPreview(uri, name, found, suggested,
            deletedAsOneBook = groups.isNotEmpty() && LibraryExclusions.excluded(books, "folder:${root.uri}", groups.flatMap { g -> g.found.audio.map { it.uri } }),
            emptyMessage = if (groups.isEmpty()) walk.nothingFound(name) + walk.problemNote() else null)
    }

    suspend fun rescanSavedFolders(onFolder: (FolderEntity?) -> Unit = {}) = withContext(Dispatchers.IO) {
        try { media.savedFolders().forEach { folder ->
            onFolder(folder)
            try { scan(Uri.parse(folder.uri)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Per-folder access errors are retained for the folders screen. */ }
        } } finally { onFolder(null) }
    }

    suspend fun scan(uri: Uri): ImportResult = folderLock.withLock { scanLocked(uri) }

    suspend fun bookCount(folderUri: String): Int = withContext(Dispatchers.IO) { media.folderBooks(folderUri).size }

    private suspend fun scanLocked(uri: Uri, explicit: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        val saved = requireNotNull(media.folder(uri.toString())) { "Folder is no longer saved." }
        try {
            media.putFolder(saved.copy(error = SCAN_INCOMPLETE))
            val root = requireNotNull(DocumentFile.fromTreeUri(context, uri))
            check(root.exists() && root.canRead()) { "Folder access was lost. Add this folder again to restore access." }
            val walk = Walk()
            val groups = if (saved.mode == FolderMode.ONE_BOOK.name) listOf(Group("folder:${root.uri}", root.name, collect(root, walk), root)) else libraryGroups(root, walk)
            val ids = mutableListOf<String>()
            val added = mutableListOf<String>()
            var failures = 0
            var transcriptFailures = 0
            var deleted = 0
            // Each book stands alone: one that can't be read is named in the result, never stops the others.
            groups.filter { it.found.audio.isEmpty() && it.found.skipped.isNotEmpty() }.forEach { walk.problem(it.name, "no audio ReadAlong can play: ${Walk.counts(it.found.skipped)}") }
            groups.filter { it.found.audio.isNotEmpty() }.forEach { group ->
                val found = group.found
                coroutineContext.ensureActive()
                try {
                    val (id, isNew) = importGroup(found.audio, group.key, uri.toString(), group.name, explicit, coverImage(found.images)?.uri) ?: run { deleted++; return@forEach }
                    ids += id; if (isNew) added += id
                    val epubs = found.epubs
                    val selected = if (epubs.size == 1) epubs.single() else epubs.singleOrNull { it.name?.substringBeforeLast('.')?.equals(group.name, true) == true }
                    if (selected != null) {
                        books.putMetadata(LibraryMetadataEntity("folder_epub:$id", selected.uri.toString()))
                        // A bad EPUB never rejects playable audio. Keep it discoverable for a retry.
                        if (database.importedEpubDao().find(id) == null) try { onEpubFound(id, selected.uri) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { }
                    }
                    for (transcript in found.transcripts.sortedByDescending { it.lastModified() }) {
                        val signature = "${transcript.uri}:${transcript.length()}:${transcript.lastModified()}"
                        val completed = database.processingDao().find(id)?.let { it.state == "READY" && it.sourceFingerprint == com.example.readalong.data.processing.TranscriptionPlan.fingerprint(database, id) } == true
                        if (completed && books.metadata("folder_transcript:$id") == signature) break
                        try {
                            onTranscriptFound(id, transcript.uri)
                            books.putMetadata(LibraryMetadataEntity("folder_transcript:$id", signature))
                            books.removeMetadata("folder_transcript_error:$id")
                            break
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            books.putMetadata(LibraryMetadataEntity("folder_transcript_error:$id", failure.message ?: "The folder transcript could not be imported."))
                        }
                    }
                    if (found.transcripts.isNotEmpty() && books.metadata("folder_transcript_error:$id") != null) transcriptFailures++
                    // Listening progress from ReadAlong for Windows (or another phone) beside the book; never fails the scan.
                    group.home?.let { home ->
                        val name = (if (group.key.startsWith("file:")) group.name else home.name ?: group.name).orEmpty().ifBlank { "Audiobook" } + FolderProgress.EXTENSION
                        try { onProgressFound(id, FolderProgressLocation(home.uri.toString(), name, found.progress.firstOrNull { it.name.equals(name, true) }?.uri?.toString())) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { }
                    }
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { failures++; walk.problem(group.name, failure.message ?: "couldn't be read") }
            }
            // Missing or unreadable groups retain their metadata, progress, and future reading data.
            media.folderBooks(uri.toString()).filter { it !in ids }.forEach { media.mediaState(it, "MISSING") }
            // A one-book folder whose book was deleted (saved before deleting a book also forgot its folder): forget it now.
            if (!explicit && saved.mode == FolderMode.ONE_BOOK.name && ids.isEmpty() && failures == 0 && deleted > 0 && media.folderBooks(uri.toString()).isEmpty()) {
                media.removeFolder(uri.toString())
                return@withContext ImportResult(emptyList(), "This audiobook was deleted from ReadAlong earlier.")
            }
            val message = (if (ids.isEmpty() && failures == 0 && deleted > 0) "No new audiobooks: the $deleted here were deleted from ReadAlong earlier. To bring them back, add this folder again with Add audiobooks."
                else if (ids.isEmpty() && failures == 0) walk.nothingFound(root.name ?: "this folder") else "${ids.size} audiobook(s) scanned.") + walk.problemNote() +
                (if (transcriptFailures > 0) " $transcriptFailures companion transcript(s) couldn't be imported. Check that the audio files match." else "")
            // An empty result stays on the folders screen too: the status line only shows for a few seconds.
            media.putFolder(saved.copy(lastScanAt = System.currentTimeMillis(), error = if (walk.problems.isNotEmpty() || ids.isEmpty()) message else null))
            ImportResult(ids.distinct(), message, added.distinct())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            media.folderBooks(uri.toString()).forEach { media.mediaState(it, "MISSING") }
            media.putFolder(saved.copy(error = failure.message ?: "Couldn't scan folder. Please try again."))
            throw failure
        }
    }

    /** Explicit confirmation is owned by FoldersScreen. Never deletes original source documents. */
    suspend fun removeFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        folderLock.lock()
        try {
            dropFolderBooks(folder.uri)
            media.removeFolder(folder.uri)
            try { resolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Access may already be revoked. */ }
        } finally { folderLock.unlock() }
    }

    /** Takes a folder's books out of the library without marking them deleted. Hold [folderLock]. */
    private suspend fun dropFolderBooks(folderUri: String) {
        val app = (context.applicationContext as? com.example.readalong.ReadAlongApplication)?.takeIf { it.database === database }
        val actions = com.example.readalong.data.book.BookActionsRepository(context, database)
        for (id in media.folderBooks(folderUri)) {
            app?.processing?.cancel(id)
            app?.playback?.releaseBook(id)
            app?.importedEpub?.remove(id)
            actions.delete(id, excludeFromScans = false)
            context.getSystemService(android.app.NotificationManager::class.java).cancel(com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id))
        }
    }

    private data class Source(val uri: Uri, val name: String, val orderingName: String = name)
    private data class Metadata(val source: Source, val title: String?, val album: String?, val author: String?, val duration: Long?, val art: ByteArray?, val chapters: List<AudioChapter> = emptyList())

    private fun metadata(source: Source): Metadata {
        resolver.openFileDescriptor(source.uri, "r")?.use { } ?: error("Audio file can't be opened.")
        val retriever = MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(context, source.uri)
            fun field(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            Metadata(source, field(MediaMetadataRetriever.METADATA_KEY_TITLE), field(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                field(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: field(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                field(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                retriever.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 })
        } catch (_: Exception) { Metadata(source, null, null, null, null, null) }
        finally { retriever.release() }
        if (source.name.substringAfterLast('.').lowercase() !in setOf("m4b", "m4a", "mp4", "mp3")) return metadata
        return try {
            resolver.openFileDescriptor(source.uri, "r")?.let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val isMp3 = source.name.endsWith(".mp3", true)
                    val duration = metadata.duration ?: if (isMp3) null else Mp4Chapters.duration(stream.channel)
                    val chapters = if (isMp3) Id3Chapters.read(stream.channel, duration) else Mp4Chapters.read(stream.channel, duration)
                    metadata.copy(duration = duration, chapters = chapters)
                }
            } ?: metadata
        } catch (_: Exception) { metadata } // Optional metadata never rejects readable source audio.
    }

    private suspend fun importGroup(sources: List<Source>, key: String, folderUri: String?, folderName: String?, explicit: Boolean = false, folderCover: Uri? = null): Pair<String, Boolean>? {
        importLock.lock()
        try {
            val excluded = database.withTransaction {
                if (explicit) LibraryExclusions.restore(books, key, sources.map { it.uri })
                LibraryExclusions.excluded(books, key, sources.map { it.uri })
            }
            if (excluded) return null
            return importGroupLocked(sources, key, folderUri, folderName, folderCover)
        } finally { importLock.unlock() }
    }

    private suspend fun importGroupLocked(sources: List<Source>, key: String, folderUri: String?, folderName: String?, folderCover: Uri? = null): Pair<String, Boolean>? {
        val all = media.allAudio()
        val bySource = all.associateBy { sourceIdentity(Uri.parse(it.uri)) }
        val known = sources.mapNotNull { bySource[sourceIdentity(it.uri)] }.distinctBy { it.id }
        // A persisted document ID is stable across direct-file and tree selections. Reuse
        // the original book/file IDs so saved chapters, transcript clocks and progress survive.
        val owners = known.map { it.bookId }.distinct()
        require(owners.size <= 1) { "These files already belong to different books. Open them from your library." }
        val id = owners.singleOrNull() ?: stableId(key)
        val previousFiles = media.audioFiles(id)
        if (known.size == sources.size && known.all { it.durationMs != null }) {
            return database.withTransaction {
                if (LibraryExclusions.excluded(books, key, sources.map { it.uri }) || books.findBook(id) == null) return@withTransaction null
                if (books.findBook(id)?.mediaState != "AVAILABLE") media.mediaState(id, "AVAILABLE")
                books.putMetadata(LibraryMetadataEntity("import_group:$id", key))
                id to false
            }
        }
        val selected = sources.associateBy { sourceIdentity(it.uri) }
        val combined = previousFiles.filter { sourceIdentity(Uri.parse(it.uri)) !in selected }
            .map { Source(Uri.parse(it.uri), it.name) } + sources
        val ordered = combined.distinctBy { sourceIdentity(it.uri) }.sortedWith { a, b -> audioNameComparator.compare(a.orderingName, b.orderingName).takeIf { it != 0 } ?: a.uri.toString().compareTo(b.uri.toString()) }
        val metadata = ordered.mapNotNull {
            coroutineContext.ensureActive()
            try { metadata(it) } catch (_: Exception) { null }
        }
        check(metadata.isNotEmpty()) { "None of the selected audio files could be read." }
        val old = books.findBook(id)
        val customCover = books.metadata("override:$id:cover")
        // Embedded art first, then a cover image in the book's folder (cover.jpg, folder.jpg, ...).
        val art = metadata.firstNotNullOfOrNull { it.art }
            ?: folderCover?.let { image -> runCatching { resolver.openInputStream(image)?.use { it.readBytes() } }.getOrNull()?.takeIf { it.size in 1..20_000_000 } }
        val cover = customCover ?: art?.let { bytes ->
            try {
                val directory = File(context.filesDir, "covers").apply { mkdirs() }
                val file = File(directory, "$id.jpg")
                val temporary = File(directory, "$id.tmp")
                temporary.writeBytes(bytes)
                check(temporary.renameTo(file))
                FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
            } catch (_: Exception) { null }
        } ?: old?.coverUri
        val first = metadata.first()
        val title = first.album ?: if (metadata.size == 1) first.title ?: first.source.name.substringBeforeLast('.')
            else folderName ?: first.source.name.substringBeforeLast('.')
        val duration = if (metadata.all { it.duration != null }) metadata.sumOf { it.duration!! } else null
        val committed = database.withTransaction {
            // Delete may happen while slow media metadata is being read. Check again atomically.
            if (LibraryExclusions.excluded(books, key, sources.map { it.uri })) return@withTransaction false
            books.putMetadata(LibraryMetadataEntity("import_group:$id", key))
            books.upsertBooks(listOf(BookEntity(id, books.metadata("override:$id:title") ?: title,
                books.metadata("override:$id:author")?.ifEmpty { null } ?: first.author.takeIf { books.metadata("override:$id:author") == null }, cover, null, duration,
                old?.addedAt ?: System.currentTimeMillis(), false, old?.coverStyle ?: 0, "AVAILABLE")))
            books.insertProgress(listOf(BookProgressEntity(id)))
            val files = metadata.mapIndexed { index, item ->
                val retained = bySource[sourceIdentity(item.source.uri)]
                AudioFileEntity(retained?.id ?: stableId(item.source.uri.toString()), id,
                retained?.uri ?: item.source.uri.toString(), item.title ?: item.source.name, index, item.duration, folderUri) }
            media.putAudio(files)
            media.putChapters(metadata.flatMapIndexed { fileIndex, item ->
                item.chapters.mapIndexed { chapterIndex, chapter -> AudioChapterEntity(
                    "${files[fileIndex].id}:${chapter.startMs}", files[fileIndex].id, chapter.title, chapterIndex,
                    chapter.startMs, item.chapters.getOrNull(chapterIndex + 1)?.startMs ?: item.duration) }
            })
            true
        }
        if (!committed) return null
        // Optional readable-version preparation cannot turn a successful audio import into a failure.
        try { onImported(id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* The player offers a retry without changing the imported book. */ }
        return id to (old == null)
    }

    private data class Collected(val audio: List<Source>, val epubs: List<DocumentFile>, val transcripts: List<DocumentFile> = emptyList(), val skipped: Map<String, Int> = emptyMap(),
        val progress: List<DocumentFile> = emptyList(), val images: List<DocumentFile> = emptyList())

    /** The image that is most likely the book's cover: one named cover/folder/front, else the only or first image. */
    private fun coverImage(images: List<DocumentFile>): DocumentFile? =
        images.firstOrNull { COVER_NAME.containsMatchIn(it.name.orEmpty()) } ?: images.minByOrNull { it.name.orEmpty().lowercase() }
    /** One audiobook found in a library folder: [key] keeps its identity stable across rescans; [home] is where its progress file goes. */
    private data class Group(val key: String, val name: String?, val found: Collected, val home: DocumentFile? = null)

    /**
     * Library folders: each subfolder with audio is a book (its own subfolders, e.g. CD1/CD2, belong
     * to it). A subfolder without audio of its own is a shelf (e.g. an author) and is searched for
     * books. Loose files in the root are one book, except several single-file audiobooks (.m4b),
     * which are one book each. This covers the layouts audiobook download tools write: flat files,
     * Author/Book and Author/Series/Book.
     */
    private fun libraryGroups(root: DocumentFile, walk: Walk): List<Group> {
        val groups = mutableListOf<Group>()
        val children = walk.list(root)
        val loose = children.filter { it.isFile && supported(it.name.orEmpty()) }
        val epubs = children.filter { it.isFile && it.name.orEmpty().endsWith(".epub", true) }
        val transcripts = children.filter { it.isFile && it.name.orEmpty().endsWith(".readalong", true) }
        val progress = children.filter { it.isFile && it.name.orEmpty().endsWith(FolderProgress.EXTENSION, true) }
        val images = children.filter { it.isFile && image(it.name.orEmpty()) }
        groups += singleFileBooks(loose, epubs, transcripts, progress, root, images) ?: listOf(Group("folder:${root.uri}", root.name, Collected(loose.map { Source(it.uri, it.name!!) }, epubs, transcripts, progress = progress, images = images), root))
        val shelves = ArrayDeque(children.filter { it.isDirectory }.map { it to 1 })
        while (shelves.isNotEmpty()) {
            val (directory, depth) = shelves.removeFirst()
            // A folder that can't be listed (or is too large) is skipped and named; the rest still scan.
            try {
                val inside = walk.list(directory)
                val audio = inside.filter { it.isFile && supported(it.name.orEmpty()) }
                val subfolders = inside.filter { it.isDirectory }
                val discs = subfolders.isNotEmpty() && subfolders.all { DISC.matches(it.name.orEmpty()) }
                when {
                    audio.isNotEmpty() || discs || depth >= 4 || subfolders.isEmpty() -> {
                        val shelfBooks = if (subfolders.isEmpty()) singleFileBooks(audio, inside.filter { it.isFile && it.name.orEmpty().endsWith(".epub", true) },
                            inside.filter { it.isFile && it.name.orEmpty().endsWith(".readalong", true) },
                            inside.filter { it.isFile && it.name.orEmpty().endsWith(FolderProgress.EXTENSION, true) }, directory, inside.filter { it.isFile && image(it.name.orEmpty()) }) else null
                        groups += shelfBooks ?: listOf(Group("folder:${directory.uri}", directory.name, collect(directory, walk), directory))
                    }
                    else -> subfolders.forEach { shelves.add(it to depth + 1) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { walk.problem(runCatching { directory.name }.getOrNull(), failure.message ?: "couldn't be read") }
        }
        return groups
    }

    /** Several .m4b files side by side are separate books; returns null for any other mix. */
    private fun singleFileBooks(audio: List<DocumentFile>, epubs: List<DocumentFile>, transcripts: List<DocumentFile>, progress: List<DocumentFile>, home: DocumentFile,
        images: List<DocumentFile> = emptyList()): List<Group>? {
        if (audio.size < 2 || !audio.all { it.name.orEmpty().endsWith(".m4b", true) }) return null
        // "Part 1.m4b", "Part 2.m4b" are one book split into parts.
        if (audio.any { PART.containsMatchIn(it.name.orEmpty()) }) return null
        return audio.map { file ->
            val name = file.name.orEmpty().substringBeforeLast('.')
            val same = { other: DocumentFile -> other.name.orEmpty().substringBeforeLast('.').equals(name, true) }
            Group("file:${file.uri}", name, Collected(listOf(Source(file.uri, file.name!!)), epubs.filter(same), transcripts.filter(same), progress = progress.filter { it.name.orEmpty().equals(name + FolderProgress.EXTENSION, true) }, images = images.filter(same)), home)
        }
    }
    private fun collect(root: DocumentFile, walk: Walk): Collected {
        val result = mutableListOf<Source>()
        val epubs = mutableListOf<DocumentFile>()
        val transcripts = mutableListOf<DocumentFile>()
        val progress = mutableListOf<DocumentFile>()
        val images = mutableListOf<DocumentFile>()
        val skipped = mutableMapOf<String, Int>()
        val pending = ArrayDeque<Triple<DocumentFile, Int, String>>()
        pending.add(Triple(root, 0, ""))
        while (pending.isNotEmpty()) {
            val (directory, depth, relative) = pending.removeFirst()
            check(depth <= 32 && result.size < 20_000) { "Folder is too large or deeply nested. Add a smaller audiobook folder." }
            walk.list(directory).forEach { child ->
                if (child.isDirectory) pending.add(Triple(child, depth + 1, relative + child.name.orEmpty() + "/"))
                else if (child.isFile) {
                    if (supported(child.name.orEmpty())) result += Source(child.uri, child.name!!, relative + child.name!!)
                    else if (child.name.orEmpty().endsWith(".epub", true)) epubs += child
                    else if (child.name.orEmpty().endsWith(".readalong", true)) transcripts += child
                    else if (child.name.orEmpty().endsWith(FolderProgress.EXTENSION, true)) { if (depth == 0) progress += child }
                    else if (image(child.name.orEmpty())) { if (depth <= 1) images += child }
                    else Walk.skippedType(child.name.orEmpty())?.let { skipped[it] = (skipped[it] ?: 0) + 1 }
                }
            }
        }
        return Collected(result, epubs, transcripts, skipped, progress, images)
    }

    internal fun sourceIdentity(uri: Uri): String = LibraryExclusions.source(uri)

    private fun displayName(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "Audiobook"

    private companion object {
        val DISC = Regex("(?i)^(cd|disc|disk|part)\\s*[-_ ]?\\d+.*")
        val PART = Regex("(?i)\\b(part|pt|disc|disk|cd)\\s*[-_.]?\\s*\\d+")
        val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mp4")
        val IMAGES = setOf("jpg", "jpeg", "png", "webp")
        val COVER_NAME = Regex("(?i)(cover|folder|front)")
        /** Files a scan uses or expects beside audio; they never count as skipped. */
        val COMPANION = setOf("epub", "readalong", "readalong-progress", "jpg", "jpeg", "png", "webp", "gif", "bmp", "txt", "nfo", "cue", "pdf", "json", "xml", "m3u", "m3u8", "nomedia", "ini", "db", "log", "srt", "lrc", "html", "url", "md")
    }
    private fun supported(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO
    private fun image(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGES

    /**
     * What a folder scan saw, so the result can say what wasn't added and why: no files reached the
     * app at all, files of types ReadAlong doesn't play (e.g. Audible's still-encrypted .aax), or
     * books that couldn't be read. Each directory is counted once, although a book folder is
     * listed again when it is collected.
     */
    private class Walk {
        private val seen = mutableSetOf<String>()
        var folders = 0
            private set
        var files = 0
            private set
        val skipped = mutableMapOf<String, Int>()
        val problems = mutableListOf<String>()
        fun list(directory: DocumentFile): Array<DocumentFile> {
            val children = directory.listFiles()
            if (seen.add(directory.uri.toString())) {
                folders++
                children.forEach { child ->
                    if (child.isDirectory) return@forEach
                    files++
                    skippedType(child.name.orEmpty())?.let { skipped[it] = (skipped[it] ?: 0) + 1 }
                }
            }
            return children
        }
        fun problem(name: String?, reason: String) { problems += "${name ?: "A folder"} ($reason)" }
        fun problemNote() = if (problems.isEmpty()) "" else
            " Not added: " + problems.take(5).joinToString("; ") + (if (problems.size > 5) "; and ${problems.size - 5} more" else "") + "."
        fun nothingFound(folder: String) = when {
            files == 0 -> "No files found in \"$folder\" ($folders folder(s) checked). Android may not be sharing this folder's contents; try choosing it again."
            skipped.isEmpty() -> "No audio files found in \"$folder\" ($folders folder(s), $files file(s) checked)."
            else -> "No audiobooks found in \"$folder\". Checked $folders folder(s) and $files file(s); none were audio ReadAlong can play. Found: ${counts(skipped)}." +
                (if (skipped.keys.any { it == ".aax" || it == ".aaxc" || it == ".aa" }) " Audible files must be converted first; see Import from Audible." else "")
        }
        companion object {
            /** The label a scan reports for a file it doesn't use, or null for audio and expected companions. */
            fun skippedType(name: String): String? {
                val type = name.substringAfterLast('.', "").lowercase()
                if (type in AUDIO || type in COMPANION) return null
                return if (type.isEmpty() || type.length > 8) "(no extension)" else ".$type"
            }
            fun counts(skipped: Map<String, Int>) = skipped.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.value} ${it.key}" }
        }
    }
    private fun stableId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

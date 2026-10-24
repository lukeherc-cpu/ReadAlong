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
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class FolderMode { ONE_BOOK, LIBRARY }
data class ImportResult(val bookIds: List<String>, val message: String, val newBookIds: List<String> = emptyList())

class AudioImporter(private val context: Context, private val database: ReadAlongDatabase, private val onEpubFound: suspend (String, Uri) -> Unit = { _, _ -> }, private val onImported: suspend (String) -> Unit = {}, private val onTranscriptFound: suspend (String, Uri) -> Unit = { _, _ -> }) {
    private val resolver = context.contentResolver
    private val media = database.mediaDao()
    private val books = database.bookDao()
    private val folderLock = kotlinx.coroutines.sync.Mutex()
    private val importLock = kotlinx.coroutines.sync.Mutex()
    val folders = media.observeFolders()

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
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { error("This provider cannot keep access to the selection. Choose a file or folder from device storage.") }
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

    suspend fun addFolder(uri: Uri, mode: FolderMode, persistAccess: Boolean = true): ImportResult = withContext(Dispatchers.IO) {
        if (persistAccess) persist(uri)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Couldn't open this folder." }
        media.putFolder(FolderEntity(uri.toString(), root.name ?: "Audiobooks", mode.name, null, "Scan incomplete. Rescan to continue."))
        folderLock.withLock { scanLocked(uri, explicit = mode == FolderMode.ONE_BOOK) }
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

    private suspend fun scanLocked(uri: Uri, explicit: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        val saved = requireNotNull(media.folder(uri.toString())) { "Folder is no longer saved." }
        try {
            media.putFolder(saved.copy(error = "Scan incomplete. Rescan to continue."))
            val root = requireNotNull(DocumentFile.fromTreeUri(context, uri))
            check(root.exists() && root.canRead()) { "Folder access was lost. Add this folder again to restore access." }
            val groups = if (saved.mode == FolderMode.ONE_BOOK.name) listOf(Group("folder:${root.uri}", root.name, collect(root))) else libraryGroups(root)
            val ids = mutableListOf<String>()
            val added = mutableListOf<String>()
            var failures = 0
            var transcriptFailures = 0
            groups.filter { it.found.audio.isNotEmpty() }.forEach { group ->
                val found = group.found
                coroutineContext.ensureActive()
                try {
                    val (id, isNew) = importGroup(found.audio, group.key, uri.toString(), group.name, explicit) ?: return@forEach
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
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { failures++ }
            }
            // Missing or unreadable groups retain their metadata, progress, and future reading data.
            media.folderBooks(uri.toString()).filter { it !in ids }.forEach { media.mediaState(it, "MISSING") }
            val message = "${ids.size} audiobook(s) scanned." + (if (failures > 0) " $failures group(s) couldn't be read; rescan to retry." else "") +
                (if (transcriptFailures > 0) " $transcriptFailures companion transcript(s) couldn't be imported. Check that the audio files match." else "")
            media.putFolder(saved.copy(lastScanAt = System.currentTimeMillis(), error = if (failures > 0) message else null))
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
            val app = (context.applicationContext as? com.example.readalong.ReadAlongApplication)?.takeIf { it.database === database }
            val actions = com.example.readalong.data.book.BookActionsRepository(context, database)
            for (id in media.folderBooks(folder.uri)) {
                app?.processing?.cancel(id)
                app?.playback?.releaseBook(id)
                app?.importedEpub?.remove(id)
                actions.delete(id, excludeFromScans = false)
                context.getSystemService(android.app.NotificationManager::class.java).cancel(com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id))
            }
            media.removeFolder(folder.uri)
            try { resolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Access may already be revoked. */ }
        } finally { folderLock.unlock() }
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

    private suspend fun importGroup(sources: List<Source>, key: String, folderUri: String?, folderName: String?, explicit: Boolean = false): Pair<String, Boolean>? {
        importLock.lock()
        try {
            val excluded = database.withTransaction {
                if (explicit) LibraryExclusions.restore(books, key, sources.map { it.uri })
                LibraryExclusions.excluded(books, key, sources.map { it.uri })
            }
            if (excluded) return null
            return importGroupLocked(sources, key, folderUri, folderName)
        } finally { importLock.unlock() }
    }

    private suspend fun importGroupLocked(sources: List<Source>, key: String, folderUri: String?, folderName: String?): Pair<String, Boolean>? {
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
        val cover = customCover ?: metadata.firstNotNullOfOrNull { it.art }?.let { bytes ->
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

    private data class Collected(val audio: List<Source>, val epubs: List<DocumentFile>, val transcripts: List<DocumentFile> = emptyList())
    /** One audiobook found in a library folder: [key] keeps its identity stable across rescans. */
    private data class Group(val key: String, val name: String?, val found: Collected)

    /**
     * Library folders: each subfolder with audio is a book (its own subfolders, e.g. CD1/CD2, belong
     * to it). A subfolder without audio of its own is a shelf (e.g. an author) and is searched for
     * books. Loose files in the root are one book, except several single-file audiobooks (.m4b),
     * which are one book each. This covers the layouts audiobook download tools write: flat files,
     * Author/Book and Author/Series/Book.
     */
    private fun libraryGroups(root: DocumentFile): List<Group> {
        val groups = mutableListOf<Group>()
        val children = root.listFiles()
        val loose = children.filter { it.isFile && supported(it.name.orEmpty()) }
        val epubs = children.filter { it.isFile && it.name.orEmpty().endsWith(".epub", true) }
        val transcripts = children.filter { it.isFile && it.name.orEmpty().endsWith(".readalong", true) }
        groups += singleFileBooks(loose, epubs, transcripts) ?: listOf(Group("folder:${root.uri}", root.name, Collected(loose.map { Source(it.uri, it.name!!) }, epubs, transcripts)))
        val shelves = ArrayDeque(children.filter { it.isDirectory }.map { it to 1 })
        while (shelves.isNotEmpty()) {
            val (directory, depth) = shelves.removeFirst()
            val inside = directory.listFiles()
            val audio = inside.filter { it.isFile && supported(it.name.orEmpty()) }
            val subfolders = inside.filter { it.isDirectory }
            val discs = subfolders.isNotEmpty() && subfolders.all { DISC.matches(it.name.orEmpty()) }
            when {
                audio.isNotEmpty() || discs || depth >= 4 || subfolders.isEmpty() -> {
                    val shelfBooks = if (subfolders.isEmpty()) singleFileBooks(audio, inside.filter { it.isFile && it.name.orEmpty().endsWith(".epub", true) },
                        inside.filter { it.isFile && it.name.orEmpty().endsWith(".readalong", true) }) else null
                    groups += shelfBooks ?: listOf(Group("folder:${directory.uri}", directory.name, collect(directory)))
                }
                else -> subfolders.forEach { shelves.add(it to depth + 1) }
            }
        }
        return groups
    }

    /** Several .m4b files side by side are separate books; returns null for any other mix. */
    private fun singleFileBooks(audio: List<DocumentFile>, epubs: List<DocumentFile>, transcripts: List<DocumentFile>): List<Group>? {
        if (audio.size < 2 || !audio.all { it.name.orEmpty().endsWith(".m4b", true) }) return null
        // "Part 1.m4b", "Part 2.m4b" are one book split into parts.
        if (audio.any { PART.containsMatchIn(it.name.orEmpty()) }) return null
        return audio.map { file ->
            val name = file.name.orEmpty().substringBeforeLast('.')
            val same = { other: DocumentFile -> other.name.orEmpty().substringBeforeLast('.').equals(name, true) }
            Group("file:${file.uri}", name, Collected(listOf(Source(file.uri, file.name!!)), epubs.filter(same), transcripts.filter(same)))
        }
    }
    private fun collect(root: DocumentFile): Collected {
        val result = mutableListOf<Source>()
        val epubs = mutableListOf<DocumentFile>()
        val transcripts = mutableListOf<DocumentFile>()
        val pending = ArrayDeque<Triple<DocumentFile, Int, String>>()
        pending.add(Triple(root, 0, ""))
        while (pending.isNotEmpty()) {
            val (directory, depth, relative) = pending.removeFirst()
            check(depth <= 32 && result.size < 20_000) { "Folder is too large or deeply nested. Add a smaller audiobook folder." }
            directory.listFiles().forEach { child ->
                if (child.isDirectory) pending.add(Triple(child, depth + 1, relative + child.name.orEmpty() + "/"))
                else if (child.isFile) {
                    if (supported(child.name.orEmpty())) result += Source(child.uri, child.name!!, relative + child.name!!)
                    else if (child.name.orEmpty().endsWith(".epub", true)) epubs += child
                    else if (child.name.orEmpty().endsWith(".readalong", true)) transcripts += child
                }
            }
        }
        return Collected(result, epubs, transcripts)
    }

    internal fun sourceIdentity(uri: Uri): String = LibraryExclusions.source(uri)

    private fun displayName(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "Audiobook"

    private companion object {
        val DISC = Regex("(?i)^(cd|disc|disk|part)\\s*[-_ ]?\\d+.*")
        val PART = Regex("(?i)\\b(part|pt|disc|disk|cd)\\s*[-_.]?\\s*\\d+")
    }
    private fun supported(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mp4")
    private fun stableId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

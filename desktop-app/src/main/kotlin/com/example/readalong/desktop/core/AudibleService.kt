package com.example.readalong.desktop.core

import com.example.readalong.audible.*
import com.example.readalong.desktop.transcription.TranscriptionService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File

/** One book's place in the Audible import. */
sealed interface AudibleDownload {
    data object Queued : AudibleDownload
    data class Running(val stage: AudibleDownloader.Stage, val fraction: Float) : AudibleDownload
    data object Done : AudibleDownload
    data class Failed(val reason: String) : AudibleDownload
    /** Downloaded before, but no longer in the library: it can be downloaded (or added back) again. */
    data object Removed : AudibleDownload
}

/**
 * Libation's workflow inside ReadAlong: sign in to Audible, list the library, download, unlock and add
 * books. The engine is shared with the phone (app/src/github/.../audible). The sign-in and the library list live
 * in ReadAlong's data folder; finished books move into a library folder and join the library and the queue.
 */
class AudibleService(private val scope: CoroutineScope, private val importer: LibraryImporter, private val transcription: TranscriptionService) {
    private val home = File(AppPaths.data, "audible").apply { mkdirs() }
    private val accountFile = File(home, "account.json")
    private val libraryFile = File(home, "library.json")
    private val books = File(home, "books")

    private val mutableAccount = MutableStateFlow(runCatching { AudibleAccount.fromJson(accountFile.readText()) }.getOrNull())
    val account: StateFlow<AudibleAccount?> = mutableAccount
    private val mutableLibrary = MutableStateFlow(runCatching { AudibleBook.listFromJson(libraryFile.readText()) }.getOrDefault(emptyList()))
    val library: StateFlow<List<AudibleBook>> = mutableLibrary
    /** Each downloaded book's folder, read from its done marker once. */
    private val placed = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val mutableDownloads = MutableStateFlow(books.listFiles().orEmpty().filter { File(it, DONE).exists() }.associate { it.name to AudibleDownload.Done as AudibleDownload })
    /** A finished download is only "Done" while its book is in the library; after the book is removed it is [AudibleDownload.Removed]. */
    val downloads: StateFlow<Map<String, AudibleDownload>> = combine(mutableDownloads, importer.books.map { list -> list.map { it.parts.firstOrNull()?.path } }.distinctUntilChanged()) { states, library ->
        states.mapValues { (asin, state) -> if (state == AudibleDownload.Done && !inLibrary(asin, library)) AudibleDownload.Removed else state }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val mutableSyncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = mutableSyncing
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One book at a time: Audible counts licence requests. */
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job = start()

    /** The sign-in in progress: the Amazon page that was opened and the device it will register. */
    var login: AudibleAuth.Login? = null
        private set

    fun startLogin(locale: AudibleLocale): String = AudibleAuth.startLogin(locale).also { login = it }.url

    /** Finishes signing in with the address Amazon showed at the end. */
    suspend fun finishLogin(redirect: String) {
        val pending = login ?: throw AudibleException("Open Amazon's sign-in page first.")
        val account = withContext(Dispatchers.IO) { AudibleAuth.register(pending, redirect) }
        save(account)
        login = null
        sync()
    }

    fun cancelLogin() { login = null }

    fun sync() {
        val current = account.value ?: return
        if (mutableSyncing.value) return
        mutableSyncing.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val list = AudibleClient(current, ::save).library()
                libraryFile.writeAtomically(AudibleBook.listToJson(list))
                mutableLibrary.value = list
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { messages.tryEmit(failure.message ?: "Couldn't load your Audible library.") }
            finally { mutableSyncing.value = false }
        }
    }

    fun download(asin: String) {
        if (mutableDownloads.value[asin].let { it is AudibleDownload.Queued || it is AudibleDownload.Running }) return
        report(asin, AudibleDownload.Queued)
        queue.trySend(asin)
    }

    /** Queues every book in [asins] that isn't downloaded, queued or downloading yet, in order. */
    fun downloadAll(asins: List<String>) {
        val current = downloads.value
        asins.filter { asin -> current[asin].let { it == null || it is AudibleDownload.Failed || it == AudibleDownload.Removed } }.forEach(::download)
    }

    fun cancelDownloads() {
        worker.cancel()
        while (queue.tryReceive().isSuccess) Unit
        mutableDownloads.update { current -> current.filterValues { it == AudibleDownload.Done || it is AudibleDownload.Failed } }
        worker = start()
    }

    /** Forgets the sign-in and removes ReadAlong from the Amazon account's devices. Imported books stay. */
    suspend fun signOut() {
        val current = account.value ?: return
        cancelDownloads()
        withContext(Dispatchers.IO) {
            runCatching { AudibleAuth.deregister(current) }
            accountFile.delete(); libraryFile.delete()
        }
        mutableAccount.value = null
        mutableLibrary.value = emptyList()
    }

    private fun start() = scope.launch(Dispatchers.IO) {
        runCatching { moveOldDownloads() }.onFailure { System.err.println("ReadAlong: older Audible downloads weren't moved: ${it.message}") }
        for (asin in queue) {
            val book = library.value.firstOrNull { it.asin == asin } ?: continue
            try {
                val folder = File(books, asin)
                // Removed from the library but still on disk: add the files back instead of downloading the book again.
                val kept = placedFolder(asin)?.takeIf { place -> place.walkTopDown().any(Scanner::isAudio) }
                val added = if (kept != null) {
                    importer.add(listOf(kept), remember = false).ifEmpty { booksUnder(kept, importer.books.value).map { it.id } }
                } else {
                    val current = account.value ?: break
                    val files = AudibleDownloader.download(AudibleClient(current, ::save), book, folder) { stage, fraction ->
                        ensureActive()
                        report(asin, AudibleDownload.Running(stage, fraction))
                    }
                    val placed = place(book, files)
                    if (placed != null) {
                        // In a library folder the book is found like any other, so link sync carries it and its ReadAlong to the phone.
                        remember(asin, placed)
                        importer.rescan().filter { id -> importer.book(id)?.let { under(it, placed) } == true }
                    } else {
                        // A multi-part book is a folder of parts: the scanner reads that folder as one book.
                        importer.add(listOf(if (files.size > 1) files.first().parentFile else files.single()), remember = false)
                    }
                }
                added.forEach { transcription.enqueue(it) }
                File(folder, DONE).let { if (!it.exists()) it.createNewFile() }
                report(asin, AudibleDownload.Done)
                messages.tryEmit("Added ${book.title}.")
            } catch (cancelled: CancellationException) {
                report(asin, AudibleDownload.Failed("Download stopped. Download again to continue."))
                throw cancelled
            } catch (throttled: AudibleThrottledException) {
                // More licence requests now would only extend the limit: drop the rest of the queue.
                report(asin, AudibleDownload.Failed(throttled.message.orEmpty()))
                while (true) { val next = queue.tryReceive().getOrNull() ?: break; report(next, AudibleDownload.Failed("Not downloaded: Audible asked ReadAlong to wait.")) }
            } catch (failure: Exception) {
                report(asin, AudibleDownload.Failed(failure.message ?: "The download didn't finish."))
            }
        }
    }

    /** Where [asin]'s files went: the folder written in its done marker, or the download folder for books from before 0.50.5. */
    private fun placedFolder(asin: String): File? = placed.getOrPut(asin) {
        val marker = File(File(books, asin), DONE).takeIf { it.isFile } ?: return null
        runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(::File) ?: File(books, asin)
    }

    /** Writes where [asin]'s book now is into its done marker. */
    private fun remember(asin: String, folder: File) {
        File(File(books, asin).apply { mkdirs() }, DONE).writeText(folder.absolutePath)
        placed[asin] = folder
    }

    private fun under(path: String?, folder: File) = path?.startsWith(folder.absolutePath + File.separator) == true
    private fun under(book: LibraryBook, folder: File) = under(book.parts.firstOrNull()?.path, folder)
    private fun booksUnder(folder: File, library: List<LibraryBook>) = library.filter { under(it, folder) }
    /** [paths]: the first audio file of each book in the library. */
    private fun inLibrary(asin: String, paths: List<String?>): Boolean {
        val folder = placedFolder(asin) ?: return true
        return paths.any { under(it, folder) }
    }

    /**
     * Books downloaded before downloads went into a library folder stayed in ReadAlong's data folder, where link sync
     * can't reach them. Move them into the library folder with the same layout. The book is marked missing first, so
     * the rescan finds it by its files and it keeps its id, position, bookmarks and ReadAlong.
     */
    private suspend fun moveOldDownloads() {
        val old = books.listFiles().orEmpty().filter { File(it, DONE).isFile && placedFolder(it.name) == it }
        for (folder in old) {
            val audio = folder.listFiles().orEmpty().filter(Scanner::isAudio).sortedBy { it.name }
            if (audio.isEmpty()) continue
            val inLibrary = booksUnder(folder, importer.books.value)
            // A book removed from the library stays removed (Download again adds it back). The book being transcribed
            // has its audio open: it moves on a later start.
            if (inLibrary.isEmpty() || inLibrary.any { it.id == transcription.job.value?.bookId }) continue
            val root = targetRoot() ?: return
            var placed: File? = null
            importer.relocate(inLibrary.map { it.id }) { placeInto(root, folder.name, inLibrary.first().title, audio, moveOnly = true).also { placed = it } != null }
            placed?.let { remember(folder.name, it) }
        }
    }

    /**
     * Moves a finished download into the first library folder that can be written, as "<Title>/<Title>.m4b" (or the
     * parts in "<Title>/"), the same layout as the phone. Without one, ReadAlong makes its own "Audible Audiobooks"
     * folder in Documents and adds it to the library. Null only when the files couldn't be moved: the book then stays
     * in ReadAlong's data folder.
     */
    private suspend fun place(book: AudibleBook, files: List<File>): File? = targetRoot()?.let { placeInto(it, book.asin, book.title, files) }

    private suspend fun targetRoot(): File? = importer.folders.map(::File).firstOrNull { it.isDirectory && it.canWrite() } ?: ownFolder()

    /**
     * [moveOnly]: for a book already in the library, which may be playing or being read. Any file that can't be moved
     * puts the ones already moved back, so the book is never left half in each place (or copied twice).
     */
    private fun placeInto(root: File, asin: String, title: String, files: List<File>, moveOnly: Boolean = false): File? {
        val name = AudibleDownloader.safeName(title)
        val target = File(root, name).let { if (it.exists() && it.listFiles().orEmpty().isNotEmpty()) File(root, "$name ($asin)") else it }
        val moved = mutableListOf<Pair<File, File>>()
        return runCatching {
            target.mkdirs()
            for (file in files) {
                val destination = File(target, file.name)
                try { java.nio.file.Files.move(file.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                catch (failure: java.io.IOException) {
                    if (moveOnly) throw failure
                    // Another drive: copy, then remove the download.
                    file.copyTo(destination, overwrite = true); file.delete()
                }
                moved += file to destination
            }
            target
        }.getOrElse { failure ->
            if (moveOnly) {
                moved.forEach { (from, to) -> runCatching { java.nio.file.Files.move(to.toPath(), from.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) } }
                if (target.listFiles().orEmpty().isEmpty()) target.delete()
            }
            System.err.println("ReadAlong: Audible book kept in the data folder: ${failure.message}")
            null
        }
    }

    /** "Audible Audiobooks" in Documents (or the home folder), added to the library so it is rescanned and linked. */
    private suspend fun ownFolder(): File? {
        val home = File(System.getProperty("user.home"))
        val folder = File(File(home, "Documents").takeIf { it.isDirectory } ?: home, OWN_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) return null
        if (!folder.canWrite()) return null
        importer.add(listOf(folder))
        messages.tryEmit("Audible books are saved in ${folder.path}.")
        return folder
    }

    @Synchronized private fun save(account: AudibleAccount) {
        accountFile.writeAtomically(account.toJson())
        // The device key is as good as a password for this Amazon device: keep it to this user.
        accountFile.setReadable(false, false); accountFile.setReadable(true, true)
        mutableAccount.value = account
    }

    private fun report(asin: String, state: AudibleDownload) = mutableDownloads.update { it + (asin to state) }

    private companion object {
        const val DONE = ".imported"
        const val OWN_FOLDER = "Audible Audiobooks"
    }
}

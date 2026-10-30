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
}

/**
 * Libation's workflow inside ReadAlong: sign in to Audible, list the library, download, unlock and add
 * books. The engine is shared with the phone (app/src/github/.../audible). The sign-in, the library list
 * and the downloaded books live in ReadAlong's data folder; finished books join the library and the queue.
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
    private val mutableDownloads = MutableStateFlow(books.listFiles().orEmpty().filter { File(it, DONE).exists() }.associate { it.name to AudibleDownload.Done as AudibleDownload })
    val downloads: StateFlow<Map<String, AudibleDownload>> = mutableDownloads
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
        for (asin in queue) {
            val book = library.value.firstOrNull { it.asin == asin } ?: continue
            val current = account.value ?: break
            try {
                val folder = File(books, asin)
                val files = AudibleDownloader.download(AudibleClient(current, ::save), book, folder) { stage, fraction ->
                    ensureActive()
                    report(asin, AudibleDownload.Running(stage, fraction))
                }
                // A multi-part book is a folder of parts: the scanner reads that folder as one book.
                val added = importer.add(listOf(if (files.size > 1) files.first().parentFile else files.single()), remember = false)
                added.forEach { transcription.enqueue(it) }
                File(folder, DONE).createNewFile()
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

    @Synchronized private fun save(account: AudibleAccount) {
        accountFile.writeAtomically(account.toJson())
        // The device key is as good as a password for this Amazon device: keep it to this user.
        accountFile.setReadable(false, false); accountFile.setReadable(true, true)
        mutableAccount.value = account
    }

    private fun report(asin: String, state: AudibleDownload) = mutableDownloads.update { it + (asin to state) }

    private companion object { const val DONE = ".imported" }
}

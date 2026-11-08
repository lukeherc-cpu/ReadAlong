package com.example.readalong.data.audible

import android.content.Context
import androidx.work.*
import com.example.readalong.audible.AudibleAccount
import com.example.readalong.audible.AudibleBook
import com.example.readalong.audible.AudibleDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** One book's place in the Audible import. */
sealed interface AudibleDownload {
    data object Queued : AudibleDownload
    data class Running(val stage: AudibleDownloader.Stage, val fraction: Float) : AudibleDownload
    data object Done : AudibleDownload
    data class Failed(val reason: String) : AudibleDownload
}

/**
 * The Audible sign-in, the last synced library and downloads in progress, in the app's private
 * storage. Finished books move to a library folder (see [AudibleLibraryFolder]). Downloads run in [AudibleDownloadWorker] so they carry on when ReadAlong is closed.
 */
class AudibleStore(private val context: Context) {
    private val home = File(context.filesDir, "audible").apply { mkdirs() }
    private val accountFile = File(home, "account.json")
    private val libraryFile = File(home, "library.json")
    val books = File(home, "books")

    private val mutableAccount = MutableStateFlow(load())
    val account = mutableAccount.asStateFlow()
    private val mutableLibrary = MutableStateFlow(runCatching { AudibleBook.listFromJson(libraryFile.readText()) }.getOrDefault(emptyList()))
    val library = mutableLibrary.asStateFlow()
    private val mutableDownloads = MutableStateFlow(books.listFiles().orEmpty().filter { it.isDirectory && downloaded(it.name) }.associate { it.name to AudibleDownload.Done as AudibleDownload })
    val downloads = mutableDownloads.asStateFlow()

    private fun load() = runCatching { AudibleAccount.fromJson(accountFile.readText()) }.getOrNull()

    @Synchronized fun save(account: AudibleAccount) {
        write(accountFile, account.toJson())
        mutableAccount.value = account
    }

    fun saveLibrary(list: List<AudibleBook>) {
        write(libraryFile, AudibleBook.listToJson(list))
        mutableLibrary.value = list
    }

    /** Forgets the sign-in and the library list. Books already imported stay in ReadAlong. */
    @Synchronized fun signOut() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        accountFile.delete(); libraryFile.delete()
        mutableAccount.value = null
        mutableLibrary.value = emptyList()
        mutableDownloads.update { current -> current.filterValues { it == AudibleDownload.Done } }
    }

    fun book(asin: String) = library.value.firstOrNull { it.asin == asin }
    fun folder(asin: String) = File(books, asin)
    private fun downloaded(asin: String) = File(folder(asin), DONE).exists()
    /** Marks a book whose every part is downloaded and imported. */
    fun finished(asin: String, bookIds: List<String>) {
        File(folder(asin), DONE).apply { parentFile?.mkdirs() }.writeText(bookIds.joinToString("\n"))
        report(asin, AudibleDownload.Done)
    }

    /** Takes a book back to "Download": its marker goes, any file left in its download folder stays for Download to reuse. */
    fun forget(asin: String) {
        File(folder(asin), DONE).delete()
        mutableDownloads.update { it - asin }
    }

    /** Books marked done by a ReadAlong before 0.49.0, whose files are still in the download folder (see [AudibleLibraryFolder.adoptOldDownloads]). */
    fun oldDownloads(): List<String> = books.listFiles().orEmpty().filter { dir ->
        downloaded(dir.name) && File(dir, DONE).readText().isBlank() && dir.listFiles().orEmpty().any { it.isFile && it.extension.lowercase() in setOf("m4b", "mp3") }
    }.map { it.name }

    /**
     * "In your library" only for books that are: a book deleted from the library goes back to Download.
     * Markers from before book ids were saved in them are kept as they are.
     */
    suspend fun reconcile(exists: suspend (String) -> Boolean) {
        for ((asin, state) in downloads.value) {
            if (state != AudibleDownload.Done) continue
            val ids = runCatching { File(folder(asin), DONE).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
            if (ids.isNotEmpty() && ids.none { exists(it) }) forget(asin)
        }
    }

    fun report(asin: String, state: AudibleDownload) = mutableDownloads.update { it + (asin to state) }

    fun download(asin: String) {
        report(asin, AudibleDownload.Queued)
        val request = OneTimeWorkRequestBuilder<AudibleDownloadWorker>()
            .setInputData(workDataOf(AudibleDownloadWorker.ASIN to asin))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG).build()
        // One book at a time: Audible counts licence requests, and a queue keeps the account well clear of its limits.
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Queues every book in [asins] that isn't downloaded, queued or downloading yet, in order. */
    fun downloadAll(asins: List<String>) {
        val current = downloads.value
        asins.filter { asin -> current[asin].let { it == null || it is AudibleDownload.Failed } }.forEach(::download)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(QUEUE)
        mutableDownloads.update { current -> current.filterValues { it == AudibleDownload.Done || it is AudibleDownload.Failed } }
    }

    private fun write(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        check(temporary.renameTo(file)) { "Couldn't save ${file.name}." }
    }

    companion object {
        const val TAG = "audible-download"
        private const val DONE = ".imported"
        private const val QUEUE = "audible-downloads"
        @Volatile private var instance: AudibleStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: AudibleStore(context.applicationContext).also { instance = it } }
    }
}

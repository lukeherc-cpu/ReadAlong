package com.example.readalong.data.audible

import android.content.Context
import androidx.core.content.FileProvider
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
 * The Audible sign-in, the last synced library and the downloaded books, all in the app's private
 * storage. Downloads run in [AudibleDownloadWorker] so they carry on when ReadAlong is closed.
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
    fun finished(asin: String) { File(folder(asin), DONE).createNewFile(); report(asin, AudibleDownload.Done) }

    /** Content URIs for a finished book's files, in order, for the normal importer. */
    fun uris(files: List<File>) = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }

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

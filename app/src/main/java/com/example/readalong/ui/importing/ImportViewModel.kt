package com.example.readalong.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.local.FolderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ImportViewModel(private val importer: AudioImporter, private val epubs: com.example.readalong.data.epub.ImportedEpubRepository? = null, private val processing: com.example.readalong.data.processing.ProcessingRepository? = null) : ViewModel() {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    val folders = importer.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutablePriority = MutableStateFlow(emptyList<ImportPriorityBook>())
    val priority = mutablePriority.asStateFlow()
    internal suspend fun offerPriority(ids: List<String>) {
        mutablePriority.value = mutablePriority.value + ids.filter { processing?.observe(it)?.first()?.state != "READY" }
            .map { ImportPriorityBook(it, importer.title(it), importer.duration(it), importer.hasFolderEpub(it)) }
    }
    fun skipPriority() { mutablePriority.value = mutablePriority.value.drop(1) }
    /** The new book's "Make a ReadAlong" choice: from the audio, or matched to an EPUB (picked now or found in its folder). */
    fun startPriority(align: Boolean, uri: Uri?) = action("Starting your ReadAlong…") {
        val book = mutablePriority.value.firstOrNull() ?: return@action ""
        if (uri != null) requireNotNull(epubs).import(book.id, uri)
        else if (align) importer.matchedEpub(book.id)
        epubs?.preference(book.id, if (align) "EPUB" else "GENERATED")
        requireNotNull(processing).transcribeNext(book.id)
        skipPriority()
        "Making your ReadAlong. You can pause it from the player or the queue."
    }
    fun startAdded(align: Boolean, uri: Uri?) = action("Starting your ReadAlong…") {
        val id = mutableAddedBook.value ?: return@action ""
        if (uri != null) { requireNotNull(epubs).import(id, uri); mutableEpubUploaded.value = true }
        epubs?.preference(id, if (align) "EPUB" else "GENERATED")
        requireNotNull(processing).transcribeNext(id)
        finishAdding()
        "Making your ReadAlong. You can pause it from the player or the queue."
    }
    private val mutableAddedBook = MutableStateFlow<String?>(null)
    val addedBook = mutableAddedBook.asStateFlow()
    private val mutableEpubUploaded = MutableStateFlow(false)
    val epubUploaded = mutableEpubUploaded.asStateFlow()
    fun finishAdding() { mutableAddedBook.value = null; mutableEpubUploaded.value = false }
    fun files(uris: List<Uri>) = action {
        val result = importer.importFiles(uris)
        offerPriority(result.newBookIds)
        mutableEpubUploaded.value = false
        result.message
    }
    fun folder(uri: Uri, mode: FolderMode, onAdded: () -> Unit = {}) = action {
        val result = importer.addFolder(uri, mode)
        if (mode == FolderMode.ONE_BOOK) offerPriority(result.newBookIds)

        onAdded()
        result.message
    }
    fun addEpub(uri: Uri) = action("Uploading EPUB…") {
        val book = requireNotNull(mutableAddedBook.value)
        requireNotNull(epubs).import(book, uri)
        mutableEpubUploaded.value = true
        "EPUB added. Your ReadAlong will use its text."
    }
    fun rescan(folder: FolderEntity) = action { val result = importer.scan(Uri.parse(folder.uri)); result.message }
    fun remove(folder: FolderEntity) = action { importer.removeFolder(folder); "Folder removed from your library." }
    private val mutableScanningFolder = MutableStateFlow<String?>(null)
    val scanningFolder = mutableScanningFolder.asStateFlow()
    private var autoScan: kotlinx.coroutines.Job? = null
    fun refreshLibrary() {
        if (autoScan?.isActive == true) return
        autoScan = viewModelScope.launch {
            while (busy.value) kotlinx.coroutines.delay(150)
            try { importer.rescanSavedFolders { mutableScanningFolder.value = it?.name } }
            finally { mutableScanningFolder.value = null }
        }
    }
    private var messageExpiry: kotlinx.coroutines.Job? = null
    private fun showMessage(value: String?) {
        messageExpiry?.cancel()
        mutableMessage.value = value
        if (!value.isNullOrEmpty()) messageExpiry = viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            mutableMessage.value = null
        }
    }
    fun clearMessage() { showMessage(null) }
    private fun action(pendingMessage: String = "Reading audio metadata…", work: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            showMessage(pendingMessage)
            try { showMessage(work()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { showMessage(failure.message ?: "Couldn't add that audio. Please try again.") }
            finally { mutableBusy.value = false }
        }
    }
    companion object {
        fun factory(importer: AudioImporter, epubs: com.example.readalong.data.epub.ImportedEpubRepository? = null, processing: com.example.readalong.data.processing.ProcessingRepository? = null): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(importer, epubs, processing) as T
        }
    }
}

data class ImportPriorityBook(val id: String, val title: String, val durationMs: Long? = null, val folderEpub: Boolean = false)

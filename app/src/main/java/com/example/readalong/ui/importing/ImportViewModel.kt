package com.example.readalong.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.FolderPreview
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
    val folderBooks = importer.folderBooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val mutablePriority = MutableStateFlow(emptyList<ImportPriorityBook>())
    val priority = mutablePriority.asStateFlow()
    internal suspend fun offerPriority(ids: List<String>) {
        mutablePriority.value = mutablePriority.value + ids.filter { processing?.observe(it)?.first()?.state != "READY" }
            .map { ImportPriorityBook(it, importer.title(it), importer.duration(it), importer.hasFolderEpub(it)) }
    }
    /**
     * One new book was added: its player opens and the cover turns over to offer a ReadAlong
     * (see [com.example.readalong.ui.player.NewBookOffer]) instead of the "Make a ReadAlong" pop-up.
     */
    private val mutableOpenBook = MutableStateFlow<String?>(null)
    val openBook = mutableOpenBook.asStateFlow()
    fun openedBook() { mutableOpenBook.value = null }
    private suspend fun offerAdded(ids: List<String>) {
        if (ids.size == 1) { com.example.readalong.ui.player.NewBookOffer.bookId = ids[0]; mutableOpenBook.value = ids[0] }
        else offerPriority(ids)
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
        offerAdded(result.newBookIds)
        mutableEpubUploaded.value = false
        result.message
    }
    private val mutableReadingFolder = MutableStateFlow(false)
    /** True while a newly chosen library folder is read for the first time (the library shows placeholders). */
    val readingFolder = mutableReadingFolder.asStateFlow()
    fun folder(uri: Uri, mode: FolderMode, onAdded: () -> Unit = {}, restoreDeleted: Boolean = mode == FolderMode.ONE_BOOK) = action {
        if (mode == FolderMode.LIBRARY) mutableReadingFolder.value = true
        val result = try { importer.addFolder(uri, mode, restoreDeleted = restoreDeleted) } finally { mutableReadingFolder.value = false }
        if (mode == FolderMode.ONE_BOOK) offerAdded(result.newBookIds)

        onAdded()
        result.message
    }
    /** Add audiobooks: a chosen folder is looked at first; [AddPreviewDialog] shows what was found. [origin] names the screen that asked. */
    data class Preview(val origin: String, val folder: FolderPreview)
    private val mutablePreview = MutableStateFlow<Preview?>(null)
    val preview = mutablePreview.asStateFlow()
    fun previewFolder(uri: Uri, origin: String) = action("Looking inside the folder…") {
        mutablePreview.value = Preview(origin, importer.preview(uri))
        null
    }
    fun dismissPreview() { mutablePreview.value = null }
    /** No audiobooks in the chosen folder: keep it anyway as a library folder, for books added or synced into it later. */
    fun useEmptyFolder() {
        val chosen = mutablePreview.value ?: return
        mutablePreview.value = null
        folder(chosen.folder.uri, FolderMode.LIBRARY, restoreDeleted = false)
    }
    fun confirmPreview(mode: FolderMode, restoreDeleted: Boolean) {
        val chosen = mutablePreview.value ?: return
        mutablePreview.value = null
        folder(chosen.folder.uri, mode, restoreDeleted = restoreDeleted)
    }
    fun addEpub(uri: Uri) = action("Uploading EPUB…") {
        val book = requireNotNull(mutableAddedBook.value)
        requireNotNull(epubs).import(book, uri)
        mutableEpubUploaded.value = true
        "EPUB added. Your ReadAlong will use its text."
    }
    suspend fun bookCount(folder: FolderEntity) = importer.bookCount(folder.uri)
    fun rescan(folder: FolderEntity) = action { val result = importer.scan(Uri.parse(folder.uri)); result.message }
    /** The person chose a folder again after ReadAlong lost access: the same folder is rescanned, another one is previewed like a new add. */
    fun reconnect(folder: FolderEntity, uri: Uri) {
        if (uri.toString() == folder.uri) folder(uri, FolderMode.valueOf(folder.mode), restoreDeleted = false)
        else previewFolder(uri, "folders")
    }
    /** The folder and its books leave the library at once; their clean-up finishes in the background. */
    fun remove(folder: FolderEntity) {
        importer.removeFolderInBackground(folder) { showMessage(it.message ?: "That folder couldn't be removed. Please try again.") }
        showMessage("Folder removed from your library.")
    }
    /** How far the folder scan under way has got, once its books are counted. */
    val scanProgress = importer.scanProgress
    private val mutableSynced = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** What a background rescan brought in, e.g. Synced “Audiobooks” · 2 new · 1 ReadAlong; nothing when it changed nothing. */
    val synced = mutableSynced.asSharedFlow()
    private val mutableScanningFolder = MutableStateFlow<String?>(null)
    val scanningFolder = mutableScanningFolder.asStateFlow()
    private var autoScan: kotlinx.coroutines.Job? = null
    fun refreshLibrary() {
        if (autoScan?.isActive == true) return
        autoScan = viewModelScope.launch {
            while (busy.value) kotlinx.coroutines.delay(150)
            try { syncedNote(importer.rescanSavedFolders { mutableScanningFolder.value = it?.name })?.let { mutableSynced.tryEmit(it) } }
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
    private fun action(pendingMessage: String = "Reading audio metadata…", work: suspend () -> String?) {
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

/**
 * The note a background rescan leaves when it brought something in: Synced “Audiobooks” · 2 new · 1 ReadAlong
 * (no name when several folders brought something). Null when nothing changed.
 */
internal fun syncedNote(results: List<Pair<FolderEntity, com.example.readalong.data.importing.ImportResult>>): String? {
    val changed = results.filter { (_, result) -> result.newBookIds.isNotEmpty() || result.transcriptsTaken > 0 }
    if (changed.isEmpty()) return null
    val added = changed.sumOf { it.second.newBookIds.distinct().size }
    val readAlongs = changed.sumOf { it.second.transcriptsTaken }
    val parts = listOfNotNull(added.takeIf { it > 0 }?.let { "$it new" }, readAlongs.takeIf { it > 0 }?.let { if (it == 1) "1 ReadAlong" else "$it ReadAlongs" })
    val title = changed.singleOrNull()?.let { "Synced “${it.first.name}”" } ?: "Synced"
    return (listOf(title) + parts).joinToString(" · ")
}

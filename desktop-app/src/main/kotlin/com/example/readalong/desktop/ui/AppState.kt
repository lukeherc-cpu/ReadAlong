package com.example.readalong.desktop.ui

import androidx.compose.runtime.*
import com.example.readalong.desktop.audio.AudioPlayer
import com.example.readalong.desktop.core.*
import com.example.readalong.desktop.reading.BookTextBuilder
import com.example.readalong.desktop.reading.ReadingText
import com.example.readalong.desktop.transcription.ModelStore
import com.example.readalong.desktop.transcription.TranscriptStore
import com.example.readalong.desktop.transcription.TranscriptionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

sealed interface Screen {
    data object Library : Screen
    data class Player(val bookId: String) : Screen
    data class Reader(val bookId: String) : Screen
    data object Queue : Screen
    data object Settings : Screen
}

/** Everything the window shows, wired together once. */
class AppState(val scope: CoroutineScope, private val background: Boolean = true) {
    val settingsStore = SettingsStore()
    val library = LibraryStore(scope = scope)
    val transcripts = TranscriptStore()
    val models = ModelStore()
    val importer = LibraryImporter(library, transcripts)
    val player = AudioPlayer(scope, library, settingsStore)
    val transcription = TranscriptionService(scope, library, settingsStore, transcripts, models, probe = background)
    /** Shares listening positions with the phone through a file beside each book. */
    val progress = ProgressSync(library)

    var screen by mutableStateOf<Screen>(Screen.Library)
        private set
    private val history = ArrayDeque<Screen>()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Ctrl+F in the reader. */
    var searchRequests by mutableStateOf(0)

    fun open(next: Screen) { if (next != screen) { history.addLast(screen); screen = next } }
    fun back() { screen = history.removeLastOrNull() ?: Screen.Library }
    fun home() { history.clear(); screen = Screen.Library }

    init {
        scope.launch { transcription.messages.collect { messages.emit(it) } }
        if (background) scope.launch(Dispatchers.IO) { runCatching { importer.rescan() }; syncProgressNow() }
        // Pausing hands the position to the phone.
        if (background) scope.launch {
            var last: Pair<String?, Boolean>? = null
            player.state.collect { state ->
                val previous = last
                if (previous?.second == true && (!state.playing || state.bookId != previous.first)) previous.first?.let { id ->
                    // The library catches up with the paused position a moment later; the player's state has it now.
                    library.book(id)?.let { b -> withContext(Dispatchers.IO) { progress.write(if (state.bookId == id) b.copy(positionMs = state.positionMs) else b) } }
                }
                last = state.bookId to state.playing
            }
        }
    }

    /** Picks up positions from the phone; run when the window comes to the front. */
    fun syncProgress() { if (background) scope.launch(Dispatchers.IO) { syncProgressNow() } }
    private fun syncProgressNow() = runCatching {
        val state = player.state.value
        val changed = progress.syncAll(playing = state.bookId.takeIf { state.playing })
        // A book that is loaded but paused moves to the phone's place too.
        if (!state.playing && state.bookId in changed) library.book(state.bookId!!)?.let { player.seekTo(it.positionMs) }
    }

    fun addPaths(paths: List<File>) = scope.launch {
        val added = runCatching { importer.add(paths) }.getOrElse { messages.emit(it.message ?: "Those files couldn't be added."); return@launch }
        messages.emit(when (added.size) { 0 -> "No new audiobooks found there."; 1 -> "Added ${library.book(added[0])?.title}."; else -> "Added ${added.size} audiobooks." })
    }

    fun play(bookId: String) {
        if (player.state.value.bookId == bookId) player.play()
        else { library.book(bookId)?.let { runCatching { progress.sync(it) } }; player.open(bookId) }
    }

    private val readers = HashMap<String, ReaderModel>()
    fun reader(bookId: String): ReaderModel = readers.getOrPut(bookId) { ReaderModel(bookId, this) }

    fun shutdown() {
        val current = player.state.value.bookId
        player.close(); transcription.shutdown()
        current?.let { id -> library.book(id)?.let { runCatching { progress.write(it) } } }
        library.flush()
    }
}

/**
 * The text of one book for the reader, rebuilt off the UI thread as transcription adds to it. An EPUB
 * is matched again at most every half minute while its book is being transcribed (matching takes a few seconds).
 */
class ReaderModel(val bookId: String, private val app: AppState) {
    private val mutableText = MutableStateFlow<ReadingText?>(null)
    val text: StateFlow<ReadingText?> = mutableText
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError

    init {
        app.scope.launch(Dispatchers.Default) {
            app.library.observe(bookId).map { it?.textSource to it?.epubPath }.distinctUntilChanged().collectLatest { source ->
                val epub = source.second?.let(::File)?.takeIf { source.first == "EPUB" && it.isFile }
                app.transcripts.revisions.map { it[bookId] ?: 0 }.distinctUntilChanged().conflate().collect {
                    build(epub)
                    // Let a few chunks accumulate before rebuilding; matching an EPUB takes a few seconds.
                    delay(if (epub != null) 30_000 else 1_500)
                }
            }
        }
    }

    private suspend fun build(epub: File?) {
        val book = app.library.book(bookId) ?: return
        mutableBusy.value = true
        try {
            val segments = app.transcripts.segments(book)
            val job = currentCoroutineContext()[Job]
            mutableText.value = if (epub != null) BookTextBuilder.epub(book, epub, segments) { job?.ensureActive() }
                else BookTextBuilder.generated(book, segments)
            mutableError.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            mutableError.value = failure.message ?: "The text couldn't be loaded."
            if (epub != null) mutableText.value = BookTextBuilder.generated(book, app.transcripts.segments(book))
        } finally { mutableBusy.value = false }
    }
}

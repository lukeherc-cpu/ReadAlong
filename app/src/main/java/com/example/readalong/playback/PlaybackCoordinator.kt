package com.example.readalong.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.session.SessionResult
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.repository.BookRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A single UI facade. Fictional silent samples never feed the real audio service. */
class PlaybackCoordinator(context: Context, repository: BookRepository, private val database: ReadAlongDatabase,
    private val store: PlaybackStore, private val notes: com.example.readalong.data.book.BookNotesRepository? = null) : PlaybackControls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + com.example.readalong.data.diagnostics.BackgroundFailures.handler("Playback"))
    private val demo = DemoPlaybackController(repository)
    private val connection = PlaybackConnection(context)
    private val selectedDemo = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    private val selectLock = Mutex()
    override val state = combine(demo.state, store.state, selectedDemo, failure) { sample, real, isDemo, error ->
        (if (isDemo) sample else real).let { if (error != null) it.copy(error = error) else it }
    }.stateIn(scope, SharingStarted.Eagerly, PlaybackState())

    init {
        scope.launch {
            store.state.collect { if (it.isPlaying && selectedDemo.value) { demo.pause(); selectedDemo.value = false } }
        }
        scope.launch {
            // Restore selection only, never auto-start audio. An already running service is retained.
            val id = database.bookDao().metadata("current_book") ?: return@launch
            repository.observeBook(id).first()?.let { if (state.value.bookId == null) select(it) }
        }
    }
    override fun select(book: Book) { run {
        selectLock.withLock {
            if (book.isDemo && state.value.bookId == book.id) return@withLock
            demo.pause()
            if (book.isDemo) {
                if (store.state.value.bookId != null) connection.controller().pause()
                demo.select(book); selectedDemo.value = true
            } else {
                selectedDemo.value = false
                val result = connection.command(SELECT_BOOK, Bundle().apply { putString("bookId", book.id) })
                check(result.resultCode == SessionResult.RESULT_SUCCESS) { "Couldn't open audio. Check file access and rescan its folder." }
            }
            database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("current_book", book.id))
        }
    } }
    override fun pause() = if (selectedDemo.value) demo.pause() else run { connection.controller().pause() }
    override fun play() = if (selectedDemo.value) { if (!demo.state.value.isPlaying) demo.toggle(); Unit } else run { connection.controller().play() }
    /**
     * Saves where the listener is before a jump, for Listening history. Skips, chapter changes and tapped
     * sentences always count; other seeks only when they move at least 15 s. The demo book keeps no history.
     */
    private fun remember(kind: com.example.readalong.domain.model.JumpKind, targetMs: Long? = null) {
        val now = state.value
        val bookId = now.bookId ?: return
        if (selectedDemo.value || notes == null) return
        // Every tapped sentence counts; other seeks only when they go somewhere new.
        val least = if (kind == com.example.readalong.domain.model.JumpKind.SENTENCE) 1_000L else MIN_JUMP_MS
        if (targetMs != null && kotlin.math.abs(targetMs - now.positionMs) < least) return
        val entry = com.example.readalong.domain.model.HistoryEntry(now.positionMs, kind, now.chapterTitle, System.currentTimeMillis())
        scope.launch { runCatching { notes.addHistory(bookId, entry) } }
    }
    override fun listenFrom(positionMs: Long) = remember(com.example.readalong.domain.model.JumpKind.SENTENCE, positionMs).let { if (selectedDemo.value) { demo.seek(positionMs); if (!demo.state.value.isPlaying) demo.toggle(); Unit } else run {
        connection.command(SEEK_BOOK, Bundle().apply { putLong("positionMs", positionMs.coerceAtLeast(0)); putBoolean("play", true) })
    } }
    override fun toggle() = if (selectedDemo.value) demo.toggle() else run {
        val id = state.value.bookId ?: return@run
        connection.command(SELECT_BOOK, Bundle().apply { putString("bookId", id) })
        val player = connection.controller()
        if (player.playWhenReady) player.pause() else { if (player.playerError != null) player.prepare(); player.play() }
    }
    override fun seek(positionMs: Long) { remember(com.example.readalong.domain.model.JumpKind.SEEK, positionMs); seekTo(positionMs) }
    private fun seekTo(positionMs: Long) = if (selectedDemo.value) demo.seek(positionMs) else run {
        connection.command(SEEK_BOOK, Bundle().apply { putLong("positionMs", positionMs.coerceAtLeast(0)) })
    }
    override fun skip(deltaMs: Long) {
        remember(if (deltaMs < 0) com.example.readalong.domain.model.JumpKind.SKIP_BACK else com.example.readalong.domain.model.JumpKind.SKIP_FORWARD)
        seekTo(state.value.positionMs + deltaMs)
    }
    override fun speed(value: Float) = if (selectedDemo.value) demo.speed(value) else run { connection.controller().setPlaybackSpeed(com.example.readalong.domain.model.roundSpeed(value)) }
    override fun sleep(minutes: Int?) = if (selectedDemo.value) demo.sleep(minutes) else run {
        connection.command(SLEEP_TIMER, Bundle().apply { putInt("minutes", minutes ?: 0) })
    }
    override fun sleepMore(minutes: Int) = if (selectedDemo.value) demo.sleepMore(minutes) else run {
        connection.command(SLEEP_TIMER, Bundle().apply { putInt("addMinutes", minutes) })
    }
    override fun sleepAtChapterEnd() = if (selectedDemo.value) demo.sleepAtChapterEnd() else run {
        connection.command(SLEEP_TIMER, Bundle().apply { putBoolean("chapterEnd", true) })
    }
    override fun chapter(index: Int) = remember(com.example.readalong.domain.model.JumpKind.CHAPTER).let { if (selectedDemo.value) demo.chapter(index) else run { connection.command(SEEK_CHAPTER, Bundle().apply { putInt("chapterIndex", index) }) } }
    suspend fun releaseBook(bookId: String) = withContext(Dispatchers.Main.immediate) {
        selectLock.withLock {
            if (state.value.bookId == bookId) {
                if (selectedDemo.value) { demo.pause(); selectedDemo.value = false }
                else check(connection.command(CLEAR_BOOK, Bundle.EMPTY).resultCode == SessionResult.RESULT_SUCCESS)
            }
            if (database.bookDao().metadata("current_book") == bookId) database.bookDao().removeMetadata("current_book")
        }
    }
    private fun run(work: suspend () -> Unit) {
        scope.launch {
            failure.value = null
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure.value = "Audio couldn't be opened. Check access to the original file and try again." }
        }
    }
    private companion object { const val MIN_JUMP_MS = 15_000L }
}

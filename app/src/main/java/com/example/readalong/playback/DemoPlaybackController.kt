package com.example.readalong.playback

import com.example.readalong.domain.model.Book
import com.example.readalong.domain.repository.BookRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Silent, labeled simulation for sample books; replaced by service playback for real media. */
class DemoPlaybackController(private val repository: BookRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                val old = mutableState.value
                val expired = old.sleepEndsAt?.let { System.currentTimeMillis() >= it } == true
                val next = if (old.isPlaying && !expired) (old.positionMs + (500 * old.speed).toLong()).coerceAtMost(old.durationMs) else old.positionMs
                val chapterEnded = old.sleepAtChapterEnd && chapterStart(next) > chapterStart(old.positionMs)
                mutableState.value = old.copy(positionMs = next, isPlaying = old.isPlaying && !expired && !chapterEnded && next < old.durationMs,
                    sleepEndsAt = if (expired) null else old.sleepEndsAt, sleepAtChapterEnd = old.sleepAtChapterEnd && !chapterEnded)
                if (old.isPlaying && ++ticks % 10 == 0) save()
            }
        }
    }

    fun select(book: Book) {
        if (state.value.bookId == book.id) return
        save()
        mutableState.value = PlaybackState(bookId = book.id, positionMs = book.positionMs, durationMs = book.durationMs ?: 0, speed = state.value.speed)
    }

    fun pause() { if (state.value.isPlaying) { mutableState.value = state.value.copy(isPlaying = false); save() } }
    fun chapter(index: Int) = seek(index * 1_800_000L)
    fun toggle() {
        mutableState.value = state.value.copy(isPlaying = !state.value.isPlaying && state.value.durationMs > 0)
        save()
    }
    fun seek(positionMs: Long) {
        mutableState.value = state.value.copy(positionMs = positionMs.coerceIn(0, state.value.durationMs))
        save()
    }
    fun skip(deltaMs: Long) = seek(state.value.positionMs + deltaMs)
    fun speed(value: Float) { mutableState.value = state.value.copy(speed = value.coerceIn(.5f, 2f)) }
    fun sleep(minutes: Int?) {
        mutableState.value = state.value.copy(sleepEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }, sleepAtChapterEnd = false)
    }
    fun sleepAtChapterEnd() { mutableState.value = state.value.copy(sleepEndsAt = null, sleepAtChapterEnd = true) }
    private fun save() {
        val snapshot = state.value
        snapshot.bookId?.let { id -> scope.launch { runCatching { repository.updateListeningPosition(id, snapshot.positionMs) } } }
    }
}

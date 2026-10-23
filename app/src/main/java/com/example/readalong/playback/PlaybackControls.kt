package com.example.readalong.playback

import com.example.readalong.domain.model.Book
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PlaybackControls {
    val state: StateFlow<PlaybackState>
    fun select(book: Book)
    fun pause()
    fun play()
    fun listenFrom(positionMs: Long)
    fun toggle()
    fun seek(positionMs: Long)
    fun skip(deltaMs: Long)
    fun speed(value: Float)
    fun sleep(minutes: Int?)
    fun sleepAtChapterEnd()
    fun chapter(index: Int)
}

data class PlaybackChapter(val index: Int, val title: String, val startMs: Long, val durationMs: Long?, val fileIndex: Int = index, val fileStartMs: Long = 0)

/** Service publishes observations; UI sends commands through MediaController. */
class PlaybackStore {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    internal fun publish(value: PlaybackState) { mutableState.value = value }
}

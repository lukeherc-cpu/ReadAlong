package com.example.readalong.data.processing

import com.example.readalong.data.reading.DemoTranscript
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/** Exercises the pipeline using original sample prose; never supplies text for imported audio. */
class SampleTranscriptionEngine(private val delayMs: Long = 350) : TranscriptionEngine {
    override val id = "sample-v1"
    override fun transcribe(input: TranscriptionInput) = flow {
        require(input.isDemo && input.bookId.startsWith("demo-")) { "The sample engine cannot transcribe imported audio." }
        DemoTranscript.segments(input.bookId).filter { it.chapterId == input.chapterId }.forEach {
            delay(delayMs)
            emit(it)
        }
    }
}

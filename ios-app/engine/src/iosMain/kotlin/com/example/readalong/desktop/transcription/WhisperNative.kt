package com.example.readalong.desktop.transcription

/**
 * whisper.cpp, linked into the app (iosApp/Whisper/readalong_whisper.cpp). Swift implements this interface over
 * the C functions (WhisperBridge.swift) and installs it at launch, so Kotlin needs no C interop of its own.
 * [transcribe]'s `samples` is the address of [count] pinned 16 kHz mono floats.
 */
interface WhisperEngine {
    fun devices(): String
    fun systemInfo(): String
    /** [gpuDevice] counts GPUs only; -1 is the CPU. 0 when the model can't load. */
    fun load(model: String, gpuDevice: Int, alignmentHeads: Int): Long
    fun free(handle: Long)
    fun cancel(handle: Long)
    fun progress(handle: Long): Int
    /** Recognition JSON, or null when whisper failed on this audio. */
    fun transcribe(handle: Long, samples: Long, count: Int, language: String, threads: Int, beams: Int): String?
}

object WhisperNative {
    var engine: WhisperEngine? = null
    fun require(): WhisperEngine = engine ?: error("whisper.cpp isn't part of this build of ReadAlong.")
}

package com.example.readalong.ios

import com.example.readalong.desktop.transcription.WhisperEngine
import com.example.readalong.desktop.transcription.WhisperNative

/**
 * whisper.cpp as Swift sees it (iosApp/Sources/WhisperBridge.swift implements this). It mirrors the engine's
 * WhisperEngine; only this module is exported to Swift, which keeps the framework header small.
 */
interface WhisperCalls {
    fun devices(): String
    fun systemInfo(): String
    fun load(model: String, gpuDevice: Int, alignmentHeads: Int): Long
    fun free(handle: Long)
    fun cancel(handle: Long)
    fun progress(handle: Long): Int
    fun transcribe(handle: Long, samples: Long, count: Int, language: String, threads: Int, beams: Int): String?
}

/** Called once at launch, before MainViewController. */
@Suppress("unused")
fun installWhisper(calls: WhisperCalls) {
    WhisperNative.engine = object : WhisperEngine {
        override fun devices() = calls.devices()
        override fun systemInfo() = calls.systemInfo()
        override fun load(model: String, gpuDevice: Int, alignmentHeads: Int) = calls.load(model, gpuDevice, alignmentHeads)
        override fun free(handle: Long) = calls.free(handle)
        override fun cancel(handle: Long) = calls.cancel(handle)
        override fun progress(handle: Long) = calls.progress(handle)
        override fun transcribe(handle: Long, samples: Long, count: Int, language: String, threads: Int, beams: Int) =
            calls.transcribe(handle, samples, count, language, threads, beams)
    }
}

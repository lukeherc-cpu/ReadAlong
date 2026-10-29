package com.example.readalong.desktop.transcription

import java.io.File

/** whisper.cpp through native/readalong_whisper.cpp. Used only inside the transcriber process ([Worker]). */
object WhisperNative {
    /** Loads whisper.cpp from [directory] and every backend this computer can run (Vulkan GPUs, the best CPU variant). */
    fun load(directory: File, verbose: Boolean = false) {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        fun library(name: String) = File(directory, if (windows) "$name.dll" else "lib$name.so")
        // Dependencies first: Windows doesn't search the folder of the library that needs them.
        listOf("ggml-base", "ggml", "whisper", "readalong_whisper").forEach { System.load(library(it).absolutePath) }
        loadBackends(directory.absolutePath, verbose)
    }

    private external fun loadBackends(directory: String, verbose: Boolean)
    /** JSON: [{name, description, backend, type: cpu|gpu|igpu, memoryTotal, memoryFree}] */
    external fun devices(): String
    external fun systemInfo(): String
    /** [gpuDevice] counts GPUs only; -1 is the CPU. [alignmentHeads] is whisper.cpp's DTW preset. 0 when the model can't load. */
    external fun load(model: String, gpuDevice: Int, alignmentHeads: Int): Long
    external fun free(handle: Long)
    external fun cancel()
    external fun progress(): Int
    /** JSON: {language, segments: [{startMs, endMs, text, noSpeech, tokens: [{text, startMs, endMs, dtwMs, confidence}]}]} */
    external fun transcribe(handle: Long, audio: FloatArray, language: String, threads: Int, beams: Int): String
}

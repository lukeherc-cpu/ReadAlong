package com.example.readalong.desktop.transcription

/** A Whisper model in whisper.cpp's format. [heads] is whisper.cpp's alignment-heads preset for word times. */
data class SpeechModel(val id: String, val name: String, val sizeBytes: Long, val englishOnly: Boolean, val heads: Int,
    /** Rough relative speed (1 = tiny) and quality, for the picker. */
    val relativeCost: Double, val summary: String) {
    val fileName get() = "ggml-$id.bin"
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    /** The phone's DesktopTranscriptImporter accepts "desktop:<backend>:<model>". */
    val packageId get() = "desktop:whisper.cpp:$id"
    val engineName get() = "Whisper $id"
}

data class Device(val name: String, val description: String, val backend: String, val type: String, val memoryTotal: Long) {
    val isGpu get() = type == "gpu" || type == "igpu"
    val label get() = if (isGpu) "${description.ifBlank { name }} ($backend)" else "CPU"
}

object SpeechModels {
    // DTW presets: whisper_alignment_heads_preset in whisper.h.
    val all = listOf(
        SpeechModel("tiny.en", "Tiny (English)", 77_704_715, true, 3, 1.0, "Fastest and smallest. More mistakes in names."),
        SpeechModel("tiny", "Tiny (every language)", 77_691_713, false, 4, 1.0, "Fastest and smallest, for books in other languages. More mistakes."),
        SpeechModel("base.en", "Base (English)", 147_964_211, true, 5, 2.0, "Fast on any computer. Good for clearly read books."),
        SpeechModel("small.en", "Small (English)", 487_614_201, true, 7, 5.0, "Accurate. Comfortable on a recent CPU, quick on a GPU."),
        SpeechModel("large-v3-turbo-q5_0", "Large v3 Turbo", 574_041_195, false, 14, 9.0, "Most accurate, every language. Best with a GPU."),
    )
    fun find(id: String?) = all.firstOrNull { it.id == id }

    /**
     * Used until a model is chosen in Settings. A computer is where the accurate text gets made, so one with a real
     * graphics card (3 GB or more) starts on Large v3 Turbo, and one with built-in graphics on Base for English.
     * Without a GPU, or before the devices are known ([devices] null), it is the smallest, quickest model for the language.
     */
    fun default(language: String, devices: List<Device>? = null): SpeechModel {
        val gpus = devices.orEmpty().filter { it.isGpu }
        val card = gpus.any { it.type == "gpu" && it.memoryTotal >= 3L * 1024 * 1024 * 1024 }
        return find(when {
            card -> "large-v3-turbo-q5_0"
            gpus.isNotEmpty() && language == "en" -> "base.en"
            language == "en" -> "tiny.en"
            else -> "tiny"
        })!!
    }

    fun byPackage(packageId: String?): SpeechModel? = all.firstOrNull { it.packageId == packageId }

    /**
     * How good a transcript's model is, from a package's "model" id, for choosing between two transcripts of one
     * book: the phone's models and Tiny are 1, then Base, Small and Large v3 Turbo.
     */
    fun quality(packageId: String?): Int = when (byPackage(packageId)?.id) {
        "base.en" -> 2
        "small.en" -> 3
        "large-v3-turbo-q5_0" -> 4
        else -> 1
    }

    /** The GPU whisper.cpp should use, as its GPU index, or -1 for the CPU. Prefers a discrete card with the most memory. */
    fun gpuIndex(devices: List<Device>, preference: String): Int {
        if (preference == "cpu") return -1
        val gpus = devices.filter { it.isGpu }
        val best = gpus.withIndex().maxWithOrNull(compareBy({ if (it.value.type == "gpu") 1 else 0 }, { it.value.memoryTotal })) ?: return -1
        return best.index
    }
}

package com.example.readalong.domain.transcription

/** The sole runtime/model offered by this release. The identity pins weights and timing behavior. */
data class SpeechModel(val id: String, val name: String, val fileName: String, val size: Long, val sha256: String,
    val url: String, val backend: String = "whistle")
object SpeechModels {
    val whistle = SpeechModel("cactus-whistle-v2-b6e02f04", "Cactus Whistle", "whistle.cact", 16919407L,
        "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
        "https://huggingface.co/Cactus-Compute/whistle/resolve/ca5287601bef25af26dcf2e1b2bdc0843a7c19e5/whistle.cact")
    val all = listOf(whistle)
    fun find(id: String) = all.firstOrNull { it.id == id }
    fun require(id: String) = requireNotNull(find(id)) { "ReadAlong has a new speech model. Redo this ReadAlong to keep going." }
}

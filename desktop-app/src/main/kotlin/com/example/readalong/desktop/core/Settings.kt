package com.example.readalong.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class AppSettings(
    /** A [com.example.readalong.desktop.transcription.SpeechModels] id, or null for the default (the smallest, quickest one). */
    val modelId: String? = null,
    /** "auto" (the best GPU, else the CPU), "gpu" or "cpu". */
    val device: String = "auto",
    /** A Whisper language code, or "auto" to detect it. */
    val language: String = "en",
    /** Write each finished ReadAlong beside its audiobook as <book>.readalong, so the phone's library scan finds it. */
    val savePackages: Boolean = true,
    val accent: Long = 0xFF496B8A,
    val readerFont: String = "LITERATA",
    val readerSize: Float = 22f,
    val readerTheme: String = "DARK",
    val skipBackSeconds: Int = 20,
    val skipForwardSeconds: Int = 30,
    val volume: Float = 1f,
    val gridCovers: Boolean = true,
    /** Run several transcribers at once (on the graphics card and the processor together) with quicker decoding, so books finish sooner. */
    val aggressive: Boolean = true,
    /** Total time spent playing audiobooks, across every book. */
    val listenedMs: Long = 0,
    /** When [listenedMs] reaches this, the "Enjoying ReadAlong?" card shows; each close without "Don't show again" moves it 10 hours on. */
    val supportPromptAtMs: Long = SUPPORT_PROMPT_EVERY_MS,
    val supportPromptOff: Boolean = false,
)

const val SUPPORT_PROMPT_EVERY_MS = 10L * 60 * 60 * 1000

class SettingsStore(private val file: java.io.File = AppPaths.settings) {
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = mutable
    val value get() = mutable.value

    fun update(change: (AppSettings) -> AppSettings) {
        mutable.update(change)
        val s = mutable.value
        runCatching {
            file.writeAtomically(JSONObject().put("modelId", s.modelId).put("device", s.device).put("language", s.language)
                .put("savePackages", s.savePackages).put("accent", s.accent).put("readerFont", s.readerFont).put("readerSize", s.readerSize.toDouble())
                .put("readerTheme", s.readerTheme).put("skipBack", s.skipBackSeconds).put("skipForward", s.skipForwardSeconds)
                .put("volume", s.volume.toDouble()).put("gridCovers", s.gridCovers).put("aggressive", s.aggressive)
                .put("listenedMs", s.listenedMs).put("supportPromptAtMs", s.supportPromptAtMs).put("supportPromptOff", s.supportPromptOff).toString(1))
        }
    }

    private fun load(): AppSettings {
        if (!file.isFile) return AppSettings()
        return runCatching {
            val o = JSONObject(file.readText()); val d = AppSettings()
            AppSettings(if (o.isNull("modelId") || !o.has("modelId")) null else o.getString("modelId"), o.optString("device", d.device),
                o.optString("language", d.language), o.optBoolean("savePackages", d.savePackages), o.optLong("accent", d.accent),
                o.optString("readerFont", d.readerFont), o.optDouble("readerSize", d.readerSize.toDouble()).toFloat(),
                o.optString("readerTheme", d.readerTheme), o.optInt("skipBack", d.skipBackSeconds), o.optInt("skipForward", d.skipForwardSeconds),
                o.optDouble("volume", 1.0).toFloat(), o.optBoolean("gridCovers", true), o.optBoolean("aggressive", d.aggressive),
                o.optLong("listenedMs", 0), o.optLong("supportPromptAtMs", d.supportPromptAtMs), o.optBoolean("supportPromptOff", false))
        }.getOrDefault(AppSettings())
    }
}

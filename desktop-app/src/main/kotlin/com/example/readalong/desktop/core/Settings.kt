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
    /** Look for a newer ReadAlong on GitHub once a day. */
    val checkUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** A version the person chose to skip; only a newer one is offered. */
    val skippedVersion: String? = null,
    /** Total time spent playing audiobooks, across every book. */
    val listenedMs: Long = 0,
    /** When [listenedMs] reaches this, the "Enjoying ReadAlong?" card shows; each close without "Don't show again" moves it 10 hours on. */
    val supportPromptAtMs: Long = SUPPORT_PROMPT_EVERY_MS,
    val supportPromptOff: Boolean = false,
    /** The light theme for the library, queue and Settings (the player keeps its book colours), like the phone's "Light theme". */
    val light: Boolean = false,
    /** Settings shows the tip jar at the bottom, under Advanced, instead of at the top (the phone's "Move tip jar to Other settings"). */
    val tipJarInOther: Boolean = false,
    /** The mini player takes the playing book's cover colours; off, it uses the accent colour. */
    val coloredMiniPlayer: Boolean = true,
    /** The accent colour everywhere instead of each book's cover colours (the phone's "Use accent color instead of cover colors"). */
    val alwaysAccent: Boolean = false,
    /** Titles and authors under the covers in the library. */
    val showTitleAndAuthor: Boolean = true,
    /** A [com.example.readalong.desktop.ui.Fonts.reading] key for the app's own text (menus, library, Settings). */
    val appFont: String = "JAKARTA",
    /** Closing the window leaves ReadAlong in the tray: books keep being made and the phone keeps syncing. */
    val keepRunning: Boolean = false,
)

const val SUPPORT_PROMPT_EVERY_MS = 10L * 60 * 60 * 1000

class SettingsStore(private val file: java.io.File = AppPaths.settings) {
    companion object {
        /** The saved light theme, read before the window opens (macOS picks the title bar appearance at startup). */
        fun savedLight(file: java.io.File = AppPaths.settings): Boolean = runCatching { JSONObject(file.readText()).optBoolean("light", false) }.getOrDefault(false)
    }

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
                .put("checkUpdates", s.checkUpdates).put("lastUpdateCheck", s.lastUpdateCheck).put("skippedVersion", s.skippedVersion ?: JSONObject.NULL)
                .put("listenedMs", s.listenedMs).put("supportPromptAtMs", s.supportPromptAtMs).put("supportPromptOff", s.supportPromptOff)
                .put("light", s.light).put("tipJarInOther", s.tipJarInOther)
                .put("coloredMiniPlayer", s.coloredMiniPlayer).put("alwaysAccent", s.alwaysAccent).put("showTitleAndAuthor", s.showTitleAndAuthor)
                .put("appFont", s.appFont).put("keepRunning", s.keepRunning).toString(1))
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
                o.optBoolean("checkUpdates", d.checkUpdates), o.optLong("lastUpdateCheck", 0), if (o.isNull("skippedVersion")) null else o.optString("skippedVersion").ifBlank { null },
                o.optLong("listenedMs", 0), o.optLong("supportPromptAtMs", d.supportPromptAtMs), o.optBoolean("supportPromptOff", false),
                light = o.optBoolean("light", d.light), tipJarInOther = o.optBoolean("tipJarInOther", d.tipJarInOther),
                coloredMiniPlayer = o.optBoolean("coloredMiniPlayer", d.coloredMiniPlayer), alwaysAccent = o.optBoolean("alwaysAccent", d.alwaysAccent),
                showTitleAndAuthor = o.optBoolean("showTitleAndAuthor", d.showTitleAndAuthor), appFont = o.optString("appFont", d.appFont).ifBlank { d.appFont }, keepRunning = o.optBoolean("keepRunning", d.keepRunning))
        }.getOrElse { VersionUpgrade.setAside(file); AppSettings() }
    }
}

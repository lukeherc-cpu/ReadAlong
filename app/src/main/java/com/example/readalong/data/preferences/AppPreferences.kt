package com.example.readalong.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appDataStore by preferencesDataStore("app_preferences", corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() })

data class AppPreferences(
    val libraryLight: Boolean = true,
    val openingView: String = "COVER",
    val coloredMiniPlayer: Boolean = true,
    val playerBack: Int = 20,
    val playerForward: Int = 30,
    val readerBack: Int = 5,
    val readerForward: Int = 5,
    val chargingOnly: Boolean = false,
    val batteryWarningSeen: Boolean = false,
    val listLibrary: Boolean = false,
    val basicColor: Long = 0xFF496B8A,
    val alwaysBasicColor: Boolean = false,
    val blurredPlayer: Boolean = true,
    val setupStage: Int = if (batteryWarningSeen) 3 else 0,
    val chargingOverrideUntil: Long = 0,
    val animateTranscriptionLogo: Boolean = true,
    val appFont: String = "JAKARTA",
    val showTitleAndAuthor: Boolean = true,
    val homeGuideHidden: Boolean = false,
    val playerGuideHidden: Boolean = false,
    val readerGuideHidden: Boolean = false,
    val tipJarInOther: Boolean = false,
    /** The short "what is a ReadAlong" explanation has been shown. */
    val readAlongExplained: Boolean = false,
    val longPressTipHidden: Boolean = false,
    val coverTipHidden: Boolean = false,
)

class AppPreferencesStore(private val context: Context) {
    private val store = context.applicationContext.appDataStore
    private fun decode(p: Preferences) = AppPreferences(
        p[booleanPreferencesKey("library_light")] ?: true,
        p[stringPreferencesKey("opening_view")]?.takeIf { it in listOf("COVER", "PREVIEW", "FULLSCREEN", "READ_ONLY") } ?: "COVER",
        p[booleanPreferencesKey("colored_mini")] ?: true,
        (p[intPreferencesKey("player_back")] ?: 20).coerceIn(1, 600),
        (p[intPreferencesKey("player_forward")] ?: 30).coerceIn(1, 600),
        (p[intPreferencesKey("reader_back")] ?: 5).coerceIn(1, 600),
        (p[intPreferencesKey("reader_forward")] ?: 5).coerceIn(1, 600),
        p[booleanPreferencesKey("charging_only")] ?: false,
        p[booleanPreferencesKey("battery_warning_seen")] ?: false,
        p[booleanPreferencesKey("list_library")] ?: false,
        com.example.readalong.ui.launch.LaunchPalettes.closest(p[longPreferencesKey("basic_color")] ?: 0xFF496B8A).color,
        p[booleanPreferencesKey("always_basic_color")] ?: false,
        p[booleanPreferencesKey("blurred_player")] ?: true,
        p[intPreferencesKey("setup_stage")] ?: if (p[booleanPreferencesKey("battery_warning_seen")] == true) 3 else 0,
        p[longPreferencesKey("charging_override_until")] ?: 0,
        p[booleanPreferencesKey("animate_transcription_logo")] ?: true,
        p[stringPreferencesKey("app_font")]?.takeIf { ReaderFonts.valid(it) && it !in setOf("EPUB", "MATCH") && (it != "SANS" || p[booleanPreferencesKey("app_font_v2")] == true) } ?: "JAKARTA",
        p[booleanPreferencesKey("show_title_author")] ?: true,
        p[booleanPreferencesKey("guide_home_hidden")] ?: false,
        p[booleanPreferencesKey("guide_player_hidden")] ?: false,
        p[booleanPreferencesKey("guide_reader_hidden")] ?: false,
        p[booleanPreferencesKey("tip_jar_in_other")] ?: false,
        p[booleanPreferencesKey("readalong_explained")] ?: false,
        p[booleanPreferencesKey("long_press_tip_hidden")] ?: false,
        p[booleanPreferencesKey("cover_tip_hidden")] ?: false,
    )
    val preferences = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map(::decode)
    suspend fun update(change: (AppPreferences) -> AppPreferences) {
        val saved = store.edit { p ->
        val value = change(decode(p))
        p[booleanPreferencesKey("library_light")] = value.libraryLight
        p[stringPreferencesKey("opening_view")] = value.openingView
        p[booleanPreferencesKey("colored_mini")] = value.coloredMiniPlayer
        p[intPreferencesKey("player_back")] = value.playerBack.coerceIn(1, 600)
        p[intPreferencesKey("player_forward")] = value.playerForward.coerceIn(1, 600)
        p[intPreferencesKey("reader_back")] = value.readerBack.coerceIn(1, 600)
        p[intPreferencesKey("reader_forward")] = value.readerForward.coerceIn(1, 600)
        p[booleanPreferencesKey("charging_only")] = value.chargingOnly
        p[booleanPreferencesKey("battery_warning_seen")] = value.batteryWarningSeen
        p[booleanPreferencesKey("list_library")] = value.listLibrary
        p[longPreferencesKey("basic_color")] = com.example.readalong.ui.launch.LaunchPalettes.closest(value.basicColor).color
        p[booleanPreferencesKey("always_basic_color")] = value.alwaysBasicColor
        p[booleanPreferencesKey("blurred_player")] = value.blurredPlayer
        p[intPreferencesKey("setup_stage")] = value.setupStage.coerceIn(0, 3)
        p[longPreferencesKey("charging_override_until")] = value.chargingOverrideUntil
        p[booleanPreferencesKey("animate_transcription_logo")] = value.animateTranscriptionLogo
        p[stringPreferencesKey("app_font")] = value.appFont.takeIf { ReaderFonts.valid(it) && it !in setOf("EPUB", "MATCH") } ?: "JAKARTA"
        p[booleanPreferencesKey("app_font_v2")] = true
        p[booleanPreferencesKey("show_title_author")] = value.showTitleAndAuthor
        p[booleanPreferencesKey("guide_home_hidden")] = value.homeGuideHidden
        p[booleanPreferencesKey("guide_player_hidden")] = value.playerGuideHidden
        p[booleanPreferencesKey("guide_reader_hidden")] = value.readerGuideHidden
        p[booleanPreferencesKey("tip_jar_in_other")] = value.tipJarInOther
        p[booleanPreferencesKey("readalong_explained")] = value.readAlongExplained
        p[booleanPreferencesKey("long_press_tip_hidden")] = value.longPressTipHidden
        p[booleanPreferencesKey("cover_tip_hidden")] = value.coverTipHidden
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.readalong.ui.launch.LaunchAppearance.apply(context, decode(saved)) }
    }
}

package com.example.readalong.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.bookReaderDataStore by preferencesDataStore("book_reading_preferences", corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() })
private val Context.readerDataStore by preferencesDataStore("reader_preferences", corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() })
data class ReaderPreferences(val fontSize: Float = 20f, val lineSpacing: Float = 1.6f, val margin: Float = 24f, val theme: String = "DARK", val useEpubFonts: Boolean = true, val fontFamily: String = "EPUB", val previewFont: String = "MATCH")

class ReaderPreferencesStore(context: Context, private val store: androidx.datastore.core.DataStore<Preferences> = context.applicationContext.readerDataStore, private val defaultTheme: String = "DARK") {
    private val font = floatPreferencesKey("font_size")
    private val spacing = floatPreferencesKey("line_spacing")
    private val margin = floatPreferencesKey("margin")
    private val theme = stringPreferencesKey("theme")
    private val family = stringPreferencesKey("font_family")
    private val preview = stringPreferencesKey("preview_font")
    private val epubFonts = booleanPreferencesKey("use_epub_fonts")
    val preferences = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { ReaderPreferences(
            (it[font]?.takeIf { n -> n.isFinite() } ?: 20f).coerceIn(16f, 32f),
            (it[spacing]?.takeIf { n -> n.isFinite() } ?: 1.6f).coerceIn(1.2f, 2.2f),
            (it[margin]?.takeIf { n -> n.isFinite() } ?: 24f).coerceIn(12f, 40f),
            it[theme]?.takeIf { t -> t in setOf("DARK", "BLACK", "LIGHT", "SEPIA") } ?: defaultTheme, it[epubFonts] ?: true,
            it[family]?.takeIf { name -> ReaderFonts.valid(name) && name != "MATCH" } ?: "EPUB",
            it[preview]?.takeIf { name -> ReaderFonts.valid(name) } ?: "MATCH") }
    companion object { fun forBooks(context: Context) = ReaderPreferencesStore(context, context.applicationContext.bookReaderDataStore, "LIGHT") }
    suspend fun save(value: ReaderPreferences) = store.edit {
        it[epubFonts] = value.useEpubFonts
        it[family] = value.fontFamily
        it[preview] = value.previewFont
        it[font] = value.fontSize.coerceIn(16f, 32f)
        it[spacing] = value.lineSpacing.coerceIn(1.2f, 2.2f)
        it[margin] = value.margin.coerceIn(12f, 40f)
        it[theme] = value.theme.takeIf { name -> name in listOf("DARK", "BLACK", "LIGHT", "SEPIA") } ?: "DARK"
    }
}

package com.example.readalong.data

import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.preferences.ReaderFonts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReaderFontImportTest {
    @Test fun jakartaExtraBoldIsTheAppDefaultWhileReaderDefaultsRemainEpub() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assertEquals("JAKARTA", com.example.readalong.data.preferences.AppPreferences().appFont)
        assertEquals("EPUB", com.example.readalong.data.preferences.ReaderPreferences().fontFamily)
        val font = androidx.core.content.res.ResourcesCompat.getFont(app, com.example.readalong.R.font.jakarta_extrabold)!!
        assertEquals(800, font.weight)
        val type = com.example.readalong.ui.theme.appTypography(androidx.compose.ui.text.font.FontFamily.SansSerif, extraBold = true)
        listOf(type.headlineLarge, type.titleMedium, type.bodyLarge, type.bodySmall, type.labelSmall).forEach {
            assertEquals(androidx.compose.ui.text.font.FontWeight.ExtraBold, it.fontWeight)
        }
    }
    @Test fun importedFontSurvivesPreferenceReloadAndInvalidFilesAreRejected() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val original = app.readerPreferences.preferences.first()
        val appOriginal = app.appPreferences.preferences.first()
        val source = File(File(app.cacheDir, "exports").apply { mkdirs() }, "Personal Font.ttf")
        var key: String? = null
        try {
            app.resources.openRawResource(com.example.readalong.R.font.lato).use { input -> source.outputStream().use { input.copyTo(it) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", source)
            val font = ReaderFonts.import(app, uri); key = font.first
            assertEquals("Personal Font", font.second)
            assertTrue(ReaderFonts.file(app, font.first)!!.exists())
            app.readerPreferences.save(original.copy(fontFamily = font.first, previewFont = "LORA"))
            val reloaded = com.example.readalong.data.preferences.ReaderPreferencesStore(app).preferences.first()
            assertEquals(font.first, reloaded.fontFamily); assertEquals("LORA", reloaded.previewFont)
            assertTrue(ReaderFonts.imported(app).contains(font))
            app.appPreferences.update { it.copy(appFont = font.first) }
            assertEquals(font.first, com.example.readalong.data.preferences.AppPreferencesStore(app).preferences.first().appFont)
            assertEquals("LORA", app.readerPreferences.preferences.first().previewFont)
            source.writeText("not a font")
            assertTrue(runCatching { ReaderFonts.import(app, uri) }.isFailure)
            assertEquals(font.first, app.readerPreferences.preferences.first().fontFamily)
            assertTrue(File(app.filesDir, "reader-fonts").listFiles().orEmpty().none { it.extension == "tmp" })
        } finally {
            app.appPreferences.update { appOriginal }; app.readerPreferences.save(original); source.delete()
            key?.let { ReaderFonts.file(app, it)?.delete(); File(app.filesDir, "reader-fonts/$it.name").delete() }
        }
    }
}

package com.example.readalong.domain.i18n

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslationsTest {
    @After fun english() = Translations.use("en") { null }

    private fun use(vararg entries: Pair<String, String>) =
        Translations.use("de") { JSONObject(entries.toMap()).toString() }

    @Test fun exactText() {
        use("Settings" to "Einstellungen")
        assertEquals("Einstellungen", tr("Settings"))
        assertEquals("Unknown", tr("Unknown"))
    }

    @Test fun blanksKeepWhatChanges() {
        use("Linked to {0}" to "Verknüpft mit {0}", "{0} of {1} chapters" to "{0} von {1} Kapiteln")
        assertEquals("Verknüpft mit Pixel 9", tr("Linked to Pixel 9"))
        assertEquals("3 von 12 Kapiteln", tr("3 of 12 chapters"))
    }

    @Test fun eachSideOfADot() {
        use("Ready" to "Fertig", "{0} chapters" to "{0} Kapitel")
        assertEquals("Fertig · 3 Kapitel", tr("Ready · 3 chapters"))
    }

    @Test fun surroundingSpaceStays() {
        use("Detect" to "Erkennen")
        assertEquals(" Erkennen ", tr(" Detect "))
    }

    @Test fun englishAndUnknownLanguagesChangeNothing() {
        Translations.use("xx") { error("English needs no file") }
        assertEquals("en", Translations.language)
        assertEquals("Settings", tr("Settings"))
    }

    /** Every shipped catalog parses, and each translation keeps exactly the blanks its English has. */
    @Test fun catalogsKeepTheirBlanks() {
        val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n")).first { it.isDirectory }
        val blank = Regex("\\{\\d+\\}")
        Translations.languages.filter { it != "en" }.forEach { code ->
            val catalog = JSONObject(File(dir, "$code.json").readText())
            assertTrue("$code is nearly empty", catalog.length() > 1000)
            catalog.keys().forEach { key ->
                assertEquals("$code: $key", blank.findAll(key).map { it.value }.sorted().toList(),
                    blank.findAll(catalog.getString(key)).map { it.value }.sorted().toList())
            }
        }
    }
}

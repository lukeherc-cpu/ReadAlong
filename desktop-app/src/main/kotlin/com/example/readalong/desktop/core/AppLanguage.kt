package com.example.readalong.desktop.core

import com.example.readalong.domain.i18n.Translations
import java.util.Locale

/** The language of ReadAlong's own text on the computer (Settings → Languages → App language). */
object AppLanguage {
    const val SYSTEM = ""

    /** The language a setting resolves to: the chosen one, or the computer's when ReadAlong has it, or English. */
    fun resolve(setting: String): String =
        setting.takeIf { it in Translations.languages } ?: Locale.getDefault().language.takeIf { it in Translations.languages } ?: "en"

    /** Loads the catalog for [setting], when it isn't already the one in use. */
    fun load(setting: String) {
        val code = resolve(setting)
        if (code == Translations.language) return
        Translations.use(code) { file -> AppLanguage::class.java.getResourceAsStream("/i18n/$file")?.bufferedReader()?.use { it.readText() } }
    }

    fun name(code: String): String = if (code == SYSTEM) "System default"
        else Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
}

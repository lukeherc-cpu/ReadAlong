package com.example.readalong.ui.about

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.example.readalong.domain.i18n.Translations
import java.util.Locale

/**
 * The language of ReadAlong's own menus and buttons (Settings → Languages → App), separate from the language books are
 * transcribed in. "" follows the phone's language. Android 13 and later keep it as the per-app language (also shown in
 * the system's App languages settings, from res/xml/locales_config.xml); older phones keep it here and apply it as
 * each activity starts.
 */
object AppLanguage {
    const val SYSTEM = ""
    /** Languages ReadAlong's text is translated into (assets/i18n). Keep locales_config.xml in step. */
    val available = Translations.languages

    fun name(tag: String): String = if (tag == SYSTEM) "System default" else Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }

    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
        context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            ?.let { tag -> available.firstOrNull { tag.startsWith(it) } } ?: SYSTEM
    else stored(context)

    /**
     * Loads the catalog for the language ReadAlong's text should be in now: the chosen one, or the phone's language
     * when ReadAlong has it, or English. Called as the app and each activity start, so a change shows on the next screen.
     */
    fun load(context: Context) {
        val tag = current(context).ifEmpty { context.resources.configuration.locales[0].language }
        val code = available.firstOrNull { tag.startsWith(it) } ?: "en"
        if (code == Translations.language) return
        Translations.use(code) { file -> runCatching { context.assets.open("i18n/$file").bufferedReader().use { it.readText() } }.getOrNull() }
    }

    /** Saves [tag] and, on older phones, restarts [activity] so its text is redrawn in that language. */
    fun set(activity: android.app.Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            preferences(activity).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** For attachBaseContext before Android 13: [base] with the chosen language, or unchanged when it follows the phone. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base).takeIf { it != SYSTEM } ?: return base
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(configuration)
    }

    private const val KEY = "app_language"
    private fun preferences(context: Context) = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
    private fun stored(context: Context) = preferences(context).getString(KEY, SYSTEM)?.takeIf { it == SYSTEM || it in available } ?: SYSTEM
}

package com.example.readalong.ui.about

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language of ReadAlong's own menus and buttons (Settings → Languages → App), separate from the language books are
 * transcribed in. "" follows the phone's language. Android 13 and later keep it as the per-app language (also shown in
 * the system's App languages settings, from res/xml/locales_config.xml); older phones keep it here and apply it as
 * each activity starts.
 */
object AppLanguage {
    const val SYSTEM = ""
    /** Languages ReadAlong's text is translated into. Add a tag here and to locales_config.xml with each values-xx folder. */
    val available = listOf("en")

    fun name(tag: String): String = if (tag == SYSTEM) "System default" else Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }

    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
        context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            ?.let { tag -> available.firstOrNull { tag.startsWith(it) } } ?: SYSTEM
    else stored(context)

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

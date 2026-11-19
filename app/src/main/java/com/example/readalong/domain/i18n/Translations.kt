package com.example.readalong.domain.i18n

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ReadAlong's own text in the app language (Settings → Languages → App language), on the phone and the computer.
 *
 * The code keeps its English text, and each catalog (assets/i18n/<language>.json) maps that English to the translation.
 * Text is translated where it's shown ([tr]), not where it's made, so status messages saved in the database and compared
 * in code ("Waiting for charging") stay English. A key may hold numbered blanks for the parts that change, such as
 * "Linked to {0}", which also matches "Linked to Pixel 9" and keeps "Pixel 9" in the translation. Text with no
 * translation stays English, and so does everything while the language is English.
 */
object Translations {
    /** English, then the languages Cactus Whistle also transcribes, which ReadAlong's text is translated into. */
    val languages = listOf("en", "de", "fr", "es", "it", "nl", "pl")

    @Volatile var language: String = "en"; private set
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var patterns: List<Pattern> = emptyList()
    private val cache = ConcurrentHashMap<String, String>()

    private class Pattern(val regex: Regex, val translation: String, val fixed: Int)

    /** Switches to [code] (an entry of [languages]; anything else is English), reading its catalog with [read]. */
    fun use(code: String, read: (fileName: String) -> String?) {
        val chosen = code.takeIf { it in languages } ?: "en"
        val catalog = if (chosen == "en") JSONObject() else runCatching { read("$chosen.json")?.let(::JSONObject) }.getOrNull() ?: JSONObject()
        val plain = HashMap<String, String>()
        val blanks = ArrayList<Pattern>()
        catalog.keys().forEach { key ->
            val value = catalog.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
            if (BLANK.containsMatchIn(key)) pattern(key, value)?.let(blanks::add) else plain[key] = value
        }
        // The most specific pattern wins: the one with the most fixed text.
        exact = plain; patterns = blanks.sortedByDescending { it.fixed }; language = chosen; cache.clear()
    }

    /** [text] in the app language, or [text] itself when there's no translation. */
    fun translate(text: String): String {
        if (language == "en" || text.isBlank()) return text
        cache[text]?.let { return it }
        val result = lookup(text)
            // "Ready · 3 chapters": the parts either side of a dot, each on its own.
            ?: if (" · " in text) text.split(" · ").joinToString(" · ") { lookup(it) ?: it } else text
        if (cache.size > 4096) cache.clear()
        cache[text] = result
        return result
    }

    private fun lookup(text: String): String? {
        exact[text]?.let { return it }
        val trimmed = text.trim()
        if (trimmed != text) exact[trimmed]?.let { return text.replace(trimmed, it) }
        for (pattern in patterns) {
            val match = pattern.regex.matchEntire(text) ?: continue
            return BLANK.replace(pattern.translation) { blank ->
                match.groupValues.getOrNull(blank.groupValues[1].toInt() + 1) ?: blank.value
            }
        }
        return null
    }

    private fun pattern(key: String, translation: String): Pattern? {
        val fixed = BLANK.replace(key, "")
        if (fixed.count { it.isLetter() } < 2) return null // "{0} · {1}" would match nearly anything.
        val regex = StringBuilder()
        var last = 0
        BLANK.findAll(key).forEach { blank ->
            // A blank never reaches across " · ", so "{0} chapters" leaves "Ready · 3 chapters" to the split below.
            regex.append(Regex.escape(key.substring(last, blank.range.first))).append("((?:(?! · ).)+?)")
            last = blank.range.last + 1
        }
        regex.append(Regex.escape(key.substring(last)))
        return Pattern(Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL), translation, fixed.length)
    }

    /** A language's name in the app language ("Deutsch" → "Allemand" in French), or [english] when there's none. */
    fun languageName(code: String, english: String): String {
        if (language == "en") return english
        val shown = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.forLanguageTag(language))
        return shown.takeIf { it.isNotBlank() && !it.equals(code, true) }?.replaceFirstChar { it.titlecase(java.util.Locale.forLanguageTag(language)) } ?: english
    }

    private val BLANK = Regex("\\{(\\d)\\}")
}

/** [text] in the app language: see [Translations]. */
fun tr(text: String): String = Translations.translate(text)
fun tr(text: String?): String? = text?.let(Translations::translate)

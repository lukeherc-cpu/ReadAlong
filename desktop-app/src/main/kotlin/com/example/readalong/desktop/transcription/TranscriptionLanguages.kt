package com.example.readalong.desktop.transcription

import java.util.Locale

/** A language Whisper can be told to expect. [code] is whisper.cpp's code ("auto" detects it). */
data class TranscriptionLanguage(val code: String, val name: String) {
    /** The language's name in itself ("Deutsch"), when Java knows it and it differs from [name]. */
    val nativeName: String? by lazy {
        if (code == TranscriptionLanguages.AUTO) return@lazy null
        val tag = TranscriptionLanguages.isoCode(code)
        runCatching { Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } } }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != tag && !it.equals(name, ignoreCase = true) }
    }
}

/**
 * Every language Whisper transcribes, as whisper.cpp names them (g_lang in whisper.cpp). The seven that Cactus Whistle,
 * the phone's speech model, also knows are [whistle]. English-only models handle English alone, and Cantonese needs
 * Large v3 Turbo (see [SpeechModels.supports]).
 */
object TranscriptionLanguages {
    const val AUTO = "auto"
    val detect = TranscriptionLanguage(AUTO, "Detect automatically")
    val whistle = setOf("en", "de", "fr", "es", "it", "nl", "pl")
    val whisper: List<TranscriptionLanguage> = listOf(
        "en" to "English", "zh" to "Chinese", "de" to "German", "es" to "Spanish", "ru" to "Russian", "ko" to "Korean",
        "fr" to "French", "ja" to "Japanese", "pt" to "Portuguese", "tr" to "Turkish", "pl" to "Polish", "ca" to "Catalan",
        "nl" to "Dutch", "ar" to "Arabic", "sv" to "Swedish", "it" to "Italian", "id" to "Indonesian", "hi" to "Hindi",
        "fi" to "Finnish", "vi" to "Vietnamese", "he" to "Hebrew", "uk" to "Ukrainian", "el" to "Greek", "ms" to "Malay",
        "cs" to "Czech", "ro" to "Romanian", "da" to "Danish", "hu" to "Hungarian", "ta" to "Tamil", "no" to "Norwegian",
        "th" to "Thai", "ur" to "Urdu", "hr" to "Croatian", "bg" to "Bulgarian", "lt" to "Lithuanian", "la" to "Latin",
        "mi" to "Maori", "ml" to "Malayalam", "cy" to "Welsh", "sk" to "Slovak", "te" to "Telugu", "fa" to "Persian",
        "lv" to "Latvian", "bn" to "Bengali", "sr" to "Serbian", "az" to "Azerbaijani", "sl" to "Slovenian", "kn" to "Kannada",
        "et" to "Estonian", "mk" to "Macedonian", "br" to "Breton", "eu" to "Basque", "is" to "Icelandic", "hy" to "Armenian",
        "ne" to "Nepali", "mn" to "Mongolian", "bs" to "Bosnian", "kk" to "Kazakh", "sq" to "Albanian", "sw" to "Swahili",
        "gl" to "Galician", "mr" to "Marathi", "pa" to "Punjabi", "si" to "Sinhala", "km" to "Khmer", "sn" to "Shona",
        "yo" to "Yoruba", "so" to "Somali", "af" to "Afrikaans", "oc" to "Occitan", "ka" to "Georgian", "be" to "Belarusian",
        "tg" to "Tajik", "sd" to "Sindhi", "gu" to "Gujarati", "am" to "Amharic", "yi" to "Yiddish", "lo" to "Lao",
        "uz" to "Uzbek", "fo" to "Faroese", "ht" to "Haitian Creole", "ps" to "Pashto", "tk" to "Turkmen", "nn" to "Nynorsk",
        "mt" to "Maltese", "sa" to "Sanskrit", "lb" to "Luxembourgish", "my" to "Burmese", "bo" to "Tibetan", "tl" to "Tagalog",
        "mg" to "Malagasy", "as" to "Assamese", "tt" to "Tatar", "haw" to "Hawaiian", "ln" to "Lingala", "ha" to "Hausa",
        "ba" to "Bashkir", "jw" to "Javanese", "su" to "Sundanese", "yue" to "Cantonese",
    ).map { (code, name) -> TranscriptionLanguage(code, name) }.sortedBy { it.name }

    /** Detection first, then every Whisper language from A to Z. */
    val all = listOf(detect) + whisper
    fun find(code: String?) = all.firstOrNull { it.code == code }
    fun name(code: String) = find(code)?.name ?: code
    /** The ISO 639 code for [code]: whisper.cpp says "jw" for Javanese. */
    fun isoCode(code: String) = if (code == "jw") "jv" else code
}

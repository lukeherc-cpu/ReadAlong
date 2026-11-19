package com.example.readalong.domain.transcription

import java.util.Locale

/** A language the speech model can be told to expect. [code] is what the engine takes ("auto" detects it). */
data class TranscriptionLanguage(val code: String, val name: String) {
    /** The language's name in itself ("Deutsch"), when that differs from [name]. */
    val nativeName: String? get() = if (code == TranscriptionLanguages.AUTO) null else runCatching {
        val locale = Locale.forLanguageTag(code)
        locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }.getOrNull()?.takeIf { it.isNotBlank() && it != code && !it.equals(name, ignoreCase = true) }
}

/**
 * The languages the phone's speech model, Cactus Whistle, transcribes: English, German, French, Spanish, Italian,
 * Dutch and Polish (third_party/needle/README.md). Whistle detects the language by itself unless it's told one.
 * Books in other languages can be transcribed by ReadAlong on a computer, whose Whisper models know 99 more.
 */
object TranscriptionLanguages {
    const val AUTO = "auto"
    val detect = TranscriptionLanguage(AUTO, "Detect automatically")
    val whistle = listOf(
        TranscriptionLanguage("en", "English"), TranscriptionLanguage("de", "German"), TranscriptionLanguage("fr", "French"),
        TranscriptionLanguage("es", "Spanish"), TranscriptionLanguage("it", "Italian"), TranscriptionLanguage("nl", "Dutch"),
        TranscriptionLanguage("pl", "Polish"),
    )
    val all = listOf(detect) + whistle
    fun valid(code: String?) = all.any { it.code == code }
    fun name(code: String) = all.firstOrNull { it.code == code }?.name ?: code
    /** The code to hand Whistle: null to detect. */
    fun forEngine(code: String?): String? = code?.takeIf { it != AUTO && whistle.any { language -> language.code == it } }
}

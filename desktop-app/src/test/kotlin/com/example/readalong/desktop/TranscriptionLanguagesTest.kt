package com.example.readalong.desktop

import com.example.readalong.desktop.transcription.SpeechModels
import com.example.readalong.desktop.transcription.TranscriptionLanguages
import kotlin.test.*

/** Settings → Languages on the computer: every Whisper language, and only models that can transcribe the chosen one. */
class TranscriptionLanguagesTest {
    @Test fun listsEveryWhisperLanguageOnce() {
        val codes = TranscriptionLanguages.whisper.map { it.code }
        assertEquals(100, codes.size) // whisper.cpp's 99, plus Cantonese from Large v3.
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(TranscriptionLanguages.AUTO, TranscriptionLanguages.all.first().code)
        assertTrue(TranscriptionLanguages.whistle.all { code -> codes.contains(code) })
    }

    @Test fun englishOnlyModelsAreLeftForOtherLanguages() {
        val tinyEn = SpeechModels.find("tiny.en")!!
        assertTrue(SpeechModels.supports(tinyEn, "en"))
        assertFalse(SpeechModels.supports(tinyEn, "de"))
        assertFalse(SpeechModels.supports(tinyEn, "auto"))
        assertTrue(SpeechModels.supports(SpeechModels.find("tiny")!!, "ja"))
        assertFalse(SpeechModels.supports(SpeechModels.find("tiny")!!, "yue"))
        assertTrue(SpeechModels.supports(SpeechModels.default("yue"), "yue"))
        assertTrue(SpeechModels.supports(SpeechModels.default("de"), "de"))
    }

    @Test fun javaneseUsesItsIsoCodeForItsOwnName() {
        assertEquals("jv", TranscriptionLanguages.isoCode("jw"))
        assertEquals("Deutsch", TranscriptionLanguages.find("de")!!.nativeName)
    }
}

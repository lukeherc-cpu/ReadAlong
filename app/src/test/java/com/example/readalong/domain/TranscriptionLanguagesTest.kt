package com.example.readalong.domain

import com.example.readalong.domain.transcription.TranscriptionLanguages
import org.junit.Assert.*
import org.junit.Test

/** Settings → Languages on the phone: only Whistle's languages reach the engine, and "auto" means detection. */
class TranscriptionLanguagesTest {
    @Test fun offersDetectionAndWhistlesSevenLanguages() {
        assertEquals(TranscriptionLanguages.AUTO, TranscriptionLanguages.all.first().code)
        assertEquals(setOf("en", "de", "fr", "es", "it", "nl", "pl"), TranscriptionLanguages.whistle.map { it.code }.toSet())
    }
    @Test fun engineGetsACodeOrNullForDetection() {
        assertNull(TranscriptionLanguages.forEngine("auto"))
        assertNull(TranscriptionLanguages.forEngine(null))
        assertNull(TranscriptionLanguages.forEngine("ja")) // Not one Whistle knows: detect rather than force it.
        assertEquals("de", TranscriptionLanguages.forEngine("de"))
    }
    @Test fun validatesSavedValues() {
        assertTrue(TranscriptionLanguages.valid("auto"))
        assertTrue(TranscriptionLanguages.valid("pl"))
        assertFalse(TranscriptionLanguages.valid("xx"))
    }
}

package com.example.readalong.data

import android.content.ContextWrapper
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.SpeechModelStore
import com.example.readalong.data.preferences.ReaderPreferencesStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreferencesRecoveryTest {
    @Test fun corruptedModelSettingsRecoverWithoutDeletingBooksOrInstalledStorage() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val root = File(app.cacheDir, "recovery-${System.nanoTime()}").apply { mkdirs() }
        val book = File(root, "readalong.db").apply { writeText("book sentinel") }
        val file = File(root, "speech.preferences_pb").apply { writeBytes(byteArrayOf(0x0a, 0x7f, 0x01)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(booleanPreferencesKey("recover_models") to true) }, produceFile = { file })
        val store = SpeechModelStore(object : ContextWrapper(app) { override fun getFilesDir() = root }, data)
        try {
            assertEquals(SpeechModelStore.ID, store.selectedModel().id)
            assertTrue(store.status.first().message!!.contains("recovered"))
            assertEquals("book sentinel", book.readText())
            assertNotEquals(true, data.data.first()[booleanPreferencesKey("recover_models")])
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun invalidCachedAppearanceValuesAreFiniteAndReadable() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val file = File(app.cacheDir, "appearance-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            data.edit { it[floatPreferencesKey("font_size")] = Float.NaN; it[floatPreferencesKey("line_spacing")] = Float.POSITIVE_INFINITY; it[floatPreferencesKey("margin")] = -100f }
            val p = ReaderPreferencesStore(app, data).preferences.first()
            assertEquals(20f, p.fontSize, 0f); assertEquals(1.6f, p.lineSpacing, 0f); assertEquals(12f, p.margin, 0f)
        } finally { scope.cancel(); file.delete() }
    }
}

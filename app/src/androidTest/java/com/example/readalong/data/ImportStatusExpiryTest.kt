package com.example.readalong.data

import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.ui.importing.ImportViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImportStatusExpiryTest {
    @Test fun updateExpiresAfterFiveSecondsAndANewUpdateRestartsTheTimer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ViewModelProvider(store, ImportViewModel.factory(app.audioImporter, app.importedEpub, app.processing))[ImportViewModel::class.java] }
        try {
            withContext(Dispatchers.Main) { model.files(emptyList()) }
            withTimeout(5000) { while (model.busy.value || model.message.value == null) delay(20) }
            delay(3000)
            withContext(Dispatchers.Main) { model.files(emptyList()) }
            delay(3000)
            assertNotNull("An older expiry must not clear a newer message", model.message.value)
            withTimeout(3000) { while (model.message.value != null) delay(20) }
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }
}

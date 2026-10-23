package com.example.readalong

import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.example.readalong.data.transcription.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadPresentationTest {
    @Test fun repeatedAutomaticRequestsKeepTheInstalledModelAndDoNotReplaceIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        org.junit.Assume.assumeTrue(app.speechModel.available())
        val before = app.speechModel.status.first()
        repeat(3) { AutomaticModelDownload.ensure(app, app.speechModel) }
        assertTrue(app.speechModel.available())
        assertEquals(before, app.speechModel.status.first())
        assertEquals(NetworkType.CONNECTED, ModelInstallWorker.request(automatic = true).workSpec.constraints.requiredNetworkType)
    }
}
package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.*
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FailureRangeTest {
    @Test fun actualLongM4bTranscribesIntervalsAroundReportedThreeToFourPercentFailure() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateFailureRange") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = File(File(app.filesDir, "recognition").apply { mkdirs() }, "failure-range.m4b")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-mystic.m4b")).use { input -> audio.outputStream().use { input.copyTo(it) } }
            val uri = android.net.Uri.fromFile(audio).toString()
            val report = mutableListOf<String>()
            RecognitionConnection(app).withSession { connection ->
                val engine = WhistleTranscriptionEngine(app, app.speechModel, SpeechModels.whistle.id, connection)
                for (second in listOf(1500L, 1650L, 1800L, 2100L, 2250L, 2400L)) {
                    val from = second * 1000
                    val segments = engine.transcribe(TranscriptionInput("failure-check", "chapter", uri, from, from + 30_000, false, from)).toList()
                    assertTrue("Narration at $second s should reach recognition", segments.isNotEmpty())
                    assertTrue(segments.all { it.startMs >= from && it.endMs <= from + 30_000 })
                    assertTrue(segments.flatMap { it.words }.all { it.startMs >= from && it.endMs <= from + 30_000 })
                    report += "$second s: ${segments.size} segments, ${segments.sumOf { it.words.size }} words"
                }
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("private_failure_range", report.joinToString("; ")) })
        } finally { audio.delete() }
    }
}

package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.transcription.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.*

class DesktopTranscriptImportTest {
    @Test fun actualWhisperTinyGpuPackageImportsWithAudioTimingsAndEngineProvenance() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "gpu-import-${System.nanoTime()}"
        val root = File(app.cacheDir, "exports").apply { mkdirs() }
        val wave = File(root, "$id.wav"); val pack = File(root, "$id.readalong")
        val processing = ProcessingRepository(app, db, app.speechModel, launchPreparation = { error("Desktop import must not launch recognition") })
        fun copy(source: String, target: File) = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/$source")).use { input -> target.outputStream().use { input.copyTo(it) } }
        fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        try {
            copy("gpu-source.wav", wave); copy("desktop-gpu.readalong", pack)
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id=id,isDemo=false,durationMs=6000)))
            db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, uri(wave).toString(), "GPU source", 0, 6000, null)))
            processing.imported(id)
            DesktopTranscriptImporter(app, db, processing, com.example.readalong.data.epub.GeneratedEpubRepository(app, db)).import(id, uri(pack))
            assertEquals("READY", db.processingDao().find(id)!!.state)
            assertEquals("whisper-tiny-v1", db.bookDao().metadata("desktop_transcript_engine:$id"))
            assertTrue(db.readingDao().segments(id).isNotEmpty())
            assertTrue(db.readingDao().segments(id).all { it.startMs>=0 && it.endMs<=6000 && it.wordsJson!=null })
            assertTrue(db.processingDao().find(id)!!.message!!.contains("Whisper Tiny"))
        } finally { processing.cancel(id);db.close();wave.delete();pack.delete() }
    }

    @Test fun realDesktopWhistlePackageImportsWithoutRecognitionAndRejectsWrongOrIncompleteAudio() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "desktop-import-${System.nanoTime()}"
        val root = File(app.cacheDir, "exports").apply { mkdirs() }
        val wave = File(root, "$id.wav"); val pack = File(root, "$id.readalong"); val bad = File(root, "$id-bad.readalong")
        fun copy(source: String, target: File) = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/$source")).use { input -> target.outputStream().use { input.copyTo(it) } }
        val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = { error("Import must never launch recognition") })
        val epub = com.example.readalong.data.epub.GeneratedEpubRepository(app, db)
        val importer = DesktopTranscriptImporter(app, db, repo, epub)
        fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        fun corrupt(change: (JSONObject, String) -> Pair<JSONObject, String>, extra: Boolean = false) {
            ZipFile(pack).use { z ->
                val header = JSONObject(z.getInputStream(z.getEntry("manifest.json")).bufferedReader().readText())
                val data = z.getInputStream(z.getEntry("chunks.jsonl")).bufferedReader().readText()
                val (h, c) = change(header, data)
                ZipOutputStream(bad.outputStream()).use { out ->
                    for ((name, value) in listOf("manifest.json" to h.toString(), "chunks.jsonl" to c)) {
                        out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
                    }
                    if (extra) { out.putNextEntry(ZipEntry("../escape")); out.write(1); out.closeEntry() }
                }
            }
        }
        try {
            copy("readalong-concurrent-short.wav", wave); copy("desktop-1.readalong", pack)
            assertTrue(pack.length() > 100)
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 6000)))
            db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, uri(wave).toString(), "Original", 0, 6000, null)))
            repo.imported(id)
            importer.import(id, uri(pack))
            val rows = db.readingDao().segments(id)
            assertTrue(rows.isNotEmpty()); assertTrue(rows.all { it.startMs >= 0 && it.endMs <= 6000 && it.wordsJson != null })
            assertEquals("READY", db.processingDao().find(id)!!.state)
            assertEquals(1, db.processingDao().find(id)!!.completedUnits)
            assertNotNull(db.readingDao().findVersion(id)!!.generatedUri)
            val snapshot = rows.map { it.text to it.wordsJson }
            corrupt({ h, c -> h.getJSONArray("sources").getJSONObject(0).put("sha256", "0".repeat(64)); h to c })
            assertTrue(runCatching { importer.import(id, uri(bad)) }.isFailure)
            corrupt({ h, _ -> h to "" })
            assertTrue(runCatching { importer.import(id, uri(bad)) }.isFailure)
            corrupt({ h, c -> h to c }, extra = true)
            assertTrue(runCatching { importer.import(id, uri(bad)) }.isFailure)
            corrupt({ h, c -> val row = JSONObject(c.trim()); row.put("startMs", 1); h to (row.toString() + "\n") })
            assertTrue(runCatching { importer.import(id, uri(bad)) }.isFailure)
            corrupt({ h, c -> val row = JSONObject(c.trim()); row.getJSONObject("result").getJSONArray("words").getJSONObject(0).put("end", 999999); h to (row.toString() + "\n") })
            assertTrue(runCatching { importer.import(id, uri(bad)) }.isFailure)
            assertEquals(snapshot, db.readingDao().segments(id).map { it.text to it.wordsJson })
            assertEquals("READY", db.processingDao().find(id)!!.state)
        } finally { repo.cancel(id); db.close(); wave.delete(); pack.delete(); bad.delete() }
    }
}

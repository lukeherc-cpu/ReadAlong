package com.example.readalong.data

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.epub.*
import com.example.readalong.data.local.*
import com.example.readalong.data.preferences.ReaderPreferencesStore
import com.example.readalong.data.processing.*
import com.example.readalong.data.reading.toEntity
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.data.transcription.SpeechModelStore
import com.example.readalong.ui.reading.ReadingViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportedEpubTest {
    private val first = "The gorilla waited quietly beside the river while three small boats crossed the water."
    private val second = "At sunrise the keeper opened the garden gate and carried a bright silver lantern."
    private fun epub(app: ReadAlongApplication): File {
        val file = File(app.cacheDir, "original-epub-${System.nanoTime()}.epub")
        val entries = mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to """<container><rootfile full-path="package.opf"/></container>""",
            "package.opf" to """<package><metadata><title>Original matching edition</title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to """<html><body><h1>At the river</h1><p>$first</p><p>♪ ♫ ♬</p><p><em>$second</em></p></body></html>""")
        ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return file
    }
    private suspend fun book(db: ReadAlongDatabase): String {
        val id = "epub-test-${System.nanoTime()}"
        db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 75_000)))
        db.bookDao().insertProgress(listOf(BookProgressEntity(id)))
        db.bookDao().updateListeningPosition(id, 42_123, 0)
        db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original-test/audio", "Original audio", 0, 75_000, null)))
        db.readingDao().putVersion(ReadableVersionEntity(id, "PARTIAL_READY", null, 0, 1, null, 0, transcriptRevision = 1))
        db.readingDao().putSegments(listOf(TranscriptEntity("$id-s1", id, "$id-file", 1000, 8000, first.replace("gorilla", "guerilla"), null, null)))
        return id
    }
    @Test fun presentationUpgradeKeepsManualTimingTranscriptAndReadingPositionAcrossRemovedFrontMatter() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val file = epub(app)
        try {
            val id = book(db); val repo = ImportedEpubRepository(app, db)
            repo.import(id, Uri.fromFile(file)); repo.align(id)
            val edition = db.importedEpubDao().find(id)!!
            repo.saveManual(repo.manualDraft(id, edition.editionId, 2, 9000), 9000, 11000)
            val rows = db.importedEpubDao().blocks(id, edition.editionId)
            val transcript = db.readingDao().segments(id)
            // Emulate a saved old parser layout with an extra front-matter block.
            db.importedEpubDao().clearBlocks(id)
            db.importedEpubDao().putBlocks(listOf(rows.first().copy(ordinal = 0, text = "Copyright. All rights reserved.")) + rows.map { it.copy(ordinal = it.ordinal + 1) })
            db.readingDao().putProgress(ReadingProgressEntity(id, "epub:${edition.editionId}:3", 9500, 1, edition.editionId, 3))
            db.bookDao().removeMetadata("epub_presentation:${edition.editionId}")
            repo.upgradeContents(id)
            val upgraded = db.importedEpubDao().blocks(id, edition.editionId)
            assertEquals(rows.map { it.text }, upgraded.map { it.text })
            assertEquals(rows.map { it.anchorsJson }, upgraded.map { it.anchorsJson })
            assertEquals(9000L, upgraded[2].manualStartMs)
            assertEquals(2, db.readingDao().progress(id)!!.epubOrdinal)
            assertEquals(9500L, db.readingDao().progress(id)!!.audioMs)
            assertEquals(42_123L, db.bookDao().progress(id)!!.audioPositionMs)
            assertEquals(transcript, db.readingDao().segments(id))
            repo.upgradeContents(id) // Idempotent on subsequent opens.
            assertEquals(upgraded, db.importedEpubDao().blocks(id, edition.editionId))
            repo.remove(id)
        } finally { file.delete(); db.close() }
    }

    @Test fun paragraphSplitTimingIsPersistedAndOldAlignmentUpgradesWithoutWhisper() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val file = epub(app)
        try {
            val id = book(db)
            db.readingDao().clearTranscript(id)
            val text = "$first $second"
            val words = text.split(' ').mapIndexed { i, w -> com.example.readalong.domain.model.TranscriptWord(w, 1000L + i * 500, 1400L + i * 500) }
            val raw = com.example.readalong.domain.model.TranscriptSegment("split", id, "$id-file", 1000, 20_000, text, words = words)
            db.readingDao().putSegments(listOf(raw.toEntity()))
            val repo = ImportedEpubRepository(app, db)
            repo.import(id, Uri.fromFile(file)); repo.align(id)
            val edition = db.importedEpubDao().find(id)!!
            val row = db.importedEpubDao().blocks(id, edition.editionId).first { it.text == second }
            val expected = words[first.split(' ').size].startMs
            assertEquals(expected, row.startMs)
            assertEquals(first.length + 1, row.toReadingSegment().anchors.single().sourceStart)
            assertEquals(row.ordinal, db.importedEpubDao().indexAt(id, expected + 100))
            db.bookDao().putMetadata(LibraryMetadataEntity("epub_alignment_version:$id", "${edition.editionId}:2"))
            db.importedEpubDao().putBlocks(listOf(row.copy(startMs = 1000)))
            repo.align(id) // Same transcript revision still upgrades the old alignment algorithm.
            assertEquals(expected, db.importedEpubDao().block(id, edition.editionId, row.ordinal)!!.startMs)
            assertEquals(1L, db.readingDao().findVersion(id)!!.transcriptRevision)
            assertEquals(42_123L, db.bookDao().progress(id)!!.audioPositionMs)
        } finally { file.delete(); db.close() }
    }

    @Test fun manualSyncOverridesAutomaticSurvivesRealignmentAndResetsWithoutChangingSources() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val name = "manual-sync-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(app, ReadAlongDatabase::class.java, name).build()
        val file = epub(app)
        try {
            val id = book(db)
            var repository = ImportedEpubRepository(app, db)
            repository.import(id, Uri.fromFile(file)); repository.align(id)
            val edition = db.importedEpubDao().find(id)!!
            val original = db.importedEpubDao().block(id, edition.editionId, 1)!!
            val raw = db.readingDao().segments(id)
            val draft = repository.manualDraft(id, edition.editionId, 2, 2_000)
            repository.saveManual(draft, 2_000, 6_000)
            assertEquals(2, db.importedEpubDao().indexAt(id, 3_000))
            assertEquals(-1L, db.importedEpubDao().block(id, edition.editionId, 1)!!.startMs)
            assertEquals(1, db.importedEpubDao().find(id)!!.manualCount)
            val manual = db.importedEpubDao().block(id, edition.editionId, 2)!!
            assertEquals("♪ ♫ ♬", manual.text)
            assertTrue(manual.anchorsJson.contains("MANUAL_ALIGNMENT"))
            assertEquals(2_000L, manual.toReadingSegment().anchors.single().startMs)
            val conflict = repository.manualDraft(id, edition.editionId, 3, 3_000)
            assertTrue(runCatching { repository.saveManual(conflict, 3_000, 7_000) }.isFailure)
            assertTrue(runCatching { repository.saveManual(draft, 5_000, 4_000) }.isFailure)
            assertTrue(runCatching { repository.saveManual(draft, 5_000, 80_000) }.isFailure)
            db.close(); db = Room.databaseBuilder(app, ReadAlongDatabase::class.java, name).build()
            repository = ImportedEpubRepository(app, db)
            db.readingDao().putVersion(db.readingDao().findVersion(id)!!.copy(transcriptRevision = 2))
            repository.align(id)
            assertEquals(2, db.importedEpubDao().indexAt(id, 3_000))
            assertEquals(2_000L, db.importedEpubDao().block(id, edition.editionId, 2)!!.manualStartMs)
            assertEquals(raw, db.readingDao().segments(id))
            assertEquals(42_123L, db.bookDao().progress(id)?.audioPositionMs)
            assertNull(db.processingDao().find(id))
            repository.saveManual(draft, 0, 0, reset = true)
            assertEquals(original.anchorsJson, db.importedEpubDao().block(id, edition.editionId, 1)!!.anchorsJson)
            assertEquals(1, db.importedEpubDao().indexAt(id, 3_000))
            assertEquals(-1L, db.importedEpubDao().block(id, edition.editionId, 2)!!.startMs)
            assertEquals(0, db.importedEpubDao().find(id)!!.manualCount)
            // An automatic paragraph may span a silent gap. Its broad start/end index must not hide a manual anchor inside that gap.
            val gapAnchors = """[{"id":"before","startMs":1000,"endMs":2000,"start":0,"end":10},{"id":"after","startMs":6000,"endMs":8000,"start":11,"end":20}]"""
            db.importedEpubDao().putBlocks(listOf(original.copy(anchorsJson = gapAnchors, automaticAnchorsJson = gapAnchors)))
            repository.saveManual(draft, 3_000, 5_000)
            assertEquals(2, db.importedEpubDao().indexAt(id, 4_000))
            assertEquals(1, db.importedEpubDao().indexAt(id, 1_500))
            repository.saveManual(draft, 20_000, 25_000)
            db.importedEpubDao().invalidateAudioMapping(id)
            assertEquals(0, db.importedEpubDao().find(id)!!.manualCount)
            assertTrue(db.importedEpubDao().blocks(id, edition.editionId).all { it.startMs == -1L && it.manualStartMs == -1L })
            assertTrue(runCatching { repository.saveManual(draft, 20_000, 25_000) }.isFailure)
            repository.import(id, Uri.fromFile(file))
            assertTrue(runCatching { repository.saveManual(draft, 20_000, 25_000) }.isFailure)
        } finally { db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); app.deleteDatabase(name); file.delete() }
    }

    @Test fun embeddedFontSurvivesImportReloadAndRemovalWhileDamageFallsBack() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        // Runtime system font keeps third-party font binaries out of repository fixtures.
        val systemFont = File("/system/fonts").listFiles()!!.first { it.extension == "ttf" && it.length() in 12..4L * 1024 * 1024 }
        val source = epub(app)
        val valid = File(app.cacheDir, "font-epub-${System.nanoTime()}.epub")
        val invalid = File(app.cacheDir, "bad-font-epub-${System.nanoTime()}.epub")
        fun withFont(target: File, bytes: ByteArray) {
            java.util.zip.ZipFile(source).use { original -> ZipOutputStream(target.outputStream()).use { z ->
                original.entries().asSequence().forEach { entry ->
                    z.putNextEntry(ZipEntry(entry.name))
                    val text = original.getInputStream(entry).use { it.readBytes() }
                    z.write(if (entry.name == "package.opf") String(text).replace("</manifest>", "<item id='css' href='book.css' media-type='text/css'/></manifest>").toByteArray() else text)
                    z.closeEntry()
                }
                mapOf("book.css" to "@font-face {font-family: Original; src:url('book.ttf')} body {font-family: Original}".toByteArray(), "book.ttf" to bytes).forEach { (name, content) ->
                    z.putNextEntry(ZipEntry(name)); z.write(content); z.closeEntry()
                }
            } }
        }
        withFont(valid, systemFont.readBytes()); withFont(invalid, "damaged font".toByteArray())
        try {
            val id = book(db); val repository = ImportedEpubRepository(app, db)
            repository.import(id, Uri.fromFile(valid))
            val edition = db.importedEpubDao().find(id)!!
            assertTrue(repository.fontFamilies(edition.editionId).containsKey("original"))
            assertTrue(ImportedEpubRepository(app, db).fontFamilies(edition.editionId).containsKey("original"))
            assertEquals("original", db.importedEpubDao().blocks(id, edition.editionId).first().toBlock().styles.first().fontFamily)
            repository.import(id, Uri.fromFile(invalid))
            assertFalse(File(app.filesDir, "imported-epubs/${edition.editionId}-fonts").exists())
            val replacement = db.importedEpubDao().find(id)!!
            assertTrue(repository.fontFamilies(replacement.editionId).isEmpty())
            assertTrue(db.importedEpubDao().blocks(id, replacement.editionId).any { "♪" in it.text })
            repository.remove(id)
            assertFalse(File(app.filesDir, "imported-epubs/${replacement.editionId}-fonts").exists())
        } finally { db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); source.delete(); valid.delete(); invalid.delete() }
    }
    @Test fun optionalEditionPreservesRawRecognitionGeneratedSnapshotAudioAndProgress() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val file = epub(app)
        try {
            val id = book(db)
            val generated = GeneratedEpubRepository(app, db)
            generated.generate(id)
            val saved = db.readingDao().findVersion(id)!!
            val repository = ImportedEpubRepository(app, db)
            repository.import(id, Uri.fromFile(file)); assertTrue(repository.align(id))
            val imported = db.importedEpubDao().find(id)!!
            assertTrue(imported.useForReading()); assertEquals("ALIGNED", imported.state)
            val blocks = db.importedEpubDao().blocks(id, imported.editionId)
            assertEquals(first, blocks[1].text); assertTrue(blocks[1].anchorsJson.contains("$id-s1")); assertEquals(-1L, blocks[2].startMs)
            assertTrue(db.readingDao().segments(id).single().text.contains("guerilla"))
            assertEquals(saved.generatedUri, db.readingDao().findVersion(id)?.generatedUri)
            assertEquals(saved.activeEditionId, db.readingDao().findVersion(id)?.activeEditionId)
            repository.preference(id, "GENERATED"); assertFalse(db.importedEpubDao().find(id)!!.useForReading())
            val privateFile = File(Uri.parse(imported.uri).path!!)
            repository.remove(id)
            assertNull(db.importedEpubDao().find(id)); assertFalse(privateFile.exists()); assertTrue(file.exists())
            assertEquals(1, db.readingDao().count(id)); assertEquals(saved.generatedUri, db.readingDao().findVersion(id)?.generatedUri)
            assertEquals(42_123L, db.bookDao().progress(id)?.audioPositionMs)
        } finally { db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); file.delete() }
    }
    @Test fun newTranscriptExtendsAlignmentWithoutRestartingSpeechOrReplacingEpubText() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>(); val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build(); val file = epub(app)
        try {
            val id = book(db); val repository = ImportedEpubRepository(app, db)
            repository.import(id, Uri.fromFile(file)); repository.align(id)
            val before = db.importedEpubDao().find(id)!!
            assertEquals(-1L, db.importedEpubDao().blocks(id, before.editionId).last().startMs)
            val mistaken = "The engine overheated during the unexpected journey beyond the distant desert mountains."
            db.readingDao().putSegments(listOf(TranscriptEntity("$id-s2", id, "$id-file", 60_000, 68_000, mistaken, null, null)))
            db.readingDao().putVersion(db.readingDao().findVersion(id)!!.copy(transcriptRevision = 2))
            assertTrue(repository.align(id))
            assertEquals(1, db.importedEpubDao().find(id)?.matchedSegments)
            // Explicit corrections can improve alignment while retaining raw recognition and its timestamps.
            db.readingDao().putCorrection(TranscriptCorrectionEntity(id, "$id-s2", mistaken, second, 1))
            db.readingDao().putVersion(db.readingDao().findVersion(id)!!.copy(transcriptRevision = 3))
            assertTrue(repository.align(id))
            val after = db.importedEpubDao().find(id)!!
            assertEquals(before.editionId, after.editionId); assertEquals(3L, after.alignedRevision); assertEquals(2, after.matchedSegments)
            assertEquals(mistaken, db.readingDao().segment("$id-s2")?.text)
            assertEquals(60_000L, db.importedEpubDao().blocks(id, after.editionId).last().startMs)
            assertNull(db.processingDao().find(id)); assertEquals(42_123L, db.bookDao().progress(id)?.audioPositionMs)
        } finally { db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); file.delete() }
    }
    @Test fun malformedReplacementKeepsPreviouslySelectedEpubAndGeneratedText() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>(); val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build(); val file = epub(app)
        val bad = File(app.cacheDir, "invalid-epub-${System.nanoTime()}").apply { writeText("invalid archive") }
        try {
            val id = book(db); val repository = ImportedEpubRepository(app, db)
            repository.import(id, Uri.fromFile(file)); repository.align(id)
            val before = db.importedEpubDao().find(id)!!
            assertTrue(runCatching { repository.import(id, Uri.fromFile(bad)) }.isFailure)
            assertEquals(before, db.importedEpubDao().find(id)); assertTrue(File(Uri.parse(before.uri).path!!).exists()); assertEquals(1, db.readingDao().count(id))
        } finally { db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); file.delete(); bad.delete() }
    }
    @Test fun openingCompletedBookRepeatedlyAndAddingEpubNeverQueuesWhisperAgain() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>(); val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build(); val file = epub(app)
        try {
            val id = book(db); val processing = ProcessingRepository(app, db, app.speechModel)
            val plan = TranscriptionPlan.encode(TranscriptionPlan.inputs(db, id))
            val finished = ProcessingJobEntity(id, java.util.UUID.randomUUID().toString(), SpeechModelStore.ID, "READY", 3, 3, "Whole book ready", 123,
                plan, TranscriptionPlan.fingerprint(db, id))
            db.processingDao().put(finished)
            repeat(10) { processing.ensureStarted(id) }
            val repository = ImportedEpubRepository(app, db); repository.import(id, Uri.fromFile(file)); repository.align(id)
            repeat(10) { processing.ensureStarted(id) }
            assertEquals(finished, db.processingDao().find(id)); assertEquals(1, db.readingDao().count(id))
        } finally { db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); file.delete() }
    }

    @Test fun backgroundAlignmentWorkerUpdatesNewTranscriptRevisionWithoutSpeechJobs() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>(); val db = app.database; val file = epub(app)
        val id = book(db)
        try {
            app.importedEpub.import(id, Uri.fromFile(file))
            val firstEdition = withTimeout(60_000) { db.importedEpubDao().observe(id).first { it?.alignedRevision == 1L } }!!
            assertEquals(1, firstEdition.matchedSegments)
            db.readingDao().putSegments(listOf(TranscriptEntity("$id-s2", id, "$id-file", 60_000, 68_000, second, null, null)))
            db.readingDao().putVersion(db.readingDao().findVersion(id)!!.copy(transcriptRevision = 2))
            app.importedEpub.requestAlignment(id)
            val next = withTimeout(60_000) { db.importedEpubDao().observe(id).first { it?.alignedRevision == 2L } }!!
            assertEquals(firstEdition.editionId, next.editionId); assertEquals(2, next.matchedSegments)
            assertNull(db.processingDao().find(id)); assertEquals(42_123L, db.bookDao().progress(id)?.audioPositionMs)
        } finally { app.importedEpub.remove(id); db.bookDao().removeBook(id); file.delete() }
    }
    @Test fun quietReadingSavesUnmatchedEpubLocationWithoutInventingSeekTime() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>(); val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build(); val file = epub(app); val models = ViewModelStore()
        try {
            val id = book(db); val repository = ImportedEpubRepository(app, db); repository.import(id, Uri.fromFile(file)); repository.align(id)
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel), repository)
            models.put("reader", model); model.openReader(4000)
            val start = withTimeout(10_000) { model.readerStart.first { it?.imported == true } }!!
            assertEquals(1, start.index); assertEquals(4000L, model.readingAudio.value)
            val edition = db.importedEpubDao().find(id)!!
            val music = db.importedEpubDao().block(id, edition.editionId, 2)!!.toReadingSegment()
            model.visible(music)
            assertFalse(model.readingHasAudio.value); assertEquals(4000L, model.readingAudio.value)
            withTimeout(5000) { while (db.readingDao().progress(id)?.epubOrdinal != 2) delay(20) }
            model.openReader(60_000, resume = true)
            val resumed = withTimeout(5000) { model.readerStart.first { it != null && it.generation > start.generation } }!!
            assertEquals(2, resumed.index); assertFalse(model.readingHasAudio.value); assertEquals(4000L, model.readingAudio.value)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.importedEpubDao().all().forEach { ImportedEpubRepository(app, db).remove(it.bookId) }; db.close(); file.delete() }
    }
}

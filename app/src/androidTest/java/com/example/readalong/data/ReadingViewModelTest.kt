package com.example.readalong.data

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.epub.toReadingSegment
import com.example.readalong.data.reading.toEntity
import com.example.readalong.data.local.*
import com.example.readalong.data.preferences.ReaderPreferencesStore
import com.example.readalong.data.processing.ProcessingRepository
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.ui.reading.ReadingViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class ReadingViewModelTest {
    @Test fun modeSwitchUsesVisibleChapterAndCharacterInsteadOfEitherSavedPosition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = "mode-position"
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            val blocks = (0..6).map { n -> ImportedEpubBlockEntity(id, "position-edition", n, n, "chapter-$n",
                "Chapter $n. " + "The visible reading line must survive switching modes. ".repeat(30),
                "P", "[]", false, "[]", if (n < 5) n * 60_000L else -1L, if (n < 5) (n + 1) * 60_000L else -1L, 1f) }
            db.importedEpubDao().put(ImportedEpubEntity(id, "position-edition", "", "Original", "ALIGNED", "", "EPUB", 0, 7, 5, 5, 7, 0))
            db.importedEpubDao().putBlocks(blocks)
            db.readingDao().putProgress(ReadingProgressEntity(id, "epub:position-edition:4", 240_000, 1, "position-edition", 4))
            db.bookDao().putMetadata(LibraryMetadataEntity("book_viewport:$id",
                """{"edition":"position-edition","index":4,"id":"epub:position-edition:4","character":900}"""))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            model.openReader(60_000)
            val first = withTimeout(5000) { model.readerStart.first { it != null } }!!
            assertEquals(1, first.index)
            model.setReadOnly(true, 60_000, com.example.readalong.ui.reading.ReaderViewport(2, 220, 7))
            val plain = withTimeout(5000) { model.readerStart.first { it?.bookMode == true } }!!
            assertEquals("Old plain-reading bookmark must not win", 2, plain.index)
            assertEquals(220, plain.characterOffset)
            assertEquals(7, plain.lineOffsetPx)
            assertEquals(first.pagerGeneration, plain.pagerGeneration)
            // Reading beyond the transcribed part cannot be represented by an audio timestamp.
            // It must still stay on the exact EPUB passage when switching to ReadAlong.
            db.importedEpubDao().put(db.importedEpubDao().find(id)!!.copy(preference = "GENERATED"))
            model.setReadOnly(false, 60_000, com.example.readalong.ui.reading.ReaderViewport(5, 540, 3))
            val follow = withTimeout(5000) { model.readerStart.first { it != null && !it.bookMode && it.generation > plain.generation } }!!
            assertTrue(follow.imported)
            assertEquals("position-edition", follow.editionId)
            assertEquals(5, follow.index)
            assertEquals(540, follow.characterOffset)
            assertEquals(3, follow.lineOffsetPx)
            assertEquals(first.pagerGeneration, follow.pagerGeneration)
            assertNull(model.error.value)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }

    @Test fun generatedTextAlsoKeepsItsViewportWhenSwitchingToReadOnly() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            model.openReader(1000)
            val first = withTimeout(10_000) { model.readerStart.first { it?.generated == true } }!!
            model.setReadOnly(true, 1000, com.example.readalong.ui.reading.ReaderViewport(2, 25, 4))
            val plain = withTimeout(5000) { model.readerStart.first { it?.bookMode == true } }!!
            assertTrue(plain.generated)
            assertFalse(plain.imported)
            assertEquals(2, plain.index)
            assertEquals(25, plain.characterOffset)
            assertEquals(4, plain.lineOffsetPx)
            assertEquals(first.pagerGeneration, plain.pagerGeneration)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }

    @Test fun switchingReadingModeKeepsTheSameOriginalEpubPagerMounted() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            db.importedEpubDao().put(ImportedEpubEntity(id, "stable-mode", "", "Original", "ALIGNED", "", "EPUB", 0, 1, 1, 1, 1, 0))
            db.importedEpubDao().putBlocks(listOf(ImportedEpubBlockEntity(id, "stable-mode", 0, 0, "one", "Original text remains visible.", "P", "[]", false, "[]", 0, 10_000, 1f)))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            val emissions = java.util.concurrent.atomic.AtomicInteger()
            val collecting = scope.launch { model.readerPages.collect { emissions.incrementAndGet() } }
            model.openReader(1000)
            withTimeout(5000) { model.readerStart.first { it?.imported == true } }
            withTimeout(5000) { while (emissions.get() == 0) delay(20) }
            val first = model.readerStart.value!!.generation
            model.setReadOnly(true, 1000)
            withTimeout(5000) { model.readerStart.first { it != null && it.generation > first && it.bookMode } }
            val second = model.readerStart.value!!.generation
            model.setReadOnly(false, 1000)
            withTimeout(5000) { model.readerStart.first { it != null && it.generation > second && !it.bookMode } }
            delay(100)
            assertEquals("Mode changes must not clear/recreate the displayed original EPUB pager", 1, emissions.get())
            collecting.cancelAndJoin()
        } finally { scope.cancel(); withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }

    @Test fun onlyPreviouslyPreparedAudioWithDetectedSeekDriftRequestsTimingRepair() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val uri = "content://timing-test/${System.nanoTime()}"
            db.mediaDao().putAudio(listOf(AudioFileEntity("timing-file", id, uri, "VBR MP3", 0, 90000, null)))
            val job = ProcessingJobEntity(id, "old", com.example.readalong.data.transcription.SpeechModelStore.ID, "READY", 3, 3, null, 0)
            db.processingDao().put(job)
            com.example.readalong.data.audio.Mp3Timing.corrected(android.net.Uri.parse(uri), 2_000_000)
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            withTimeout(5000) { model.needsAudioTimingRepair.first { it } }
            db.bookDao().putMetadata(LibraryMetadataEntity(com.example.readalong.data.audio.Mp3Timing.versionKey(id), com.example.readalong.data.audio.Mp3Timing.VERSION))
            db.processingDao().put(job.copy(updatedAt = 1))
            withTimeout(5000) { model.needsAudioTimingRepair.first { !it } }
            assertFalse(model.needsAudioTimingRepair.value)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }
    @Test fun longPublisherSentenceLoadsItsOpeningWordsBeyondTheCurrentAudioWindow() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val sources = (0..100).map { i ->
                val text = "word${i * 3} word${i * 3 + 1} word${i * 3 + 2}" + if (i == 100) "." else ""
                com.example.readalong.domain.model.TranscriptSegment("long-$i", id, "one", 1000L + i * 2000, 2900L + i * 2000, text,
                    words = text.split(' ').mapIndexed { j, word -> com.example.readalong.domain.model.TranscriptWord(word, 1000L + i * 2000 + j * 600, 1500L + i * 2000 + j * 600) })
            }
            db.readingDao().putSegments(sources.map { it.toEntity() })
            var offset = 0
            val anchors = org.json.JSONArray()
            for (source in sources) {
                anchors.put(org.json.JSONObject().put("id", source.id).put("start", offset).put("end", offset + source.text.length)
                    .put("sourceStart", 0).put("sourceEnd", source.text.length).put("startMs", source.startMs).put("endMs", source.endMs))
                offset += source.text.length + 1
            }
            val text = sources.joinToString(" ") { it.text }
            db.importedEpubDao().put(ImportedEpubEntity(id, "long", "", "Original", "ALIGNED", "", "EPUB", 0, 1, 1, 101, 101, 0))
            db.importedEpubDao().putBlocks(listOf(ImportedEpubBlockEntity(id, "long", 0, 0, "one", text, "P", "[]", false,
                anchors.toString(), 1000, 203000, 1f)))
            assertFalse(TranscriptRepository(db).window(id, 199000).first().any { it.id == "long-0" })
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db),
                ProcessingRepository(app, db, app.speechModel), com.example.readalong.data.epub.ImportedEpubRepository(app, db))
            models.put("reader", model); model.follow(199000)
            val passages = withTimeout(5000) { model.passages.first { it.any { p -> p.imported } && it.any { p -> p.id == "long-0" } } }
            val cue = com.example.readalong.domain.reading.sentenceCues(passages.first { it.imported }, passages.filter { !it.imported }.associateBy { it.id }).single()
            assertEquals(1000L, cue.audioStartMs)
            assertTrue(cue.activeAt(199000))
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }
    @Test fun contentsAfterSearchStartsAtChapterBeginningAndOrdersNavigationBeforeMatchingAudio() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val text = "Opening sentence. A later search passage."
            val blocks = (0..3).map { index -> ImportedEpubBlockEntity(id, "contents-test", index, 0, "story",
                text, "P", "[]", false, "[]", index * 10_000L, index * 10_000L + 5000, 1f) }
            db.importedEpubDao().put(ImportedEpubEntity(id, "contents-test", "", "Original", "ALIGNED", "", "EPUB", 0, 4, 4, 4, 4, 0))
            db.importedEpubDao().putBlocks(blocks)
            // Publisher navigation may be ordered differently to the spine or repeat a locator.
            db.bookDao().putMetadata(LibraryMetadataEntity("epub_contents:contents-test",
                """[{"ordinal":2,"title":"Later chapter"},{"ordinal":0,"title":"Opening chapter"},{"ordinal":2,"title":"Duplicate"}]"""))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            model.openReader(0)
            withTimeout(10_000) { model.readerStart.first { it?.imported == true } }
            val chapters = withTimeout(5000) { model.contents.first { it.size == 2 } }
            assertEquals(listOf("Opening chapter", "Later chapter"), chapters.map { it.title })
            assertEquals(listOf(0L, 20_000L), chapters.map { it.startMs })
            assertTrue(model.selectSearch(com.example.readalong.ui.reading.ReadingSearchResult(
                blocks[0].toReadingSegment(), 0, text.indexOf("later"), text.length, text, false, "contents-test")))
            assertTrue(model.readerStart.value!!.characterOffset > 0)
            val generation = model.readerStart.value!!.generation
            model.openEpubSection(2)
            val target = withTimeout(5000) { model.readerStart.first { it!!.generation > generation } }!!
            assertEquals(2, target.index)
            assertEquals("Contents must not reuse the previous search's character offset", 0, target.characterOffset)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }

    @Test fun missingSentenceTimingNoticeExpiresWithoutAnotherUserAction() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val block = ImportedEpubBlockEntity(id, "untimed", 0, 0, "one", "Still waiting for audio.", "P", "[]", false, "[]", -1, -1, 0f)
            db.importedEpubDao().put(ImportedEpubEntity(id, "untimed", "", "Original", "ALIGNED", "", "EPUB", 0, 1, 0, 0, 0, 0))
            db.importedEpubDao().putBlocks(listOf(block))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            assertNull(model.sentenceTarget(block.toReadingSegment(), 0))
            assertTrue(model.error.value!!.contains("No earlier sentence"))
            withTimeout(6500) { model.error.first { it == null } }
            assertNull(model.error.value)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }

    @Test fun missingEpubPromptCanBeDismissedForEveryBookWithoutStartingTranscription() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val ids = RoomBookRepository(db, seedSamples = true).observeBooks().first().map { it.id }
            fun reader(id: String) = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            val first = reader(ids[0]); models.put("first", first)
            first.checkMissingEpub(false)
            delay(300)
            assertFalse("A skipped Create ReadAlong choice must not trigger the EPUB prompt", first.missingEpub.value)
            db.processingDao().put(ProcessingJobEntity(ids[0], java.util.UUID.randomUUID().toString(), "whistle", "CANCELLED", 0, 0, null, 0))
            first.checkMissingEpub(false)
            withTimeout(5000) { first.missingEpub.first { it } }
            assertEquals("CANCELLED", db.processingDao().find(ids[0])!!.state)
            first.dismissMissingEpub(true)
            withTimeout(5000) { while (db.bookDao().metadata("hide_missing_epub") != "true") delay(50) }
            val second = reader(ids[1]); models.put("second", second)
            second.checkMissingEpub(false); delay(300)
            assertFalse(second.missingEpub.value)
            assertNull(db.processingDao().find(ids[1]))
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }
    @Test fun sentenceTapLoadsDistantWordTimesAndUnmatchedEpubFallsBackToEarlierSentence() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val text = "The gate opened. Then it closed."
            val source = com.example.readalong.domain.model.TranscriptSegment("distant", id, "chapter", 900_000, 920_000, text,
                words = listOf("The", "gate", "opened.", "Then", "it", "closed.").mapIndexed { i, word ->
                    com.example.readalong.domain.model.TranscriptWord(word, 902_000L + i * 1000, 902_800L + i * 1000)
                })
            db.readingDao().putSegments(listOf(source.toEntity()))
            val anchor = org.json.JSONArray().put(org.json.JSONObject().put("id", source.id).put("start", 0).put("end", text.length).put("startMs", source.startMs).put("endMs", source.endMs)).toString()
            val matched = ImportedEpubBlockEntity(id, "original", 0, 0, "one", text, "P", "[]", false, anchor, 900_000, 920_000, 1f)
            val unmatched = matched.copy(ordinal = 1, text = "Music and unrecognized words.", anchorsJson = "[]", startMs = -1, endMs = -1)
            db.importedEpubDao().put(ImportedEpubEntity(id, "original", "", "Original", "ALIGNED", "", "AUTO", 0, 1, 1, 1, 1, 0))
            db.importedEpubDao().putBlocks(listOf(matched, unmatched))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            assertEquals(904_840L, model.sentenceTarget(matched.toReadingSegment(), text.indexOf("closed")))
            assertEquals(904_840L, model.sentenceTarget(unmatched.toReadingSegment(), 8))
            model.openReader(903_000)
            withTimeout(10_000) { model.readerStart.first { it != null } }
            assertEquals(905_000L, model.adjacentSentence(903_000, 1))
            assertEquals(902_000L, model.adjacentSentence(906_000, -1))
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }
    @Test fun sentenceSearchCrossesDatabasePagesAndRejectsStaleRecognition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val rows = (0 until 202).map { index -> TranscriptEntity("search-$index", id, "chapter", index * 1000L, index * 1000L + 900,
                when(index) {199 -> "The traveler reached the old"; 200 -> "bridge at sunrise."; else -> "Original filler passage $index."}, null, null) }
            db.readingDao().putSegments(rows)
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app), GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            model.search("OLD BRIDGE at sunrise")
            val hit = withTimeout(10_000) { model.searchResults.first { it.isNotEmpty() } }.single()
            assertEquals(199, hit.index)
            assertEquals(199_000L, hit.audioMs)
            assertTrue(model.selectSearch(hit))
            assertEquals(199_000L, model.readingAudio.value)
            assertEquals(199, model.readerStart.value?.index)
            db.readingDao().putSegments(listOf(rows[199].copy(text = "Replacement recognition")))
            assertFalse(model.selectSearch(hit))
            assertEquals(199_000L, model.readingAudio.value)
            assertNotNull(model.searchError.value)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }
    @Test fun failedLegacyFormattingUpgradeStillOpensSavedEditionAndKeepsGapTime() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val dao = db.readingDao()
            // Malformed current word timing deliberately makes regeneration fail.
            dao.putSegments(listOf(TranscriptEntity("raw", id, "chapter", 100, 200, "Current text", null, "invalid JSON")))
            dao.putEdition(listOf(EpubParagraphEntity(id, "legacy", "old", "Saved readable words", "chapter.xhtml#old", "chapter", 100, 200)))
            dao.putVersion(ReadableVersionEntity(id, "READY", null, 1, 1, "content://saved/edition", 0, activeEditionId = "legacy"))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), ReaderPreferencesStore(app),
                GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
            models.put("reader", model)
            model.openReader(50_000)
            val start = withTimeout(10_000) { model.readerStart.first { it != null } }!!
            assertTrue(start.generated)
            assertEquals(50_000L, model.readingAudio.value)
            assertEquals(1, start.index)
            assertNotNull(model.error.value)
            assertEquals("legacy", dao.findVersion(id)?.activeEditionId)
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }
}

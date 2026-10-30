package com.example.readalong.desktop

import com.example.readalong.desktop.core.AudioPart
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.ProgressSync
import com.example.readalong.desktop.core.companionFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressSyncTest {
    private fun book(folder: File, lastPlayedAt: Long?, positionMs: Long = 0) = LibraryBook("b1", "Book", key = "folder:${folder.path}", folder = folder.path,
        parts = listOf(AudioPart(File(folder, "01.mp3").path, 600_000, 0, 1, 1), AudioPart(File(folder, "02.mp3").path, 600_000, 600_000, 1, 1)),
        positionMs = positionMs, lastPlayedAt = lastPlayedAt)

    private fun phoneFile(updatedAt: Long, file: String? = "02.mp3", fileIndex: Int = 1, durationMs: Long = 1_200_000) =
        JSONObject().put("format", "readalong-progress").put("version", 1).put("device", "Android").put("updatedAt", updatedAt)
            .put("positionMs", 700_000).put("durationMs", durationMs).put("finished", false).apply { if (file != null) put("file", file) }
            .put("fileIndex", fileIndex).put("filePositionMs", 90_000).put("somethingNew", true).toString()

    @Test fun newerPhonePositionIsTakenByFileName() {
        val folder = Files.createTempDirectory("Book").toFile()
        val library = LibraryStore(File(folder, "library.json"), CoroutineScope(Dispatchers.Unconfined))
        val local = book(folder, lastPlayedAt = 1_000_000)
        library.replaceAll { listOf(local) }
        local.companionFile(ProgressSync.EXTENSION).writeText(phoneFile(updatedAt = 2_000_000))
        assertTrue(ProgressSync(library).sync(local))
        val synced = library.book("b1")!!
        assertEquals(690_000, synced.positionMs)   // 02.mp3 starts at 10:00, plus 1:30 into it
        assertEquals(2_000_000, synced.lastPlayedAt)
        // Taking it doesn't bounce it back: nothing newer to write.
        assertFalse(ProgressSync(library).sync(synced))
    }

    @Test fun newerComputerPositionIsWrittenForThePhone() {
        val folder = Files.createTempDirectory("Book").toFile()
        val library = LibraryStore(File(folder, "library.json"), CoroutineScope(Dispatchers.Unconfined))
        val local = book(folder, lastPlayedAt = 5_000_000, positionMs = 650_000)
        library.replaceAll { listOf(local) }
        local.companionFile(ProgressSync.EXTENSION).writeText(phoneFile(updatedAt = 2_000_000))
        assertFalse(ProgressSync(library).sync(local))
        val written = JSONObject(File(folder, "${folder.name}.readalong-progress").readText())
        assertEquals("Windows", written.getString("device"))
        assertEquals(5_000_000, written.getLong("updatedAt"))
        assertEquals("02.mp3", written.getString("file"))
        assertEquals(1, written.getInt("fileIndex"))
        assertEquals(50_000, written.getLong("filePositionMs"))
        assertEquals(1_200_000, written.getLong("durationMs"))
    }

    @Test fun unknownFilesFallBackOnlyWhenTheBookMatches() {
        val folder = Files.createTempDirectory("Book").toFile()
        val local = book(folder, null)
        // Renamed files, same length: the file number places it.
        assertEquals(690_000, ProgressSync.resolve(local, ProgressSync.parse(phoneFile(1, file = "Chapter 2.mp3"))!!))
        // A different edition (lengths differ): nothing is taken.
        assertNull(ProgressSync.resolve(local, ProgressSync.parse(phoneFile(1, file = "Chapter 2.mp3", durationMs = 900_000))!!))
        // A later format version is skipped.
        assertNull(ProgressSync.parse(JSONObject(phoneFile(1)).put("version", 2).toString()))
    }
}

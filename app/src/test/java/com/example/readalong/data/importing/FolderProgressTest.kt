package com.example.readalong.data.importing

import com.example.readalong.data.local.AudioFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderProgressTest {
    private fun file(name: String, ordinal: Int, length: Long) = AudioFileEntity("f$ordinal", "book", "content://$name", name, ordinal, length, null)
    private val files = listOf(file("01.mp3", 0, 60_000), file("02.mp3", 1, 60_000), file("03.mp3", 2, 60_000))

    @Test fun roundTripsThroughJson() {
        val progress = FolderProgress(1_700_000_000_000, 90_000, 180_000, false, "02.mp3", 1, 30_000, "Windows", fileCount = 3)
        assertEquals(progress, FolderProgress.parse(progress.toJson()))
    }

    @Test fun rejectsOtherFormatsAndNewerVersions() {
        assertNull(FolderProgress.parse("""{"format":"readalong-transcript","version":1,"updatedAt":1,"positionMs":1}"""))
        assertNull(FolderProgress.parse("""{"format":"readalong-progress","version":2,"updatedAt":1,"positionMs":1}"""))
        assertNull(FolderProgress.parse("not json"))
    }

    @Test fun locatesByFileNameEvenWhenLengthsDiffer() {
        val progress = FolderProgress(1, 0, 999_999, false, "03.MP3", 7, 12_000)
        assertEquals(2 to 12_000L, progress.locate(files))
    }

    @Test fun fallsBackToFileNumberThenBookPositionWhenLengthsAgree() {
        assertEquals(1 to 5_000L, FolderProgress(1, 0, 181_000, false, "Part 2.mp3", 1, 5_000).locate(files))
        assertEquals(2 to 10_000L, FolderProgress(1, 130_000, 179_000, false, null, -1, 0).locate(files))
        // With a file count, the number is trusted even when the lengths differ, and refused when the counts differ.
        assertEquals(1 to 5_000L, FolderProgress(1, 0, 900_000, false, "Part 2.mp3", 1, 5_000, fileCount = 3).locate(files))
        assertNull(FolderProgress(1, 0, 900_000, false, "Part 2.mp3", 1, 5_000, fileCount = 4).locate(files))
    }

    @Test fun ignoresProgressForDifferentAudio() {
        assertNull(FolderProgress(1, 130_000, 400_000, false, "Other.mp3", 0, 5_000).locate(files))
    }

    @Test fun newerListeningWins() {
        val remote = FolderProgress(10_000, 0, 0, false, null, -1, 0)
        assertEquals(FolderProgress.Direction.TAKE, FolderProgress.direction(null, remote))
        assertEquals(FolderProgress.Direction.TAKE, FolderProgress.direction(5_000, remote))
        assertEquals(FolderProgress.Direction.NONE, FolderProgress.direction(9_000, remote))
        assertEquals(FolderProgress.Direction.WRITE, FolderProgress.direction(20_000, remote))
        assertEquals(FolderProgress.Direction.WRITE, FolderProgress.direction(20_000, null))
        assertEquals(FolderProgress.Direction.NONE, FolderProgress.direction(null, null))
    }
}

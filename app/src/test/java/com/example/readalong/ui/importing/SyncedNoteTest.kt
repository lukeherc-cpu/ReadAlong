package com.example.readalong.ui.importing

import com.example.readalong.data.importing.ImportResult
import com.example.readalong.data.local.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncedNoteTest {
    private fun folder(name: String) = FolderEntity("content://$name", name, "LIBRARY", null, null)

    @Test fun nothingChangedLeavesNoNote() {
        assertNull(syncedNote(listOf(folder("Audiobooks") to ImportResult(listOf("a", "b"), "2 audiobook(s) scanned."))))
        assertNull(syncedNote(emptyList()))
    }

    @Test fun oneFolderIsNamedWithWhatArrived() {
        assertEquals("Synced “Audiobooks” · 2 new · 1 ReadAlong",
            syncedNote(listOf(folder("Audiobooks") to ImportResult(listOf("a", "b", "c"), "", listOf("b", "c"), transcriptsTaken = 1),
                folder("Other") to ImportResult(listOf("d"), ""))))
    }

    @Test fun severalFoldersAddUp() {
        assertEquals("Synced · 1 new · 3 ReadAlongs",
            syncedNote(listOf(folder("A") to ImportResult(listOf("a"), "", listOf("a")), folder("B") to ImportResult(listOf("b"), "", transcriptsTaken = 3))))
    }
}

package com.example.readalong

import com.example.readalong.data.local.ImportedEpubEntity
import com.example.readalong.ui.reading.isInitialSyncPending
import org.junit.Assert.*
import org.junit.Test

class EpubSyncProgressTest {
    @Test fun onlyTheInitialAlignmentShowsASyncingBar() {
        val edition = ImportedEpubEntity("book", "edition", "", "Original book", "QUEUED", "", "EPUB", -1, 0, 0, 0, 0, 0)
        assertTrue(edition.isInitialSyncPending())
        assertTrue(edition.copy(state = "ALIGNING").isInitialSyncPending())
        assertFalse(edition.copy(state = "WAITING_TRANSCRIPT", alignedRevision = 0).isInitialSyncPending())
        assertFalse(edition.copy(state = "PARTIAL", alignedRevision = 12).isInitialSyncPending())
        assertFalse(edition.copy(state = "ALIGNING", alignedRevision = 12).isInitialSyncPending())
        assertFalse(edition.copy(state = "FAILED").isInitialSyncPending())
        assertFalse((null as ImportedEpubEntity?).isInitialSyncPending())
        assertTrue(edition.copy(editionId = "replacement").isInitialSyncPending())
    }
}

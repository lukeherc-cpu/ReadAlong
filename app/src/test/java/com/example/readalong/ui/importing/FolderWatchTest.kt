package com.example.readalong.ui.importing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderWatchTest {
    private val scanned = mapOf("a" to 1, "b" to 2)

    @Test fun aNewBookIsScannedOnceItHasFinishedCopying() {
        val copying = mapOf("a" to 5, "b" to 2)
        assertFalse(FolderWatch.shouldScan(scanned, previous = scanned, now = copying, sinceScanMs = 60_000))
        assertTrue(FolderWatch.shouldScan(scanned, previous = copying, now = copying, sinceScanMs = 60_000))
    }

    @Test fun aBookStillGrowingWaits() {
        assertFalse(FolderWatch.shouldScan(scanned, previous = mapOf("a" to 5, "b" to 2), now = mapOf("a" to 6, "b" to 2), sinceScanMs = 60_000))
    }

    @Test fun nothingChangedMeansNoScan() {
        assertFalse(FolderWatch.shouldScan(scanned, previous = scanned, now = scanned, sinceScanMs = 60_000))
    }

    @Test fun scansAreSpacedOut() {
        val changed = mapOf("a" to 5, "b" to 2)
        assertFalse(FolderWatch.shouldScan(scanned, previous = changed, now = changed, sinceScanMs = 5_000))
    }

    @Test fun aNewlyAddedFolderDoesNotTriggerAnotherScan() {
        val withNew = scanned + ("c" to 9)
        assertFalse(FolderWatch.shouldScan(scanned, previous = withNew, now = withNew, sinceScanMs = 60_000))
    }

    @Test fun aFullRescanRunsEveryTenMinutesAnyway() {
        assertTrue(FolderWatch.shouldScan(scanned, previous = scanned, now = scanned, sinceScanMs = FolderWatch.FULL_RESCAN_MS))
        assertTrue(FolderWatch.shouldScan(null, previous = null, now = scanned, sinceScanMs = FolderWatch.FULL_RESCAN_MS))
    }

    @Test fun switchingBackSoonRescansOnlyIfAFolderChanged() {
        assertFalse(FolderWatch.scanOnResume(scanned, scanned, sinceScanMs = 10_000))
        assertTrue(FolderWatch.scanOnResume(scanned, mapOf("a" to 5, "b" to 2), sinceScanMs = 10_000))
        assertTrue(FolderWatch.scanOnResume(scanned, scanned, sinceScanMs = FolderWatch.RESUME_MS))
        assertTrue(FolderWatch.scanOnResume(null, scanned, sinceScanMs = 10_000))
    }
}

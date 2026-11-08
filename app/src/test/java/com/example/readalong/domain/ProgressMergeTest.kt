package com.example.readalong.domain

import com.example.readalong.domain.link.ProgressMerge
import com.example.readalong.domain.link.ProgressMerge.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressMergeTest {
    @Test fun neverSyncedComparesClocks() {
        assertEquals(Direction.TAKE, ProgressMerge.decide(5_000, 10_000))
        assertEquals(Direction.WRITE, ProgressMerge.decide(20_000, 10_000))
        assertEquals(Direction.NONE, ProgressMerge.decide(9_000, 10_000))
        assertEquals(Direction.NONE, ProgressMerge.decide(null, null))
    }

    @Test fun onlyTheSideThatChangedWinsWhateverTheClocks() {
        // The computer's clock runs ten minutes fast: its old position still looks "newer" by the clock.
        val computer = 1_000_000L
        val phoneBefore = 400_000L
        val phoneNow = 500_000L
        assertEquals(Direction.WRITE, ProgressMerge.decide(phoneNow, computer, seenRemote = computer, seenLocal = phoneBefore))
        // The computer listened again; the phone didn't.
        assertEquals(Direction.TAKE, ProgressMerge.decide(phoneBefore, computer + 1, seenRemote = computer, seenLocal = phoneBefore))
    }

    @Test fun bothChangedFallsBackToClocks() {
        assertEquals(Direction.TAKE, ProgressMerge.decide(500_000, 900_000, seenRemote = 100, seenLocal = 200))
        assertEquals(Direction.WRITE, ProgressMerge.decide(900_000, 500_000, seenRemote = 100, seenLocal = 200))
    }

    @Test fun nothingChangedDoesNothingUnlessTheFileWentMissing() {
        assertEquals(Direction.NONE, ProgressMerge.decide(300, 300, seenRemote = 300, seenLocal = 300))
        assertEquals(Direction.WRITE, ProgressMerge.decide(300, null, seenRemote = 300, seenLocal = 300))
    }

    @Test fun seenRoundTrips() {
        assertEquals(12L to 34L, ProgressMerge.decodeSeen(ProgressMerge.encodeSeen(12, 34)))
        assertEquals(null to 34L, ProgressMerge.decodeSeen(ProgressMerge.encodeSeen(null, 34)))
    }
}

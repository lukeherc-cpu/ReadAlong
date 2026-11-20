package com.example.readalong.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialTest {
    private val day = 86_400_000L
    private val start = 1_700_000_000_000L

    @Test fun fullTrialOnInstallDay() = assertEquals(90, Trial.daysLeft(start, start + 5_000))

    @Test fun countsDownByWholeDays() {
        assertEquals(89, Trial.daysLeft(start, start + day))
        assertEquals(1, Trial.daysLeft(start, start + 89 * day + day - 1))
    }

    @Test fun endsAfterNinetyDays() {
        assertFalse(Trial.ended(start, start + 90 * day - 1))
        assertTrue(Trial.ended(start, start + 90 * day))
        assertEquals(0, Trial.daysLeft(start, start + 400 * day))
    }

    @Test fun clockBeforeInstallKeepsFullTrial() = assertEquals(90, Trial.daysLeft(start, start - 10 * day))
}

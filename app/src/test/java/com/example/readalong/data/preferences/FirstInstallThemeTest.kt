package com.example.readalong.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstInstallThemeTest {
    @Test fun freshInstallStartsDark() {
        assertFalse(FirstInstallTheme.startsLight(hasSavedSettings = false, hasLaunchCache = false, packageUpdated = false))
    }

    @Test fun anySignOfAnEarlierInstallKeepsLight() {
        assertTrue(FirstInstallTheme.startsLight(hasSavedSettings = true, hasLaunchCache = false, packageUpdated = false))
        assertTrue(FirstInstallTheme.startsLight(hasSavedSettings = false, hasLaunchCache = true, packageUpdated = false))
        assertTrue(FirstInstallTheme.startsLight(hasSavedSettings = false, hasLaunchCache = false, packageUpdated = true))
    }
}

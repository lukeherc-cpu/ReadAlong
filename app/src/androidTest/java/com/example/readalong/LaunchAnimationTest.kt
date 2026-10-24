package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class LaunchAnimationTest {
    @Test fun completeSplashMarkFitsInsideAndroidsIconMask() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val drawable = androidx.core.content.ContextCompat.getDrawable(app, R.drawable.ic_splash_logo)!!
        val bitmap = android.graphics.Bitmap.createBitmap(288, 288, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 288, 288); drawable.draw(android.graphics.Canvas(bitmap))
        var pixels = 0
        for (y in 0 until 288) for (x in 0 until 288) if (android.graphics.Color.alpha(bitmap.getPixel(x,y)) > 64) {
            pixels++
            org.junit.Assert.assertTrue("Headphones and pages must fit the splash safe circle", (x-144)*(x-144)+(y-144)*(y-144) <= 96*96)
        }
        org.junit.Assert.assertTrue(pixels > 1000)
    }

    @get:Rule val compose = createEmptyComposeRule()
    @Test fun launchTurnsOnePageAndDoesNotReplayOnRecreation() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val original = runBlocking { app.appPreferences.preferences.first() }
        runBlocking { app.appPreferences.update { it.copy(batteryWarningSeen = false, setupStage = 0) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("launch_logo").assertDoesNotExist()
            compose.onNodeWithTag("welcome_next").performClick()
            compose.onNodeWithText("Where are your audiobooks?").assertIsDisplayed()
            compose.onNodeWithText("Skip for now").performClick()
            compose.onNodeWithTag("welcome").assertDoesNotExist()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("launch_logo").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { app.appPreferences.update { original } }
            scenario.close()
        }
    }
}

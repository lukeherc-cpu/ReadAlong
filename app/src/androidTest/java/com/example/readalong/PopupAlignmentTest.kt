package com.example.readalong

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupAlignmentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun backgroundPromptKeepsBothActionsCenteredAtNormalAndLargerFontSizes() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                com.example.readalong.ui.theme.ReadAlongTheme {
                    com.example.readalong.ui.transcription.BackgroundPreparationPrompt(app, force = true)
                }
            }
        }
        for (value in listOf(1f, 1.6f)) {
            compose.runOnIdle { scale = value }
            val primary = compose.onNodeWithText("Allow background processing").performScrollTo().fetchSemanticsNode().boundsInRoot
            val later = compose.onNodeWithText("Not now").performScrollTo().fetchSemanticsNode().boundsInRoot
            assertEquals(primary.center.x, later.center.x, 1f)
            assertEquals(primary.width, later.width, 1f)
            compose.onNodeWithText("Not now").assertIsDisplayed()
        }
    }
}

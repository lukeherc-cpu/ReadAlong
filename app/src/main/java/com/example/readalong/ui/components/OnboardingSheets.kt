package com.example.readalong.ui.components

import androidx.compose.runtime.*

/**
 * Only one first-run sheet (a page guide or the background-work prompt) is open at a time. Once the
 * background prompt has been shown, page guides wait until the app is next started, so the two
 * never arrive back to back.
 */
object OnboardingSheets {
    var open by mutableStateOf<String?>(null)
        internal set
    var guidesPaused by mutableStateOf(false)
        internal set

    /** Claims the slot for [key] while [wants] is true; true when [key] may show its sheet now. */
    @Composable
    fun turn(key: String, wants: Boolean): Boolean {
        val current = open
        LaunchedEffect(key, wants, current) {
            if (wants && open == null) open = key
            else if (!wants && open == key) open = null
        }
        DisposableEffect(key) { onDispose { if (open == key) open = null } }
        return wants && current == key
    }

    fun pauseGuides() { guidesPaused = true }
}

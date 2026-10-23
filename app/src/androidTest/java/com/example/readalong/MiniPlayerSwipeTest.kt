package com.example.readalong

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.PlaybackState
import com.example.readalong.ui.player.MiniPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MiniPlayerSwipeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun leftSwipeDismissesAfterAnimationWhileRightSwipeAndButtonTapsKeepPlayer() {
        var dismissed=0;var toggled=0;var opened=0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            MiniPlayer(Book("one","A book"),PlaybackState(bookId="one"),{opened++},{toggled++},onDismiss={dismissed++})
        } } }
        compose.onNodeWithContentDescription("Play mini player").performClick()
        compose.runOnIdle { assertEquals(1,toggled);assertEquals(0,opened);assertEquals(0,dismissed) }
        compose.onNodeWithContentDescription("Expand mini player").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0,dismissed) }
        compose.onNodeWithContentDescription("Expand mini player").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { dismissed == 1 }
        compose.onNodeWithContentDescription("Expand mini player").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0,opened);assertEquals(1,dismissed) }
    }
    @Test fun changingBooksResetsTheDismissedGestureState() {
        var book by mutableStateOf(Book("one","First"));var dismissed=0
        compose.setContent { MaterialTheme { MiniPlayer(book,PlaybackState(bookId=book.id),{},{},onDismiss={dismissed++}) } }
        compose.onNodeWithContentDescription("Expand mini player").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { dismissed == 1 }
        compose.runOnIdle { book=Book("two","Second") }
        compose.onNodeWithContentDescription("Expand mini player").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play mini player").assertIsDisplayed()
    }
}

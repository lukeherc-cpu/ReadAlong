package com.example.readalong

import android.content.ComponentName
import android.os.Looper
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.playback.AudiobookService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What Android Auto sees: Started, New and Finished to browse, and Speed and Next chapter buttons. */
class AndroidAutoLibraryTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    private fun <T> onMain(work: () -> T): T { var out: T? = null; InstrumentationRegistry.getInstrumentation().runOnMainSync { out = work() }; @Suppress("UNCHECKED_CAST") return out as T }

    @Test fun libraryCanBeBrowsed() {
        val token = SessionToken(app, ComponentName(app, AudiobookService::class.java))
        val browser = MediaBrowser.Builder(app, token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(20, TimeUnit.SECONDS)
        try {
            val root = onMain { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
            assertEquals("root", root.value!!.mediaId)
            val tabs = onMain { browser.getChildren("root", 0, 20, null) }.get(10, TimeUnit.SECONDS).value!!
            assertEquals(listOf("Started", "New", "Finished"), tabs.map { it.mediaMetadata.title.toString() })
            tabs.forEach { tab ->
                val books = onMain { browser.getChildren(tab.mediaId, 0, 50, null) }.get(20, TimeUnit.SECONDS).value!!
                assertTrue(books.all { it.mediaId.startsWith("book:") && it.mediaMetadata.isPlayable == true })
            }
            val commands = onMain { browser.availableSessionCommands }
            assertTrue(commands.contains(SessionCommand("readalong.CYCLE_SPEED", android.os.Bundle.EMPTY)))
            assertTrue(commands.contains(SessionCommand("readalong.NEXT_CHAPTER", android.os.Bundle.EMPTY)))
        } finally { onMain { browser.release() } }
    }
}

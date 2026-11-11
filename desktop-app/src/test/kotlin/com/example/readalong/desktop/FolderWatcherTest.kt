package com.example.readalong.desktop

import com.example.readalong.desktop.core.FolderWatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** A book copied into a library folder while ReadAlong is open is noticed without "Check for new books". */
class FolderWatcherTest {
    private val root = Files.createTempDirectory("library").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun waitFor(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (done()) return true; Thread.sleep(100) }
        return done()
    }

    @Test fun aNewBookFolderSetsOffOneRescanOnceQuiet() = runBlocking {
        root.resolve("Old book").mkdirs()
        val calls = AtomicInteger()
        val job = launch(Dispatchers.IO) { FolderWatcher(MutableStateFlow(listOf(root.absolutePath)), { calls.incrementAndGet() }, quietMs = 500).run() }
        try {
            delay(700) // registered
            // ReadAlong's own files beside a book never count.
            root.resolve("Old book/Old book.readalong").writeText("x")
            root.resolve("Old book/.progress.tmp").writeText("x")
            delay(1_500)
            assertEquals(0, calls.get())
            val book = root.resolve("Author/New book").apply { mkdirs() }
            delay(200)
            book.resolve("01.mp3").writeBytes(ByteArray(1024))
            book.resolve("02.mp3").writeBytes(ByteArray(1024))
            // On macOS the JDK polls folders every few seconds, so allow time.
            assertTrue(waitFor(20_000) { calls.get() >= 1 }, "No rescan after a book was added")
            delay(1_500)
            assertTrue(calls.get() <= 2, "Rescanned ${calls.get()} times for one book")
        } finally { job.cancelAndJoin() }
    }

    @Test fun whatCountsAsABookChange() {
        assertTrue(FolderWatcher.counts("01.mp3", isFolder = false, deleted = false))
        assertTrue(FolderWatcher.counts("Book.M4B", isFolder = false, deleted = false))
        assertTrue(FolderWatcher.counts("New book", isFolder = true, deleted = false))
        assertTrue(FolderWatcher.counts("Gone book", isFolder = false, deleted = true))
        assertTrue(FolderWatcher.counts("Vol. 1 book", isFolder = false, deleted = true))
        assertFalse(FolderWatcher.counts("Book.readalong", isFolder = false, deleted = false))
        assertFalse(FolderWatcher.counts("cover.jpg", isFolder = false, deleted = true))
        assertFalse(FolderWatcher.counts(".Book.m4b.readalong-sync", isFolder = false, deleted = false))
    }
}

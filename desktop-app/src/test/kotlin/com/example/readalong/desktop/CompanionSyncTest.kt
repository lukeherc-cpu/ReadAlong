package com.example.readalong.desktop

import com.example.readalong.desktop.core.*
import com.example.readalong.desktop.link.DeviceLink
import com.example.readalong.domain.link.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** Covers, EPUBs and the "What to sync" choices travelling between the computer and the phone. */
class CompanionSyncTest {
    private val home = Files.createTempDirectory("companions").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun bookIn(folder: File): Pair<LibraryStore, LibraryBook> {
        folder.mkdirs()
        val audio = File(folder, "01.mp3").apply { writeBytes(ByteArray(10)) }
        val book = LibraryBook("b1", "Dune", key = "folder:${folder.path}", folder = folder.path, parts = listOf(AudioPart(audio.path, 1000, 0, 10, audio.lastModified())))
        return LibraryStore(File(home, "library.json"), scope).apply { replaceAll { listOf(book) } } to book
    }

    @Test fun kindsAreSortedOutByName() {
        assertEquals(FolderSyncPlan.Kind.TRANSCRIPT, FolderSyncPlan.kind("Dune/Dune.readalong"))
        assertEquals(FolderSyncPlan.Kind.PROGRESS, FolderSyncPlan.kind("Dune/Dune.readalong-progress"))
        assertEquals(FolderSyncPlan.Kind.EPUB, FolderSyncPlan.kind("Dune/Dune.epub"))
        assertEquals(FolderSyncPlan.Kind.COVER, FolderSyncPlan.kind("Dune/Dune.cover.jpg"))
        assertNull(FolderSyncPlan.kind("Dune/01.mp3"))
        assertTrue(FolderSyncPlan.included("Dune/01.mp3", setOf("transcript", "progress", "epub", "cover")))
        assertFalse(FolderSyncPlan.included("Dune/cover.png", setOf("cover")))
        assertTrue(FolderSyncPlan.included("Dune/Dune.epub", setOf("cover")))
        assertEquals(setOf("epub", "cover"), FolderSyncPlan.decodeKinds(FolderSyncPlan.encodeKinds(setOf("cover", "epub"))))
    }

    @Test fun aChosenEpubIsCopiedBesideTheBook() {
        val (library, book) = bookIn(File(home, "Books/Dune"))
        val chosen = File(home, "Downloads/dune-frank-herbert.epub").apply { parentFile.mkdirs(); writeBytes(ByteArray(64) { 1 }) }
        val companions = Companions(library, File(home, "companions.json"))
        val used = companions.attachEpub(book, chosen)
        assertEquals(File(home, "Books/Dune/Dune.epub"), used)
        assertContentEquals(chosen.readBytes(), used.readBytes())
        assertEquals(used.path, library.book("b1")?.epubPath)
        // Its own copy isn't news to the next rescan; a new one from the phone is.
        assertFalse(companions.epubChanged(library.book("b1")!!, used))
        used.writeBytes(ByteArray(80) { 2 }); used.setLastModified(used.lastModified() + 5_000)
        assertTrue(companions.epubChanged(library.book("b1")!!, used))
    }

    @Test fun anEpubAlreadyInUseIsOnlyRecordedOnUpgrade() {
        val (library, book) = bookIn(File(home, "Books/Dune"))
        val epub = File(home, "Books/Dune/Dune.epub").apply { writeBytes(ByteArray(64)) }
        library.update(book.id) { it.copy(epubPath = epub.path) }
        val companions = Companions(library, File(home, "companions.json"))
        assertFalse(companions.epubChanged(library.book("b1")!!, epub))
        // A different EPUB in use: the companion is new, so it's taken.
        val other = Companions(library, File(home, "other.json"))
        library.update(book.id) { it.copy(epubPath = File(home, "elsewhere.epub").path) }
        assertTrue(other.epubChanged(library.book("b1")!!, epub))
    }

    @Test fun aCoverIsSavedAsJpegBesideTheBookAndPhoneCoversAreTaken() {
        val (library, book) = bookIn(File(home, "Books/Dune"))
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val companions = Companions(library, File(home, "companions.json"))
        val saved = companions.saveCover(book, png)
        assertEquals(File(home, "Books/Dune/Dune.cover.jpg"), saved)
        assertEquals(saved.path, library.book("b1")?.coverPath)
        assertEquals(40, ImageIO.read(saved).width)
        assertFalse(companions.coverChanged(book, saved))
        // The phone sends a new one.
        saved.writeBytes(png); saved.setLastModified(saved.lastModified() + 5_000)
        assertTrue(companions.coverChanged(book, saved))
        assertFalse(companions.coverChanged(book, saved))
        // Remembered across restarts.
        assertFalse(Companions(library, File(home, "companions.json")).coverChanged(book, saved))
    }

    @Test fun singleFileBooksFindTheirCompanionCover() {
        val folder = File(home, "Shelf").apply { mkdirs() }
        File(folder, "A.m4b").writeBytes(ByteArray(10)); File(folder, "B.m4b").writeBytes(ByteArray(10))
        File(folder, "A.cover.jpg").writeBytes(ByteArray(10))
        val a = Scanner.scan(folder).single { it.name == "A" }
        assertEquals(listOf("A.cover.jpg"), a.images.map { it.name })
    }

    /** Kinds turned off on the computer aren't listed to the phone or accepted from it, and the phone is told which. */
    @Test fun kindsTurnedOffOnTheComputerDontSync() {
        val books = File(home, "Audiobooks").apply { mkdirs() }
        File(books, "Dune").mkdirs()
        File(books, "Dune/01.mp3").writeBytes(ByteArray(100)); File(books, "Dune/Dune.epub").writeBytes(ByteArray(10)); File(books, "Dune/Dune.readalong").writeBytes(ByteArray(10))
        val library = LibraryStore(File(home, "library.json"), scope).apply { setFolders(listOf(books.absolutePath)) }
        val link = DeviceLink(scope, library, onSynced = {}, file = File(home, "link.json"))
        try {
            val code = link.startPairing()
            link.setShared(listOf(books.absolutePath)); link.setSkipped(setOf("epub"))
            val id = DeviceLink.folderId(books.absolutePath)
            Socket("127.0.0.1", code.port).use { socket ->
                val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), code.key)
                channel.sendJson(JSONObject().put("op", "hello").put("device", "phone-1").put("name", "Pixel")); channel.receiveJson()
                channel.sendJson(JSONObject().put("op", "list").put("folder", id))
                val head = channel.receiveJson()
                assertEquals(setOf("epub"), FolderSyncPlan.decodeKinds(head.optJSONArray("skip")))
                val files = channel.receiveJson().getJSONArray("files")
                assertEquals(setOf("Dune/01.mp3", "Dune/Dune.readalong"), (0 until files.length()).map { files.getJSONObject(it).getString("path") }.toSet())
                channel.sendJson(JSONObject().put("op", "put").put("folder", id).put("path", "Dune/Other.epub").put("size", 4))
                assertFailsWith<LinkException> { channel.receiveJson() }
                channel.sendJson(JSONObject().put("op", "done")); channel.receive()
            }
            assertFalse(File(books, "Dune/Other.epub").exists())
            assertEquals(setOf("epub"), DeviceLink(scope, library, {}, File(home, "link.json")).state.value.skipped)
        } finally { link.stop() }
    }
}

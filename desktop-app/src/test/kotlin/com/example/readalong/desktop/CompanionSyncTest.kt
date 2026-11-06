package com.example.readalong.desktop

import com.example.readalong.desktop.core.*
import com.example.readalong.desktop.link.DeviceLink
import com.example.readalong.desktop.transcription.TranscriptStore
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
            assertEquals(setOf("epub"), DeviceLink(scope, library, {}, file = File(home, "link.json")).state.value.skipped)
        } finally { link.stop() }
    }

    /** A phone package with no readable text is refused, so the book doesn't count as having a ReadAlong it can't show. */
    @Test fun aPhonePackageIsOnlyTakenWhenItHasText() {
        val folder = File(home, "Books/Emma").apply { mkdirs() }
        val audio = File(folder, "01.mp3").apply { writeBytes(ByteArray(2048) { (it % 13).toByte() }) }
        val book = LibraryBook("pkg-test-" + java.util.UUID.randomUUID(), "Emma", key = "folder:${folder.path}", folder = folder.path,
            parts = listOf(AudioPart(audio.path, 40_000, 0, audio.length(), audio.lastModified())))
        val (size, sha) = TranscriptStore.sha256(audio)
        fun pkg(name: String, text: String, length: Long = 40_000): File {
            val words = org.json.JSONArray().apply { if (text.isNotEmpty()) text.split(" ").forEachIndexed { i, w -> put(JSONObject().put("word", w).put("start", 1.0 + i * .4).put("end", 1.3 + i * .4)) } }
            val chunks = listOf(0L to 30_000L, 30_000L to length).map { (a, b) ->
                JSONObject().put("file", 0).put("startMs", a).put("endMs", b).put("result", JSONObject().put("text", if (a == 0L) text else "").put("words", if (a == 0L) words else org.json.JSONArray())).toString()
            }
            val manifest = JSONObject().put("format", "readalong-transcript").put("version", 1).put("complete", true).put("model", "whistle-v1").put("engine", "Cactus Whistle")
                .put("chunks", chunks.size).put("sources", org.json.JSONArray().put(JSONObject().put("name", "01.mp3").put("size", size).put("sha256", sha).put("durationMs", length)))
            return File(home, name).apply {
                java.util.zip.ZipOutputStream(outputStream()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                    zip.putNextEntry(java.util.zip.ZipEntry("chunks.jsonl")); chunks.forEach { zip.write((it + "\n").toByteArray()) }; zip.closeEntry()
                }
            }
        }
        val store = TranscriptStore()
        try {
            assertFailsWith<IllegalArgumentException> { store.importPackage(book, pkg("empty.readalong", "")) }
            assertNull(store.info(book.id))
            store.importPackage(book, pkg("full.readalong", "Emma Woodhouse, handsome, clever, and rich."))
            assertEquals(true, store.info(book.id)?.complete)
            // The same bytes measured 10% longer by the other app (a VBR MP3 without a frame index): still this book's text.
            store.importPackage(book, pkg("vbr.readalong", "Emma Woodhouse, handsome, clever, and rich.", length = 44_000))
            assertEquals(true, store.info(book.id)?.complete)
            assertTrue(store.segments(book).isNotEmpty())
        } finally { store.remove(book.id) }
    }

    /** A title or author edited on either device reaches the other; the newer edit wins. */
    @Test fun editedTitlesAndAuthorsSyncBothWays() {
        val (library, book) = bookIn(File(home, "Books/Dune"))
        val sync = BookInfoSync(library)
        val state = File(home, "Books/Dune/Dune.readalong-state")
        fun shared() = BookInfoFiles.parse(BookStateFiles.section(state.readText(), BookStateFiles.Part.INFO)!!)
        fun phoneEdits(info: BookInfo) = state.writeText(BookStateFiles.withSection(state.takeIf { it.isFile }?.readText(), BookStateFiles.Part.INFO, BookInfoFiles.encode(info, "Android"), "Android")!!)
        // Never edited anywhere: nothing written, nothing changed.
        assertFalse(sync.sync(book)); assertFalse(state.exists())
        // Edited here: shared.
        sync.edit("b1", "Dune (Deluxe)", "Frank Herbert ")
        assertEquals(BookInfo("Dune (Deluxe)", "Frank Herbert", library.book("b1")!!.editedAt!!), shared())
        // Edited later on the phone: taken.
        val later = library.book("b1")!!.editedAt!! + 60_000
        phoneEdits(BookInfo("Dune Messiah", null, later))
        assertTrue(sync.sync(library.book("b1")!!))
        assertEquals(Triple("Dune Messiah", null, later), library.book("b1")!!.let { Triple(it.title, it.author, it.editedAt) })
        // An older phone edit doesn't undo a newer one here; this computer's is written back.
        sync.edit("b1", "Dune", "Herbert")
        phoneEdits(BookInfo("Old", null, 5))
        assertFalse(sync.sync(library.book("b1")!!))
        assertEquals("Dune", library.book("b1")!!.title)
        assertEquals("Dune", shared()?.title)
        // The edit survives a restart.
        assertEquals(library.book("b1")!!.editedAt, LibraryStore.decode(LibraryStore.encode(library.book("b1")!!)).editedAt)
        assertNull(BookInfoFiles.parse("{\"format\":\"readalong-info\",\"version\":1,\"title\":\" \",\"editedAt\":1}"))
    }

    /** Progress, bookmarks and edits kept the old way, one file each, end up in the book's single state file. */
    @Test fun oldSeparateFilesAreFoldedIntoOneStateFile() {
        val folder = File(home, "Books/Dune")
        val (library, book) = bookIn(folder)
        val mark = { id: String -> SharedBookmark(id, id, 1, 1_000, 0, null, -1, 0, null) }
        // The state file already has a newer position and one bookmark; the old files have an older position, a new edit and more bookmarks.
        File(folder, "Dune.readalong-state").writeText(BookStateFiles.withSection(null, BookStateFiles.Part.PROGRESS,
            JSONObject().put("format", "readalong-progress").put("version", 1).put("updatedAt", 9_000).put("positionMs", 900).toString(), "Android")!!
            .let { BookStateFiles.withSection(it, BookStateFiles.Part.BOOKMARKS, BookmarkFiles.encode(BookmarkFile(listOf(mark("a")), setOf("x")), "Android"), "Android")!! })
        File(folder, "Dune.readalong-progress").writeText(JSONObject().put("format", "readalong-progress").put("version", 1).put("updatedAt", 5_000).put("positionMs", 500).toString())
        File(folder, "Dune.readalong-bookmarks").writeText(BookmarkFiles.encode(BookmarkFile(listOf(mark("b"), mark("x")), setOf("c")), "Android"))
        File(folder, "Dune.readalong-info").writeText(BookInfoFiles.encode(BookInfo("Dune Messiah", "Herbert", 7), "Android"))
        File(folder, "notes.readalong-info").writeText("not ours")

        BookState.convert(book)
        val text = File(folder, "Dune.readalong-state").readText()
        assertEquals(9_000, JSONObject(BookStateFiles.section(text, BookStateFiles.Part.PROGRESS)!!).getLong("updatedAt"))
        val marks = BookmarkFiles.parse(BookStateFiles.section(text, BookStateFiles.Part.BOOKMARKS)!!)!!
        assertEquals(setOf("a", "b"), marks.bookmarks.map { it.id }.toSet())
        assertEquals(setOf("c", "x"), marks.removed)
        assertEquals("Dune Messiah", BookInfoFiles.parse(BookStateFiles.section(text, BookStateFiles.Part.INFO)!!)?.title)
        assertEquals(listOf("01.mp3", "Dune.readalong-state", "notes.readalong-info"), folder.list()!!.sorted())
        // The apps read it as before: the info is taken from it.
        assertTrue(BookInfoSync(library).sync(book))
        assertEquals("Dune Messiah", library.book("b1")!!.title)
        // One file to sync, turned off with progress or bookmarks.
        assertEquals(FolderSyncPlan.Kind.PROGRESS, FolderSyncPlan.kind("Dune/Dune.readalong-state"))
        assertTrue(FolderSyncPlan.included("Dune/Dune.readalong-state", setOf("epub")))
        assertFalse(FolderSyncPlan.included("Dune/Dune.readalong-state", setOf("bookmarks")))
        // A state file from a newer version that this one can't read is never written over.
        assertNull(BookStateFiles.withSection("{\"format\":\"readalong-state\",\"version\":9}", BookStateFiles.Part.INFO, "{}", "Test"))
    }
}

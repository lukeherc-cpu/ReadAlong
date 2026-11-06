package com.example.readalong.desktop

import com.example.readalong.desktop.core.*
import com.example.readalong.desktop.link.DeviceLink
import com.example.readalong.domain.link.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.nio.file.Files
import kotlin.test.*

/** Bookmarks and app/reader settings travelling between the computer and the phone. */
class BookmarkAndSettingsSyncTest {
    private val home = Files.createTempDirectory("bookmarks").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun book(folder: File, bookmarks: List<Bookmark> = emptyList()): Pair<LibraryStore, LibraryBook> {
        folder.mkdirs()
        val one = File(folder, "01.mp3").apply { writeBytes(ByteArray(10)) }
        val two = File(folder, "02.mp3").apply { writeBytes(ByteArray(10)) }
        val book = LibraryBook("b1", "Dune", key = "folder:${folder.path}", folder = folder.path,
            parts = listOf(AudioPart(one.path, 600_000, 0, 10, 1), AudioPart(two.path, 600_000, 600_000, 10, 1)), bookmarks = bookmarks)
        return LibraryStore(File(home, "library.json"), scope).apply { replaceAll { listOf(book) } } to book
    }

    private fun phoneMark(id: String, file: String, within: Long) = SharedBookmark(id, "From the phone", 5, 0, 1_190_000, file, -1, within, 2)

    @Test fun mergeKeepsBothSidesAndHonoursDeletions() {
        val a = phoneMark("a", "01.mp3", 1); val b = phoneMark("b", "02.mp3", 2); val c = phoneMark("c", "02.mp3", 3)
        val result = BookmarkFiles.merge(listOf(a, c), setOf("x"), BookmarkFile(listOf(b, c), setOf("c")))
        assertEquals(listOf("b"), result.add.map { it.id })
        assertEquals(setOf("c"), result.delete)
        assertEquals(setOf("a", "b"), result.write!!.bookmarks.map { it.id }.toSet())
        assertEquals(setOf("c", "x"), result.removed)
        // Nothing new on either side: the file is left alone.
        assertNull(BookmarkFiles.merge(listOf(a, b), setOf("c", "x"), BookmarkFile(listOf(a, b), setOf("c", "x"))).write)
        assertNull(BookmarkFiles.merge(emptyList(), emptySet(), null).write)
        val text = BookmarkFiles.encode(BookmarkFile(listOf(a, b), setOf("c")), "Test")
        assertEquals(BookmarkFile(listOf(a, b), setOf("c")), BookmarkFiles.parse(text))
    }

    @Test fun phoneBookmarksArriveAndDeletionsTravelBothWays() {
        val folder = File(home, "Dune")
        val mine = Bookmark(30_000, "Mine", 1, "mine")
        val (library, start) = book(folder, listOf(mine))
        val file = File(folder, "Dune.readalong-bookmarks")
        // The phone splits nothing differently, but its whole-book length is a little off: the file name and time in it still place it.
        file.writeText(BookmarkFiles.encode(BookmarkFile(listOf(phoneMark("phone", "02.mp3", 90_000)), emptySet()), "Android"))
        val sync = BookmarkSync(library)
        sync.sync(start)
        val merged = library.book("b1")!!
        assertEquals(listOf(30_000L, 690_000L), merged.bookmarks.map { it.positionMs })
        // The old separate file was folded into the book's state file.
        assertFalse(file.exists())
        assertEquals(setOf("mine", "phone"), BookmarkFiles.parse(BookStateFiles.section(File(folder, "Dune.readalong-state").readText(), BookStateFiles.Part.BOOKMARKS)!!)!!.bookmarks.map { it.id }.toSet())

        // Deleted here: gone from the file, and remembered.
        library.update("b1") { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == "phone" }, removedBookmarks = it.removedBookmarks + "phone") }
        sync.sync(library.book("b1")!!)
        assertEquals(setOf("mine"), BookmarkFiles.parse(BookStateFiles.section(File(folder, "Dune.readalong-state").readText(), BookStateFiles.Part.BOOKMARKS)!!)!!.bookmarks.map { it.id }.toSet())
        assertTrue("phone" in BookmarkFiles.parse(BookStateFiles.section(File(folder, "Dune.readalong-state").readText(), BookStateFiles.Part.BOOKMARKS)!!)!!.removed)

        // Deleted on the phone (here by an older version, which still writes the separate file): deleted here.
        file.writeText(BookmarkFiles.encode(BookmarkFile(emptyList(), setOf("phone", "mine")), "Android"))
        sync.sync(library.book("b1")!!)
        assertTrue(library.book("b1")!!.bookmarks.isEmpty())

        // Ids and deletions survive a restart of the library.
        library.update("b1") { it.copy(bookmarks = listOf(Bookmark(5, "Again", 2, "again"))) }
        library.flush()
        val reloaded = LibraryStore(File(home, "library.json"), scope).book("b1")!!
        assertEquals("again", reloaded.bookmarks.single().id)
        assertEquals(setOf("phone", "mine"), reloaded.removedBookmarks.toSet())
        // Bookmarks saved before ids existed get a stable one.
        assertEquals(Bookmark(7, "Old", 9).id, Bookmark(7, "Old", 9).id)
    }

    @Test fun settingsMergeTakesWhicheverSideChanged() {
        val agreed = mapOf("accent" to "1", "skipBack" to "20", "readerTheme" to "DARK")
        val phone = mapOf("accent" to "2", "skipBack" to "20", "readerTheme" to "SEPIA")
        val computer = mapOf("accent" to "1", "skipBack" to "15", "readerTheme" to "LIGHT")
        // Accent changed on the phone, skip time on the computer, theme on both (the phone's wins).
        assertEquals(mapOf("accent" to "2", "skipBack" to "15", "readerTheme" to "SEPIA"), SettingsSync.merge(phone, computer, agreed))
        // The first sync takes the phone's.
        assertEquals(phone, SettingsSync.merge(phone, computer, null))
        assertEquals(phone, SettingsSync.decode(SettingsSync.encode(phone)))
    }

    @Test fun computerServesAndTakesSettingsUnlessTurnedOff() {
        val (library, _) = book(File(home, "Books"))
        var computer = mapOf("accent" to "1", "readerTheme" to "DARK")
        val link = DeviceLink(scope, library, onSynced = {}, settings = { computer }, applySettings = { computer = computer + it }, file = File(home, "link.json"))
        try {
            val code = link.startPairing()
            fun session(block: (LinkChannel) -> Unit) = Socket("127.0.0.1", code.port).use { socket ->
                val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), code.key)
                channel.sendJson(JSONObject().put("op", "hello").put("device", "phone-1").put("name", "Pixel")); channel.receiveJson()
                block(channel)
                channel.sendJson(JSONObject().put("op", "done")); channel.receive()
            }
            session { channel ->
                channel.sendJson(JSONObject().put("op", "settings"))
                assertEquals(computer, SettingsSync.decode(channel.receiveJson().getJSONObject("values")))
                channel.sendJson(JSONObject().put("op", "set-settings").put("values", SettingsSync.encode(mapOf("readerTheme" to "SEPIA")))); channel.receiveJson()
            }
            assertEquals("SEPIA", computer["readerTheme"])
            link.setSkipped(setOf("settings"))
            session { channel ->
                channel.sendJson(JSONObject().put("op", "settings"))
                assertTrue("settings" in FolderSyncPlan.decodeKinds(channel.receiveJson().optJSONArray("skip")))
                channel.sendJson(JSONObject().put("op", "set-settings").put("values", SettingsSync.encode(mapOf("readerTheme" to "LIGHT")))); channel.receiveJson()
            }
            assertEquals("SEPIA", computer["readerTheme"])
        } finally { link.stop() }
    }

    @Test fun textSizeIsNotShared() {
        val sent = SettingsSync.encode(mapOf("readerFont" to "LITERATA", "readerSize" to "30.0"))
        assertEquals(mapOf("readerFont" to "LITERATA"), SettingsSync.decode(sent))
        assertEquals(mapOf("readerFont" to "LITERATA"), SettingsSync.merge(mapOf("readerFont" to "LITERATA", "readerSize" to "30.0"), mapOf("readerSize" to "18.0"), null))
    }

    @Test fun bookmarkFilesAreTheirOwnKind() {
        assertEquals(FolderSyncPlan.Kind.BOOKMARKS, FolderSyncPlan.kind("Dune/Dune.readalong-bookmarks"))
        assertFalse(FolderSyncPlan.included("Dune/Dune.readalong-bookmarks", setOf("bookmarks")))
        assertTrue(FolderSyncPlan.included("Dune/Dune.readalong-progress", setOf("bookmarks", "settings")))
    }
}

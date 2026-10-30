package com.example.readalong.desktop

import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.link.DeviceLink
import com.example.readalong.domain.link.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.*

class DeviceLinkTest {
    private val day = 86_400_000L

    @Test fun channelRoundTripsAndRejectsTheWrongKey() {
        val key = LinkChannel.newKey()
        val toServer = PipedInputStream(1 shl 21); val clientOut = PipedOutputStream(toServer)
        val toClient = PipedInputStream(1 shl 21); val serverOut = PipedOutputStream(toClient)
        var received: String? = null
        val server = thread {
            val (channel, id) = LinkChannel.accept(toServer, serverOut) { if (it == LinkChannel.keyId(key)) key else null }
            assertEquals(LinkChannel.keyId(key), id)
            received = channel.receiveJson().getString("hello")
            val sink = ByteArrayOutputStream(); channel.receiveStream(sink, 700_000)
            channel.sendJson(JSONObject().put("size", sink.size()))
        }
        val client = LinkChannel.connect(toClient, clientOut, key)
        client.sendJson(JSONObject().put("hello", "phone"))
        client.sendStream(ByteArray(700_000) { it.toByte() }.inputStream(), 700_000)
        assertEquals(700_000, client.receiveJson().getInt("size"))
        server.join()
        assertEquals("phone", received)

    }

    @Test fun linkCodeSurvivesTheQrCode() {
        val code = LinkCode("abc", "Luke's PC & laptop", listOf("192.168.1.20", "10.0.0.4"), 47810, LinkChannel.newKey())
        assertEquals(code, LinkCode.parse(code.encode()))
        assertNull(LinkCode.parse("https://example.com"))
        assertNull(LinkCode.parse("readalong-link:1?d=a&h=1.2.3.4&p=1&k=short"))
    }

    @Test fun planCopiesNewFilesBothWaysAndNeverResurrectsDeletions() {
        val local = listOf(SyncFile("Dune/01.mp3", 100, day), SyncFile("Dune/Dune.readalong-progress", 10, 5 * day), SyncFile(".hidden/x.mp3", 1, 1))
        val remote = listOf(SyncFile("Dune/01.mp3", 100, 2 * day), SyncFile("Hobbit/01.mp3", 50, day), SyncFile("Dune/Dune.readalong-progress", 11, 4 * day))
        val first = FolderSyncPlan.plan(local, remote, emptyMap())
        // Same-size audio is taken as the same file; the newer progress file wins; the new book comes down; hidden files stay put.
        assertEquals(setOf(SyncAction.Upload("Dune/Dune.readalong-progress"), SyncAction.Download("Hobbit/01.mp3")), first.toSet())

        val synced = FolderSyncPlan.settled(local + SyncFile("Hobbit/01.mp3", 50, 6 * day), remote, emptyMap())
        // Hobbit deleted on the phone afterwards: not copied back.
        assertEquals(emptyList(), FolderSyncPlan.plan(local, remote, synced).filter { it.path.startsWith("Hobbit") })
        // Only the computer's progress changed since: it comes down, even though the phone's file is "newer".
        val later = remote.map { if (it.path.endsWith("progress")) it.copy(size = 12, modified = 6 * day) else it }
        val record = FolderSyncPlan.settled(local, remote, emptyMap())
        assertEquals(listOf(SyncAction.Download("Dune/Dune.readalong-progress")), FolderSyncPlan.plan(local, later, record))
        // Audio changed on both sides is left alone.
        val bothAudio = FolderSyncPlan.plan(listOf(SyncFile("a.mp3", 1, 9)), listOf(SyncFile("a.mp3", 2, 9)), mapOf("a.mp3" to SyncedState(3, 1, 3, 1)))
        assertTrue(bothAudio.isEmpty())
        assertEquals(record, FolderSyncPlan.decode(FolderSyncPlan.encode(record)))
    }

    @Test fun unsafePathsAreRefused() {
        listOf("../x.mp3", "/etc/passwd", "a/../../b", "C:/x", "a\\b", "", "a//b").forEach { assertFalse(FolderSyncPlan.safe(it), it) }
        val root = Files.createTempDirectory("root").toFile()
        assertFailsWith<IllegalArgumentException> { DeviceLink.resolve(root, "../escape.mp3") }
        assertEquals(File(root.canonicalFile, "Dune/01.mp3"), DeviceLink.resolve(root, "Dune/01.mp3"))
    }

    /** A phone pairs with the code, then copies a book both ways through the server, as the phone app does. */
    @Test fun phonePairsAndSyncsAFolder() {
        val home = Files.createTempDirectory("link").toFile()
        val books = File(home, "Audiobooks").apply { mkdirs() }
        File(books, "Dune").mkdirs(); File(books, "Dune/01.mp3").writeBytes(ByteArray(1_500_000) { (it % 7).toByte() })
        val scope = CoroutineScope(Dispatchers.IO)
        val library = LibraryStore(File(home, "library.json"), scope).apply { setFolders(listOf(books.absolutePath)) }
        var synced = 0
        val link = DeviceLink(scope, library, onSynced = { synced++ }, file = File(home, "link.json"))
        try {
            val code = link.startPairing()
            fun session(block: (LinkChannel) -> Unit) = Socket("127.0.0.1", code.port).use { socket ->
                val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), code.key)
                channel.sendJson(JSONObject().put("op", "hello").put("device", "phone-1").put("name", "Pixel"))
                assertEquals(link.state.value.deviceId, channel.receiveJson().getString("device"))
                block(channel)
                channel.sendJson(JSONObject().put("op", "done")); channel.receive()
            }
            session { channel ->
                channel.sendJson(JSONObject().put("op", "folders"))
                val folder = channel.receiveJson().getJSONArray("folders").getJSONObject(0)
                assertFalse(folder.getBoolean("shared"))
                // Not shared yet: refused.
                channel.sendJson(JSONObject().put("op", "list").put("folder", folder.getString("id")))
                assertFailsWith<LinkException> { channel.receiveJson() }
            }
            assertEquals("Pixel", link.justLinked.value?.name)
            // Another key (an old code, or a stranger on the network) gets nowhere.
            assertFailsWith<IOException> {
                Socket("127.0.0.1", code.port).use { socket ->
                    val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), LinkChannel.newKey())
                    channel.sendJson(JSONObject().put("op", "hello").put("device", "x")); channel.receiveJson()
                }
            }
            assertNull(link.pairing.value)
            link.setShared(listOf(books.absolutePath)); link.folderPromptAnswered()

            val id = DeviceLink.folderId(books.absolutePath)
            session { channel ->
                channel.sendJson(JSONObject().put("op", "list").put("folder", id))
                assertEquals(1, channel.receiveJson().getInt("pages"))
                val files = channel.receiveJson().getJSONArray("files")
                assertEquals("Dune/01.mp3", files.getJSONObject(0).getString("path"))

                channel.sendJson(JSONObject().put("op", "get").put("folder", id).put("path", "Dune/01.mp3"))
                val size = channel.receiveJson().getLong("size")
                val copy = ByteArrayOutputStream(); channel.receiveStream(copy, size)
                assertContentEquals(File(books, "Dune/01.mp3").readBytes(), copy.toByteArray())

                val upload = ByteArray(300_000) { 3 }
                channel.sendJson(JSONObject().put("op", "put").put("folder", id).put("path", "Hobbit/Hobbit.readalong-progress").put("size", upload.size))
                channel.receiveJson()
                channel.sendStream(upload.inputStream(), upload.size.toLong())
                assertEquals(upload.size.toLong(), channel.receiveJson().getLong("size"))

                channel.sendJson(JSONObject().put("op", "get").put("folder", id).put("path", "../link.json"))
                assertFailsWith<LinkException> { channel.receiveJson() }
            }
            assertContentEquals(ByteArray(300_000) { 3 }, File(books, "Hobbit/Hobbit.readalong-progress").readBytes())
            assertTrue(File(books, "Hobbit").listFiles()!!.none { it.name.startsWith(".") })
            Thread.sleep(300)
            assertEquals(1, synced)
            // The link survives a restart.
            assertEquals(listOf("Pixel"), DeviceLink(scope, library, {}, File(home, "link.json")).state.value.devices.map { it.name })
        } finally { link.stop() }
    }
}

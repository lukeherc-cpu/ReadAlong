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
        assertEquals(listOf(SyncAction.Download("Dune/Dune.readalong-progress")), FolderSyncPlan.plan(local, later, record).filter { it.path.startsWith("Dune") })
        // Audio changed on both sides is left alone.
        val bothAudio = FolderSyncPlan.plan(listOf(SyncFile("a.mp3", 1, 9)), listOf(SyncFile("a.mp3", 2, 9)), mapOf("a.mp3" to SyncedState(3, 1, 3, 1)))
        assertTrue(bothAudio.isEmpty())
        // ...and named, so the person can choose.
        assertEquals(listOf("a.mp3"), FolderSyncPlan.conflicts(listOf(SyncFile("a.mp3", 1, 9)), listOf(SyncFile("a.mp3", 2, 9)), mapOf("a.mp3" to SyncedState(3, 1, 3, 1))))
        // Two state files of the same size at the first sync aren't assumed equal: the newer one wins.
        assertEquals(listOf(SyncAction.Download("Dune/Dune.readalong-state")),
            FolderSyncPlan.plan(listOf(SyncFile("Dune/Dune.readalong-state", 412, day)), listOf(SyncFile("Dune/Dune.readalong-state", 412, 3 * day)), emptyMap()))
        assertEquals(record, FolderSyncPlan.decode(FolderSyncPlan.encode(record)))
    }

    /** A phone asks for a book's ReadAlong by its first audio file, and reads how the computer is getting on. */
    @Test fun phoneQueuesABookOnTheComputer() {
        val home = Files.createTempDirectory("link").toFile()
        val books = File(home, "Audiobooks").apply { mkdirs() }
        val audio = File(books, "Dune/01.mp3").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        val scope = CoroutineScope(Dispatchers.IO)
        val library = LibraryStore(File(home, "library.json"), scope).apply { setFolders(listOf(books.absolutePath)) }
        val asked = mutableListOf<Triple<File, Boolean, String>>()
        val id = DeviceLink.folderId(books.absolutePath)
        val link = DeviceLink(scope, library, onSynced = {},
            transcribe = { file, make, phone -> asked += Triple(file, make, phone); if (file.isFile) ComputerJob.QUEUED else ComputerJob.MISSING },
            // One in a shared folder, one elsewhere: only the first is the phone's business.
            jobs = { listOf(audio to ComputerJob("", "", ComputerJob.WORKING, 3, 12, "base.en"), File(home, "Private/x.mp3") to ComputerJob("", "", ComputerJob.QUEUED)) },
            file = File(home, "link.json"))
        try {
            val code = link.startPairing()
            fun session(block: (LinkChannel) -> Unit) = Socket("127.0.0.1", code.port).use { socket ->
                val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), code.key)
                channel.sendJson(JSONObject().put("op", "hello").put("device", "phone-1").put("name", "Pixel"))
                assertTrue(LinkChannel.TRANSCRIBE in LinkChannel.peerOps(channel.receiveJson()))
                block(channel)
                channel.sendJson(JSONObject().put("op", "done")); channel.receive()
            }
            fun ask(channel: LinkChannel, path: String, remove: Boolean = false): String {
                channel.sendJson(JSONObject().put("op", LinkChannel.TRANSCRIBE).put("folder", id).put("files", org.json.JSONArray(listOf(path))).put("remove", remove))
                return channel.receiveJson().getString("state")
            }
            // A folder that isn't shared is refused, like any other request for it.
            session { channel -> assertFailsWith<LinkException> { ask(channel, "Dune/01.mp3") } }
            link.setShared(listOf(books.absolutePath)); link.folderPromptAnswered()
            session { channel ->
                assertEquals(ComputerJob.QUEUED, ask(channel, "Dune/01.mp3"))
                assertEquals(ComputerJob.MISSING, ask(channel, "Hobbit/01.mp3"))
                assertFailsWith<LinkException> { ask(channel, "../outside.mp3") }
                ask(channel, "Dune/01.mp3", remove = true)
                channel.sendJson(JSONObject().put("op", LinkChannel.JOBS))
                val jobs = channel.receiveJson().getJSONArray("jobs")
                assertEquals(1, jobs.length())
                val job = ComputerJob.fromJson(jobs.getJSONObject(0))
                assertEquals(ComputerJob(id, "Dune/01.mp3", ComputerJob.WORKING, 3, 12, "base.en"), job)
                assertEquals(25, job.percent)
                assertTrue(job.pending)
            }
            assertEquals(listOf(Triple(audio, true, "Pixel"), Triple(File(books, "Hobbit/01.mp3"), true, "Pixel"), Triple(audio, false, "Pixel")),
                asked.map { Triple(it.first.canonicalFile, it.second, it.third) }.map { Triple(File(it.first.path), it.second, it.third) })
        } finally { link.stop() }
    }

    /** A copy that was cut off carries on from where it stopped, and the checksum that follows covers the whole file. */
    @Test fun aCutOffCopyCarriesOnAndIsVerified() {
        val home = Files.createTempDirectory("link").toFile()
        val books = File(home, "Audiobooks").apply { mkdirs() }
        val bytes = ByteArray(900_000) { (it * 31 % 251).toByte() }
        File(books, "Dune").mkdirs(); File(books, "Dune/01.mp3").writeBytes(bytes)
        val scope = CoroutineScope(Dispatchers.IO)
        val library = LibraryStore(File(home, "library.json"), scope).apply { setFolders(listOf(books.absolutePath)) }
        val link = DeviceLink(scope, library, onSynced = {}, file = File(home, "link.json"))
        try {
            val code = link.startPairing()
            val id = DeviceLink.folderId(books.absolutePath)
            fun sha(data: ByteArray) = LinkChannel.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data))
            Socket("127.0.0.1", code.port).use { socket ->
                val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), code.key)
                channel.sendJson(JSONObject().put("op", "hello").put("device", "phone-1").put("name", "Pixel"))
                assertTrue(LinkChannel.RESUME in LinkChannel.peerOps(channel.receiveJson()))
                link.setShared(listOf(books.absolutePath))
                fun get(offset: Long?, verify: Boolean): Triple<Long, ByteArray, String?> {
                    channel.sendJson(JSONObject().put("op", "get").put("folder", id).put("path", "Dune/01.mp3").apply { offset?.let { put("offset", it) }; if (verify) put("verify", true) })
                    val head = channel.receiveJson()
                    val from = head.optLong("offset", -1)
                    val sink = ByteArrayOutputStream(); channel.receiveStream(sink, head.getLong("size") - maxOf(from, 0))
                    return Triple(from, sink.toByteArray(), if (verify) channel.receiveJson().getString("sha256") else null)
                }
                // As phones before 0.54 ask: the whole file, nothing after it.
                assertContentEquals(bytes, get(null, false).second)
                // Carrying on from 300 000 bytes: only the rest is sent; the checksum is of all of it.
                val (from, rest, hash) = get(300_000, true)
                assertEquals(300_000, from)
                assertContentEquals(bytes.copyOfRange(300_000, bytes.size), rest)
                assertEquals(sha(bytes), hash)
                // An offset that makes no sense (the file shrank) starts again from the beginning.
                val (again, all, whole) = get(5_000_000, true)
                assertEquals(0, again)
                assertContentEquals(bytes, all)
                assertEquals(sha(bytes), whole)
                channel.sendJson(JSONObject().put("op", "done")); channel.receive()
            }
        } finally { link.stop() }
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
            assertEquals(listOf("Pixel"), DeviceLink(scope, library, {}, file = File(home, "link.json")).state.value.devices.map { it.name })
        } finally { link.stop() }
    }

    /** "Sync to phone" and a finished ReadAlong tell the phone, at its next check, that there is something new. */
    @Test fun computerSaysWhenItHasSomethingNew() {
        val home = Files.createTempDirectory("link").toFile()
        val books = File(home, "Audiobooks").apply { mkdirs() }
        val scope = CoroutineScope(Dispatchers.IO)
        val library = LibraryStore(File(home, "library.json"), scope).apply { setFolders(listOf(books.absolutePath)) }
        val link = DeviceLink(scope, library, onSynced = {}, file = File(home, "link.json"))
        try {
            val code = link.startPairing()
            fun session(block: (LinkChannel) -> Unit = {}): Long = Socket("127.0.0.1", code.port).use { socket ->
                val channel = LinkChannel.connect(socket.getInputStream(), socket.getOutputStream(), code.key)
                channel.sendJson(JSONObject().put("op", "hello").put("device", "phone-1").put("name", "Pixel"))
                val changed = channel.receiveJson().getLong("changed")
                block(channel)
                channel.sendJson(JSONObject().put("op", "done")); channel.receive()
                changed
            }
            val atStart = session()
            assertTrue(atStart > 0)
            // Nothing new: the same answer.
            assertEquals(atStart, session())
            link.requestSync()
            assertNotNull(link.syncRequested.value)
            val asked = session()
            assertTrue(asked > atStart)
            // Only checking whether the computer is there doesn't answer the request; a sync does.
            assertNotNull(link.syncRequested.value)
            link.setShared(listOf(books.absolutePath))
            session { channel ->
                channel.sendJson(JSONObject().put("op", "list").put("folder", DeviceLink.folderId(books.absolutePath)))
                val pages = channel.receiveJson().getInt("pages")
                repeat(pages) { channel.receiveJson() }
            }
            // The computer finishes the session just after answering "done".
            val until = System.currentTimeMillis() + 3_000
            while (link.syncRequested.value != null && System.currentTimeMillis() < until) Thread.sleep(20)
            assertNull(link.syncRequested.value)
            link.changed()
            assertTrue(session() > asked)
        } finally { link.stop() }
    }

    /** Copies the phone cut short (everything after the last full 256 KB frame was lost) are copied again, once. */
    @Test fun shortCopiesFromOlderPhonesAreFetchedAgain() {
        val frame = LinkChannel.FRAME_DATA
        val pkg = "Glass Castle/Glass Castle.readalong"; val progress = "Dune/Dune.readalong-state"; val audio = "Dune/01.mp3"; val same = "Dune/02.mp3"
        val local = listOf(SyncFile(pkg, 3 * frame, 10), SyncFile(progress, 0, 10), SyncFile(audio, 40 * frame, 10), SyncFile(same, 500, 10))
        val remote = listOf(SyncFile(pkg, 3 * frame + 9_000, 20), SyncFile(progress, 700, 20), SyncFile(audio, 40 * frame + 5, 20), SyncFile(same, 500, 20))
        val record = (local zip remote).associate { (l, r) -> l.path to SyncedState(l.size, l.modified, r.size, r.modified) }
        assertEquals(setOf(SyncAction.Download(pkg), SyncAction.Download(progress), SyncAction.Download(audio)), FolderSyncPlan.plan(local, remote, record).toSet())
        // Once whole, nothing more to do.
        val fixed = remote.map { it.copy(modified = 30) }
        assertTrue(FolderSyncPlan.plan(fixed, remote, FolderSyncPlan.settled(fixed, remote, record)).isEmpty())
        // A phone file that is simply different (not the computer's cut at a frame) is left to the usual rules.
        assertFalse(FolderSyncPlan.shortCopy(SyncedState(frame + 1, 1, 2 * frame, 1)))
    }
}

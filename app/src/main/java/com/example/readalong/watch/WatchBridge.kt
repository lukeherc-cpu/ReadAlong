package com.example.readalong.watch

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.graphics.toArgb
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.model.LibrarySection
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The watch app's view of the phone's player, sent over the Wearable data layer as one data item
 * ([PATH_STATE]): book, chapter, position, speed, sleep timer, colours, cover, chapters and the Started
 * books. The watch sends buttons back as messages (see [WatchListenerService]).
 */
object WatchBridge {
    const val PATH_STATE = "/readalong/state"
    const val PATH_COMMAND = "/readalong/command/"
    private val lock = Mutex()
    private var lastCover: Pair<String, Asset?>? = null

    /** Sends the state at once on play, pause, a new book or chapter, speed or sleep, and every 30 seconds while playing. */
    fun follow(app: ReadAlongApplication) {
        app.applicationScope.launch {
            // No watch paired, or no Play services: nothing to do, ever.
            val paired = runCatching { withContext(Dispatchers.IO) { Tasks.await(Wearable.getNodeClient(app).connectedNodes).isNotEmpty() } }.getOrDefault(false)
            if (!paired) return@launch
            combine(
                app.playbackStore.state.map { s -> listOf(s.bookId, s.isPlaying, s.chapterIndex, s.chapters.size, s.speed, s.sleepEndsAt, s.sleepAtChapterEnd, if (s.isPlaying) s.positionMs / 30_000 else s.positionMs / 1_000) }.distinctUntilChanged(),
                app.appPreferences.preferences.map { it.alwaysBasicColor to it.basicColor }.distinctUntilChanged(),
            ) { _, _ -> }.collect { send(app) }
        }
    }

    suspend fun send(app: ReadAlongApplication) = lock.withLock {
        try { withContext(Dispatchers.IO) { put(app) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    private suspend fun put(app: ReadAlongApplication) {
        val state = app.playbackStore.state.value
        val settings = app.appPreferences.preferences.first()
        val books = app.database.bookDao().observeBooks().first().map { it.toBook() }
        val book = books.firstOrNull { it.id == (state.bookId ?: app.database.bookDao().metadata("current_book")) }
        val live = book != null && state.bookId == book.id
        val palette = book?.let { com.example.readalong.ui.components.coverPaletteFor(app, it, settings.alwaysBasicColor, settings.basicColor) }
            ?: com.example.readalong.ui.theme.basicPalette(settings.basicColor)
        val request = PutDataMapRequest.create(PATH_STATE)
        request.dataMap.apply {
            putLong("stamp", System.currentTimeMillis())
            putString("bookId", book?.id.orEmpty())
            putString("title", book?.title.orEmpty())
            putString("author", book?.author.orEmpty())
            putLong("positionMs", if (live) state.positionMs else book?.positionMs ?: 0)
            putLong("durationMs", (if (live) state.durationMs else 0).takeIf { it > 0 } ?: book?.durationMs ?: 0)
            putBoolean("playing", live && state.isPlaying)
            putFloat("speed", if (live) state.speed else 1f)
            putLong("sleepEndsAt", if (live) state.sleepEndsAt ?: 0 else 0)
            putBoolean("sleepAtChapterEnd", live && state.sleepAtChapterEnd)
            putInt("chapterIndex", if (live) state.chapterIndex else 0)
            putStringArray("chapters", if (live) state.chapters.map { it.title }.toTypedArray() else emptyArray())
            putLongArray("chapterStarts", if (live) state.chapters.map { it.startMs }.toLongArray() else LongArray(0))
            putInt("background", palette.background.toArgb())
            putInt("accent", palette.accent.toArgb())
            val started = books.filter { it.section == LibrarySection.CURRENT && !it.isDemo }.sortedByDescending { it.lastPlayedAt ?: 0 }.take(12)
            putStringArray("startedIds", started.map { it.id }.toTypedArray())
            putStringArray("startedTitles", started.map { it.title }.toTypedArray())
            cover(app, book)?.let { putAsset("cover", it) }
        }
        Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
    }

    private fun cover(context: Context, book: Book?): Asset? {
        val key = book?.id + "|" + book?.coverUri
        lastCover?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = book?.coverUri?.let { com.example.readalong.ui.widget.Widgets.loadCover(context, it, 200) }
        val asset = bitmap?.let { b -> java.io.ByteArrayOutputStream().use { out -> b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out); Asset.createFromBytes(out.toByteArray()) } }
        lastCover = key to asset
        return asset
    }

    /** A button pressed on the watch. Paths are [PATH_COMMAND] plus toggle, back, forward, speed, sleep, chapter, book, volume or hello. */
    suspend fun handle(app: ReadAlongApplication, command: String, data: String) {
        val playback = app.playback
        val settings = app.appPreferences.preferences.first()
        suspend fun ready(bookId: String? = null): Boolean {
            val target = bookId ?: playback.state.value.bookId ?: app.database.bookDao().metadata("current_book") ?: return false
            if (playback.state.value.bookId == target) return true
            val book = app.bookRepository.observeBook(target).first() ?: return false
            withContext(Dispatchers.Main) { playback.select(book) }
            return withTimeoutOrNull(6_000) { playback.state.first { it.bookId == target && !it.isLoading } } != null
        }
        when (command) {
            "toggle" -> if (ready()) withContext(Dispatchers.Main) { if (playback.state.value.isPlaying) playback.pause() else playback.play() }
            "back" -> if (ready()) withContext(Dispatchers.Main) { playback.skip(-settings.playerBack * 1000L) }
            "forward" -> if (ready()) withContext(Dispatchers.Main) { playback.skip(settings.playerForward * 1000L) }
            "speed" -> data.toFloatOrNull()?.let { if (ready()) withContext(Dispatchers.Main) { playback.speed(com.example.readalong.domain.model.roundSpeed(it)) } }
            "sleep" -> data.toIntOrNull()?.let { minutes -> if (ready()) withContext(Dispatchers.Main) { if (minutes < 0) playback.sleepAtChapterEnd() else playback.sleep(minutes.takeIf { it > 0 }) } }
            "chapter" -> data.toIntOrNull()?.let { if (ready()) withContext(Dispatchers.Main) { playback.chapter(it) } }
            "book" -> if (data.isNotBlank() && ready(data)) withContext(Dispatchers.Main) { playback.play() }
            "volume" -> (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (data == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            "hello" -> Unit
        }
        send(app)
    }
}

/** Receives the watch's buttons, and a "hello" when the watch app opens so it gets the state at once. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith(WatchBridge.PATH_COMMAND)) return
        val app = application as? ReadAlongApplication ?: return
        // The listener runs on a background thread; finish the command before it returns, but never hang.
        runBlocking { withTimeoutOrNull(9_000) { WatchBridge.handle(app, event.path.removePrefix(WatchBridge.PATH_COMMAND), String(event.data)) } }
    }
}

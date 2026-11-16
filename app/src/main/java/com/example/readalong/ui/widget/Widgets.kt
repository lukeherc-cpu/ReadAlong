package com.example.readalong.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.example.readalong.R
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.CoverPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Home screen widgets. Each one is drawn by [Widgets.update]; buttons go through [WidgetActionReceiver]. */
abstract class ReadAlongWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = Widgets.refresh(context)
}
class CoverWidget : ReadAlongWidgetProvider()
class ContinueWidget : ReadAlongWidgetProvider()
class NowPlayingWidget : ReadAlongWidgetProvider()
class StripWidget : ReadAlongWidgetProvider()
class ReadAlongTextWidget : ReadAlongWidgetProvider()

object Widgets {
    private val lock = Mutex()
    private data class CoverKey(val bookId: String, val coverUri: String?, val basic: Boolean, val basicColor: Long)
    private var cached: Pair<CoverKey, Pair<CoverPalette, Bitmap?>>? = null

    fun refresh(context: Context) {
        val app = context.applicationContext as? ReadAlongApplication ?: return
        app.applicationScope.launch { update(app) }
    }

    /** Keeps widgets current: at once for play, pause, a new book or chapter, and about every 15 seconds while playing. */
    fun follow(app: ReadAlongApplication) {
        app.applicationScope.launch {
            combine(
                app.playbackStore.state.map { s -> listOf(s.bookId, s.isPlaying, s.chapterIndex, s.sleepEndsAt != null || s.sleepAtChapterEnd, if (s.isPlaying) s.positionMs / 15_000 else null) }.distinctUntilChanged(),
                app.appPreferences.preferences.map { it.alwaysBasicColor to it.basicColor }.distinctUntilChanged(),
                app.database.bookDao().observeBooks().map { list -> list.map { it.toBook() }.filter(::started).sortedByDescending { it.lastPlayedAt ?: 0 }.take(6).map { it.id to it.coverUri } }.distinctUntilChanged(),
            ) { _, _, _ -> }.collect {
                try { update(app) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }

    private fun started(book: Book) = !book.isFinished && book.positionMs > 0 && !book.isDemo

    val kinds: List<Class<out ReadAlongWidgetProvider>> = listOf(CoverWidget::class.java, ContinueWidget::class.java, NowPlayingWidget::class.java, StripWidget::class.java, ReadAlongTextWidget::class.java)

    suspend fun update(app: ReadAlongApplication) = lock.withLock {
        val manager = AppWidgetManager.getInstance(app)
        val placed = kinds.associateWith { manager.getAppWidgetIds(ComponentName(app, it)) }.filterValues { it.isNotEmpty() }
        if (placed.isEmpty()) return@withLock
        build(app, placed.keys).forEach { (kind, views) -> manager.updateAppWidget(placed.getValue(kind), views) }
    }

    /** Draws each kind of widget in [wanted] for the book being played (or last played). */
    suspend fun build(app: ReadAlongApplication, wanted: Set<Class<out ReadAlongWidgetProvider>>): Map<Class<out ReadAlongWidgetProvider>, RemoteViews> {
        val out = LinkedHashMap<Class<out ReadAlongWidgetProvider>, RemoteViews>()
        val state = app.playbackStore.state.value
        val settings = app.appPreferences.preferences.first()
        val books = app.database.bookDao().observeBooks().first().map { it.toBook() }
        val currentId = state.bookId ?: app.database.bookDao().metadata("current_book")
        val book = books.firstOrNull { it.id == currentId }
        val live = book != null && state.bookId == book.id
        val (palette, cover) = book?.let { look(app, it, settings.alwaysBasicColor, settings.basicColor) }
            ?: (com.example.readalong.ui.theme.basicPalette(settings.basicColor) to null)
        val position = if (live) state.positionMs else book?.positionMs ?: 0
        val duration = (if (live) state.durationMs else 0).takeIf { it > 0 } ?: book?.durationMs ?: 0
        val progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        val playing = live && state.isPlaying
        val title = book?.title ?: app.getString(R.string.widget_nothing_playing)
        val chapter = if (live && state.chapters.isNotEmpty()) state.chapterTitle else book?.author.orEmpty()
        val left = if (duration > position && position > 0) " · ${timeLeft(duration - position)} left" else ""
        val sleeping = live && (state.sleepEndsAt != null || state.sleepAtChapterEnd)
        val open = PendingIntent.getActivity(app, 0, com.example.readalong.ui.launch.LaunchAppearance.intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun RemoteViews.tint(base: CoverPalette) {
            if (Build.VERSION.SDK_INT >= 31) setColorStateList(android.R.id.background, "setBackgroundTintList", ColorStateList.valueOf(base.background.toArgb()))
        }
        fun RemoteViews.controls(withSleep: Boolean) {
            setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            setInt(R.id.widget_play_bg, "setColorFilter", palette.accent.toArgb())
            setInt(R.id.widget_play, "setColorFilter", palette.background.toArgb())
            setContentDescription(R.id.widget_play, if (playing) "Pause" else "Play")
            setOnClickPendingIntent(R.id.widget_play, action(app, WidgetActionReceiver.TOGGLE))
            setOnClickPendingIntent(R.id.widget_back, action(app, WidgetActionReceiver.BACK))
            setOnClickPendingIntent(R.id.widget_forward, action(app, WidgetActionReceiver.FORWARD))
            setContentDescription(R.id.widget_back, "Rewind ${settings.playerBack} seconds")
            setContentDescription(R.id.widget_forward, "Forward ${settings.playerForward} seconds")
            if (withSleep) {
                setImageViewResource(R.id.widget_sleep, if (sleeping) R.drawable.ic_sleep_filled else R.drawable.ic_sleep)
                setContentDescription(R.id.widget_sleep, if (sleeping) "Turn off sleep timer" else app.getString(R.string.widget_sleep))
                setOnClickPendingIntent(R.id.widget_sleep, action(app, WidgetActionReceiver.SLEEP))
            }
        }
        fun RemoteViews.coverImage() {
            if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewBitmap(R.id.widget_cover, placeholder(title, palette, 240))
            setOnClickPendingIntent(R.id.widget_cover, open)
        }
        fun RemoteViews.progressLine() {
            setProgressBar(R.id.widget_progress, 1000, progress, false)
            if (Build.VERSION.SDK_INT >= 31) setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(palette.accent.toArgb()))
        }

        if (CoverWidget::class.java in wanted) run {
            val views = RemoteViews(app.packageName, R.layout.widget_cover).apply { coverImage(); progressLine(); controls(withSleep = false) }
            out[CoverWidget::class.java] = views
        }
        if (NowPlayingWidget::class.java in wanted) run {
            val views = RemoteViews(app.packageName, R.layout.widget_now_playing).apply {
                tint(palette); coverImage(); progressLine(); controls(withSleep = true)
                setTextViewText(R.id.widget_title, title); setTextViewText(R.id.widget_subtitle, chapter + left)
            }
            out[NowPlayingWidget::class.java] = views
        }
        if (StripWidget::class.java in wanted) run {
            val views = RemoteViews(app.packageName, R.layout.widget_strip).apply {
                tint(palette); coverImage(); controls(withSleep = false)
                setTextViewText(R.id.widget_title, title); setTextViewText(R.id.widget_subtitle, chapter)
            }
            out[StripWidget::class.java] = views
        }
        if (ReadAlongTextWidget::class.java in wanted) run {
            val sentence = book?.let { b ->
                runCatching { app.database.readingDao().heardAt(b.id, position) ?: app.database.readingDao().paragraphAt(b.id, position) }.getOrNull()?.text
            } ?: if (book == null) "" else "Transcribe this book to read along here."
            val views = RemoteViews(app.packageName, R.layout.widget_readalong).apply {
                setTextViewText(R.id.widget_title, listOfNotNull(book?.title, chapter.takeIf { it.isNotBlank() }).joinToString(" · "))
                setTextViewText(R.id.widget_sentence, sentence.trim())
                setOnClickPendingIntent(android.R.id.background, open)
            }
            out[ReadAlongTextWidget::class.java] = views
        }
        if (ContinueWidget::class.java in wanted) run {
            val recent = books.filter(::started).sortedByDescending { it.lastPlayedAt ?: 0 }.take(6)
            val slots = listOf(R.id.widget_book_0, R.id.widget_book_1, R.id.widget_book_2, R.id.widget_book_3, R.id.widget_book_4, R.id.widget_book_5)
            val views = RemoteViews(app.packageName, R.layout.widget_continue).apply {
                setViewVisibility(R.id.widget_empty, if (recent.isEmpty()) View.VISIBLE else View.GONE)
                slots.forEachIndexed { index, slot ->
                    val item = recent.getOrNull(index)
                    if (item == null) { setViewVisibility(slot, if (recent.isEmpty()) View.GONE else View.INVISIBLE); return@forEachIndexed }
                    setViewVisibility(slot, View.VISIBLE)
                    val art = item.coverUri?.let { loadCover(app, it, 200) }
                        ?: placeholder(item.title, com.example.readalong.ui.components.coverPaletteFor(app, item, settings.alwaysBasicColor, settings.basicColor), 200)
                    setImageViewBitmap(slot, art)
                    setContentDescription(slot, "Play ${item.title}")
                    setOnClickPendingIntent(slot, action(app, WidgetActionReceiver.PLAY_BOOK, item.id, index))
                }
                setOnClickPendingIntent(R.id.widget_heading, open)
            }
            out[ContinueWidget::class.java] = views
        }
        return out
    }

    private suspend fun look(app: ReadAlongApplication, book: Book, basic: Boolean, basicColor: Long): Pair<CoverPalette, Bitmap?> = withContext(Dispatchers.IO) {
        val key = CoverKey(book.id, book.coverUri, basic, basicColor)
        cached?.takeIf { it.first == key }?.let { return@withContext it.second }
        val value = com.example.readalong.ui.components.coverPaletteFor(app, book, basic, basicColor) to book.coverUri?.let { loadCover(app, it, 300) }
        cached = key to value
        value
    }

    internal fun loadCover(context: Context, uri: String, size: Int): Bitmap? = runCatching {
        if (!uri.startsWith("content://") && !uri.startsWith("android.resource://")) return@runCatching null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) }
        var sample = 1
        while (options.outWidth / (sample * 2) >= size && options.outHeight / (sample * 2) >= size) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample
        val bitmap = context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
        // Square and small, as RemoteViews copies every bitmap to the launcher.
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        Bitmap.createScaledBitmap(square, minOf(size, side), minOf(size, side), true)
    }.getOrNull()

    /** A plain cover in the book's colours with its title, for books without artwork. */
    private fun placeholder(title: String, palette: CoverPalette, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.preview.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent.toArgb(); textSize = size / 9f; isFakeBoldText = true }
        val words = title.split(' ').fold(mutableListOf<String>()) { lines, word ->
            if (lines.isNotEmpty() && paint.measureText(lines.last() + " " + word) < size * .8f) lines[lines.lastIndex] = lines.last() + " " + word else lines += word
            lines
        }.take(4)
        words.forEachIndexed { index, line -> canvas.drawText(line, size * .1f, size * .22f + index * paint.textSize * 1.2f, paint) }
        return bitmap
    }

    private fun timeLeft(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    private fun action(context: Context, name: String, bookId: String? = null, slot: Int = 0): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(name).apply { bookId?.let { putExtra(WidgetActionReceiver.BOOK, it) } }
        val code = WidgetActionReceiver.ACTIONS.indexOf(name) * 10 + slot
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** A widget button: play or pause, skip, sleep, or play a book from Continue listening. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ReadAlongApplication ?: return
        val pending = goAsync()
        app.applicationScope.launch {
            try { withTimeout(9_000) { act(app, intent.action, intent.getStringExtra(BOOK)) } }
            catch (cancelled: CancellationException) { }
            catch (_: Exception) { android.util.Log.w("ReadAlong", "A widget button couldn't reach the player.") }
            finally { pending.finish() }
        }
    }

    private suspend fun act(app: ReadAlongApplication, action: String?, bookId: String?) {
        val playback = app.playback
        val settings = app.appPreferences.preferences.first()
        when (action) {
            TOGGLE -> if (choose(app, null)) withContext(Dispatchers.Main) { if (playback.state.value.isPlaying) playback.pause() else playback.play() }
            PLAY_BOOK -> if (choose(app, bookId)) withContext(Dispatchers.Main) { playback.play() }
            BACK -> if (choose(app, null)) withContext(Dispatchers.Main) { playback.skip(-settings.playerBack * 1000L) }
            FORWARD -> if (choose(app, null)) withContext(Dispatchers.Main) { playback.skip(settings.playerForward * 1000L) }
            SLEEP -> if (choose(app, null)) withContext(Dispatchers.Main) {
                val state = playback.state.value
                playback.sleep(if (state.sleepEndsAt != null || state.sleepAtChapterEnd) null else 30)
            }
        }
        Widgets.update(app)
    }

    /** Makes [bookId] (or the last book played) the player's book. False when there's no book to play. */
    private suspend fun choose(app: ReadAlongApplication, bookId: String?): Boolean {
        val playback = app.playback
        val target = bookId ?: playback.state.value.bookId ?: app.database.bookDao().metadata("current_book") ?: return false
        if (playback.state.value.bookId == target) return true
        val book = app.bookRepository.observeBook(target).first() ?: return false
        withContext(Dispatchers.Main) { playback.select(book) }
        return withTimeoutOrNull(6_000) { playback.state.first { it.bookId == target && !it.isLoading } } != null
    }

    companion object {
        const val TOGGLE = "com.example.readalong.widget.TOGGLE"
        const val BACK = "com.example.readalong.widget.BACK"
        const val FORWARD = "com.example.readalong.widget.FORWARD"
        const val SLEEP = "com.example.readalong.widget.SLEEP"
        const val PLAY_BOOK = "com.example.readalong.widget.PLAY_BOOK"
        const val BOOK = "bookId"
        val ACTIONS = listOf(TOGGLE, BACK, FORWARD, SLEEP, PLAY_BOOK)
    }
}

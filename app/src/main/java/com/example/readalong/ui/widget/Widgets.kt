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
    override fun onDeleted(context: Context, ids: IntArray) = WidgetBooks.forget(context, ids)
}
class CoverWidget : ReadAlongWidgetProvider()
class ContinueWidget : ReadAlongWidgetProvider()
class NowPlayingWidget : ReadAlongWidgetProvider()
class StripWidget : ReadAlongWidgetProvider()
class ReadAlongTextWidget : ReadAlongWidgetProvider()

object Widgets {
    private val lock = Mutex()
    private data class CoverKey(val bookId: String, val coverUri: String?, val basic: Boolean, val basicColor: Long)
    private val cached = LinkedHashMap<CoverKey, Pair<CoverPalette, Bitmap?>>()

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
                app.database.bookDao().observeBooks().map { list -> list.map { it.toBook() }.filter(::started).map { listOf(it.id, it.coverUri, it.title) }.sortedBy { it[0] } }.distinctUntilChanged(),
            ) { _, _, _ -> }.collect {
                try { update(app) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }

    private fun started(book: Book) = !book.isFinished && book.positionMs > 0 && !book.isDemo

    val kinds: List<Class<out ReadAlongWidgetProvider>> = listOf(CoverWidget::class.java, ContinueWidget::class.java, NowPlayingWidget::class.java, StripWidget::class.java, ReadAlongTextWidget::class.java)

    /** Redraws every placed widget, each for its own book (or what's playing) and at its own size. */
    suspend fun update(app: ReadAlongApplication) = lock.withLock {
        val manager = AppWidgetManager.getInstance(app)
        for (kind in kinds) for (id in manager.getAppWidgetIds(ComponentName(app, kind))) {
            val views = build(app, setOf(kind), WidgetBooks.book(app, id), manager.getAppWidgetOptions(id))[kind] ?: continue
            manager.updateAppWidget(id, views)
        }
    }

    /**
     * Draws each kind of widget in [wanted] for [bookId], or for the book being played (or last played) when
     * that's null or gone. [options] (the widget's size) lets the Read along text fill the widget.
     */
    suspend fun build(app: ReadAlongApplication, wanted: Set<Class<out ReadAlongWidgetProvider>>, bookId: String? = null, options: Bundle? = null): Map<Class<out ReadAlongWidgetProvider>, RemoteViews> {
        val out = LinkedHashMap<Class<out ReadAlongWidgetProvider>, RemoteViews>()
        val state = app.playbackStore.state.value
        val settings = app.appPreferences.preferences.first()
        val books = app.database.bookDao().observeBooks().first().map { it.toBook() }
        val pinned = bookId?.let { id -> books.firstOrNull { it.id == id } }
        val currentId = pinned?.id ?: state.bookId ?: app.database.bookDao().metadata("current_book")
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
        // A widget for one book acts on that book; one that follows playback acts on whatever is playing.
        val target = pinned?.id
        val open = book?.let { openBook(app, it.id) }
            ?: PendingIntent.getActivity(app, 0, com.example.readalong.ui.launch.LaunchAppearance.intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun RemoteViews.tint(base: CoverPalette) {
            if (Build.VERSION.SDK_INT >= 31) setColorStateList(android.R.id.background, "setBackgroundTintList", ColorStateList.valueOf(base.background.toArgb()))
        }
        fun RemoteViews.controls(withSleep: Boolean) {
            setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            setInt(R.id.widget_play_bg, "setColorFilter", palette.accent.toArgb())
            setInt(R.id.widget_play, "setColorFilter", palette.background.toArgb())
            setContentDescription(R.id.widget_play, if (playing) "Pause" else "Play")
            setOnClickPendingIntent(R.id.widget_play, action(app, WidgetActionReceiver.TOGGLE, target))
            setOnClickPendingIntent(R.id.widget_back, action(app, WidgetActionReceiver.BACK, target))
            setOnClickPendingIntent(R.id.widget_forward, action(app, WidgetActionReceiver.FORWARD, target))
            setContentDescription(R.id.widget_back, "Rewind ${settings.playerBack} seconds")
            setContentDescription(R.id.widget_forward, "Forward ${settings.playerForward} seconds")
            if (withSleep) {
                setImageViewResource(R.id.widget_sleep, if (sleeping) R.drawable.ic_sleep_filled else R.drawable.ic_sleep)
                setContentDescription(R.id.widget_sleep, if (sleeping) "Turn off sleep timer" else app.getString(R.string.widget_sleep))
                setOnClickPendingIntent(R.id.widget_sleep, action(app, WidgetActionReceiver.SLEEP, target))
            }
        }
        fun RemoteViews.coverImage() {
            if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewBitmap(R.id.widget_cover, placeholder(title, palette, 240, 240))
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
            // The sentence being heard, then what follows it, so the text fills the widget instead of one line.
            val passage = book?.let { b ->
                runCatching {
                    val dao = app.database.readingDao()
                    dao.heardFrom(b.id, position, 40).map { it.text.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { it to " " }
                        ?: dao.paragraphsFrom(b.id, position, 12).map { it.text.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { it to "\n\n" }
                }.getOrNull()
            }
            val text: CharSequence = when {
                passage != null -> android.text.SpannableStringBuilder().apply {
                    val (parts, gap) = passage
                    append(parts.first())
                    if (parts.size > 1) {
                        val start = length
                        append(gap); append(parts.drop(1).joinToString(gap))
                        setSpan(android.text.style.ForegroundColorSpan(0xFF8A8078.toInt()), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                book == null -> ""
                else -> "Make a ReadAlong for this book to read along here."
            }
            val views = RemoteViews(app.packageName, R.layout.widget_readalong).apply {
                setTextViewText(R.id.widget_title, listOfNotNull(book?.title, chapter.takeIf { it.isNotBlank() }).joinToString(" · "))
                setTextViewText(R.id.widget_sentence, text)
                textLines(app, options)?.let { setInt(R.id.widget_sentence, "setMaxLines", it) }
                setOnClickPendingIntent(android.R.id.background, open)
            }
            out[ReadAlongTextWidget::class.java] = views
        }
        if (ContinueWidget::class.java in wanted) run {
            val shelf = WidgetBooks.shelf(app, books.filter(::started))
            val slots = listOf(R.id.widget_book_0, R.id.widget_book_1, R.id.widget_book_2, R.id.widget_book_3, R.id.widget_book_4, R.id.widget_book_5)
            val views = RemoteViews(app.packageName, R.layout.widget_continue).apply {
                setViewVisibility(R.id.widget_empty, if (shelf.isEmpty()) View.VISIBLE else View.GONE)
                // One shelf for up to three books; the second appears once there's a fourth.
                setViewVisibility(R.id.widget_row_1, if (shelf.size > 3) View.VISIBLE else View.INVISIBLE)
                setViewVisibility(R.id.widget_shelf_1, if (shelf.size > 3) View.VISIBLE else View.INVISIBLE)
                slots.forEachIndexed { index, slot ->
                    val item = shelf.getOrNull(index)
                    if (item == null) { setViewVisibility(slot, View.INVISIBLE); return@forEachIndexed }
                    setViewVisibility(slot, View.VISIBLE)
                    val art = item.coverUri?.let { loadCover(app, it, 360, square = false) }
                        ?: placeholder(item.title, com.example.readalong.ui.components.coverPaletteFor(app, item, settings.alwaysBasicColor, settings.basicColor), 240, 360)
                    setImageViewBitmap(slot, rounded(art))
                    setContentDescription(slot, "Open ${item.title}")
                    setOnClickPendingIntent(slot, openBook(app, item.id))
                }
                setOnClickPendingIntent(R.id.widget_heading, PendingIntent.getActivity(app, 0, com.example.readalong.ui.launch.LaunchAppearance.intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            out[ContinueWidget::class.java] = views
        }
        return out
    }

    /** How many lines of Read along text fit the widget's height, so the last one isn't cut in half. */
    private fun textLines(context: Context, options: Bundle?): Int? {
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: return null
        val scale = context.resources.configuration.fontScale
        val line = 17f * scale * 1.33f + 4f * scale
        val header = 14f * scale + 8f + 28f
        return ((height - header) / line).toInt().coerceAtLeast(1)
    }

    /** Opens ReadAlong on [bookId]'s player. */
    private fun openBook(context: Context, bookId: String): PendingIntent {
        val intent = Intent(context, com.example.readalong.MainActivity::class.java)
            .setData(Uri.parse("readalong-widget://open/" + Uri.encode(bookId)))
            .putExtra(com.example.readalong.MainActivity.OPEN_BOOK, bookId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private suspend fun look(app: ReadAlongApplication, book: Book, basic: Boolean, basicColor: Long): Pair<CoverPalette, Bitmap?> = withContext(Dispatchers.IO) {
        val key = CoverKey(book.id, book.coverUri, basic, basicColor)
        cached[key]?.let { return@withContext it }
        val value = com.example.readalong.ui.components.coverPaletteFor(app, book, basic, basicColor) to book.coverUri?.let { loadCover(app, it, 300) }
        if (cached.size >= 4) cached.remove(cached.keys.first())
        cached[key] = value
        value
    }

    /** A small copy of a cover: cropped square unless [square] is false, when it keeps its own shape. */
    internal fun loadCover(context: Context, uri: String, size: Int, square: Boolean = true): Bitmap? = runCatching {
        if (!uri.startsWith("content://") && !uri.startsWith("android.resource://")) return@runCatching null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) }
        var sample = 1
        while (options.outWidth / (sample * 2) >= size && options.outHeight / (sample * 2) >= size) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample
        val bitmap = context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
        // Small, as RemoteViews copies every bitmap to the launcher.
        if (!square) {
            val scale = minOf(1f, size.toFloat() / maxOf(bitmap.width, bitmap.height))
            return@runCatching Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
        val side = minOf(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        Bitmap.createScaledBitmap(cropped, minOf(size, side), minOf(size, side), true)
    }.getOrNull()

    /** A book on the shelf: softly rounded corners and a darker spine edge. */
    private fun rounded(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = minOf(source.width, source.height) * .035f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = android.graphics.RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
        paint.shader = android.graphics.LinearGradient(0f, 0f, source.width * .06f, 0f, 0x55000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        return out
    }

    /** A plain cover in the book's colours with its title, for books without artwork. */
    private fun placeholder(title: String, palette: CoverPalette, width: Int, height: Int): Bitmap {
        val size = width
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

    /** A button's action, for [bookId] when the widget shows one book. The data keeps each widget's buttons apart. */
    private fun action(context: Context, name: String, bookId: String? = null): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(name)
            .setData(Uri.parse("readalong-widget://action/" + Uri.encode(bookId ?: "playing")))
            .apply { bookId?.let { putExtra(WidgetActionReceiver.BOOK, it) } }
        return PendingIntent.getBroadcast(context, WidgetActionReceiver.ACTIONS.indexOf(name), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** A widget button: play or pause, skip or sleep, for the widget's own book or whatever is playing. */
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
            TOGGLE -> if (choose(app, bookId)) withContext(Dispatchers.Main) { if (playback.state.value.isPlaying) playback.pause() else playback.play() }
            PLAY_BOOK -> if (choose(app, bookId)) withContext(Dispatchers.Main) { playback.play() }
            BACK -> if (choose(app, bookId)) withContext(Dispatchers.Main) { playback.skip(-settings.playerBack * 1000L) }
            FORWARD -> if (choose(app, bookId)) withContext(Dispatchers.Main) { playback.skip(settings.playerForward * 1000L) }
            SLEEP -> if (choose(app, bookId)) withContext(Dispatchers.Main) {
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

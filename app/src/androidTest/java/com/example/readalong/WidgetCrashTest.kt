package com.example.readalong

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.local.BookEntity
import com.example.readalong.data.local.BookProgressEntity
import com.example.readalong.ui.widget.WidgetBooks
import com.example.readalong.ui.widget.WidgetSetupActivity
import com.example.readalong.ui.widget.Widgets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Draws every widget the way a launcher does: with a size, a chosen book, a full shelf, and the picker previews. */
class WidgetCrashTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private val ids = (1..7).map { "widget-test-$it" }

    @Before fun books() = runBlocking {
        val dao = app.database.bookDao()
        dao.insertBooks(ids.mapIndexed { i, id -> BookEntity(id, "Widget test book number ${i + 1} with a long title", "Author $i", null, null, 9_000_000L, 1_000L + i, false, i % 4) })
        dao.insertProgress(ids.mapIndexed { i, id -> BookProgressEntity(id, 60_000L * (i + 1), lastPlayedAt = 5_000L + i) })
    }

    @After fun cleanUp() = runBlocking { ids.forEach { runCatching { app.database.bookDao().removeBook(it) } } }

    private fun options(widthDp: Int, heightDp: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    }

    private fun apply(name: String, views: RemoteViews) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                val shown = views.apply(app, FrameLayout(app))
                shown.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY))
                shown.layout(0, 0, 1000, 700)
            } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw AssertionError("$name failed to draw: $it", it) }
    }

    @Test fun everyWidgetDrawsWithASizeAndAChosenBook() {
        for (book in listOf(null, ids[0])) for (size in listOf(options(320, 120), options(380, 300))) {
            val views = runBlocking { Widgets.build(app, Widgets.kinds.toSet(), book, size) }
            views.forEach { (kind, remote) -> apply("${kind.simpleName} (book $book)", remote) }
        }
    }

    @Test fun pickerPreviewsDraw() {
        listOf(R.layout.widget_cover_preview, R.layout.widget_continue_preview, R.layout.widget_now_playing_preview, R.layout.widget_strip_preview, R.layout.widget_readalong_preview)
            .forEach { apply(app.resources.getResourceEntryName(it), RemoteViews(app.packageName, it)) }
    }

    @Test fun placedWidgetsUpdate() {
        // AppWidgetManager.updateAppWidget runs in the app; a size or bitmap problem throws here, not in the launcher.
        runBlocking { Widgets.update(app) }
        val views = runBlocking { Widgets.build(app, setOf(com.example.readalong.ui.widget.ContinueWidget::class.java)) }
        val parcel = android.os.Parcel.obtain()
        try { views.values.single().writeToParcel(parcel, 0); org.junit.Assert.assertTrue("Bookshelf is ${parcel.dataSize()} bytes", parcel.dataSize() < 1_000_000) } finally { parcel.recycle() }
    }

    @Test fun setupScreenOpensAndChoosesABook() {
        val intent = Intent(app, WidgetSetupActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 4242).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<WidgetSetupActivity>(intent).use { scenario ->
            Thread.sleep(2_000)
            scenario.onActivity { }
        }
        WidgetBooks.set(app, 4242, ids[1])
        org.junit.Assert.assertEquals(ids[1], WidgetBooks.book(app, 4242))
        WidgetBooks.forget(app, intArrayOf(4242))
    }

    /** Places each widget for real (as a launcher does, with the bind permission granted by the check), then updates it. */
    @Test fun realPlacedWidgetsLoadWithoutAnError() {
        val manager = AppWidgetManager.getInstance(app)
        val host = android.appwidget.AppWidgetHost(app, 7341)
        val placed = mutableListOf<Int>()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { host.startListening() }
            for (kind in Widgets.kinds) {
                val id = host.allocateAppWidgetId(); placed += id
                val provider = android.content.ComponentName(app, kind)
                org.junit.Assert.assertTrue("Couldn't bind ${kind.simpleName}; is grantbind set?", manager.bindAppWidgetIdIfAllowed(id, provider))
                if (kind != com.example.readalong.ui.widget.ContinueWidget::class.java && placed.size % 2 == 0) WidgetBooks.set(app, id, ids[2])
                manager.updateAppWidgetOptions(id, options(340, 200))
            }
            runBlocking { Widgets.update(app) }
            Thread.sleep(1_500)
            val errors = mutableListOf<String>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                placed.forEach { id ->
                    val info = manager.getAppWidgetInfo(id)
                    val view = host.createView(app, id, info)
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1000, 600)
                    // A RemoteViews that fails to apply leaves the host's error view: a single TextView.
                    val child = view.getChildAt(0)
                    val text = (child as? android.widget.TextView)?.text?.toString()
                    if (view.childCount == 0 || (child is android.widget.TextView && child.id == android.view.View.NO_ID)) errors += "${info.provider.shortClassName}: ${text ?: "nothing drawn"}"
                }
            }
            org.junit.Assert.assertTrue("Widgets didn't load: $errors", errors.isEmpty())
        } finally {
            placed.forEach { host.deleteAppWidgetId(it) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { host.stopListening() }
        }
    }
}

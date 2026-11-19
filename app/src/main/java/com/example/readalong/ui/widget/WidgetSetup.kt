package com.example.readalong.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.BookCover
import kotlinx.coroutines.flow.map

/** Which book each placed widget shows (none means it follows whatever is playing), and the Bookshelf's order. */
object WidgetBooks {
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun book(context: Context, widgetId: Int): String? = prefs(context).getString("book_$widgetId", null)
    fun set(context: Context, widgetId: Int, bookId: String?) {
        prefs(context).edit().apply { if (bookId == null) remove("book_$widgetId") else putString("book_$widgetId", bookId) }.apply()
    }
    fun forget(context: Context, widgetIds: IntArray) {
        prefs(context).edit().apply { widgetIds.forEach { remove("book_$it") } }.apply()
    }

    /**
     * The Bookshelf's books in a steady order: books keep their place when they're played, a newly
     * started book takes the next free place, and one that's finished (or pushed off by a seventh) leaves.
     */
    fun shelf(context: Context, started: List<Book>): List<Book> {
        val wanted = started.sortedByDescending { it.lastPlayedAt ?: 0 }.take(6)
        val byId = wanted.associateBy { it.id }
        val saved = prefs(context).getString("shelf", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        val order = shelfOrder(saved, wanted.map { it.id })
        if (order != saved) prefs(context).edit().putString("shelf", order.joinToString("\n")).apply()
        return order.mapNotNull { byId[it] }
    }

    /** Keeps [saved]'s order for the books still in [wanted], then adds the new ones, most recent first. */
    internal fun shelfOrder(saved: List<String>, wanted: List<String>): List<String> =
        saved.filter { it in wanted } + wanted.filter { it !in saved }
}

/**
 * Shown when a Cover, Now playing, Mini player or Read along text widget is placed (and from the widget's
 * settings later): pick one book for it, or let it follow whatever is playing. Leaving without a choice keeps
 * the widget, following what's playing.
 */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val done = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_OK, done)
        val app = application as ReadAlongApplication
        setContent {
            val settings by app.appPreferences.preferences.collectAsStateWithLifecycle(null)
            val books by remember { app.bookRepository.observeBooks().map { list -> list.filter { !it.isDemo }.sortedWith(compareBy<Book> { it.isFinished }.thenByDescending { it.lastPlayedAt ?: 0 }.thenBy { it.title.lowercase() }) } }
                .collectAsStateWithLifecycle(null)
            val chosen = remember { WidgetBooks.book(this, widgetId) }
            val preferences = settings ?: return@setContent
            CompositionLocalProvider(com.example.readalong.ui.theme.LocalAppSettings provides preferences) {
                com.example.readalong.ui.theme.ReadAlongTheme(light = preferences.libraryLight) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            Text(stringResource(R.string.widget_setup_title), Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                            LazyColumn(Modifier.fillMaxSize()) {
                                item {
                                    SetupRow(selected = chosen == null, onClick = { choose(app, widgetId, null) }, tag = "widget_follow") {
                                        Column(Modifier.weight(1f)) {
                                            Text(stringResource(R.string.widget_setup_follow), style = MaterialTheme.typography.titleMedium)
                                            Text(stringResource(R.string.widget_setup_follow_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                                }
                                items(books.orEmpty(), key = { it.id }) { book ->
                                    SetupRow(selected = chosen == book.id, onClick = { choose(app, widgetId, book.id) }, tag = "widget_book_${book.id}") {
                                        BookCover(book, Modifier.width(44.dp), showTitle = false)
                                        Column(Modifier.weight(1f)) {
                                            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            book.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(app: ReadAlongApplication, widgetId: Int, bookId: String?) {
        WidgetBooks.set(this, widgetId, bookId)
        Widgets.refresh(app)
        finish()
    }
}

@Composable
private fun SetupRow(selected: Boolean, onClick: () -> Unit, tag: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        content()
        RadioButton(selected = selected, onClick = onClick)
    }
}

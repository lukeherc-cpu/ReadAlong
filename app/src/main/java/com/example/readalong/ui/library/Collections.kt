package com.example.readalong.ui.library

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.readalong.R
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.model.BookCollection
import com.example.readalong.domain.model.CollectionRow
import com.example.readalong.domain.model.collectionSummary
import com.example.readalong.ui.components.BookCover
import com.example.readalong.ui.theme.Corners

/** One collection in the Collections view: up to three of its covers stacked, its name, and how far along its books are. */
@Composable
internal fun CollectionRowView(row: CollectionRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val books = row.books
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Corners.medium)).clickable(onClick = onClick)
        .padding(vertical = 6.dp).testTag("collection_row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(72.dp)) {
            val covers = books.take(3)
            if (covers.isEmpty()) Box(Modifier.size(56.dp).offset(y = 8.dp).clip(RoundedCornerShape(Corners.small))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_bookshelf), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            covers.reversed().forEachIndexed { index, book ->
                val step = covers.size - 1 - index
                BookCover(book, Modifier.size(56.dp).offset(x = (step * 8).dp, y = (16 - step * 8).dp)
                    .graphicsLayer { alpha = 1f - step * .25f }.clip(RoundedCornerShape(Corners.small)), showTitle = false)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(tr(row.name), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tr(if (books.isEmpty()) "No books yet" else collectionSummary(books)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "New collection" as the last row of the Collections view, with a dashed cover where the stack would be. */
@Composable
internal fun NewCollectionRow(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Corners.medium)).clickable(onClick = onClick).padding(vertical = 6.dp).testTag("new_collection"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            val outline = MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(56.dp).offset(y = 4.dp).drawBehind {
                drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }, contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_add), null, tint = ink) }
        }
        Text(tr("New collection"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = ink)
    }
}

/** The heading inside a collection: back, its name and size, and (for a real collection) Rename and Delete. */
@Composable
internal fun CollectionHeader(row: CollectionRow, onBack: () -> Unit, onRename: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("collection_header"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) { Icon(painterResource(R.drawable.ic_back), tr("All collections")) }
        Column(Modifier.weight(1f).offset(x = (-8).dp)) {
            Text(tr(row.name), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr(if (row.books.size == 1) "1 book" else "${row.books.size} books"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRename != null || onDelete != null) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("collection_menu")) { Icon(painterResource(R.drawable.ic_more), tr("Collection options"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                onRename?.let { DropdownMenuItem(text = { Text(tr("Rename")) }, onClick = { menu = false; it() }) }
                onDelete?.let { DropdownMenuItem(text = { Text(tr("Delete collection"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; it() }, modifier = Modifier.testTag("collection_delete")) }
            }
        }
    }
}

/** The Add books tile at the end of a collection, the size of a book. */
@Composable
internal fun AddBooksTile(list: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(Corners.medium)
    val outline = Modifier.clip(shape).border(2.dp, MaterialTheme.colorScheme.outlineVariant, shape).clickable(onClick = onClick).testTag("collection_add_books")
    if (list) Row(Modifier.fillMaxWidth().then(outline).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_add), null, tint = ink); Text(tr("Add books"), color = ink, style = MaterialTheme.typography.titleSmall)
    } else Column(Modifier.fillMaxWidth().aspectRatio(1f).then(outline), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_add), null, tint = ink); Spacer(Modifier.height(6.dp)); Text(tr("Add books"), color = ink, style = MaterialTheme.typography.titleSmall)
    }
}

/** Asks for a collection's name: "New collection" (then books are picked) or "Rename collection". */
@Composable
internal fun CollectionNameDialog(title: String, initial: String = "", confirm: String, message: String? = null, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { Text(tr(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("collection_name"))
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }, modifier = Modifier.testTag("collection_name_save")) { Text(tr(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } })
}

/**
 * Full-screen cover picker for a collection: tap covers to tick them, with search. Books already in
 * the collection start ticked, so unticking one takes it out.
 */
@Composable
internal fun CollectionBookPicker(collection: BookCollection, books: List<Book>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var chosen by rememberSaveable(collection.id) { mutableStateOf(collection.bookIds.filter { id -> books.any { it.id == id } }) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = books.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.author.orEmpty().contains(query.trim(), true) }
    val added = chosen.count { it !in collection.bookIds }
    val removed = collection.bookIds.count { it !in chosen && books.any { b -> b.id == it } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).testTag("collection_picker")) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { com.example.readalong.ui.components.CloseGlyph(MaterialTheme.colorScheme.onSurface, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Add to ${collection.name}"), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("${chosen.size} selected"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("collection_picker_search"),
                    placeholder = { Text(tr("Search your books")) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) })
                LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shown, key = { it.id }) { book ->
                        val on = book.id in chosen
                        Column(Modifier.toggleable(on, role = Role.Checkbox) { chosen = if (it) chosen + book.id else chosen - book.id }.testTag("pick_${book.id}")) {
                            Box {
                                BookCover(book, Modifier.fillMaxWidth().clip(RoundedCornerShape(Corners.small))
                                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Corners.small)) else Modifier), showTitle = false)
                                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.scrim.copy(alpha = .35f))
                                    .border(2.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                                    if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            Text(book.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Button(onClick = { onSave(chosen) }, Modifier.fillMaxWidth().padding(16.dp).height(52.dp).testTag("collection_picker_save")) {
                    Text(tr(when {
                        added > 0 && removed == 0 -> if (added == 1) "Add 1 book" else "Add $added books"
                        else -> "Done"
                    }))
                }
            }
        }
    }
}

/** The Collections part of a book's long-press sheet: tick the collections it belongs in, or make a new one with it. */
@Composable
internal fun BookCollectionsSection(book: Book, collections: List<BookCollection>, onToggle: (BookCollection, Boolean) -> Unit, onNew: () -> Unit) {
    Column(Modifier.testTag("book_collections")) {
        Text(tr("Collections"), Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        collections.sortedBy { it.name.lowercase() }.forEach { collection ->
            val inside = book.id in collection.bookIds
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(inside, role = Role.Checkbox) { onToggle(collection, it) }
                .padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Checkbox(inside, onCheckedChange = null, modifier = Modifier.size(22.dp))
                Text(collection.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        com.example.readalong.ui.components.SheetRow("New collection", onNew, Modifier.testTag("book_new_collection"), icon = R.drawable.ic_add)
    }
}

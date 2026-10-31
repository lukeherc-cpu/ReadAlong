package com.example.readalong.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.PreviewBook
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Add audiobooks: one folder picker for a single book or a whole library. The folder is looked at
 * first, and [AddPreviewDialog] shows what was found before anything is added, so a library can't
 * be added as one giant book by mistake. Returns the launcher; [origin] names the calling screen so
 * only its own preview is shown.
 */
@Composable
fun rememberAddAudiobooks(model: ImportViewModel, origin: String, onAdded: () -> Unit = {}): () -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { model.previewFolder(it, origin) }
    }
    AddPreviewDialog(model, origin, onAdded)
    return { picker.launch(null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPreviewDialog(model: ImportViewModel, origin: String, onAdded: () -> Unit = {}) {
    val preview by model.preview.collectAsStateWithLifecycle()
    val shown = preview?.takeIf { it.origin == origin }?.folder ?: return
    // Keyed by folder: a new choice starts from the new folder's suggestion.
    var mode by remember(shown) { mutableStateOf(shown.suggested) }
    val deleted = if (mode == FolderMode.LIBRARY) shown.deletedBooks else if (shown.deletedAsOneBook) 1 else 0
    val everythingDeleted = mode == FolderMode.LIBRARY && shown.deletedBooks == shown.books.size || mode == FolderMode.ONE_BOOK && shown.deletedAsOneBook
    // Bring back deleted books by default only when nothing else would be added; otherwise ask.
    var restore by remember(shown, mode) { mutableStateOf(everythingDeleted) }

    if (shown.emptyMessage != null) {
        com.example.readalong.ui.components.IllustratedDialog("No audiobooks found", model::dismissPreview, actions = {
            TextButton(onClick = model::dismissPreview) { Text("OK") }
        }) { Text(shown.emptyMessage, style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val count = shown.books.size
    val library = mode == FolderMode.LIBRARY
    val title = if (library) "$count audiobooks found" else "1 audiobook found"
    com.example.readalong.ui.components.IllustratedDialog(title, model::dismissPreview, centered = true, actions = {}) {
        Text("in your \u201c${shown.name}\u201d folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-10).dp))
        if (library) PreviewGrid(shown.books)
        else {
            // One book: a square cover placeholder with its name, like the library's covers.
            val name = if (count == 1) shown.books.first().name else shown.name
            PreviewTile(name, 0, Modifier.size(176.dp), large = true, book = shown.books.firstOrNull { it.cover != null } ?: shown.books.firstOrNull())
            Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (count >= 2) Text("All $count folders' audio is played as one audiobook, in order.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        // Only when something here was deleted earlier, naming what comes back.
        if (deleted > 0) {
            val names = if (library) shown.books.filter { it.deleted }.map { it.name } else listOf(if (count == 1) shown.books.first().name else shown.name)
            val label = when {
                names.size == 1 -> "Bring back ${names[0]} (you deleted it earlier)"
                names.size <= 3 -> "Bring back ${names.dropLast(1).joinToString(", ")} and ${names.last()} (you deleted them earlier)"
                else -> "Bring back the ${names.size} books you deleted earlier"
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(restore, role = Role.Checkbox) { restore = it }.testTag("restore_deleted"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Checkbox(restore, null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(onClick = { model.confirmPreview(mode, restore); onAdded() }, enabled = deleted == 0 || restore || !everythingDeleted,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("confirm_add"), shape = RoundedCornerShape(28.dp)) {
            Text(if (library) "Add $count books" else "Add book", style = MaterialTheme.typography.titleMedium)
        }
        when {
            library -> TextButton(onClick = { mode = FolderMode.ONE_BOOK }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("as_one_book")) { Text("It's really one book") }
            count >= 2 -> TextButton(onClick = { mode = FolderMode.LIBRARY }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("as_library")) { Text("They're $count separate books") }
            else -> TextButton(onClick = model::dismissPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
        if (library) Text("ReadAlong will keep checking this folder for new books.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Square tiles: 5 at first and a "+N" tile that opens out to every book, which pop in row by row; "Less" folds them back. */
@Composable
private fun PreviewGrid(books: List<PreviewBook>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val first = if (books.size <= 6) books.size else 5
    val shown = if (open) books else books.take(first)
    val rest = books.size - first
    val tiles: List<Pair<String, (() -> Unit)?>> = shown.map { (it.name + if (it.deleted) " (deleted earlier)" else "") to null } +
        (if (rest > 0) listOf((if (open) "Show less" else "more") to { open = !open }) else emptyList())
    Column(Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.spring(dampingRatio = .9f, stiffness = 300f)),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(3).forEachIndexed { row, items ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEachIndexed { col, (label, onClick) ->
                    val index = row * 3 + col
                    val more = index >= shown.size
                    val deleted = !more && shown[index].deleted
                    key(if (more) "more-tile" else "book-$index") {
                        // Books revealed by "+N" pop in a row at a time; the first five are already there.
                        val appear = remember { androidx.compose.animation.core.Animatable(if (more || index < first) 1f else 0f) }
                        LaunchedEffect(Unit) {
                            if (appear.value < 1f) {
                                kotlinx.coroutines.delay(60L + 45L * ((index - first) / 3).coerceAtMost(12))
                                appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .7f, stiffness = 420f))
                            }
                        }
                        Column(Modifier.weight(1f).graphicsLayer { alpha = appear.value.coerceIn(0f, 1f); val sc = .8f + .2f * appear.value; scaleX = sc; scaleY = sc }
                                .then(if (onClick != null) Modifier.clickable(onClickLabel = if (open) "Show fewer books" else "Show all ${books.size} books", onClick = onClick) else Modifier)
                                .then(if (more) Modifier.testTag("preview_more") else Modifier),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (more) Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                androidx.compose.animation.AnimatedContent(open, label = "More tile") { isOpen ->
                                    Text(if (isOpen) "Less" else "+$rest", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            } else PreviewTile(shown[index].name, index, Modifier.fillMaxWidth().alpha(if (deleted) .45f else 1f), book = shown[index])
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** At most two covers are read out of audio files at once, so opening a big folder doesn't stall the sheet. */
private val coverReads = kotlinx.coroutines.sync.Semaphore(2)

/** A square placeholder cover in one of the accent's shades, with the title's first letter. */
@Composable
private fun PreviewTile(name: String, index: Int, modifier: Modifier, large: Boolean = false, book: PreviewBook? = null) {
    val colors = MaterialTheme.colorScheme
    val shade = androidx.compose.ui.graphics.lerp(colors.primary, colors.background, listOf(0f, .3f, .15f, .5f, .22f)[index % 5])
    val context = androidx.compose.ui.platform.LocalContext.current
    // The book's own cover: an image in its folder, else the art inside its first audio file.
    val embedded by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, book?.firstAudio, book?.cover) {
        val audio = book?.firstAudio
        if (book?.cover == null && audio != null) value = coverReads.withPermit { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, audio)
                    retriever.embeddedPicture?.let { bytes ->
                        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                    }
                } finally { retriever.release() }
            }.getOrNull()
        } }
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(if (large) 14.dp else 10.dp)).background(shade), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = colors.onPrimary, style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge)
        val picture = embedded
        if (book?.cover != null) coil3.compose.AsyncImage(model = book.cover, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (picture != null) androidx.compose.foundation.Image(picture, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

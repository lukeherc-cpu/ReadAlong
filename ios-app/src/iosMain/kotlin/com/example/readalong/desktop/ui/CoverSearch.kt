// The desktop CoverSearch.kt for iOS: the same internet search and cover menu, with Photos or Files to choose an image.
package com.example.readalong.desktop.ui

import com.example.readalong.compat.*

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.readalong.desktop.core.InternetCovers
import com.example.readalong.desktop.core.LibraryBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Right-click on a cover: the two ways to change it, as on the phone. */
fun coverMenu(app: AppState, book: LibraryBook): List<ContextMenuItem> = listOf(
    ContextMenuItem("Search the internet for a cover…") { app.coverSearch = book.id },
    ContextMenuItem("Choose a cover image from a file…") { chooseCoverFile(app, book) },
)

fun chooseCoverFile(app: AppState, book: LibraryBook) {
    pickImage { file ->
        app.scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                require(InternetCovers.readable(bytes)) { "That file isn't an image ReadAlong can read." }
                setCover(app, book, bytes)
            }.onFailure { app.messages.emit(it.message ?: "That image couldn't be used.") }
        }
    }
}

/**
 * Saves the cover beside the book (so it syncs to the phone) through [AppState.companions], then
 * drops the old image from the cache: the file keeps its name, so the cache wouldn't notice on its own.
 */
private fun setCover(app: AppState, book: LibraryBook, bytes: ByteArray) {
    val previous = book.coverPath
    val saved = app.companions.saveCover(book, bytes)
    previous?.let(Covers::forget); Covers.forget(saved.path)
    app.messages.tryEmit("Cover updated for ${book.title}.")
}

/** Search public audiobook artwork for the book named by [AppState.coverSearch] and pick one. */
@Composable
fun CoverSearchDialog(app: AppState) {
    val id = app.coverSearch ?: return
    val book = app.library.book(id) ?: run { app.coverSearch = null; return }
    val close = { app.coverSearch = null }
    var query by remember(id) { mutableStateOf(listOfNotNull(book.title, book.author).joinToString(" ")) }
    var results by remember(id) { mutableStateOf(emptyList<InternetCovers.Cover>()) }
    var searching by remember(id) { mutableStateOf(false) }
    var saving by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (searching) return
        searching = true; error = null
        scope.launch {
            try { results = InternetCovers.search(query); if (results.isEmpty()) error = "No covers found. Try the book's title and author." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Covers couldn't be searched. Check your connection." }
            finally { searching = false }
        }
    }
    LaunchedEffect(id) { search() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shown = remember(id) { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(shown, enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = .85f, stiffness = 500f)) { it / 12 } + scaleIn(initialScale = .97f)) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 16.dp) {
                Column(Modifier.width(720.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Find a cover", style = MaterialTheme.typography.headlineSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Title and author") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                        PillButton("Search", { search() }, enabled = !searching, icon = "ic_search")
                    }
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        when {
                            searching -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                            error != null && results.isEmpty() -> Text(error!!, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(results, key = { it.url }) { cover ->
                                    CoverResult(cover, busy = saving == cover.url, enabled = saving == null) {
                                        saving = cover.url; error = null
                                        scope.launch {
                                            try {
                                                val bytes = InternetCovers.download(cover.url)
                                                withContext(Dispatchers.IO) { setCover(app, book, bytes) }
                                                close()
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) { error = failure.message ?: "That cover couldn't be downloaded." }
                                            finally { saving = null }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (error != null && results.isNotEmpty()) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("From Apple Books and Open Library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { close(); chooseCoverFile(app, book) }) { Text("Choose a file instead…") }
                        TextButton(onClick = close) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverResult(cover: InternetCovers.Cover, busy: Boolean, enabled: Boolean, onPick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, cover.url) {
        value = runCatching { withContext(Dispatchers.IO) { org.jetbrains.skia.Image.makeFromEncoded(InternetCovers.download(cover.url)).toComposeImageBitmap() } }.getOrNull()
    }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered && enabled) 1f else 0f, spring(stiffness = 400f))
    Column(Modifier.hoverable(interaction).clickable(interaction, null, enabled = enabled, onClick = onPick).pointerHoverIcon(PointerIcon.Hand),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = 1f + .03f * lift; scaleY = 1f + .03f * lift }.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            image?.let { Image(it, cover.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (busy) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
            }
        }
        Text(cover.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(cover.author, cover.source).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

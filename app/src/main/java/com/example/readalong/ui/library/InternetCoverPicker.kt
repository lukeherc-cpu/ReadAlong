package com.example.readalong.ui.library

import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.readalong.data.book.InternetCovers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

@Composable
fun InternetCoverPicker(title: String, onDismiss: () -> Unit, onSave: (Uri) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("$title audiobook cover") }
    var covers by remember { mutableStateOf(emptyList<InternetCovers.Cover>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Bitmap?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableIntStateOf(1) }
    fun search() { if (!busy) scope.launch {
        busy = true; error = null
        try { covers = InternetCovers.search(query); if (covers.isEmpty()) error = "No covers found. Try the book title and author." }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "Couldn't search for covers." }
        finally { busy = false }
    } }
    fun clamp(bitmap: Bitmap, requested: Offset, scale: Float): Offset {
        val base = max(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val x = ((bitmap.width * base * scale - size) / 2).coerceAtLeast(0f)
        val y = ((bitmap.height * base * scale - size) / 2).coerceAtLeast(0f)
        return Offset(requested.x.coerceIn(-x, x), requested.y.coerceIn(-y, y))
    }
    LaunchedEffect(Unit) { search() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(.94f).heightIn(max = 720.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (selected == null) "Cover from internet" else "Crop cover", style = MaterialTheme.typography.titleLarge)
                if (selected == null) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search audiobook covers") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = ::search, enabled = !busy) { Text("Search") }
                    LazyVerticalGrid(GridCells.Adaptive(100.dp), modifier = Modifier.weight(1f, fill = false).heightIn(min = 180.dp, max = 400.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(covers, key = { it.url }) { cover ->
                            Column(Modifier.clickable(enabled = !busy) { scope.launch {
                                busy = true; error = null
                                try { selected = InternetCovers.image(cover.url); zoom = 1f; pan = Offset.Zero }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (e: Exception) { error = e.message ?: "Couldn't load this cover." }
                                finally { busy = false }
                            } }) {
                                CoverThumbnail(cover.url)
                                Text(cover.title, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                                Text(cover.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else selected?.let { bitmap ->
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Color.Black).onSizeChanged { size = it.width; pan = clamp(bitmap, pan, zoom) }
                        .pointerInput(bitmap, size) { detectTransformGestures { _, movement, change, _ ->
                            zoom = (zoom * change).coerceIn(1f, 5f); pan = clamp(bitmap, pan + movement, zoom)
                        } }) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val scale = max(this.size.width / bitmap.width, this.size.height / bitmap.height) * zoom
                            val left = (this.size.width - bitmap.width * scale) / 2 + pan.x
                            val top = (this.size.height - bitmap.height * scale) / 2 + pan.y
                            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bitmap, null,
                                RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale),
                                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
                        }
                    }
                    Text("Pinch to zoom and drag to crop", style = MaterialTheme.typography.bodySmall)
                    Slider(zoom, { zoom = it; pan = clamp(bitmap, pan, zoom) }, valueRange = 1f..5f)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (busy || selected == null) onDismiss() else selected = null }) { Text(if (selected != null) "Back" else "Cancel") }
                    if (selected != null) TextButton(enabled = !busy, onClick = { scope.launch {
                        busy = true; error = null
                        try {
                            val bitmap = requireNotNull(selected)
                            val uri = withContext(Dispatchers.IO) {
                                val output = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                                val scale = max(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height) * zoom
                                val ratio = 1024f / size
                                val left = (size - bitmap.width * scale) / 2 + pan.x
                                val top = (size - bitmap.height * scale) / 2 + pan.y
                                BitmapCanvas(output).apply {
                                    drawColor(android.graphics.Color.WHITE)
                                    drawBitmap(bitmap, null, RectF(left * ratio, top * ratio, (left + bitmap.width * scale) * ratio, (top + bitmap.height * scale) * ratio), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                                }
                                val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "cover-crop-${UUID.randomUUID()}.jpg")
                                try { file.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 94, it)) } } finally { output.recycle() }
                                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            }
                            onSave(uri)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (e: Exception) { error = e.message ?: "Couldn't save the cover." }
                        finally { busy = false }
                    } }) { Text("Use cover") }
                }
            }
        }
    }
}

@Composable private fun CoverThumbnail(url: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { try { bitmap = InternetCovers.image(url) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

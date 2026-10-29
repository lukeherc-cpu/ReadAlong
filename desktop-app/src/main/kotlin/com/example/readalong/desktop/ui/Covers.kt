package com.example.readalong.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.readalong.desktop.core.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Cover images decoded once, scaled to the size shown, and kept in a small cache. */
object Covers {
    private class Entry(val image: ImageBitmap, val palette: CoverPalette?)
    private val cache = object : LinkedHashMap<String, Entry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 160
    }

    fun cached(path: String, size: Int): ImageBitmap? = synchronized(cache) { cache["$path@$size"]?.image }

    suspend fun load(path: String, size: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(path, size)?.let { return@withContext it }
        val source = runCatching { ImageIO.read(File(path)) }.getOrNull() ?: return@withContext decodeWithSkia(path, size)
        val scale = size.toDouble() / maxOf(source.width, source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1); val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(source, 0, 0, w, h, null); dispose()
        }
        val bitmap = scaled.toComposeImageBitmap()
        synchronized(cache) { cache["$path@$size"] = Entry(bitmap, paletteOf(scaled)) }
        bitmap
    }

    private fun decodeWithSkia(path: String, size: Int): ImageBitmap? = runCatching {
        val image = org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes())
        image.toComposeImageBitmap().also { synchronized(cache) { cache["$path@$size"] = Entry(it, null) } }
    }.getOrNull()

    suspend fun palette(path: String): CoverPalette? {
        load(path, 96)
        return synchronized(cache) { cache["$path@96"]?.palette }
    }

    /** The phone's cover sampling: the most common saturated hue, weighted by saturation. */
    private fun paletteOf(image: BufferedImage): CoverPalette {
        val buckets = HashMap<Int, Int>(); val hsb = FloatArray(3)
        val step = maxOf(1, maxOf(image.width, image.height) / 96)
        for (y in 0 until image.height step step) for (x in 0 until image.width step step) {
            val pixel = image.getRGB(x, y)
            java.awt.Color.RGBtoHSB(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255, hsb)
            if ((pixel ushr 24) > 200 && hsb[2] in .12f..0.94f && hsb[1] >= .15f) {
                val key = (hsb[0] * 360 / 15).toInt()
                buckets[key] = (buckets[key] ?: 0) + (1 + hsb[1] * 6).toInt()
            }
        }
        return buckets.maxByOrNull { it.value }?.key?.let { palette(it * 15f + 7.5f, .48f) } ?: palette(0f, 0f)
    }
}

/** A stable colour per title for books without cover art (the phone's placeholderPalette). */
fun placeholderPalette(book: LibraryBook): CoverPalette {
    val hues = listOf(212f, 168f, 24f, 340f, 262f, 42f, 196f, 8f, 140f, 288f)
    return palette(hues[Math.floorMod((book.title.lowercase() + (book.author ?: "")).hashCode(), hues.size)], .40f)
}

@Composable
fun rememberCoverPalette(book: LibraryBook?): CoverPalette {
    val fallback = remember(book?.id, book?.title) { book?.let(::placeholderPalette) ?: palette(212f, .4f) }
    val path = book?.coverPath
    val result by produceState(fallback, path) { value = path?.let { Covers.palette(it) } ?: fallback }
    return result
}

@Composable
fun rememberCover(path: String?, size: Int): ImageBitmap? {
    val image by produceState(path?.let { Covers.cached(it, size) }, path, size) { value = path?.let { Covers.load(it, size) } }
    return image
}

/** The cover, or the phone's title-on-colour placeholder when the book has none. */
@Composable
fun BookCover(book: LibraryBook, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium, size: Int = 400) {
    val image = rememberCover(book.coverPath, size)
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val p = placeholderPalette(book)
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(p.preview, p.background))).padding(12.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(book.title, color = p.accent, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = if (size < 120) 9.sp else 16.sp, lineHeight = if (size < 120) 11.sp else 20.sp))
                    if (size >= 120) book.author?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = p.accent.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

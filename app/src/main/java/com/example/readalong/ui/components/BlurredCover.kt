package com.example.readalong.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val blurredCovers = android.util.LruCache<String, Bitmap>(8)

/** Blur only a small sampled image on IO, once per cover. Works on pre-Android-12 tablets too. */
@Composable
fun BlurredCover(uri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<Bitmap?>(null, uri) {
        value = null
        if (uri == null) return@produceState
        value = withContext(Dispatchers.IO) {
            blurredCovers.get(uri) ?: runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 96).coerceAtLeast(1) }
                val original = context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
                val small = Bitmap.createScaledBitmap(original, 64, 64, true)
                if (small !== original) original.recycle()
                var pixels = IntArray(4096); small.getPixels(pixels, 0, 64, 0, 0, 64, 64); small.recycle()
                repeat(3) {
                    val target = IntArray(4096)
                    for (y in 0..63) for (x in 0..63) {
                        var r = 0; var g = 0; var b = 0; var n = 0
                        for (dy in -6..6) for (dx in -6..6) {
                            val p = pixels[(y + dy).coerceIn(0, 63) * 64 + (x + dx).coerceIn(0, 63)]
                            r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255; n++
                        }
                        target[y * 64 + x] = android.graphics.Color.rgb(r / n, g / n, b / n)
                    }
                    pixels = target
                }
                Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888).also { blurredCovers.put(uri, it) }
            }.getOrNull()
        }
    }
    image?.let {
        Box(modifier) {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Clearer at the top so the cover's colour shows; darker behind the title and controls.
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Black.copy(alpha = .38f), .45f to Color.Black.copy(alpha = .62f), 1f to Color.Black.copy(alpha = .82f))))
        }
    }
}

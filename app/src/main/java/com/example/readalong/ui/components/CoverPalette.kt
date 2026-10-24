package com.example.readalong.ui.components

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.readalong.domain.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CoverPalette(val background: Color, val preview: Color, val accent: Color)

/** Small local bitmap sampling keeps cover-derived colors cheap and independent of playback. */
@Composable
fun rememberCoverPalette(book: Book): CoverPalette {
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val basic = com.example.readalong.ui.theme.basicPalette(settings.basicColor)
    val context = LocalContext.current
    val fallback = remember(book.coverStyle, book.coverUri, settings.basicColor) {
        if (book.coverUri == null) return@remember basic
        val hue = listOf(150f, 205f, 25f, 230f, 275f, 350f)[Math.floorMod(book.coverStyle, 6)]
        palette(hue, .42f)
    }
    val result by produceState(fallback, book.coverUri, book.coverStyle) {
        value = fallback
        val uri = book.coverUri?.takeIf { it.startsWith("content://") || it.startsWith("android.resource://") } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) }
                var sample = 1
                while (options.outWidth / sample > 96 || options.outHeight / sample > 96) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                val bitmap = context.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching fallback
                try {
                    val buckets = HashMap<Int, Int>(); val hsv = FloatArray(3)
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        android.graphics.Color.colorToHSV(pixel, hsv)
                        if (android.graphics.Color.alpha(pixel) > 200 && hsv[2] in .12f.. .94f && hsv[1] >= .15f) {
                            val key = (hsv[0] / 15).toInt()
                            buckets[key] = (buckets[key] ?: 0) + (1 + hsv[1] * 6).toInt()
                        }
                    }
                    buckets.maxByOrNull { it.value }?.key?.let { palette(it * 15f + 7.5f, .48f) } ?: palette(0f, 0f)
                } finally { bitmap.recycle() }
            }.getOrDefault(fallback)
        }
    }
    return if (settings.alwaysBasicColor || book.coverUri == null) basic else result
}

private fun palette(hue: Float, saturation: Float) = CoverPalette(
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, .25f))),
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, .38f))),
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, .20f, .95f))))

/**
 * A Material scheme tinted with the book's cover colours (or the accent color when "Use accent color
 * instead of cover colors" is on), for dialogs about one book. Replaces Material's default purples
 * that would otherwise show through the player's dark scheme.
 */
@Composable
fun rememberBookColorScheme(book: Book?): androidx.compose.material3.ColorScheme {
    val base = androidx.compose.material3.MaterialTheme.colorScheme
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val palette = book?.let { rememberCoverPalette(it) } ?: com.example.readalong.ui.theme.basicPalette(settings.basicColor)
    val dark = base.background.luminance() < .5f
    return remember(palette, dark, base) {
        if (dark) {
            val surface = lerp(Color(0xFF131619), palette.background, .6f)
            base.copy(primary = palette.accent, onPrimary = palette.background,
                primaryContainer = lerp(palette.preview, surface, .25f), onPrimaryContainer = Color.White,
                surface = surface, background = surface, surfaceVariant = lerp(surface, palette.preview, .5f),
                onSurface = Color.White, onSurfaceVariant = lerp(palette.accent, Color.White, .25f),
                outline = palette.accent.copy(alpha = .55f), outlineVariant = palette.accent.copy(alpha = .25f))
        } else {
            val surface = lerp(Color.White, palette.accent, .35f)
            base.copy(primary = palette.preview, onPrimary = Color.White,
                primaryContainer = lerp(palette.accent, palette.preview, .18f), onPrimaryContainer = palette.background,
                surface = surface, background = surface, surfaceVariant = lerp(Color.White, palette.accent, .8f),
                onSurface = Color(0xFF1B2024), onSurfaceVariant = palette.preview,
                outline = palette.preview.copy(alpha = .5f), outlineVariant = palette.preview.copy(alpha = .22f))
        }
    }
}

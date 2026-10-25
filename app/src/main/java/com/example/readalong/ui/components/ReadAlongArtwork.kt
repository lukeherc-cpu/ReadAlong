package com.example.readalong.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.example.readalong.R

/** Paper illustrations isolated from the supplied designs, in the logo's blue palette. */
@Composable
fun ReadAlongArtwork(resource: Int = R.drawable.art_readalong, modifier: Modifier = Modifier.fillMaxWidth().height(112.dp), matchColor: androidx.compose.ui.graphics.Color? = null) {
    if (matchColor == null) { Image(painterResource(resource), null, modifier); return }
    // Recolour only the art's blue parts (covers, file, link) to [matchColor]'s hue; cream pages stay cream.
    val context = androidx.compose.ui.platform.LocalContext.current
    val target = matchColor.toArgb()
    val image by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(recoloured[resource to target], resource, target) {
        value = recoloured[resource to target] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { recolour(android.graphics.BitmapFactory.decodeResource(context.resources, resource), target) }.getOrNull()
        }?.also { recoloured[resource to target] = it }
    }
    image?.let { Image(it, null, modifier) } ?: Image(painterResource(resource), null, modifier)
}

private val recoloured = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, androidx.compose.ui.graphics.ImageBitmap>()

/** Moves every clearly blue pixel to [target]'s hue (keeping its lightness); greys, creams and whites are left alone. */
private fun recolour(source: android.graphics.Bitmap, target: Int): androidx.compose.ui.graphics.ImageBitmap {
    val goal = FloatArray(3).also { android.graphics.Color.colorToHSV(target, it) }
    val width = source.width; val height = source.height
    val pixels = IntArray(width * height); source.getPixels(pixels, 0, width, 0, 0, width, height)
    val hsv = FloatArray(3)
    for (i in pixels.indices) {
        val p = pixels[i]; val alpha = p ushr 24
        if (alpha == 0) continue
        android.graphics.Color.colorToHSV(p, hsv)
        if (hsv[0] in 180f..260f && hsv[1] > .12f) {
            hsv[0] = goal[0]
            // A near-grey target gets grey art; otherwise keep the art's own saturation, nudged toward the target's.
            hsv[1] = if (goal[1] < .1f) hsv[1] * .15f else hsv[1] * .6f + goal[1] * .4f
            pixels[i] = android.graphics.Color.HSVToColor(alpha, hsv)
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun BackgroundWaveform() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val heights = listOf(.2f,.5f,.9f,.6f,1f,.65f,.35f,.18f)
        val width = 96.dp.toPx()
        heights.forEachIndexed { i, h ->
            val x = (size.width - width)/2 + (i + .5f)*width/heights.size
            drawLine(ink, Offset(x, size.height*(1-h)/2), Offset(x,size.height*(1+h)/2), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}

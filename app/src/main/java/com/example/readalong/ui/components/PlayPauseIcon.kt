package com.example.readalong.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Two matching contours morph on the draw thread without replacing or resizing the button. */
@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier, description: String = if (playing) "Pause" else "Play") {
    val fraction = animateFloatAsState(if (playing) 1f else 0f, tween(220), label = "Play pause")
    val ink = LocalContentColor.current
    Canvas(modifier.semantics { contentDescription = description }) {
        val triangle = arrayOf(floatArrayOf(7f,4f,13f,8f,13f,16f,7f,20f), floatArrayOf(13f,8f,21f,12f,21f,12f,13f,16f))
        val bars = arrayOf(floatArrayOf(6f,5f,10f,5f,10f,19f,6f,19f), floatArrayOf(14f,5f,18f,5f,18f,19f,14f,19f))
        val combined = Path()
        for (part in 0..1) {
            val path = Path()
            for (point in 0..3) {
                val i = point * 2
                val x = (triangle[part][i] + (bars[part][i] - triangle[part][i]) * fraction.value) * size.width / 24
                val y = (triangle[part][i+1] + (bars[part][i+1] - triangle[part][i+1]) * fraction.value) * size.height / 24
                if (point == 0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            path.close(); combined.addPath(path)
        }
        drawPath(combined, ink)
    }
}

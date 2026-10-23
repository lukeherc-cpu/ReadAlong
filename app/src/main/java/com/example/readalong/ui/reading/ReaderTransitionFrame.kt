package com.example.readalong.ui.reading

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** Record the settled surface once per draw. During a transform only this retained layer moves. */
@Stable
class ReaderTransitionFrame(val layer: GraphicsLayer) {
    var frozen by mutableStateOf(false)
    var recorded = false
    var previewWidthPx = 0
    var previewAnchor: ReaderVisualAnchor? = null
}

data class ReaderVisualAnchor(val paragraphId: String, val character: Int, val y: Float)

val LocalReaderDrawFrame = staticCompositionLocalOf { true }
val LocalReaderFrame = staticCompositionLocalOf<ReaderTransitionFrame?> { null }

fun Modifier.retainReaderFrame(frame: ReaderTransitionFrame?, visible: Boolean = true): Modifier = if (frame == null) this else drawWithContent {
    if (frame.frozen && frame.recorded) {
        if (visible) drawLayer(frame.layer)
        return@drawWithContent
    }
    if (!frame.frozen || !frame.recorded) {
        frame.layer.record { this@drawWithContent.drawContent() }
        frame.recorded = true
    }
    val width = frame.layer.size.width
    if (width > 0) {
        // Uniform scaling preserves line breaks and glyph proportions; expanding bounds reveal the rest.
        val scale = size.width / width
        withTransform({ scale(scale, scale, Offset.Zero) }) { drawLayer(frame.layer) }
    }
}

@androidx.compose.runtime.Composable
fun FrozenReaderFrame(frame: ReaderTransitionFrame, modifier: Modifier = Modifier) {
    val visible = LocalReaderDrawFrame.current
    androidx.compose.foundation.Canvas(modifier.drawWithContent {
        val width = frame.layer.size.width
        if (visible && frame.recorded && width > 0) {
            withTransform({ translate(left = (size.width - width) / 2f) }) { drawLayer(frame.layer) }
        }
    }) { }
}

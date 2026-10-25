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
    /** Where the spoken line sat on the full page (from the page's top), for the way back to the card. */
    var readerAnchor: ReaderVisualAnchor? = null
}

data class ReaderVisualAnchor(val paragraphId: String, val character: Int, val y: Float)

val LocalReaderDrawFrame = staticCompositionLocalOf { true }
val LocalReaderFrame = staticCompositionLocalOf<ReaderTransitionFrame?> { null }

/**
 * Records this content for the reader transition, or draws the retained frame while [frame] is frozen.
 * [handoff] > 0 keeps the retained frame drawn (centered, at that opacity) over the live content just
 * after a transition, so the switch from the frame to the live content is a short crossfade.
 */
fun Modifier.retainReaderFrame(frame: ReaderTransitionFrame?, visible: Boolean = true, handoff: () -> Float = { 0f }): Modifier = if (frame == null) this else drawWithContent {
    if (frame.frozen && frame.recorded) {
        if (visible) drawLayer(frame.layer)
        return@drawWithContent
    }
    val fade = handoff()
    if (fade > 0f && frame.recorded) {
        drawContent()
        if (visible) {
            val previous = frame.layer.alpha
            frame.layer.alpha = fade
            withTransform({ translate(left = (size.width - frame.layer.size.width) / 2f) }) { drawLayer(frame.layer) }
            frame.layer.alpha = previous
        }
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

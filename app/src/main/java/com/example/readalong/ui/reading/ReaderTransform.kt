package com.example.readalong.ui.reading

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Supplied by the book's shared transition owner; playback and reading models stay outside it. */
val LocalReaderSurface = staticCompositionLocalOf<Modifier> { Modifier }
val LocalReaderTransforming = staticCompositionLocalOf { false }

/** The animated surface color during expand/minimize, read at draw time. Reading it in composition
 * would recompose the whole player or reader on every frame of the transition. Unspecified when idle. */
val LocalReaderSurfaceColor = staticCompositionLocalOf<() -> androidx.compose.ui.graphics.Color> { { androidx.compose.ui.graphics.Color.Unspecified } }

/** One curve for the bounds, color and corners of the expand/minimize transition, so they move together. */
val ReaderMotionEasing = androidx.compose.animation.core.CubicBezierEasing(.2f, 0f, .1f, 1f)
const val ReaderMotionMillis = 320

/** Rounded corners whose radius is read when the outline is built (draw time), not in composition. */
class AnimatedCornerShape(private val radius: () -> androidx.compose.ui.unit.Dp) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        androidx.compose.foundation.shape.RoundedCornerShape(radius()).createOutline(size, layoutDirection, density)
}

@androidx.compose.runtime.Immutable
data class ReaderInsets(val horizontal: androidx.compose.ui.unit.Dp, val top: androidx.compose.ui.unit.Dp, val bottom: androidx.compose.ui.unit.Dp)
val LocalReaderInsets = staticCompositionLocalOf { ReaderInsets(androidx.compose.ui.unit.Dp(24f), androidx.compose.ui.unit.Dp(56f), androidx.compose.ui.unit.Dp(24f)) }

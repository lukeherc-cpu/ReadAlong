package com.example.readalong.ui.reading

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Supplied by the book's shared transition owner; playback and reading models stay outside it. */
val LocalReaderSurface = staticCompositionLocalOf<Modifier> { Modifier }
val LocalReaderTransforming = staticCompositionLocalOf { false }

val LocalReaderSurfaceColor = staticCompositionLocalOf { androidx.compose.ui.graphics.Color.Unspecified }

@androidx.compose.runtime.Immutable
data class ReaderInsets(val horizontal: androidx.compose.ui.unit.Dp, val top: androidx.compose.ui.unit.Dp, val bottom: androidx.compose.ui.unit.Dp)
val LocalReaderInsets = staticCompositionLocalOf { ReaderInsets(androidx.compose.ui.unit.Dp(24f), androidx.compose.ui.unit.Dp(56f), androidx.compose.ui.unit.Dp(24f)) }

package com.example.readalong.desktop.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

/** The desktop's mouse-wheel easing has no meaning on a touch screen; lists scroll with iOS's own fling. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.smoothWheelScroll(state: ScrollableState, onUserScroll: () -> Unit = {}): Modifier = this

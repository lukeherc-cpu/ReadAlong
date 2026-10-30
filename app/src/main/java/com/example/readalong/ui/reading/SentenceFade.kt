package com.example.readalong.ui.reading

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*

/** The sentence highlight hand-off: [previous] fades out while [current] fades in. */
@Immutable
data class SentenceFade(val previous: Pair<Int, Int>?, val current: Pair<Int, Int>?, val progress: Float)

/** A short crossfade (120 ms) when the highlighted sentence changes, so the highlight moves instead of blinking. */
@Composable
fun rememberSentenceFade(focus: Pair<Int, Int>?, enabled: Boolean = true): SentenceFade? {
    if (!enabled) return null
    var previous by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var current by remember { mutableStateOf(focus) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(focus) {
        if (focus == current) return@LaunchedEffect
        previous = current; current = focus
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SentenceFadeMillis, easing = LinearOutSlowInEasing))
        previous = null
    }
    return SentenceFade(previous, current, progress.value)
}

const val SentenceFadeMillis = 120

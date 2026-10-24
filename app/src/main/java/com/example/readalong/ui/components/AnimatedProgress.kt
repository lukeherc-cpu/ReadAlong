package com.example.readalong.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.example.readalong.ui.reading.ReaderMotionEasing

/** Progress bars ease to new values instead of jumping when a checkpoint lands. Read the State inside the bar's lambda. */
@Composable
fun animatedFraction(target: Float, label: String = "Progress"): State<Float> =
    animateFloatAsState(target.coerceIn(0f, 1f), tween(600, easing = ReaderMotionEasing), label = label)

/** Percent labels count up to the new value alongside their bar. */
@Composable
fun animatedPercent(target: Int, label: String = "Percent"): State<Int> =
    animateIntAsState(target.coerceIn(0, 100), tween(600, easing = ReaderMotionEasing), label = label)

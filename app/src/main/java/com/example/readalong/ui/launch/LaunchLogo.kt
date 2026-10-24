package com.example.readalong.ui.launch

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.readalong.ui.theme.LogoPaleBlue

/** One page turns above a complete, stationary mark; Android and Compose use the same safe inset. */
@Composable
fun LaunchLogo(ready: Boolean = true, animate: Boolean = false, onFinished: () -> Unit = {}) {
    val turn = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(ready, animate) {
        if (!ready) return@LaunchedEffect
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) turn.animateTo(1f, tween(1000, easing = PageTurnEasing))
        finished()
    }
    if (!ready || animate) Box(Modifier.fillMaxSize().testTag("launch_logo").background(androidx.compose.ui.graphics.Color(LaunchPalettes.closest(com.example.readalong.ui.theme.LocalAppSettings.current.basicColor).background(com.example.readalong.ui.theme.LocalAppSettings.current.libraryLight))), contentAlignment = Alignment.Center) {
        PageTurnLogo(turn.value, Modifier.size(288.dp), safeInset = true)
    }
}

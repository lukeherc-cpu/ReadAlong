package com.example.readalong.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.readalong.ui.reading.AnimatedCornerShape
import com.example.readalong.ui.reading.ReaderMotionEasing
import com.example.readalong.ui.reading.ReaderMotionMillis

/** Shared-element scopes for transitions between navigation destinations (library ↔ player). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalNavSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
/** The key the player's cover answers to, or null when the player was opened some other way. */
val LocalPlayerCoverKey = staticCompositionLocalOf<String?> { null }

fun coverKey(bookId: String) = "cover:$bookId"
fun playerKey(bookId: String) = "player:$bookId"

/**
 * Container transform: the mini player grows into the full player (and shrinks back). Both sides
 * are laid out once at their own size and scaled into the moving bounds, so nothing re-measures per
 * frame. The corners round off in step with the bounds rather than snapping at the end.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.navContainer(key: String?, smallCorner: Dp, largeCorner: Dp = 0.dp, small: Boolean): Modifier {
    val shared = LocalNavSharedScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    if (key == null) return this
    val corner by visibility.transition.animateDp({ tween<Dp>(ReaderMotionMillis, easing = ReaderMotionEasing) }, label = "Container corners") { state: EnterExitState ->
        if (state == EnterExitState.Visible) (if (small) smallCorner else largeCorner) else (if (small) largeCorner else smallCorner)
    }
    val shape = remember { AnimatedCornerShape { corner } }
    return with(shared) {
        this@navContainer.sharedBounds(rememberSharedContentState(key), visibility,
            enter = fadeIn(tween(ReaderMotionMillis - 100, delayMillis = 80)),
            exit = fadeOut(tween(110)),
            boundsTransform = { _, _ -> tween(ReaderMotionMillis, easing = ReaderMotionEasing) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(if (small) ContentScale.FillWidth else ContentScale.Crop),
            clipInOverlayDuringTransition = OverlayClip(shape))
    }
}

/** The same cover flies between the library grid and the player. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.navSharedCover(key: String?): Modifier {
    val shared = LocalNavSharedScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    if (key == null) return this
    return with(shared) {
        this@navSharedCover.sharedElement(rememberSharedContentState(key), visibility,
            boundsTransform = { _, _ -> tween(ReaderMotionMillis, easing = ReaderMotionEasing) })
    }
}

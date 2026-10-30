package com.example.readalong.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * A mouse wheel (tablets, Chromebooks, DeX) glides the way it does in the desktop app: a notch moves a fixed
 * distance and eases out over about 200 ms, notches mid-glide add to it, and turning the wheel back stops
 * it. Compose on Android jumps the whole notch in one frame. Touch scrolling and flings are untouched.
 *
 * Put it on the scrolling container. [onUserScroll] runs when the wheel moves it.
 */
fun Modifier.smoothWheelScroll(state: ScrollableState, onUserScroll: () -> Unit = {}): Modifier = composed {
    val scope = rememberCoroutineScope()
    val notch = with(LocalDensity.current) { NotchDp.dp.toPx() }
    val glide = remember(state) { WheelGlide(state) }
    glide.onUserScroll = onUserScroll
    pointerInput(glide) {
        awaitPointerEventScope {
            while (true) {
                // Claim on the way in (outermost first, so the innermost list that can move wins) and consume,
                // which keeps Compose's own wheel handling out; scroll on the way back out.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val delta = if (event.type == PointerEventType.Scroll) event.changes.fold(0f) { sum, c -> sum + c.scrollDelta.y } * notch else 0f
                if (delta != 0f && glide.canMove(delta)) {
                    WheelClaim.owner = glide
                    event.changes.forEach { it.consume() }
                }
                awaitPointerEvent(PointerEventPass.Main)
                if (delta != 0f && WheelClaim.owner === glide) {
                    WheelClaim.owner = null
                    glide.add(delta, limit = size.height * 2.5f, scope = scope)
                }
            }
        }
    }
}

private const val NotchDp = 100f

/** The list that takes the wheel event being dispatched. Pointer input runs on the main thread, one event at a time. */
private object WheelClaim { var owner: WheelGlide? = null }

private class WheelGlide(private val state: ScrollableState) {
    var onUserScroll: () -> Unit = {}
    /** Distance still to travel, in pixels; positive is down. */
    private var pending = 0f
    private var job: Job? = null

    fun canMove(delta: Float) = job?.isActive == true || if (delta > 0) state.canScrollForward else state.canScrollBackward

    fun add(delta: Float, limit: Float, scope: CoroutineScope) {
        if (pending != 0f && sign(delta) != sign(pending)) pending = 0f
        val bound = limit.coerceAtLeast(1f)
        pending = (pending + delta).coerceIn(-bound, bound)
        onUserScroll()
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                state.scroll(MutatePriority.UserInput) {
                    var last = withFrameNanos { it } - 16_000_000L
                    while (abs(pending) >= .5f) {
                        val now = withFrameNanos { it }
                        val elapsedMs = ((now - last) / 1_000_000f).coerceIn(1f, 50f)
                        last = now
                        var step = pending * (1f - exp(-elapsedMs / EaseMs))
                        if (abs(pending - step) < .5f) step = pending
                        val moved = scrollBy(step)
                        // At the top or bottom: drop the rest rather than pushing against the end.
                        pending = if (abs(moved - step) > .5f) 0f else pending - step
                    }
                }
            } finally { pending = 0f }
        }
    }

    private companion object {
        /** Time constant of the ease-out: 95% of the way there after three of these. */
        const val EaseMs = 60f
    }
}

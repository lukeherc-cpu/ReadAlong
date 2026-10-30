package com.example.readalong.desktop.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Mouse-wheel scrolling that glides the way a browser's does, on every desktop build. Compose's own wheel
 * handling moves a share of the viewport per notch (so tall windows lurch) along a 100 ms straight line that
 * stops dead, and restarts at each notch. Here a notch moves a fixed distance and eases out over about 200 ms;
 * notches that arrive mid-glide add to where it is heading, and turning the wheel back stops it at once.
 * Touchpads on macOS already send fine, accelerated steps, so those move without easing.
 *
 * Put it on the scrolling container (before `verticalScroll` in the chain). [onUserScroll] runs when the
 * wheel moves it, which is how the read-along text knows to stop following the narration.
 */
fun Modifier.smoothWheelScroll(state: ScrollableState, onUserScroll: () -> Unit = {}): Modifier = composed {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val glide = remember(state) { WheelGlide(state) }
    glide.onUserScroll = onUserScroll
    pointerInput(glide) {
        awaitPointerEventScope {
            while (true) {
                // Claim on the way in (outermost first, so the innermost container that can move wins) and
                // consume, which keeps Compose's own wheel handlers out; scroll on the way back out.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val delta = if (event.type == PointerEventType.Scroll) glide.distance(event, density, size.height) else 0f
                if (delta != 0f && glide.canMove(delta)) {
                    WheelClaim.owner = glide
                    event.changes.forEach { it.consume() }
                }
                awaitPointerEvent(PointerEventPass.Main)
                if (delta != 0f && WheelClaim.owner === glide) {
                    WheelClaim.owner = null
                    glide.add(delta, immediate = glide.isTouchpad(event), limit = size.height * 2.5f, scope = scope)
                }
            }
        }
    }
}

/** The container that takes the wheel event being dispatched. Pointer input runs on the UI thread, one event at a time. */
private object WheelClaim { var owner: WheelGlide? = null }

private class WheelGlide(private val state: ScrollableState) {
    var onUserScroll: () -> Unit = {}
    /** Distance still to travel, in pixels; positive is down. */
    private var pending = 0f
    private var immediate = false
    private var job: Job? = null

    fun canMove(delta: Float) = job?.isActive == true || if (delta > 0) state.canScrollForward else state.canScrollBackward

    /** Pixels to move for this wheel event (0 for sideways scrolling, which is left to Compose). */
    fun distance(event: PointerEvent, density: Density, viewport: Int): Float {
        val notches = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
        if (notches == 0f) return 0f
        val wheel = event.awtEventOrNull as? MouseWheelEvent
        return when {
            wheel?.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL -> notches * viewport * .9f
            isTouchpad(event) -> notches * with(density) { 10.dp.toPx() }
            // The system's "lines per notch" (3 by default on Windows and Linux) at 36 dp a line.
            else -> notches * (wheel?.scrollAmount ?: 3).coerceIn(1, 12) * with(density) { 36.dp.toPx() }
        }
    }

    /** A macOS touchpad or Magic Mouse: fractional, already-accelerated steps that should land without easing. */
    fun isTouchpad(event: PointerEvent): Boolean {
        val wheel = event.awtEventOrNull as? MouseWheelEvent ?: return false
        return AppPaths.os() == "macos" && abs(wheel.preciseWheelRotation - wheel.wheelRotation) > 0.001
    }

    fun add(delta: Float, immediate: Boolean, limit: Float, scope: CoroutineScope) {
        if (pending != 0f && sign(delta) != sign(pending)) pending = 0f
        pending = (pending + delta).coerceIn(-limit.coerceAtLeast(1f), limit.coerceAtLeast(1f))
        this.immediate = immediate
        onUserScroll()
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                state.scroll(MutatePriority.UserInput) {
                    var last = withFrameNanos { it } - FRAME_NANOS
                    while (abs(pending) >= .5f) {
                        val now = withFrameNanos { it }
                        val elapsedMs = ((now - last) / 1_000_000f).coerceIn(1f, 50f)
                        last = now
                        var step = if (this@WheelGlide.immediate) pending else pending * (1f - exp(-elapsedMs / EASE_MS))
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
        const val EASE_MS = 60f
        const val FRAME_NANOS = 16_000_000L
    }
}

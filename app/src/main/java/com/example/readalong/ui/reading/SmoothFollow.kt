package com.example.readalong.ui.reading

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Follows the highlighted line with one critically damped spring instead of a new fixed-length
 * tween per line. When the next line arrives while the list is still gliding, the spring is
 * retargeted with its current velocity, so consecutive follows blend into one motion rather than
 * stopping and restarting. A finger on the list (UserInput priority) still interrupts it at once.
 */
@Stable
class SmoothFollow internal constructor(private val list: LazyListState, private val scope: CoroutineScope) {
    private val travel = Animatable(0f)

    /** Glides the list by [delta] px from where it is now (measured against the current layout). */
    fun scrollBy(delta: Float) {
        if (abs(delta) < 1f) return
        // Read velocity before list.scroll cancels the running glide (cancellation resets it).
        val velocity = travel.velocity
        scope.launch {
            list.scroll(MutatePriority.Default) {
                val start = travel.value
                var applied = start
                travel.animateTo(start + delta, spring(dampingRatio = 1f, stiffness = FollowStiffness), initialVelocity = velocity) {
                    applied += scrollBy(value - applied)
                }
            }
        }
    }

    companion object {
        /** Settles in about 0.6 s: calm enough to read through, quick enough to keep the line in view. */
        const val FollowStiffness = 160f
    }
}

@Composable
fun rememberSmoothFollow(list: LazyListState): SmoothFollow {
    val scope = rememberCoroutineScope()
    return remember(list, scope) { SmoothFollow(list, scope) }
}

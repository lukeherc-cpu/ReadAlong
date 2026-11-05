package com.example.readalong.desktop

import com.example.readalong.desktop.transcription.CrashPolicy
import com.example.readalong.desktop.transcription.CrashPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashPolicyTest {
    @Test fun aFailingUnitIsRetriedThenRescuedThenSkipped() {
        val policy = CrashPolicy()
        assertEquals(Action.RETRY, policy.failed(12, onGpu = true))
        assertEquals(Action.RESCUE, policy.failed(12, onGpu = true))
        assertEquals(Action.SKIP, policy.failed(12, onGpu = false))
        assertEquals(3, policy.failures(12))
        assertEquals(0, policy.failures(13))
    }

    @Test fun oneBadUnitKeepsTheBookOnTheGpu() {
        val policy = CrashPolicy()
        repeat(3) { policy.failed(7, onGpu = true) }
        assertFalse(policy.gpuUnstable)
        assertFalse(policy.preferGreedy)
    }

    @Test fun failuresOnManyUnitsMoveTheBookToTheCpu() {
        val policy = CrashPolicy()
        policy.failed(1, onGpu = true); policy.failed(2, onGpu = true)
        assertTrue(policy.preferGreedy)
        for (unit in 3 until CrashPolicy.GPU_UNIT_LIMIT) policy.failed(unit, onGpu = true)
        assertFalse(policy.gpuUnstable)
        policy.failed(100, onGpu = true)
        assertTrue(policy.gpuUnstable)
    }

    @Test fun cpuFailuresDontCountAgainstTheGpu() {
        val policy = CrashPolicy()
        for (unit in 0 until CrashPolicy.GPU_UNIT_LIMIT * 2) policy.failed(unit, onGpu = false)
        assertFalse(policy.gpuUnstable)
    }

    @Test fun aGpuThatCantLoadTheModelIsGivenUp() {
        val policy = CrashPolicy()
        policy.gpuStartFailed()
        assertFalse(policy.gpuUnstable)
        policy.gpuStartFailed()
        assertTrue(policy.gpuUnstable)
    }
}

package com.example.readalong.domain.transcription

/** A short worker session, still checkpointed in <=30-second audio units.
 * Leave time for another unit based on the slowest completed one, never just the last one. */
class TranscriptionBatch {
    private var slowestMs = 0L
    private var completed = 0
    fun continueAfter(unitMs: Long, elapsedMs: Long, conserveResources: Boolean): Boolean {
        slowestMs = maxOf(slowestMs, unitMs)
        completed++
        return !conserveResources && completed < MAX_UNITS &&
            elapsedMs + maxOf(10_000, slowestMs + slowestMs / 2) < SESSION_BUDGET_MS
    }
    companion object {
        const val MAX_UNITS = 32
        const val SESSION_BUDGET_MS = 360_000L
    }
}

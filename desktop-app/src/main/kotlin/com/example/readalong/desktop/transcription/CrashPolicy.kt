package com.example.readalong.desktop.transcription

/**
 * What a lane does when its transcriber dies (exit 134 and the like) or whisper fails on one unit of the plan.
 *
 * A failure belongs to the unit that was being recognized, not to the device: one hard stretch of audio shouldn't
 * move a whole book off the GPU, and shouldn't stop the book either. So the unit is tried again on its own in a fresh
 * transcriber with greedy decoding, then once on the CPU without attention-based word times (the step most crashes
 * come from), and only then left empty so the rest of the book carries on. The GPU is given up for the book only when
 * failures keep happening on many different units, which points at the driver rather than the audio.
 */
class CrashPolicy(private val gpuUnitLimit: Int = GPU_UNIT_LIMIT) {
    enum class Action {
        /** A fresh transcriber on the same device, this unit alone, greedy decoding. */
        RETRY,
        /** This unit alone on the CPU, without DTW word times. */
        RESCUE,
        /** Save the unit with no words and go on with the book. */
        SKIP,
    }

    private val failures = HashMap<Int, Int>()
    private val gpuUnits = HashSet<Int>()
    private var gpuStartFailures = 0

    @Synchronized fun failures(unit: Int): Int = failures[unit] ?: 0

    /** [unit] (a plan index) failed on the GPU ([onGpu]) or the CPU. Returns what to do with it next. */
    @Synchronized fun failed(unit: Int, onGpu: Boolean): Action {
        val count = (failures[unit] ?: 0) + 1
        failures[unit] = count
        if (onGpu) gpuUnits += unit
        return when (count) {
            1 -> Action.RETRY
            2 -> Action.RESCUE
            else -> Action.SKIP
        }
    }

    /** The transcriber died on the GPU before the model had loaded: nothing was recognized, so no unit is to blame. */
    @Synchronized fun gpuStartFailed() { gpuStartFailures++ }

    /** True once the GPU has failed on enough different units (or failed to start often enough) to finish on the CPU. */
    val gpuUnstable: Boolean @Synchronized get() = gpuUnits.size >= gpuUnitLimit || gpuStartFailures >= GPU_START_LIMIT

    /**
     * True once two different units have failed on the GPU: greedy decoding there from then on. A driver that resets
     * long-running work (amdgpu's job timeout on Linux, Windows' TDR) gets much shorter submissions.
     */
    val preferGreedy: Boolean @Synchronized get() = gpuUnits.size >= 2

    companion object {
        /** Different units failing on the GPU before a book moves to the CPU. */
        const val GPU_UNIT_LIMIT = 6
        /** Transcribers dying on the GPU before the model even loads. */
        const val GPU_START_LIMIT = 2
    }
}

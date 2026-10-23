package com.example.readalong.domain.audio

/** Streaming mono resampler; carries sample phase across decoder buffers. */
class PcmResampler(private val sourceRate: Int, private val targetRate: Int = 16000, private val emit: (Float) -> Unit) {
    private var index = -1L
    private var next = 0.0
    private var previous = 0f
    init { require(sourceRate in 8000..192000 && targetRate > 0) }
    fun sample(value: Float) {
        index++
        while (next <= index) {
            val fraction = (next - (index - 1)).coerceIn(0.0, 1.0).toFloat()
            emit(if (index == 0L) value else previous + (value - previous) * fraction)
            next += sourceRate.toDouble() / targetRate
        }
        previous = value
    }
}

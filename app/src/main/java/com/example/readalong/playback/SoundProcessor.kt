package com.example.readalong.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.readalong.domain.model.SoundChain
import com.example.readalong.domain.model.SoundSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the equalizer, noise reduction and volume boost on decoded audio, before skip silence and
 * speed. It stays active for 16-bit and float audio so a settings change applies at once without
 * rebuilding the player; with nothing to do it copies the audio through unchanged.
 */
@UnstableApi
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var settings: SoundSettings = SoundSettings.DEFAULT
    private var chain: SoundChain? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    // A seek or new format starts the filters from silence; the chain is rebuilt for the new format.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) { chain = null }

    override fun onReset() { chain = null; scratch = FloatArray(0) }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val chain = chain ?: SoundChain(inputAudioFormat.sampleRate, inputAudioFormat.channelCount).also { this.chain = it }
        chain.configure(settings)
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val count = if (float) size / 4 else size / 2
        if (scratch.size < count) scratch = FloatArray(count)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until count) scratch[i] = input.getFloat()
        else for (i in 0 until count) scratch[i] = input.getShort() / 32768f
        chain.process(scratch, count)
        if (float) for (i in 0 until count) output.putFloat(scratch[i])
        else for (i in 0 until count) output.putShort((scratch[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        output.flip()
    }
}

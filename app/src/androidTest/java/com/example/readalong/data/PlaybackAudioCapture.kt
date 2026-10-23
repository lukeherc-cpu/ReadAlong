package com.example.readalong.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.readalong.data.audio.AudiobookExtractorsFactory
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Capture PCM actually submitted to playback, rather than trusting the displayed seek clock. */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun capturePlaybackAudio(context: Context, uri: Uri, position: Long): FloatArray {
    val captured = ByteArrayOutputStream()
    var rate = 0; var channels = 0
    val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = synchronized(captured) {
            check(encoding == C.ENCODING_PCM_16BIT)
            rate = sampleRateHz; channels = channelCount
        }
        override fun handleBuffer(buffer: ByteBuffer) = synchronized(captured) {
            val size = minOf(buffer.remaining(), (rate * channels * 4 - captured.size()).coerceAtLeast(0))
            if (size > 0) captured.write(ByteArray(size).also { buffer.get(it) })
        }
    }
    val renderer = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setAudioProcessors(arrayOf(TeeAudioProcessor(sink))).build()
    }
    var player: ExoPlayer? = null
    try {
        withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(context, renderer)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, AudiobookExtractorsFactory(context))).build().apply {
                    setMediaItem(MediaItem.fromUri(uri)); prepare(); seekTo(position); play()
                }
        }
        withTimeout(45_000) {
            while (synchronized(captured) { rate == 0 || captured.size() < rate * channels * 4 }) delay(50)
        }
    } finally { withContext(Dispatchers.Main) { player?.release() } }
    val pcm = ByteBuffer.wrap(captured.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return FloatArray(minOf(2000, pcm.remaining() / channels * 1000 / rate)) { ms ->
        val from = ms * rate / 1000; val to = (ms + 1) * rate / 1000
        var sum = 0.0
        for (frame in from until to) for (channel in 0 until channels) sum += pcm.get(frame * channels + channel) / 32768.0
        (sum / ((to - from) * channels)).toFloat()
    }
}

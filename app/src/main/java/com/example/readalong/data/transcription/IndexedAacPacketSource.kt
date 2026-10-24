package com.example.readalong.data.transcription

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.extractor.AacUtil
import com.example.readalong.domain.audio.AacMp4Index
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext

/** Seek in the original AAC sample tables with bounded heap, including multi-day M4Bs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class IndexedAacPacketSource(context: Context, uri: Uri) : Closeable {
    private val descriptor = requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r"))
    private val stream = FileInputStream(descriptor.fileDescriptor)
    private var index: AacMp4Index? = null
    suspend fun open(fromUs: Long): MediaFormat {
        coroutineContext.ensureActive()
        val source = AacMp4Index(stream.channel, descriptor.startOffset,
            descriptor.length.takeIf { it >= 0 } ?: (stream.channel.size() - descriptor.startOffset))
        index = source
        val config = AacUtil.parseAudioSpecificConfig(source.audioSpecificConfig)
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", config.sampleRateHz, config.channelCount)
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(source.audioSpecificConfig))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        source.seek(fromUs - 1_000_000) // Include negative edit-list priming at the opening too.
        coroutineContext.ensureActive()
        return format
    }
    suspend fun next(): AacMp4Index.Packet? { coroutineContext.ensureActive(); return requireNotNull(index).next() }
    override fun close() { try { stream.close() } finally { descriptor.close() } }
}

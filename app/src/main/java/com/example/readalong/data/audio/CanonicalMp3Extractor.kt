package com.example.readalong.data.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.mp3.Mp3Extractor

/** Xing/VBRI lookup tables can assign a variable-bitrate frame to the wrong second.
 * Use actual scanned frame positions and reset the sample clock to that exact frame
 * after a seek. Constant-bitrate and other accurate seek maps retain Media3's behavior. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CanonicalMp3Extractor(private val context: Context, private val uri: Uri,
    private val delegate: Mp3Extractor = Mp3Extractor(), private val checkCancelled: () -> Unit = {}) : Extractor {
    private var index: Mp3SeekIndex? = null
    private var requestedBase: Long? = null
    private var correction: Long? = null
    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) = delegate.init(object : ForwardingExtractorOutput(output) {
        override fun seekMap(map: SeekMap) {
            if (map.javaClass.simpleName in setOf("XingSeeker", "VbriSeeker")) {
                val precise = Mp3SeekIndex.load(context, uri, checkCancelled)
                index = precise
                val probes = listOf(60_000_000L, 180_000_000L, 600_000_000L, 1_200_000_000L,
                    map.durationUs / 4, map.durationUs / 2, map.durationUs * 3 / 4).filter { it in 0 until map.durationUs }
                val error = probes.maxOfOrNull { time ->
                    val old = map.getSeekPoints(time).first
                    kotlin.math.abs(precise.estimatedTimeAt(old.position) - old.timeUs)
                } ?: 0L
                Mp3Timing.corrected(uri, error)
            }
            super.seekMap(index ?: map)
        }
        override fun track(id: Int, type: Int): TrackOutput = object : ForwardingTrackOutput(super.track(id, type)) {
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (correction == null) correction = requestedBase?.let { it - timeUs } ?: 0L
                super.sampleMetadata(timeUs + correction!!, flags, size, offset, cryptoData)
            }
        }
    })
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) {
        requestedBase = if (position > 0) index?.timeAt(position) else null
        correction = null
        delegate.seek(position, timeUs)
    }
    override fun release() = delegate.release()
}

@androidx.annotation.OptIn(UnstableApi::class)
class AudiobookExtractorsFactory(private val context: Context) : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { if (it is Mp3Extractor) CanonicalMp3Extractor(context, uri, it) else it }.toTypedArray()
}

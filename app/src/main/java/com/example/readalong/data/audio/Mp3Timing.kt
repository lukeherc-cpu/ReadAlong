package com.example.readalong.data.audio

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object Mp3Timing {
    const val VERSION = "frame-clock-v1"
    fun versionKey(bookId: String) = "transcription_clock:$bookId"
    private val corrected = MutableStateFlow(emptySet<String>())
    val correctedUris = corrected.asStateFlow()
    fun corrected(uri: Uri, errorUs: Long) {
        // A one-second threshold exceeds the uncertainty of interpolation between the
        // one-second index entries. Do not request rebuilds for normal codec-frame jitter.
        if (errorUs >= 1_000_000) corrected.update { it + uri.toString() }
    }
}

package com.example.readalong.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SELECT_BOOK = "readalong.SELECT_BOOK"
internal const val SEEK_CHAPTER = "readalong.SEEK_CHAPTER"
internal const val SEEK_BOOK = "readalong.SEEK_BOOK"
internal const val SLEEP_TIMER = "readalong.SLEEP_TIMER"
internal const val CLEAR_BOOK = "readalong.CLEAR_BOOK"

internal suspend fun <T> ListenableFuture<T>.awaitResult(context: Context): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { if (continuation.isActive) continuation.resume(get()) }
        catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
    }, ContextCompat.getMainExecutor(context))
    continuation.invokeOnCancellation { cancel(false) }
}

class PlaybackConnection(private val context: Context) {
    private var controller: MediaController? = null
    private val lock = kotlinx.coroutines.sync.Mutex()
    suspend fun controller(): MediaController {
        lock.lock()
        try {
            controller?.takeIf { it.isConnected }?.let { return it }
            return MediaController.Builder(context, SessionToken(context, ComponentName(context, AudiobookService::class.java)))
                .buildAsync().awaitResult(context).also { controller = it }
        } finally { lock.unlock() }
    }
    suspend fun command(action: String, args: Bundle): SessionResult = controller()
        .sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args).awaitResult(context)
}

package com.example.readalong.data.transcription

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class RecognitionConnection(private val context: Context, private val service: Class<out RecognitionService>? = null) {
    companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
        private val recentMs = ArrayDeque<Long>()
        /** A chunk that takes far longer than recent ones has stalled (e.g. its worker threads are
         * starved after Android shrank the CPU set). Fail it so the caller retries in a fresh session. */
        fun stallLimitMs(): Long = synchronized(recentMs) {
            if (recentMs.size < 3) 180_000L else (recentMs.sorted()[recentMs.size / 2] * 6).coerceIn(60_000L, 240_000L)
        }
        private fun record(ms: Long) = synchronized(recentMs) { recentMs.addLast(ms); while (recentMs.size > 9) recentMs.removeFirst() }
    }
    /** An extra binding keeps one private service/model alive between bounded chunk requests.
     * Always released on success, cancellation or error; no started service or idle model cache. */
    suspend fun <T> withSession(block: suspend (RecognitionConnection) -> T): T {
        if (service == null) return RecognitionSlots.use { selected -> RecognitionConnection(context, selected).withSession(block) }
        val lease = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = Unit
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        try {
            withContext(Dispatchers.Main) {
                bound = context.bindService(Intent(context, service), lease, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            }
            if (!bound) throw IOException("Couldn't start the speech model.")
            return block(this)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { if (bound) context.unbindService(lease) }
        }
    }

    suspend fun transcribe(model: File, pcm: File, backend: String = "whistle", onMetrics: (LongArray) -> Unit = {}, onProgress: (Int) -> Unit = {}): String {
        if (service == null) return withSession { it.transcribe(model, pcm, backend, onMetrics, onProgress) }
        val limit = stallLimitMs()
        val started = SystemClock.elapsedRealtime()
        val result = try { withContext(Dispatchers.Main) {
        withTimeout(limit) { suspendCancellableCoroutine<String> { continuation ->
            val requestId = java.util.UUID.randomUUID().toString()
            var remote: Messenger? = null
            val bound = AtomicBoolean(false)
            lateinit var connection: ServiceConnection
            val main = Handler(Looper.getMainLooper())
            // A recognition process that died may be restarted by Android only after a growing delay.
            // Don't wait on it: fail fast, and the caller retries with a fresh session.
            val watchdog = Runnable {
                if (remote == null && continuation.isActive) {
                    continuation.resumeWith(Result.failure(IOException("The speech model didn't start in time. Trying again…")))
                    if (bound.compareAndSet(true, false)) runCatching { context.unbindService(connection) }
                }
            }
            fun unbind() { main.removeCallbacks(watchdog); if (bound.compareAndSet(true, false)) runCatching { context.unbindService(connection) } }
            val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (message.what == RecognitionService.PROGRESS) {
                        if (continuation.isActive) onProgress(message.arg1)
                        return
                    }
                    if (message.what != RecognitionService.RESULT) return
                    if (continuation.isActive) {
                        message.data.getLongArray("metrics")?.let(onMetrics)
                        val error = message.data.getString("error")
                        val result = message.data.getString("result")
                        if (error != null || result == null) continuation.resumeWith(Result.failure(IOException(error ?: "The speech model returned nothing for this part. Trying again…")))
                        else continuation.resumeWith(Result.success(result))
                    }
                    unbind()
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    main.removeCallbacks(watchdog)
                    if (!continuation.isActive) { unbind(); return }
                    remote = Messenger(binder)
                    try { remote!!.send(Message.obtain(null, RecognitionService.RECOGNIZE).apply {
                        replyTo = reply
                        data = Bundle().apply { putString("model", model.absolutePath); putString("pcm", pcm.absolutePath); putString("backend", backend); putString("requestId", requestId) }
                    }) } catch (_: RemoteException) { failed() }
                }
                private fun failed() {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("The speech model stopped. Progress is saved; try again.")))
                    unbind()
                }
                override fun onServiceDisconnected(name: ComponentName) = failed()
                override fun onBindingDied(name: ComponentName) = failed()
                override fun onNullBinding(name: ComponentName) = failed()
            }
            bound.set(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
            if (!bound.get()) continuation.resumeWith(Result.failure(IOException("Couldn't start the speech model.")))
            else main.postDelayed(watchdog, CONNECT_TIMEOUT_MS)
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    runCatching { remote?.send(Message.obtain(null, RecognitionService.CANCEL).apply { data = Bundle().apply { putString("requestId", requestId) } }) }
                    unbind()
                }
            }
        } }
        } } catch (stalled: TimeoutCancellationException) {
            throw IOException("Speech recognition stalled for ${limit / 1000} s. Retrying in a fresh session.")
        }
        record(SystemClock.elapsedRealtime() - started)
        return result
    }
}

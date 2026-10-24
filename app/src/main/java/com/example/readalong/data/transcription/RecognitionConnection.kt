package com.example.readalong.data.transcription

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class RecognitionConnection(private val context: Context, private val service: Class<out RecognitionService>? = null) {
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
            if (!bound) throw IOException("Couldn't start local recognition.")
            return block(this)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { if (bound) context.unbindService(lease) }
        }
    }

    suspend fun transcribe(model: File, pcm: File, backend: String = "whistle", onMetrics: (LongArray) -> Unit = {}, onProgress: (Int) -> Unit = {}): String {
        if (service == null) return withSession { it.transcribe(model, pcm, backend, onMetrics, onProgress) }
        // The recognition process restarts itself when Android changes its CPU set (see
        // RecognitionService). That is not a failure: wait for the fresh process and resend.
        repeat(4) { attempt ->
            try { return transcribeOnce(model, pcm, backend, onMetrics, onProgress) }
            catch (_: RecognitionRestart) { delay(400L + 400L * attempt) }
        }
        return transcribeOnce(model, pcm, backend, onMetrics, onProgress)
    }

    private class RecognitionRestart : IOException("Recognition runtime restarting.")

    private suspend fun transcribeOnce(model: File, pcm: File, backend: String, onMetrics: (LongArray) -> Unit, onProgress: (Int) -> Unit): String {
        return withContext(Dispatchers.Main) {
        withTimeout(8 * 60_000L) { suspendCancellableCoroutine { continuation ->
            val requestId = java.util.UUID.randomUUID().toString()
            var remote: Messenger? = null
            val bound = AtomicBoolean(false)
            lateinit var connection: ServiceConnection
            fun unbind() { if (bound.compareAndSet(true, false)) runCatching { context.unbindService(connection) } }
            val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (message.what == RecognitionService.PROGRESS) {
                        if (continuation.isActive) onProgress(message.arg1)
                        return
                    }
                    if (message.what != RecognitionService.RESULT) return
                    if (message.data.getBoolean("restart")) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(RecognitionRestart()))
                        unbind(); return
                    }
                    if (continuation.isActive) {
                        message.data.getLongArray("metrics")?.let(onMetrics)
                        val error = message.data.getString("error")
                        val result = message.data.getString("result")
                        if (error != null || result == null) continuation.resumeWith(Result.failure(IOException(error ?: "Recognition returned no result.")))
                        else continuation.resumeWith(Result.success(result))
                    }
                    unbind()
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (!continuation.isActive) { unbind(); return }
                    remote = Messenger(binder)
                    try { remote!!.send(Message.obtain(null, RecognitionService.RECOGNIZE).apply {
                        replyTo = reply
                        data = Bundle().apply { putString("model", model.absolutePath); putString("pcm", pcm.absolutePath); putString("backend", backend); putString("requestId", requestId) }
                    }) } catch (_: RemoteException) { failed() }
                }
                private fun failed() {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("Recognition stopped. Audio and saved text are preserved; retry preparation.")))
                    unbind()
                }
                override fun onServiceDisconnected(name: ComponentName) = failed()
                override fun onBindingDied(name: ComponentName) = failed()
                override fun onNullBinding(name: ComponentName) = failed()
            }
            bound.set(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
            if (!bound.get()) continuation.resumeWith(Result.failure(IOException("Couldn't start local recognition.")))
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    runCatching { remote?.send(Message.obtain(null, RecognitionService.CANCEL).apply { data = Bundle().apply { putString("requestId", requestId) } }) }
                    unbind()
                }
            }
        } }
    }
    }
}

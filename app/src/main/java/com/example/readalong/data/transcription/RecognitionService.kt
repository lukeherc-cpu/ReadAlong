package com.example.readalong.data.transcription

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object WhistleNative {
    init { System.loadLibrary("readalong_whistle") }
    external fun transcribe(modelPath: String, pcmPath: String): ByteArray
}

/** Private process owns the upstream non-thread-safe runtime and its bounded model lifetime. */
open class RecognitionService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var activeRequest: String? = null
    // CPUs Android allowed this process when the model (and Whistle's thread pool) was loaded.
    private var loadedCpus: String? = null
    private var restarting = false
    private val cancelled = AtomicBoolean(false)
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == CANCEL) {
                if (message.data.getString("requestId") == activeRequest) cancelOwned()
                return
            }
            if (message.what != RECOGNIZE) return
            val reply = message.replyTo ?: return
            if (restarting) { sendRestart(reply); return }
            if (busy) { send(reply, null, "Speech recognition is busy. Retry shortly."); return }
            // Whistle sizes its thread pool and pins workers to the fast cores it can see when the
            // model loads. When ReadAlong leaves or returns to the screen, Android moves this process
            // to a different CPU set (on many phones the background set lacks the fastest cores), and
            // the pinned workers end up crowded onto too few cores: background chunks ran many times
            // slower. The runtime has no unload or re-pin API, so restart this private process between
            // chunks and let the client retry at once; the new pool matches the current CPU set.
            val cpus = CpuSet.allowed()
            if (loadedCpus != null && cpus != null && cpus != loadedCpus) {
                android.util.Log.i("ReadAlong", "Recognition CPU set changed ($loadedCpus -> $cpus); restarting the runtime.")
                restarting = true
                sendRestart(reply)
                handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
                return
            }
            val model = message.data.getString("model") ?: return
            val pcm = message.data.getString("pcm") ?: return
            if (message.data.getString("backend", "whistle") != "whistle") {
                send(reply, null, "This version uses Cactus Whistle. Rebuild the older transcription."); return
            }
            if (!File(model).canonicalPath.startsWith(filesDir.canonicalPath + "/") ||
                !(File(pcm).canonicalPath.startsWith(cacheDir.canonicalPath + "/") ||
                    File(pcm).canonicalPath.startsWith(File(filesDir, "recognition").canonicalPath + "/"))) {
                send(reply, null, "Speech recognition input is unavailable."); return
            }
            activeRequest = message.data.getString("requestId") ?: return
            if (loadedCpus == null) loadedCpus = cpus
            busy = true; cancelled.set(false)
            try { reply.send(Message.obtain(null, PROGRESS).apply { arg1 = -1 }) } catch (_: RemoteException) { }
            executor.execute {
                try {
                    val result = WhistleNative.transcribe(model, pcm).toString(Charsets.UTF_8)
                    handler.post { busy = false; if (!cancelled.get()) send(reply, result, null) }
                } catch (failure: Throwable) {
                    android.util.Log.w("ReadAlong", "Whistle recognition failed: ${failure.javaClass.simpleName}")
                    val error = when (failure) {
                        is UnsatisfiedLinkError -> "Whistle's Android runtime couldn't load. Use a supported ARM64 device and update ReadAlong."
                        is OutOfMemoryError -> "Not enough free memory for Whistle. Close other apps and retry."
                        else -> "Whistle couldn't process this audio (${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(160)}). Check model storage and retry."
                    }
                    handler.post { busy = false; if (!cancelled.get()) send(reply, null, error) }
                }
            }
        }
    })
    private fun sendRestart(reply: Messenger) {
        try { reply.send(Message.obtain(null, RESULT).apply { data = Bundle().apply { putBoolean("restart", true) } }) }
        catch (_: RemoteException) { }
    }
    private fun send(reply: Messenger, result: String?, error: String?) {
        try { reply.send(Message.obtain(null, RESULT).apply { data = Bundle().apply { putString("result", result); putString("error", error) } }) }
        catch (_: RemoteException) { }
    }
    private fun cancelOwned() {
        cancelled.set(true)
        // No upstream abort API. Killing only this manifest-isolated process stops native
        // inference immediately; the UI/playback and every finished checkpoint survive.
        if (busy) android.os.Process.killProcess(android.os.Process.myPid())
    }
    override fun onCreate() { super.onCreate(); RecognitionProcessLifetime.created() }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { cancelOwned(); return false }
    override fun onDestroy() {
        executor.shutdownNow(); super.onDestroy()
        // Allow a quick rebind before exiting the idle process to release native weights.
        RecognitionProcessLifetime.destroyed()
    }
    companion object { const val RECOGNIZE = 1; const val CANCEL = 2; const val RESULT = 3; const val PROGRESS = 4 }
}
class ModelValidationService : RecognitionService()

/** The CPUs this process may run on, as Android's cpuset currently allows (main thread, never pinned). */
internal object CpuSet {
    fun allowed(): String? = runCatching { parse(java.io.File("/proc/self/status").readText()) }.getOrNull()
    fun parse(status: String): String? = status.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
}

/** Main-thread only. An older service's teardown must not kill a newly bound instance. */
private object RecognitionProcessLifetime {
    private val handler = Handler(Looper.getMainLooper())
    private var instances = 0
    private val exit = Runnable { if (instances == 0) android.os.Process.killProcess(android.os.Process.myPid()) }
    fun created() { instances++; handler.removeCallbacks(exit) }
    fun destroyed() { instances--; if (instances == 0) handler.postDelayed(exit, 2000) }
}

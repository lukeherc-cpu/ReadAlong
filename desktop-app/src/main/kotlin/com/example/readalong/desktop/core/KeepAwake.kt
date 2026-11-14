package com.example.readalong.desktop.core

import java.util.concurrent.CountDownLatch

/**
 * Keeps the computer from going to sleep while a book is being transcribed (the screen may still turn off), so a
 * book left running overnight is done in the morning. Nothing is installed or changed for good: the request ends
 * with [release], and with ReadAlong itself.
 *
 * Windows: SetThreadExecutionState on a thread of its own, which holds the request for as long as it lives.
 * macOS: `caffeinate -i`, tied to this process. Linux: `systemd-inhibit`, where there is one (not inside the Flatpak,
 * which has no way to ask without more permissions; there the system's own sleep settings apply).
 */
object KeepAwake {
    private var holders = 0
    private var release: (() -> Unit)? = null

    @Synchronized fun acquire() { if (holders++ == 0) release = runCatching { start() }.getOrNull() }
    @Synchronized fun release() {
        if (holders == 0) return
        if (--holders == 0) { runCatching { release?.invoke() }; release = null }
    }

    private fun start(): (() -> Unit)? = when {
        AppPaths.isWindows -> windows()
        AppPaths.isMac -> process("caffeinate", "-i", "-w", ProcessHandle.current().pid().toString())
        else -> process("systemd-inhibit", "--what=sleep:idle", "--who=ReadAlong", "--why=Making a ReadAlong", "--mode=block",
            // Ends by itself if ReadAlong goes away without releasing.
            "tail", "--pid=${ProcessHandle.current().pid()}", "-f", "/dev/null")
    }

    private fun windows(): () -> Unit {
        val done = CountDownLatch(1)
        Thread({
            runCatching {
                val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
                kernel.SetThreadExecutionState(com.sun.jna.platform.win32.WinBase.ES_CONTINUOUS or com.sun.jna.platform.win32.WinBase.ES_SYSTEM_REQUIRED)
                done.await()
                kernel.SetThreadExecutionState(com.sun.jna.platform.win32.WinBase.ES_CONTINUOUS)
            }
        }, "ReadAlong keep awake").apply { isDaemon = true }.start()
        return { done.countDown() }
    }

    private fun process(vararg command: String): (() -> Unit)? {
        val started = runCatching { ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() ?: return null
        return { started.descendants().forEach { it.destroy() }; started.destroy() }
    }
}

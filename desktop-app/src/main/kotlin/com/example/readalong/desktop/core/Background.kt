package com.example.readalong.desktop.core

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Only one ReadAlong runs at a time (two would fight over the library, the phone link's port and the speech
 * model). Starting it again asks the one already running to show its window.
 */
object SingleInstance {
    private val lockFile get() = File(AppPaths.data, "running.lock")
    private val showFile get() = File(AppPaths.data, "show-window")
    private var lock: FileLock? = null

    /** True when this is the only ReadAlong; false after asking the other one to come forward. */
    fun claim(): Boolean {
        // If the lock can't even be tried (a read-only folder), carry on as before rather than refuse to start.
        val held = runCatching { RandomAccessFile(lockFile, "rw").channel.tryLock() }.getOrElse { return true }
        if (held != null) { lock = held; runCatching { showFile.delete() }; return true }
        runCatching { showFile.writeText(System.currentTimeMillis().toString()) }
        return false
    }

    /** Whether ReadAlong was started again since the last look. */
    fun showRequested(): Boolean = showFile.isFile && showFile.delete()
}

/** Starts ReadAlong, hidden in the tray, when the person signs in to the computer. Free on every platform: no service, no installer change. */
object StartAtLogin {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "ReadAlong"
    private val agent get() = File(System.getProperty("user.home"), "Library/LaunchAgents/io.github.lukeherc_cpu.ReadAlong.plist")
    private val autostart get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "autostart/readalong.desktop")

    /** The packaged launcher; null when run from Gradle, or inside the Flatpak (whose sandbox can't add a login item). */
    private val launcher: String? get() {
        if (System.getenv("FLATPAK_ID") != null) return null
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        return command.takeUnless { File(it).name.lowercase().removeSuffix(".exe") in setOf("java", "javaw") }
    }

    val available get() = launcher != null

    fun enabled(): Boolean = runCatching {
        when (AppPaths.os()) {
            "windows" -> Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
            "macos" -> agent.isFile
            else -> autostart.isFile
        }
    }.getOrDefault(false)

    /** Returns whether it is now set as asked. */
    fun set(on: Boolean): Boolean = runCatching {
        val exe = launcher ?: return false
        when (AppPaths.os()) {
            "windows" -> if (on) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME, "\"$exe\" --background")
                else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
            "macos" -> if (on) { agent.parentFile.mkdirs(); agent.writeText(plist(exe)) } else agent.delete()
            else -> if (on) { autostart.parentFile.mkdirs(); autostart.writeText(desktopEntry(exe)) } else autostart.delete()
        }
        enabled() == on
    }.getOrDefault(false)

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal fun plist(exe: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>io.github.lukeherc_cpu.ReadAlong</string>
  <key>ProgramArguments</key><array><string>${xml(exe)}</string><string>--background</string></array>
  <key>RunAtLoad</key><true/>
</dict></plist>
"""

    internal fun desktopEntry(exe: String) = "[Desktop Entry]\nType=Application\nName=ReadAlong\nExec=\"${exe.replace("\\", "\\\\").replace("\"", "\\\"")}\" --background\nX-GNOME-Autostart-enabled=true\n"
}

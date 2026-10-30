package com.example.readalong.desktop.core

import java.io.File

/**
 * Where ReadAlong keeps its library, transcripts and speech models: %APPDATA%\ReadAlong on Windows,
 * ~/Library/Application Support/ReadAlong on a Mac, and $XDG_DATA_HOME/ReadAlong on Linux (inside the
 * Flatpak that is ~/.var/app/<app id>/data/ReadAlong).
 */
object AppPaths {
    val data: File by lazy {
        val override = System.getProperty("readalong.home") ?: System.getenv("READALONG_HOME")
        val home = System.getProperty("user.home")
        val base = override?.let(::File) ?: when (os()) {
            "windows" -> System.getenv("APPDATA")?.let { File(it, "ReadAlong") }
            "macos" -> File(home, "Library/Application Support/ReadAlong")
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "ReadAlong") }
        } ?: File(home, ".local/share/ReadAlong")
        base.apply { mkdirs() }
    }
    val library get() = File(data, "library.json")
    val settings get() = File(data, "settings.json")
    val models get() = File(data, "models").apply { mkdirs() }
    val covers get() = File(data, "covers").apply { mkdirs() }
    fun book(id: String) = File(File(data, "books"), id).apply { mkdirs() }

    /** whisper.cpp and its GPU/CPU backends: packaged beside the app, or native/build/resources when run from Gradle. */
    val whisperLibraries: File? get() = listOfNotNull(
        System.getProperty("readalong.native")?.let(::File),
        System.getProperty("compose.application.resources.dir")?.let { File(it, "whisper") },
        File("native/build/resources/${os()}/whisper"),
    ).firstOrNull { it.isDirectory }

    fun os() = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("mac" in it) "macos" else "linux" }
    val isWindows get() = os() == "windows"
    val isMac get() = os() == "macos"
    /** "Windows", "macOS" or "Linux": shown in Settings and written to the progress file the phone reads. */
    val platformName get() = when (os()) { "windows" -> "Windows"; "macos" -> "macOS"; else -> "Linux" }
    /** How shortcut labels name the modifier key. */
    val shortcutKey get() = if (isMac) "⌘" else "Ctrl+"
}

/** Writes through a temporary file, so a crash or power loss never leaves half a file. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val temporary = File(parentFile, "$name.tmp")
    temporary.writeText(text)
    java.nio.file.Files.move(temporary.toPath(), toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
}

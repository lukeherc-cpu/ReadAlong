package com.example.readalong.desktop.core

import com.example.readalong.compat.*
import java.io.File
import platform.Foundation.*

/**
 * Where ReadAlong for iPhone keeps things. The library, settings, transcripts and speech models live in Application
 * Support (backed up, never shown). Audiobooks live in Documents/Audiobooks, which the Files app shows under
 * "On My iPhone › ReadAlong", so books can be copied in from a computer, iCloud Drive or AirDrop.
 */
object AppPaths {
    private fun directory(kind: NSSearchPathDirectory): File =
        File((NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String))

    val data: File by lazy { File(directory(NSApplicationSupportDirectory), "ReadAlong").apply { mkdirs() } }
    /** The audiobook folder the library scans, in the Files app. */
    val audiobooks: File by lazy { File(directory(NSDocumentDirectory), "Audiobooks").apply { mkdirs() } }
    val library get() = File(data, "library.json")
    val settings get() = File(data, "settings.json")
    val models get() = File(data, "models").apply { mkdirs() }
    val covers get() = File(data, "covers").apply { mkdirs() }
    fun book(id: String) = File(File(data, "books"), id).apply { mkdirs() }

    /** whisper.cpp is linked into the app itself on iOS. */
    val whisperLibraries: File? get() = File(NSBundle.mainBundle.bundlePath)

    fun os() = "ios"
    val isWindows get() = false
    val isMac get() = false
    /** Written to the progress files the other devices read, and shown in Settings. */
    val platformName get() = "iPhone"
    val shortcutKey get() = "⌘"
    /** The app bundle's own files: fonts, icons, the version. */
    fun resource(path: String): File = File(NSBundle.mainBundle.resourcePath + "/" + path)
}

/** Writes through a temporary file, so a crash or power loss never leaves half a file. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val temporary = File(parentFile, "$name.${java.util.UUID.randomUUID()}.tmp")
    try {
        temporary.writeText(text)
        if (!temporary.renameTo(this)) throw java.io.IOException("Couldn't save $name")
    } finally { temporary.delete() }
}

fun File.readTextRetrying(): String = readText()

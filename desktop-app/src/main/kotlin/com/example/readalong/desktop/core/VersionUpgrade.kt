package com.example.readalong.desktop.core

import java.io.File

/**
 * Keeps a copy of the small data files (library, settings, linked phones) each time a different version of
 * ReadAlong starts, so going back to an older version, or a newer one that can't read something, never
 * loses the library. Copies live in `<data>/backups/<version>/`; the five most recent are kept.
 */
object VersionUpgrade {
    private val files = listOf("library.json", "settings.json", "link.json")
    private const val KEEP = 5

    /** The version that ran before this one, or null on a first run or when it's the same version. */
    fun run(current: String, data: File = AppPaths.data): String? = runCatching {
        val marker = File(data, "last-version")
        val previous = marker.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
        if (previous == current) return null
        // Older versions didn't write the marker; their files still get backed up.
        val existing = files.map { File(data, it) }.filter { it.isFile }
        if (existing.isNotEmpty()) {
            val folder = File(File(data, "backups"), previous ?: "before-$current").apply { mkdirs() }
            existing.forEach { it.copyTo(File(folder, it.name), overwrite = true) }
            prune(File(data, "backups"))
        }
        marker.writeAtomically(current)
        previous
    }.getOrNull()

    /** Sets aside a file this version couldn't read, so saving over it doesn't destroy what a newer version wrote. */
    fun setAside(file: File): File? = runCatching {
        if (!file.isFile) return null
        File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}").also { file.copyTo(it) }
    }.getOrNull()

    private fun prune(backups: File) {
        val all = backups.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        all.drop(KEEP).forEach { it.deleteRecursively() }
    }
}

/** Names the phone's version, and which side to update when they differ (both keep syncing what they share). */
fun versionNote(phone: String?, computer: String?): String? {
    val p = com.example.readalong.domain.update.AppVersion.parse(phone)
    val c = com.example.readalong.domain.update.AppVersion.parse(computer)
    return when {
        phone == null -> null // not synced since this version, or a phone on 0.44.1 or earlier
        p == null || c == null || p.compareTo(c) == 0 -> "ReadAlong $phone"
        p < c -> "ReadAlong $phone, older than this computer's. Update the phone to sync everything this version can."
        else -> "ReadAlong $phone, newer than this computer's. Update ReadAlong here to sync everything the phone can."
    }
}

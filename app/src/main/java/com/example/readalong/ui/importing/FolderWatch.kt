package com.example.readalong.ui.importing

/**
 * When ReadAlong, while on screen, rescans its saved folders without being asked. Snapshots are
 * [com.example.readalong.data.importing.AudioImporter.folderSnapshot]s, by folder URI.
 */
internal object FolderWatch {
    /** How often the folders' top levels are looked at while ReadAlong is on screen. */
    const val CHECK_MS = 5_000L
    /** Two background scans this close together would only repeat each other. */
    const val SPACING_MS = 20_000L
    /** A full rescan anyway, for changes deeper than a snapshot sees (Author/Book/files). */
    const val FULL_RESCAN_MS = 10 * 60_000L
    /** Switching back to ReadAlong within this long of a scan rescans only if a folder looks different. */
    const val RESUME_MS = 30_000L

    /**
     * True when a folder that was scanned now looks different, and looked the same at the last check (so a
     * book still being copied in is left to finish). A folder added since the last scan doesn't count: adding
     * it scanned it.
     */
    fun changed(scanned: Map<String, Int>?, previous: Map<String, Int>?, now: Map<String, Int>): Boolean {
        if (scanned == null || now != previous) return false
        return now.any { (folder, sign) -> scanned[folder]?.let { it != sign } == true }
    }

    fun shouldScan(scanned: Map<String, Int>?, previous: Map<String, Int>?, now: Map<String, Int>, sinceScanMs: Long): Boolean =
        sinceScanMs >= FULL_RESCAN_MS || sinceScanMs >= SPACING_MS && changed(scanned, previous, now)

    /** On switching back to ReadAlong: always after a while, and sooner if a book arrived meanwhile. */
    fun scanOnResume(scanned: Map<String, Int>?, now: Map<String, Int>, sinceScanMs: Long?): Boolean =
        sinceScanMs == null || sinceScanMs >= RESUME_MS || scanned == null || now.any { (folder, sign) -> scanned[folder]?.let { it != sign } == true }
}

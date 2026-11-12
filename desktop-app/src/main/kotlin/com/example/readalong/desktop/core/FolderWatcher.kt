package com.example.readalong.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * Calls [onChange] soon after a book appears, changes or goes in a saved library folder, at any depth, so a book
 * copied in shows up without "Check for new books". It waits until the folder has been quiet for [quietMs], so a
 * book still being copied is scanned once, when it's whole.
 *
 * Only audio files and folders count: ReadAlong's own writes beside a book (.readalong, progress, bookmarks) and
 * hidden temporary files never set off a rescan, so a rescan can't set off another.
 */
class FolderWatcher(private val folders: StateFlow<List<String>>, private val onChange: suspend () -> Unit, private val quietMs: Long = 3_000) {
    /** Runs until cancelled. Folders that can't be watched (gone, unplugged, no permission) are left to manual rescans. */
    suspend fun run() = withContext(Dispatchers.IO) {
        val service = try { FileSystems.getDefault().newWatchService() } catch (_: Exception) { return@withContext }
        service.use {
            val keys = mutableMapOf<WatchKey, Path>()
            var watched: List<String>? = null
            var changedAt: Long? = null
            while (isActive) {
                val current = folders.value
                if (current != watched) {
                    keys.keys.forEach { it.cancel() }; keys.clear()
                    current.map { Path.of(it) }.filter { Files.isDirectory(it) }.forEach { register(service, it, keys) }
                    watched = current
                }
                val key = try { service.poll(500, TimeUnit.MILLISECONDS) } catch (_: ClosedWatchServiceException) { return@withContext }
                if (key != null) {
                    val directory = keys[key]
                    for (event in key.pollEvents()) {
                        if (event.kind() == OVERFLOW) { changedAt = System.currentTimeMillis(); continue }
                        val name = event.context() as? Path ?: continue
                        val child = directory?.resolve(name) ?: continue
                        val isFolder = Files.isDirectory(child)
                        if (event.kind() == ENTRY_CREATE && isFolder) register(service, child, keys)
                        // Windows also reports a folder as modified when a file inside it is written; that file's own event decides.
                        if (event.kind() == ENTRY_MODIFY && isFolder) continue
                        if (counts(name.toString(), isFolder, deleted = event.kind() == ENTRY_DELETE)) changedAt = System.currentTimeMillis()
                    }
                    if (!key.reset()) keys.remove(key)
                }
                val since = changedAt
                if (since != null && System.currentTimeMillis() - since >= quietMs) {
                    changedAt = null
                    try { onChange() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The next change, or a manual rescan, tries again. */ }
                }
            }
        }
    }

    private fun register(service: WatchService, root: Path, keys: MutableMap<WatchKey, Path>) {
        try {
            Files.walk(root).use { paths ->
                paths.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.forEach { folder ->
                    try { keys[folder.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)] = folder } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        /** Whether a change to [name] can mean a book came, changed or went. A deleted folder no longer says it was one. */
        fun counts(name: String, isFolder: Boolean, deleted: Boolean): Boolean {
            if (name.startsWith(".")) return false
            val type = name.substringAfterLast('.', "").lowercase()
            val folderLike = type.isEmpty() || type.length > 5 || !type.all { it.isLetterOrDigit() }
            return isFolder || type in Scanner.AUDIO || deleted && folderLike
        }
    }
}

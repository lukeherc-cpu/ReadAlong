package com.example.readalong.desktop.platform

import com.example.readalong.compat.*
import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.writeAtomically
import java.io.File
import kotlinx.cinterop.*
import org.json.JSONObject
import platform.Foundation.*
import platform.UIKit.*

/** The view controller on top, to present pickers, sign-in pages and share sheets from. */
fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>().flatMap { it.windows.filterIsInstance<UIWindow>() }.firstOrNull { it.isKeyWindow() }
        ?: UIApplication.sharedApplication.keyWindow
    var top = window?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/**
 * Folders outside the app (iCloud Drive, a USB drive, another app's folder) need iOS's permission each launch.
 * The picker grants it once; a bookmark saved here asks for it again at the next start, and the folder keeps its
 * path for the library.
 */
object FolderAccess {
    private val file get() = File(AppPaths.data, "folder-bookmarks.json")
    private val open = HashMap<String, NSURL>()

    fun remember(url: NSURL): File? {
        url.startAccessingSecurityScopedResource()
        val path = url.path ?: return null
        open[path] = url
        val data = url.bookmarkDataWithOptions(0u, null, null, null)
        if (data != null) {
            val saved = load()
            saved.put(path, data.base64EncodedStringWithOptions(0u))
            runCatching { file.writeAtomically(saved.toString()) }
        }
        return File(path)
    }

    /** At launch: opens every folder chosen before. A folder that moved is saved under its new path. */
    fun restoreAll() {
        val saved = load()
        for (path in saved.keySet().toList()) {
            val data = NSData.create(base64EncodedString = saved.getString(path), options = 0u) ?: continue
            memScoped {
                val stale = alloc<BooleanVar>()
                val url = NSURL.URLByResolvingBookmarkData(data, 0u, null, stale.ptr, null) ?: return@memScoped
                if (url.startAccessingSecurityScopedResource()) open[url.path ?: path] = url
            }
        }
    }

    private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
}

/** Keeps the screen on while a book is transcribing in the foreground (iOS stops apps soon after they leave the screen). */
fun keepScreenOn(on: Boolean) {
    NSOperationQueue.mainQueue.addOperationWithBlock { UIApplication.sharedApplication.idleTimerDisabled = on }
}

fun NSData.toByteArray(): ByteArray {
    val n = length.toInt(); if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) } }
}
fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }

/** Runs [block] on the main thread: now when already there, else as soon as the main queue gets to it. */
fun onMain(block: () -> Unit) {
    if (NSThread.isMainThread) block() else NSOperationQueue.mainQueue.addOperationWithBlock(block)
}

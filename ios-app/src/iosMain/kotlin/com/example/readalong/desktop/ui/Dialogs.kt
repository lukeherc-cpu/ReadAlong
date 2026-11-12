// The desktop's file and folder dialogs, for iOS: the system document picker. The desktop calls block until
// something is chosen; here the build turns each `chooseFile(…)?.let { … }` into `pickFile(…) { … }` (see
// ios-app/build.gradle.kts), and the block runs once the person has picked.
package com.example.readalong.desktop.ui

import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.Scanner
import com.example.readalong.desktop.platform.FolderAccess
import com.example.readalong.desktop.platform.topViewController
import java.io.File
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/** Kept alive while its picker is on screen (UIKit holds delegates weakly). */
private var activeDelegate: NSObject? = null

private fun present(types: List<UTType>, folder: Boolean, onPicked: (List<NSURL>) -> Unit) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = !folder)
    picker.allowsMultipleSelection = false
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            activeDelegate = null
            onPicked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
        }
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { activeDelegate = null }
    }
    activeDelegate = delegate
    picker.delegate = delegate
    topViewController()?.presentViewController(picker, animated = true, completion = null)
}

private fun type(extension: String): UTType = UTType.typeWithFilenameExtension(extension) ?: UTTypeItem

/** One file, copied into the app (the picker's copy), so it stays readable. */
fun pickFile(title: String, extension: String, onPicked: (File) -> Unit) {
    present(listOf(type(extension)), folder = false) { urls -> urls.firstOrNull()?.path?.let { onPicked(File(it)) } }
}

/**
 * A folder anywhere the Files app reaches (On My iPhone, iCloud Drive, a USB drive). ReadAlong keeps a bookmark to
 * it, so the library can read it again after the app restarts ([FolderAccess]).
 */
fun pickFolder(title: String = "Choose your audiobook folder", onPicked: (File) -> Unit) {
    present(listOf(UTTypeFolder), folder = true) { urls -> urls.firstOrNull()?.let { FolderAccess.remember(it) }?.let(onPicked) }
}

/** One audiobook file. */
fun pickAudioFile(title: String = "Choose an audiobook file", onPicked: (File) -> Unit) {
    val types = Scanner.AUDIO.mapNotNull { UTType.typeWithFilenameExtension(it) } + UTTypeAudio
    present(types.distinct(), folder = false) { urls ->
        // The picker's copy lives in a temporary folder; the library keeps books in Audiobooks, in the Files app.
        urls.firstOrNull()?.path?.let { path ->
            val copy = File(AppPaths.audiobooks, File(path).name)
            val source = File(path)
            if (!copy.exists()) source.renameTo(copy)
            onPicked(if (copy.exists()) copy else source)
        }
    }
}

fun pickImage(onPicked: (File) -> Unit) {
    present(listOf(UTTypeImage), folder = false) { urls -> urls.firstOrNull()?.path?.let { onPicked(File(it)) } }
}

/** Saving goes to ReadAlong's folder in the Files app (On My iPhone › ReadAlong), where it can be shared on. */
fun pickSave(title: String, suggested: File, onPicked: (File) -> Unit) {
    val name = if (suggested.name.endsWith(".readalong", true)) suggested.name else "${suggested.name}.readalong"
    val exports = File(AppPaths.audiobooks.parentFile, "Exports").apply { mkdirs() }
    onPicked(File(exports, name))
}

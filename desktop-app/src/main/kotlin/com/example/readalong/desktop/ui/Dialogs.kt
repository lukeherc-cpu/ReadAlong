package com.example.readalong.desktop.ui

import com.example.readalong.desktop.core.AppPaths
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Native-looking file and folder pickers (Windows' and GTK's own look via the system look and feel, the Finder's panel on a Mac). */
private fun systemLook() { runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } }

fun chooseFolder(title: String = "Choose your audiobook folder"): File? {
    if (AppPaths.isMac) {
        // The Swing chooser looks foreign on a Mac; the AWT dialog is the real Finder panel, in folder mode.
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { isVisible = true }
            return dialog.files.firstOrNull()
        } finally { System.setProperty("apple.awt.fileDialogForDirectories", "false") }
    }
    systemLook()
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        isMultiSelectionEnabled = false
        isAcceptAllFileFilterUsed = true
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun chooseFile(title: String, extension: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        file = "*.$extension"
        setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
        isVisible = true
    }
    return dialog.files.firstOrNull()
}

fun chooseSave(title: String, suggested: File): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
        directory = suggested.parent; file = suggested.name; isVisible = true
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, if (name.endsWith(".readalong", true)) name else "$name.readalong")
}

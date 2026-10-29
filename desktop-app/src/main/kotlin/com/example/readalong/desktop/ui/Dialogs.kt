package com.example.readalong.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Native-looking file and folder pickers (Windows' own look via the system look and feel). */
private fun systemLook() { runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } }

fun chooseFolder(title: String = "Choose your audiobook folder"): File? {
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

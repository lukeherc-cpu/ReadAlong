package com.example.readalong.desktop.ui

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.readalong.desktop.core.AppPaths
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

interface Dwm : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
}

/** Windows 10/11: a title bar in the app's own colour (dark or light, following the theme) instead of the default white one. */
fun styleTitleBar(window: ComposeWindow, caption: Color, dark: Boolean = true) {
    // macOS: the title bar follows the window's appearance.
    if (AppPaths.isMac) runCatching { window.rootPane.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua") }
    if (!AppPaths.isWindows) return
    runCatching {
        val dwm = Native.load("dwmapi", Dwm::class.java)
        val hwnd = WinDef.HWND(Pointer(window.windowHandle))
        dwm.DwmSetWindowAttribute(hwnd, 20, IntByReference(if (dark) 1 else 0), 4) // DWMWA_USE_IMMERSIVE_DARK_MODE
        val argb = caption.toArgb()
        val colorref = (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb shr 16 and 0xFF) // 0x00BBGGRR
        dwm.DwmSetWindowAttribute(hwnd, 35, IntByReference(colorref), 4) // DWMWA_CAPTION_COLOR (Windows 11)
    }
}

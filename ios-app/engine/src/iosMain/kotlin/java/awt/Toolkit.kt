package java.awt

import java.awt.datatransfer.Clipboard

/** Only the clipboard, which the shared screens use for "Copy" buttons: the iPhone's general pasteboard. */
class Toolkit private constructor() {
    val systemClipboard = Clipboard()
    companion object {
        private val instance = Toolkit()
        fun getDefaultToolkit() = instance
    }
}

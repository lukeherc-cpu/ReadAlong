package java.awt.datatransfer

import platform.UIKit.UIPasteboard

class DataFlavor private constructor(val name: String) {
    companion object {
        val stringFlavor = DataFlavor("string")
        val javaFileListFlavor = DataFlavor("files")
    }
}

interface Transferable {
    fun isDataFlavorSupported(flavor: DataFlavor): Boolean
    fun getTransferData(flavor: DataFlavor): Any
}
interface ClipboardOwner

class StringSelection(private val text: String) : Transferable {
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
    override fun getTransferData(flavor: DataFlavor): Any = text
}

class UnsupportedFlavorException(flavor: DataFlavor) : Exception(flavor.name)

class Clipboard internal constructor() {
    fun setContents(contents: Transferable, owner: ClipboardOwner?) {
        if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) UIPasteboard.generalPasteboard.string = contents.getTransferData(DataFlavor.stringFlavor) as String
    }
    fun getData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.stringFlavor) throw UnsupportedFlavorException(flavor)
        return UIPasteboard.generalPasteboard.string ?: throw UnsupportedFlavorException(flavor)
    }
    fun isDataFlavorAvailable(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor && UIPasteboard.generalPasteboard.hasStrings
}

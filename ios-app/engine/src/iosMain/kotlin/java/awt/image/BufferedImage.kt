// The few java.awt imaging calls the desktop app makes (cover scaling and JPEG writing), over Skia, which Compose
// already ships on iOS. Covers.kt and Companions.kt then compile for the iPhone unchanged.
package java.awt.image

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class BufferedImage internal constructor(internal var image: Image) {
    constructor(width: Int, height: Int, @Suppress("UNUSED_PARAMETER") type: Int) : this(blank(width, height))
    val width: Int get() = image.width
    val height: Int get() = image.height
    fun getWidth() = width
    fun getHeight() = height
    private var pixels: Bitmap? = null
    fun getRGB(x: Int, y: Int): Int {
        val bitmap = pixels ?: Bitmap.makeFromImage(image).also { pixels = it }
        return bitmap.getColor(x, y)
    }
    fun createGraphics(): java.awt.Graphics2D = java.awt.Graphics2D(this).also { pixels = null }

    companion object {
        const val TYPE_INT_RGB = 1
        const val TYPE_INT_ARGB = 2
        private fun blank(w: Int, h: Int): Image {
            val bitmap = Bitmap(); bitmap.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL))
            bitmap.erase(0)
            return Image.makeFromBitmap(bitmap)
        }
    }
}

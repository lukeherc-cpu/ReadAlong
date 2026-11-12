package java.awt

import java.awt.image.BufferedImage
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class Color(val rgb: Int) {
    constructor(r: Int, g: Int, b: Int) : this((0xff shl 24) or (r shl 16) or (g shl 8) or b)
    companion object {
        val WHITE = Color(0xffffffff.toInt())
        val BLACK = Color(0xff000000.toInt())
        fun HSBtoRGB(hue: Float, saturation: Float, brightness: Float): Int {
            var r = 0; var g = 0; var b = 0
            if (saturation == 0f) { r = (brightness * 255f + .5f).toInt(); g = r; b = r }
            else {
                val h = (hue - kotlin.math.floor(hue)) * 6f
                val f = h - kotlin.math.floor(h)
                val p = brightness * (1f - saturation); val q = brightness * (1f - saturation * f); val t = brightness * (1f - saturation * (1f - f))
                fun c(v: Float) = (v * 255f + .5f).toInt()
                when (h.toInt()) {
                    0 -> { r = c(brightness); g = c(t); b = c(p) }
                    1 -> { r = c(q); g = c(brightness); b = c(p) }
                    2 -> { r = c(p); g = c(brightness); b = c(t) }
                    3 -> { r = c(p); g = c(q); b = c(brightness) }
                    4 -> { r = c(t); g = c(p); b = c(brightness) }
                    else -> { r = c(brightness); g = c(p); b = c(q) }
                }
            }
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        fun RGBtoHSB(r: Int, g: Int, b: Int, hsbvals: FloatArray?): FloatArray {
            val out = hsbvals ?: FloatArray(3)
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val brightness = max / 255f
            val saturation = if (max != 0) (max - min).toFloat() / max else 0f
            var hue = 0f
            if (saturation != 0f) {
                val rc = (max - r).toFloat() / (max - min); val gc = (max - g).toFloat() / (max - min); val bc = (max - b).toFloat() / (max - min)
                hue = when (max) { r -> bc - gc; g -> 2f + rc - bc; else -> 4f + gc - rc } / 6f
                if (hue < 0) hue += 1f
            }
            out[0] = hue; out[1] = saturation; out[2] = brightness
            return out
        }
    }
}

object RenderingHints {
    val KEY_INTERPOLATION = Any(); val VALUE_INTERPOLATION_BICUBIC = Any(); val VALUE_INTERPOLATION_BILINEAR = Any()
    val KEY_RENDERING = Any(); val VALUE_RENDER_QUALITY = Any()
    val KEY_ANTIALIASING = Any(); val VALUE_ANTIALIAS_ON = Any()
}

/** Draws into a [BufferedImage] through a Skia surface; the image is replaced when [dispose] is called. */
class Graphics2D internal constructor(private val target: BufferedImage) {
    private val surface = Surface.makeRasterN32Premul(target.width, target.height)
    init { surface.canvas.drawImage(target.image, 0f, 0f) }
    var color: Color = Color.BLACK
    fun setRenderingHint(@Suppress("UNUSED_PARAMETER") key: Any, @Suppress("UNUSED_PARAMETER") value: Any) {}
    fun fillRect(x: Int, y: Int, w: Int, h: Int) {
        surface.canvas.drawRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()), Paint().apply { color = this@Graphics2D.color.rgb })
    }
    fun drawImage(image: BufferedImage, x: Int, y: Int, w: Int, h: Int, @Suppress("UNUSED_PARAMETER") observer: Any?): Boolean {
        surface.canvas.drawImageRect(image.image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), Paint(), true)
        return true
    }
    fun dispose() { target.image = surface.makeImageSnapshot() }
}

/** Opens links in Safari (About, the tip jar, release notes). */
class Desktop private constructor() {
    fun browse(uri: java.net.URI) {
        platform.Foundation.NSURL.URLWithString(uri.toString())?.let { platform.UIKit.UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
    }
    fun open(file: java.io.File) { browse(java.net.URI("shareddocuments://" + file.absolutePath)) }
    fun isSupported(@Suppress("UNUSED_PARAMETER") action: Action) = true
    enum class Action { BROWSE, OPEN }
    companion object {
        fun isDesktopSupported() = true
        fun getDesktop() = Desktop()
    }
}

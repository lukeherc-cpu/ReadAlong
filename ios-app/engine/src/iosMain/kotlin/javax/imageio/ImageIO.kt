package javax.imageio

import com.example.readalong.compat.readBytes
import com.example.readalong.compat.writeBytes
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

object ImageIO {
    fun read(input: InputStream): BufferedImage? = decode(input.readAllBytes())
    fun read(file: File): BufferedImage? = if (file.isFile) decode(file.readBytes()) else null
    private fun decode(bytes: ByteArray): BufferedImage? = runCatching { BufferedImage(Image.makeFromEncoded(bytes)) }.getOrNull()
    fun write(image: BufferedImage, format: String, file: File): Boolean {
        val type = when (format.lowercase()) { "jpg", "jpeg" -> EncodedImageFormat.JPEG; "png" -> EncodedImageFormat.PNG; else -> return false }
        val data = image.image.encodeToData(type, 90) ?: return false
        file.writeBytes(data.bytes)
        return true
    }
}

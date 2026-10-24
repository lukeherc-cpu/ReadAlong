package com.example.readalong.data.preferences

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.readalong.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ReaderFonts {
    val bundled = listOf(
        Triple("JAKARTA", "Plus Jakarta Sans", R.font.jakarta_extrabold),
        Triple("LITERATA", "Literata", R.font.literata), Triple("LORA", "Lora", R.font.lora),
        Triple("MERRIWEATHER", "Merriweather", R.font.merriweather), Triple("BASKERVILLE", "Libre Baskerville", R.font.librebaskerville),
        Triple("GARAMOND", "EB Garamond", R.font.ebgaramond), Triple("SOURCE_SERIF", "Source Serif 4", R.font.sourceserif4),
        Triple("OPEN_SANS", "Open Sans", R.font.opensans), Triple("LATO", "Lato", R.font.lato),
        Triple("MONTSERRAT", "Montserrat", R.font.montserrat), Triple("BITTER", "Bitter", R.font.bitter))
    fun valid(key: String) = key in setOf("EPUB", "MATCH", "SERIF", "SANS", "MONO") || bundled.any { it.first == key } || key.matches(Regex("CUSTOM_[a-f0-9]{64}"))
    fun file(context: Context, key: String): File? = key.takeIf { it.startsWith("CUSTOM_") && valid(it) }?.let { File(context.filesDir, "reader-fonts/$it.font") }
    suspend fun imported(context: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        File(context.filesDir, "reader-fonts").listFiles().orEmpty().filter { it.extension == "name" && file(context, it.nameWithoutExtension)?.exists() == true }
            .map { it.nameWithoutExtension to it.readText().take(120) }.sortedBy { it.second.lowercase() }
    }
    suspend fun import(context: Context, uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported font"
        val directory = File(context.filesDir, "reader-fonts").apply { mkdirs() }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192); var count: Int; var total = 0
                while (input.read(buffer).also { count = it } != -1) {
                    total += count; require(total <= 20 * 1024 * 1024) { "Choose a font smaller than 20 MB." }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            val header = ByteArray(4).also { bytes -> java.io.DataInputStream(temporary.inputStream()).use { it.readFully(bytes) } }
            require(header.contentEquals(byteArrayOf(0,1,0,0)) || String(header, Charsets.US_ASCII) in setOf("OTTO", "ttcf", "true")) { "Choose a TTF, OTF or TTC font file." }
            android.graphics.Typeface.createFromFile(temporary) // Reject malformed fonts before saving preferences.
            val key = "CUSTOM_" + digest.digest().joinToString("") { "%02x".format(it) }
            val label = name.substringBeforeLast('.').take(120).ifBlank { "Imported font" }
            val destination = file(context, key)!!
            if (!destination.exists()) check(temporary.renameTo(destination)) { "Couldn't save this font. Try again." }
            File(directory, "$key.name").writeText(label)
            key to label
        } finally { temporary.delete() }
    }
}

package com.example.readalong.data.link

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * SHA-256 of an audio file, remembered with its size and modification time. Writing or reading a `.readalong`
 * matches audio by its bytes, and reading every byte of a large audiobook again on each scan and sync kept the
 * storage busy for minutes; an unchanged file is now hashed once.
 */
object AudioHashes {
    /** The file's size and lower-case hex SHA-256. */
    suspend fun sha256(context: Context, database: ReadAlongDatabase, uri: String): Pair<Long, String> {
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val stamp = runCatching {
            resolver.query(parsed, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) "${cursor.getLong(0)}:${cursor.getLong(1)}" else null
            }
        }.getOrNull()
        val key = "audio_sha256:$uri"
        val books = database.bookDao()
        if (stamp != null) books.metadata(key)?.split(':')?.takeIf { it.size == 3 && "${it[0]}:${it[1]}" == stamp }?.let { return it[0].toLong() to it[2] }
        val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
        requireNotNull(resolver.openInputStream(parsed)).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; size += n; hash.update(buffer, 0, n) }
        }
        val hex = hash.digest().joinToString("") { "%02x".format(it) }
        // Only when the provider's size agrees with what was read: otherwise the file changed while it was hashed.
        if (stamp != null && stamp.substringBefore(':').toLongOrNull() == size) books.putMetadata(LibraryMetadataEntity(key, "$stamp:$hex"))
        return size to hex
    }
}

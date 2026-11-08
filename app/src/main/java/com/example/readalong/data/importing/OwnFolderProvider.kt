package com.example.readalong.data.importing

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * ReadAlong's own "Audible Audiobooks" folder, served as a document tree so it is a library folder
 * like any the person chose: it is scanned, listed on the folders page and can hold ReadAlongs and
 * EPUBs beside the books. It also shows in the system Files app under ReadAlong once it exists.
 *
 * On Android 11 and later the books live in the phone's shared "Documents/Audible Audiobooks", so they
 * stay when ReadAlong is uninstalled or its storage is cleared, and can be copied over USB. Documents
 * (unlike Audiobooks) also takes the EPUB, cover and ReadAlong files saved beside a book. Older Android
 * needs a storage permission for that, so there the folder stays in ReadAlong's own storage.
 *
 * Books saved in ReadAlong's own storage by 0.49.0 and 0.49.1 move to the shared folder ([migrate]).
 * Document ids are paths inside the folder, the same in either place, so a book keeps its id, progress
 * and ReadAlong when it moves; until it has moved it's served from where it is.
 *
 * ReadAlong reaches its own provider without a permission grant, so the folder never needs choosing.
 */
class OwnFolderProvider : DocumentsProvider() {
    override fun onCreate() = true

    private val ctx get() = requireNotNull(context)

    /** The path inside the folder that [documentId] names: "" for the folder itself. */
    private fun path(documentId: String): String {
        if (documentId == ROOT) return ""
        if (!documentId.startsWith("$ROOT/")) throw FileNotFoundException(documentId)
        val path = documentId.removePrefix("$ROOT/")
        // Never step outside the folder, whatever an id says.
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw FileNotFoundException(documentId)
        return path
    }

    /** Where [documentId] is now: the shared folder, or ReadAlong's own storage for a book not moved yet. New files go to the shared folder. */
    private fun file(documentId: String): File = locate(ctx, path(documentId))

    private fun id(path: String) = if (path.isEmpty()) ROOT else "$ROOT/$path"

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        if (!base(ctx).isDirectory && !private(ctx).isDirectory) return@apply
        newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT).add(Root.COLUMN_TITLE, NAME)
            .add(Root.COLUMN_SUMMARY, "ReadAlong").add(Root.COLUMN_ICON, ctx.applicationInfo.icon)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_AVAILABLE_BYTES, base(ctx).let { if (it.exists()) it.freeSpace else ctx.filesDir.freeSpace })
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        val path = path(documentId)
        val file = locate(ctx, path)
        // The folder itself always exists for a scan: an empty folder is just empty.
        if (path.isEmpty()) base(ctx).mkdirs() else if (!file.exists()) throw FileNotFoundException(documentId)
        row(this, path, file)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            val parent = path(parentDocumentId)
            // The shared folder first, then anything still in ReadAlong's own storage, each name once.
            val names = linkedSetOf<String>()
            bases(ctx).forEach { base -> (if (parent.isEmpty()) base else File(base, parent)).list()?.let { names += it.sorted() } }
            names.filterNot { it.endsWith(PARTIAL) }.forEach { name ->
                val path = if (parent.isEmpty()) name else "$parent/$name"
                row(this, path, locate(ctx, path))
            }
        }

    private fun row(cursor: MatrixCursor, path: String, file: File) {
        val directory = path.isEmpty() || file.isDirectory
        val flags = (if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id(path)).add(Document.COLUMN_DISPLAY_NAME, if (path.isEmpty()) NAME else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (directory) Document.MIME_TYPE_DIR else mime(file)).add(Document.COLUMN_SIZE, if (directory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified()).add(Document.COLUMN_FLAGS, flags)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        runCatching { path(documentId); parentDocumentId == ROOT || documentId.startsWith("$parentDocumentId/") }.getOrDefault(false)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = file(documentId)
        if ('w' in mode) file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = path(parentDocumentId)
        val directory = (if (parent.isEmpty()) base(ctx) else File(base(ctx), parent)).apply { mkdirs() }
        val stem = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        // A name taken in either place, or by a file this install can't see, gets a number instead.
        fun taken(name: String) = locate(ctx, if (parent.isEmpty()) name else "$parent/$name").exists()
        var name = displayName
        var copy = 1
        while (true) {
            if (!taken(name)) {
                val target = File(directory, name)
                val made = runCatching { if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile() }.getOrDefault(false)
                if (made) return id(if (parent.isEmpty()) name else "$parent/$name")
            }
            if (copy > 50) throw FileNotFoundException("Couldn't create $displayName")
            name = "$stem (${copy++})$extension"
        }
    }

    override fun deleteDocument(documentId: String) {
        val path = path(documentId)
        if (path.isEmpty()) throw FileNotFoundException(documentId)
        var deleted = false
        bases(ctx).forEach { base -> File(base, path).takeIf { it.exists() }?.let { deleted = it.deleteRecursively() || deleted } }
        if (!deleted) throw FileNotFoundException(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val path = path(documentId)
        val from = file(documentId)
        val to = File(from.parentFile, displayName)
        if (path.isEmpty() || '/' in displayName || to.exists() || !from.renameTo(to)) throw FileNotFoundException(documentId)
        return id(path.substringBeforeLast('/', "").let { if (it.isEmpty()) displayName else "$it/$displayName" })
    }

    private fun mime(file: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: if (file.extension.equals("m4b", true)) "audio/mp4" else "application/octet-stream"

    companion object {
        const val NAME = "Audible Audiobooks"
        private const val ROOT = "audible"
        /** Files still being written; kept out of listings so a scan never sees half a book. */
        const val PARTIAL = ".part"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        fun authority(context: Context) = "${context.packageName}.audiobooks"

        /** The shared "Documents/Audible Audiobooks", where ReadAlong may write without a permission (Android 11+). */
        private fun shared(): File? = if (Build.VERSION.SDK_INT >= 30 && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED)
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), NAME) else null
        /** ReadAlong's own storage: the folder's home before 0.49.2, and on Android 10 and older. */
        private fun private(context: Context) = File(context.filesDir, NAME)
        private fun bases(context: Context) = listOfNotNull(shared(), private(context)).distinct()

        /** Where new books go. */
        fun base(context: Context) = shared() ?: private(context)

        /** The file at [path] inside the folder: where it is now, or where a new one goes. */
        fun locate(context: Context, path: String): File {
            if (path.isEmpty()) return base(context)
            return bases(context).map { File(it, path) }.firstOrNull { it.exists() } ?: File(base(context), path)
        }

        /** The folder as a library folder uri, the way a chosen folder is saved. */
        fun treeUri(context: Context): Uri = DocumentsContract.buildTreeDocumentUri(authority(context), ROOT)
        /** ReadAlong's own folder needs no permission grant, so it never shows as lost. */
        fun isOwn(context: Context, uri: String) = Uri.parse(uri).authority == authority(context)

        /**
         * Moves books from ReadAlong's own storage to the shared folder, one file at a time: each is copied
         * beside its final name, renamed into place and only then deleted here. A file that can't move (no
         * space, a name taken by a file from an earlier install) stays where it is and keeps playing from there.
         */
        fun migrate(context: Context) {
            val shared = shared() ?: return
            val old = private(context)
            if (!old.isDirectory) return
            old.walkBottomUp().forEach { file ->
                if (file == old) return@forEach
                if (file.isDirectory) { file.delete(); return@forEach } // Only once empty.
                if (file.name.endsWith(PARTIAL)) { file.delete(); return@forEach }
                val target = File(shared, file.relativeTo(old).path)
                runCatching {
                    target.parentFile?.mkdirs()
                    if (!(target.isFile && target.length() == file.length())) {
                        val partial = File(target.parentFile, target.name + PARTIAL)
                        file.copyTo(partial, overwrite = true)
                        if (!partial.renameTo(target)) { partial.delete(); return@forEach }
                    }
                    target.setLastModified(file.lastModified())
                    file.delete()
                }
            }
            old.delete()
        }
    }
}

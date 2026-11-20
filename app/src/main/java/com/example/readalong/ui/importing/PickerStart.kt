package com.example.readalong.ui.importing

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Where the next "Add audiobooks" folder picker opens. The Play build's Audible guide sets it just before it
 * asks for the folder, so the picker starts in Audiobooks or Download instead of wherever it was last.
 * Android ignores the hint when that folder doesn't exist.
 */
object PickerStart {
    @Volatile private var next: Uri? = null

    /** A folder at the top of the phone's shared storage, such as "Audiobooks" or "Download". */
    fun sharedFolder(name: String) {
        next = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$name")
    }

    internal fun take(): Uri? = next.also { next = null }
}

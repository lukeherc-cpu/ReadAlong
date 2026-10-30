package com.example.readalong.ui.audible

import androidx.compose.runtime.Composable

/** The Google Play edition doesn't sign in to Audible; it explains how to bring books in with LibriSync. */
@Composable
fun AudibleImportEntry(onChooseFolder: () -> Unit, onDismiss: () -> Unit) =
    com.example.readalong.ui.importing.AudibleImportDialog(onChooseFolder, onDismiss)

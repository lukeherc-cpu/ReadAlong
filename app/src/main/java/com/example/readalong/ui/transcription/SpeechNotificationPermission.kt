package com.example.readalong.ui.transcription

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Permission exposes foreground progress/Cancel in the notification drawer; denial still allows work. */
@Composable
fun rememberSpeechNotificationAction(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return { action ->
        if (!asked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            asked = true
            pending = action
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
}

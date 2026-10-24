package com.example.readalong.data.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

object SpeechNotifications {
    fun foreground(context: Context, workId: UUID, text: String, type: Int): ForegroundInfo {
        val channel = "readable-preparation"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Speech model", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(com.example.readalong.R.drawable.ic_notification)
            .setContentTitle("ReadAlong").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(workId)).build()
        val supportedType = if (Build.VERSION.SDK_INT >= 35) type else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(workId.hashCode(), notification, supportedType) else ForegroundInfo(workId.hashCode(), notification)
    }
}

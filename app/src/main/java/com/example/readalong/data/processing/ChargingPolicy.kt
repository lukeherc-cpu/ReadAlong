package com.example.readalong.data.processing

import android.content.Context
import android.os.BatteryManager
import com.example.readalong.data.preferences.AppPreferencesStore
import kotlinx.coroutines.flow.first

object ChargingPolicy {
    fun restricted(settings: com.example.readalong.data.preferences.AppPreferences, now: Long = System.currentTimeMillis()): Boolean =
        settings.chargingOnly && settings.chargingOverrideUntil <= now

    suspend fun shouldWait(context: Context): Boolean = restricted(AppPreferencesStore(context).preferences.first()) &&
        !connectedToPower(context)
    fun connectedToPower(context: Context): Boolean {
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0 || context.getSystemService(BatteryManager::class.java).isCharging
    }
}


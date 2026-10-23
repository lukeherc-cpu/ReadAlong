package com.example.readalong.data.processing

import android.content.Context
import android.os.Build
import android.os.PowerManager

internal class TranscriptionResources(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val memory = context.getSystemService(android.app.ActivityManager::class.java)
    private fun thermal() = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
    fun tooHot() = thermal() >= 3 // PowerManager.THERMAL_STATUS_SEVERE (API 29)
    fun warm() = thermal() >= 2
    fun conserve(): Boolean = power.isPowerSaveMode || warm() ||
        android.app.ActivityManager.MemoryInfo().also(memory::getMemoryInfo).lowMemory
}

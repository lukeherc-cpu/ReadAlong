package com.example.readalong.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.readalong.data.preferences.AppPreferences
import com.example.readalong.domain.model.ShakeDetector

/** Listens for a shake only while a sleep timer runs, so the accelerometer isn't kept on otherwise. */
class SleepShake(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val detector = ShakeDetector()

    fun start() { sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sensors?.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        if (detector.onSample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000)) onShake()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** The "Reset sound" from Settings → Sleep timer, at its own volume so it won't wake anyone. */
        fun playResetSound(context: Context, settings: AppPreferences) {
            val volume = settings.resetVolume.coerceIn(0, 100)
            when (settings.resetSound) {
                "NONE" -> Unit
                "VIBRATE" -> vibrate(context)
                "FILE" -> runCatching {
                    val player = MediaPlayer.create(context, Uri.parse(settings.resetSoundUri)) ?: return@runCatching tone(ToneGenerator.TONE_PROP_ACK, volume)
                    player.setVolume(volume / 100f, volume / 100f)
                    player.setOnCompletionListener { it.release() }
                    player.start()
                }.onFailure { tone(ToneGenerator.TONE_PROP_ACK, volume) }
                "BEEPS" -> tone(ToneGenerator.TONE_PROP_BEEP2, volume)
                else -> tone(ToneGenerator.TONE_PROP_ACK, volume)
            }
        }

        private fun tone(kind: Int, volume: Int) {
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
                generator.startTone(kind, 300)
                Handler(Looper.getMainLooper()).postDelayed({ generator.release() }, 600)
            }
        }

        private fun vibrate(context: Context) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1))
            }
        }
    }
}

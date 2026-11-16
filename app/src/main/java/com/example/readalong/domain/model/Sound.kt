package com.example.readalong.domain.model

import kotlin.math.*

/** The five equalizer bands, in Hz. */
val EqBands = listOf(60, 230, 910, 3600, 14000)
const val EQ_MAX_DB = 12
const val VOLUME_MAX_DB = 12
const val PITCH_MAX = 6

/** Equalizer starting points made for speech. [CUSTOM] uses the bands the listener set. */
enum class EqPreset(val label: String, val gains: List<Int>) {
    FLAT("Flat", listOf(0, 0, 0, 0, 0)),
    VOICE("Voice clarity", listOf(-3, -2, 1, 4, 1)),
    LESS_BASS("Less bass", listOf(-6, -3, 0, 0, 0)),
    BRIGHTER("Brighter", listOf(0, 0, 1, 3, 5)),
    CAR("Car", listOf(2, 0, 1, 4, 2)),
    CUSTOM("Custom", listOf(0, 0, 0, 0, 0)),
}

/**
 * How a book sounds: equalizer, volume boost or reduction (dB), pitch (semitones), skip silence and
 * noise reduction. Settings holds the default for every book; a book changed from the player's Sound
 * sheet keeps its own copy until it's reset.
 */
data class SoundSettings(
    val equalizer: Boolean = false,
    val preset: EqPreset = EqPreset.VOICE,
    /** The Custom curve, kept when another preset is picked so it isn't lost. */
    val customGains: List<Int> = EqPreset.FLAT.gains,
    val volumeDb: Int = 0,
    val pitch: Int = 0,
    val skipSilence: Boolean = false,
    val noiseReduction: Boolean = false,
) {
    val gains: List<Int> get() = if (preset == EqPreset.CUSTOM) customGains else preset.gains
    /** Whether the audio needs any filtering at all; when false it passes through untouched. */
    val filters: Boolean get() = (equalizer && gains.any { it != 0 }) || volumeDb != 0 || noiseReduction
    /** Playback pitch as a factor for the player (1 is normal). */
    val pitchFactor: Float get() = 2.0.pow(pitch / 12.0).toFloat()

    fun withBand(index: Int, db: Int) = copy(equalizer = true, preset = EqPreset.CUSTOM,
        customGains = gains.toMutableList().also { it[index] = db.coerceIn(-EQ_MAX_DB, EQ_MAX_DB) })

    fun encode() = listOf(if (equalizer) "1" else "0", preset.name, customGains.joinToString(","), volumeDb, pitch,
        if (skipSilence) "1" else "0", if (noiseReduction) "1" else "0").joinToString(";")

    companion object {
        /** The equalizer and its Settings screen start from Flat when reset. */
        val DEFAULT = SoundSettings()
        fun decode(text: String?): SoundSettings? {
            val parts = text?.split(";")?.takeIf { it.size >= 7 } ?: return null
            return try {
                SoundSettings(parts[0] == "1", EqPreset.valueOf(parts[1]),
                    parts[2].split(",").map { it.toInt().coerceIn(-EQ_MAX_DB, EQ_MAX_DB) }.takeIf { it.size == EqBands.size } ?: EqPreset.FLAT.gains,
                    parts[3].toInt().coerceIn(-VOLUME_MAX_DB, VOLUME_MAX_DB), parts[4].toInt().coerceIn(-PITCH_MAX, PITCH_MAX),
                    parts[5] == "1", parts[6] == "1")
            } catch (_: IllegalArgumentException) { null }
        }
    }
}

/** One second-order filter (RBJ cookbook), normalised so a0 = 1. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    companion object {
        fun peaking(rate: Int, hz: Double, db: Double, q: Double = 1.0): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            val a0 = 1 + alpha / a
            return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
        }
        fun shelf(rate: Int, hz: Double, db: Double, high: Boolean): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val c = cos(w); val s = sin(w)
            val alpha = s / 2 * sqrt(2.0); val k = 2 * sqrt(a) * alpha
            val sign = if (high) -1 else 1
            val b0 = a * ((a + 1) - sign * (a - 1) * c + k)
            val b1 = 2 * sign * a * ((a - 1) - sign * (a + 1) * c)
            val b2 = a * ((a + 1) - sign * (a - 1) * c - k)
            val a0 = (a + 1) + sign * (a - 1) * c + k
            val a1 = -2 * sign * ((a - 1) + sign * (a + 1) * c)
            val a2 = (a + 1) + sign * (a - 1) * c - k
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
        fun highPass(rate: Int, hz: Double, q: Double = sqrt(0.5)): Biquad {
            val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
    /** Gain in dB at [hz], for tests and drawing the curve. */
    fun responseDb(rate: Int, hz: Double): Double {
        val w = 2 * PI * hz / rate
        fun mag(c0: Double, c1: Double, c2: Double): Double {
            val re = c0 + c1 * cos(w) + c2 * cos(2 * w); val im = -(c1 * sin(w) + c2 * sin(2 * w))
            return sqrt(re * re + im * im)
        }
        return 20 * log10(mag(b0, b1, b2) / mag(1.0, a1, a2))
    }
}

/**
 * Applies [SoundSettings] to interleaved float samples (-1..1), keeping filter state between calls.
 * Pitch and skip silence are the player's job; this does the equalizer, noise reduction and volume.
 */
class SoundChain(private val rate: Int, private val channels: Int) {
    private var filters = emptyArray<Biquad>()
    private var state = DoubleArray(0)
    private var gain = 1.0
    var settings: SoundSettings? = null
        private set

    fun configure(value: SoundSettings) {
        if (value == settings) return
        settings = value
        val nyquistSafe = { hz: Int -> hz < rate * 0.45 }
        val list = mutableListOf<Biquad>()
        if (value.noiseReduction) {
            // Rumble below speech, and hiss above it: a gentle cut rather than a gate, so voices stay natural.
            list += Biquad.highPass(rate, 80.0)
            if (nyquistSafe(7000)) list += Biquad.shelf(rate, 7000.0, -6.0, high = true)
        }
        if (value.equalizer) value.gains.forEachIndexed { i, db ->
            val hz = EqBands[i]
            if (db != 0 && nyquistSafe(hz)) list += when (i) {
                0 -> Biquad.shelf(rate, hz.toDouble(), db.toDouble(), high = false)
                EqBands.lastIndex -> Biquad.shelf(rate, hz.toDouble(), db.toDouble(), high = true)
                else -> Biquad.peaking(rate, hz.toDouble(), db.toDouble())
            }
        }
        val keep = list.size == filters.size
        filters = list.toTypedArray()
        if (!keep) state = DoubleArray(filters.size * channels * 4)
        gain = 10.0.pow(value.volumeDb / 20.0)
    }

    fun reset() { state.fill(0.0) }

    /** Processes [samples] in place. */
    fun process(samples: FloatArray, count: Int = samples.size) {
        if (settings?.filters != true) return
        for (i in 0 until count) {
            val ch = i % channels
            var x = samples[i].toDouble()
            for (f in filters.indices) {
                val s = (f * channels + ch) * 4
                val filter = filters[f]
                val y = filter.b0 * x + filter.b1 * state[s] + filter.b2 * state[s + 1] - filter.a1 * state[s + 2] - filter.a2 * state[s + 3]
                state[s + 1] = state[s]; state[s] = x; state[s + 3] = state[s + 2]; state[s + 2] = y
                x = y
            }
            samples[i] = limit(x * gain).toFloat()
        }
    }

    /** Boosts can push peaks past full scale; ease them in above 0.9 instead of clipping harshly. */
    private fun limit(x: Double): Double {
        val m = abs(x)
        return if (m <= 0.9) x else sign(x) * (0.9 + 0.1 * tanh((m - 0.9) / 0.1))
    }
}

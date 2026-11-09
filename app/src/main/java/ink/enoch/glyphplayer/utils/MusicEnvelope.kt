package ink.enoch.glyphplayer.utils

import kotlin.math.max
import kotlin.math.pow

/**
 * Three-band adaptation of CAVA cavacore.c: autosensitivity, gravity falloff, integral smoothing.
 * Copyright (c) 2015 Karl Stavestrand <karl@stavestrand.no>, MIT license.
 * Source: https://github.com/karlstav/cava/blob/5eb61ec68fbd26e729069e9f14d1e8bb31af1bc4/cavacore.c
 * The full upstream license is included in assets/licenses/CAVA.txt.
 *
 * Adaptations: bounded gain, elapsed-frame timing, invalid-input guards, exact silence snapping.
 * One shared gain preserves the relative strengths of bass, midrange and treble.
 */
class MusicEnvelope {
    private var sensitivity = 1.0
    private var calibrating = true
    private val previous = DoubleArray(3)
    private val peak = DoubleArray(3)
    private val fall = DoubleArray(3)
    private val memory = DoubleArray(3)
    private val input = DoubleArray(3)
    private val output = FloatArray(3)

    fun process(magnitudes: SpectrumLevels, elapsedSeconds: Double): SpectrumLevels {
        fun clean(value: Float) = if (value.isFinite()) value.toDouble().coerceIn(0.0, 1e6) else 0.0
        input[0] = clean(magnitudes.low)
        input[1] = clean(magnitudes.mid)
        input[2] = clean(magnitudes.high)
        val silent = input.all { it <= 1e-6 }
        val dt = if (elapsedSeconds.isFinite()) elapsedSeconds.coerceIn(1.0 / 240, 0.25) else 1.0 / 30
        val frameMod = 66 * dt
        val gravity = frameMod.pow(2.5) * 2 / NOISE_REDUCTION
        val integral = frameMod.pow(0.1)
        var overshoot = false
        for (i in 0..2) {
            var value = input[i] * sensitivity
            if (value < previous[i]) {
                value = max(value, max(0.0, peak[i] * (1 - fall[i] * fall[i] * gravity)))
                fall[i] += 0.028
            } else {
                peak[i] = value
                fall[i] = 0.0
            }
            previous[i] = value
            value += memory[i] * NOISE_REDUCTION / integral
            memory[i] = value
            if (value > 1) overshoot = true
            output[i] = if (value < 0.01) 0f else value.coerceAtMost(1.0).toFloat()
            if (silent && output[i] == 0f) {
                previous[i] = 0.0
                peak[i] = 0.0
                fall[i] = 0.0
                memory[i] = 0.0
            }
        }
        if (overshoot) {
            sensitivity *= max(0.01, 1 - 0.02 * frameMod)
            calibrating = false
        } else if (!silent) {
            sensitivity *= 1 + 0.001 * frameMod
            if (calibrating) sensitivity *= 1 + 0.1 * frameMod
        }
        sensitivity = sensitivity.coerceIn(1e-6, 1e6)
        return SpectrumLevels(output[0], output[1], output[2])
    }

    companion object { private const val NOISE_REDUCTION = 0.77 }
}

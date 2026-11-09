package ink.enoch.glyphplayer.utils

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * CAVA-inspired auto-ranging, adapted for three independent Glyph strips.
 * Copyright (c) 2015 Karl Stavestrand <karl@stavestrand.no>, MIT license.
 * Original reference: https://github.com/karlstav/cava/blob/5eb61ec68fbd26e729069e9f14d1e8bb31af1bc4/cavacore.c
 * License: assets/licenses/CAVA.txt.
 *
 * Unlike CAVA's accumulated integral, this tracks recent per-band peaks/troughs and uses a
 * non-accumulating attack/release envelope. A dominant bass band cannot pin the other strips
 * at a few middle LEDs. Steady input stays steady; movement comes from the input dynamics.
 */
class MusicEnvelope {
    private val peaks = Array(3) { TimedExtrema(maximum = true) }
    private val troughs = Array(3) { TimedExtrema(maximum = false) }
    private var time = 0.0
    private val output = DoubleArray(3)
    private val input = DoubleArray(3)

    fun process(magnitudes: SpectrumLevels, elapsedSeconds: Double): SpectrumLevels {
        fun clean(value: Float) = if (value.isFinite()) value.toDouble().coerceIn(0.0, 1e6) else 0.0
        input[0] = clean(magnitudes.low)
        input[1] = clean(magnitudes.mid)
        input[2] = clean(magnitudes.high)
        val audibleFloor = max(NOISE_FLOOR, maxOf(input[0], input[1], input[2]) * 0.001)
        for (i in 0..2) if (input[i] <= audibleFloor) input[i] = 0.0
        val dt = if (elapsedSeconds.isFinite()) elapsedSeconds.coerceIn(1.0 / 240, 0.25) else 1.0 / 30
        time += dt
        val attack = 1 - exp(-dt / 0.008)
        val release = 1 - exp(-dt / 0.060)
        for (i in 0..2) {
            val amplitude = input[i]
            peaks[i].add(time, amplitude)
            troughs[i].add(time, amplitude)
            val floor = troughs[i].value * 0.8
            val range = max(peaks[i].value - floor, MINIMUM_PEAK * 0.2)
            val target = if (amplitude <= NOISE_FLOOR) 0.0 else ((amplitude - floor) / range).coerceIn(0.0, 1.0).pow(1.6)
            output[i] += (target - output[i]) * if (target > output[i]) attack else release
            if (output[i] < 0.01) output[i] = 0.0
        }
        return SpectrumLevels(output[0].toFloat(), output[1].toFloat(), output[2].toFloat())
    }

    /** Fixed-capacity monotonic deque: each sample is pushed/popped once, no rescanning or sorting. */
    private class TimedExtrema(private val maximum: Boolean) {
        private val values = DoubleArray(1024)
        private val timestamps = DoubleArray(1024)
        private var head = 0
        private var size = 0
        val value: Double get() = if (size == 0) 0.0 else values[head]

        fun add(time: Double, value: Double) {
            while (size > 0 && timestamps[head] <= time - 3.0) {
                head = (head + 1) and 1023
                size--
            }
            while (size > 0) {
                val tail = (head + size - 1) and 1023
                if (if (maximum) values[tail] > value else values[tail] < value) break
                size--
            }
            // The minimum dt bounds a 3-second window to 721 samples; retain a hard cap as well.
            if (size == 1024) { head = (head + 1) and 1023; size-- }
            val tail = (head + size) and 1023
            values[tail] = value
            timestamps[tail] = time
            size++
        }
    }

    companion object {
        private const val NOISE_FLOOR = 1e-6
        private const val MINIMUM_PEAK = 1e-5
    }
}

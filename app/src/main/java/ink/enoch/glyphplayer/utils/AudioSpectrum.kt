package ink.enoch.glyphplayer.utils

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Normalized low (40–250 Hz), mid (250–2000 Hz), high (2000–8000 Hz) levels. */
data class SpectrumLevels(val low: Float, val mid: Float, val high: Float) {
    fun smooth(previous: SpectrumLevels): SpectrumLevels {
        fun mix(value: Float, old: Float): Float {
            val result = old + (value - old) * if (value > old) 0.65f else 0.22f
            return if (result < 0.01f) 0f else result
        }
        return SpectrumLevels(mix(low, previous.low), mix(mid, previous.mid), mix(high, previous.high))
    }
    companion object { val SILENT = SpectrumLevels(0f, 0f, 0f) }
}

private fun normalizedEnergy(power: Double): Float {
    if (!power.isFinite() || power <= 0.0) return 0f
    // A dB scale makes quiet microphone audio visible without lighting LEDs on digital silence.
    return ((10.0 * log10(power) + 60.0) / 50.0).toFloat().coerceIn(0f, 1f)
}

private fun band(frequency: Double): Int = when {
    frequency < 40.0 -> -1
    frequency < 250.0 -> 0
    frequency < 2000.0 -> 1
    frequency <= 8000.0 -> 2
    else -> -1
}

/** Android Visualizer reports sampling rate in millihertz, and signed, packed FFT bytes. */
fun visualizerSpectrum(fft: ByteArray, samplingRateMilliHz: Int): SpectrumLevels {
    if (fft.size < 4 || samplingRateMilliHz <= 0) return SpectrumLevels.SILENT
    val powers = DoubleArray(3)
    val sampleRate = samplingRateMilliHz / 1000.0
    for (bin in 1 until fft.size / 2) {
        val index = band(bin * sampleRate / fft.size)
        if (index < 0) continue
        val real = fft[2 * bin] / 128.0
        val imaginary = fft[2 * bin + 1] / 128.0
        powers[index] += real * real + imaginary * imaginary
    }
    return SpectrumLevels(normalizedEnergy(powers[0]), normalizedEnergy(powers[1]), normalizedEnergy(powers[2]))
}

/** Radix-2 FFT over a Hann-windowed PCM frame. Reuse one instance on the recording thread. */
class PcmSpectrumAnalyzer(private val size: Int = 2048, private val sampleRate: Int = 44100) {
    init {
        require(size >= 4 && size and (size - 1) == 0)
        require(sampleRate > 0)
    }
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val window = DoubleArray(size) { 0.5 * (1.0 - cos(2.0 * PI * it / (size - 1))) }

    fun analyze(samples: ShortArray): SpectrumLevels {
        require(samples.size == size)
        val mean = samples.sumOf { it.toDouble() } / size
        for (i in 0 until size) {
            real[i] = (samples[i] - mean) / 32768.0 * window[i]
            imaginary[i] = 0.0
        }
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val swap = real[i]; real[i] = real[j]; real[j] = swap
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in 0 until size step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val tr = real[b] * wr - imaginary[b] * wi
                    val ti = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                    val nextWr = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = nextWr
                }
            }
            length *= 2
        }
        val powers = DoubleArray(3)
        for (bin in 1 until size / 2) {
            val index = band(bin.toDouble() * sampleRate / size)
            if (index >= 0) powers[index] += (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * 4.0 / (size.toDouble() * size)
        }
        return SpectrumLevels(normalizedEnergy(powers[0]), normalizedEnergy(powers[1]), normalizedEnergy(powers[2]))
    }
}

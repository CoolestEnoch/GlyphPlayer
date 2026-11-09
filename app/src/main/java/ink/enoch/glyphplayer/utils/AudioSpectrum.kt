package ink.enoch.glyphplayer.utils

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Low (40–250 Hz), mid (250–2000 Hz), high (2000–8000 Hz). Display envelopes normalize raw magnitudes to 0–1. */
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
    return VisualizerSpectrumAnalyzer(fft.size, samplingRateMilliHz).analyze(fft)
}

/** Reuse the frequency-bin lookup while the source format is unchanged. */
class VisualizerSpectrumAnalyzer(private val size: Int, sampleRateMilliHz: Int) {
    private val bands = IntArray(size / 2) { band(it * (sampleRateMilliHz / 1000.0) / size) }
    fun musicMagnitudes(fft: ByteArray): SpectrumLevels {
        require(fft.size == size)
        var low = 0.0
        var mid = 0.0
        var high = 0.0
        for (bin in 1 until bands.size) {
            val real = fft[2 * bin] / 128.0
            val imaginary = fft[2 * bin + 1] / 128.0
            val power = real * real + imaginary * imaginary
            when (bands[bin]) { 0 -> low += power; 1 -> mid += power; 2 -> high += power }
        }
        return SpectrumLevels(sqrt(low).toFloat(), sqrt(mid).toFloat(), sqrt(high).toFloat())
    }

    fun analyze(fft: ByteArray): SpectrumLevels {
        require(fft.size == size)
        var low = 0.0
        var mid = 0.0
        var high = 0.0
        for (bin in 1 until bands.size) {
            val real = fft[2 * bin] / 128.0
            val imaginary = fft[2 * bin + 1] / 128.0
            val power = real * real + imaginary * imaginary
            when (bands[bin]) { 0 -> low += power; 1 -> mid += power; 2 -> high += power }
        }
        return SpectrumLevels(normalizedEnergy(low), normalizedEnergy(mid), normalizedEnergy(high))
    }
}

/** Radix-2 FFT over a Hann-windowed PCM frame. Reuse one instance on the recording thread. */
class PcmSpectrumAnalyzer(private val size: Int = 2048, private val sampleRate: Int = 44100) {
    init {
        require(size >= 4 && size and (size - 1) == 0)
        require(sampleRate > 0)
    }
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val stages = Integer.numberOfTrailingZeros(size)
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - stages) }
    private val window = DoubleArray(size) { 0.5 * (1.0 - cos(2.0 * PI * it / (size - 1))) }
    private val stepReal = DoubleArray(stages) { cos(-2.0 * PI / (1 shl (it + 1))) }
    private val stepImaginary = DoubleArray(stages) { sin(-2.0 * PI / (1 shl (it + 1))) }
    private val bands = IntArray(size / 2) { band(it.toDouble() * sampleRate / size) }
    private val powerScale = 4.0 / (size.toDouble() * size)

    private var lowPower = 0.0
    private var midPower = 0.0
    private var highPower = 0.0

    fun analyze(samples: ShortArray): SpectrumLevels {
        calculate(samples)
        return SpectrumLevels(normalizedEnergy(lowPower), normalizedEnergy(midPower), normalizedEnergy(highPower))
    }

    fun musicMagnitudes(samples: ShortArray): SpectrumLevels {
        calculate(samples)
        return SpectrumLevels(sqrt(lowPower).toFloat(), sqrt(midPower).toFloat(), sqrt(highPower).toFloat())
    }

    private fun calculate(samples: ShortArray) {
        require(samples.size == size)
        val mean = samples.sumOf { it.toDouble() } / size
        imaginary.fill(0.0)
        for (i in 0 until size) real[reversed[i]] = (samples[i] - mean) / 32768.0 * window[i]
        var length = 2
        var stage = 0
        while (length <= size) {
            val sr = stepReal[stage]
            val si = stepImaginary[stage]
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
                    val nextWr = wr * sr - wi * si
                    wi = wr * si + wi * sr
                    wr = nextWr
                }
            }
            length *= 2
            stage++
        }
        var low = 0.0
        var mid = 0.0
        var high = 0.0
        for (bin in 1 until size / 2) {
            val power = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * powerScale
            when (bands[bin]) { 0 -> low += power; 1 -> mid += power; 2 -> high += power }
        }
        lowPower = low
        midPower = mid
        highPower = high
    }
}

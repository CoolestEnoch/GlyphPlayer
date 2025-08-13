package ink.enoch.glyphplayer.utils

import android.media.audiofx.Visualizer
import android.util.Log

class AudioFrequencyAnalyzer(
    private val audioSessionId: Int,
    private val getGlyphMode: () -> Boolean
) {
    private var visualizer: Visualizer? = null
    private var isAnalyzing = false
    private val fftLogEnable = false

    // 频率范围定义 (单位: Hz)
    private val lowFreqRange = 20f..250f      // 低频: 20-250Hz
    private val midFreqRange = 251f..2000f    // 中频: 251-2000Hz
    private val highFreqRange = 2001f..6000f  // 高频: 2001-6000Hz

    fun startAnalysis() {
        if (isAnalyzing) return

        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]  // 最大捕获大小
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) { /* 不需要波形数据 */
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fftData: ByteArray?,
                            samplingRate: Int
                        ) {
                            fftData?.let { processFFT(it, samplingRate) }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,  // 不捕获波形数据
                    true    // 捕获FFT数据
                )

                enabled = true
                isAnalyzing = true
                Log.d("AudioAnalyzer", "FFT分析已启动")
            }
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "初始化Visualizer失败: ${e.message}")
        }
    }

    private fun processFFT(fftData: ByteArray, samplingRate: Int) {
        val n = fftData.size / 2
        if (n < 2) return

        // 计算频率区间
        val freqStep = samplingRate / 2f / n
        val lowFreqEnd = (300 / freqStep).toInt() + 1
        val midFreqEnd = (6000 / freqStep).toInt() + 1

        // 重置能量值
        var lowSum = 0f
        var midSum = 0f
        var highSum = 0f

        // 处理FFT数据 (实部和虚部交替)
        for (i in 2 until n * 2 step 2) {
            val real = fftData[i].toFloat()
            val imag = fftData[i + 1].toFloat()
            val magnitude = real * real + imag * imag

            val freqIndex = (i / 2) - 1
            when {
                freqIndex < lowFreqEnd -> lowSum += magnitude
                freqIndex < midFreqEnd -> midSum += magnitude
                else -> highSum += magnitude
            }
        }


        // 平滑处理并归一化
        val lowFreqEnergy = if (lowSum < 500) {
            lowSum / 500f
        } else if (lowSum < 1000) {
            lowSum / 1000f
        } else if (lowSum < 5000) {
            lowSum / 5000f
        } else if (lowSum < 10000) {
            lowSum / 10000f
        } else if (lowSum < 50000) {
            lowSum / 50000f
        } else {
            lowSum / 100000f
        }
        //lowFreqEnergy = lowSum / 100000f //.coerceAtMost(1f)
        val highFreqEnergy = highSum / 100000f //.coerceAtMost(1f)
        val midFreqEnergy = (lowFreqEnergy + highFreqEnergy) / 2 // / 100000f //.coerceAtMost(1f)
        if (fftLogEnable) {
            Log.d("FFT", "lowFreqEnergy = ${lowFreqEnergy}")
            Log.d("FFT", "midFreqEnergy = ${midFreqEnergy}")
            Log.d("FFT", "highFreqEnergy = ${highFreqEnergy}")
            Log.d("FFT", "======================================")
        }
        glyphTick.lightA = lowFreqEnergy
        glyphTick.lightB = midFreqEnergy
        glyphTick.lightC = highFreqEnergy

        GlyphHelper.show(glyphTick, getGlyphMode())
    }

    val glyphTick = GlyphHelper.GlyphTick(0f, 0f, 0f, true, false, false)


    fun stopAnalysis() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        isAnalyzing = false
        Log.d("AudioAnalyzer", "FFT分析已停止")
    }
}
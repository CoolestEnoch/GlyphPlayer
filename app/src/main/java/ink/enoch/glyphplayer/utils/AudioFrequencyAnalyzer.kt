package ink.enoch.glyphplayer.utils

import android.media.audiofx.Visualizer
import android.util.Log

class AudioFrequencyAnalyzer(private val audioSessionId: Int, private val getGlyphMode: () -> Boolean) {
    private var visualizer: Visualizer? = null
    private var generation = 0
    private var levels = SpectrumLevels.SILENT

    fun startAnalysis() {
        if (visualizer != null || audioSessionId == 0) return
        val token = ++generation
        try {
            val instance = Visualizer(audioSessionId)
            visualizer = instance
            instance.captureSize = Visualizer.getCaptureSizeRange()[1]
            instance.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) { }
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null) return
                    val next = visualizerSpectrum(fft, samplingRate)
                    runOnMainThread {
                        if (token != generation) return@runOnMainThread
                        levels = next.smooth(levels)
                        GlyphHelper.show(GlyphHelper.GlyphTick(levels.low, levels.mid, levels.high, true, false, false), getGlyphMode())
                    }
                }
            }, minOf(Visualizer.getMaxCaptureRate(), 30000), false, true)
            instance.enabled = true
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Cannot initialize audio visualizer", e)
            stopAnalysis()
        }
    }

    fun stopAnalysis() {
        ++generation
        try { visualizer?.release() } finally {
            visualizer = null
            levels = SpectrumLevels.SILENT
        }
    }
}

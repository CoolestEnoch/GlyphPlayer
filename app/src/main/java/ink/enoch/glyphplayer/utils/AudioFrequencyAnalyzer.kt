package ink.enoch.glyphplayer.utils

import android.media.audiofx.Visualizer
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioFrequencyAnalyzer(private val audioSessionId: Int, private val getGlyphMode: () -> Boolean, private val framesPerSecond: Int = 30) {
    private var visualizer: Visualizer? = null
    private var fftRequests: LatestValueDispatcher<Pair<ByteArray, Int>>? = null
    private var fftWorker: ExecutorService? = null
    private var active: AtomicBoolean? = null
    private val calibrationVersion = AtomicLong()
    fun resetSensitivity() { calibrationVersion.incrementAndGet() }

    fun startAnalysis() {
        if (visualizer != null || audioSessionId == 0) return
        val processing = AtomicBoolean(true)
        active = processing
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Playback FFT and Glyph") }
        fftWorker = worker
        var envelope = MusicEnvelope()
        var calibration = calibrationVersion.get()
        var previousFrame = 0L
        var spectrum: VisualizerSpectrumAnalyzer? = null
        var formatSize = 0
        var formatRate = 0
        val requests = LatestValueDispatcher<Pair<ByteArray, Int>>({ worker.execute(it) }) { (fft, rate) ->
            // One worker completes analysis AND the hardware write before accepting the next frame.
            if (fft.size < 4 || rate <= 0) return@LatestValueDispatcher
            if (spectrum == null || formatSize != fft.size || formatRate != rate) {
                spectrum = VisualizerSpectrumAnalyzer(fft.size, rate)
                formatSize = fft.size
                formatRate = rate
            }
            if (calibration != calibrationVersion.get()) {
                envelope = MusicEnvelope()
                calibration = calibrationVersion.get()
                previousFrame = 0L
            }
            val now = System.nanoTime()
            val elapsed = if (previousFrame == 0L) 1.0 / framesPerSecond else (now - previousFrame) / 1e9
            previousFrame = now
            val levels = envelope.process(spectrum!!.musicMagnitudes(fft), elapsed)
            synchronized(GlyphHelper) {
                if (processing.get()) GlyphHelper.show(GlyphHelper.GlyphTick(levels.low, levels.mid, levels.high,
                    true, false, false), getGlyphMode())
            }
        }
        fftRequests = requests
        try {
            val instance = Visualizer(audioSessionId)
            visualizer = instance
            instance.captureSize = Visualizer.getCaptureSizeRange()[1]
            instance.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) { }
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null && processing.get()) requests.offer(fft.copyOf() to samplingRate)
                }
            }, minOf(Visualizer.getMaxCaptureRate(), framesPerSecond.coerceIn(1, 60) * 1000), false, true)
            instance.enabled = true
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Cannot initialize audio visualizer", e)
            stopAnalysis()
        }
    }

    fun stopAnalysis() {
        active?.set(false)
        active = null
        fftRequests?.close()
        fftRequests = null
        fftWorker?.shutdown()
        fftWorker = null
        visualizer?.release()
        visualizer = null
    }
}

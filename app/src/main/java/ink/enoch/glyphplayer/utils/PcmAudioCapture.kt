package ink.enoch.glyphplayer.utils

import android.media.AudioRecord
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A single capture session: one reader, one serial FFT/Glyph worker, one replaceable pending frame. */
class PcmAudioCapture(
    private val createRecorder: () -> AudioRecord,
    private val music: Boolean,
    private val onLevels: (SpectrumLevels) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    private var compute: ExecutorService? = null
    private var requests: LatestValueDispatcher<ShortArray>? = null

    fun start(): Boolean {
        if (running.get()) return true
        try {
            val audio = createRecorder()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize audio capture" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Cannot start audio capture" }
            running.set(true)
            val spectrum = PcmSpectrumAnalyzer(FRAME_SIZE, SAMPLE_RATE)
            val envelope = if (music) MusicEnvelope() else null
            var levels = SpectrumLevels.SILENT
            var previousFrame = 0L
            val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "PCM FFT and Glyph") }
            compute = worker
            val pending = LatestValueDispatcher<ShortArray>({ worker.execute(it) }) { samples ->
                if (running.get()) {
                    try {
                        if (music) {
                            val now = System.nanoTime()
                            val elapsed = if (previousFrame == 0L) FRAME_SIZE.toDouble() / SAMPLE_RATE else (now - previousFrame) / 1e9
                            previousFrame = now
                            levels = envelope!!.process(spectrum.musicMagnitudes(samples), elapsed)
                        } else levels = spectrum.analyze(samples).smooth(levels)
                        // The consumer completes its synchronous Glyph write before the next FFT.
                        if (running.get()) onLevels(levels)
                    } catch (e: Exception) { fail(e) }
                }
            }
            requests = pending
            reader = thread(name = "PCM capture") {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val samples = ShortArray(FRAME_SIZE)
                    var filled = 0
                    while (running.get()) {
                        val count = audio.read(samples, filled, samples.size - filled, AudioRecord.READ_BLOCKING)
                        if (!running.get()) break
                        check(count > 0) { "Audio capture read failed ($count)" }
                        filled += count
                        if (filled == samples.size) {
                            pending.offer(samples.copyOf())
                            filled = 0
                        }
                    }
                } catch (e: Exception) { fail(e) }
                finally {
                    running.set(false)
                    pending.close()
                    worker.shutdown()
                    try { audio.stop() } catch (_: IllegalStateException) { }
                    audio.release()
                }
            }
            return true
        } catch (e: Exception) {
            running.set(false)
            requests?.close()
            compute?.shutdown()
            recorder?.release()
            recorder = null
            onError(e.message ?: "Audio capture is unavailable")
            return false
        }
    }

    private fun fail(error: Exception) {
        if (running.compareAndSet(true, false)) onError(error.message ?: "Audio capture failed")
    }

    fun stop() {
        running.set(false)
        requests?.close()
        requests = null
        compute?.shutdown()
        compute = null
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        reader?.join(300)
        reader = null
        recorder = null // The reader's finally releases its recorder.
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val FRAME_SIZE = 2048
    }
}

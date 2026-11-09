package ink.enoch.glyphplayer.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.concurrent.thread
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One capture thread feeds one fixed FFT/output worker; neither thread is created per request. */
class MicrophoneAnalyzer(
    private val onLevels: (SpectrumLevels) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var fftWorker: ExecutorService? = null
    private var fftRequests: LatestValueDispatcher<ShortArray>? = null

    @SuppressLint("MissingPermission") // The activity checks and requests RECORD_AUDIO before start().
    fun start(): Boolean {
        if (running) return true
        try {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Microphone format is not supported" }
            val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, FRAME_SIZE * 4))
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize microphone" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Cannot start microphone" }
            running = true
            val spectrum = PcmSpectrumAnalyzer(FRAME_SIZE, SAMPLE_RATE)
            val compute = Executors.newSingleThreadExecutor { task -> Thread(task, "Microphone FFT and Glyph") }
            fftWorker = compute
            var levels = SpectrumLevels.SILENT
            val requests = LatestValueDispatcher<ShortArray>({ compute.execute(it) }) { samples ->
                if (running) {
                    levels = spectrum.analyze(samples).smooth(levels)
                    if (running) onLevels(levels)
                }
            }
            fftRequests = requests
            worker = thread(name = "Microphone capture") {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val samples = ShortArray(FRAME_SIZE)
                    var filled = 0
                    while (running) {
                        val count = audio.read(samples, filled, samples.size - filled, AudioRecord.READ_BLOCKING)
                        if (!running) break
                        check(count > 0) { "Microphone read failed ($count)" }
                        filled += count
                        if (filled == samples.size) {
                            // One calculating frame + one replaceable pending frame, never a FIFO backlog.
                            requests.offer(samples.copyOf())
                            filled = 0
                        }
                    }
                } catch (e: Exception) {
                    if (running) onError(e.message ?: "Microphone capture failed")
                } finally {
                    running = false
                    requests.close()
                    compute.shutdown()
                    try { audio.stop() } catch (_: IllegalStateException) { }
                    audio.release()
                }
            }
            return true
        } catch (e: Exception) {
            running = false
            fftRequests?.close()
            fftWorker?.shutdown()
            recorder?.release()
            recorder = null
            onError(e.message ?: "Microphone is unavailable")
            return false
        }
    }

    fun stop() {
        running = false
        fftRequests?.close()
        fftRequests = null
        fftWorker?.shutdown()
        fftWorker = null
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        worker?.join(300)
        worker = null
        recorder = null // The capture thread releases its AudioRecord in finally.
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FRAME_SIZE = 2048
    }
}

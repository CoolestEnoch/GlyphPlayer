package ink.enoch.glyphplayer.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Physical microphone input remains separate from system playback capture. */
class MicrophoneAnalyzer(onLevels: (SpectrumLevels) -> Unit, onError: (String) -> Unit) {
    private val capture = PcmAudioCapture(::createRecorder, music = false, onLevels = onLevels, onError = onError)

    @SuppressLint("MissingPermission") // Granted only through the UI's dedicated authorization button.
    private fun createRecorder(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(PcmAudioCapture.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone format is not supported" }
        return AudioRecord(MediaRecorder.AudioSource.MIC, PcmAudioCapture.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, PcmAudioCapture.FRAME_SIZE * 4))
    }

    fun start() = capture.start()
    fun stop() = capture.stop()
}

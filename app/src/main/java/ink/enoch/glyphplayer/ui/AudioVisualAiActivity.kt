package ink.enoch.glyphplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.databinding.ActivityAudioVisualAiBinding
import ink.enoch.glyphplayer.utils.AudioFrequencyAnalyzer
import ink.enoch.glyphplayer.utils.GlyphHelper

/** Experimental single-track controls use the same bounded FFT/output path as the music player. */
class AudioVisualAiActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAudioVisualAiBinding
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var analyzer: AudioFrequencyAnalyzer? = null
    private var fps = 10
    private var rolling = false
    private var tick = 0
    private var visible = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updatePermissionUi()
        syncAnalyzer()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) play(uri)
    }
    private val updates = object : Runnable {
        override fun run() {
            if (!visible) return
            if (prepared) binding.sbProgress.progress = player?.currentPosition ?: 0
            if (rolling) {
                val phase = tick++ % 40
                val level = (if (phase <= 20) phase else 40 - phase) / 20f
                GlyphHelper.show(GlyphHelper.GlyphTick(level, level, level, false, false, false))
            }
            handler.postDelayed(this, 1000L / fps)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioVisualAiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnGrantMicrophone.setOnClickListener { permission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.btnFPSConfirm.setOnClickListener {
            val value = binding.etFPS.text.toString().toIntOrNull()
            if (value == null || value !in 1..60) Toast.makeText(this, "FPS must be between 1 and 60", Toast.LENGTH_SHORT).show()
            else { fps = value; syncAnalyzer() }
        }
        binding.btnPlayerUI.setOnClickListener { startActivity(Intent(this, MusicPlayerUI::class.java)) }
        binding.btnChooseMusicSingle.setOnClickListener { picker.launch(arrayOf("audio/*")) }
        binding.btnPausePlay.setOnClickListener {
            if (prepared) {
                player?.let { if (it.isPlaying) it.pause() else it.start() }
                syncAnalyzer()
            }
        }
        binding.btnStop.setOnClickListener { stop() }
        binding.btnGlyphRolling.setOnClickListener {
            rolling = !rolling
            syncAnalyzer()
        }
        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && prepared) player?.seekTo(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
        })
    }

    override fun onStart() {
        super.onStart()
        visible = true
        updatePermissionUi()
        GlyphHelper.init(this, Glyph.DEVICE_24111)
        handler.post(updates)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(updates)
        stop()
        GlyphHelper.release()
        super.onStop()
    }

    private fun updatePermissionUi() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        binding.btnGrantMicrophone.isEnabled = !granted
        binding.btnGrantMicrophone.setText(if (granted) R.string.microphone_access_granted else R.string.grant_microphone)
    }

    private fun play(uri: Uri) {
        stop()
        val next = MediaPlayer()
        player = next
        try {
            next.setDataSource(this, uri)
            next.setOnPreparedListener {
                if (player !== it || !visible) return@setOnPreparedListener
                prepared = true
                binding.sbProgress.max = it.duration
                it.start()
                syncAnalyzer()
            }
            next.setOnCompletionListener { syncAnalyzer() }
            next.setOnErrorListener { _, _, _ -> stop(); true }
            next.prepareAsync()
        } catch (e: Exception) {
            stop()
            Toast.makeText(this, "Playback failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun syncAnalyzer() {
        analyzer?.stopAnalysis()
        analyzer = null
        GlyphHelper.turnOffTheLight()
        if (!rolling && prepared && player?.isPlaying == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            analyzer = AudioFrequencyAnalyzer(player!!.audioSessionId, { false }, fps).apply { startAnalysis() }
        }
    }

    private fun stop() {
        rolling = false
        prepared = false
        analyzer?.stopAnalysis()
        analyzer = null
        player?.release()
        player = null
        GlyphHelper.turnOffTheLight()
    }
}

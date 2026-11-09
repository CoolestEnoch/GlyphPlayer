package ink.enoch.glyphplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.databinding.ActivityMicrophoneVisualizerBinding
import ink.enoch.glyphplayer.utils.GlyphHelper
import ink.enoch.glyphplayer.utils.MicrophoneAnalyzer
import ink.enoch.glyphplayer.utils.SpectrumLevels

class MicrophoneVisualizerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMicrophoneVisualizerBinding
    private var microphone: MicrophoneAnalyzer? = null
    private var visible = false
    private var generation = 0

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && visible) startMicrophone()
        else if (!granted) binding.tvMicrophoneStatus.setText(R.string.microphone_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMicrophoneVisualizerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnStartMicrophone.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startMicrophone()
            } else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.btnStopMicrophone.setOnClickListener { stopMicrophone() }
        binding.switchGlyph.setOnCheckedChangeListener { _, enabled ->
            if (enabled && visible) connectGlyph() else {
                GlyphHelper.release()
                binding.tvGlyphStatus.setText(R.string.glyph_disabled)
            }
        }
        binding.btnStopMicrophone.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (binding.switchGlyph.isChecked) connectGlyph()
    }

    override fun onStop() {
        visible = false
        stopMicrophone()
        GlyphHelper.release()
        super.onStop()
    }

    private fun connectGlyph() {
        if (!Common.is24111()) {
            binding.switchGlyph.isChecked = false
            binding.switchGlyph.isEnabled = false
            binding.tvGlyphStatus.setText(R.string.glyph_supported_devices)
            return
        }
        GlyphHelper.init(this, Glyph.DEVICE_24111) { message ->
            if (visible) binding.tvGlyphStatus.text = message
        }
    }

    private fun startMicrophone() {
        if (!visible || microphone != null) return
        if (binding.switchGlyph.isChecked) connectGlyph()
        val token = ++generation
        val analyzer = MicrophoneAnalyzer(onLevels = { levels ->
            runOnUiThread {
                if (visible && token == generation) {
                    displayLevels(levels)
                    if (binding.switchGlyph.isChecked) {
                        GlyphHelper.show(GlyphHelper.GlyphTick(levels.low, levels.mid, levels.high, true, false, false), binding.switchDot.isChecked)
                    }
                }
            }
        }, onError = { error ->
            runOnUiThread {
                if (token == generation) {
                    stopMicrophone()
                    binding.tvMicrophoneStatus.text = getString(R.string.microphone_error, error)
                }
            }
        })
        microphone = analyzer
        if (analyzer.start()) {
            binding.tvMicrophoneStatus.setText(R.string.microphone_listening)
            binding.btnStartMicrophone.isEnabled = false
            binding.btnStopMicrophone.isEnabled = true
        } else microphone = null
    }

    private fun stopMicrophone() {
        ++generation
        microphone?.stop()
        microphone = null
        GlyphHelper.turnOffTheLight()
        displayLevels(SpectrumLevels.SILENT)
        binding.btnStartMicrophone.isEnabled = true
        binding.btnStopMicrophone.isEnabled = false
        binding.tvMicrophoneStatus.setText(R.string.microphone_stopped)
    }

    private fun displayLevels(levels: SpectrumLevels) {
        binding.levelLow.progress = (levels.low * 100).toInt()
        binding.levelMid.progress = (levels.mid * 100).toInt()
        binding.levelHigh.progress = (levels.high * 100).toInt()
    }
}

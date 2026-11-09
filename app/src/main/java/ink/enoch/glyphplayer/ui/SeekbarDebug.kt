package ink.enoch.glyphplayer.ui

import android.os.Bundle
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.databinding.ActivitySeekbarDebugBinding
import ink.enoch.glyphplayer.utils.GlyphHelper

class SeekbarDebug : AppCompatActivity() {
    private lateinit var binding: ActivitySeekbarDebugBinding
    private var flow = false
    private var lightsEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySeekbarDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        flow = savedInstanceState?.getBoolean("flow") ?: false
        lightsEnabled = savedInstanceState?.getBoolean("lightsEnabled") ?: false
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvStatusA.text = "A = ${binding.sbA.progress}"
                binding.tvStatusB.text = "B = ${binding.sbB.progress}"
                binding.tvStatusC.text = "C = ${binding.sbC.progress}"
                binding.tvStatusT.text = "T = ${binding.sbT.progress}"
                if (fromUser) {
                    flow = bar === binding.sbT
                    lightsEnabled = true
                    render()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
        }
        listOf(binding.sbA, binding.sbB, binding.sbC, binding.sbT).forEach { it.setOnSeekBarChangeListener(listener) }
        binding.cbR.setOnCheckedChangeListener { _, _ -> render() }
        binding.cbDot.setOnCheckedChangeListener { _, _ -> render() }
        listOf(binding.rbA, binding.rbB, binding.rbC).forEach { radio ->
            radio.setOnCheckedChangeListener { _, checked -> if (checked) render() }
        }
        binding.btnTurnOff.setOnClickListener {
            lightsEnabled = false
            GlyphHelper.turnOffTheLight()
        }
    }

    override fun onStart() {
        super.onStart()
        GlyphHelper.init(this, Glyph.DEVICE_24111, onConnected = ::render)
    }

    override fun onStop() {
        GlyphHelper.release()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("flow", flow)
        outState.putBoolean("lightsEnabled", lightsEnabled)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (!lightsEnabled) return
        val reverse = binding.cbR.isChecked
        val dot = binding.cbDot.isChecked
        if (flow) {
            val start = when { binding.rbB.isChecked -> 'b'; binding.rbC.isChecked -> 'c'; else -> 'a' }
            GlyphHelper.showFlow(binding.sbT.progress / 100f, start, reverse, dot)
        } else {
            // R applies to every individual strip, as well as the combined flow slider.
            GlyphHelper.show(GlyphHelper.GlyphTick(binding.sbA.progress / 100f,
                binding.sbB.progress / 100f, binding.sbC.progress / 100f, reverse, reverse, reverse), dot)
        }
    }
}

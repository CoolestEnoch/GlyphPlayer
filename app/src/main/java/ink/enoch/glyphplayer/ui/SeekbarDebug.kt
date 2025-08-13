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
import kotlin.concurrent.thread

class SeekbarDebug : AppCompatActivity() {
    private lateinit var binding: ActivitySeekbarDebugBinding

    private var start = 'a'
    private var reverse = false
    private var dot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySeekbarDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        GlyphHelper.init(this, Glyph.DEVICE_24111)

        binding.sbA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvStatusA.text = "A = $progress"

                    GlyphHelper.show(
                        GlyphHelper.GlyphTick(
                            binding.sbA.progress / 100f,
                            binding.sbB.progress / 100f,
                            binding.sbC.progress / 100f,
                            true, true, true
                        ), dot
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvStatusB.text = "B = $progress"

                    GlyphHelper.show(
                        GlyphHelper.GlyphTick(
                            binding.sbA.progress / 100f,
                            binding.sbB.progress / 100f,
                            binding.sbC.progress / 100f,
                            true, true, true
                        ), dot
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbC.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvStatusC.text = "C = $progress"

                    GlyphHelper.show(
                        GlyphHelper.GlyphTick(
                            binding.sbA.progress / 100f,
                            binding.sbB.progress / 100f,
                            binding.sbC.progress / 100f,
                            true, true, true
                        ), dot
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnTurnOff.setOnClickListener {
            GlyphHelper.turnOffTheLight()
        }

        binding.sbT.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvStatusT.text = "T = $progress"

                    GlyphHelper.showFlow(
                        progress / 100f,
                        start,
                        reverse,
                        dot
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.rbA.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) start = 'a'
        }

        binding.rbB.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) start = 'b'
        }

        binding.rbC.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) start = 'c'
        }

        binding.cbR.setOnCheckedChangeListener { buttonView, isChecked ->
            reverse = isChecked
        }

        binding.cbDot.setOnCheckedChangeListener { buttonView, isChecked ->
            dot = isChecked
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        GlyphHelper.release()
    }
}
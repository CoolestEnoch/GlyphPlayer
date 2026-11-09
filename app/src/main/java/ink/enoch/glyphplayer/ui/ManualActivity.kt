package ink.enoch.glyphplayer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.databinding.ActivityManualBinding
import ink.enoch.glyphplayer.utils.GlyphHelper

class ManualActivity : AppCompatActivity() {
    private lateinit var binding: ActivityManualBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.channelABtn.setOnClickListener { GlyphHelper.show(GlyphHelper.GlyphTick(1f, 0f, 0f, false, false, false)) }
        binding.channelBBtn.setOnClickListener { GlyphHelper.blinkB() }
        binding.channelCBtn.setOnClickListener { GlyphHelper.show(GlyphHelper.GlyphTick(0f, 0f, 1f, false, false, false)) }
        binding.channelDBtn.setOnClickListener { GlyphHelper.turnOffTheLight() }
    }

    override fun onStart() {
        super.onStart()
        GlyphHelper.init(this, Glyph.DEVICE_24111)
    }

    override fun onStop() {
        GlyphHelper.release()
        super.onStop()
    }
}

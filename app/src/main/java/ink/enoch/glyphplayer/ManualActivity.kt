package ink.enoch.glyphplayer

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import ink.enoch.glyphplayer.databinding.ActivityManualBinding

const val TAG = "GlyphPlayer"


class ManualActivity : AppCompatActivity() {
    private lateinit var binding: ActivityManualBinding

    private var mGM: GlyphManager? = null
    private var mCallback: GlyphManager.Callback? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)

        init()
        mGM = GlyphManager.getInstance(applicationContext)
        mGM!!.init(mCallback)
        initView()
    }

    public override fun onDestroy() {
        try {
            mGM!!.closeSession()
        } catch (e: GlyphException) {
            Log.e(TAG, "${e.message}")
        }
        mGM!!.unInit()
        super.onDestroy()
    }

    private fun init() {
        mCallback = object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                if (Common.is20111()) mGM!!.register(Glyph.DEVICE_20111)
                if (Common.is22111()) mGM!!.register(Glyph.DEVICE_22111)
                if (Common.is23111()) mGM!!.register(Glyph.DEVICE_23111)
                if (Common.is23113()) mGM!!.register(Glyph.DEVICE_23113)
                if (Common.is24111()) mGM!!.register(Glyph.DEVICE_24111)
                try {
                    mGM!!.openSession()
                } catch (e: GlyphException) {
                    Log.e(TAG, "${e.message}")
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                mGM!!.closeSession()
            }
        }
    }

    private fun initView() {
        binding.channelABtn.setOnClickListener(View.OnClickListener {
            val builder: GlyphFrame.Builder = mGM!!.getGlyphFrameBuilder()
            val frame = builder.buildChannelA().build()
            mGM!!.toggle(frame)
        })
        binding.channelBBtn.setOnClickListener(View.OnClickListener {
            val builder: GlyphFrame.Builder = mGM!!.getGlyphFrameBuilder()
            val frame = builder.buildChannelB().buildInterval(10)
                .buildCycles(2).buildPeriod(3000).build()
            mGM!!.animate(frame)
        })
        binding.channelCBtn.setOnClickListener(View.OnClickListener {
//            val builder: GlyphFrame.Builder = mGM!!.getGlyphFrameBuilder()
//            val frame = builder.buildChannelC().build()
//            mGM!!.animate(frame)
            val builder: GlyphFrame.Builder = mGM!!.getGlyphFrameBuilder()
            val frame = builder.buildChannelC().build()
            mGM!!.toggle(frame)
        })
        binding.channelDBtn.setOnClickListener(View.OnClickListener { mGM!!.turnOff() })
    }
}
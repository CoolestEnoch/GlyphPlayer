package ink.enoch.glyphplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager
import ink.enoch.glyphplayer.databinding.ActivityAudioVisualAiBinding
import kotlin.concurrent.thread


class AudioVisualAiActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GlyphMusicPlayer"
        private const val FFT_FPS = 30 // 帧率 (1-60)
        private const val FFT_REFRESH_RATE_MS = 1000L / FFT_FPS
        private const val AUDIO_PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var binding: ActivityAudioVisualAiBinding
    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var glyphManager: GlyphManager? = null
    private var isSupportedDevice = false
    private var mIsPlaying = false
    private var updateHandler: Handler? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateGlyphLights()
            updateHandler?.postDelayed(this, FFT_REFRESH_RATE_MS)
        }
    }

    // 频段能量值 (0-1)
    private var lowFreqEnergy = 0f
    private var midFreqEnergy = 0f
    private var highFreqEnergy = 0f

    // 设备型号常量
    private val DEVICE_24111 = "24111"

    // 灯条索引范围 (Phone 3a/3a Pro)
    private val C_LED_RANGE = 0..19   // 中频 (C灯条)
    private val A_LED_RANGE = 30 downTo 20  // 高频 (A灯条)
    private val B_LED_RANGE = 31..35  // 低频 (B灯条)

    private val documentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { playMusic(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioVisualAiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 检查设备兼容性
        isSupportedDevice = Common.is24111()
        if (!isSupportedDevice) {
            Toast.makeText(
                this,
                "Glyph lights only supported on Phone (3a) and (3a) Pro",
                Toast.LENGTH_LONG
            ).show()
        } else {
            initializeGlyphManager()
        }

        binding.btnChooseMusic.setOnClickListener {
            checkPermissionsAndOpenPicker()
        }

        var rolling = false
        binding.btnStop.setOnClickListener {
            rolling = false
            stopMusicAndCleanup()
        }

        binding.btnEnableGlyph.setOnClickListener {

        }

        binding.btnGlyphRolling.setOnClickListener {
            rolling = !rolling
            thread {
                while (rolling) {
                    for (i in 0..10) {
                        lowFreqEnergy = i / 10f
                        midFreqEnergy = i / 10f
                        highFreqEnergy = i / 10f
                        Log.e(TAG, "lowFreqEnergy = ${lowFreqEnergy}")
                        Log.e(TAG, "midFreqEnergy = ${midFreqEnergy}")
                        Log.e(TAG, "highFreqEnergy = ${highFreqEnergy}")
                        Log.e(TAG, "======================================")
                        Thread.sleep(10)
                    }
                    for (i in 10 downTo 0) {
                        lowFreqEnergy = i / 10f
                        midFreqEnergy = i / 10f
                        highFreqEnergy = i / 10f
                        Log.e(TAG, "lowFreqEnergy = ${lowFreqEnergy}")
                        Log.e(TAG, "midFreqEnergy = ${midFreqEnergy}")
                        Log.e(TAG, "highFreqEnergy = ${highFreqEnergy}")
                        Log.e(TAG, "======================================")
                        Thread.sleep(10)
                    }
                }
            }
        }
    }

    private fun initializeGlyphManager() {
        glyphManager = GlyphManager.getInstance(applicationContext)
        glyphManager?.init(object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName?) {
                try {
                    glyphManager?.register(Glyph.DEVICE_24111)
                    glyphManager?.openSession()
                    Log.d(TAG, "Glyph service connected")
                } catch (e: GlyphException) {
                    Log.e(TAG, "Glyph initialization error: ${e.message}")
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                try {
                    glyphManager?.closeSession()
                    Log.d(TAG, "Glyph service disconnected")
                } catch (e: GlyphException) {
                    Log.e(TAG, "Glyph close session error: ${e.message}")
                }
            }
        })
    }

    private fun checkPermissionsAndOpenPicker() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openDocumentPicker()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                AUDIO_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun openDocumentPicker() {
        documentLauncher.launch(arrayOf("audio/*"))
    }

    private fun playMusic(uri: Uri) {
        stopMusicAndCleanup()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setOnPreparedListener {
                    start()
                    mIsPlaying = true
                    setupVisualizer(audioSessionId)
                    startGlyphUpdates()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error playing music: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "MediaPlayer error", e)
        }
    }

    private fun setupVisualizer(audioSessionId: Int) {
        visualizer = Visualizer(audioSessionId).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1] // 最大捕获大小

            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int
                ) {
                    // 不需要波形数据
                }

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fftData: ByteArray?,
                    samplingRate: Int
                ) {
                    fftData?.let { processFFTData(it, samplingRate) }
                }
            }, Visualizer.getMaxCaptureRate(), false, true) // 仅捕获FFT数据

            enabled = true
        }
    }

    private fun processFFTData(fftData: ByteArray, samplingRate: Int) {
        val n = fftData.size / 2
        if (n < 2) return

        // 计算频率区间
        val freqStep = samplingRate / 2f / n
        val lowFreqEnd = (300 / freqStep).toInt() + 1
        val midFreqEnd = (6000 / freqStep).toInt() + 1

        // 重置能量值
        var lowSum = 0f
        var midSum = 0f
        var highSum = 0f

        // 处理FFT数据 (实部和虚部交替)
        for (i in 2 until n * 2 step 2) {
            val real = fftData[i].toFloat()
            val imag = fftData[i + 1].toFloat()
            val magnitude = real * real + imag * imag

            val freqIndex = (i / 2) - 1
            when {
                freqIndex < lowFreqEnd -> lowSum += magnitude
                freqIndex < midFreqEnd -> midSum += magnitude
                else -> highSum += magnitude
            }
        }


        // 平滑处理并归一化
        lowFreqEnergy = if (lowSum < 500) {
            lowSum / 500f
        } else if (lowSum < 1000) {
            lowSum / 1000f
        } else if (lowSum < 5000) {
            lowSum / 5000f
        } else if (lowSum < 10000) {
            lowSum / 10000f
        } else if (lowSum < 50000) {
            lowSum / 50000f
        } else {
            lowSum / 100000f
        }
        //lowFreqEnergy = lowSum / 100000f //.coerceAtMost(1f)
        highFreqEnergy = highSum / 100000f //.coerceAtMost(1f)
        midFreqEnergy = (lowFreqEnergy + highFreqEnergy) / 2 // / 100000f //.coerceAtMost(1f)
        Log.e(TAG, "lowFreqEnergy = ${lowFreqEnergy}")
        Log.e(TAG, "midFreqEnergy = ${midFreqEnergy}")
        Log.e(TAG, "highFreqEnergy = ${highFreqEnergy}")
        Log.e(TAG, "======================================")
//        lowFreqEnergy = 0.5f
//        midFreqEnergy = 0.5f
//        highFreqEnergy = 0.5f
    }

    private fun startGlyphUpdates() {
        updateHandler = Handler(Looper.getMainLooper())
        updateHandler?.post(updateRunnable)
    }

    private fun updateGlyphLights() {
        if (!isSupportedDevice || !mIsPlaying) return

        try {
            val builder = glyphManager?.getGlyphFrameBuilder() ?: return

            // 清除之前的设置
            builder.buildPeriod(0)
            builder.buildCycles(0)
            builder.buildInterval(0)

            // 高频 - A灯条 (A1-A11)
            val aLevel = (highFreqEnergy * A_LED_RANGE.count()).toInt()
            A_LED_RANGE.take(aLevel).forEach { builder.buildChannel(it) }

            // 中频 - C灯条 (C1-C20)
            val cLevel = (midFreqEnergy * C_LED_RANGE.count()).toInt()
            C_LED_RANGE.take(cLevel).forEach { builder.buildChannel(it) }

            // 低频 - B灯条 (B1-B5)
            val bLevel = (lowFreqEnergy * B_LED_RANGE.count()).toInt()
            B_LED_RANGE.take(bLevel).forEach { builder.buildChannel(it) }

            val frame = builder.build()
            glyphManager?.toggle(frame)
        } catch (e: GlyphException) {
            Log.e(TAG, "Glyph update error: ${e.message}")
        }
    }

    private fun stopMusicAndCleanup() {
        mIsPlaying = false
        updateHandler?.removeCallbacks(updateRunnable)

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null

        try {
            glyphManager?.turnOff()
        } catch (e: GlyphException) {
            Log.e(TAG, "Glyph turn off error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMusicAndCleanup()

        try {
            glyphManager?.closeSession()
            glyphManager?.unInit()
        } catch (e: GlyphException) {
            Log.e(TAG, "Glyph cleanup error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            openDocumentPicker()
        }
    }
}
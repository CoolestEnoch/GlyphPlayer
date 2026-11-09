package ink.enoch.glyphplayer.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.databinding.ActivityMicrophoneVisualizerBinding
import ink.enoch.glyphplayer.service.MicrophoneService

class MicrophoneVisualizerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMicrophoneVisualizerBinding
    private var service: MicrophoneService? = null
    private var bound = false
    private var visible = false
    private var rendering = false
    private var resumeCapture = false
    private var microphoneGranted = false
    private var notificationsGranted = false
    private var lastState = MicrophoneService.State()

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshAccess() // Granting permission never starts a feature.
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshAccess()
    }
    private val observer: (MicrophoneService.State) -> Unit = ::render
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) return
            val capture = (binder as MicrophoneService.LocalBinder).getService()
            service = capture
            capture.listener = observer
            if (!capture.state.requested) capture.setOptions(binding.switchGlyph.isChecked,
                binding.switchDot.isChecked, binding.switchReverse.isChecked)
            capture.setUiVisible(true)
            if (resumeCapture && microphoneGranted && !capture.state.requested) capture.startCapture()
            resumeCapture = false
            render(capture.state)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            render(lastState.copy(capturing = false, requested = false, background = false))
        }
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
        resumeCapture = savedInstanceState?.getBoolean("resumeCapture") ?: false
        if (savedInstanceState != null) lastState = lastState.copy(
            glyph = savedInstanceState.getBoolean("glyph", true),
            dot = savedInstanceState.getBoolean("dot"), reverse = savedInstanceState.getBoolean("reverse"))
        binding.btnGrantMicrophone.setOnClickListener { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.btnGrantNotifications.setOnClickListener {
            if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.btnStartMicrophone.setOnClickListener { if (microphoneGranted) service?.startCapture() }
        binding.btnStopMicrophone.setOnClickListener { resumeCapture = false; service?.stopCapture() }
        binding.btnBackground.setOnClickListener {
            if (service?.state?.background == true) service?.disableBackground()
            else if (microphoneGranted && notificationsGranted) {
                ContextCompat.startForegroundService(this, Intent(this, MicrophoneService::class.java)
                    .setAction(MicrophoneService.ACTION_BACKGROUND))
            }
        }
        val changeOptions = { _: android.widget.CompoundButton, _: Boolean ->
            if (!rendering) service?.setOptions(binding.switchGlyph.isChecked,
                binding.switchDot.isChecked, binding.switchReverse.isChecked)
        }
        binding.switchGlyph.setOnCheckedChangeListener(changeOptions)
        binding.switchDot.setOnCheckedChangeListener(changeOptions)
        binding.switchReverse.setOnCheckedChangeListener(changeOptions)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        refreshAccess()
        bound = bindService(Intent(this, MicrophoneService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        if (!microphoneGranted) { resumeCapture = false; service?.stopCapture() }
        else if (!notificationsGranted && service?.state?.background == true) service?.disableBackground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("glyph", lastState.glyph)
        outState.putBoolean("dot", lastState.dot)
        outState.putBoolean("reverse", lastState.reverse)
        outState.putBoolean("resumeCapture", resumeCapture || (lastState.requested && !lastState.background))
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        visible = false
        service?.let {
            resumeCapture = it.state.requested && !it.state.background
            if (it.listener === observer) {
                it.listener = null
                it.setUiVisible(false)
            }
        }
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshAccess() {
        microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val channel = getSystemService(NotificationManager::class.java).getNotificationChannel("microphone_visualizer")
        notificationsGranted = hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            NotificationManagerCompat.from(this).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        render(service?.state ?: lastState)
    }

    private fun render(state: MicrophoneService.State) {
        lastState = state
        rendering = true
        try {
            binding.btnGrantMicrophone.isEnabled = !microphoneGranted
            binding.btnGrantMicrophone.setText(if (microphoneGranted) R.string.microphone_access_granted else R.string.grant_microphone)
            binding.btnGrantNotifications.isEnabled = !notificationsGranted
            binding.btnGrantNotifications.setText(if (notificationsGranted) R.string.notification_access_granted else R.string.grant_notifications)
            binding.btnStartMicrophone.isEnabled = microphoneGranted && service != null && !state.capturing
            binding.btnStopMicrophone.isEnabled = state.requested
            binding.btnBackground.isEnabled = service != null && ((microphoneGranted && notificationsGranted) || state.background)
            binding.btnBackground.setText(if (state.background) R.string.microphone_background_disable else R.string.microphone_background_enable)
            binding.tvMicrophoneStatus.text = state.message
            binding.tvGlyphStatus.text = state.glyphMessage
            binding.switchGlyph.isChecked = state.glyph
            binding.switchDot.isChecked = state.dot
            binding.switchReverse.isChecked = state.reverse
            binding.levelLow.progress = (state.levels.low * 100).toInt()
            binding.levelMid.progress = (state.levels.mid * 100).toInt()
            binding.levelHigh.progress = (state.levels.high * 100).toInt()
        } finally { rendering = false }
    }
}

package ink.enoch.glyphplayer.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import ink.enoch.glyphplayer.databinding.ActivityAudioPlaybackCaptureBinding
import ink.enoch.glyphplayer.service.AudioPlaybackCaptureService

class AudioPlaybackCaptureActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAudioPlaybackCaptureBinding
    private var service: AudioPlaybackCaptureService? = null
    private var bound = false
    private var visible = false
    private var rendering = false
    private var starting = false
    private var audioGranted = false
    private var notificationsGranted = false
    private var consent: Intent? = null // One-use authorization, never persisted or automatically reused.
    private var consentCode = RESULT_CANCELED
    private var lastState = AudioPlaybackCaptureService.State()
    private val observer: (AudioPlaybackCaptureService.State) -> Unit = { state -> starting = false; render(state) }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAccess() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAccess() }
    private val systemAudioAuthorization = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consent = if (result.resultCode == RESULT_OK) result.data else null
        consentCode = result.resultCode
        render(service?.state ?: lastState)
        if (consent == null) binding.tvCaptureStatus.setText(R.string.system_audio_denied)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) return
            val capture = (binder as AudioPlaybackCaptureService.LocalBinder).getService()
            service = capture
            val preferences = lastState // Listener updates must not overwrite preferences mid-restore.
            capture.listener = observer
            if (!capture.state.capturing) {
                capture.setOptions(preferences.glyph, preferences.dot, preferences.reverse)
                capture.setBackground(preferences.background && notificationsGranted)
            }
            capture.setUiVisible(true)
            render(capture.state)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            consent = null
            render(lastState.copy(capturing = false, background = false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAudioPlaybackCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (savedInstanceState != null) lastState = lastState.copy(
            glyph = savedInstanceState.getBoolean("glyph", true), dot = savedInstanceState.getBoolean("dot"),
            reverse = savedInstanceState.getBoolean("reverse"), background = savedInstanceState.getBoolean("background"))
        binding.btnGrantAudio.setOnClickListener { audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.btnGrantNotifications.setOnClickListener {
            if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        binding.btnAuthorizeSystemAudio.setOnClickListener {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            systemAudioAuthorization.launch(request)
        }
        binding.btnStartCapture.setOnClickListener {
            val approval = consent ?: return@setOnClickListener
            if (!audioGranted || service == null) return@setOnClickListener
            consent = null // Consume exactly once, including failed start attempts.
            starting = true
            render(lastState)
            try {
                ContextCompat.startForegroundService(this, Intent(this, AudioPlaybackCaptureService::class.java)
                    .setAction(AudioPlaybackCaptureService.ACTION_START)
                    .putExtra(AudioPlaybackCaptureService.EXTRA_CONSENT, approval)
                    .putExtra(AudioPlaybackCaptureService.EXTRA_RESULT_CODE, consentCode))
            } catch (e: Exception) {
                starting = false
                render(lastState)
                binding.tvCaptureStatus.text = "Cannot start capture: ${e.message}. Authorize system audio again to retry."
            }
        }
        binding.btnStopCapture.setOnClickListener { consent = null; service?.stopCapture(); render(service?.state ?: lastState) }
        binding.btnBackground.setOnClickListener { service?.let { it.setBackground(!it.state.background) } }
        val options = { _: android.widget.CompoundButton, _: Boolean ->
            if (!rendering) service?.setOptions(binding.switchGlyph.isChecked, binding.switchDot.isChecked, binding.switchReverse.isChecked)
        }
        binding.switchGlyph.setOnCheckedChangeListener(options)
        binding.switchDot.setOnCheckedChangeListener(options)
        binding.switchReverse.setOnCheckedChangeListener(options)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        refreshAccess()
        bound = bindService(Intent(this, AudioPlaybackCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        if (!audioGranted) { consent = null; service?.stopCapture() }
        else if (!notificationsGranted && service?.state?.background == true) service?.setBackground(false)
    }

    override fun onStop() {
        visible = false
        starting = false
        consent = null
        service?.let {
            if (it.listener === observer) { it.listener = null; it.setUiVisible(false) }
        }
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("glyph", lastState.glyph)
        outState.putBoolean("dot", lastState.dot)
        outState.putBoolean("reverse", lastState.reverse)
        outState.putBoolean("background", lastState.background)
        super.onSaveInstanceState(outState)
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshAccess() {
        audioGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(AudioPlaybackCaptureService.CHANNEL)
        notificationsGranted = hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            NotificationManagerCompat.from(this).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        render(service?.state ?: lastState)
    }

    private fun render(state: AudioPlaybackCaptureService.State) {
        lastState = state
        rendering = true
        try {
            binding.btnGrantAudio.isEnabled = !audioGranted
            binding.btnGrantAudio.setText(if (audioGranted) R.string.audio_access_granted else R.string.grant_audio_capture)
            binding.btnGrantNotifications.isEnabled = !notificationsGranted
            binding.btnGrantNotifications.setText(if (notificationsGranted) R.string.notification_access_granted else R.string.grant_notifications)
            binding.btnAuthorizeSystemAudio.isEnabled = !starting && service != null && audioGranted && !state.capturing && consent == null
            binding.btnStartCapture.isEnabled = !starting && service != null && audioGranted && !state.capturing && consent != null
            binding.btnStopCapture.isEnabled = state.capturing || consent != null
            binding.btnBackground.isEnabled = !starting && service != null && (notificationsGranted || state.background)
            binding.btnBackground.setText(if (state.background) R.string.system_audio_background_disable else R.string.microphone_background_enable)
            binding.switchGlyph.isEnabled = !starting
            binding.switchDot.isEnabled = !starting
            binding.switchReverse.isEnabled = !starting
            binding.tvCaptureStatus.text = if (starting) "Starting capture…" else if (consent != null) getString(R.string.system_audio_authorized) else state.message
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

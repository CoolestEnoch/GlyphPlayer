package ink.enoch.glyphplayer.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.ui.MicrophoneVisualizerActivity
import ink.enoch.glyphplayer.utils.*

/** Capture has one owner. The UI binds to it; only an explicit action enables foreground mode. */
class MicrophoneService : Service() {
    data class State(
        val requested: Boolean = false,
        val capturing: Boolean = false,
        val background: Boolean = false,
        val glyph: Boolean = true,
        val dot: Boolean = false,
        val reverse: Boolean = false,
        val levels: SpectrumLevels = SpectrumLevels.SILENT,
        val message: String = "Microphone stopped",
        val glyphMessage: String = "Glyph Interface disabled"
    )
    @Volatile var state = State()
        private set
    var listener: ((State) -> Unit)? = null
    private var uiVisible = false
    private var captureActive: AtomicBoolean? = null
    private var analyzer: MicrophoneAnalyzer? = null
    private var frames: LatestValueDispatcher<SpectrumLevels>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() { fun getService() = this@MicrophoneService }
    override fun onBind(intent: Intent?): IBinder = binder

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible && state.requested) startCapture()
        else if (!visible && !state.background) suspendCapture()
    }

    fun setOptions(glyph: Boolean, dot: Boolean, reverse: Boolean) {
        if (state.glyph == glyph && state.dot == dot && state.reverse == reverse) return
        val changedGlyph = state.glyph != glyph
        update(state.copy(glyph = glyph, dot = dot, reverse = reverse))
        if (changedGlyph) {
            if (glyph && state.capturing) connectGlyph() else GlyphHelper.release(this)
        }
        draw(state.levels)
    }

    fun startCapture() {
        if ((!uiVisible && !state.background) || analyzer != null) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            stopCapture("Microphone permission is required. Use Grant microphone access.")
            return
        }
        update(state.copy(requested = true, message = "Starting microphone…"))
        if (state.glyph) connectGlyph()
        val output = LatestValueDispatcher<SpectrumLevels>({ mainHandler.post(it) }) { levels ->
            update(state.copy(levels = levels))
        }
        frames = output
        val active = AtomicBoolean(true)
        captureActive = active
        val capture = MicrophoneAnalyzer({ levels ->
            // Called synchronously on the single FFT worker, not on a second rendering task.
            synchronized(GlyphHelper) { if (active.get()) draw(levels) }
            if (active.get()) output.offer(levels) // Only UI publication is asynchronous/coalesced.
        }) { error ->
            mainHandler.post { if (frames === output) stopCapture("Microphone error: $error") }
        }
        analyzer = capture
        if (capture.start()) update(state.copy(capturing = true, message = "Listening…"))
        else stopCapture("Microphone could not be started")
    }

    fun disableBackground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        update(state.copy(background = false))
        if (!uiVisible) suspendCapture()
        stopSelf()
    }

    fun stopCapture(message: String = "Microphone stopped") {
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        releaseCapture()
        update(state.copy(requested = false, capturing = false, background = false,
            levels = SpectrumLevels.SILENT, message = message, glyphMessage = "Glyph Interface disabled"))
        stopSelf()
    }

    private fun suspendCapture() {
        releaseCapture()
        update(state.copy(capturing = false, levels = SpectrumLevels.SILENT,
            message = if (state.requested) "Paused while in background" else "Microphone stopped",
            glyphMessage = "Glyph Interface disabled"))
    }

    private fun releaseCapture() {
        captureActive?.set(false)
        captureActive = null
        frames?.close()
        frames = null
        analyzer?.stop()
        analyzer = null
        GlyphHelper.release(this)
    }

    private fun connectGlyph() {
        if (!Common.is24111()) {
            update(state.copy(glyphMessage = "Glyph requires Nothing Phone (3a) or (3a) Pro"))
            return
        }
        // Claim the SDK session before other screens can draw or release it.
        GlyphHelper.release()
        GlyphHelper.init(this, Glyph.DEVICE_24111, onStatus = { message ->
            update(state.copy(glyphMessage = message))
        }, owner = this, onConnected = { draw(state.levels) })
    }

    private fun draw(levels: SpectrumLevels) {
        val options = state
        if (!options.glyph || !options.capturing) return
        GlyphHelper.show(GlyphHelper.GlyphTick(levels.low, levels.mid, levels.high,
            !options.reverse, options.reverse, options.reverse), options.dot, owner = this)
    }

    private fun update(next: State) {
        if (state == next) return
        state = next
        listener?.invoke(next)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_BACKGROUND -> enableBackground()
        }
        // Do not silently restart the microphone after a system kill or reboot.
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout") // Deliberate continuous capture, released on every stop/error path.
    private fun enableBackground() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) ||
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS) ||
            !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            stopCapture("Grant microphone and notification access before enabling background mode.")
            return
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Microphone visualizer", NotificationManager.IMPORTANCE_LOW))
            if (notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                stopCapture("Enable the Microphone visualizer notification channel in app settings.")
                return
            }
            val open = PendingIntent.getActivity(this, 0, Intent(this, MicrophoneVisualizerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 0, Intent(this, MicrophoneService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("Microphone Visualizer")
                .setContentText("Microphone is active in the background. Tap Stop to finish.")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, "Stop", stop).setDeleteIntent(stop).build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "GlyphPlayer:Microphone").apply { acquire() }
            }
            update(state.copy(background = true))
            startCapture()
        } catch (e: Exception) {
            stopCapture("Cannot enable background mode: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!state.background) stopCapture()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        listener = null
        releaseCapture()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_BACKGROUND = "ink.enoch.glyphplayer.MICROPHONE_BACKGROUND"
        const val ACTION_STOP = "ink.enoch.glyphplayer.MICROPHONE_STOP"
        private const val CHANNEL = "microphone_visualizer"
        private const val NOTIFICATION_ID = 2001
    }
}

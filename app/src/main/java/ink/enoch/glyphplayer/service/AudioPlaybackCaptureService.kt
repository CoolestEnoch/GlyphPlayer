package ink.enoch.glyphplayer.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.ui.AudioPlaybackCaptureActivity
import ink.enoch.glyphplayer.utils.*
import java.util.concurrent.atomic.AtomicBoolean

/** System playback only. This service never opens a physical microphone or creates a display. */
class AudioPlaybackCaptureService : Service() {
    data class State(
        val capturing: Boolean = false,
        val background: Boolean = false,
        val glyph: Boolean = true,
        val dot: Boolean = false,
        val reverse: Boolean = false,
        val levels: SpectrumLevels = SpectrumLevels.SILENT,
        val message: String = "System audio capture stopped",
        val glyphMessage: String = "Glyph Interface disabled"
    )
    @Volatile var state = State()
        private set
    var listener: ((State) -> Unit)? = null
    private var uiVisible = false
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audio: PcmAudioCapture? = null
    private var output: LatestValueDispatcher<SpectrumLevels>? = null
    private var active: AtomicBoolean? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSoundAt = 0L
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() { fun getService() = this@AudioPlaybackCaptureService }
    override fun onBind(intent: Intent?): IBinder = binder

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible && state.capturing && !state.background) stopCapture("Capture stopped in background. Authorize system audio again to restart.")
    }

    fun setOptions(glyph: Boolean, dot: Boolean, reverse: Boolean) {
        if (state.glyph == glyph && state.dot == dot && state.reverse == reverse) return
        val glyphChanged = state.glyph != glyph
        update(state.copy(glyph = glyph, dot = dot, reverse = reverse))
        if (glyphChanged) {
            if (glyph && state.capturing) connectGlyph() else GlyphHelper.release(this)
        }
        draw(state.levels)
    }

    fun setBackground(enabled: Boolean) {
        val allowed = enabled && notificationsAvailable()
        if (state.capturing && allowed) acquireWakeLock()
        if (!allowed) releaseWakeLock()
        update(state.copy(background = allowed))
        if (!uiVisible && state.capturing && !allowed) stopCapture()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!state.capturing) startCapture(intent)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopCapture("Audio permission is required. Use Grant audio capture access.")
            return
        }
        try {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val consent = intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            check(code == Activity.RESULT_OK && consent != null) { "Authorize system audio before starting capture" }
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "System audio capture", NotificationManager.IMPORTANCE_LOW))
            // Even foreground-only MediaProjection must run in a foreground service. Android permits
            // this without POST_NOTIFICATIONS; optional background mode still requires that grant.
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val session = checkNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, consent)) {
                "System audio authorization is no longer valid"
            }
            projection = session
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === session) stopCapture("System audio authorization ended. Authorize again to restart.")
                }
            }
            projectionCallback = callback
            session.registerCallback(callback, mainHandler)
            update(state.copy(capturing = true,
                background = state.background && notificationsAvailable(), message = "Capturing system audio…"))
            if (!uiVisible && !state.background) { stopCapture(); return }
            if (state.background) acquireWakeLock()
            if (state.glyph) connectGlyph()
            val processing = AtomicBoolean(true)
            active = processing
            lastSoundAt = SystemClock.elapsedRealtime()
            val ui = LatestValueDispatcher<SpectrumLevels>({ mainHandler.post(it) }) { levels ->
                if (maxOf(levels.low, levels.mid, levels.high) > 0.01f) lastSoundAt = SystemClock.elapsedRealtime()
                update(state.copy(levels = levels, message = if (SystemClock.elapsedRealtime() - lastSoundAt > 3000)
                    "No capturable audio. Play media in an app that allows playback capture." else "Capturing system audio…"))
            }
            output = ui
            val capture = PcmAudioCapture({ createRecorder(session) }, music = true, onLevels = { levels ->
                synchronized(GlyphHelper) { if (processing.get()) draw(levels) }
                if (processing.get()) ui.offer(levels)
            }, onError = { error ->
                mainHandler.post { if (output === ui) stopCapture("System audio capture error: $error") }
            })
            audio = capture
            if (!capture.start()) stopCapture("Cannot start system audio capture. Authorize again to retry.")
        } catch (e: Exception) {
            stopCapture("Cannot start capture: ${e.message}. Authorize system audio again to retry.")
        }
    }

    @SuppressLint("MissingPermission") // The explicit Grant audio capture access button grants RECORD_AUDIO.
    private fun createRecorder(session: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(session)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val minimum = AudioRecord.getMinBufferSize(PcmAudioCapture.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "System audio format is not supported" }
        return AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(PcmAudioCapture.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, PcmAudioCapture.FRAME_SIZE * 4))
            .setAudioPlaybackCaptureConfig(config).build()
    }

    private fun connectGlyph() {
        GlyphHelper.release()
        GlyphHelper.init(this, Glyph.DEVICE_24111, onStatus = { update(state.copy(glyphMessage = it)) },
            owner = this, onConnected = { draw(state.levels) })
    }

    private fun draw(levels: SpectrumLevels) {
        val options = state
        if (options.capturing && options.glyph) GlyphHelper.show(GlyphHelper.GlyphTick(levels.low, levels.mid, levels.high,
            !options.reverse, options.reverse, options.reverse), options.dot, owner = this)
    }

    fun stopCapture(message: String = "System audio capture stopped") {
        active?.set(false)
        active = null
        output?.close()
        output = null
        audio?.stop()
        audio = null
        val old = projection
        projection = null // Invalidate before stop(), so its callback cannot recursively stop us.
        projectionCallback?.let { old?.unregisterCallback(it) }
        projectionCallback = null
        try { old?.stop() } catch (_: Exception) { }
        GlyphHelper.release(this)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        update(state.copy(capturing = false, background = false, levels = SpectrumLevels.SILENT,
            message = message, glyphMessage = "Glyph Interface disabled"))
        stopSelf()
    }

    private fun update(next: State) {
        if (state == next) return
        state = next
        listener?.invoke(next)
    }

    private fun notificationsAvailable(): Boolean {
        val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(this).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, AudioPlaybackCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, AudioPlaybackCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("Audio Playback Capture").setContentText("System playback audio is being visualized. Tap Stop to finish.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop).setDeleteIntent(stop).build()
    }

    @SuppressLint("WakelockTimeout") // Explicit background capture, released on stop/revocation/destruction.
    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphPlayer:PlaybackCapture").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        listener = null
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ink.enoch.glyphplayer.PLAYBACK_CAPTURE_START"
        const val ACTION_STOP = "ink.enoch.glyphplayer.PLAYBACK_CAPTURE_STOP"
        const val EXTRA_CONSENT = "consent"
        const val EXTRA_RESULT_CODE = "result_code"
        const val CHANNEL = "audio_playback_capture"
        private const val NOTIFICATION_ID = 2002
    }
}

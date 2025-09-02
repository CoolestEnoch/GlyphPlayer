package ink.enoch.glyphplayer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.base.Objects
import ink.enoch.glyphplayer.R

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        initializePlayer()
        createNotificationChannel()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()

        // 设置MediaSession
        mediaSession = MediaSession.Builder(this, exoPlayer!!)
//            .setCallback(MediaSessionCallback())
            .build()

        // 设置播放器监听器
        exoPlayer!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> updateNotification()
                    Player.STATE_ENDED -> {}
                    else -> {}
                }
            }
        })
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "music_channel",
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager!!.createNotificationChannel(channel)
    }

    @OptIn(UnstableApi::class)
    private fun updateNotification() {
        // 创建播放通知
        val notification = Notification.Builder(this, "music_channel")
            .setSmallIcon(R.drawable.ic_music_note)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession!!.platformToken))
            .build()

        // 将服务设置为前台服务
        startForeground(1, notification)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession!!.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer!!.release()
        exoPlayer = null
        super.onDestroy()
    }

    // MediaSession回调处理
    inner class MediaSessionCallback : MediaSession.Callback {
//        override fun onPlay() {
//            exoPlayer!!.play()
//        }
//
//        fun onPause(request: PauseRequest) {
//            exoPlayer!!.pause()
//        }
//
//        fun onSeekTo(request: SeekToRequest) {
//            exoPlayer!!.seekTo(request.position)
//        }
//
//        fun onSetMediaUri(request: SetMediaUriRequest) {
//            val mediaItem = MediaItem.fromUri(request.uri)
//            exoPlayer!!.setMediaItem(mediaItem)
//            exoPlayer!!.prepare()
//            exoPlayer!!.play()
//        }
    }

    // 用于Activity绑定的Binder
    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        super.onBind(intent)
        return binder
    }

    // 公共方法供Activity调用
    fun play() {
        exoPlayer!!.play()
    }

    fun pause() {
        exoPlayer!!.pause()
    }

    fun seekTo(position: Long) {
        exoPlayer!!.seekTo(position)
    }

    fun playFromUri(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer!!.setMediaItem(mediaItem)
        exoPlayer!!.prepare()
        exoPlayer!!.play()
    }

    fun getCurrentPosition(): Long {
        return exoPlayer!!.currentPosition ?: 0
    }

    fun getDuration(): Long {
        return exoPlayer!!.duration ?: 0
    }

    fun isPlaying(): Boolean {
        return exoPlayer!!.isPlaying ?: false
    }

    fun hasMediaItem(): Boolean {
        return exoPlayer!!.mediaItemCount != 0
    }

    fun addListener(listener:Player.Listener){
        exoPlayer!!.addListener(listener)
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun getAudioSessionId(): Int{
        return exoPlayer!!.audioSessionId
    }

    fun releasePlayer() {
        exoPlayer!!.stop()
        exoPlayer!!.release()
        stopSelf()
    }
}
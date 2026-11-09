package ink.enoch.glyphplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.ui.MusicPlayerUI

/** A single session/player supplies the UI, system controls, and Media3 foreground notification. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_music_note)
        })
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_ALL
        }
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MusicPlayerUI::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setSessionExtras(audioSessionExtras(player.audioSessionId)).build()
        // Session extras expose the audio effect session ID without a second service binder.
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                session?.setSessionExtras(audioSessionExtras(audioSessionId))
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun audioSessionExtras(id: Int) = Bundle().apply { putInt(EXTRA_AUDIO_SESSION_ID, id) }

    companion object {
        const val EXTRA_AUDIO_SESSION_ID = "ink.enoch.glyphplayer.AUDIO_SESSION_ID"
    }
}

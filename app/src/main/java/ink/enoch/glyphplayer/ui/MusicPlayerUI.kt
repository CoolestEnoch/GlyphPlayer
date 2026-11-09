package ink.enoch.glyphplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.material.snackbar.Snackbar
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.databinding.ActivityMusicPlayerUiBinding
import ink.enoch.glyphplayer.service.PlaybackService
import ink.enoch.glyphplayer.utils.AudioFrequencyAnalyzer
import ink.enoch.glyphplayer.utils.GlyphHelper
import ink.enoch.glyphplayer.utils.formatTime
import ink.enoch.glyphplayer.utils.readAudioItem
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MusicPlayerUI : AppCompatActivity() {
    private lateinit var binding: ActivityMusicPlayerUiBinding
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val playlist = mutableListOf<MediaItem>()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var analyzerSessionId = 0
    private var visible = false
    private var dotMode = false
    private var analyzer: AudioFrequencyAnalyzer? = null
    private var artworkId: String? = null
    private var loading: AlertDialog? = null

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && visible) { initGlyph(); syncPlaybackUi() }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                loadPlaylist(uri)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Cannot access this folder: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlaybackUi() }
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MusicPlayerUI, "Playback failed: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
    private val progress = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        binding = ActivityMusicPlayerUiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivBlurBackground.setRenderEffect(RenderEffect.createBlurEffect(25f, 25f, Shader.TileMode.CLAMP))
        binding.btnPlayPause.isEnabled = false
        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) { controller?.seekTo(value.toLong()); updateProgress() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
        })
        binding.btnPlayPause.setOnClickListener {
            val playback = controller ?: return@setOnClickListener
            when {
                playback.mediaItemCount > 0 -> {
                    if (playback.playWhenReady && playback.playbackState != Player.STATE_ENDED) playback.pause()
                    else {
                        if (playback.playbackState == Player.STATE_IDLE) playback.prepare()
                        if (playback.playbackState == Player.STATE_ENDED) playback.seekToDefaultPosition()
                        playback.play()
                    }
                }
                playlist.isNotEmpty() -> playItems(0)
                else -> folderPicker.launch(null)
            }
        }
        binding.btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.btnShuffle.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            syncPlaybackUi()
        }
        binding.btnShuffle.setOnLongClickListener {
            dotMode = !dotMode
            Snackbar.make(binding.root, if (dotMode) "Glyph: Dot mode" else "Glyph: Bar mode", Snackbar.LENGTH_SHORT).show()
            true
        }
        binding.btnPlaylist.setOnClickListener {
            if (playlist.isEmpty()) folderPicker.launch(null)
            else binding.llPlayList.isVisible = !binding.llPlayList.isVisible
        }
        binding.btnPlaylist.setOnLongClickListener { folderPicker.launch(null); true }
        if (Common.is24111() && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        connectController()
        initGlyph()
        syncPlaybackUi()
        handler.post(progress)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(progress)
        stopAnalyzer()
        GlyphHelper.release()
        controller?.removeListener(listener)
        binding.btnPlayPause.isEnabled = false
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        loading?.dismiss()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun connectController() {
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) { syncPlaybackUi() }
                override fun onDisconnected(controller: MediaController) {
                    this@MusicPlayerUI.controller = null
                    binding.btnPlayPause.isEnabled = false
                    stopAnalyzer()
                }
            }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || !visible) return@addListener
            try {
                val playback = future.get()
                controller = playback
                playback.addListener(listener)
                if (playlist.isEmpty()) {
                    playlist.addAll((0 until playback.mediaItemCount).map {
                        val item = playback.getMediaItemAt(it)
                        item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
                    })
                    renderPlaylist()
                }
                syncPlaybackUi()
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot connect to player: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playItems(index: Int) {
        val playback = controller ?: return
        if (index !in playlist.indices) return
        val queued = (0 until playback.mediaItemCount).map(playback::getMediaItemAt)
        if (queued.map { it.mediaId } == playlist.map { it.mediaId }) playback.seekTo(index, 0)
        else playback.setMediaItems(playlist.toList(), index, 0)
        playback.prepare()
        playback.play()
    }

    private fun initGlyph() {
        if (Common.is24111()) GlyphHelper.init(this, Glyph.DEVICE_24111)
    }

    private fun stopAnalyzer() {
        analyzer?.stopAnalysis()
        analyzer = null
        analyzerSessionId = 0
        GlyphHelper.turnOffTheLight()
    }

    private fun syncPlaybackUi() {
        val playback = controller ?: return
        binding.btnPlayPause.isEnabled = true
        binding.btnPlayPause.text = if (playback.playWhenReady && playback.playbackState != Player.STATE_ENDED) "⏸️" else "▶️"
        binding.btnShuffle.text = if (playback.shuffleModeEnabled) "🔀" else "🔁"
        val item = playback.currentMediaItem
        binding.btnPrev.isEnabled = item != null
        binding.btnNext.isEnabled = item != null
        if (item == null) {
            artworkId = null
            binding.tvSongTitle.text = "Select a track"
            binding.tvArtist.text = "Artist"
            binding.tvAlbum.text = "Album"
            binding.ivAlbumArt.setImageResource(R.mipmap.default_music_bg)
            binding.ivBlurBackground.setImageResource(R.mipmap.default_music_bg)
        }
        if (item != null && item.mediaId != artworkId) {
            artworkId = item.mediaId
            val metadata = item.mediaMetadata
            binding.tvSongTitle.text = metadata.title ?: "Unknown track"
            binding.tvArtist.text = metadata.artist ?: "Unknown artist"
            binding.tvAlbum.text = metadata.albumTitle ?: "Unknown album"
            val cover = metadata.artworkData?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: BitmapFactory.decodeResource(resources, R.mipmap.default_music_bg)
            binding.ivAlbumArt.setImageBitmap(cover)
            binding.ivBlurBackground.setImageBitmap(cover)
        }
        updateProgress()
        val audioSessionId = playback.sessionExtras.getInt(PlaybackService.EXTRA_AUDIO_SESSION_ID)
        if (visible && playback.isPlaying && Common.is24111() && audioSessionId != 0 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (analyzer == null || analyzerSessionId != audioSessionId) {
                stopAnalyzer()
                analyzerSessionId = audioSessionId
                analyzer = AudioFrequencyAnalyzer(audioSessionId) { dotMode }.apply { startAnalysis() }
            }
        } else stopAnalyzer()
    }

    private fun updateProgress() {
        val playback = controller ?: return
        binding.sbProgress.max = playback.duration.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (!binding.sbProgress.isPressed) binding.sbProgress.progress = playback.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        binding.tvProgressTime.text = "${formatTime(playback.currentPosition.coerceAtLeast(0))} / ${formatTime(playback.duration.coerceAtLeast(0))}"
    }

    private fun loadPlaylist(uri: Uri) {
        loading = AlertDialog.Builder(this).setMessage("Loading playlist…").setCancelable(false).show()
        worker.execute {
            val items = mutableListOf<MediaItem>()
            var failure: Exception? = null
            try {
                val folders = java.util.ArrayDeque<DocumentFile>()
                DocumentFile.fromTreeUri(this, uri)?.let { folders.add(it) }
                while (folders.isNotEmpty() && !Thread.currentThread().isInterrupted) {
                    for (file in folders.removeFirst().listFiles()) {
                        if (Thread.currentThread().isInterrupted) break
                        if (file.isDirectory) folders.add(file)
                        else if (file.isFile && file.name?.substringAfterLast('.', "")?.lowercase() in listOf("mp3", "flac")) {
                            items.add(readAudioItem(this, file.uri))
                        }
                    }
                }
            } catch (e: Exception) { failure = e }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loading?.dismiss()
                if (failure != null) {
                    Toast.makeText(this, "Cannot load folder: ${failure.message}", Toast.LENGTH_LONG).show()
                } else {
                    playlist.clear()
                    playlist.addAll(items)
                    renderPlaylist()
                    binding.llPlayList.visibility = View.VISIBLE
                    if (items.isEmpty()) Toast.makeText(this, "No MP3 or FLAC files found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderPlaylist() {
        binding.llPlayList.removeAllViews()
        playlist.forEach { item ->
            val card = MusicInfoCard(this).apply {
                setMusicInfo(item.mediaMetadata.title?.toString() ?: "Unknown track", item.mediaMetadata.artist?.toString() ?: "Unknown artist")
                setOnClickView { playItems(playlist.indexOf(item)) }
                setOnDelete {
                    playlist.remove(item)
                    controller?.let { playback ->
                        val index = (0 until playback.mediaItemCount).firstOrNull { playback.getMediaItemAt(it).mediaId == item.mediaId }
                        if (index != null) playback.removeMediaItem(index)
                    }
                    binding.llPlayList.removeView(this)
                }
            }
            binding.llPlayList.addView(card)
        }
    }
}

package ink.enoch.glyphplayer.ui

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.android.material.snackbar.Snackbar
import ink.enoch.glyphplayer.R
import ink.enoch.glyphplayer.databinding.ActivityMusicPlayerUiBinding
import java.net.URLDecoder
import kotlin.concurrent.thread
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import ink.enoch.glyphplayer.utils.AudioFrequencyAnalyzer
import ink.enoch.glyphplayer.utils.GlyphHelper
import ink.enoch.glyphplayer.utils.runOnMainThread


class MusicPlayerUI : AppCompatActivity() {
    private lateinit var binding: ActivityMusicPlayerUiBinding

    // 音乐元信息
    data class MusicMetaData(
        val cover: Bitmap,
        val title: String,
        val artist: String,
        val album: String
    )

    private var selectedFolderUri: Uri? = null // 存储用户选择的文件夹 URI
    private val validExtList = listOf("mp3", "flac") // 支持的扩展名列表
    private val playList = mutableListOf<Uri>() // 播放列表
    private var playingIndex = 0 // 当前播放的音乐是播放列表里的第几个
    private var isShuffle = false // 随机播放
    private var isGlyphDotMode = false // Glyph是单点模式还是长条模式

    private var musicPlayer: ExoPlayer? = null // 主播放器
    private var mediaSession: MediaSession? = null
    private var frequencyAnalyzer: AudioFrequencyAnalyzer? = null

    // 注册 SAF 文件夹选择器
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // 1. 保存用户选择的文件夹 URI
                selectedFolderUri = uri

                // 2. 获取持久化访问权限
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                // 3. 获取文件夹内所有有效文件
                thread { initPlayList(uri) }
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMusicPlayerUiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 创建activity自带的代码
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        // 状态栏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 设置系统栏图标颜色（根据背景自适应）
        ViewCompat.getWindowInsetsController(window.decorView)?.apply {
            isAppearanceLightStatusBars = false // 深色背景用浅色图标
            isAppearanceLightNavigationBars = false
        }

        // 初始化Glyph
        if (!Common.is24111()) {
            Toast.makeText(
                this,
                "Glyph lights only supported on Phone (3a) and (3a) Pro",
                Toast.LENGTH_LONG
            ).show()
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                GlyphHelper.init(this, Glyph.DEVICE_24111)
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    114514
                )
            }
        }

        // 使用默认值设置封面和背景
        val albumArtBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.mipmap.miku)
        binding.ivAlbumArt.setImageBitmap(albumArtBitmap)
        binding.ivBlurBackground.setImageBitmap(albumArtBitmap)
        binding.ivBlurBackground.setRenderEffect(
            RenderEffect.createBlurEffect(
                25f,
                25f,
                Shader.TileMode.REPEAT
            )
        )


        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    musicPlayer?.seekTo(progress.toLong()) // 更新播放进度
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.sbProgress.post(object : Runnable {
            override fun run() {
                musicPlayer?.let { player ->
                    if (player.isPlaying) {
                        // 获取当前播放位置（毫秒）
                        val currentPosition = player.currentPosition.toInt()
                        binding.sbProgress.progress = currentPosition
                    }
                }
                // 每1秒更新一次进度
                binding.sbProgress.postDelayed(this, 1000)
            }
        })

        binding.btnPrev.setOnClickListener {
            if (playList.isEmpty()) {
                // 不存在播放列表, 选择文件夹，仅初始化播放列表，不初始化播放器
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                folderPickerLauncher.launch(intent)
            } else {
                destroyPlayer()
                if (isShuffle) {
                    playNewMusic(playList.random())
                } else {
                    playingIndex -= 1
                    if (playingIndex < 0) playingIndex = (playList.size - 1) % playList.size
                    playNewMusic(playList[playingIndex])
                }
            }
        }
        binding.btnNext.setOnClickListener {
            if (playList.isEmpty()) {
                // 不存在播放列表, 选择文件夹，仅初始化播放列表，不初始化播放器
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                folderPickerLauncher.launch(intent)
            } else {
                destroyPlayer()
                if (isShuffle) {
                    playNewMusic(playList.random())
                } else {
                    playingIndex = (playingIndex + 1) % playList.size
                    playNewMusic(playList[playingIndex])
                }
            }
        }

        binding.btnPlayPause.setOnLongClickListener {
            destroyPlayer()
            playList.clear()
            binding.llPlayList.removeAllViews()

            true
        }
        binding.btnPlayPause.setOnClickListener {
            if (playList.isEmpty()) {
                // 不存在播放列表, 选择文件夹，仅初始化播放列表，不初始化播放器
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                folderPickerLauncher.launch(intent)
            } else {
                if (musicPlayer != null) {
                    // 存在播放列表, 切换播放状态控制
                    if (musicPlayer!!.isPlaying) {
                        musicPlayer!!.pause()
                        binding.btnPlayPause.text = "▶\uFE0F"
                        frequencyAnalyzer?.stopAnalysis()
                        frequencyAnalyzer = null
                        thread {
                            Thread.sleep(500)
                            GlyphHelper.turnOffTheLight()
                        }
                    } else {
                        musicPlayer!!.play()
                        binding.btnPlayPause.text = "⏸\uFE0F"
                        if (frequencyAnalyzer == null) {
                            frequencyAnalyzer =
                                AudioFrequencyAnalyzer(musicPlayer!!.audioSessionId) { isGlyphDotMode }.apply {
                                    startAnalysis()
                                }
                        }
                    }
                } else {
                    val selectedMusicUri = playList.random()
                    val index = playList.indexOf(selectedMusicUri)
                    playingIndex = index
                    playNewMusic(selectedMusicUri)
                }
            }
        }

        binding.btnShuffle.setOnLongClickListener {
            isGlyphDotMode = !isGlyphDotMode
            val modeTip = if (isGlyphDotMode) {
                "单点模式"
            } else {
                "长条模式"
            }
            Snackbar.make(window.decorView, "Glyph: $modeTip", Snackbar.LENGTH_LONG).show()

            true
        }
        binding.btnShuffle.setOnClickListener {
            isShuffle = !isShuffle
            if (isShuffle) {
                binding.btnShuffle.text = "🔀"
            } else {
                binding.btnShuffle.text = "\uD83D\uDD01"
            }
        }

        binding.btnPlaylist.setOnClickListener {
            if (binding.llPlayList.isVisible) {
                binding.llPlayList.visibility = View.GONE
            } else {
                binding.llPlayList.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyPlayer()
        GlyphHelper.release()
        frequencyAnalyzer?.stopAnalysis()
        frequencyAnalyzer = null
    }


    private fun initPlayList(folderUri: Uri) {
        fun isValidAudioFile(file: DocumentFile): Boolean {
            if (!file.isFile) return false
            // 获取小写扩展名（不带点）
            val extension = file.name?.substringAfterLast('.', "")?.lowercase()
            return extension in validExtList
        }

        fun loadFilesRecursive(uri: Uri) {
            val resolver: ContentResolver = contentResolver
            val folderDoc = DocumentFile.fromTreeUri(this, uri)
            folderDoc?.listFiles()?.forEach { file ->
                when {
                    file.isDirectory -> loadFilesRecursive(file.uri)
                    isValidAudioFile(file) -> playList.add(file.uri)
                }
            }
        }

        playList.clear()
        val folderDoc = DocumentFile.fromTreeUri(this, folderUri)
        folderDoc?.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> {
                    // 递归遍历子文件夹
                    loadFilesRecursive(file.uri)
                }

                isValidAudioFile(file) -> {
                    playList.add(file.uri)
                }
            }
        }
        // 现在 playList 包含所有有效音频文件的 URI
        // 可以开始使用播放列表
        var index = 0
        playList.forEach { item ->
            runOnUiThread {
                binding.llPlayList.addView(createPlaylistMusicCard(item))
            }
            index++
        }

    }

    fun createPlaylistMusicCard(uri: Uri) = MusicInfoCard(this).apply {
        val metadata = getMusicMetadata(uri)
        setMusicInfo(metadata.title, metadata.artist)
        setOnDelete {
            playList.remove(uri)
            binding.llPlayList.removeView(this)
        }
        setOnClickView {
            destroyPlayer()
            playNewMusic(uri)
        }
    }

    private fun destroyPlayer() {
        mediaSession?.apply {
            release()
        }
        mediaSession = null
        musicPlayer?.apply {
            stop()
            release()
        }
        musicPlayer = null
        binding.tvPlayList.text = ""

        frequencyAnalyzer?.stopAnalysis()
        frequencyAnalyzer = null
    }

    @OptIn(UnstableApi::class)
    private fun playNewMusic(uri: Uri) {
        // 获取音乐uri
        val selectedMusicUri = uri
        // 设置封面等信息
        val metadata = getMusicMetadata(selectedMusicUri)
        binding.ivBlurBackground.setImageBitmap(metadata.cover)
        binding.ivAlbumArt.setImageBitmap(metadata.cover)
        binding.tvSongTitle.text = metadata.title
        binding.tvArtist.text = metadata.artist
        binding.tvAlbum.text = metadata.album
        // 开始播放
        musicPlayer = ExoPlayer.Builder(this@MusicPlayerUI).build()
        musicPlayer!!.apply {
            val thisPlayer = musicPlayer!!
            val mediaItem = MediaItem.fromUri(selectedMusicUri)
            setMediaItem(mediaItem)
            prepare()
            mediaSession = MediaSession.Builder(this@MusicPlayerUI, thisPlayer).build()
            Log.d("musicPlayer", "thisPlayer.audioSessionId = ${thisPlayer.audioSessionId}")
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    super.onAudioSessionIdChanged(audioSessionId)
                    if (frequencyAnalyzer == null) {
                        frequencyAnalyzer =
                            AudioFrequencyAnalyzer(thisPlayer.audioSessionId) { isGlyphDotMode }.apply {
                                startAnalysis()
                            }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    Log.d("musicPlayer", "state = $state")
                    when (state) {
                        Player.STATE_READY -> {
                            Log.d("musicPlayer", "duration = ${thisPlayer.duration}")
                            play()
                            binding.sbProgress.max = thisPlayer.duration.toInt()
                        }

                        Player.STATE_ENDED -> {
                            frequencyAnalyzer?.stopAnalysis()
                            frequencyAnalyzer = null
                            thread {
                                Thread.sleep(10)
                                runOnMainThread {
                                    binding.btnNext.callOnClick()
                                }
                            }
                        }
                    }
                }
            })
        }
    }


    // 工具函数
    // 获取音频元信息
    private fun getMusicMetadata(uri: Uri): MusicMetaData {
        val retriever = MediaMetadataRetriever()
        try {
            // 封面
            retriever.setDataSource(this, uri)
            val coverData = retriever.embeddedPicture
            val cover = if (coverData != null) {
                BitmapFactory.decodeByteArray(coverData, 0, coverData.size)
            } else {
                BitmapFactory.decodeResource(resources, R.mipmap.miku)
            }
            val title =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "暂无标题"
            val artist =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知歌手"
            val album =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"

            return MusicMetaData(cover, title, artist, album)
        } catch (e: Exception) {
            // 异常处理：若无法获取元数据，则返回默认值
            return MusicMetaData(
                BitmapFactory.decodeResource(resources, R.mipmap.miku),
                "暂无标题",
                "未知歌手",
                "未知专辑"
            )
        } finally {
            retriever.release()
        }
    }

    // FFT


}
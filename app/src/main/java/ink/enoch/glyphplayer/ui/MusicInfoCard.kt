package ink.enoch.glyphplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ink.enoch.glyphplayer.R

class MusicInfoCard(context: Context) : LinearLayout(context) {

    private val tvMusicInfo: TextView
    private val btnDelete: Button
    private var onDelete: () -> Unit
    private var onClickView: () -> Unit

    init {
        // 加载布局
        LayoutInflater.from(context).inflate(R.layout.music_info_card, this, true)
//        layoutParams = LinearLayout.LayoutParams(
//            LinearLayout.LayoutParams.MATCH_PARENT,
//            LinearLayout.LayoutParams.WRAP_CONTENT
//        )

        tvMusicInfo = findViewById(R.id.tvMusicInfo)
        btnDelete = findViewById(R.id.btnDelete)
        onDelete = {}
        onClickView = {}

        // 设置删除按钮点击事件
        btnDelete.setOnClickListener {
            onDelete()
        }
        this.setOnClickListener { onClickView() }
    }

    fun setMusicInfo(title: String, artist: String) {
        tvMusicInfo.text = "$title - $artist"
    }

    fun setOnDelete(block: () -> Unit) {
        onDelete = block
    }

    fun setOnClickView(block: () -> Unit) {
        onClickView = block
    }
}
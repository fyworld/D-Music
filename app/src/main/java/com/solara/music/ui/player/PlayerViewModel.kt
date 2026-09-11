package com.solara.music.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solara.music.data.LyricRepository
import com.solara.music.lyrics.LrcLine
import com.solara.music.lyrics.LrcParser
import com.solara.music.player.PlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * v1.4.9：歌词加载改走 LyricRepository——
 * 本地歌曲（内嵌标签/搜索匹配）+ 全部歌词磁盘缓存（离线可读）。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val lyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    val lyricLoading = MutableStateFlow(false)

    private var lastKey: String? = null

    init {
        viewModelScope.launch {
            PlayerManager.currentSong.collect { song ->
                if (song == null) {
                    lastKey = null
                    lyrics.value = emptyList()
                    lyricLoading.value = false
                    return@collect
                }
                val key = "${song.source}:${song.id}"
                if (key == lastKey) return@collect
                lastKey = key
                lyrics.value = emptyList()
                lyricLoading.value = true
                val raw = LyricRepository.fetchLyric(getApplication(), song)
                lyrics.value = LrcParser.parse(raw)
                lyricLoading.value = false
            }
        }
    }
}

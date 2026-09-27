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

    // v1.4.39：歌词全局修订号——「下载歌词」（本地歌曲页/播放页/批量）成功后
    // bump，播放页 collect 到变化即强制重取当前歌歌词（缓存已更新，直接命中）
    private val lyricRevision = MutableStateFlow(0)

    /** 歌词数据有外部更新（下载歌词/批量下载）时调用，触发播放页刷新。 */
    fun notifyLyricUpdated() {
        lyricRevision.value++
    }

    init {
        viewModelScope.launch {
            // v1.4.39：监听修订号——下载歌词后强制重取（lastKey 去重会挡住同歌重取）
            launch {
                lyricRevision.collect {
                    if (it > 0) refreshLyrics()
                }
            }
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

    /**
     * v1.4.17：重新加载当前歌歌词（保存校准歌词后调用）。
     * lastKey 去重会挡住同歌重取，这里强制清空再走一遍取词流程，
     * 让显示的 LRC 立即换成校准后的新时间轴。
     */
    fun refreshLyrics() {
        val song = PlayerManager.currentSong.value ?: return
        viewModelScope.launch {
            lastKey = null
            lyrics.value = emptyList()
            lyricLoading.value = true
            val raw = LyricRepository.fetchLyric(getApplication(), song)
            lyrics.value = LrcParser.parse(raw)
            lyricLoading.value = false
        }
    }
}

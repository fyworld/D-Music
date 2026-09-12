package com.solara.music.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solara.music.data.ExploreGenres
import com.solara.music.data.MusicApi
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.moved
import com.solara.music.player.PlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 探索雷达：从偏好风格中随机抽取一种 + 随机音源搜索 30 首，
 * 去重后追加进播放队列（队列空则自动开播）。移植自 Solara 网页版。
 */
class ExploreViewModel : ViewModel() {

    companion object {
        const val EXPLORE_COUNT = 30
        /** 原版探索雷达使用的音源池。 */
        private val RADAR_SOURCES = listOf("netease", "kuwo")
    }

    /** 本次探索使用的风格，null 表示尚未探索。 */
    val genre = MutableStateFlow<String?>(null)
    /** 本次探索新增的歌曲。 */
    val results = MutableStateFlow<List<Song>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** 一次性提示（Snackbar 用）。 */
    val toast = MutableStateFlow<String?>(null)

    init {
        // 恢复上次退出前的探索结果（风格 + 列表）
        val saved = Store.readExploreState()
        if (saved != null && saved.second.isNotEmpty()) {
            genre.value = saved.first
            results.value = saved.second
        }
    }

    /** 持久化探索状态（结果变化时调用）。 */
    private fun persist() {
        Store.saveExploreState(genre.value, results.value)
    }

    fun consumeToast() {
        toast.value = null
    }

    /** 探索结果手动拖动排序（仅影响本次结果展示）。 */
    fun moveResult(from: Int, to: Int): Boolean {
        val next = results.value.moved(from, to) ?: return false
        results.value = next
        persist()
        return true
    }

    /** 从探索结果移除一行。 */
    fun removeResult(index: Int) {
        val cur = results.value
        if (index !in cur.indices) return
        results.value = cur.filterIndexed { i, _ -> i != index }
        persist()
    }

    /** v1.4.26：批量从探索结果移除。 */
    fun removeResults(songs: List<Song>) {
        if (songs.isEmpty()) return
        results.value = results.value.filterNot { r -> songs.any { it.sameAs(r) } }
        persist()
    }

    /** 随机挑选风格：用户勾选的偏好优先，未勾选则用全部风格。 */
    private fun pickGenre(): String {
        val pool = Store.settings.value.radarGenres
            .filter { it.isNotBlank() }
            .ifEmpty { ExploreGenres.all }
        return pool.random()
    }

    private fun pickSource(): String = RADAR_SOURCES.random()

    fun explore() {
        if (isLoading.value) return
        val pickedGenre = pickGenre()
        val source = pickSource()
        genre.value = pickedGenre
        isLoading.value = true
        error.value = null
        viewModelScope.launch {
            runCatching { MusicApi.search(source, pickedGenre, page = 1, count = EXPLORE_COUNT) }
                .onSuccess { songs ->
                    if (songs.isEmpty()) {
                        error.value = "本次未找到「$pickedGenre」歌曲，换个风格再试试"
                        results.value = emptyList()
                        persist()
                    } else {
                        appendToQueue(songs, pickedGenre)
                    }
                }
                .onFailure { e ->
                    error.value = e.message ?: "探索失败，请稍后重试"
                }
            isLoading.value = false
        }
    }

    /** 去重后追加进播放队列；队列为空时从第一首自动播放。 */
    private fun appendToQueue(songs: List<Song>, pickedGenre: String) {
        val existing = PlayerManager.queue.value
        val existingKeys = existing.map { "${it.source}:${it.id}" }.toHashSet()
        val appended = songs.filter { s ->
            val key = "${s.source}:${s.id}"
            existingKeys.add(key) // add 返回 false 表示已存在
        }
        if (appended.isEmpty()) {
            toast.value = "本次探索的歌曲都已在队列中，换个风格试试"
            results.value = emptyList()
            return
        }
        val wasIdle = existing.isEmpty()
        if (wasIdle) {
            PlayerManager.setQueue(appended, 0)
        } else {
            appended.forEach { PlayerManager.addToQueue(it, autoplayIfIdle = false) }
        }
        results.value = appended
        persist()
        toast.value = "探索雷达：新增 ${appended.size} 首「$pickedGenre」歌曲" +
            if (wasIdle) "，开始播放" else ""
    }
}

package com.solara.music.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solara.music.data.ExploreGenres
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.MusicApi
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.moved
import com.solara.music.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** v1.4.45：探索页背景封面 URL——每次探索成功后从结果随机挑一首的封面。 */
    val bgCover = MutableStateFlow<String?>(null)

    init {
        // 恢复上次退出前的探索结果（风格 + 列表）
        val saved = Store.readExploreState()
        if (saved != null && saved.second.isNotEmpty()) {
            genre.value = saved.first
            results.value = saved.second
            // v1.4.45：恢复会话也从上次结果里随机挑一张背景封面
            pickBgCoverAsync(saved.second)
        }
        // v1.4.57：背景封面跟随当前播放歌曲自动切换——播到哪首换哪首。
        // 收集 currentSong 流，切歌时解析新歌封面更新 bgCover。
        // - 在线歌：fetchPicUrl（带内存缓存，重复切回不重复请求）
        // - 本地歌：只查 Store.localCoverUrl（在线匹配过的 URL 缓存）；
        //   不调聚合 API（无 local 源，注定失败——v1.4.28 教训），
        //   无缓存则静默保持当前背景
        // 点击歌曲的 setBgFromSong 行为保留（点击的歌通常随即开播，
        // 两者自然衔接）。
        viewModelScope.launch {
            PlayerManager.currentSong.collect { song ->
                if (song != null) {
                    val url = if (LocalCoverExtractor.isLocalSong(song)) {
                        withContext(Dispatchers.IO) { Store.localCoverUrl(song) }
                    } else {
                        runCatching { MusicApi.fetchPicUrl(song) }.getOrNull()
                    }
                    if (url != null) bgCover.value = url
                }
            }
        }
    }

    /** 持久化探索状态（结果变化时调用）。 */
    private fun persist() {
        Store.saveExploreState(genre.value, results.value)
    }

    /**
     * v1.4.45：从结果列表随机挑一首解析封面 URL 作页面背景。
     * 解析失败静默忽略（背景保持上一张/渐变兜底，不阻塞探索流程）。
     */
    private fun pickBgCoverAsync(songs: List<Song>) {
        if (songs.isEmpty()) return
        val pick = songs.random()
        viewModelScope.launch {
            runCatching { MusicApi.fetchPicUrl(pick) }
                .getOrNull()
                ?.let { bgCover.value = it }
        }
    }

    /**
     * v1.4.50：点击结果列表某首歌时，卡片背景换成这首歌的封面。
     * 解析失败静默忽略（保持当前背景）。
     */
    fun setBgFromSong(song: Song) {
        viewModelScope.launch {
            runCatching { MusicApi.fetchPicUrl(song) }
                .getOrNull()
                ?.let { bgCover.value = it }
        }
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
                        // v1.4.45：每次探索成功都随机换一张背景封面
                        pickBgCoverAsync(songs)
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

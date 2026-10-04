package com.solara.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solara.music.customsource.CustomSourceManager
import com.solara.music.data.MusicApi
import com.solara.music.data.PlatformSearchApi
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.moved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {

    companion object {
        const val PAGE_SIZE = 30
    }

    val query = MutableStateFlow("")
    val source = MutableStateFlow(Store.settings.value.source)
    val results = MutableStateFlow<List<Song>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val isLoadingMore = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val hasMore = MutableStateFlow(false)

    private var page = 1

    init {
        // 恢复上次退出前的搜索状态（关键词/音源/结果/页码）
        val saved = Store.readSearchState()
        if (saved != null && saved.songs.isNotEmpty()) {
            query.value = saved.query
            source.value = saved.source
            results.value = saved.songs
            page = saved.page
            hasMore.value = saved.songs.size >= PAGE_SIZE
        }
    }

    /** 持久化当前搜索状态（结果变化时调用）。 */
    private fun persist() {
        Store.saveSearchState(query.value, source.value, results.value, page)
    }

    fun onQueryChange(q: String) {
        query.value = q
    }

    /**
     * v1.5.1 r29：当前是否处于「平台直连搜索」模式——
     * 启用了自定义音源 + 优先策略 + 沙箱就绪（脚本声明了支持的源）。
     * 此时搜索页源列表切换为「聚合 + 脚本支持的源」。
     */
    val platformMode: Boolean
        get() = CustomSourceManager.isPreferred && CustomSourceManager.sandboxReady

    /** 平台直连模式下的源 tab 列表（聚合 + 脚本支持的源）。 */
    val platformSources: List<Pair<String, String>>
        get() {
            val supported = CustomSourceManager.sandboxSources.keys
                .filter { it in PlatformSearchApi.sourceNames }
                .sortedBy { PlatformSearchApi.sourceNames.keys.indexOf(it) }
            if (supported.isEmpty()) return emptyList()
            return buildList {
                add(PlatformSearchApi.SRC_ALL to "聚合")
                supported.forEach { add(it to PlatformSearchApi.sourceNames.getValue(it)) }
            }
        }

    fun changeSource(src: String) {
        if (source.value == src) return
        source.value = src
        Store.updateSettings { it.copy(source = src) }
        if (query.value.isNotBlank()) search()
    }

    /** 搜索结果手动拖动排序（仅影响本次结果展示）。 */
    fun moveResult(from: Int, to: Int): Boolean {
        val next = results.value.moved(from, to) ?: return false
        results.value = next
        persist()
        return true
    }

    /** 从搜索结果移除一行。 */
    fun removeResult(index: Int) {
        val cur = results.value
        if (index !in cur.indices) return
        results.value = cur.filterIndexed { i, _ -> i != index }
        persist()
    }

    /** v1.4.26：批量从搜索结果移除。 */
    fun removeResults(songs: List<Song>) {
        if (songs.isEmpty()) return
        results.value = results.value.filterNot { r -> songs.any { it.sameAs(r) } }
        persist()
    }

    fun search() {
        val keyword = query.value.trim()
        if (keyword.isEmpty() || isLoading.value) return
        page = 1
        isLoading.value = true
        error.value = null
        hasMore.value = false
        viewModelScope.launch {
            runCatching { doSearch(keyword, page) }
                .onSuccess { songs ->
                    results.value = songs
                    hasMore.value = songs.size >= PAGE_SIZE
                    persist()
                }
                .onFailure { e ->
                    results.value = emptyList()
                    error.value = e.message ?: "搜索失败，请稍后重试"
                    persist()
                }
            isLoading.value = false
        }
    }

    fun loadMore() {
        val keyword = query.value.trim()
        if (keyword.isEmpty() || isLoading.value || isLoadingMore.value || !hasMore.value) return
        isLoadingMore.value = true
        viewModelScope.launch {
            runCatching { doSearch(keyword, page + 1) }
                .onSuccess { songs ->
                    if (songs.isEmpty()) {
                        hasMore.value = false
                    } else {
                        page += 1
                        val current = results.value
                        val merged = current + songs.filter { n ->
                            current.none { it.sameAs(n) }
                        }
                        results.value = merged
                        hasMore.value = songs.size >= PAGE_SIZE
                    }
                    persist()
                }
                .onFailure { e ->
                    error.value = e.message ?: "加载失败，请稍后重试"
                }
            isLoadingMore.value = false
        }
    }

    /**
     * v1.5.1 r29：搜索分发——平台直连模式走 PlatformSearchApi
     * （聚合=并发全平台+相似度排序；单平台=直连该平台），否则走 GD API。
     * v1.5.1 r40：GD 模式失效兜底——GD API 2026-09 服务端变更后只剩
     * netease/joox/bilibili 三个稳定源（kuwo 等全部 400），且 "all"
     * （离开平台模式的残留）GD API 也不支持——搜索失败时回落平台直连。
     */
    private suspend fun doSearch(keyword: String, page: Int): List<Song> {
        if (platformMode && source.value == PlatformSearchApi.SRC_ALL) {
            // 聚合搜索：单平台失败返回空，全失败才报错
            val songs = PlatformSearchApi.search(PlatformSearchApi.SRC_ALL, keyword, page)
            if (songs.isEmpty()) throw java.io.IOException("所有平台搜索均失败，请检查网络")
            return songs
        }
        if (platformMode && source.value in PlatformSearchApi.sourceNames.keys) {
            return PlatformSearchApi.search(source.value, keyword, page)
        }
        return try {
            MusicApi.search(source.value, keyword, page)
        } catch (e: Exception) {
            // 失效源（kuwo/tencent/kugou/migu）或 "all" 残留 → 平台直连兜底
            val lxSrc = PlatformSearchApi.toPlatform(source.value)
                ?: if (source.value == PlatformSearchApi.SRC_ALL) PlatformSearchApi.SRC_ALL
                else null
                ?: throw e
            PlatformSearchApi.search(lxSrc, keyword, page)
        }
    }
}

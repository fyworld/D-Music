package com.solara.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solara.music.data.MusicApi
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
            runCatching { MusicApi.search(source.value, keyword, page) }
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
            runCatching { MusicApi.search(source.value, keyword, page + 1) }
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
}

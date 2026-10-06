@file:OptIn(ExperimentalMaterial3Api::class)

package com.solara.music.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.solara.music.data.PlatformSearchApi
import com.solara.music.data.Sources
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.AddSongsToPlaylistSheet
import com.solara.music.ui.components.BatchActionBar
import com.solara.music.ui.components.BatchDownloadDialog
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.SaveAsPlaylistDialog
import com.solara.music.ui.components.SelectionTopBar
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.dragReorderItem
import com.solara.music.ui.components.dragReorderSource
import com.solara.music.ui.components.rememberDragReorderState
import com.solara.music.ui.components.rememberMultiSelectState
import com.solara.music.ui.components.saveSongsToNewPlaylist

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onAddToPlaylist: (com.solara.music.data.Song) -> Unit = {},
    onDownload: (com.solara.music.data.Song) -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    val query by vm.query.collectAsState()
    val source by vm.source.collectAsState()
    val results by vm.results.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val isLoadingMore by vm.isLoadingMore.collectAsState()
    val error by vm.error.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val favorites by Store.favorites.collectAsState()
    // v1.4.59：当前播放歌曲——列表行显示播放中标记
    val currentSong by PlayerManager.currentSong.collectAsState()
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // v1.4.13 #63：批量存歌单 / 批量下载
    var showSavePlaylist by remember { mutableStateOf(false) }
    var showBatchDownload by remember { mutableStateOf(false) }
    // v1.4.26：多选批量（收藏 / 加入歌单 / 下载 / 移除）
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }

    val dragState = rememberDragReorderState(
        listState,
        onMove = { from, to -> vm.moveResult(from, to) },
        // r63d：排除尾部「加载更多」/结尾 Spacer（拖到底越界回弹）
        dragRange = { 0 until results.size }
    )

    // v1.4.26：多选模式下按系统返回键 = 退出多选（而不是退出 App）
    androidx.activity.compose.BackHandler(enabled = select.active) {
        select.exit()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        // v1.4.17：音源选择收进搜索框左侧——点源名弹下拉菜单切换，页面更干净
        // v1.5.1 r29：自定义源优先+沙箱就绪时切换为「聚合+脚本支持的源」（平台直连搜索）；
        // 否则保持源 A-D（GD音乐台聚合）
        var sourceMenuOpen by remember { mutableStateOf(false) }
        val platformMode = vm.platformMode
        val platformSources = vm.platformSources
        val effectivePlatformMode = platformMode && platformSources.isNotEmpty()
        val sourceOptions: List<Pair<String, String>> = if (effectivePlatformMode) {
            platformSources
        } else {
            Sources.all.map { it.id to it.label }
        }
        // 平台模式下当前源不在列表（如上次是源C/D）→ 自动切到聚合
        val currentSourceLabel = sourceOptions.firstOrNull { it.first == source }?.second
            ?: if (effectivePlatformMode) "聚合" else "源 A"
        LaunchedEffect(effectivePlatformMode) {
            if (effectivePlatformMode && source !in sourceOptions.map { it.first }) {
                vm.changeSource(PlatformSearchApi.SRC_ALL)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索歌曲、歌手、专辑") },
            leadingIcon = {
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { sourceMenuOpen = true }
                            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentSourceLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "选择音源",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuOpen,
                        onDismissRequest = { sourceMenuOpen = false }
                    ) {
                        sourceOptions.forEach { (srcId, srcLabel) ->
                            DropdownMenuItem(
                                text = { Text(srcLabel) },
                                trailingIcon = {
                                    if (srcId == source) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    sourceMenuOpen = false
                                    vm.changeSource(srcId)
                                }
                            )
                        }
                    }
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() })
        )

        Spacer(Modifier.height(8.dp))
        when {
            isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())

            error != null && results.isEmpty() -> Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { vm.search() }) { Text("重试") }
            }

            results.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("输入关键词，跨站搜索海量曲库")
            }

            else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                // r60-2：栏目行固定在列表外（不随内容滚动）；
                // r60-1：栏目提出后 LazyColumn 纯歌曲行——dragReorder 的
                // 歌曲下标 i 即绝对下标，clampToViewport 不再错位一格
                // （此前条目可拖出列表底界、被播放控制栏遮挡）
                if (select.active) {
                    // v1.4.26：多选模式顶栏（收藏 / 加入歌单 / 下载 / 移除）
                    SelectionTopBar(
                        selectedCount = select.selected.size,
                        totalCount = results.size,
                        onExit = { select.exit() },
                        onToggleSelectAll = {
                            if (select.selected.size >= results.size) {
                                select.clearSelection()
                            } else {
                                select.selectAll(results)
                            }
                        },
                        onFavorite = {
                            val added = Store.addFavorites(select.selectedSongs(results))
                            onShowMessage("已收藏 $added 首（重复自动跳过）")
                        },
                        onAddToPlaylist = {
                            if (select.selected.isNotEmpty()) showBatchPlaylist = true
                        },
                        onDelete = {
                            // 搜索结果"删除"= 从结果列表移除（不动收藏）
                            val n = select.selected.size
                            vm.removeResults(select.selectedSongs(results))
                            select.clearSelection()
                            onShowMessage("已从结果移除 $n 首")
                        }
                    )
                } else {
                    // v1.4.17：结果数标题行 + 更多按钮（存为歌单/下载全部收进菜单）
                    BatchActionBar(
                        title = "搜索结果（${results.size} 首）",
                        onSaveToPlaylist = { showSavePlaylist = true },
                        onDownloadAll = { showBatchDownload = true },
                        onMultiSelect = { select.enter() }
                    )
                }
                LazyColumn(
                    state = listState,
                    // r64：手势检测移到容器层（条目回收不再杀死拖动）
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .dragReorderSource(dragState, listState, enabled = !select.active) {
                            0 until results.size
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // r60-3：key = 稳定标识（r52 教训——无 key 时组件按位置
                    // 复用，换位后手势组件随滚动滚出视口被回收 → 手势静默
                    // 死亡 → 松手 onEnd 永不触发 → 自动滚动永不停止）
                    items(
                        count = results.size,
                        key = { i -> results[i].source + ":" + results[i].id }
                    ) { i ->
                        val song = results[i]
                        SongRow(
                            song = song,
                            isFavorite = favorites.any { it.sameAs(song) },
                            isCurrent = currentSong?.sameAs(song) == true,
                            onClick = { PlayerManager.setQueue(results, i) },
                            onToggleFavorite = { Store.toggleFavorite(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            onRemove = { vm.removeResult(i) },
                            onDownload = { onDownload(song) },
                            selectionMode = select.active,
                            selected = select.isSelected(song),
                            onSelect = { select.toggle(song) },
                            modifier = if (select.active) Modifier else Modifier.dragReorderItem(dragState, i)
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    TextButton(onClick = { vm.loadMore() }) { Text("加载更多") }
                                }
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = "— 没有更多了 —",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // v1.4.13 #63：批量操作弹窗
    if (showSavePlaylist) {
        SaveAsPlaylistDialog(
            songCount = results.size,
            defaultName = query.ifBlank { "搜索歌单" },
            onDismiss = { showSavePlaylist = false },
            onConfirm = { name ->
                val saved = saveSongsToNewPlaylist(results, name)
                if (saved.isNotBlank()) onShowMessage("已存入歌单「$saved」")
                showSavePlaylist = false
            }
        )
    }
    if (showBatchDownload) {
        BatchDownloadDialog(
            songCount = results.size,
            onDismiss = { showBatchDownload = false },
            onConfirm = { quality ->
                results.forEach { com.solara.music.data.DownloadManager.enqueue(context, it, quality) }
                onShowMessage("已开始下载 ${results.size} 首")
                showBatchDownload = false
            }
        )
    }

    // v1.4.26：多选批量加入歌单
    if (showBatchPlaylist) {
        AddSongsToPlaylistSheet(
            songs = select.selectedSongs(results),
            onDismiss = { showBatchPlaylist = false },
            onDone = { name ->
                onShowMessage("已加入歌单「$name」")
                select.exit()
            }
        )
    }
}

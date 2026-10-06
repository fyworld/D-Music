package com.solara.music.ui.recent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.AddSongsToPlaylistSheet
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.SelectionTopBar
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.DragReorderOverlay
import com.solara.music.ui.components.dragReorderItem
import com.solara.music.ui.components.dragReorderSource
import com.solara.music.ui.components.rememberDragReorderState
import com.solara.music.ui.components.rememberMultiSelectState

/**
 * 最近播放：试听过的歌曲自动留痕（去重，最新在前）。
 * - 点击播放（以最近列表为队列）
 * - 长按拖动调整顺序
 * - 更多菜单：加入歌单 / 下载 / 移除；右上角一键清空
 * - v1.4.26：多选批量（收藏 / 加入歌单 / 移除记录）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    onAddToPlaylist: (Song) -> Unit = {},
    onDownload: (Song) -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    val recent by Store.recent.collectAsState()
    val favorites by Store.favorites.collectAsState()
    // v1.4.59：当前播放歌曲——列表行显示播放中标记
    val currentSong by PlayerManager.currentSong.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // v1.4.26：多选批量
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val dragState = rememberDragReorderState(
        listState,
        onMove = { from, to -> Store.moveRecent(from, to) },
        // r63d：排除尾部 Spacer——拖到底 targetIndex 落在 Spacer 下标
        // 会让 onMove 越界失败 → 视觉回弹
        dragRange = { 0 until recent.size }
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
        if (select.active) {
            // v1.4.26：多选模式顶栏（收藏 / 加入歌单 / 移除记录）
            SelectionTopBar(
                selectedCount = select.selected.size,
                totalCount = recent.size,
                onExit = { select.exit() },
                onToggleSelectAll = {
                    if (select.selected.size >= recent.size) select.clearSelection()
                    else select.selectAll(recent)
                },
                onFavorite = {
                    val added = Store.addFavorites(select.selectedSongs(recent))
                    onShowMessage("已收藏 $added 首（重复自动跳过）")
                },
                onAddToPlaylist = {
                    if (select.selected.isNotEmpty()) showBatchPlaylist = true
                },
                onDelete = {
                    val n = select.selected.size
                    Store.removeRecentBatch(select.selectedSongs(recent))
                    select.clearSelection()
                    onShowMessage("已移除 $n 条记录")
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近播放",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                // v1.4.26：多选/清空/播放全部收进更多菜单
                // end padding 12dp 与 SongRow 卡片内更多按钮右对齐
                Box(modifier = Modifier.padding(end = 12.dp)) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("多选") },
                            leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                            enabled = recent.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                select.enter()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("播放全部") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            enabled = recent.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                PlayerManager.setQueue(recent, 0)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清空最近播放") },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                            enabled = recent.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                showClearConfirm = true
                            }
                        )
                    }
                }
            }
        }

        if (recent.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("还没有播放记录\n试听过的歌曲会出现在这里")
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    // r64：手势检测移到容器层——条目滚出视口被回收不再
                    // 杀死进行中的拖动（「拖到边缘自动滚动 3-4 行后停止」
                    // 根因修复）
                    modifier = Modifier
                        .fillMaxWidth()
                        .dragReorderSource(dragState, listState, enabled = !select.active) {
                            0 until recent.size
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // r60-3：key = 稳定标识（r52 教训——无 key 时组件按位置
                    // 复用，换位后手势组件随滚动滚出视口被回收 → 手势静默
                    // 死亡 → 松手 onEnd 永不触发 → 自动滚动永不停止）
                    items(
                        count = recent.size,
                        key = { i -> recent[i].source + ":" + recent[i].id }
                    ) { i ->
                        val song = recent[i]
                        SongRow(
                            song = song,
                            isFavorite = favorites.any { it.sameAs(song) },
                            isCurrent = currentSong?.sameAs(song) == true,
                            onClick = { PlayerManager.setQueue(recent, i) },
                            onToggleFavorite = { Store.toggleFavorite(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            onRemove = { Store.removeRecent(song) },
                            onDownload = { onDownload(song) },
                            selectionMode = select.active,
                            selected = select.isSelected(song),
                            onSelect = { select.toggle(song) },
                            modifier = if (select.active) Modifier else Modifier.dragReorderItem(dragState, i)
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                // r65：拖动条目快照 overlay（条目回收不再导致消失）
                DragReorderOverlay(dragState)
            }
        }
    }

    // v1.4.26：多选批量加入歌单
    if (showBatchPlaylist) {
        AddSongsToPlaylistSheet(
            songs = select.selectedSongs(recent),
            onDismiss = { showBatchPlaylist = false },
            onDone = { name ->
                onShowMessage("已加入歌单「$name」")
                select.exit()
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空最近播放") },
            text = { Text("确定清空全部 ${recent.size} 条播放记录？") },
            confirmButton = {
                TextButton(onClick = {
                    Store.clearRecent()
                    showClearConfirm = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

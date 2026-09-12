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
import com.solara.music.ui.components.dragReorder
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
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // v1.4.26：多选批量
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val dragState = rememberDragReorderState(listState) { from, to ->
        Store.moveRecent(from, to)
    }

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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recent.size) { i ->
                    val song = recent[i]
                    SongRow(
                        song = song,
                        isFavorite = favorites.any { it.sameAs(song) },
                        onClick = { PlayerManager.setQueue(recent, i) },
                        onToggleFavorite = { Store.toggleFavorite(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onRemove = { Store.removeRecent(song) },
                        onDownload = { onDownload(song) },
                        selectionMode = select.active,
                        selected = select.isSelected(song),
                        onSelect = { select.toggle(song) },
                        modifier = if (select.active) Modifier else Modifier.dragReorder(dragState, i)
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
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

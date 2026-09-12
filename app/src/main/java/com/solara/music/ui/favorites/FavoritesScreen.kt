@file:OptIn(ExperimentalMaterial3Api::class)

package com.solara.music.ui.favorites

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
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.AddSongsToPlaylistSheet
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.SelectionTopBar
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.dragReorder
import com.solara.music.ui.components.rememberDragReorderState
import com.solara.music.ui.components.rememberMultiSelectState

@Composable
fun FavoritesScreen(
    onAddToPlaylist: (com.solara.music.data.Song) -> Unit = {},
    onDownload: (com.solara.music.data.Song) -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    val favorites by Store.favorites.collectAsState()
    val listState = rememberLazyListState()
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val dragState = rememberDragReorderState(listState) { from, to ->
        Store.moveFavorite(from, to)
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
        Spacer(Modifier.height(8.dp))
        if (select.active) {
            // v1.4.26：多选模式顶栏（退出/全选/加歌单/删除）
            SelectionTopBar(
                selectedCount = select.selected.size,
                totalCount = favorites.size,
                onExit = { select.exit() },
                onToggleSelectAll = {
                    if (select.selected.size >= favorites.size) select.clearSelection()
                    else select.selectAll(favorites)
                },
                onAddToPlaylist = {
                    if (select.selected.isNotEmpty()) showBatchPlaylist = true
                },
                onDelete = {
                    if (select.selected.isNotEmpty()) showDeleteConfirm = true
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "我的收藏",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                // v1.4.26：多选/播放全部/清空收藏收进更多菜单
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
                            enabled = favorites.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                select.enter()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("播放全部") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            enabled = favorites.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                PlayerManager.setQueue(favorites, 0)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清空收藏") },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                            enabled = favorites.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                showClearConfirm = true
                            }
                        )
                    }
                }
            }
        }

        if (favorites.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("还没有收藏的歌曲\n在搜索结果中点击 ♥ 收藏喜欢的音乐")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites.size) { i ->
                    val song = favorites[i]
                    SongRow(
                        song = song,
                        isFavorite = true,
                        onClick = { PlayerManager.setQueue(favorites, i) },
                        onToggleFavorite = { Store.toggleFavorite(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onRemove = { Store.removeFavorite(song) },
                        onDownload = { onDownload(song) },
                        selectionMode = select.active,
                        selected = select.isSelected(song),
                        onSelect = { select.toggle(song) },
                        modifier = if (select.active) Modifier else Modifier.dragReorder(dragState, i)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // v1.4.26：批量加入歌单
    if (showBatchPlaylist) {
        AddSongsToPlaylistSheet(
            songs = select.selectedSongs(favorites),
            onDismiss = { showBatchPlaylist = false },
            onDone = { name ->
                onShowMessage("已加入歌单「$name」")
                select.exit()
            }
        )
    }

    // v1.4.26：批量取消收藏确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("取消收藏") },
            text = { Text("确定取消收藏所选的 ${select.selected.size} 首歌曲？") },
            confirmButton = {
                TextButton(onClick = {
                    val removed = Store.removeFavorites(select.selectedSongs(favorites))
                    showDeleteConfirm = false
                    select.exit()
                    onShowMessage("已取消收藏 $removed 首")
                }) { Text("取消收藏", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // v1.4.26：清空收藏确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空收藏") },
            text = { Text("确定清空全部 ${favorites.size} 首收藏歌曲？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    Store.clearFavorites()
                    showClearConfirm = false
                    onShowMessage("收藏已清空")
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

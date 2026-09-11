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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
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
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.dragReorder
import com.solara.music.ui.components.rememberDragReorderState

/**
 * 最近播放：试听过的歌曲自动留痕（去重，最新在前）。
 * - 点击播放（以最近列表为队列）
 * - 长按拖动调整顺序
 * - 更多菜单：加入歌单 / 下载 / 移除；右上角一键清空
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    onAddToPlaylist: (Song) -> Unit = {},
    onDownload: (Song) -> Unit = {}
) {
    val recent by Store.recent.collectAsState()
    val favorites by Store.favorites.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val dragState = rememberDragReorderState(listState) { from, to ->
        Store.moveRecent(from, to)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
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
            if (recent.isNotEmpty()) {
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "清空最近播放",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(
                onClick = { PlayerManager.setQueue(recent, 0) },
                enabled = recent.isNotEmpty()
            ) { Text("播放全部") }
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
                        modifier = Modifier.dragReorder(dragState, i)
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
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

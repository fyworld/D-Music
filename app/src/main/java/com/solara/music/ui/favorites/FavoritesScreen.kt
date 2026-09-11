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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.dragReorder
import com.solara.music.ui.components.rememberDragReorderState

@Composable
fun FavoritesScreen(
    onAddToPlaylist: (com.solara.music.data.Song) -> Unit = {},
    onDownload: (com.solara.music.data.Song) -> Unit = {}
) {
    val favorites by Store.favorites.collectAsState()
    val listState = rememberLazyListState()

    val dragState = rememberDragReorderState(listState) { from, to ->
        Store.moveFavorite(from, to)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
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
            TextButton(
                onClick = { PlayerManager.setQueue(favorites, 0) },
                enabled = favorites.isNotEmpty()
            ) { Text("播放全部") }
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
                        modifier = Modifier.dragReorder(dragState, i)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

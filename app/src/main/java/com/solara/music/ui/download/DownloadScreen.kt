package com.solara.music.ui.download

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solara.music.data.DownloadManager
import com.solara.music.data.DownloadStatus
import com.solara.music.data.DownloadTask
import com.solara.music.data.Qualities
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.ui.components.CoverImage

/**
 * 品质选择弹窗：挑 128K/192K/320K/FLAC 后开始下载。
 */
@Composable
fun QualityPickerDialog(
    song: Song,
    onDismiss: () -> Unit,
    onStart: (Song, String) -> Unit
) {
    var picked by remember { mutableStateOf(Qualities.all.first { it.value == "320" }.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载品质") },
        text = {
            Column {
                Text(
                    text = "${song.displayName} - ${song.artistName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Qualities.all.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = picked == q.value,
                            onClick = { picked = q.value }
                        )
                        Column {
                            Text("${q.label}（${q.description}）")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "文件保存到 Music/D_Music 目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(song, picked); onDismiss() }) { Text("下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 下载管理页：任务列表 + 进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit = {}
) {
    val tasks by DownloadManager.tasks.collectAsState()
    val favorites by Store.favorites.collectAsState()

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
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
            Text(
                text = "下载管理",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (tasks.any { it.status != DownloadStatus.DOWNLOADING }) {
                TextButton(onClick = { DownloadManager.clearFinished() }) { Text("清除已完成") }
            }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "暂无下载任务\n在歌曲上点 ⬇ 选择品质开始下载",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks.size) { i ->
                    DownloadRow(
                        task = tasks[i],
                        isFavorite = favorites.any { it.sameAs(tasks[i].song) },
                        onAddToPlaylist = { onAddToPlaylist(tasks[i].song) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTask,
    isFavorite: Boolean,
    onAddToPlaylist: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            CoverImage(song = task.song, size = 48.dp, corner = 10.dp)
            when (task.status) {
                DownloadStatus.DONE -> Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )

                DownloadStatus.FAILED -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )

                else -> {}
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = "${task.song.artistName} - ${task.song.displayName}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (task.status) {
                    DownloadStatus.PENDING -> "等待中"
                    DownloadStatus.DOWNLOADING -> "下载中 ${(task.progress * 100).toInt()}%"
                    DownloadStatus.DONE -> "已完成 · ${task.qualityLabel()}"
                    DownloadStatus.FAILED -> "失败：${task.error ?: "未知错误"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (task.status == DownloadStatus.DOWNLOADING) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (task.status == DownloadStatus.DOWNLOADING) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                progress = { task.progress },
                modifier = Modifier.size(24.dp)
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("加入歌单") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = { menuOpen = false; onAddToPlaylist() }
                )
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                    leadingIcon = {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            null
                        )
                    },
                    onClick = {
                        menuOpen = false
                        Store.toggleFavorite(task.song)
                    }
                )
                DropdownMenuItem(
                    text = {
                        // v1.4.23：进行中任务显示「取消下载」（现在能真正中断），已结束显示「移除」
                        Text(
                            if (task.status == DownloadStatus.DOWNLOADING ||
                                task.status == DownloadStatus.PENDING
                            ) "取消下载" else "移除"
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = {
                        menuOpen = false
                        DownloadManager.removeTask(task.id)
                    }
                )
            }
        }
    }
}

private fun DownloadTask.qualityLabel(): String =
    Qualities.all.firstOrNull { it.value == quality }?.label ?: quality

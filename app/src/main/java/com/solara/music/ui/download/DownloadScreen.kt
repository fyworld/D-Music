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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Checklist
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
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.AddSongsToPlaylistSheet
import com.solara.music.ui.components.CoverImage
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.LocalFileOpsHandler
import com.solara.music.ui.components.SelectionTopBar
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.rememberMultiSelectState

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
 * 下载管理页（v1.4.58 重构）：
 * - 进行中/失败/等待：任务行（进度条 + 取消/移除）
 * - 已完成：SongRow 完整操作（点击播放、加入歌单、收藏、下载歌词、
 *   重命名、删除文件、多选批量、播放全部）——与本地歌曲页一致
 * - 「清除已完成」改为「清除失败」：已完成任务保留在页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onLyricUpdated: () -> Unit = {}
) {
    val tasks by DownloadManager.tasks.collectAsState()
    val favorites by Store.favorites.collectAsState()
    // v1.4.59：当前播放歌曲——列表行显示播放中标记
    val currentSong by PlayerManager.currentSong.collectAsState()
    // v1.4.58：已完成歌曲数据源 = Store.downloads（持久化、跨重启保留；
    // 删除/重命名文件后记录同步消失）。过滤旧版扫描导入的 local: 歌——
    // 那些归本地歌曲页（文件夹浏览）管
    val downloads by Store.downloads.collectAsState()
    val doneSongs = downloads.filterNot { it.source == "local" }
    val activeTasks = tasks

    // 多选批量（对已完成歌曲）
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 文件操作（删除/重命名）走公共授权链路组件
    var deleteTarget by remember { mutableStateOf<Song?>(null) }
    var renameTarget by remember { mutableStateOf<Song?>(null) }
    var opsMessage by remember { mutableStateOf<String?>(null) }
    // 单曲下载歌词
    var lyricTarget by remember { mutableStateOf<Song?>(null) }

    // 多选模式下返回键 = 退出多选
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
            SelectionTopBar(
                selectedCount = select.selected.size,
                totalCount = doneSongs.size,
                onExit = { select.exit() },
                onToggleSelectAll = {
                    if (select.selected.size >= doneSongs.size) select.clearSelection()
                    else select.selectAll(doneSongs)
                },
                onFavorite = {
                    val added = Store.addFavorites(select.selectedSongs(doneSongs))
                    onShowMessage("已收藏 $added 首（重复自动跳过）")
                },
                onAddToPlaylist = {
                    if (select.selected.isNotEmpty()) showBatchPlaylist = true
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "下载管理",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (doneSongs.isNotEmpty()) {
                            Text(
                                text = "（${doneSongs.size} 首）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    if (activeTasks.isNotEmpty()) {
                        Text(
                            text = "进行中 ${activeTasks.count { it.status == DownloadStatus.DOWNLOADING }} / ${activeTasks.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box(modifier = Modifier.padding(end = 12.dp)) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("多选") },
                            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                            enabled = doneSongs.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                select.enter()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("播放全部") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            enabled = doneSongs.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                PlayerManager.setQueue(doneSongs, 0)
                            }
                        )
                        // v1.4.58：只清失败任务（已完成保留）
                        DropdownMenuItem(
                            text = { Text("清除失败任务") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            enabled = tasks.any { it.status == DownloadStatus.FAILED },
                            onClick = {
                                menuOpen = false
                                DownloadManager.clearFinished()
                            }
                        )
                    }
                }
            }
        }

        if (tasks.isEmpty() && doneSongs.isEmpty()) {
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
                // 进行中/等待/失败任务（在前，最新下载优先可见）
                items(activeTasks.size) { i ->
                    val task = activeTasks[i]
                    DownloadRow(
                        task = task,
                        isFavorite = favorites.any { it.sameAs(task.song) },
                        onAddToPlaylist = { onAddToPlaylist(task.song) }
                    )
                }
                // 已完成歌曲：SongRow 完整操作（与本地歌曲页一致）
                items(doneSongs.size) { i ->
                    val song = doneSongs[i]
                    SongRow(
                        song = song,
                        isFavorite = favorites.any { it.sameAs(song) },
                        isCurrent = currentSong?.sameAs(song) == true,
                        onClick = { PlayerManager.setQueue(doneSongs, i) },
                        onToggleFavorite = { Store.toggleFavorite(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onRemove = { deleteTarget = song },
                        onRename = { renameTarget = song },
                        onDownloadLyric = { lyricTarget = song },
                        selectionMode = select.active,
                        selected = select.isSelected(song),
                        onSelect = { select.toggle(song) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    // 文件操作公共链路（删除/重命名 + Scoped Storage 授权）
    LocalFileOpsHandler(
        deleteTarget = deleteTarget,
        onDeleteTargetChange = { deleteTarget = it },
        renameTarget = renameTarget,
        onRenameTargetChange = { renameTarget = it },
        onDone = { message ->
            opsMessage = message
        }
    )

    opsMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { opsMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { opsMessage = null }) { Text("知道了") }
            }
        )
    }

    // 单曲「下载歌词」对话框
    lyricTarget?.let { target ->
        com.solara.music.ui.components.LyricDownloadDialog(
            song = target,
            onDismiss = { lyricTarget = null },
            onDownloaded = { embedded ->
                opsMessage = if (embedded) "歌词已下载并嵌入文件，其他播放器也能显示"
                else "歌词已下载（嵌入文件失败，已存缓存）"
                onLyricUpdated()
            }
        )
    }

    // 多选批量加入歌单
    if (showBatchPlaylist) {
        AddSongsToPlaylistSheet(
            songs = select.selectedSongs(doneSongs),
            onDismiss = { showBatchPlaylist = false },
            onDone = { name ->
                onShowMessage("已加入歌单「$name」")
                select.exit()
            }
        )
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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.solara.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import com.solara.music.data.Qualities
import com.solara.music.data.Song
import com.solara.music.data.Store

/**
 * v1.4.26：列表多选状态（批量收藏 / 加入歌单 / 删除）。
 * 以 "source:id" 为键，避免对象相等性问题；选中集合与激活态分离，
 * 退出多选时一并清空。
 */
class MultiSelectState {
    var active by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    fun keyOf(song: Song): String = "${song.source}:${song.id}"

    fun enter() {
        active = true
        selected = emptySet()
    }

    fun exit() {
        active = false
        selected = emptySet()
    }

    fun toggle(song: Song) {
        val k = keyOf(song)
        selected = if (k in selected) selected - k else selected + k
    }

    fun isSelected(song: Song): Boolean = keyOf(song) in selected

    fun selectAll(songs: List<Song>) {
        selected = songs.map { keyOf(it) }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    /** 按列表顺序返回选中的歌曲。 */
    fun selectedSongs(songs: List<Song>): List<Song> = songs.filter { isSelected(it) }
}

@Composable
fun rememberMultiSelectState(): MultiSelectState = remember { MultiSelectState() }

/**
 * v1.4.26：多选模式顶部操作条——退出 / 全选切换 / 收藏 / 加入歌单 / 删除。
 * 动作回调传 null 即隐藏对应按钮；宽度按最坏情况（5 个图标位）设计，
 * 标题 weight(1f) 自动收缩。
 */
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onExit: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val allSelected = totalCount > 0 && selectedCount >= totalCount
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Filled.Close, contentDescription = "退出多选")
        }
        Text(
            text = "已选 $selectedCount 首",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onToggleSelectAll) {
            Icon(
                imageVector = if (allSelected) Icons.Filled.DoneAll else Icons.Filled.SelectAll,
                contentDescription = if (allSelected) "取消全选" else "全选",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onFavorite != null) {
            IconButton(onClick = onFavorite) {
                Icon(
                    Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏所选",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "加入歌单",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除所选",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * v1.4.13 #63：列表批量操作——「存为歌单」「下载全部」收进更多菜单（v1.4.17 改版）。
 * 标题行 = 标题文字 + 右侧更多按钮；**end padding 12dp 与 SongRow 卡片内边距对齐**
 * （SongRow 的更多按钮在卡片 12dp 水平内边距内，不加会偏右约一个字符宽）。
 * 探索/搜索结果列表头部共用。空列表时隐藏（由调用方控制）。
 * v1.4.26：菜单追加「多选」入口（onMultiSelect 传 null 则不显示）。
 */
@Composable
fun BatchActionBar(
    title: String,
    onSaveToPlaylist: () -> Unit,
    onDownloadAll: () -> Unit,
    onMultiSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onMultiSelect != null) {
                    DropdownMenuItem(
                        text = { Text("多选") },
                        leadingIcon = {
                            Icon(Icons.Filled.Checklist, null)
                        },
                        onClick = { menuOpen = false; onMultiSelect() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("存为歌单") },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                    },
                    onClick = { menuOpen = false; onSaveToPlaylist() }
                )
                DropdownMenuItem(
                    text = { Text("下载全部") },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                    onClick = { menuOpen = false; onDownloadAll() }
                )
            }
        }
    }
}

/**
 * 「存为歌单」命名弹窗：输入歌单名，把整份歌曲列表存为新歌单（自动去重）。
 */
@Composable
fun SaveAsPlaylistDialog(
    songCount: Int,
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存为歌单") },
        text = {
            Column {
                Text(
                    text = "将把当前列表的 $songCount 首歌曲保存为新歌单（重复歌曲自动去重）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("歌单名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 「下载全部」品质选择弹窗：选一次品质，批量入队整份列表。
 * 下载管理页可看进度（并发限流 3 路）。
 */
@Composable
fun BatchDownloadDialog(
    songCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var picked by remember { mutableStateOf("320") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载全部") },
        text = {
            Column {
                Text(
                    text = "将下载当前列表的 $songCount 首歌曲，已下载过的自动跳过。可在「下载管理」查看进度。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Qualities.all.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = picked == q.value,
                            onClick = { picked = q.value }
                        )
                        Text("${q.label}（${q.description}）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 批量存歌单的公共实现：创建歌单 + 逐首添加（Store.addToPlaylist 自带去重）。
 * 返回创建的歌单名。
 */
fun saveSongsToNewPlaylist(songs: List<Song>, name: String): String {
    if (songs.isEmpty()) return ""
    val playlist = Store.createPlaylist(name)
    songs.forEach { Store.addToPlaylist(playlist.id, it) }
    return playlist.name
}

/**
 * v1.4.26：批量加入歌单底部弹窗——选择目标歌单或新建，一次加入多首
 * （Store.addToPlaylist 自带去重）。与单首版 AddToPlaylistSheet 视觉一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsToPlaylistSheet(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    val playlists by Store.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            Text(
                text = "加入歌单",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
            )
            Text(
                text = "已选 ${songs.size} 首歌曲",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
            )
            LazyColumn {
                item {
                    BatchSheetRow(
                        icon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "新建歌单",
                        subtitle = "创建一个新歌单并添加这些歌曲",
                        onClick = { showCreateDialog = true }
                    )
                }
                items(playlists.size) { i ->
                    val p = playlists[i]
                    val contained = songs.count { p.contains(it) }
                    BatchSheetRow(
                        icon = {
                            Icon(
                                Icons.Filled.LibraryMusic, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        },
                        title = p.name,
                        subtitle = "${p.songs.size} 首" +
                            if (contained > 0) " · $contained 首已在歌单" else "",
                        onClick = {
                            songs.forEach { Store.addToPlaylist(p.id, it) }
                            onDone(p.name)
                            onDismiss()
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // 新建歌单：命名后创建并批量加入
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val saved = saveSongsToNewPlaylist(songs, name)
                        if (saved.isNotBlank()) {
                            showCreateDialog = false
                            onDone(saved)
                            onDismiss()
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BatchSheetRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) { icon() }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

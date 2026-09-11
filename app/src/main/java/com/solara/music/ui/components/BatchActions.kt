@file:OptIn(ExperimentalMaterial3Api::class)

package com.solara.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solara.music.data.Qualities
import com.solara.music.data.Song
import com.solara.music.data.Store

/**
 * v1.4.13 #63：列表批量操作栏——「存为歌单」「下载全部」两个入口，
 * 探索/搜索结果列表头部共用。空列表时隐藏（由调用方控制）。
 */
@Composable
fun BatchActionBar(
    songCount: Int,
    onSaveToPlaylist: () -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onSaveToPlaylist, modifier = Modifier.weight(1f)) {
            Icon(
                Icons.Filled.LibraryAdd,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(4.dp))
            Text("存为歌单")
        }
        TextButton(onClick = onDownloadAll, modifier = Modifier.weight(1f)) {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(4.dp))
            Text("下载全部")
        }
    }
    Text(
        text = "$songCount 首",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 12.dp)
    )
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

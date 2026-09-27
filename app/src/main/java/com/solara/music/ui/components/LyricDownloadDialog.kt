package com.solara.music.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solara.music.data.LyricRepository
import com.solara.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.4.39：「下载歌词」对话框——为本地歌曲在线搜索并下载歌词。
 *
 * - 搜索词预填「歌手 歌名」，可编辑（删掉歌手只按歌名搜），
 *   支持歌名或歌名+歌手任意组合
 * - 打开自动搜一次；改词点「搜索」重搜
 * - 候选列表点选即下载：写磁盘缓存（App 内显示）+ 嵌入音频文件
 *   （MP3 USLT / FLAC 伴生 .lrc，其他播放器也能显示）
 * - [onDownloaded] 下载成功回调（调用方刷新播放页歌词显示）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricDownloadDialog(
    song: Song,
    onDismiss: () -> Unit,
    onDownloaded: (embedded: Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 预填搜索词：歌手 + 歌名（歌手未知时只用歌名）
    val defaultQuery = remember(song) {
        buildString {
            if (song.artistName.isNotBlank() && song.artistName != "未知歌手") {
                append(song.artistName).append(' ')
            }
            append(song.name)
        }.trim()
    }
    var query by remember(song) { mutableStateOf(defaultQuery) }
    var searching by remember(song) { mutableStateOf(false) }
    var results by remember(song) { mutableStateOf<List<Song>>(emptyList()) }
    var searchedOnce by remember(song) { mutableStateOf(false) }
    var downloadingIdx by remember(song) { mutableStateOf(-1) }
    var searchJob by remember(song) { mutableStateOf<Job?>(null) }

    fun doSearch() {
        searchJob?.cancel()
        searching = true
        searchJob = scope.launch {
            val list = withContext(Dispatchers.IO) {
                LyricRepository.searchLyricCandidates(song, query)
            }
            results = list
            searching = false
            searchedOnce = true
        }
    }

    // 打开自动搜一次（预填词）
    LaunchedEffect(song) { doSearch() }

    AlertDialog(
        onDismissRequest = { if (downloadingIdx < 0) onDismiss() },
        title = { Text("下载歌词") },
        text = {
            Column {
                Text(
                    text = "${song.displayName} - ${song.artistName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f),
                        placeholder = { Text("歌名 或 歌名+歌手") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = { doSearch() },
                        enabled = !searching && query.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(if (searching) "搜索中" else "搜索")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 320.dp)
                ) {
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.Center)
                        )
                    } else if (results.isEmpty()) {
                        Text(
                            text = if (searchedOnce) "未找到候选，试试只搜歌名" else "输入关键词搜索",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn {
                            items(results.size) { i ->
                                val r = results[i]
                                val downloading = downloadingIdx == i
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = downloadingIdx < 0) {
                                            downloadingIdx = i
                                        }
                                        .padding(
                                            horizontal = 8.dp,
                                            vertical = 10.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (downloading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Lyrics,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 10.dp)
                                    ) {
                                        Text(
                                            text = r.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = r.artistName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = { if (downloadingIdx < 0) onDismiss() },
                enabled = downloadingIdx < 0
            ) { Text("关闭") }
        }
    )

    // 下载选中候选：取词 → 缓存 + 嵌入文件 → 回调刷新
    LaunchedEffect(downloadingIdx) {
        if (downloadingIdx < 0) return@LaunchedEffect
        val candidate = results.getOrNull(downloadingIdx) ?: run {
            downloadingIdx = -1; return@LaunchedEffect
        }
        val pair = withContext(Dispatchers.IO) {
            LyricRepository.downloadLyric(context.applicationContext, song, candidate)
        }
        downloadingIdx = -1
        if (pair != null) {
            onDownloaded(pair.second)
            onDismiss()
        } else {
            // 失败：留在对话框让用户换候选重试
            android.widget.Toast.makeText(
                context.applicationContext,
                "该候选没有歌词，换一个试试",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

package com.solara.music.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.MusicApi
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.TagEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * v1.4.41：「封面编辑」对话框——为当前播放歌曲设置封面。
 *
 * - **在线搜索**：按「歌名 / 歌名+歌手」搜候选（词可编辑），点选候选
 *   下载封面应用——本地歌嵌入音频文件（MP3 APIC / FLAC PICTURE，
 *   其他播放器也能显示，失败降级存 URL 缓存）；在线歌存 URL 缓存
 * - **本地选图**：系统相册选图（裁成正方形）→ 本地歌嵌入文件 +
 *   存自定义封面；在线歌存自定义封面（App 专属目录持久保存）
 * - 应用后 bumpRevision 全局刷新封面显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverEditorDialog(
    song: Song,
    onDismiss: () -> Unit,
    onApplied: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
    var applyingIdx by remember(song) { mutableStateOf(-1) }
    var busy by remember(song) { mutableStateOf(false) }
    var searchJob by remember(song) { mutableStateOf<Job?>(null) }

    fun doSearch() {
        searchJob?.cancel()
        searching = true
        searchJob = scope.launch {
            val q = query.trim().ifBlank { defaultQuery }
            val src = Store.settings.value.source.ifBlank { "netease" }
            val list = withContext(Dispatchers.IO) {
                // v1.5.1 r40：改走 LyricRepository.searchCandidates 统一路由——
                // 修复聚合 tab（src="all"）与 GD 失效源（kuwo 等）时候选搜索
                // 空列表（「未找到候选」）双根因。候选是 lx 源码时 applyOnline
                // 已走平台直连取封面。
                com.solara.music.data.LyricRepository.searchCandidates(src, q, count = 20)
            }
            results = list
            searching = false
            searchedOnce = true
        }
    }

    // 打开自动搜一次
    LaunchedEffect(song) { doSearch() }

    /** 应用在线候选封面。 */
    fun applyOnline(candidate: Song, idx: Int) {
        if (busy) return
        applyingIdx = idx
        busy = true
        scope.launch {
            val ctx = context.applicationContext
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // v1.5.1 r37：候选是 lx 源码（kw/kg/tx/wy/mg）时走平台
                    // 直连取封面（GD API 不支持——r37-3 根因），失败回落
                    // 自定义源脚本；GD 源码候选仍走 GD API
                    val url = if (com.solara.music.data.PlatformMediaApi.isPlatformSource(candidate.source)) {
                        runCatching {
                            com.solara.music.data.PlatformMediaApi.fetchPicUrl(candidate)
                        }.getOrNull()
                            ?: runCatching {
                                com.solara.music.customsource.CustomSourceManager.getPicUrl(candidate)
                            }.getOrNull()
                    } else {
                        MusicApi.fetchPicUrl(candidate)
                    } ?: return@runCatching false
                    val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                    // v1.4.44：清自定义封面（相册图）——它优先级最高，
                    // 不清则永远挡住新应用的在线封面（"设过相册图后
                    // 在线候选换不动"的根因）。用户点在线候选 = 想用它，
                    // 相册图让位。
                    Store.clearCustomCover(song)
                    // v1.5.1 r47：在线下载存量歌（文件在 D_Music，id 非
                    // local:）定位本地文件后同样嵌入——此前只认本地导入
                    // 歌，封面只进 URL 缓存不落文件
                    var onlineFileName: String? = null
                    if (!LocalCoverExtractor.isLocalSong(song)) {
                        onlineFileName = com.solara.music.data.DownloadManager
                            .findLocalFileAbsPath(ctx, song)?.let { java.io.File(it).name }
                    }
                    val hasLocalFile = LocalCoverExtractor.isLocalSong(song) || onlineFileName != null
                    if (hasLocalFile) {
                        // 本地歌：嵌入文件（失败降级 URL 缓存，UI 仍能显示）
                        val embedded = TagEmbedder.embedCoverInto(ctx, song, bytes, onlineFileName)
                        if (!embedded) Store.saveLocalCoverUrl(song, url)
                        // v1.4.42：嵌入成功必须清旧 URL 缓存——URL 缓存优先级
                        // 高于内嵌封面，不清会挡住新封面（自动匹配过的歌都有旧记录）
                        else Store.clearLocalCoverUrl(song)
                    } else {
                        // 纯在线歌（未下载过）：存 URL 缓存（Coil 直接加载）
                        Store.saveOnlineCoverUrl(song, url)
                        // v1.4.42：同步内存缓存——CoverImage 的 url 状态先查
                        // CoverCache，不更新则命中旧值不刷新
                        CoverCache.put(
                            "${song.source}:${song.picId.ifBlank { song.id }}",
                            url
                        )
                    }
                    true
                }.getOrDefault(false)
            }
            applyingIdx = -1
            busy = false
            if (ok) {
                LocalCoverExtractor.bumpRevision()
                onApplied()
                onDismiss()
            } else {
                android.widget.Toast.makeText(
                    ctx, "封面获取失败，换个候选试试",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 应用本地选图（裁方形 JPEG）。 */
    fun applyLocalImage(bytes: ByteArray) {
        if (busy || bytes.isEmpty()) return
        busy = true
        scope.launch {
            val ctx = context.applicationContext
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // 裁正方形（居中裁剪）+ 压缩 JPEG（原图可能几十 MB，必须压）
                    val square = cropSquare(
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ) ?: return@runCatching false
                    val out = ByteArrayOutputStream()
                    square.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    val jpeg = out.toByteArray()
                    // 存自定义封面（本地/在线歌通用，最高优先级）
                    Store.saveCustomCover(ctx, song, jpeg) != null
                    // v1.5.1 r47：在线下载存量歌同样嵌入文件（同 applyOnline
                    // 修复——此前只认本地导入歌）
                    var onlineFileName: String? = null
                    if (!LocalCoverExtractor.isLocalSong(song)) {
                        onlineFileName = com.solara.music.data.DownloadManager
                            .findLocalFileAbsPath(ctx, song)?.let { java.io.File(it).name }
                    }
                    if (LocalCoverExtractor.isLocalSong(song) || onlineFileName != null) {
                        runCatching {
                            TagEmbedder.embedCoverInto(ctx, song, jpeg, onlineFileName)
                        }
                        // v1.4.42：清旧 URL 缓存，保证清除自定义封面后
                        // 显示的也是新嵌入的封面而非自动匹配的旧图
                        Store.clearLocalCoverUrl(song)
                    }
                    true
                }.getOrDefault(false)
            }
            busy = false
            if (ok) {
                LocalCoverExtractor.bumpRevision()
                onApplied()
                onDismiss()
            } else {
                android.widget.Toast.makeText(
                    ctx, "图片保存失败", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 相册选图（GetContent 单选图片）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                busy = false
                if (bytes != null) applyLocalImage(bytes)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("封面编辑") },
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
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("歌名 或 歌名+歌手") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = { doSearch() },
                        enabled = !searching && !busy && query.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.ImageSearch,
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
                        .heightIn(min = 180.dp, max = 300.dp)
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
                                val applying = applyingIdx == i
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !busy) { applyOnline(r, i) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (applying) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(44.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        // 候选封面缩略图（在线歌直接用 CoverImage 加载）
                                        CoverImage(
                                            song = r,
                                            size = 44.dp,
                                            corner = 8.dp
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
                Spacer(Modifier.height(8.dp))
                // 本地选图入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !busy) { pickImage.launch("image/*") }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "从相册选择图片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = { if (!busy) onDismiss() },
                enabled = !busy
            ) { Text("关闭") }
        }
    )
}

/** 位图居中裁正方形。 */
private fun cropSquare(src: Bitmap?): Bitmap? {
    if (src == null) return null
    val side = minOf(src.width, src.height)
    val x = (src.width - side) / 2
    val y = (src.height - side) / 2
    // 目标边长上限 800（封面显示 300dp 足够，控制文件体积）
    val target = if (side > 800) 800 else side
    val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, null, RectF(0f, 0f, target.toFloat(), target.toFloat()), null)
    return out
}

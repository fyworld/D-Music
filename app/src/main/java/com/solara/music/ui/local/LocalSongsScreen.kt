package com.solara.music.ui.local

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.solara.music.data.DownloadManager
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.TagEmbedder
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.AddSongsToPlaylistSheet
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.FolderPickerDialog
import com.solara.music.ui.components.SelectionTopBar
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.dragReorder
import com.solara.music.ui.components.rememberDragReorderState
import com.solara.music.ui.components.rememberMultiSelectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 待授权操作：移除或重命名（v1.4.4）。 */
private data class PendingAuth(
    val song: Song,
    val isDelete: Boolean,
    val renameInput: String? = null
)

/** v1.4.5：待「所有文件访问」授权的操作（跳系统设置返回后自动续办）。 */
private data class PendingAllFiles(
    val song: Song,
    val isDelete: Boolean,
    val renameInput: String? = null
)

/**
 * 本地歌曲：已下载到本机的歌曲列表。
 * - 点击播放（本地文件优先，离线可用）
 * - 更多菜单：加入歌单 / 收藏 / 移除（移出列表并删除文件）/ 重命名
 * - 顶栏菜单：扫描本地歌曲 / 选择扫描文件夹（系统文件管理器）/ 清空列表 /
 *   匹配在线封面
 * - v1.4.4：删除/重命名他建文件走 Scoped Storage 系统授权弹窗
 * - 长按拖动排序
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSongsScreen(
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    val songs by Store.downloads.collectAsState()
    val favorites by Store.favorites.collectAsState()
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    var pendingRename by remember { mutableStateOf<Song?>(null) }
    var pendingClear by remember { mutableStateOf(false) }
    var pendingAuth by remember { mutableStateOf<PendingAuth?>(null) }
    // v1.4.5：他建文件操作 → 自家选择框（永久授权 / 仅此一次）
    var askAllFiles by remember { mutableStateOf<PendingAllFiles?>(null) }
    var pendingAllFiles by remember { mutableStateOf<PendingAllFiles?>(null) }
    // v1.4.7：App 内文件夹浏览器
    var showFolderPicker by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanToast by remember { mutableStateOf<String?>(null) }
    // v1.4.0：批量匹配在线封面
    var matching by remember { mutableStateOf(false) }
    var matchProgress by remember { mutableStateOf(0 to 0) } // done to total
    // v1.4.26：多选批量（收藏 / 加入歌单 / 移出列表）
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }
    var showBatchRemoveConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val dragState = rememberDragReorderState(listState) { from, to ->
        Store.moveDownload(from, to)
    }

    // v1.4.26：多选模式下按系统返回键 = 退出多选（优先于 SolaraApp 的
    // 子页面返回拦截——Compose BackHandler 组合树中后注册者优先）
    androidx.activity.compose.BackHandler(enabled = select.active) {
        select.exit()
    }

    /**
     * 扫描本地歌曲。
     * v1.4.8：folder 参数语义收紧——
     * - null = 默认目录 Music/D_Music（「扫描本地歌曲」固定用默认目录，
     *   不再被「选择扫描文件夹」的选择劫持）
     * - 指定路径 = 「选择扫描文件夹」确认的目录
     */
    fun runScan(folder: String? = null) {
        if (scanning) return
        scanning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadManager.scanLocalLibrary(context.applicationContext, folder)
                }.getOrDefault(Store.downloads.value)
            }
            val added = result.size - songs.size
            Store.replaceDownloads(result)
            scanning = false
            val folderName = folder ?: "Music/D_Music"
            scanToast = when {
                result.isEmpty() -> "在 $folderName 未找到音频文件"
                added > 0 -> "扫描完成：新增 $added 首，共 ${result.size} 首"
                else -> "扫描完成：共 ${result.size} 首（无新增）"
            }
        }
    }

    // v1.4.5：执行「所有文件访问」授权后的待办操作（删除/重命名）。
    // 从系统设置返回（onResume）且权限已开 → 自动重试一次
    fun runPendingAllFiles() {
        val pending = pendingAllFiles ?: return
        pendingAllFiles = null
        if (!DownloadManager.hasAllFilesAccess()) {
            scanToast = "未开启「所有文件访问」，操作已取消"
            return
        }
        scope.launch {
            val target = pending.song
            if (pending.isDelete) {
                // 目标在播：彻底停止并释放文件句柄
                val cur = PlayerManager.currentSong.value
                if (cur != null && cur.sameAs(target)) {
                    PlayerManager.stop()
                    PlayerManager.playerOrNull?.let { p ->
                        runCatching { p.stop(); p.clearMediaItems() }
                    }
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        DownloadManager.deleteLocalFileWithAuth(
                            context.applicationContext, target
                        )
                    }.getOrDefault(DownloadManager.LocalFileResult.FAILED)
                }
                when (result) {
                    DownloadManager.LocalFileResult.DELETED,
                    DownloadManager.LocalFileResult.NOT_FOUND -> {
                        Store.removeDownload(target)
                        PlayerManager.queue.value.forEachIndexed { i, s ->
                            if (s.sameAs(target)) {
                                PlayerManager.removeAt(i); return@forEachIndexed
                            }
                        }
                        if (result == DownloadManager.LocalFileResult.DELETED) {
                            scanToast = "已删除文件并移出列表"
                        }
                    }

                    else -> scanToast = "文件删除失败"
                }
            } else {
                val input = pending.renameInput ?: return@launch
                val cur = PlayerManager.currentSong.value
                if (cur != null && cur.sameAs(target)) {
                    PlayerManager.stop()
                    PlayerManager.playerOrNull?.let { p ->
                        runCatching { p.stop(); p.clearMediaItems() }
                    }
                }
                val (result, newFile) = withContext(Dispatchers.IO) {
                    runCatching {
                        DownloadManager.renameLocalFileWithAuth(
                            context.applicationContext, target, input
                        )
                    }.getOrDefault(DownloadManager.RenameResult.FAILED to null)
                }
                if (result == DownloadManager.RenameResult.OK && newFile != null) {
                    Store.renameDownload(target, newFile)
                    LocalCoverExtractor.invalidate(target)
                    LocalCoverExtractor.bumpRevision()
                    PlayerManager.replaceSong(
                        target,
                        Store.downloads.value.firstOrNull {
                            it.id == "local:$newFile"
                        } ?: target.copy(id = "local:$newFile")
                    )
                    scanToast = "重命名成功"
                } else {
                    scanToast = "重命名失败"
                }
            }
        }
    }

    // 从系统设置开启权限返回本页时，自动续办刚才的操作
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingAllFiles != null) runPendingAllFiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v1.4.7：选择扫描文件夹已改为 App 内文件夹浏览器（FolderPickerDialog）。
    // 旧 SAF 方案（OpenDocumentTree）在部分 ROM 上会记住上次授权的 tree URI，
    // 第二次打开直接自动返回旧目录无法重选；且点选即扫描、无法进子目录确认。

    // v1.4.4：Scoped Storage 授权弹窗结果（删除/写入他建文件）
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingAuth
        pendingAuth = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (pending.isDelete) {
                        // 系统已删文件（或授权成功），清理记录
                        Store.removeDownload(pending.song)
                        PlayerManager.queue.value.forEachIndexed { i, s ->
                            if (s.sameAs(pending.song)) {
                                PlayerManager.removeAt(i); return@forEachIndexed
                            }
                        }
                    } else {
                        // 写权限已授予：重试重命名
                        val input = pending.renameInput ?: return@withContext
                        val newFile = runCatching {
                            DownloadManager.renameLocalFile(
                                context.applicationContext, pending.song, input
                            )
                        }.getOrNull()
                        if (newFile != null) {
                            Store.renameDownload(pending.song, newFile)
                            LocalCoverExtractor.invalidate(pending.song)
                            LocalCoverExtractor.bumpRevision()
                            PlayerManager.replaceSong(
                                pending.song,
                                Store.downloads.value.firstOrNull {
                                    it.id == "local:$newFile"
                                } ?: pending.song.copy(id = "local:$newFile")
                            )
                        } else {
                            scanToast = "重命名失败"
                        }
                    }
                }
            }
        } else if (pending != null && !pending.isDelete) {
            scanToast = "未获得文件修改权限，重命名已取消"
        }
    }

    /**
     * v1.4.0：批量匹配在线封面。
     * 暂停播放（释放文件占用）→ 逐首：跳过已有内嵌封面/URL 缓存的 →
     * 在线搜索匹配 → 下载封面字节 → 嵌入文件（失败则仅存 URL 缓存）。
     * 完成后 bumpRevision 触发全列表封面刷新。
     */
    fun runMatchCovers() {
        if (matching) return
        val targets = songs.filter { LocalCoverExtractor.isLocalSong(it) }
        if (targets.isEmpty()) {
            scanToast = "没有需要匹配封面的本地歌曲"
            return
        }
        matching = true
        matchProgress = 0 to targets.size
        // 暂停播放：嵌入写文件时目标文件不能正被 ExoPlayer 占用
        PlayerManager.stop()
        scope.launch {
            var ok = 0
            var fail = 0
            targets.forEachIndexed { i, song ->
                matchProgress = i to targets.size
                withContext(Dispatchers.IO) {
                    runCatching {
                        // 已有封面（内嵌/同名图/URL 缓存）则跳过
                        if (Store.localCoverUrl(song) != null) return@runCatching true
                        val url = LocalCoverExtractor.matchCover(song) ?: return@runCatching false
                        val bytes = runCatching {
                            java.net.URL(url).openStream().use { it.readBytes() }
                        }.getOrNull() ?: return@runCatching false
                        // 嵌入文件（失败不影响 URL 缓存，UI 仍能显示封面）
                        val embedded = TagEmbedder.embedCoverInto(
                            context.applicationContext, song, bytes
                        )
                        if (!embedded) Store.saveLocalCoverUrl(song, url)
                        true
                    }.getOrDefault(false).let { if (it) ok++ else fail++ }
                }
            }
            matchProgress = targets.size to targets.size
            matching = false
            LocalCoverExtractor.bumpRevision()
            scanToast = "封面匹配完成：成功 $ok 首" +
                (if (fail > 0) "，未匹配 $fail 首" else "") +
                "\n（已嵌入文件，其他播放器也能显示）"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        if (select.active) {
            // v1.4.26：多选模式顶栏（收藏 / 加入歌单 / 移出列表）
            SelectionTopBar(
                selectedCount = select.selected.size,
                totalCount = songs.size,
                onExit = { select.exit() },
                onToggleSelectAll = {
                    if (select.selected.size >= songs.size) select.clearSelection()
                    else select.selectAll(songs)
                },
                onFavorite = {
                    val added = Store.addFavorites(select.selectedSongs(songs))
                    onShowMessage("已收藏 $added 首（重复自动跳过）")
                },
                onAddToPlaylist = {
                    if (select.selected.isNotEmpty()) showBatchPlaylist = true
                },
                onDelete = {
                    if (select.selected.isNotEmpty()) showBatchRemoveConfirm = true
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "本地歌曲",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${songs.size} 首 · 长按可拖动排序",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(modifier = Modifier.padding(end = 12.dp)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    // v1.4.26：多选收进更多菜单
                    DropdownMenuItem(
                        text = { Text("多选") },
                        leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            select.enter()
                        }
                    )
                    // v1.4.26：播放全部收进更多菜单
                    DropdownMenuItem(
                        text = { Text("播放全部") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            PlayerManager.setQueue(songs, 0)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (scanning) "扫描中…" else "扫描本地歌曲") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = !scanning,
                        onClick = {
                            menuOpen = false
                            runScan()
                        }
                    )
                    // v1.4.7：选择扫描文件夹——App 内文件夹浏览器
                    // （SAF 在部分 ROM 上会记住旧授权目录导致无法重选）
                    DropdownMenuItem(
                        text = { Text("扫描文件夹") },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        enabled = !scanning,
                        onClick = {
                            menuOpen = false
                            showFolderPicker = true
                        }
                    )
                    // v1.4.3：清空列表（不删除文件）
                    DropdownMenuItem(
                        text = { Text("清空列表") },
                        leadingIcon = { Icon(Icons.Filled.ClearAll, contentDescription = null) },
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            pendingClear = true
                        }
                    )
                    // v1.4.0：批量匹配在线封面（搜索匹配 → 嵌入文件 + 缓存 URL）
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (matching) "匹配封面中 ${matchProgress.first}/${matchProgress.second}…"
                                else "匹配在线封面"
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.ImageSearch, contentDescription = null) },
                        enabled = !matching && songs.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            runMatchCovers()
                        }
                    )
                }
                }
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyState("还没有本地歌曲\n在歌曲菜单里选「下载」保存到本机")
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { runScan() },
                        enabled = !scanning
                    ) { Text(if (scanning) "扫描中…" else "扫描本地歌曲") }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(songs.size) { i ->
                    val song = songs[i]
                    SongRow(
                        song = song,
                        isFavorite = favorites.any { it.sameAs(song) },
                        onClick = { PlayerManager.setQueue(songs, i) },
                        onToggleFavorite = { Store.toggleFavorite(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onRemove = { pendingDelete = song },
                        onRename = { pendingRename = song },
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

    scanToast?.let { msg ->
        AlertDialog(
            onDismissRequest = { scanToast = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { scanToast = null }) { Text("知道了") }
            }
        )
    }

    // v1.4.3：清空列表确认（只清记录，不删除文件）
    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("清空本地歌曲列表") },
            text = {
                Text("移除列表中全部 ${songs.size} 条记录，不会删除手机里的音频文件。之后可通过「扫描本地歌曲」重新添加。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClear = false
                        Store.replaceDownloads(emptyList())
                        scanToast = "列表已清空（文件未删除）"
                    }
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text("取消") }
            }
        )
    }

    // 移除确认：删除文件 + 移出列表（v1.4.4：他建文件走系统授权）
    if (pendingDelete != null) {
        val song = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除本地歌曲") },
            text = {
                Text(
                    "将「${song.artistName} - ${song.displayName}」移出本地歌曲列表，" +
                        "并删除手机里的音频文件，无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = song
                    pendingDelete = null
                    scope.launch {
                        // 目标在播：彻底停止并释放文件句柄
                        val cur = PlayerManager.currentSong.value
                        if (cur != null && cur.sameAs(target)) {
                            PlayerManager.stop()
                            PlayerManager.playerOrNull?.let { p ->
                                runCatching { p.stop(); p.clearMediaItems() }
                            }
                        }
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                DownloadManager.deleteLocalFileWithAuth(
                                    context.applicationContext, target
                                )
                            }.getOrDefault(DownloadManager.LocalFileResult.FAILED)
                        }
                        when (result) {
                            DownloadManager.LocalFileResult.DELETED -> {
                                Store.removeDownload(target)
                                PlayerManager.queue.value.forEachIndexed { i, s ->
                                    if (s.sameAs(target)) {
                                        PlayerManager.removeAt(i); return@forEachIndexed
                                    }
                                }
                            }

                            DownloadManager.LocalFileResult.NEEDS_AUTH -> {
                                // v1.4.5：他建文件 → 自家选择框（推荐永久授权，
                                // 开启后所有删除/重命名完全静默无弹窗）
                                askAllFiles = PendingAllFiles(target, isDelete = true)
                            }

                            else -> {
                                // NOT_FOUND：文件已不在，直接清记录
                                Store.removeDownload(target)
                                PlayerManager.queue.value.forEachIndexed { i, s ->
                                    if (s.sameAs(target)) {
                                        PlayerManager.removeAt(i); return@forEachIndexed
                                    }
                                }
                                if (result == DownloadManager.LocalFileResult.FAILED) {
                                    scanToast = "已移出列表，但音频文件删除失败"
                                }
                            }
                        }
                    }
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    // 重命名：改本地文件名（沿用扩展名），并同步更新列表记录
    if (pendingRename != null) {
        val song = pendingRename!!
        val oldBase = song.id.removePrefix("local:").substringBeforeLast('.', "")
        var newName by remember(song.id) { mutableStateOf(oldBase) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名本地歌曲") },
            text = {
                Column {
                    Text(
                        "修改文件名（不含扩展名）。建议保持「歌手 - 歌名」格式，" +
                            "列表标题和歌手会按此重新解析。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = song
                        val input = newName.trim()
                        pendingRename = null
                        if (input.isBlank() || input == oldBase) return@TextButton
                        scope.launch {
                            // 目标文件正在播放：彻底停止并清空播放器释放文件句柄
                            val cur = PlayerManager.currentSong.value
                            if (cur != null && cur.sameAs(target)) {
                                PlayerManager.stop()
                                PlayerManager.playerOrNull?.let { p ->
                                    runCatching { p.stop(); p.clearMediaItems() }
                                }
                            }
                            val (result, newFile) = withContext(Dispatchers.IO) {
                                runCatching {
                                    DownloadManager.renameLocalFileWithAuth(
                                        context.applicationContext, target, input
                                    )
                                }.getOrDefault(
                                    DownloadManager.RenameResult.FAILED to null
                                )
                            }
                            when (result) {
                                DownloadManager.RenameResult.OK -> {
                                    Store.renameDownload(target, newFile!!)
                                    LocalCoverExtractor.invalidate(target)
                                    LocalCoverExtractor.bumpRevision()
                                    PlayerManager.replaceSong(
                                        target,
                                        Store.downloads.value.firstOrNull {
                                            it.id == "local:$newFile"
                                        } ?: target.copy(id = "local:$newFile")
                                    )
                                }

                                DownloadManager.RenameResult.NEEDS_AUTH -> {
                                    // v1.4.5：他建文件 → 自家选择框
                                    askAllFiles = PendingAllFiles(
                                        target, isDelete = false, renameInput = input
                                    )
                                }

                                DownloadManager.RenameResult.NOT_FOUND ->
                                    scanToast = "重命名失败：找不到音频文件"

                                else ->
                                    scanToast = "重命名失败，请检查文件是否被占用"
                            }
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            }
        )
    }
    // v1.4.5：他建文件操作的自家选择框——
    // 「去开启」= 跳系统设置开「所有文件访问」（一次开启，永久静默）；
    // 「仅此一次」= 旧流程（系统单次授权弹窗）
    if (askAllFiles != null) {
        val pending = askAllFiles!!
        AlertDialog(
            onDismissRequest = { askAllFiles = null },
            title = { Text("需要文件管理权限") },
            text = {
                Text(
                    "「${pending.song.displayName}」由其他应用创建，系统要求授权后才能" +
                        (if (pending.isDelete) "删除" else "重命名") + "。\n\n" +
                        "推荐开启「所有文件访问」：只需设置一次，之后删除、重命名" +
                        "任何歌曲都不会再弹窗。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askAllFiles = null
                    val intent = DownloadManager.allFilesAccessSettingsIntent(context)
                    if (intent != null) {
                        pendingAllFiles = pending
                        runCatching { context.startActivity(intent) }
                    } else {
                        // 系统不支持直达设置页：退回单次授权
                        scope.launch {
                            val sender = withContext(Dispatchers.IO) {
                                if (pending.isDelete) {
                                    DownloadManager.createDeleteAuth(context, pending.song)
                                } else {
                                    DownloadManager.createRenameAuth(context, pending.song)
                                }
                            }
                            if (sender != null) {
                                pendingAuth = PendingAuth(
                                    pending.song, pending.isDelete, pending.renameInput
                                )
                                runCatching {
                                    authLauncher.launch(IntentSenderRequestBuilder.build(sender))
                                }
                            } else {
                                scanToast = "当前系统不支持此授权方式"
                            }
                        }
                    }
                }) { Text("去开启（推荐）") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askAllFiles = null
                    // 仅此一次：走旧的单次系统授权弹窗
                    scope.launch {
                        val sender = withContext(Dispatchers.IO) {
                            if (pending.isDelete) {
                                DownloadManager.createDeleteAuth(context, pending.song)
                            } else {
                                DownloadManager.createRenameAuth(context, pending.song)
                            }
                        }
                        if (sender != null) {
                            pendingAuth = PendingAuth(
                                pending.song, pending.isDelete, pending.renameInput
                            )
                            runCatching {
                                authLauncher.launch(IntentSenderRequestBuilder.build(sender))
                            }
                        } else {
                            scanToast = "当前系统不支持此授权方式"
                        }
                    }
                }) { Text("仅此一次") }
            }
        )
    }

    // v1.4.26：多选批量加入歌单
    if (showBatchPlaylist) {
        AddSongsToPlaylistSheet(
            songs = select.selectedSongs(songs),
            onDismiss = { showBatchPlaylist = false },
            onDone = { name ->
                onShowMessage("已加入歌单「$name」")
                select.exit()
            }
        )
    }

    // v1.4.26：批量移出列表确认（仅清记录，不删除文件；删文件走单曲菜单）
    if (showBatchRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchRemoveConfirm = false },
            title = { Text("移出本地歌曲列表") },
            text = {
                Text(
                    "将所选的 ${select.selected.size} 首歌曲移出列表（仅清除记录，不删除手机里的音频文件）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = select.selected.size
                    Store.removeDownloads(select.selectedSongs(songs))
                    showBatchRemoveConfirm = false
                    select.exit()
                    onShowMessage("已移出 $n 首（文件未删除）")
                }) { Text("移出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRemoveConfirm = false }) { Text("取消") }
            }
        )
    }

    // v1.4.7：App 内文件夹浏览器——浏览进子目录，确认后才扫描
    if (showFolderPicker) {
        FolderPickerDialog(
            initialRelPath = Store.scanFolder.value,
            onDismiss = { showFolderPicker = false },
            onConfirm = { relPath ->
                showFolderPicker = false
                if (relPath.isBlank()) {
                    scanToast = "请选择一个文件夹（不支持扫描内部存储根目录）"
                } else {
                    Store.saveScanFolder(relPath)
                    runScan(relPath)
                }
            }
        )
    }
}

/** IntentSender → IntentSenderRequest 的薄封装（避免 UI 层直接依赖 androidx 类型别名混乱）。 */
private object IntentSenderRequestBuilder {
    fun build(sender: android.content.IntentSender): androidx.activity.result.IntentSenderRequest =
        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
}

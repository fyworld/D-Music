package com.solara.music.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.solara.music.data.DownloadManager
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.LyricRepository
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.TagEmbedder
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.AddSongsToPlaylistSheet
import com.solara.music.ui.components.EmptyState
import com.solara.music.ui.components.LocalFileOpsHandler
import com.solara.music.ui.components.SelectionTopBar
import com.solara.music.ui.components.SongRow
import com.solara.music.ui.components.dragReorder
import com.solara.music.ui.components.rememberDragReorderState
import com.solara.music.ui.components.rememberMultiSelectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地歌曲（v1.4.58 重构）：文件夹浏览形式。
 *
 * - 打开即浏览内部存储根目录，逐级进入文件夹
 * - 混合列表：文件夹行（图标+名称+音频计数）在前，歌曲行（SongRow）在后
 * - 歌曲直接点击播放（MediaStore 全库按文件名精确解析，任意目录可播）
 * - 目录内更多菜单：多选（整个目录加入歌单）/ 播放全部 / 匹配在线封面 /
 *   批量下载歌词
 * - 单曲菜单：加入歌单 / 收藏 / 下载歌词 / 重命名 / 移除（删除文件）
 * - 删除/重命名他建文件走 Scoped Storage 系统授权弹窗（v1.4.4/5 链路保留）
 * - v1.4.58 起移除「扫描本地歌曲 / 扫描文件夹 / 清空列表 / 拖动排序」
 *   （浏览即所见，无需扫描导入；Store.downloads 仍保留在线下载记录）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSongsScreen(
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onLyricUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by Store.favorites.collectAsState()
    // v1.4.59：当前播放歌曲——列表行显示播放中标记
    val currentSong by PlayerManager.currentSong.collectAsState()

    // ---------- 文件夹浏览状态 ----------
    // null = 内部存储根；"Music/D_Music" = 子目录。
    // v1.4.58：初始值取 Store 持久化（跨页面/重启保留最后浏览位置），
    // 每次导航同步落盘
    var currentPath by remember { mutableStateOf(Store.localBrowsePath.value) }
    var content by remember { mutableStateOf<DownloadManager.FolderContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canRead by remember { mutableStateOf(true) }
    // v1.4.58：目录列表刷新 tick（删除/重命名文件后 bump）
    var refreshTick by remember { mutableStateOf(0) }

    // 浏览位置持久化：路径变化即保存（含回到根 = null）
    LaunchedEffect(currentPath) {
        Store.saveLocalBrowsePath(currentPath)
    }

    // ---------- 操作状态（沿用旧版链路） ----------
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    var pendingRename by remember { mutableStateOf<Song?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var scanToast by remember { mutableStateOf<String?>(null) }
    // v1.4.0：批量匹配在线封面
    var matching by remember { mutableStateOf(false) }
    var matchProgress by remember { mutableStateOf(0 to 0) }
    // v1.4.39：下载歌词（单曲对话框 + 批量）
    var lyricTarget by remember { mutableStateOf<Song?>(null) }
    var lyricBatching by remember { mutableStateOf(false) }
    var lyricBatchProgress by remember { mutableStateOf(0 to 0) }
    // v1.4.26：多选批量
    val select = rememberMultiSelectState()
    var showBatchPlaylist by remember { mutableStateOf(false) }
    // v1.4.58：重命名当前文件夹
    var pendingFolderRename by remember { mutableStateOf(false) }

    // v1.5.1 r38：本地歌时长补全结果（song.id → "m:ss"）。列目录 effect
    // 后台逐首读元数据写入；换目录/刷新 tick 变化时清空重读
    var durations by remember(currentPath, refreshTick) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    // v1.5.1 r59：列表状态（拖动排序需要）+ 每文件夹自定义顺序。
    // dirKey：根目录 ""、子目录用相对路径——顺序表的 key。
    // orderVersion：顺序版本号——moveLocalSong 成功后 bump，触发
    // songs 重算（content/dirKey 都没变时顺序变化也要刷新 UI）
    val listState = rememberLazyListState()
    val dirKey = currentPath ?: ""
    var orderVersion by remember { mutableStateOf(0) }
    val songs = remember(content, dirKey, orderVersion) {
        content?.let { Store.applyLocalSongOrder(dirKey, it.songs) } ?: emptyList()
    }
    val folderCount = content?.folders?.size ?: 0

    // r59：拖动排序。onMove 的 from/to 是 LazyColumn 绝对下标
    // （文件夹行在前）——减 folderCount 换算成歌曲下标再搬。
    // lambda 只在首次组合创建一次，捕获的普通 val（dirKey/songs）
    // 会过期——currentPath/content/orderVersion 是委托变量（捕获
    // State 本体）读的永远是现值；歌曲列表在 lambda 内从 Store
    // 重算（顺序表刚写入，读必是新序）
    val dragState = rememberDragReorderState(listState) { from, to ->
        val dirKeyNow = currentPath ?: ""
        val folderCountNow = content?.folders?.size ?: 0
        val songsNow = content?.let { Store.applyLocalSongOrder(dirKeyNow, it.songs) }
            ?: emptyList()
        val ok = Store.moveLocalSong(
            dirKeyNow, from - folderCountNow, to - folderCountNow, songsNow
        )
        if (ok) orderVersion++
        ok
    }

    // r59：换目录重置滚动——listState 提升到页面级后跨目录存活
    // （loading 分支销毁 LazyColumn 也不再销毁 state），不重置会
    // 带着旧目录的滚动位置进新目录
    LaunchedEffect(currentPath) {
        listState.scrollToItem(0)
    }

    /** 返回上级路径（根的父级 = null = 根）。 */
    fun parentOf(path: String?): String? =
        path?.substringBeforeLast('/', "")?.ifBlank { null }

    // v1.4.58：系统返回键统一拦截（优先级从高到低）：
    // 1. 多选模式 → 退出多选（优先于 SolaraApp 的子页面返回拦截——
    //    Compose BackHandler 组合树中后注册者优先）
    // 2. 目录内 → 返回上级
    // 3. 根目录 → 不拦截，交给外层退出本页
    androidx.activity.compose.BackHandler(
        enabled = select.active || currentPath != null
    ) {
        when {
            select.active -> select.exit()
            else -> currentPath = parentOf(currentPath)
        }
    }

    // 列目录 + v1.5.1 r38 时长补全（路径或刷新 tick 变化时重列）。
    // 时长读元数据与列目录合并成一个 effect——原拆两个同键 effect 有竞态：
    // 两者同时启动，时长 effect 读 songs 时 content 还是旧值（首次为
    // null → targets 空 → 直接 return，时长永远不出现）。
    LaunchedEffect(currentPath, refreshTick) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { DownloadManager.listFolder(currentPath) }.getOrNull()
        }
        canRead = result != null
        content = result
        loading = false
        // 本地歌 interval 恒空（songFromFileName 不读元数据）——IO 后台
        // 逐个 MediaMetadataRetriever 读（每首约几十 ms），结果写内存 map
        // 渐进刷新 UI；换目录/刷新自动作废（key 变了 map 清空重读）。
        val targets = (result?.songs ?: emptyList()).filter { it.interval.isBlank() }
        if (targets.isNotEmpty()) {
            val acc = mutableMapOf<String, String>()
            targets.forEach { song ->
                val dur = withContext(Dispatchers.IO) {
                    runCatching {
                        val f = DownloadManager.localFileOf(song) ?: return@runCatching null
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(f.absolutePath)
                            mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull()?.let { ms ->
                                if (ms > 0) "%d:%02d".format(ms / 60000, (ms % 60000) / 1000) else null
                            }
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }.getOrNull()
                }
                if (dur != null) {
                    acc[song.id] = dur
                    durations = acc.toMap()
                }
            }
        }
    }

    /** v1.4.58：文件删除/重命名成功后刷新当前目录。 */
    fun refreshFolder() {
        refreshTick++
    }

    /**
     * v1.4.0：批量匹配在线封面（对当前目录歌曲）。
     * 暂停播放（释放文件占用）→ 逐首：跳过已有内嵌封面/URL 缓存的 →
     * 在线搜索匹配 → 下载封面字节 → 嵌入文件（失败则仅存 URL 缓存）。
     */
    fun runMatchCovers() {
        if (matching) return
        val targets = songs.filter { LocalCoverExtractor.isLocalSong(it) }
        if (targets.isEmpty()) {
            scanToast = "当前目录没有歌曲"
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

    /**
     * v1.4.39：批量下载歌词（对当前目录歌曲）。
     */
    fun runBatchLyrics(onAllDone: () -> Unit) {
        if (lyricBatching) return
        val targets = songs.filter { LocalCoverExtractor.isLocalSong(it) }
        if (targets.isEmpty()) {
            scanToast = "当前目录没有歌曲"
            return
        }
        lyricBatching = true
        lyricBatchProgress = 0 to targets.size
        scope.launch {
            var ok = 0
            var skip = 0
            var fail = 0
            targets.forEachIndexed { i, song ->
                lyricBatchProgress = i to targets.size
                withContext(Dispatchers.IO) {
                    runCatching {
                        // 已有歌词（缓存/内嵌/伴生）跳过
                        if (LyricRepository.hasLyric(context.applicationContext, song)) {
                            return@runCatching -1
                        }
                        val hit = LyricRepository.matchLyricOnline(song)
                            ?: return@runCatching 0
                        val pair = LyricRepository.downloadLyric(
                            context.applicationContext, song, hit
                        ) ?: return@runCatching 0
                        1
                    }.getOrDefault(0).let { r ->
                        when (r) {
                            -1 -> skip++
                            1 -> ok++
                            else -> fail++
                        }
                    }
                }
            }
            lyricBatchProgress = targets.size to targets.size
            lyricBatching = false
            scanToast = buildString {
                append("歌词下载完成：成功 $ok 首")
                if (skip > 0) append("，已有跳过 $skip 首")
                if (fail > 0) append("，未匹配 $fail 首")
                append("\n（已嵌入文件，其他播放器也能显示）")
            }
            onAllDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        if (select.active) {
            // v1.4.26：多选模式顶栏（收藏 / 加入歌单）
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
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (currentPath == null) onBack()
                    else currentPath = parentOf(currentPath)
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentPath?.substringAfterLast('/') ?: "本地歌曲",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // v1.4.58：标题后跟（n 首）小字（对齐歌单详情页样式）
                        if (!loading && canRead && songs.isNotEmpty()) {
                            Text(
                                text = "（${songs.size} 首）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = "内部存储" + (currentPath?.let { "/$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        // v1.4.58：重命名当前文件夹（对齐歌单详情页菜单结构）
                        DropdownMenuItem(
                            text = { Text("重命名文件夹") },
                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                            enabled = currentPath != null,
                            onClick = {
                                menuOpen = false
                                pendingFolderRename = true
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
                        // v1.4.39：批量下载歌词（自动匹配歌名+歌手 → 缓存+嵌入文件）
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (lyricBatching) "下载歌词中 ${lyricBatchProgress.first}/${lyricBatchProgress.second}…"
                                    else "批量下载歌词"
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Lyrics, contentDescription = null) },
                            enabled = !lyricBatching && songs.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                runBatchLyrics(onLyricUpdated)
                            }
                        )
                    }
                }
            }
        }

        when {
            loading -> Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            }

            !canRead -> Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyState("无法读取文件夹\n请开启「所有文件访问」权限后重试")
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        DownloadManager.allFilesAccessSettingsIntent(context)?.let {
                            runCatching { context.startActivity(it) }
                        }
                    }) { Text("去开启") }
                }
            }

            songs.isEmpty() && (content?.folders ?: emptyList()).isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth()
            ) {
                EmptyState(
                    "此文件夹是空的\n进入子文件夹找到歌曲后即可直接播放"
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 文件夹行（在前）
                items(content?.folders ?: emptyList(), key = { it.name }) { folder ->
                    // v1.4.59 r15：当前播放歌曲属于该文件夹（含子目录）时
                    // 文件夹行显示播放标志（名称+图标转主色）
                    val folderPath = if (currentPath == null) folder.name
                    else "$currentPath/${folder.name}"
                    FolderRow(
                        name = folder.name,
                        songCount = folder.audioCount,
                        isPlaying = currentSong?.let { cur ->
                            cur.source == "local" && cur.id.startsWith("local:") &&
                                cur.id.removePrefix("local:").startsWith("$folderPath/")
                        } == true,
                        onClick = {
                            currentPath = if (currentPath == null) folder.name
                            else "$currentPath/${folder.name}"
                        }
                    )
                }
                // 歌曲行（在后）
                // r59：key = 稳定标识（r52 教训——无 key 时组件按位置
                // 复用，交换后手势组件显示别的歌 → 手势取消）；
                // dragReorder 的 index 是 LazyColumn 绝对下标（文件夹
                // 行在前，需 +folderCount 偏移）
                items(
                    count = songs.size,
                    key = { i -> songs[i].source + ":" + songs[i].id }
                ) { i ->
                    val song = songs[i]
                    SongRow(
                        song = song,
                        isFavorite = favorites.any { it.sameAs(song) },
                        isCurrent = currentSong?.sameAs(song) == true,
                        onClick = { PlayerManager.setQueue(songs, i) },
                        onToggleFavorite = { Store.toggleFavorite(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        // v1.5.1 r59：置顶/置底（本地歌曲有顺序语义）
                        onMoveToTop = {
                            if (Store.moveLocalSong(dirKey, i, 0, songs)) orderVersion++
                        },
                        onMoveToBottom = {
                            if (Store.moveLocalSong(dirKey, i, songs.size - 1, songs)) orderVersion++
                        },
                        onRemove = { pendingDelete = song },
                        onRename = { pendingRename = song },
                        onDownloadLyric = { lyricTarget = song },
                        selectionMode = select.active,
                        selected = select.isSelected(song),
                        onSelect = { select.toggle(song) },
                        // v1.5.1 r38：本地歌时长（后台 MediaMetadataRetriever 补全）
                        durationOverride = durations[song.id],
                        modifier = if (select.active) Modifier
                        else Modifier.dragReorder(dragState, folderCount + i)
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

    // v1.4.39：单曲「下载歌词」对话框
    lyricTarget?.let { target ->
        com.solara.music.ui.components.LyricDownloadDialog(
            song = target,
            onDismiss = { lyricTarget = null },
            onDownloaded = { embedded ->
                scanToast = if (embedded) "歌词已下载并嵌入文件，其他播放器也能显示"
                else "歌词已下载（嵌入文件失败，已存缓存）"
                onLyricUpdated()
            }
        )
    }

    // v1.4.58：文件操作公共链路（删除/重命名 + Scoped Storage 授权），
    // 从本页抽出为公共组件，与下载管理页共用
    LocalFileOpsHandler(
        deleteTarget = pendingDelete,
        onDeleteTargetChange = { pendingDelete = it },
        renameTarget = pendingRename,
        onRenameTargetChange = { pendingRename = it },
        onDone = { message ->
            scanToast = message
            refreshFolder()
        }
    )

    // v1.4.58：重命名当前文件夹
    if (pendingFolderRename && currentPath != null) {
        val path = currentPath!!
        val oldName = path.substringAfterLast('/')
        var newName by remember(path) { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { pendingFolderRename = false },
            title = { Text("重命名文件夹") },
            text = {
                Column {
                    Text(
                        "修改当前文件夹的名称。文件夹内有歌曲正在播放时会先停止播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("文件夹名") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val input = newName.trim()
                        pendingFolderRename = false
                        if (input.isBlank() || input == oldName) return@TextButton
                        scope.launch {
                            // 文件夹内歌曲在播：先停止（文件句柄占用会导致重命名失败）
                            val playing = PlayerManager.currentSong.value
                            if (playing != null && LocalCoverExtractor.isLocalSong(playing)) {
                                PlayerManager.stop()
                                PlayerManager.playerOrNull?.let { p ->
                                    runCatching { p.stop(); p.clearMediaItems() }
                                }
                            }
                            val newRel = withContext(Dispatchers.IO) {
                                runCatching {
                                    DownloadManager.renameFolder(path, input)
                                }.getOrNull()
                            }
                            if (newRel != null) {
                                currentPath = newRel
                                scanToast = "文件夹已重命名"
                            } else {
                                scanToast = "重命名失败（需要「所有文件访问」权限，且不能与现有文件夹重名）"
                            }
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFolderRename = false }) { Text("取消") }
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
}

/**
 * v1.4.58：文件夹行——图标 + 名称 + 音频计数 + 右箭头，
 * 样式对齐 SongRow（16dp 圆角卡片、surface 底、52dp 图标位）。
 * v1.4.59 r15：isPlaying——当前播放歌曲属于该文件夹（含子目录）时
 * 名称+图标转主色，名称前加 GraphicEq 播放标志（对齐歌单卡片样式）。
 */
@Composable
private fun FolderRow(
    name: String,
    songCount: Int,
    isPlaying: Boolean = false,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = if (isPlaying) accent else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(30.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = "正在播放",
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaying) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (songCount > 0) "$songCount 首" else "文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


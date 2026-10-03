package com.solara.music.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.MusicApi
import com.solara.music.data.Song
import com.solara.music.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.scrollBy

/**
 * 在线封面搜索限流（v1.4.0）：
 * 列表滚动时每首无封面本地歌都会触发一次搜索，必须限流防刷接口。
 * - 同一首歌一次会话只搜一次（Set 记录）
 * - 全局每 800ms 最多放行一次（时间窗）
 */
private object CoverSearchThrottle {
    private val searched = HashSet<String>()
    @Volatile private var lastAt = 0L

    fun tryAcquire(song: Song): Boolean {
        val key = "${song.source}:${song.id}"
        synchronized(searched) {
            if (key in searched) return false
            val now = System.currentTimeMillis()
            if (now - lastAt < 800) return false
            lastAt = now
            searched.add(key)
            return true
        }
    }
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

/** v1.5.1 r35：是否平台直连源码（lx 五平台）——GD API 不支持这些源的 pic 查询。 */
internal fun isLxPlatformSource(source: String): Boolean =
    source == "kw" || source == "kg" || source == "tx" || source == "wy" || source == "mg"

/**
 * 封面图：在线歌曲走聚合接口解析直链（带内存缓存）；
 * 本地歌曲（source="local"，扫描导入）走 LocalCoverExtractor 提取
 * 磁盘 URL 缓存/内嵌封面/同名图片，均无时后台在线搜索匹配（结果持久化，
 * 下次直接命中），仍无显示默认图标。
 * 观察 [LocalCoverExtractor.revision]：批量匹配封面/重命名后自动刷新。
 */
@Composable
fun CoverImage(song: Song?, size: Dp, corner: Dp = 10.dp) {
    val context = LocalContext.current
    val isLocal = song != null && LocalCoverExtractor.isLocalSong(song)
    // v1.4.0：封面数据变化（批量匹配/重命名）时触发整棵重组刷新
    val coverRev by LocalCoverExtractor.revision.collectAsState()

    // v1.4.41：用户自定义封面（「封面编辑」本地选图）——优先级最高，
    // 本地/在线歌都支持；文件在 App 专属目录，卸载即清
    var customBitmap by remember(song?.source, song?.id, coverRev) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var customChecked by remember(song?.source, song?.id, coverRev) { mutableStateOf(false) }
    if (song != null && !customChecked) {
        LaunchedEffect(song.source, song.id, coverRev) {
            customBitmap = withContext(Dispatchers.IO) {
                Store.customCoverFile(context.applicationContext, song)?.let { f ->
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    }.getOrNull()
                }
            }
            customChecked = true
        }
    }

    // 在线：封面 URL 缓存
    // v1.4.25：先读磁盘持久化（Store.onlineCoverUrl）——冷启动不再每首歌
    // 联网调 API 解析 URL，直接命中 Coil 磁盘图片缓存，离线也有封面；
    // 磁盘没有才调 API，拿到后写盘供下次使用
    // v1.4.42：remember 键加 coverRev——封面编辑应用新 URL 后 bumpRevision
    // 触发重读；否则 url 状态保留旧值，封面不刷新
    var url by remember(song?.source, song?.picId, song?.id, coverRev) {
        val s = song
        mutableStateOf(
            if (s != null && !isLocal) {
                CoverCache.get("${s.source}:${s.picId.ifBlank { s.id }}")
                    ?: Store.onlineCoverUrl(s)?.also {
                        CoverCache.put("${s.source}:${s.picId.ifBlank { s.id }}", it)
                    }
            } else null
        )
    }
    // 本地：封面 Bitmap 缓存
    var localBitmap by remember(song?.source, song?.id, coverRev) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var localChecked by remember(song?.source, song?.id, coverRev) { mutableStateOf(false) }

    if (isLocal && !localChecked && song != null) {
        LaunchedEffect(song.source, song.id, coverRev) {
            localBitmap = withContext(Dispatchers.IO) {
                LocalCoverExtractor.getCover(context.applicationContext, song)
            }
            localChecked = true
        }
    }

    // 本地歌曲无本地封面：后台在线搜索匹配（结果写 Store 持久化，限流防刷接口）
    if (isLocal && localChecked && localBitmap == null && song != null) {
        LaunchedEffect(song.source, song.id, coverRev) {
            if (!CoverSearchThrottle.tryAcquire(song)) return@LaunchedEffect
            val matched = withContext(Dispatchers.IO) {
                runCatching { LocalCoverExtractor.matchCover(song) }.getOrNull()
            }
            if (matched != null) {
                // 命中：清负缓存并刷新（getCover 现在会走 URL 缓存级）
                LocalCoverExtractor.invalidate(song)
                LocalCoverExtractor.bumpRevision()
            }
        }
    }

    if (!isLocal && url == null && song != null) {
        LaunchedEffect(song.source, song.id, song.picId) {
            // v1.4.41：有自定义封面时不再调 API
            if (customBitmap != null) return@LaunchedEffect
            // v1.5.1 r35：平台直连源码（kw/kg/tx/wy/mg）——GD API 不支持
            // 这些源的 pic 查询（实测不支持），先走平台官方接口（与 lx-music
            // 内置 musicSdk 同源），失败回落自定义源脚本
            val fetched = if (isLxPlatformSource(song.source)) {
                runCatching { com.solara.music.data.PlatformMediaApi.fetchPicUrl(song) }.getOrNull()
                    ?: runCatching {
                        com.solara.music.customsource.CustomSourceManager.getPicUrl(song)
                    }.getOrNull()
            } else {
                MusicApi.fetchPicUrl(song)
            }
            if (fetched != null) {
                val key = "${song.source}:${song.picId.ifBlank { song.id }}"
                CoverCache.put(key, fetched)
                // v1.4.25：URL 写盘持久化——下次冷启动免 API 解析
                withContext(Dispatchers.IO) { Store.saveOnlineCoverUrl(song, fetched) }
                url = fetched
            }
        }
    }

    // v1.4.25：磁盘缓存的 URL 可能过期（CDN 直链时效）——Coil 加载失败时
    // 清掉内存+磁盘缓存，重新走 API 解析拿新 URL 再试一次（每首歌最多一次，防循环）
    var retryUrl by remember(song?.source, song?.id) { mutableStateOf<String?>(null) }
    var hasRetried by remember(song?.source, song?.id) { mutableStateOf(false) }
    if (!isLocal && song != null) {
        LaunchedEffect(retryUrl) {
            if (retryUrl == null) return@LaunchedEffect
            val old = retryUrl
            retryUrl = null
            val key = "${song.source}:${song.picId.ifBlank { song.id }}"
            CoverCache.remove(key)
            withContext(Dispatchers.IO) { Store.clearOnlineCoverUrl(song) }
            val fresh = withContext(Dispatchers.IO) {
                // v1.5.1 r35：平台直连源码先走平台官方接口（与首次解析同链路）
                if (isLxPlatformSource(song.source)) {
                    runCatching { com.solara.music.data.PlatformMediaApi.fetchPicUrl(song) }.getOrNull()
                        ?: runCatching {
                            com.solara.music.customsource.CustomSourceManager.getPicUrl(song)
                        }.getOrNull()
                } else {
                    runCatching { MusicApi.fetchPicUrl(song) }.getOrNull()
                }
            }
            if (fresh != null && fresh != old) {
                CoverCache.put(key, fresh)
                withContext(Dispatchers.IO) { Store.saveOnlineCoverUrl(song, fresh) }
                url = fresh
            } else if (fresh != null && fresh == old) {
                // 同一 URL（可能只是网络抖动）：放回缓存，交给 Coil 自身重试
                CoverCache.put(key, fresh)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = if (isLocal) localBitmap else null
        when {
            // v1.4.41：自定义封面最优先（「封面编辑」用户选的图）
            customBitmap != null -> Image(
                bitmap = customBitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            url != null -> AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // v1.4.25：加载失败（URL 过期/网络断）触发一次重解析（每首歌最多一次，防循环）
                onState = { state ->
                    if (state is coil.compose.AsyncImagePainter.State.Error &&
                        song != null && !isLocal && !hasRetried
                    ) {
                        hasRetried = true
                        retryUrl = url
                    }
                }
            )
            else -> Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

internal object CoverCache {
    private val map = mutableMapOf<String, String>()
    fun get(key: String): String? = map[key]
    fun put(key: String, value: String) {
        if (map.size > 300) map.clear()
        map[key] = value
    }
    fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * 歌曲行：封面 + 标题/歌手 + 时长 + 更多操作。
 * 更多弹菜单：收藏（首位）、加入歌单、下载、移除（移除行为由所在列表定义）。
 * v1.4.26：selectionMode=true 时切换为多选行——点击整行切换勾选，
 * 左侧封面位置显示勾选框，隐藏时长/更多按钮。
 * v1.4.59：isCurrent=true 时歌名前显示播放中标记（GraphicEq 图标+主色，
 * 与播放页队列样式一致），歌名/歌手文字转主色。
 * v1.5.1 r38：收藏按钮从行尾移进更多菜单首位（与播放页一致——行更简洁，
 * 收藏是低频操作）；原收藏按钮位置改显示歌曲时长（interval "mm:ss"）。
 */
@Composable
fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDownloadLyric: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    // v1.5.1 r38：时长覆盖（本地歌 interval 恒空，由页面后台读元数据传入）
    durationOverride: String? = null,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = if (selectionMode) (onSelect ?: onClick) else onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            // 多选模式：封面位置换成勾选框（保持行高一致）
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "取消选择" else "选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp)
                )
            }
        } else {
            CoverImage(song = song, size = 52.dp, corner = 12.dp)
        }
        // v1.4.59：播放中标记（非多选模式且是当前播放歌曲时显示）
        if (!selectionMode && isCurrent) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "正在播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = song.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    selectionMode && selected -> MaterialTheme.colorScheme.primary
                    isCurrent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (!selectionMode) {
            // v1.5.1 r38：原收藏按钮位置改显示歌曲时长（interval "mm:ss"；
            // 本地歌用页面后台补全的 durationOverride）
            // v1.5.1 r39：GD 源歌 interval 恒空（GD API 搜索不返回时长），
            // 播放过的歌查已播时长表兜底（ExoPlayer duration 落盘）
            val duration = durationOverride
                ?: song.interval.ifBlank { null }
                ?: remember(song.source, song.id) {
                    Store.playedDuration(song)
                }
            if (duration != null) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
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
                    // v1.5.1 r38：收藏收进菜单首位（原行尾按钮移除——与播放页一致）
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                        leadingIcon = {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                null,
                                tint = if (isFavorite) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { menuOpen = false; onToggleFavorite() }
                    )
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("加入歌单") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                            },
                            onClick = { menuOpen = false; onAddToPlaylist() }
                        )
                    }
                    if (onDownload != null) {
                        DropdownMenuItem(
                            text = { Text("下载") },
                            leadingIcon = { Icon(Icons.Filled.Download, null) },
                            onClick = { menuOpen = false; onDownload() }
                        )
                    }
                    if (onDownloadLyric != null) {
                        DropdownMenuItem(
                            text = { Text("下载歌词") },
                            leadingIcon = {
                                Icon(Icons.Filled.Lyrics, null)
                            },
                            onClick = { menuOpen = false; onDownloadLyric() }
                        )
                    }
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                            onClick = { menuOpen = false; onRename() }
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("移除") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menuOpen = false; onRemove() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * LazyColumn 长按拖动排序状态：拖动条目越过相邻条目时交换位置。
 * [onMove] 返回 false 表示移动无效（如下标越界），此时不更新拖动状态。
 *
 * v1.5.1 r49：边缘自动滚动——拖到视口上下边缘时列表按深度线性加速滚动。
 * v1.5.1 r51 修三个 bug：
 * ① 补偿用 scrollBy 的**实际返回值**（r49/r50 用期望值——列表到边界后
 *   实际滚动 0 但 dragOffset 仍累加 → 条目 translationY 持续漂移飞出屏幕，
 *   这就是「拖到边缘就不见」「第一首上滑消失」的根因）；
 * ② 边缘判定改用**手指位置**（r49/r50 用条目顶/底——第一首歌 top≈0
 *   天然在上边缘区内，长按即触发向上滚；手指位置才是用户意图）；
 * ③ 自动滚动每轮滚动后立即做交换判定（r49/r50 只在 onDrag 手势回调里
 *   交换——手指按住不动时列表滚了但顺序不变，拖动条目布局位置滚出
 *   视口还会导致组件销毁、拖动中断）。
 */
class DragReorderState(
    private val listState: LazyListState,
    private val onMove: (Int, Int) -> Boolean
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** 手指在 LazyColumn 视口内的 Y（边缘滚动判定用）。 */
    private var pointerY: Float? = null

    /** 自动滚动协程（每帧滚动一次，速度随边缘深度线性提升）。 */
    private var autoScrollJob: Job? = null

    /** [yInItem] = 手指相对条目组件的 Y，换算成视口坐标。 */
    fun onStart(index: Int, yInItem: Float) {
        draggingIndex = index
        dragOffset = 0f
        pointerY = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.let { it.offset + yInItem }
    }

    fun onDrag(delta: Float) {
        if (draggingIndex == null) return
        dragOffset += delta
        pointerY = pointerY?.plus(delta)
        checkSwap()
    }

    /**
     * 交换判定（手势拖动用）。
     *
     * r52：视觉中心落在哪个可见条目上就与哪个交换（单步约束保持
     * 逐条手感）。自动滚动不走此路径——见 [startAutoScroll] 的
     * 「数据搬移+滚动同步」方案。
     */
    private fun checkSwap() {
        val current = draggingIndex ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val currInfo = visible.firstOrNull { it.index == current } ?: return
        val visualCenter = currInfo.offset + dragOffset + currInfo.size / 2f
        val hit = visible.firstOrNull { vi ->
            visualCenter >= vi.offset && visualCenter < vi.offset + vi.size
        }
        if (hit == null || hit.index == current) return
        val target = if (hit.index > current) current + 1 else current - 1
        val targetInfo = visible.firstOrNull { it.index == target } ?: return
        if (onMove(current, target)) {
            draggingIndex = target
            dragOffset = currInfo.offset + dragOffset - targetInfo.offset
        }
    }

    fun onEnd() {
        draggingIndex = null
        dragOffset = 0f
        pointerY = null
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    /**
     * 拖动开始时启动边缘自动滚动协程（delay(16) 轮询，~60fps，
     * r50：不用 withFrameNanos——裸协程上下文没有组合帧时钟会崩）。
     *
     * r52「数据搬移+滚动同步」方案（根治「滚几行就停」）：
     * 旧方案（滚动+补偿+checkSwap）的死结——自动滚动时所有条目
     * 一起移动，拖动条目与相邻条目相对位置不变，交换判定永不触发；
     * 条目布局位置随内容滚出视口被 LazyColumn 回收 → 手势协程
     * 取消 → 滚动中断。
     *
     * 新方案：每滚过一个条目步长就「数据搬移一格 + 滚动回退一格」——
     * 搬移使拖动条目在数据中前移一格（布局位置前移一格），滚动回退
     * 使所有条目回到原布局位置——**拖动条目布局位置不变**（视觉也
     * 不变），但它「穿过」了一个条目（数据顺序变了）。条目永不滚出
     * 视口，手势持续，列表内容持续滚动。
     */
    internal fun startAutoScroll() {
        if (autoScrollJob != null) return
        autoScrollJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            // 累积滚动量，每过一个条目步长搬移一次
            var accumulated = 0f
            while (isActive) {
                delay(16)
                val fingerY = pointerY ?: continue
                val viewportBottom = listState.layoutInfo.viewportSize.height.toFloat()
                val edgeZone = 96f
                val maxSpeed = 22f
                val upSpeed = if (fingerY < edgeZone) {
                    ((edgeZone - fingerY) / edgeZone).coerceIn(0f, 1f) * maxSpeed
                } else 0f
                val downSpeed = if (fingerY > viewportBottom - edgeZone) {
                    ((fingerY - (viewportBottom - edgeZone)) / edgeZone).coerceIn(0f, 1f) * maxSpeed
                } else 0f
                val expected = downSpeed - upSpeed
                // r52：列表已在顶/底时禁用对应方向滚动
                val canScrollUp = listState.canScrollBackward
                val canScrollDown = listState.canScrollForward
                val effective = when {
                    expected < 0f && !canScrollUp -> 0f
                    expected > 0f && !canScrollDown -> 0f
                    else -> expected
                }
                if (effective != 0f) {
                    try {
                        val current = draggingIndex
                        if (current == null) continue
                        // 条目步长 = 相邻可见条目间距（含 spacing）
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val currInfo = visible.firstOrNull { it.index == current }
                        val nextInfo = visible.firstOrNull { it.index == current + 1 }
                        val prevInfo = visible.firstOrNull { it.index == current - 1 }
                        val stepDown = nextInfo?.let { (it.offset - currInfo!!.offset).toFloat() }
                        val stepUp = prevInfo?.let { (currInfo!!.offset - it.offset).toFloat() }
                        val actual = listState.scrollBy(effective)
                        accumulated += actual
                        // 向下滚（内容上移）：每累积一个步长，把拖动条目在数据中下移一格，
                        // 同时把列表滚回一格（布局位置复原）——条目「穿过」下方条目
                        if (accumulated > 0f && stepDown != null && accumulated >= stepDown) {
                            if (onMove(current, current + 1)) {
                                draggingIndex = current + 1
                                // 搬移后条目布局位置前移 stepDown，滚回 stepDown 复原：
                                listState.scrollBy(-stepDown)
                                accumulated -= stepDown
                            } else accumulated = 0f
                        }
                        // 向上滚：对称
                        else if (accumulated < 0f && stepUp != null && -accumulated >= stepUp) {
                            if (onMove(current, current - 1)) {
                                draggingIndex = current - 1
                                listState.scrollBy(stepUp)
                                accumulated += stepUp
                            } else accumulated = 0f
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}

@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    onMove: (Int, Int) -> Boolean
): DragReorderState = remember(listState) { DragReorderState(listState, onMove) }

/**
 * 列表条目的长按拖动排序修饰符：拖动中的条目置顶绘制并跟随手指平移。
 * [index] 必须是该条目在 LazyColumn 中的绝对下标（包含非歌曲条目时需自行偏移）。
 * v1.5.1 r49：拖动期间启动边缘自动滚动；r51：手指位置传入边缘判定。
 */
@Composable
fun Modifier.dragReorder(dragState: DragReorderState, index: Int): Modifier {
    val view = LocalView.current
    // pointerInput(Unit) 的协程不会因 index 变化重启，必须经 State 读最新值，
    // 否则交换位置后会拿到过期下标
    val currentIndex by rememberUpdatedState(index)
    return this
        .zIndex(if (dragState.draggingIndex == index) 1f else 0f)
        .graphicsLayer {
            if (dragState.draggingIndex == index) {
                translationY = dragState.dragOffset
            }
        }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    // r51：手指在条目内的 Y → onStart 换算视口坐标做边缘判定
                    dragState.onStart(currentIndex, offset.y)
                    dragState.startAutoScroll()
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragState.onDrag(amount.y)
                },
                onDragEnd = {
                    dragState.onEnd()
                },
                onDragCancel = {
                    dragState.onEnd()
                }
            )
        }
}

@Composable
fun EmptyState(hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

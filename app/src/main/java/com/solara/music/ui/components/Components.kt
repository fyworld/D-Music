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
 * v1.5.1 r56 修 r55 真机「滚 4-5 行就停」：
 * ① 交换后 dragOffset 未减回步长——滚动补偿使 dragOffset 逐帧累积，
 *   交换使布局净 0，视觉 = 布局 + dragOffset 每次交换跳一个步长、
 *   持续漂移（模拟器纯按住无 onDrag 不暴露，真机微抖必现）；
 * ② 漂移几百 px 的视觉位置被微抖触发的 onDrag→checkSwap 拿去判定，
 *   hit 到远处错误条目 → 错误交换 + dragOffset 重算 → 布局乱跳 →
 *   组件滚出视口被回收 → dragCancel。4-5 行 ≈ 漂移到视觉中心飞出
 *   条目区域所需的交换次数。修复 = 交换后 dragOffset 减回步长（视觉
 *   严格跟手，任何路径都不漂移）+ 自动滚动活跃期间关闭视觉判定
 *   （换位唯一由累积滚动量驱动，两路径彻底隔离）。
 * v1.5.1 r56b 补两个真机根因（模拟器日志实证）：
 * ③ 手势路径 checkSwap 连续换位使布局逐次 ±stride——拖到视口边缘
 *   外时布局累计越界 → 条目完全滚出视口 → LazyColumn 回收组件 →
 *   dragCancel（「拖到顶部边缘过程中手势就断」）。修复 = 换位后
 *   syncScrollAfterSwap 把新条目拉回视口（拉回量补偿进 dragOffset，
 *   不计入 scrollAccum）；
 * ④ onDragCancel 不区分条目——滚动时**任意**被回收条目的 detector
 *   CANCELLED 都会调 onEnd() 误杀进行中的拖动（非拖动条目被回收
 *   是滚动常态！）。修复 = 只有 draggingIndex == currentIndex 时才
 *   onEnd；onDragEnd 同理放宽（UP 可能落在非拖动条目上）。
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

    /**
     * r55：自动滚动期间的累积滚动量（自上次交换起）。
     * 滚动让所有条目布局一起移动，但拖动条目与相邻条目**相对位置不变**
     * ——视觉/位置判定在自动滚动下永不触发交换（r54 真机实证）。改用
     * 纯滚动量驱动：每累积一个条目步长就换位一格。
     */
    private var scrollAccum = 0f

    /** r55：条目步长（含间距），onStart 时从布局信息取。 */
    private var itemStride = 136f

    /**
     * r56：自动滚动是否活跃（最近 [AUTO_SCROLL_ACTIVE_WINDOW_MS] 内
     * 有实际滚动量）。活跃期间 onDrag 不做视觉换位判定——换位唯一由
     * 累积滚动量驱动（两路径隔离，微抖不再用漂移位置错误换位）。
     */
    private var lastAutoScrollNanos = 0L

    private companion object {
        /** r56：自动滚动活跃窗口——超时视为回到手势模式。 */
        const val AUTO_SCROLL_ACTIVE_WINDOW_MS = 120L
    }

    /** [yInItem] = 手指相对条目组件的 Y，换算成视口坐标。 */
    fun onStart(index: Int, yInItem: Float) {
        draggingIndex = index
        dragOffset = 0f
        scrollAccum = 0f
        pointerY = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.let { it.offset + yInItem }
        // r55：条目步长 = 下一可见条目与本条目的 offset 差（含间距）
        val infos = listState.layoutInfo.visibleItemsInfo.sortedBy { it.index }
        val self = infos.indexOfFirst { it.index == index }
        if (self in 0 until infos.size - 1) {
            itemStride = (infos[self + 1].offset - infos[self].offset).toFloat()
        }
    }

    fun onDrag(delta: Float) {
        if (draggingIndex == null) return
        dragOffset += delta
        pointerY = pointerY?.plus(delta)
        // r56：自动滚动活跃期间关闭视觉判定——换位唯一由累积滚动量
        // 驱动。微抖的 ±1px 不再被拿去判定（r55 漂移位置错误换位根因）
        if (!isAutoScrollActive()) checkSwap()
    }

    /** r56：最近一次实际滚动距今是否在活跃窗口内。 */
    private fun isAutoScrollActive(): Boolean =
        lastAutoScrollNanos != 0L &&
            System.nanoTime() - lastAutoScrollNanos < AUTO_SCROLL_ACTIVE_WINDOW_MS * 1_000_000L

    /**
     * 交换判定（手势拖动路径专用）。
     *
     * 视觉中心落在哪个可见条目上就与哪个交换（单步约束保持逐条
     * 手感）。手指在视口内移动时新条目天然可见，无需同步滚动。
     * 自动滚动路径**不走这里**——见 [autoScrollSwap]（r55）。
     *
     * r56b：换位后同步滚动防回收。向上/向下连续换位使布局逐次
     * ±stride——拖到视口边缘外时（手指在视口坐标 <0 或 >viewport），
     * 布局累计偏移使条目完全滚出视口（offset+size<0 或 offset>vp）
     * → LazyColumn 回收组件 → pointerInput 协程取消 → dragCancel
     * （真机「拖到顶部边缘过程中手势就断」根因，r54 只修了自动滚动
     * 路径）。修复：换位后若新条目布局越界，scrollBy 拉回安全区并
     * 把实际滚动量补偿进 dragOffset（视觉不变、布局回视口）。
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
            // r56b：换位后新条目布局越界 → 同步滚动拉回（防回收）
            syncScrollAfterSwap(target)
        }
    }

    /**
     * r56b：换位后同步滚动——新条目布局滚出视口时把它拉回。
     * scrollBy(正)=向下滚（offset 减小），scrollBy(负)=向上滚（offset 增大）。
     * 顶部越界（offset<0）→ 向上滚拉回：scrollBy(offset)（负值）。
     * 底部越界（offset+size>vp）→ 向下滚拉回：scrollBy(vp-size-offset)（正值）。
     * 实际滚动量补偿进 dragOffset 保持视觉不变；滚动量也计入
     * scrollAccum（真实发生了滚动）。
     */
    private fun syncScrollAfterSwap(target: Int) {
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == target } ?: return
        val viewportH = listState.layoutInfo.viewportSize.height
        val pullBack = when {
            info.offset < 0 -> info.offset.toFloat()  // 负值=向上滚
            info.offset + info.size > viewportH ->
                (viewportH - info.size - info.offset).toFloat()  // 正值=向下滚
            else -> return
        }
        if (pullBack == 0f) return
        // scrollBy 是 suspend——在 Main 协程里执行（与 startAutoScroll 同模式）
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val actual = try { listState.scrollBy(pullBack) } catch (_: Exception) { return@launch }
            if (actual != 0f && draggingIndex != null) {
                // 拉回量补偿进 dragOffset（视觉不变）。不计入 scrollAccum——
                // 这是布局修正不是内容滚动，不能驱动换位判定（否则过度换位）
                dragOffset += actual
            }
        }
    }

    /**
     * r55：自动滚动期间的换位判定——纯滚动量驱动。
     *
     * 每帧滚动后调用：累积滚动量，|累积| ≥ 一个条目步长就换位一格并
     * 减去步长。数学（向下滚为例，滚动量 delta 为正）：
     * - 滚动 +stride：所有条目布局 -stride（含拖动条目），dragOffset
     *   补偿 +stride → 视觉跟手；
     * - 交换（current → current+1）：数据搬移使拖动条目组件移到原
     *   target 位置——布局 +stride，与滚动抵消 → 拖动条目布局位置
     *   不变（永不滚出视口、组件不被回收、手势持续）；
     * - **r56 修正**：r55 注释声称「布局天然抵消故 dragOffset 不变」
     *   是错的——布局净 0 但 dragOffset 已 +stride（滚动补偿），
     *   视觉 = 布局 + dragOffset 每次交换跳 +stride、持续漂移。
     *   交换后必须 dragOffset -= step * stride：布局 +stride 与
     *   dragOffset -stride 抵消 → 视觉严格跟手、任何路径不漂移。
     * 判定纯滚动驱动，**对真机手指微抖完全免疫**（r54 的视觉判定在
     * 补偿下数学死锁：视觉中心永远落在拖动条目自己身上，永不交换）。
     */
    private fun autoScrollSwap(scrollDelta: Float) {
        scrollAccum += scrollDelta
        var current = draggingIndex ?: return
        while (kotlin.math.abs(scrollAccum) >= itemStride) {
            val step = if (scrollAccum > 0) 1 else -1
            val target = current + step
            if (target < 0 || !onMove(current, target)) {
                // 到列表头/尾或移动失败：清空累积，停止换位（滚动继续到边界）
                scrollAccum = 0f
                return
            }
            draggingIndex = target
            current = target
            scrollAccum -= step * itemStride
            // r56：交换使布局 +stride（数据搬移把拖动条目组件移到原
            // target 位置），dragOffset 减回 stride 才能保持视觉不变。
            // r55 漏了这一步 → dragOffset 逐交换漂移 → 真机 4-5 行后
            // 微抖触发 checkSwap 用漂移视觉错误换位 → 布局乱跳 →
            // 组件回收 → dragCancel
            dragOffset -= step * itemStride
        }
    }

    fun onEnd() {
        draggingIndex = null
        dragOffset = 0f
        pointerY = null
        scrollAccum = 0f
        lastAutoScrollNanos = 0L
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    /**
     * 拖动开始时启动边缘自动滚动协程（delay(16) 轮询，~60fps，
     * r50：不用 withFrameNanos——裸协程上下文没有组合帧时钟会崩）。
     *
     * r55 方案（累积滚动量驱动换位）：
     * - 每帧滚动后把实际滚动量补偿进 dragOffset——拖动条目**视觉位置
     *   固定在手指下**（跟手）；
     * - 每累积一个条目步长（136px）就换位一格——滚动让内容滚上来，
     *   数据搬移让拖动条目「穿过」被滚过的条目；
     * - 布局天然抵消：滚动 -stride + 交换 +stride = 0——拖动条目布局
     *   位置不变（**永不滚出视口**，组件不被回收、手势不取消）；
     *
     * 历史教训（r49-r56 七轮迭代）：
     * - r52：视觉/相对位置判定在自动滚动下永不触发（所有条目一起移动，
     *   相对位置不变）；
     * - r53：删补偿后视觉漂移，真机微抖触发 onDrag→checkSwap 用漂移
     *   位置判定，交换方向混乱；
     * - r54：恢复补偿 + hit 判定——数学死锁：补偿使视觉位置固定，视觉
     *   中心相对拖动条目布局的偏移也固定，**永远落在自己身上**，永不
     *   交换（模拟器 ±2px 抖动恰好跨界是假象，真机 ±1px 在条目中部
     *   永不跨界）→ 无同步滚动 → 布局滚出视口 → 回收 → dragCancel；
     * - r55：累积滚动量驱动换位（判定对微抖免疫），但交换后 dragOffset
     *   未减回步长 → 逐交换漂移 → 真机微抖触发 checkSwap 用漂移视觉
     *   错误换位 → 布局乱跳 → 回收 → dragCancel（滚 4-5 行就停）；
     * - r56：交换后 dragOffset -= step*stride（视觉严格跟手）+ 自动滚动
     *   活跃窗口内关闭 onDrag 视觉判定（两路径隔离，微抖只更新
     *   pointerY/dragOffset，不参与换位）。
     */
    internal fun startAutoScroll() {
        if (autoScrollJob != null) return
        autoScrollJob = CoroutineScope(Dispatchers.Main.immediate).launch {
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
                // 列表已在顶/底时禁用对应方向滚动
                val canScrollUp = listState.canScrollBackward
                val canScrollDown = listState.canScrollForward
                val effective = when {
                    expected < 0f && !canScrollUp -> 0f
                    expected > 0f && !canScrollDown -> 0f
                    else -> expected
                }
                if (effective != 0f) {
                    try {
                        val actual = listState.scrollBy(effective)
                        if (actual != 0f) {
                            // r54：补偿实际滚动量——视觉跟手（r53 删补偿是错的）
                            dragOffset += actual
                            // r56：记录滚动时刻——活跃窗口内 onDrag 不做
                            // 视觉判定（两路径隔离）
                            lastAutoScrollNanos = System.nanoTime()
                            // r55：累积滚动量驱动换位（纯滚动驱动，
                            // 对微抖免疫；r56：交换后 dragOffset 减回步长）
                            autoScrollSwap(actual)
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
                    // r56b：手指只有一个——任何条目收到 UP 都意味着松手，
                    // 结束拖动（UP 可能落在非拖动条目上——拖动条目已换位）
                    if (dragState.draggingIndex != null) dragState.onEnd()
                },
                onDragCancel = {
                    // r56b：组件回收导致的 CANCELLED 来自任意条目（滚动时
                    // 非拖动条目也会被回收）——只有拖动条目自己被取消才
                    // 真正终止拖动，否则误杀进行中的拖动（真机「拖几行就
                    // 停」的另一半根因）
                    if (dragState.draggingIndex == currentIndex) dragState.onEnd()
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

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
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.solara.music.ui.theme.LocalUiScaleFactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
fun CoverImage(
    song: Song?,
    size: Dp,
    corner: Dp = 10.dp,
    // v1.5.1 r69：true = 尺寸反向补偿界面缩放（dp ÷ factor），像素恒定
    // 不随「界面大小」设置变化——播放页大封面是视觉锚点，缩放后不好看
    keepPixelSize: Boolean = false
) {
    val context = LocalContext.current
    // v1.5.1 r69：反向补偿后的实际渲染尺寸
    val uiScaleFactor = if (keepPixelSize) LocalUiScaleFactor.current else 1.0f
    val renderSize = if (keepPixelSize && uiScaleFactor != 1.0f) size / uiScaleFactor else size
    val renderCorner = if (keepPixelSize && uiScaleFactor != 1.0f) corner / uiScaleFactor else corner
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
            .size(renderSize)
            .clip(RoundedCornerShape(renderCorner))
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
                modifier = Modifier.size(renderSize / 2)
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
 * 更多弹菜单：收藏（首位）、加入歌单、置顶、置底、下载、移除（移除行为由所在列表定义）。
 * v1.4.26：selectionMode=true 时切换为多选行——点击整行切换勾选，
 * 左侧封面位置显示勾选框，隐藏时长/更多按钮。
 * v1.4.59：isCurrent=true 时歌名前显示播放中标记（GraphicEq 图标+主色，
 * 与播放页队列样式一致），歌名/歌手文字转主色。
 * v1.5.1 r38：收藏按钮从行尾移进更多菜单首位（与播放页一致——行更简洁，
 * 收藏是低频操作）；原收藏按钮位置改显示歌曲时长（interval "mm:ss"）。
 * v1.5.1 r58：置顶/置底菜单项（加在加入歌单后面）——仅对有顺序语义
 * 的列表（歌单详情）传入回调显示；收藏/最近播放是时间序，不传即隐藏。
 */
@Composable
fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onMoveToTop: (() -> Unit)? = null,
    onMoveToBottom: (() -> Unit)? = null,
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
                    // v1.5.1 r58：置顶/置底（仅歌单详情等有顺序语义的列表传入）
                    if (onMoveToTop != null) {
                        DropdownMenuItem(
                            text = { Text("置顶") },
                            leadingIcon = {
                                Icon(Icons.Filled.KeyboardDoubleArrowUp, null)
                            },
                            onClick = { menuOpen = false; onMoveToTop() }
                        )
                    }
                    if (onMoveToBottom != null) {
                        DropdownMenuItem(
                            text = { Text("置底") },
                            leadingIcon = {
                                Icon(Icons.Filled.KeyboardDoubleArrowDown, null)
                            },
                            onClick = { menuOpen = false; onMoveToBottom() }
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
 * ④ onDragCancel 不区分条目——滚动时**任意**被回收条目的 detector
 *   CANCELLED 都会调 onEnd() 误杀进行中的拖动（非拖动条目被回收
 *   是滚动常态！）。修复 = 只有 draggingIndex == currentIndex 时才
 *   onEnd；onDragEnd 同理放宽（UP 可能落在非拖动条目上）。
 * v1.5.1 r57 修 r56 真机「松手后列表失控滚动 + 条目重叠空白」：
 * ⑤ r56b 的 syncScrollAfterSwap 用独立 launch 启动协程——**不受
 *   autoScrollJob 管理**，拖动期间多次换位排队多个拉回协程，松手
 *   后继续 scrollBy → 列表持续滚动（「放开手指列表自动滚动，按别
 *   的条目才停」）；且松手后 draggingIndex=null 使补偿分支失效 →
 *   dragOffset 不再补偿 → 条目视觉错乱（重叠）+ 滚动越界（空白）。
 *   修复 = 整体删除（其防护前提本身不成立：手势换位后新条目布局
 *   = hit 条目的原位置，必然可见，不会越界回收）；
 * ⑥ 松手瞬间 dragOffset 硬归零 → 条目跳变。修复 = onEnd 启动
 *   settleJob 落位动画（~120ms dragOffset 平滑归零），onStart 时
 *   cancel（新拖动立即接管）。
 * v1.5.1 r57b（用户方案）：拖动条目视觉位置 clamp 在视口内——
 *   拖到最前/最后可见条目位置条目被钉住（完整可见），手指继续
 *   下探进入边缘区驱动列表滚动，条目持续换位但**永不出界**。
 *   与换位/滚动补偿自洽：clamp 界随 slot 平移同步移动。
 * v1.5.1 r61 修「拖到最前条目消失」（所有拖动列表）：
 *   LazyColumn 滚动锚定（updateScrollPositionIfTheFirstItemWasMoved，
 *   r59b 同源）只跟踪第一可见项的 key——拖动条目与第一可见项换位
 *   时，锚定把视口拉到原第一可见项的新 index（视口内容不变），拖动
 *   条目被推到视口上方**不被组合**→ pointerInput 销毁 → 手势死亡 →
 *   条目「跳到最前条目前面」看不到、列表不滚动。r60-3 给所有列表
 *   补了稳定 key（修手势回收 bug）反而使锚定得以触发——两个 bug
 *   此消彼长。修复 = 每次换位后立刻 scrollToItem 钉回换位前的视口
 *   位置（见 pinViewportAfterSwap）：清除锚定 key，下一帧布局槽位
 *   不变，checkSwap/autoScrollSwap 的 dragOffset 补偿公式假设
 *   （换位后新槽 offset = target 原槽 offset）重新成立。
 */
/**
 * LazyColumn 长按拖动排序状态（r62 视觉让位架构，iOS 式）。
 *
 * **核心原则：拖动中数据完全不动**——所有视觉效果（拖动条目跟手、
 * 其他条目让位平移）全部用 graphicsLayer.translationY 表达，LazyColumn
 * 不重组、锚定不触发、组件不回收。松手才一次性 onMove(from, to)
 * 提交真实换位。
 *
 * 为什么重写（r49-r61b 十四轮迭代教训）：
 * - 旧架构「拖动中就换位数据」把 LazyColumn 的重组、滚动锚定
 *   （updateScrollPositionIfTheFirstItemWasMoved 只跟踪第一可见项
 *   key）、组件回收全部卷入拖动循环——r52 无 key 手势死亡、r55/r56
 *   补偿漂移、r59b 锚定跳变、r61 拖到最前条目消失（锚定拉走视口→
 *   条目出视口不被组合→pointerInput 销毁→手势死亡）层层补丁互相
 *   打架：修 A 触发 B（r60-3 补 key 修手势回收反而使锚定得以触发）。
 * - 视觉让位架构从根上消除这些耦合：数据不动 → 无重组 → 无锚定 →
 *   无回收 → 手势永不死亡；无换位补偿数学（checkSwap/autoScrollSwap/
 *   dragOffset 补偿公式全部删除）。
 *
 * 让位数学：拖动条目 D（原 index=d）视觉中心 vs 条目 i 中心——
 * i 在 D 上方（i<d）且 D 视觉中心越过 i 中心 → i 下移让位（+stride）；
 * i 在 D 下方（i>d）且 D 视觉中心越过 i 中心（向上）→ i 上移让位
 * （-stride）。targetIndex = d + 下方让位数 - 上方让位数。
 * 松手：onMove(d, targetIndex) 一次性提交 + 让位/拖动偏移归零动画。
 */
class DragReorderState(
    private val listState: LazyListState,
    private val onMove: (Int, Int) -> Boolean,
    /** Composable 作用域（rememberCoroutineScope）——带 MonotonicFrameClock，
     *  Animatable.animateTo 必须在帧时钟上下文里跑（r62 崩溃修复：
     *  裸 CoroutineScope(Dispatchers.Main) 无帧时钟 →
     *  IllegalStateException: A MonotonicFrameClock is not available）。 */
    private val scope: CoroutineScope,
    /**
     * r63d：可拖动条目区间（含端点）——让位判定/targetIndex 只在此
     * 区间内计数。默认 null = [0, totalItemsCount)（含尾部 Spacer，
     * r62 行为）。各页应显式传入排除非歌曲条目：
     * - 普通页（歌曲 + 尾部 Spacer）：0 until count——否则拖到底
     *   targetIndex 落在 Spacer 下标 → onMove 越界失败 → 视觉回弹；
     * - 本地歌曲页（头部文件夹 + 歌曲 + 尾部 Spacer）：
     *   folderCount until folderCount+songs——否则拖到顶 targetIndex
     *   落进文件夹区，同样越界回弹。
     * lambda 每次调用现读（页面数据是 State 委托，捕获不过期）。
     */
    private val dragRange: (() -> IntRange)? = null
) {
    /** 拖动中的条目原下标（数据不动，此值拖动全程不变）。 */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** 拖动条目的视觉偏移（graphicsLayer.translationY）。 */
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** 松手落位动画中的条目原下标。 */
    var settlingIndex by mutableStateOf<Int?>(null)
        private set

    /** 拖动条目实时目标下标（松手提交用）。 */
    var targetIndex by mutableStateOf(0)
        private set

    /** 手指在视口内的 Y（边缘滚动判定用）。 */
    private var pointerY: Float? = null

    /** 自动滚动协程。 */
    private var autoScrollJob: Job? = null

    /** 落位动画协程。 */
    private var settleJob: Job? = null

    /** 条目步长（含间距），onStart 时取。 */
    private var itemStride = 136f

    /** 拖动条目行高（不含间距），onStart 时取——r63d 视口 clamp 基准。 */
    private var itemSize = 128f

    /** 拖动条目原布局 top（视口坐标快照）——r63d 视口 clamp 基准。 */
    private var dragBaseTopY = 0f

    /** 累积自动滚动量（scrollBy 实际值之和）——dragOffset 含此补偿，
     *  手势位移 = dragOffset - scrollAccum（clamp 作用在手势位移上）。 */
    private var scrollAccum = 0f

    /** 让位映射：条目下标 → 让位动画（0 → ±stride）。 */
    private val shifts = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()

    /** r63d：提交后让位条目的平移补偿（新下标 → ±stride）。提交后
     *  布局槽位平移了一个 stride，translation 加回补偿量才视觉连续
     *  （Animatable 继续原 tween 趋向 ±stride，加补偿后恰好归零）。 */
    private val settleAdjust = mutableMapOf<Int, Float>()

    /**
     * r65：拖动条目的视觉快照（overlay 渲染用）。
     * 根因：r62 架构下拖动中数据不动，槽位冻结在原下标——自动滚动
     * 时槽位滚出组合缓存区 → 条目组件被回收 → translationY 无处
     * 渲染 → **条目消失**（r64 修了手势死亡，渲染仍依赖组件存在）。
     * 修复（iOS drag preview 同构）：onStart 时截图，拖动期间 overlay
     * 显示快照跟手（快照永不回收），原条目 alpha=0；松手清除快照，
     * 条目落位动画接管。
     */
    var dragSnapshot by mutableStateOf<android.graphics.Bitmap?>(null)
        private set

    /** r65：快照渲染的屏幕 top（视口坐标，含 clamp 后的 dragOffset）。 */
    var snapshotTopY by mutableFloatStateOf(0f)
        private set

    /** r65：快照渲染的左边界（视口坐标，onStart 时取列表内容左界）。 */
    var snapshotLeftX by mutableFloatStateOf(0f)
        private set

    /** r65：快照宽度（onStart 时取条目宽）。 */
    var snapshotWidth by mutableFloatStateOf(0f)
        private set

    /**
     * r65b：截图启动器（onStart 时由容器调用）——**异步**截图，
     * 完成后回调 [onSnapshotReady]。
     * r65 首版用 View.draw 同步截图：Compose 内容经 RenderNode
     * 硬件层渲染，画到软件 Canvas 是**空白**（drawRenderNode 软件画
     * 布不支持）→ 快照全透明 → overlay 无内容 + 条目 alpha=0 →
     * 「长按条目直接消失」。r65b 改 PixelCopy（API 26+）读窗口硬件
     * 帧，内容保真；截图就绪前条目自渲染兜底（dragReorderItem），
     * 就绪后无缝切换——任何失败路径条目都不会凭空消失。
     */
    internal var snapshotProvider: ((index: Int) -> Unit)? = null

    /** r65b：截图完成回调（PixelCopy listener 主线程回调）。 */
    internal fun onSnapshotReady(index: Int, bmp: android.graphics.Bitmap?) {
        if (draggingIndex == index && bmp != null) dragSnapshot = bmp
    }

    fun onStart(index: Int, yInItem: Float) {
        settleJob?.cancel()
        settleJob = null
        settlingIndex = null
        shifts.clear()
        draggingIndex = index
        targetIndex = index
        dragOffset = 0f
        scrollAccum = 0f
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index } ?: return
        pointerY = info.offset + yInItem
        dragBaseTopY = info.offset.toFloat()
        itemSize = info.size.toFloat()
        val infos = listState.layoutInfo.visibleItemsInfo.sortedBy { it.index }
        val self = infos.indexOfFirst { it.index == index }
        if (self in 0 until infos.size - 1) {
            itemStride = (infos[self + 1].offset - infos[self].offset).toFloat()
        }
        // r65b：截图 + 快照几何（overlay 渲染，条目回收不再导致消失）。
        // 条目都是 fillMaxWidth——快照全宽，left=0，width=视口宽
        snapshotTopY = dragBaseTopY
        snapshotLeftX = 0f
        snapshotWidth = listState.layoutInfo.viewportSize.width.toFloat()
        dragSnapshot = null
        snapshotProvider?.invoke(index)
    }

    /**
     * 手势位移（不含自动滚动补偿）——r63d 视口 clamp 判定用。
     * dragOffset = 手势位移 + scrollAccum（滚动补偿让条目视觉跟手）。
     * 拖动条目屏幕视觉 top = dragBaseTopY + gestureOffset（滚动补偿
     * 与 base 随视口的移动相互抵消）——clamp 作用在 gesture 上即
     * 条目屏幕位置 clamp 在视口内，且界不随滚动变化。
     */
    private val gestureOffset: Float get() = dragOffset - scrollAccum

    fun onDrag(delta: Float) {
        if (draggingIndex == null) return
        // r63d：视口边界 clamp——拖动条目整体限制在可视区域内
        //（上界=列表区顶部，下界=视口底=播放栏顶）。拖到边界顶住，
        // 手指继续下探进入边缘区驱动列表滚动，条目持续换位但永不出界。
        // clamp 只作用于视觉位移（dragOffset），pointerY 始终跟手指
        // 真实位置（否则 clamp 顶住后 pointerY 冻结在边缘区外，
        // 自动滚动失效——r63 问题2 根因）。
        val viewportH = listState.layoutInfo.viewportSize.height.toFloat()
        val minGesture = -dragBaseTopY
        val maxGesture = viewportH - dragBaseTopY - itemSize
        val newGesture = (gestureOffset + delta).coerceIn(minGesture, maxGesture)
        dragOffset += newGesture - gestureOffset
        pointerY = pointerY?.plus(delta)
        // r65：快照跟手（条目屏幕视觉 top = dragBaseTopY + gestureOffset）
        snapshotTopY = dragBaseTopY + newGesture
        updateShifts()
    }

    /**
     * 让位判定（r63d：dragOffset 基准 + 非严格不等式）。
     * 判定量 = dragOffset（含滚动补偿）：i 的布局位置随滚动移动，
     * dragOffset 的补偿分量与 base 随视口的移动相互抵消——
     * dragOffset - (i-d)*stride 恒等于「拖动条目中心与 i 原中心的
     * 屏幕真实距离」。自动滚动时这个距离持续变化（i 们移动、条目
     * 顶在视口边界），让位状态随滚动持续更新——插入位置才会
     * 持续变化（r63 用 gestureOffset 判定在顶住后冻结，是误判）。
     * 统一式：i 让位 ⟺ (dragOffset - (i-d)*stride) 与 (i-d) 同号。
     * **必须 ≥（非严格）**：滚到头时拖动条目中心与首/末行中心恰好
     * 相等（临界）——严格 > 会差一行（拖到底松手变倒数第二）。
     * targetIndex = d - shiftDown + shiftUp。
     */
    private fun updateShifts() {
        val d = draggingIndex ?: return
        val range = dragRange?.invoke()
            ?: (0 until listState.layoutInfo.totalItemsCount)
        val g = dragOffset
        var shiftDown = 0
        var shiftUp = 0
        for (i in range) {
            if (i == d) continue
            val rel = i - d
            val shouldShift = (g - rel * itemStride) * rel >= 0f
            if (shouldShift && !shifts.containsKey(i)) {
                val dir = if (rel < 0) 1 else -1
                val anim = Animatable(0f)
                shifts[i] = anim
                scope.launch {
                    anim.animateTo(dir * itemStride, tween(120))
                }
            } else if (!shouldShift && shifts.containsKey(i)) {
                val anim = shifts[i]!!
                scope.launch {
                    anim.animateTo(0f, tween(120))
                }
            }
            // 计数用几何判定（同步、确定性）——launch 异步调度，动画值
            // 计数在 onEnd 紧跟最后一次 onDrag 时会少算（r62 测试2 根因）。
            if (shouldShift) {
                if (rel < 0) shiftDown++ else shiftUp++
            }
        }
        targetIndex = (d - shiftDown + shiftUp).coerceIn(range.first, range.last)
    }

    /** 条目让位平移量（dragReorder 修饰符读取）。 */
    fun shiftOf(index: Int): Float {
        val d = draggingIndex
        if (d != null) {
            if (index == d) return 0f
            return shifts[index]?.value ?: 0f
        }
        val s = settlingIndex
        if (s != null) {
            if (index == s) return 0f
            // r63d：提交后让位条目的动画值 + 平移补偿（新槽位已平移
            // 一个 stride，加补偿后视觉连续，Animatable 趋向 ±stride
            // 恰好整体归零）
            val anim = shifts[index]?.value ?: 0f
            val adj = settleAdjust[index] ?: 0f
            return anim + adj
        }
        return 0f
    }

    /**
     * 松手：一次性提交换位 + 落位动画（让位平移 + 拖动偏移归零）。
     *
     * r63d 视口自洽四件套（让位判定改 dragOffset 后必须）：
     * ① settle 起点改「视觉残差」ε：松手时 dragOffset ≈ (t-d)·stride + ε
     *   （ε<stride，条目中心与目标槽中心的偏差）。提交重组后条目落新槽
     *   （新槽视觉位置 = 原目标槽位置），translationY 只需从 ε 归零——
     *   从 dragOffset 归零会先「飞回」再落位（r62 遗留，ε 小不显；
     *   r63d 顶边界松手 ε 可达 stride，跳变明显）。
     * ② 视口钉回：LazyColumn 锚定跟踪布局槽位（不知让位平移），提交后
     *   视口被拉偏一格（t≠d 时锚定条目换人）——scrollToItem 钉回提交
     *   前视口，保证「视觉排列 == 提交后排列」不变量成立。
     * ③ 让位条目动画归零：提交后让位条目落新槽（视觉位置 = 原位 ±
     *   stride = 新槽位），translationY 从 ±stride 归零无跳变。
     * ④ 索引重映射：提交后数据换位，条目们落到新下标——settlingIndex
     *   用新下标 t（旧 d 是别的歌！），让位动画跟歌重键 + 平移补偿
     *   （新槽位平移一个 stride，Animatable 继续趋向 ±stride，加补偿
     *   恰好整体归零）。r62 遗留：单格拖动 = 两歌互换再换回的毛刺。
     */
    fun onEnd() {
        val d = draggingIndex ?: return
        val t = targetIndex
        // ① 残差：条目中心相对目标槽中心的偏差（ε < stride）
        val settlingOffset = dragOffset - (t - d) * itemStride
        // ② 视口快照：提交后钉回（等重组 + scrollToItem）
        val firstIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        val firstOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0
        // ④ 让位动画快照（提交前旧下标 → 当前动画值/方向）
        val oldShifts = shifts.entries.associate { (i, anim) ->
            i to (anim.value to (if (i < d) 1 else -1))
        }
        draggingIndex = null
        pointerY = null
        autoScrollJob?.cancel()
        autoScrollJob = null
        // r65：清除快照——条目提交重组到新下标（视口钉回后可见），
        // settle 动画接管视觉。快照与条目在提交瞬间视觉位置一致
        //（快照 top = 条目新槽视觉位置 + ε 残差），无跳变
        dragSnapshot = null
        if (t != d) onMove(d, t)
        settlingIndex = t
        // ④ 让位动画跟歌重键：旧 i 的歌提交后落新下标 i'（i<d → i+1，
        // i>d → i-1），平移补偿 = -dir·stride（新槽位平移量）
        shifts.clear()
        settleAdjust.clear()
        oldShifts.forEach { (i, pair) ->
            val newIndex = if (i < d) i + 1 else i - 1
            val dir = pair.second
            val anim = Animatable(pair.first)
            shifts[newIndex] = anim
            settleAdjust[newIndex] = -dir * itemStride
        }
        dragOffset = settlingOffset
        settleJob = scope.launch {
            try {
                // ② 等一帧数据重组（onMove → StateFlow → 重组 → 新布局），
                // 再钉回视口——不等的话 scrollToItem 作用在旧布局上
                if (t != d) {
                    delay(50)
                    listState.scrollToItem(firstIndex, 0)
                    if (firstOffset != 0) {
                        listState.scrollBy(-firstOffset.toFloat())
                    }
                }
                val animJobs = shifts.values.map { anim ->
                    async { anim.animateTo(0f, tween(120)) }
                }
                val frames = 8L
                var i = 0L
                while (i < frames && isActive) {
                    delay(15)
                    i++
                    dragOffset = settlingOffset * (1f - i.toFloat() / frames)
                }
                animJobs.awaitAll()
            } finally {
                if (draggingIndex == null) {
                    dragOffset = 0f
                    settlingIndex = null
                    shifts.clear()
                    settleAdjust.clear()
                }
            }
        }
    }

    /** 取消拖动：数据从未动过，直接恢复原状。 */
    fun onCancel() {
        draggingIndex = null
        pointerY = null
        autoScrollJob?.cancel()
        autoScrollJob = null
        settleJob?.cancel()
        settleJob = null
        // r65：清除快照（条目恢复原位渲染）
        dragSnapshot = null
        scope.launch {
            shifts.values.forEach { it.snapTo(0f) }
            shifts.clear()
        }
        dragOffset = 0f
        settlingIndex = null
    }

    /**
     * 边缘自动滚动：拖到视口上下边缘 scrollBy 滚动列表。
     * 滚动量补偿进 dragOffset（视觉跟手）+ 实时让位判定。
     */
    internal fun startAutoScroll() {
        if (autoScrollJob != null) return
        autoScrollJob = scope.launch {
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
                            scrollAccum += actual
                            dragOffset += actual
                            updateShifts()
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
    onMove: (Int, Int) -> Boolean,
    /** r63d：可拖动条目区间（排除尾部 Spacer / 头部文件夹行）。 */
    dragRange: (() -> IntRange)? = null
): DragReorderState {
    val scope = rememberCoroutineScope()
    return remember(listState, scope) { DragReorderState(listState, onMove, scope, dragRange) }
}

/**
 * v1.5.1 r59b：置顶/置底后保持视口不动（列表不跳动）。
 *
 * 根因：LazyColumn 的滚动锚定（LazyListScrollPosition
 * .updateScrollPositionIfTheFirstItemWasMoved）只跟踪**第一个可见
 * 项的 key**——被移动条目恰好是第一个可见项时，视口跟着它跳到
 * 新位置（置底 → 跳到底部；置顶 → 跳到顶部）。
 *
 * 修复：数据搬移**前**记住第一个可见项的 offset，搬移后用 index
 * 变换数学算出「视口顶部应显示的条目」的新下标，scrollToItem
 * 恢复。语义：视口显示的内容**不变**（除被移动条目从当前位置
 * 消失/插入外，其他条目相对视口不动）。
 *
 * 条目从 [movedFrom] 移到 [movedTo] 后，视口顶部条目的新下标：
 * - firstIndex < min(from,to) 或 > max(from,to)：不变（视口外发生的事）；
 * - firstIndex == from：仍是 from（这个位置现在是原 from±1 的条目——
 *   被移动项从当前位置消失，下面的条目顶上来）；
 * - from < firstIndex ≤ to（置底）：-1（区间整体前移一格）；
 * - to ≤ firstIndex < from（置顶）：+1（区间整体后移一格）。
 *
 * 注意：scrollToItem 必须在**数据重组后**执行才能拿到正确的新布局
 * ——transform 只改数据源，重组在下一帧。本函数在 scrollToItem 前
 * 挂起等待一帧（withFrameNanos 不可用于非组合上下文，改用
 * awaitFrame 简易等待）确保新布局生效后再滚动。
 */
suspend fun keepScrollAfterMove(
    listState: LazyListState,
    movedFrom: Int,
    movedTo: Int,
    transform: () -> Boolean
) {
    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: run {
        transform(); return
    }
    val firstIndex = first.index
    val firstOffset = first.offset
    if (!transform()) return
    val newIndex = when {
        firstIndex == movedFrom -> movedFrom
        movedFrom < firstIndex && firstIndex <= movedTo -> firstIndex - 1
        movedTo <= firstIndex && firstIndex < movedFrom -> firstIndex + 1
        else -> firstIndex
    }
    // 等一帧让数据重组完成（transform 触发的 StateFlow 更新 → 重组
    // → LazyColumn 新布局）。不等的话 scrollToItem 作用在旧布局上，
    // 重组后锚定机制又会把视口拉走。
    kotlinx.coroutines.delay(50)
    // scrollToItem 的负 offset 会被测量规范化（firstVisibleItem 重算），
    // 部分滚出的条目无法直接用负 offset 表达——先对齐到条目顶部再
    // scrollBy 滚回原偏移。注意方向：firstOffset<0（条目顶部在视口上方）
    // 时要恢复需条目**上移**|firstOffset| = 窗口下移 = scrollBy 正值，
    // 即 scrollBy(-firstOffset)——r59b 首版写成 scrollBy(firstOffset)
    // 方向反了，置顶部分滚出场景视口上跳一行（SongRealA 顶替 RingB）
    listState.scrollToItem(newIndex, 0)
    if (firstOffset != 0) {
        listState.scrollBy(-firstOffset.toFloat())
    }
}

/**
 * 列表条目的拖动排序视觉修饰符（r64：从 dragReorder 拆分）。
 * 拖动条目：r65 起拖动期间 alpha=0（视觉由 DragReorderOverlay 的
 * 快照接管——条目组件滚出缓存区被回收不再导致消失）；松手 settle
 * 期间恢复渲染 + translationY = dragOffset（落位动画）。
 * 其他条目：translationY = shiftOf(index)（让位平移，松手归零）。
 * [index] 必须是该条目在 LazyColumn 中的绝对下标。
 * **不含手势**——手势统一在容器 dragReorderSource（r64 架构）。
 */
@Composable
fun Modifier.dragReorderItem(dragState: DragReorderState, index: Int): Modifier {
    return this
        .zIndex(if (dragState.draggingIndex == index || dragState.settlingIndex == index) 1f else 0f)
        .graphicsLayer {
            val dragging = dragState.draggingIndex == index
            val settling = dragState.settlingIndex == index
            if (dragging) {
                if (dragState.dragSnapshot != null) {
                    // r65：快照接管视觉，条目隐藏
                    alpha = 0f
                } else {
                    // r65b 防御：快照未就绪/截图失败时条目自己渲染跟手
                    //（r64 行为兜底）——条目绝不凭空消失
                    alpha = 1f
                    translationY = dragState.dragOffset
                }
            } else {
                alpha = 1f
                translationY = if (settling) {
                    dragState.dragOffset
                } else {
                    dragState.shiftOf(index)
                }
            }
        }
}

/**
 * r65：拖动条目的快照 overlay——包在 LazyColumn 外层（Box）。
 * onStart 截图 → 拖动期间快照跟手（永不回收）→ 松手清除。
 * 必须与 LazyColumn 同一个 Box 父级，坐标才对齐（视口坐标系）。
 */
@Composable
fun DragReorderOverlay(dragState: DragReorderState) {
    val bmp = dragState.dragSnapshot ?: return
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
    ) {
        drawImage(
            image = bmp.asImageBitmap(),
            dstOffset = androidx.compose.ui.unit.IntOffset(
                dragState.snapshotLeftX.toInt(),
                dragState.snapshotTopY.toInt()
            ),
            dstSize = androidx.compose.ui.unit.IntSize(
                dragState.snapshotWidth.toInt().coerceAtLeast(1),
                dragState.dragSnapshot?.height ?: 1
            )
        )
    }
}

/**
 * r65b：View → Window（API 24 兼容——View.getWindow() 是 API 28+）。
 */
private fun windowOf(view: android.view.View): android.view.Window? {
    var ctx: android.content.Context? = view.context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}

/**
 * r64：长按拖动手势检测（容器层）——挂在 LazyColumn 的 modifier 上。
 *
 * **为什么从条目移到容器（r64 根因修复）**：
 * r62 视觉让位架构下拖动中数据不动，拖动条目的布局槽位冻结在原
 * 下标 d。自动滚动时视口移开，槽位滚出 LazyColumn 组合缓存区
 * （视口 + 缓存若干行）→ 条目组件被回收 → 条目上的 pointerInput
 * 协程被杀 → onDragCancel → onCancel → autoScrollJob 取消 →
 * **滚动停止（用户报告「只能滚 3-4 行」——正是槽位到缓存区边界
 * 的距离）**。
 * 手势挂在容器上：容器永不回收，手势永不死亡；hit test 由容器
 * 统一做（长按时找手指下的条目），与 iOS UITableView 同构
 * （手势在 scrollView 上，不在 cell 上）。
 *
 * 长按定位条目：用 listState.layoutInfo.visibleItemsInfo 的
 * offset/size 做命中判定（视口坐标），不依赖条目自身回调。
 * [dragRange] 限定可拖动条目区间（与 DragReorderState.dragRange
 * 同源——头部文件夹行/尾部 Spacer 不可拖）。
 */
@Composable
fun Modifier.dragReorderSource(
    dragState: DragReorderState,
    listState: LazyListState,
    /** r64：多选模式禁用拖动手势（原条目架构多选时条目无手势修饰符，
     *  容器架构手势常驻——必须显式关，否则多选模式长按误触发拖动） */
    enabled: Boolean = true,
    dragRange: (() -> IntRange)? = null
): Modifier {
    val view = LocalView.current
    // r65b：LazyColumn 在组合根中的位置——PixelCopy 拷贝的是窗口
    // 硬件帧（窗口原点），而 info.offset 是 LazyList 视口坐标，
    // 两者差一个列表区顶部偏移（顶栏+Tab 高度）。onPlace 时记录
    // positionInRoot，截图裁剪时补上这个差值。
    val listTopInRoot = remember { mutableFloatStateOf(0f) }
    // r65b：截图 provider——PixelCopy（API 26+）异步读窗口硬件帧。
    // r65 首版 View.draw 软件渲染 Compose 内容空白（RenderNode
    // 硬件层画不进软件 Canvas），改 PixelCopy 保真。截图期间条目
    // 自渲染兜底（dragReorderItem），就绪后无缝切换。
    dragState.snapshotProvider = { index ->
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (info == null) {
            android.util.Log.d("DragR65", "snapshot: index $index not visible, skip")
        } else if (android.os.Build.VERSION.SDK_INT < 26) {
            // API 24/25 无 PixelCopy——条目自渲染兜底（r64 行为）
            android.util.Log.d("DragR65", "snapshot: API<26 skip")
        } else {
            try {
                val w = view.width.coerceAtLeast(1)
                val h = view.height.coerceAtLeast(1)
                // 条目在 AndroidComposeView 坐标系中的区域
                //（列表区偏移 + 条目视口偏移）
                val topInView = (listTopInRoot.floatValue + info.offset).toInt()
                val top = topInView.coerceIn(0, h - 1)
                val bottom = (topInView + info.size).coerceAtMost(h)
                if (bottom - top <= 0) {
                    android.util.Log.d("DragR65", "snapshot: empty clip region, skip")
                } else {
                    // 窗口坐标 = AndroidComposeView 在窗口中的位置 + 条目在 view 中的位置
                    //（PixelCopy(Window) 的 srcRect 用窗口坐标系）
                    val loc = IntArray(2)
                    view.getLocationInWindow(loc)
                    val winTop = (loc[1] + top).coerceAtLeast(0)
                    val winBottom = (loc[1] + bottom).coerceAtLeast(winTop + 1)
                    val dst = android.graphics.Bitmap.createBitmap(
                        w, winBottom - winTop, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val window = windowOf(view)
                    if (window == null) {
                        android.util.Log.d("DragR65", "snapshot: no window, skip")
                    } else {
                        android.view.PixelCopy.request(
                            window,
                            android.graphics.Rect(0, winTop, w, winBottom),
                            dst,
                            { result ->
                                if (result == android.view.PixelCopy.SUCCESS) {
                                    // 采样诊断：非透明像素计数
                                    var nonTransparent = 0
                                    val stepX = (dst.width / 8).coerceAtLeast(1)
                                    val stepY = (dst.height / 8).coerceAtLeast(1)
                                    for (x in 0 until dst.width step stepX) {
                                        for (y in 0 until dst.height step stepY) {
                                            if (dst.getPixel(x, y) ushr 24 != 0) nonTransparent++
                                        }
                                    }
                                    android.util.Log.d(
                                        "DragR65",
                                        "snapshot ok: index=$index w=${dst.width} h=${dst.height} " +
                                            "winTop=$winTop viewTop=$top listTop=${listTopInRoot.floatValue} " +
                                            "nonTransparent=$nonTransparent"
                                    )
                                    dragState.onSnapshotReady(index, dst)
                                } else {
                                    android.util.Log.d("DragR65", "snapshot failed: result=$result")
                                }
                            },
                            android.os.Handler(android.os.Looper.getMainLooper())
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("DragR65", "snapshot exception: ${e.message}")
            }
        }
    }
    return this
        .onGloballyPositioned { coords ->
            listTopInRoot.floatValue = coords.positionInRoot().y
        }
        .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                // 命中判定：手指位置 → 可见条目下标。
                // r64：条目间有 8dp 间距（缝隙），offset+size 判定会漏
                // ——缝隙归并到上方条目（用下一行 offset 作底界）
                val infos = listState.layoutInfo.visibleItemsInfo
                val hit = infos.firstOrNull { it.offset <= offset.y && offset.y < it.offset + it.size }
                    ?: infos.lastOrNull { it.offset <= offset.y }
                val index = hit?.index
                val range = dragRange?.invoke() ?: (0 until listState.layoutInfo.totalItemsCount)
                if (index == null || index !in range) return@detectDragGesturesAfterLongPress
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dragState.onStart(index, offset.y - hit.offset)
                dragState.startAutoScroll()
            },
            onDrag = { change, amount ->
                change.consume()
                dragState.onDrag(amount.y)
            },
            onDragEnd = {
                if (dragState.draggingIndex != null) dragState.onEnd()
            },
            onDragCancel = {
                if (dragState.draggingIndex != null) dragState.onCancel()
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

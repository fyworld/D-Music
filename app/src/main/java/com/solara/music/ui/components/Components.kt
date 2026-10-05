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
     * r57：松手落位动画协程——dragOffset 平滑归零（~120ms），
     * 消除松手瞬间条目跳变。onStart 时 cancel（新拖动立即接管）。
     */
    private var settleJob: Job? = null

    /**
     * r61：换位后视口钉回协程。scrollToItem 是 suspend，需协程承载；
     * 用 Main.immediate 保证在换位的同一主线程调用栈内同步执行完
     * （先于下一帧 measure，锚定来不及拉走视口）。onStart/onEnd
     * cancel 防泄漏（r57 ⑤ 教训：不受管理的换位协程是失控滚动源）。
     */
    private var pinJob: Job? = null

    /**
     * r57：落位中的条目下标（动画期间 graphicsLayer 仍应用
     * dragOffset——draggingIndex 已清 null）。动画结束清 null。
     */
    var settleIndex by mutableStateOf<Int?>(null)
        private set

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
        // r57：打断落位动画——新拖动立即接管 dragOffset
        settleJob?.cancel()
        settleJob = null
        settleIndex = null
        // r61：打断残留的视口钉回（r57 ⑤ 教训——换位协程必须受管理）
        pinJob?.cancel()
        pinJob = null
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
        // r57b：视觉位置限制在视口内——条目始终完整可见（用户建议：
        // 拖到最前/最后可见条目位置即停，列表继续滚动，条目不出界）。
        // 与换位/滚动补偿自洽：所有路径保持视觉不变，clamp 界随 slot
        // 平移同步移动；checkSwap/autoScrollSwap 后 dragOffset=视觉-新
        // slot，视觉在界内 → 新值必在新 clamp 界内
        clampToViewport()
        pointerY = pointerY?.plus(delta)
        // r56：自动滚动活跃期间关闭视觉判定——换位唯一由累积滚动量
        // 驱动。微抖的 ±1px 不再被拿去判定（r55 漂移位置错误换位根因）
        if (!isAutoScrollActive()) checkSwap()
    }

    /**
     * r57b：把拖动条目的视觉位置限制在视口内（完整可见）。
     * 视觉顶 ≥ 0 且视觉底 ≤ viewport——条目最多拖到与第一/最后
     * 可见条目对齐。钉住后手指继续下探（pointerY 不 clamp）自然
     * 进入边缘区驱动自动滚动，滚动期间条目钉在边界完整显示。
     */
    private fun clampToViewport() {
        val idx = draggingIndex ?: return
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == idx } ?: return
        val vpH = listState.layoutInfo.viewportSize.height.toFloat()
        val min = -info.offset.toFloat()          // 视觉顶 = 0
        val max = vpH - info.size - info.offset   // 视觉底 = vpH
        if (max >= min) dragOffset = dragOffset.coerceIn(min, max)
    }

    /** r56：最近一次实际滚动距今是否在活跃窗口内。 */
    private fun isAutoScrollActive(): Boolean =
        lastAutoScrollNanos != 0L &&
            System.nanoTime() - lastAutoScrollNanos < AUTO_SCROLL_ACTIVE_WINDOW_MS * 1_000_000L

    /**
     * r61：换位后把视口钉回换位前的位置（对抗 LazyColumn 锚定）。
     *
     * 锚定机制（LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved）
     * 只跟踪第一可见项的 key：换位使该 key 的 index 变化时，重组把
     * firstVisibleItemIndex 拉到 key 的新 index——视口内容不变，但
     * 拖动条目（换到第一可见项原 index）被推到视口上方不被组合 →
     * 手势死亡（r61 BUG 根因）。
     *
     * 修复：onMove 数据搬移后**立刻** scrollToItem 钉回换位前的
     * firstVisibleItemIndex/ScrollOffset。scrollToItem 内部
     * requestPositionAndForgetLastKnownKey 清除锚定 key 记忆 →
     * 下一帧按钉住的 position 布局 → 槽位不变 → 拖动条目落在第一
     * 可见槽（完整可见）→ 补偿公式假设成立。
     *
     * Main.immediate 同步执行：在换位的同一调用栈内完成（先于帧
     * 回调的 measure），锚定来不及触发。scrollToItem 无动画、
     * scroll 通道无并发（checkSwap 在手势回调、autoScrollSwap 在
     * 自身协程内串行调用，r56 两路径隔离），无竞态。
     *
     * ⚠ 不能用 r59b keepScrollAfterMove 的 scrollToItem(0)+scrollBy
     * 两步法：scrollBy 会触发一次**旧数据测量**重新记录锚定 key，
     * 随后新数据测量照样跳变（r59b 场景 delay(50) 先让跳变发生再
     * 事后纠正；拖动中条目出视口即被回收、手势即死，必须事前阻止，
     * scrollToItem 本身不触测量、无中间锚定记录）。
     *
     * offset 换算：visibleItemsInfo 的 offset 是视口坐标（滚动中
     * ≤ 0），scrollToItem 的 scrollOffset 为正 = 条目顶部在视口
     * 上方（正向滚动）——互为相反数。直接传视口坐标会方向反转
     * （r59b「负 offset 规范化」坑即把两者混用）。firstOffset>0
     * 仅出现在列表顶 contentPadding 未滚动区，scrollOffset=0 时
     * 条目恰好落在 padding 位，取 0 即还原。
     *
     * ⚠ 不读 layoutInfo.visibleItemsInfo（那是**上一帧测量**结果）：
     * autoScrollSwap 路径 scrollBy 刚发生、下一帧测量未完成，用它
     * 钉回会撤销本次滚动、破坏边缘自动滚动。改读
     * firstVisibleItemIndex/firstVisibleItemScrollOffset——滚动位置
     * 的权威状态，scrollBy 同步更新，两条路径都实时；且语义与
     * scrollToItem 参数完全一致（正值 = 条目顶部在视口上方），
     * 无需符号换算。
     */
    private fun pinViewportAfterSwap() {
        val firstIndex = listState.firstVisibleItemIndex
        val scrollOffset = listState.firstVisibleItemScrollOffset
        pinJob?.cancel()
        pinJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            listState.scrollToItem(firstIndex, scrollOffset)
        }
    }

    /**
     * 交换判定（手势拖动路径专用）。
     *
     * 视觉中心落在哪个可见条目上就与哪个交换（单步约束保持逐条
     * 手感）。手指在视口内移动时新条目天然可见，无需同步滚动。
     * 自动滚动路径**不走这里**——见 [autoScrollSwap]（r55）。
     *
     * r57：换位后新条目布局 = hit 条目的原位置（必然在视口内），
     * 不存在越界回收——r56b 的 syncScrollAfterSwap 前提不成立且
     * 协程泄漏（松手后失控滚动根因），已删除。
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
            // r61：钉回视口对抗锚定——否则换位涉及第一可见项时拖动
            // 条目被推出视口（不被组合→手势死亡→条目消失）
            pinViewportAfterSwap()
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
            // r61：向上滚动换位经过第一可见项时锚定同样拉走视口
            // （scrollBy 后 lastKnownKey 未更新，换位使 key 移动 →
            // 锚定把 firstVisibleItemIndex 拉到 key 新 index，视口
            // 被拉走 2 格）——钉回保持槽位假设成立
            pinViewportAfterSwap()
        }
    }

    /**
     * r57：松手落位。draggingIndex 立即清 null（停换位/停自动滚动/
     * zIndex 复位），dragOffset 不再硬归零——启动 settleJob 平滑
     * 动画归零（~120ms），条目优雅滑回数据位置，消除松手跳变。
     * 动画期间 graphicsLayer 仍读 dragOffset（draggingIndex==index
     * 已不成立——见 dragReorder 修饰符的 settleIndex）。
     */
    fun onEnd() {
        val settlingOffset = dragOffset
        val settlingIndex = draggingIndex
        draggingIndex = null
        pointerY = null
        scrollAccum = 0f
        lastAutoScrollNanos = 0L
        autoScrollJob?.cancel()
        autoScrollJob = null
        // r61：松手结束钉回协程（scrollToItem 已同步执行完，此处
        // 只是兜底清理引用，防止泄漏）
        pinJob?.cancel()
        pinJob = null
        if (settlingIndex == null || settlingOffset == 0f) {
            dragOffset = 0f
            settleIndex = null
            return
        }
        settleJob?.cancel()
        settleIndex = settlingIndex
        settleJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val durationMs = 120L
                val frames = (durationMs / 16L).coerceAtLeast(1L)
                var i = 0L
                while (i < frames && isActive) {
                    delay(16)
                    i++
                    val t = (i.toFloat() / frames).coerceIn(0f, 1f)
                    // easeOutCubic：先快后慢，落位自然
                    val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                    dragOffset = settlingOffset * (1f - eased)
                }
            } finally {
                // r57b：竞态守卫——onStart 打断动画时 draggingIndex 已被
                // 新拖动接管，finally 不得清 dragOffset/settleIndex
                if (draggingIndex == null) {
                    dragOffset = 0f
                    settleIndex = null
                }
            }
        }
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
        .zIndex(if (dragState.draggingIndex == index || dragState.settleIndex == index) 1f else 0f)
        .graphicsLayer {
            // r57：拖动中或落位动画中都应用 dragOffset（松手后平滑归零）
            if (dragState.draggingIndex == index || dragState.settleIndex == index) {
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

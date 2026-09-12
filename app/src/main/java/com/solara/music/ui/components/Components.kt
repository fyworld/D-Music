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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // 在线：封面 URL 缓存
    // v1.4.25：先读磁盘持久化（Store.onlineCoverUrl）——冷启动不再每首歌
    // 联网调 API 解析 URL，直接命中 Coil 磁盘图片缓存，离线也有封面；
    // 磁盘没有才调 API，拿到后写盘供下次使用
    var url by remember(song?.source, song?.picId, song?.id) {
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
            val fetched = MusicApi.fetchPicUrl(song)
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
                runCatching { MusicApi.fetchPicUrl(song) }.getOrNull()
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
 * 歌曲行：封面 + 标题/歌手 + 收藏/更多操作。
 * 更多弹菜单：加入歌单、下载、移除（移除行为由所在列表定义）。
 * v1.4.26：selectionMode=true 时切换为多选行——点击整行切换勾选，
 * 左侧封面位置显示勾选框，隐藏收藏/更多按钮。
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
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = song.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selectionMode && selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (!selectionMode) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
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
 */
class DragReorderState(
    private val listState: LazyListState,
    private val onMove: (Int, Int) -> Boolean
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    fun onStart(index: Int) {
        draggingIndex = index
        dragOffset = 0f
    }

    fun onDrag(delta: Float) {
        val current = draggingIndex ?: return
        dragOffset += delta
        val visible = listState.layoutInfo.visibleItemsInfo
        val currInfo = visible.firstOrNull { it.index == current } ?: return
        val start = currInfo.offset + dragOffset
        val end = start + currInfo.size
        val target = visible.firstOrNull { vi ->
            vi.index != current &&
                ((start >= vi.offset && start < vi.offset + vi.size) ||
                    (end > vi.offset && end <= vi.offset + vi.size))
        }
        if (target != null && onMove(current, target.index)) {
            draggingIndex = target.index
            dragOffset += currInfo.offset - target.offset
        }
    }

    fun onEnd() {
        draggingIndex = null
        dragOffset = 0f
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
                onDragStart = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    dragState.onStart(currentIndex)
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragState.onDrag(amount.y)
                },
                onDragEnd = { dragState.onEnd() },
                onDragCancel = { dragState.onEnd() }
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

@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.solara.music.ui.player

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.TagEmbedder
import com.solara.music.lyrics.LrcParser
import com.solara.music.player.PlayMode
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.CoverImage
import com.solara.music.ui.components.formatMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏播放页（v1.4.3 循环滑动结构）：
 * - Pager 大页数交替排列：偶数页=封面页、奇数页=歌词页，从中间开始
 * - 左右连续滑动在 播放页 ↔ 歌词页 之间无限循环（无边界、无回弹）
 * - 封面页：封面 + 歌名/歌手 + 歌词预览（多行自动滚动跟随播放）
 * - 歌词页：整页歌词（自动滚动跟随播放）
 * - 顶部区域向下划 = 收起回播放栏
 */
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {}
) {
    val song by PlayerManager.currentSong.collectAsState()
    val isPlaying by PlayerManager.isPlaying.collectAsState()
    val playMode by PlayerManager.playMode.collectAsState()
    val queue by PlayerManager.queue.collectAsState()
    val currentIndex by PlayerManager.currentIndex.collectAsState()
    val favorites by Store.favorites.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val lyricLoading by vm.lyricLoading.collectAsState()
    // v1.4.15：按歌歌词偏移（每首歌独立校准，随歌曲切换/调节按钮重读）
    var offsetTick by remember { mutableStateOf(0) }
    val lyricOffset = remember(song, offsetTick) {
        song?.let { Store.lyricOffsetOf(it) } ?: 0f
    }

    var showQueue by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    // ---- v1.4.16：逐句打点模式 ----
    // 打点模式：唱到当前句时点「打点」记录此刻播放位置为该句开唱时刻，
    // 自动跳到下一句；支持撤销上一条/跳过；退出即保存。
    var tappingMode by remember { mutableStateOf(false) }
    var tappingLine by remember { mutableStateOf(0) }
    /** 已打点结果：行索引 → 毫秒（退出打点模式时整体落盘）。 */
    var tappingMap by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    /** 该歌已保存的打点时间戳（显示歌词时优先使用）。 */
    var timestampsTick by remember { mutableStateOf(0) }
    val savedTimestamps = remember(song, timestampsTick) {
        song?.let { Store.lyricTimestampsOf(it) } ?: emptyMap()
    }

    // 切歌自动退出打点模式（未保存的打点丢弃——不同歌的行索引不通用）
    LaunchedEffect(song) {
        if (tappingMode) {
            tappingMode = false
            tappingMap = emptyMap()
            tappingLine = 0
        }
    }

    // 循环滑动（v1.4.3 修复：v1.4.2 的"3页+边界弹回"方案有缺陷——停稳在歌词页
    // 也被弹回播放页。改为大页数交替排列：偶数页=封面、奇数页=歌词，
    // 从中间页开始，两个方向都能无限划，无需任何弹回逻辑）
    val pageCount = 1001
    val centerPage = pageCount / 2 // 500
    val pagerState = rememberPagerState(initialPage = centerPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val player = PlayerManager.playerOrNull

    // 顶部区域向下划收起：累计向下位移超过阈值即触发（向上划忽略，避免与 Pager 冲突）
    var dismissAccum by remember { mutableStateOf(0f) }
    fun dismissDragModifier() = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { dismissAccum = 0f },
            onDragEnd = { dismissAccum = 0f },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                if (dragAmount > 0) dismissAccum += dragAmount
                if (dismissAccum > 60f) {
                    dismissAccum = 0f
                    onDismiss()
                }
            }
        )
    }

    LaunchedEffect(player) {
        while (true) {
            player?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.takeIf { d -> d >= 0 } ?: 0L
            }
            delay(200L)
        }
    }

    val currentLine = if (tappingMode) {
        // 打点模式：高亮跟随打点指针（用户手动控制），不自动跟随播放
        tappingLine
    } else if (savedTimestamps.isNotEmpty()) {
        // v1.4.16：该歌有打点时间戳——按打点时刻查当前句（最准确）
        remember(lyrics, position, savedTimestamps) {
            var ans = -1
            savedTimestamps.entries.sortedBy { it.value }.forEach { (idx, ms) ->
                if (ms <= position) ans = idx
            }
            ans
        }
    } else {
        remember(lyrics, position, lyricOffset) {
            // v1.4.15：应用当前歌的校准偏移——正值延后、负值提前。
            LrcParser.indexOf(lyrics, position - (lyricOffset * 1000).toLong())
        }
    }
    // 整页歌词的滚动状态（当前页为奇数 = 歌词页时驱动）
    val listState = rememberLazyListState()
    LaunchedEffect(currentLine, pagerState.currentPage) {
        if (pagerState.currentPage % 2 == 1 &&
            currentLine >= 0 && !listState.isScrollInProgress
        ) {
            listState.animateScrollToItem(currentLine)
        }
    }
    // v1.4.16：打点模式下滚动跟随打点指针（tappingLine 驱动 currentLine，此为保险）
    LaunchedEffect(tappingLine, tappingMode, pagerState.currentPage) {
        if (tappingMode && pagerState.currentPage % 2 == 1 && !listState.isScrollInProgress) {
            runCatching { listState.animateScrollToItem(tappingLine.coerceAtLeast(0)) }
        }
    }
    // 封面页歌词预览的滚动状态（当前页为偶数 = 封面页时驱动）
    val previewState = rememberLazyListState()
    LaunchedEffect(currentLine, pagerState.currentPage) {
        if (pagerState.currentPage % 2 == 0 &&
            currentLine >= 0 && !previewState.isScrollInProgress
        ) {
            previewState.animateScrollToItem(currentLine.coerceAtLeast(0), 0)
        }
    }

    val current = song

    /**
     * v1.4.15：调节当前歌曲的歌词偏移（±0.5s 步进，范围 ±90s），reset=true 直接归零。
     * 每首歌独立保存——不同歌的 LRC 时间轴偏差不同，全局偏移会互相污染。
     * 正值=歌词延后显示（歌快词慢时用"延后"），负值=提前。
     */
    fun adjustLyricOffset(delta: Float, reset: Boolean = false) {
        val s = song ?: return
        val next = if (reset) 0f
        else (Store.lyricOffsetOf(s) + delta).coerceIn(-90f, 90f)
        Store.saveLyricOffset(s, next)
        // 触发重组刷新显示（remember(song) 只认歌曲切换，不认偏移变化）
        offsetTick++
    }

    val context = LocalContext.current

    /**
     * v1.4.17：把校准结果（打点 + 偏移）合成新的 LRC 文本。
     * - 打点句：用打点时刻（最准，本身就是播放器时间轴上的真实时刻）；
     *   打点模式进行中未点「完成」的打点（tappingMap）也一并带上
     * - 未打点句：用 LRC 原时间 + 偏移秒（显示逻辑 position-offset>=time，
     *   即真实开唱时刻 = 原时间 + 偏移）
     * 无校准数据（无打点且偏移为 0）返回 null。
     */
    fun buildCalibratedLrc(): String? {
        if (lyrics.isEmpty()) return null
        val allTimestamps = savedTimestamps + tappingMap
        if (allTimestamps.isEmpty() && lyricOffset == 0f) return null
        val offsetMs = (lyricOffset * 1000).toLong()
        return lyrics.mapIndexed { i, line ->
            val timeMs = allTimestamps[i]
                ?: ((line.time * 1000).toLong() + offsetMs).coerceAtLeast(0L)
            "[%02d:%02d.%03d]".format(
                timeMs / 60000, timeMs / 1000 % 60, timeMs % 1000
            ) + line.text
        }.joinToString("\n")
    }

    /**
     * v1.4.17：保存校准后的歌词——
     * 1) 写磁盘缓存（取词优先级缓存最高，不更新则旧缓存盖过嵌入内容）
     * 2) 本地歌曲：嵌入音频文件（MP3 USLT / FLAC 伴生 .lrc），跨播放器生效
     * 3) 清掉偏移/打点数据（校准已固化进新 LRC，留着会被二次叠加）
     * 4) 刷新歌词显示 + Toast 反馈
     */
    fun saveCalibratedLyric() {
        val s = song ?: return
        val lrc = buildCalibratedLrc() ?: return
        val ctx = context.applicationContext
        scope.launch {
            val isLocal = LocalCoverExtractor.isLocalSong(s)
            val embedded = if (isLocal) {
                withContext(Dispatchers.IO) {
                    runCatching { TagEmbedder.embedLyricInto(ctx, s, lrc) }.getOrDefault(false)
                }
            } else false
            withContext(Dispatchers.IO) { Store.saveCachedLyric(s, lrc) }
            Store.saveLyricOffset(s, 0f)
            Store.saveLyricTimestamps(s, emptyMap())
            offsetTick++
            timestampsTick++
            // 退出打点模式并清未落盘数据（已固化进新 LRC）
            tappingMode = false
            tappingMap = emptyMap()
            tappingLine = 0
            vm.refreshLyrics()
            Toast.makeText(
                ctx,
                when {
                    isLocal && embedded -> "校准歌词已嵌入文件，以后播放无需再调"
                    isLocal -> "校准歌词已保存到缓存（嵌入文件失败）"
                    else -> "校准歌词已保存"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // ---- 顶栏（向下划收起）：歌名/歌手居中 + 更多菜单 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dismissDragModifier()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器")
                }
                // 歌名 + 歌手（小字体居中，点击切到歌词页）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    if (pagerState.currentPage % 2 == 0)
                                        pagerState.currentPage + 1
                                    else pagerState.currentPage - 1
                                )
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = current?.displayName ?: "未在播放",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = current?.artistName ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                // v1.4.14：更多菜单（收藏 / 加入歌单 / 下载），替代原队列按钮
                val isFavorite = current != null && favorites.any { it.sameAs(current) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                            leadingIcon = {
                                Icon(
                                    if (isFavorite) Icons.Filled.Favorite
                                    else Icons.Filled.FavoriteBorder,
                                    null
                                )
                            },
                            onClick = {
                                menuOpen = false
                                current?.let { Store.toggleFavorite(it) }
                            }
                        )
                        if (current != null) {
                            DropdownMenuItem(
                                text = { Text("加入歌单") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onAddToPlaylist(current)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("下载") },
                                leadingIcon = { Icon(Icons.Filled.Download, null) },
                                onClick = {
                                    menuOpen = false
                                    onDownload()
                                }
                            )
                            // v1.4.17：保存校准歌词（有校准数据时显示）——
                            // 本地歌曲嵌入文件，以后播放无需重新调整
                            if (lyrics.isNotEmpty() &&
                                (lyricOffset != 0f || savedTimestamps.isNotEmpty() || tappingMap.isNotEmpty())
                            ) {
                                DropdownMenuItem(
                                    text = { Text("保存校准歌词") },
                                    leadingIcon = { Icon(Icons.Filled.Save, null) },
                                    onClick = {
                                        menuOpen = false
                                        saveCalibratedLyric()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ---- 循环主体：偶数页=封面页，奇数页=歌词页，交替排列无限划 ----
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                if (page % 2 == 0) {
                    // 封面页：大封面 + 歌词预览（歌名/歌手在顶栏，点预览进歌词页）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(dismissDragModifier()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CoverImage(
                                song = current,
                                size = 300.dp,
                                corner = 28.dp
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // 歌词预览：多行自动滚动跟随播放，点击进整页歌词
                        if (lyrics.isNotEmpty()) {
                            LazyColumn(
                                state = previewState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    },
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(lyrics.size) { i ->
                                    Text(
                                        text = lyrics[i].text,
                                        textAlign = TextAlign.Center,
                                        color = if (i == currentLine) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.5f)
                                        },
                                        style = if (i == currentLine) {
                                            MaterialTheme.typography.titleMedium
                                        } else {
                                            MaterialTheme.typography.bodyLarge
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    // 歌词页：整页歌词（歌名/歌手在顶栏）
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (tappingMode) {
                            // ---- v1.4.16：打点模式工具栏（v1.4.17 紧凑化：单行） ----
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "唱到高亮句时点「打点」（第 ${tappingLine + 1}/${lyrics.size} 句）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CompactTextButton(onClick = {
                                        // 记录此刻播放位置为当前句开唱时刻，跳下一句
                                        if (tappingLine < lyrics.size) {
                                            tappingMap = tappingMap + (tappingLine to position)
                                            tappingLine++
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.TouchApp,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.size(2.dp))
                                        Text("打点", style = MaterialTheme.typography.labelMedium)
                                    }
                                    CompactTextButton(onClick = {
                                        // 撤销：删掉最后一条打点，指针回退
                                        val lastIdx = tappingMap.keys.maxOrNull()
                                        if (lastIdx != null) {
                                            tappingMap = tappingMap - lastIdx
                                            tappingLine = lastIdx
                                        }
                                    }) { Text("撤销", style = MaterialTheme.typography.labelMedium) }
                                    CompactTextButton(onClick = {
                                        // 跳过：这句不打点（保留 LRC 原时间），指针前进
                                        if (tappingLine < lyrics.size - 1) tappingLine++
                                    }) { Text("跳过", style = MaterialTheme.typography.labelMedium) }
                                    CompactTextButton(onClick = {
                                        // 完成：合并已有打点（跳过的句保留旧值）并保存退出
                                        val merged = savedTimestamps + tappingMap
                                        song?.let { Store.saveLyricTimestamps(it, merged) }
                                        timestampsTick++
                                        tappingMode = false
                                        tappingMap = emptyMap()
                                        tappingLine = 0
                                    }) { Text("完成", style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        } else {
                            // v1.4.15：校准行（贴顶栏；v1.4.17 紧凑化：六按钮单行）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompactTextButton(onClick = { adjustLyricOffset(-0.5f) }) {
                                    Icon(
                                        Icons.Filled.FastRewind,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.size(2.dp))
                                    Text("提前", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    text = if (lyricOffset == 0f && savedTimestamps.isEmpty()) "同步校准"
                                    else if (savedTimestamps.isNotEmpty()) "已打点"
                                    else "偏移 ${if (lyricOffset > 0) "+" else ""}${"%.1f".format(lyricOffset)}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                CompactTextButton(onClick = { adjustLyricOffset(0.5f) }) {
                                    Text("延后", style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.size(2.dp))
                                    Icon(
                                        Icons.Filled.FastForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                if (lyricOffset != 0f) {
                                    CompactTextButton(onClick = { adjustLyricOffset(0f, reset = true) }) {
                                        Text("重置", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                // v1.4.16：逐句打点入口（偏移救不了的歌词用这个）
                                CompactTextButton(onClick = {
                                    if (lyrics.isNotEmpty()) {
                                        tappingMap = emptyMap()
                                        tappingLine = 0
                                        tappingMode = true
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.TouchApp,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.size(2.dp))
                                    Text("打点", style = MaterialTheme.typography.labelSmall)
                                }
                                // 已有打点数据时可清除（恢复 LRC 原时间轴 + 偏移）
                                if (savedTimestamps.isNotEmpty()) {
                                    CompactTextButton(onClick = {
                                        song?.let { Store.saveLyricTimestamps(it, emptyMap()) }
                                        timestampsTick++
                                    }) {
                                        Text("清打点", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            when {
                                lyrics.isNotEmpty() -> LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 24.dp)
                                ) {
                                    items(lyrics.size) { i ->
                                        val isTappingTarget = tappingMode && i == tappingLine
                                        val isTapped = tappingMode && tappingMap.containsKey(i)
                                        Text(
                                            text = if (isTapped) {
                                                // 打点模式：已打句显示打点时刻
                                                "✓ ${lyrics[i].text}"
                                            } else lyrics[i].text,
                                            textAlign = TextAlign.Center,
                                            color = when {
                                                isTappingTarget -> MaterialTheme.colorScheme.primary
                                                isTapped -> MaterialTheme.colorScheme.primary
                                                    .copy(alpha = 0.6f)
                                                i == currentLine -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.55f)
                                            },
                                            style = when {
                                                isTappingTarget || i == currentLine ->
                                                    MaterialTheme.typography.titleMedium
                                                else -> MaterialTheme.typography.bodyLarge
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp)
                                        )
                                    }
                                }

                                lyricLoading -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                else -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "暂无歌词",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 进度条 + 控制（固定底部）----
            val sliderValue = dragPosition
                ?: (if (duration > 0) position.toFloat() / duration else 0f)
            Column {
                Slider(
                    value = sliderValue,
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { f ->
                            if (duration > 0) PlayerManager.seekTo((f * duration).toLong())
                        }
                        dragPosition = null
                    },
                    enabled = duration > 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatMs(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatMs(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { PlayerManager.cycleMode() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = when (playMode) {
                            PlayMode.SEQUENCE -> Icons.Filled.Repeat
                            PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
                            PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                        },
                        contentDescription = playMode.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { PlayerManager.previous() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(32.dp)
                    )
                }
                FilledIconButton(
                    onClick = { PlayerManager.togglePlayPause() },
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "播放或暂停",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(
                    onClick = { PlayerManager.next() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(
                    onClick = { showQueue = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "播放队列",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheetContent(
                queue = queue,
                currentIndex = currentIndex,
                onPlay = { i ->
                    PlayerManager.playAt(i)
                    showQueue = false
                },
                onRemove = { PlayerManager.removeAt(it) }
            )
        }
    }
}

@Composable
private fun QueueSheetContent(
    queue: List<com.solara.music.data.Song>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    // v1.4.9：打开队列时定位到当前播放歌曲
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (currentIndex > 0) {
            runCatching { listState.scrollToItem(currentIndex) }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
    ) {
        Text(
            text = "播放队列（${queue.size} 首）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
        )
        LazyColumn(state = listState) {
            items(queue.size) { i ->
                val s = queue[i]
                val isCurrent = i == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(i) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCurrent) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Spacer(Modifier.size(20.dp))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = s.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = s.artistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onRemove(i) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * v1.4.17：校准行/打点工具栏专用紧凑文字按钮——
 * TextButton 自带 48dp 最小触摸宽度 + 较大水平内边距，六个按钮一行放不下
 * （"清打点"被挤到第二行）。此组件去最小宽度约束、内边距压到 6dp，
 * 视觉与 TextButton 一致但宽度按内容收缩，整行稳定单行。
 */
@Composable
private fun CompactTextButton(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

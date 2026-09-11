@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.solara.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.lyrics.LrcParser
import com.solara.music.player.PlayMode
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.CoverImage
import com.solara.music.ui.components.formatMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // v1.4.13 #62：歌词偏移校准（全局设置，歌词页可调）
    val settings by Store.settings.collectAsState()
    val lyricOffset = settings.lyricOffset

    var showQueue by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

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

    val currentLine = remember(lyrics, position, lyricOffset) {
        // v1.4.13 #62：应用用户校准的歌词偏移——正值延后、负值提前。
        // 例：偏移 +0.5s 时，播放位置 10.0s 按 9.5s 查歌词行（歌词晚半秒唱到）。
        LrcParser.indexOf(lyrics, position - (lyricOffset * 1000).toLong())
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
     * v1.4.13 #62：调节歌词偏移（±0.5s 步进，范围 ±10s），reset=true 直接归零。
     * 正值=歌词延后显示（歌快词慢时用"延后"），负值=提前。
     */
    fun adjustLyricOffset(delta: Float, reset: Boolean = false) {
        Store.updateSettings { s ->
            s.copy(
                lyricOffset = if (reset) 0f
                else (s.lyricOffset + delta).coerceIn(-10f, 10f)
            )
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
            // ---- 顶栏（向下划收起）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dismissDragModifier()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放队列")
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
                    // 封面页：封面 + 歌名/歌手 + 歌词预览（点歌名区域 → 歌词页）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(dismissDragModifier()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CoverImage(
                                song = current,
                                size = 280.dp,
                                corner = 28.dp
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                            ) {
                                Text(
                                    text = current?.displayName ?: "未在播放",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = current?.artistName ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            val isFavorite =
                                current != null && favorites.any { it.sameAs(current) }
                            IconButton(onClick = { current?.let { Store.toggleFavorite(it) } }) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite
                                    else Icons.Filled.FavoriteBorder,
                                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                    tint = if (isFavorite) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // v1.4.7：加入歌单（收藏键边上）
                            if (current != null) {
                                IconButton(onClick = { onAddToPlaylist(current) }) {
                                    Icon(
                                        Icons.Filled.PlaylistAdd,
                                        contentDescription = "加入歌单",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (current != null) {
                                IconButton(onClick = onDownload) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = "下载",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
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
                    // 歌词页：整页歌词（自动滚动跟随）
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = current?.displayName ?: "未在播放",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = current?.artistName ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 4.dp)
                        )
                        // v1.4.13 #62：歌词同步校准——歌词快了点"延后"、慢了点"提前"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { adjustLyricOffset(-0.5f) }) {
                                Icon(
                                    Icons.Filled.FastRewind,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.size(2.dp))
                                Text("提前", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                text = if (lyricOffset == 0f) "同步校准"
                                else "偏移 ${if (lyricOffset > 0) "+" else ""}${"%.1f".format(lyricOffset)}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            TextButton(onClick = { adjustLyricOffset(0.5f) }) {
                                Text("延后", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.size(2.dp))
                                Icon(
                                    Icons.Filled.FastForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (lyricOffset != 0f) {
                                TextButton(onClick = { adjustLyricOffset(0f, reset = true) }) {
                                    Text("重置", style = MaterialTheme.typography.labelSmall)
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
                                        Text(
                                            text = lyrics[i].text,
                                            textAlign = TextAlign.Center,
                                            color = if (i == currentLine) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.55f)
                                            },
                                            style = if (i == currentLine) {
                                                MaterialTheme.typography.titleMedium
                                            } else {
                                                MaterialTheme.typography.bodyLarge
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

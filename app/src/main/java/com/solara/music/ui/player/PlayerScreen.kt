@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.solara.music.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.TagEmbedder
import com.solara.music.lyrics.LrcLine
import com.solara.music.lyrics.LrcParser
import com.solara.music.player.PlayMode
import com.solara.music.player.PlayerManager
import com.solara.music.ui.components.CoverImage
import com.solara.music.ui.components.ShareHelper
import com.solara.music.ui.components.formatMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    // v1.5.1 r28：取歌链路标记（custom=自定义源/api=GD音乐台/local=本地/cache=缓存）
    val resolveSource by PlayerManager.resolveSource.collectAsState()
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

    /**
     * v1.4.32：拖动释放后的"锚定"进度——seek 生效前播放器位置仍是旧值，
     * 若立即清 dragPosition，slider 会先跳回拖动前的位置、seek 生效后再跳到
     * 目标位置（"先跑回再跑到"）。释放后把目标位置存进 seekAnchor 并保持
     * 显示，直到轮询位置真正到达目标附近（±500ms）或超时/切歌才解除。
     */
    var seekAnchor by remember { mutableStateOf<Float?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    // ---- v1.4.25：校准模式（原常驻校准行收进更多菜单）+ 歌词编辑 ----
    /** 校准模式：更多菜单「歌词校准」进入，显示校准行/打点工具栏，退出恢复简洁。 */
    // v1.4.58 第十二轮：remember(song)——切歌随重组同步重置（校准数据
    // 按歌独立；不再依赖 LaunchedEffect 协程清理，见下方打点状态注释）
    var calibMode by remember(song) { mutableStateOf(false) }
    /** 歌词编辑对话框：编辑当前歌词文本（LRC 原文或纯文本），保存后生效。 */
    var showLyricEditor by remember { mutableStateOf(false) }
    // v1.4.39：下载歌词对话框（本地歌无歌词时从歌词页空态/更多菜单进入）
    var showLyricDownload by remember { mutableStateOf(false) }
    // v1.4.41：封面编辑对话框（在线搜索匹配 / 相册选图）
    var showCoverEditor by remember { mutableStateOf(false) }

    // ---- v1.4.59 r18：分享需要下载确认框 ----
    // 无本地文件点「分享」→ 弹确认框；确认 = 跳到下载流程（品质选择
    // 下载），分享完全手动——下载完成后用户自己再点分享
    var shareDownloadTarget by remember { mutableStateOf<Song?>(null) }

    // ---- v1.4.16：逐句打点模式 ----
    // 打点模式：唱到当前句时点「打点」记录此刻播放位置为该句开唱时刻，
    // 自动跳到下一句；支持撤销上一条/跳过；退出即保存。
    // v1.4.58 第十二轮：remember(song)——切歌随重组同步重置，根治
    // r11 引入的跨歌残留：原 LaunchedEffect(song) 协程清理依赖调度时序，
    // 打点进行中切歌（holdAutoAdvance 停播后手动切下一首）时 tappingMode/
    // tappingMap 可能残留——tappingMode 残留 → currentLine 恒为 tappingLine
    // （新歌歌词不滚动）；tappingMap 残留 → 菜单误现「保存校准歌词」，
    // 点保存还会把上一首的打点时刻写进新歌 LRC（数据污染）。
    // remember 键变更 = 重组时同步重建状态，无时序依赖。
    var tappingMode by remember(song) { mutableStateOf(false) }
    var tappingLine by remember(song) { mutableStateOf(0) }
    /** 已打点结果：行索引 → 毫秒（退出打点模式时整体落盘）。 */
    var tappingMap by remember(song) { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    /** 该歌已保存的打点时间戳（显示歌词时优先使用）。 */
    var timestampsTick by remember { mutableStateOf(0) }
    val savedTimestamps = remember(song, timestampsTick) {
        song?.let { Store.lyricTimestampsOf(it) } ?: emptyMap()
    }

    // v1.4.58 第十二轮：切歌同步解除自动切歌抑制。打点/校准状态本身已由
    // remember(song) 随重组同步重置（见上），此 effect 只兜底全局标志——
    // holdAutoAdvance 是 PlayerManager 单例字段，不随组合状态重置。
    // （resolveAndPlay 侧按 holdKey 判曲目变化也会清，此处双保险）
    LaunchedEffect(song) {
        PlayerManager.releaseAutoAdvance()
    }

    // v1.4.58 第十一轮：播放页离开组合（收起/退出 App）时兜底解除
    // 自动切歌抑制——tappingMode 是本组合内的 remember 状态，收起即丢，
    // 若 holdAutoAdvance 残留 true，后续歌曲播完永远停在原地不切歌。
    // 打点数据本身随收起丢弃（与旧行为一致：未保存即弃）。
    DisposableEffect(Unit) {
        onDispose { PlayerManager.releaseAutoAdvance() }
    }

    // 循环滑动（v1.4.3 修复：v1.4.2 的"3页+边界弹回"方案有缺陷——停稳在歌词页
    // 也被弹回播放页。改为大页数交替排列：偶数页=封面、奇数页=歌词，
    // 从中间页开始，两个方向都能无限划，无需任何弹回逻辑）
    val pageCount = 1001
    val centerPage = pageCount / 2 // 500
    val pagerState = rememberPagerState(initialPage = centerPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val player = PlayerManager.playerOrNull

    // v1.4.26：顶部更多菜单为封面页/歌词页共用——从封面页点「歌词校准/歌词编辑」
    // 时自动翻到歌词页，进入校准/编辑即可直接看到歌词内容，无需再手动滑动
    fun scrollToLyricPage() {
        scope.launch {
            if (pagerState.currentPage % 2 == 0) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

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
        var anchorAtMs = 0L   // v1.4.32：锚定建立时刻（绝对超时兜底用）
        while (true) {
            player?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.takeIf { d -> d >= 0 } ?: 0L
                // v1.4.32：拖动释放后锚定目标位置——播放器位置到达目标附近才解除，
                // 期间 slider/时间文本稳定停在拖动落点，不回跳
                seekAnchor?.let { a ->
                    if (duration > 0) {
                        val targetMs = a * duration
                        val arrived = position >= targetMs - 500 && position <= targetMs + 500
                        // 兜底：seek 异常（如流式源不支持精确 seek）时 3 秒后强制回落实时位置，
                        // 避免进度条永远钉在目标处不动
                        val timedOut = System.currentTimeMillis() - anchorAtMs > 3000
                        if (arrived || timedOut) seekAnchor = null
                    } else seekAnchor = null
                } ?: run { anchorAtMs = System.currentTimeMillis() }
            }
            delay(200L)
        }
    }

    // v1.4.32：切歌解除锚定（新歌位置从 0 开始，旧锚定无意义）
    LaunchedEffect(song) {
        seekAnchor = null
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
    // v1.4.53/54/55 三轮修复后用户实测仍冻结。v1.4.56 结构性重构定稿：
    // 根因是「同一 LazyListState 被 Pager 多页共享」——1001 页交替排列下
    // 所有歌词页共用顶层 listState、封面页共用 previewState；翻页动画期间
    // 新旧两页短暂共存，加预组合后相邻两页长期共存，同一 state 被多个
    // LazyColumn 同时持有（Compose 不支持），手势处理错乱 → 歌词滚动冻结。
    // 主页面 Pager（4 页各含 LazyColumn，翻页后滚动正常）的差异佐证：
    // 每页 Screen 内部各自 rememberLazyListState，无共享。
    // 重构：每页独立 state（remember 在页内容里，页面销毁即丢弃），
    // 跟随 effect 移入页内组合，翻页后新页 state 全新、跟随立即定位当前句。
    var lyricFollowPausedUntil by remember { mutableStateOf(0L) }
    /** v1.4.54：暂停窗口到期时 tick 一下，驱动「到期主动恢复」effect */
    var followResumeTick by remember { mutableStateOf(0) }
    // v1.4.54：歌词拖动观察者（歌词页 LazyColumn 与封面页预览 LazyColumn 共用）。
    // Initial pass 旁路观察（不消费事件，滚动仍由 LazyColumn 正常处理）+
    // 垂直方向过滤——累计垂直位移超过 touchSlop 才刷新暂停窗口。
    // v1.4.55：累计改为带符号净位移（每次按下重置）+ 水平主导性检查——
    // 原绝对值累计会把「对角翻页手势」的微小垂直分量也累计进去误刷新暂停
    // 窗口；净位移 + 「垂直分量明显大于水平分量」双条件，只有真正的垂直
    // 拖动才刷新窗口，水平/对角翻页绝不触发。
    fun lyricDragObserver() = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            val touchSlop = viewConfiguration.touchSlop
            while (true) {
                var netDragY = 0f
                var netDragX = 0f
                // 等一次按下
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    if (down.changes.any { it.pressed }) break
                    netDragY = 0f
                    netDragX = 0f
                }
                // 按住期间累计带符号净位移。sumOf 无 Float 重载，用 fold
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = ev.changes.any { it.pressed }
                    if (pressed) {
                        netDragY += ev.changes.fold(0f) { acc, c ->
                            acc + c.positionChange().y
                        }
                        netDragX += ev.changes.fold(0f) { acc, c ->
                            acc + c.positionChange().x
                        }
                        // 垂直净位移超 slop 且明显大于水平分量 → 用户拖歌词
                        if (abs(netDragY) > touchSlop &&
                            abs(netDragY) > abs(netDragX) * 1.5f
                        ) {
                            lyricFollowPausedUntil = System.currentTimeMillis() + 5000L
                        }
                    } else break
                }
            }
        }
    }
    // 切歌重置暂停窗口——新歌应立即恢复自动跟随，不被上一首的拖动暂停拖累
    LaunchedEffect(song) {
        lyricFollowPausedUntil = 0L
        followResumeTick = 0
    }
    // v1.4.54：暂停窗口到期 → tick 驱动页内「到期主动恢复」effect
    // （间奏无新句推进时也能及时滚回当前句）
    LaunchedEffect(lyricFollowPausedUntil) {
        val remaining = lyricFollowPausedUntil - System.currentTimeMillis()
        if (remaining > 0) {
            delay(remaining)
            followResumeTick++
        }
    }
    // v1.4.55：自研水平翻页手势——替代 Pager 自带手势（userScrollEnabled=false）。
    // 根因：Pager 的 scrollable 手势与 LazyColumn 垂直滚动手势竞争，翻页后
    // 内部手势状态纠缠，导致歌词滚动冻结（Compose 1.6 已知问题）。自研检测
    // 在 Initial pass 旁路观察水平位移，手指抬起后程序化翻页：
    // - 水平检测与垂直滚动天然正交，互不干扰
    // - 翻页发生在手指抬起后，目标页在无手势进行时组合，检测器干净启动
    // - 不消费任何事件，LazyColumn 滚动完全正常
    fun pagerSwipeModifier() = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            val touchSlop = viewConfiguration.touchSlop
            while (true) {
                var netDragX = 0f
                var netDragY = 0f
                // 等一次按下
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    if (down.changes.any { it.pressed }) break
                    netDragX = 0f
                    netDragY = 0f
                }
                // 按住期间累计净位移
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (!ev.changes.any { it.pressed }) break
                    netDragX += ev.changes.fold(0f) { acc, c ->
                        acc + c.positionChange().x
                    }
                    netDragY += ev.changes.fold(0f) { acc, c ->
                        acc + c.positionChange().y
                    }
                }
                // 手指抬起后判定：水平净位移超 slop 且明显大于垂直分量 → 翻页
                if (abs(netDragX) > touchSlop && abs(netDragX) > abs(netDragY) * 1.5f) {
                    val target = if (netDragX < 0) pagerState.currentPage + 1
                    else pagerState.currentPage - 1
                    if (target in 0 until pagerState.pageCount) {
                        scope.launch {
                            pagerState.animateScrollToPage(target)
                        }
                    }
                }
            }
        }
    }

    val current = song

    // v1.4.58 第十一轮：播放页拦截系统返回键 = 收起播放页。
    // 根因：OnBackPressedDispatcher 按"后注册先分发"（LIFO）工作——
    // 播放页覆盖在主界面/歌单详情之上，但此前自己没有 BackHandler，
    // 返回键先被底层的歌单详情 BackHandler（PlaylistsScreen）吃掉
    // 隐形关闭详情（被播放页盖住看不见），第二次才关播放页——
    // 用户看到"按两次、落在歌单列表"而不是歌曲列表。
    // 播放页组合晚于底层页面 → 此 BackHandler 注册最晚 → 最先分发，
    // 一次返回即收起播放页，落回打开前的页面（歌单详情/歌曲列表）。
    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }

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
     * v1.5.1 r44：嵌入范围扩大——在线下载存量歌（文件在 D_Music，id 非
     * local:）同样定位本地文件强制覆盖嵌入（此前只认本地导入歌，校准
     * 只进 App 缓存不落文件——FLAC 同目录 .lrc 不更新校准时间的根因）。
     */
    fun saveCalibratedLyric() {
        val s = song ?: return
        val lrc = buildCalibratedLrc() ?: return
        val ctx = context.applicationContext
        scope.launch {
            val isLocal = LocalCoverExtractor.isLocalSong(s)
            // v1.5.1 r44：在线下载存量歌定位本地文件（找到才嵌入）
            var onlineFileName: String? = null
            if (!isLocal) {
                onlineFileName = withContext(Dispatchers.IO) {
                    runCatching {
                        com.solara.music.data.DownloadManager.findLocalFileAbsPath(ctx, s)
                    }.getOrNull()?.let { java.io.File(it).name }
                }
            }
            val hasLocalFile = isLocal || onlineFileName != null
            val embedded = if (hasLocalFile) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        TagEmbedder.embedLyricInto(ctx, s, lrc, fileNameOverride = onlineFileName)
                    }.getOrDefault(false)
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
            // v1.4.58 第十一轮：固化后解除自动切歌抑制
            PlayerManager.releaseAutoAdvance()
            vm.refreshLyrics()
            Toast.makeText(
                ctx,
                when {
                    hasLocalFile && embedded -> "校准歌词已嵌入文件，以后播放无需再调"
                    hasLocalFile -> "校准歌词已保存到缓存（嵌入文件失败）"
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = current?.artistName ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(top = 1.dp)
                        )
                        // v1.5.1 r31：取歌链路徽标常显——自定义源高亮主色，
                        // 其余链路（GD/本地/缓存）中性色。常显的价值：
                        // 用户随时能看到当前歌走哪条链路；切策略/换歌后徽标
                        // 变化直观可验证（之前只在 custom 时显示，其他情况
                        // "没徽标"无法区分"走了 GD"还是"功能没生效"）
                        // v1.5.1 r66：文案精简（GD API→GD、自定义源→LX）+
                        // 字号改小三级（labelSmall 11sp → 8sp 自定义）
                        val badge = when (resolveSource) {
                            "custom" -> "LX"
                            "api" -> "GD"
                            "local" -> "本地"
                            "cache" -> "缓存"
                            else -> null
                        }
                        if (badge != null) {
                            val isCustom = resolveSource == "custom"
                            Text(
                                text = badge,
                                fontSize = 8.sp,
                                lineHeight = 8.sp,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCustom) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .background(
                                        if (isCustom) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
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
                            // v1.4.59 r18：分享（下载后、封面编辑前）——分享必须
                            // 有本地文件：有文件（已下载/本地导入）直接分享文件本体；
                            // 没有则弹「分享需要下载」确认框——确认跳到下载流程
                            // （品质选择下载），下载完成后用户手动再点分享
                            DropdownMenuItem(
                                text = { Text("分享") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Share, null)
                                },
                                onClick = {
                                    menuOpen = false
                                    current?.let { song ->
                                        scope.launch {
                                            if (!ShareHelper.shareFile(context, song)) {
                                                shareDownloadTarget = song
                                            }
                                        }
                                    }
                                }
                            )
                            // v1.4.41：封面编辑（在线搜索匹配 / 相册选图自定义）
                            // v1.4.42：移到「下载」后、「歌词校准」前
                            DropdownMenuItem(
                                text = { Text("封面编辑") },
                                leadingIcon = {
                                    Icon(Icons.Filled.AddPhotoAlternate, null)
                                },
                                onClick = {
                                    menuOpen = false
                                    showCoverEditor = true
                                }
                            )
                            // v1.4.25：歌词校准入口（原常驻校准行收进菜单——不常用，
                            // 收起来页面更简洁）。进入后歌词页顶部显示校准行/打点工具栏
                            DropdownMenuItem(
                                text = { Text(if (calibMode) "退出歌词校准" else "歌词校准") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Tune, null)
                                },
                                onClick = {
                                    menuOpen = false
                                    if (tappingMode) {
                                        // v1.5.1 r24：打点已即时落盘，退出校准
                                        // 不再丢数据——只收编辑态，数据在 Store
                                        tappingMode = false
                                        tappingMap = emptyMap()
                                        tappingLine = 0
                                        PlayerManager.releaseAutoAdvance()
                                    }
                                    calibMode = !calibMode
                                    // v1.4.26：进入校准时自动翻到歌词页（封面页点进来的场景）
                                    if (calibMode) scrollToLyricPage()
                                }
                            )
                            // v1.4.25：歌词编辑——无歌词时自己输入，有歌词时修改文本
                            DropdownMenuItem(
                                text = { Text("歌词编辑") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.NoteAdd, null)
                                },
                                onClick = {
                                    menuOpen = false
                                    showLyricEditor = true
                                    // v1.4.26：编辑保存后需要看到歌词页效果，先翻过去
                                    scrollToLyricPage()
                                }
                            )
                            // v1.4.39：下载歌词（在线搜词：歌名 或 歌名+歌手；
                            // 本地歌嵌文件，在线歌存缓存——所有歌统一入口）
                            DropdownMenuItem(
                                text = { Text("下载歌词") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Lyrics, null)
                                },
                                onClick = {
                                    menuOpen = false
                                    showLyricDownload = true
                                    scrollToLyricPage()
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
            // v1.4.55：关闭 Pager 自带手势（userScrollEnabled=false），翻页改由
            // pagerSwipeModifier 自研水平检测驱动（挂在 Pager 容器上）——消除
            // Pager scrollable 与 LazyColumn 垂直滚动的手势竞争。
            // v1.4.56：去掉 beyondBoundsPageCount 预组合——每页独立 state 后
            // 不再需要；保持默认单页组合，翻页动画期间新旧页短暂共存但 state
            // 各自独立，无共享冲突。
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(pagerSwipeModifier())
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
                            // v1.4.56：封面页预览同样独立 state + 页内跟随
                            val previewListState = rememberLazyListState()
                            LaunchedEffect(currentLine, lyricFollowPausedUntil) {
                                if (currentLine >= 0 && !previewListState.isScrollInProgress &&
                                    System.currentTimeMillis() >= lyricFollowPausedUntil
                                ) {
                                    previewListState.animateScrollToItem(
                                        currentLine.coerceAtLeast(0), 0
                                    )
                                }
                            }
                            LazyColumn(
                                state = previewListState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .then(lyricDragObserver())
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
                        // v1.4.25：校准控件收进更多菜单——仅校准模式（calibMode）
                        // 时显示，平时歌词页顶部干净无控件
                        if (tappingMode) {
                            // ---- v1.4.16：打点模式工具栏（v1.4.17 紧凑化：单行） ----
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (tappingMap.isNotEmpty())
                                        "已打 ${tappingMap.size}/${lyrics.size} 句，唱到高亮句时点「打点」（第 ${tappingLine + 1} 句）"
                                    else
                                        "唱到高亮句时点「打点」（第 ${tappingLine + 1}/${lyrics.size} 句）",
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
                                        // v1.5.1 r24：即时落盘——中途退出/收起/切歌不丢
                                        if (tappingLine < lyrics.size) {
                                            tappingMap = tappingMap + (tappingLine to position)
                                            tappingLine++
                                            song?.let { Store.saveLyricTimestamps(it, tappingMap) }
                                            timestampsTick++
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
                                        // v1.5.1 r24：同步落盘（可撤销已保存的点）
                                        val lastIdx = tappingMap.keys.maxOrNull()
                                        if (lastIdx != null) {
                                            tappingMap = tappingMap - lastIdx
                                            tappingLine = lastIdx
                                            song?.let { Store.saveLyricTimestamps(it, tappingMap) }
                                            timestampsTick++
                                        }
                                    }) { Text("撤销", style = MaterialTheme.typography.labelMedium) }
                                    CompactTextButton(onClick = {
                                        // 跳过：这句不打点（保留 LRC 原时间），指针前进
                                        if (tappingLine < lyrics.size - 1) tappingLine++
                                    }) { Text("跳过", style = MaterialTheme.typography.labelMedium) }
                                    CompactTextButton(onClick = {
                                        // 完成：合并已有打点（跳过的句保留旧值）并保存退出
                                        // v1.5.1 r24：打点已即时落盘，这里只收尾
                                        val merged = savedTimestamps + tappingMap
                                        song?.let { Store.saveLyricTimestamps(it, merged) }
                                        timestampsTick++
                                        tappingMode = false
                                        tappingMap = emptyMap()
                                        tappingLine = 0
                                        // v1.4.58 第十一轮：落盘后解除自动切歌抑制
                                        PlayerManager.releaseAutoAdvance()
                                    }) { Text("完成", style = MaterialTheme.typography.labelMedium) }
                                    // v1.4.25：退出打点（未保存打点丢弃）
                                    // v1.5.1 r24：打点已即时落盘，退出不再丢数据——
                                    // tappingMap 清空只是退出编辑态，数据在 Store 里
                                    CompactTextButton(onClick = {
                                        tappingMode = false
                                        tappingMap = emptyMap()
                                        tappingLine = 0
                                        // v1.4.58 第十一轮：退出打点即解除自动切歌抑制
                                        PlayerManager.releaseAutoAdvance()
                                    }) { Text("退出", style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        } else if (calibMode) {
                            // ---- v1.4.25：校准模式工具栏（原 v1.4.15 常驻校准行迁移至此） ----
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
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
                                    // v1.5.1 r24：续打——已保存的打点装入工作区，
                                    // 指针跳到第一个未打句，从断点继续而非从头再来
                                    CompactTextButton(onClick = {
                                        if (lyrics.isNotEmpty()) {
                                            val existing = savedTimestamps
                                            tappingMap = existing
                                            tappingLine = (0 until lyrics.size)
                                                .firstOrNull { !existing.containsKey(it) }
                                                ?: (lyrics.size - 1) // 全打完：停最后一句可重打
                                            tappingMode = true
                                            // v1.4.58 第十一轮：打点进行中抑制自动切歌——
                                            // 歌曲播完停在原地等保存，保住未落盘的打点数据
                                            song?.let { PlayerManager.holdAutoAdvanceFor(it) }
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
                                Text(
                                    text = "调好偏移或打点后，用「保存校准歌词」固化；完成点「退出校准」",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = { calibMode = false }) {
                                    Text("退出校准", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            when {
                                lyrics.isNotEmpty() -> {
                                    // v1.4.56：每页独立 LazyListState——remember 在页内容
                                    // 里，页面销毁即丢弃；翻页后新页 state 全新，无跨页
                                    // 共享（共享 state 被多 LazyColumn 同时持有是手势
                                    // 冻结的根因）。跟随 effect 也在页内，只驱动本页。
                                    val pageListState = rememberLazyListState()
                                    // 常规跟随：当前句推进时滚动（暂停窗口内不跟随）
                                    LaunchedEffect(currentLine, lyricFollowPausedUntil) {
                                        if (currentLine >= 0 && !pageListState.isScrollInProgress &&
                                            System.currentTimeMillis() >= lyricFollowPausedUntil
                                        ) {
                                            pageListState.animateScrollToItem(
                                                currentLine.coerceIn(
                                                    0, (lyrics.size - 1).coerceAtLeast(0)
                                                ),
                                                0
                                            )
                                        }
                                    }
                                    // 暂停窗口到期 → 主动滚回当前句（间奏无新句也恢复）
                                    LaunchedEffect(followResumeTick) {
                                        if (followResumeTick > 0 && currentLine >= 0 &&
                                            !pageListState.isScrollInProgress
                                        ) {
                                            pageListState.animateScrollToItem(
                                                currentLine.coerceIn(
                                                    0, (lyrics.size - 1).coerceAtLeast(0)
                                                ),
                                                0
                                            )
                                        }
                                    }
                                    // 打点模式跟随打点指针
                                    LaunchedEffect(tappingLine, tappingMode) {
                                        if (tappingMode && !pageListState.isScrollInProgress &&
                                            System.currentTimeMillis() >= lyricFollowPausedUntil
                                        ) {
                                            runCatching {
                                                pageListState.animateScrollToItem(
                                                    tappingLine.coerceAtLeast(0)
                                                )
                                            }
                                        }
                                    }
                                    LazyColumn(
                                        state = pageListState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(lyricDragObserver()),
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
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "暂无歌词",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.55f)
                                        )
                                        // v1.4.39：无歌词 → 引导在线下载（所有歌统一）
                                        if (current != null) {
                                            Spacer(Modifier.height(12.dp))
                                            TextButton(onClick = { showLyricDownload = true }) {
                                                Icon(
                                                    Icons.Filled.Lyrics,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.size(4.dp))
                                                Text("下载歌词")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 进度条 + 控制（固定底部）----
            // v1.4.32：拖动中显示拖动位置；释放后锚定目标位置（seekAnchor），
            // 直到播放器位置真正到达才回落到实时位置——消除"先跳回旧位置再跳到目标"
            val sliderValue = dragPosition
                ?: seekAnchor
                ?: (if (duration > 0) position.toFloat() / duration else 0f)
            // v1.4.32：时间文本与 slider 同源（拖动/锚定期间显示目标时间，不回跳）
            val displayedPositionMs = when {
                dragPosition != null && duration > 0 -> (dragPosition!! * duration).toLong()
                seekAnchor != null && duration > 0 -> (seekAnchor!! * duration).toLong()
                else -> position
            }
            Column {
                Slider(
                    value = sliderValue,
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { f ->
                            if (duration > 0) {
                                PlayerManager.seekTo((f * duration).toLong())
                                seekAnchor = f   // v1.4.32：锚定目标位置直到 seek 真正生效
                            }
                        }
                        dragPosition = null
                    },
                    enabled = duration > 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatMs(displayedPositionMs),
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
                            // 顺序播放：播完即停（不循环）——箭头单向往右
                            PlayMode.SEQUENCE -> Icons.AutoMirrored.Filled.ArrowRightAlt
                            // 顺序循环：播完最后一首绕回第一首
                            PlayMode.LIST_LOOP -> Icons.Filled.Repeat
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

    // ---- v1.4.25：歌词编辑对话框 ----
    if (showLyricEditor) {
        LyricEditorDialog(
            song = current,
            lyrics = lyrics,
            onDismiss = { showLyricEditor = false },
            onSaved = {
                // 保存后刷新显示（缓存优先级最高，立即生效）
                vm.refreshLyrics()
                showLyricEditor = false
            }
        )
    }

    // ---- v1.4.39：下载歌词对话框（本地歌在线搜词） ----
    if (showLyricDownload && current != null) {
        com.solara.music.ui.components.LyricDownloadDialog(
            song = current,
            onDismiss = { showLyricDownload = false },
            onDownloaded = { vm.refreshLyrics() }
        )
    }

    // ---- v1.4.41：封面编辑对话框（在线搜索 / 相册选图） ----
    if (showCoverEditor && current != null) {
        com.solara.music.ui.components.CoverEditorDialog(
            song = current,
            onDismiss = { showCoverEditor = false }
        )
    }

    // ---- v1.4.59 r18：分享需要下载确认框（分享手动） ----
    // 无本地文件点「分享」→ 提醒分享需要下载；确认 = 跳到下载流程
    // （品质选择下载），取消 = 退出分享。下载完成后用户自己再点
    // 「分享」分享文件——全程手动，不做自动等待/自动分享
    shareDownloadTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { shareDownloadTarget = null },
            title = { Text("分享需要下载歌曲") },
            text = {
                Text(
                    "「${target.displayName} - ${target.artistName}」还没有下载到本地。\n" +
                        "分享歌曲文件需要先下载。去下载吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    shareDownloadTarget = null
                    // 跳到下载流程（品质选择弹窗），与「下载」菜单一致
                    onDownload()
                }) { Text("去下载") }
            },
            dismissButton = {
                TextButton(onClick = { shareDownloadTarget = null }) { Text("取消") }
            }
        )
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

/**
 * v1.4.25：歌词编辑对话框——无歌词时自己输入，有歌词时修改文本。
 *
 * - 预填当前歌词原文：优先 LRC 格式（保留时间轴，改文本不破坏同步）；
 *   纯文本歌词（无时间轴）按行预填。
 * - 保存：写磁盘缓存（取词优先级缓存最高）+ 本地歌嵌入文件
 *   （MP3 USLT / FLAC 伴生 .lrc，跨播放器生效）+ 清校准数据
 *   （行数可能变了，旧打点/偏移失效防错位）。
 * - 纯文本（无时间标签）也能存——LrcParser 会按行生成伪时间轴，
 *   配合「歌词校准→打点」逐句对齐后「保存校准歌词」固化成真 LRC。
 */
@Composable
private fun LyricEditorDialog(
    song: com.solara.music.data.Song?,
    lyrics: List<LrcLine>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 预填文本（v1.5.1 修复）：优先读磁盘缓存原文——用户上次保存的
    // 原样回填（含无标签行，不被自动补时间标签）；缓存没有时（在线
    // 歌词未缓存等场景）回退按解析行重建 LRC（保留时间轴）。
    // 之前从解析行重建会把用户追加的无标签行自动补上时间标签，
    // 与用户输入原文不一致，且旧版解析器会丢弃无标签行导致
    // 「换行文本保存后消失」。
    val initialText = remember(song) {
        val cached = song?.let { runCatching { Store.cachedLyric(it) }.getOrNull() }
        when {
            cached != null -> cached
            lyrics.isEmpty() -> ""
            else -> lyrics.joinToString("\n") { line ->
                val totalMs = (line.time * 1000).toLong()
                "[%02d:%02d.%03d]".format(
                    totalMs / 60000, totalMs / 1000 % 60, totalMs % 1000
                ) + line.text
            }
        }
    }
    var text by remember(song) { mutableStateOf(initialText) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("歌词编辑") },
        text = {
            Column {
                Text(
                    text = "支持 LRC 格式（[mm:ss.xxx] 歌词）或纯文本（每行一句）。" +
                        "纯文本保存后用「歌词校准→打点」逐句对齐。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp, max = 420.dp),
                    placeholder = { Text("输入或粘贴歌词…") },
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = song ?: return@TextButton
                    val content = text.trim()
                    if (content.isEmpty()) return@TextButton
                    saving = true
                    val ctx = context.applicationContext
                    scope.launch {
                        val isLocal = LocalCoverExtractor.isLocalSong(s)
                        // v1.5.1 r45：在线下载存量歌（文件在 D_Music，id 非
                        // local:）定位本地文件后同样强制嵌入——此前只认本地
                        // 导入歌，编辑后的歌词只进 App 缓存不落文件
                        var onlineFileName: String? = null
                        if (!isLocal) {
                            onlineFileName = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.solara.music.data.DownloadManager.findLocalFileAbsPath(ctx, s)
                                }.getOrNull()?.let { java.io.File(it).name }
                            }
                        }
                        val hasLocalFile = isLocal || onlineFileName != null
                        val embedded = if (hasLocalFile) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    TagEmbedder.embedLyricInto(ctx, s, content, fileNameOverride = onlineFileName)
                                }.getOrDefault(false)
                            }
                        } else false
                        withContext(Dispatchers.IO) { Store.saveCachedLyric(s, content) }
                        // 行数/内容可能变了：清校准数据防错位
                        Store.saveLyricOffset(s, 0f)
                        Store.saveLyricTimestamps(s, emptyMap())
                        Toast.makeText(
                            ctx,
                            when {
                                hasLocalFile && embedded -> "歌词已保存并嵌入文件"
                                hasLocalFile -> "歌词已保存（嵌入文件失败）"
                                else -> "歌词已保存"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        onSaved()
                    }
                },
                enabled = !saving && text.isNotBlank()
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) { Text("取消") }
        }
    )
}

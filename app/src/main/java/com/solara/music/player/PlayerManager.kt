package com.solara.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.solara.music.customsource.CustomSourceManager
import com.solara.music.data.DownloadManager
import com.solara.music.data.MusicApi
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.ui.components.CoverCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class PlayMode(val label: String) {
    /** 顺序播放：按顺序播完最后一首即停止（不绕回）。 */
    SEQUENCE("顺序播放"),

    /** 顺序循环：播完最后一首自动回到第一首（v1.4.26 与"顺序播放"拆分）。 */
    LIST_LOOP("顺序循环"),

    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放")
}

/** 退出 App 的应用内广播 Action。 */
const val ACTION_EXIT_APP = "com.solara.music.action.EXIT_APP"

/**
 * 全局播放控制器：ExoPlayer 由 [PlaybackService] 创建并通过 [attachPlayer] 注入，
 * 这里只负责队列、播放模式、直链解析与状态暴露。
 *
 * 播放策略：已下载到本地的歌曲优先播本地文件（离线可用），否则解析在线直链。
 */
object PlayerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var playerRef: ExoPlayer? = null
    @Volatile private var appContext: Context? = null

    /** v1.4.13 #61：记住 application context，服务被杀后点播放时自愈重启服务。 */
    @Volatile private var bootContext: Context? = null

    /** 安全访问：Service 尚未创建 player 时返回 null，UI 侧应使用此属性避免崩溃。 */
    val playerOrNull: ExoPlayer? get() = playerRef

    val player: ExoPlayer
        get() = playerRef ?: error("PlayerManager: player 未附加，请先调用 ensureService()")

    val queue = MutableStateFlow<List<Song>>(emptyList())
    val currentIndex = MutableStateFlow(-1)
    val isPlaying = MutableStateFlow(false)
    val playMode = MutableStateFlow(PlayMode.SEQUENCE)

    /** v1.4.26：播放模式持久化（App 启动时由 SolaraApp 调 restorePlayMode）。 */
    fun restorePlayMode() {
        val saved = Store.readPlayMode() ?: return
        playMode.value = saved
    }

    /**
     * 播放失败事件（v1.4.13 #65）：解析失败/网络错误时发射，UI 层收集展示提示。
     * SharedFlow 挂了就丢——提示类事件不需要补播。
     */
    val playError = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val currentSong: StateFlow<Song?> = combine(queue, currentIndex) { songs, i ->
        songs.getOrNull(i)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var pendingJob: Job? = null
    /** v1.4.59 r19：后台缓存补全任务（当前曲目装载成功后启动，换曲取消）。 */
    private var backfillJob: Job? = null
    private var consecutiveFailures = 0
    private var attached = false

    /** 服务尚未就绪时用户已点播放：attachPlayer 后自动从当前曲目续播。 */
    @Volatile private var pendingPlayOnAttach = false

    /** 连续解析失败达到此次数后停止自动跳歌，避免"一直滚动曲目"。 */
    private val maxConsecutiveFailures = 3

    /**
     * v1.4.29：当前曲目是否用的是持久化直链兜底（API 失败 + 音频全量缓存）。
     * 播放器报错时据此清掉该直链并重解析一次，防止坏直链反复重试。
     */
    @Volatile private var playingFromUrlCache = false
    @Volatile private var urlCacheSong: Song? = null
    @Volatile private var urlCacheBr: String? = null
    @Volatile private var urlCacheRetryDone = false

    /**
     * v1.4.30：当前曲目播放器错误是否已原地重试过。
     * 在线歌直链有时效（部分音源几分钟即过期），播放中 403 断流后
     * 重新解析直链即可原地恢复——比直接跳歌体验好（不打断听感、
     * 不浪费跳歌）。每首歌最多一次，resolveAndPlay 装载时重置。
     */
    @Volatile private var playerErrorRetryDone = false

    /**
     * v1.4.59 r22：最近一次装载的曲目 key（source:id）。
     * 用于 resolveAndPlay 判断"曲目是否真的变了"——只有换曲才重置
     * playerErrorRetryDone（同曲重装载保留重试标志，防错误重试无限循环）。
     */
    @Volatile private var lastLoadedKey: String? = null

    /**
     * v1.5.1 r28：最近一次取歌链路（custom=自定义源 / api=GD音乐台 /
     * local=本地文件 / cache=缓存直链兜底 / null=未播放）。
     * 播放页显示小标记，用户可直观确认当前歌走的是哪条链路。
     */
    val resolveSource = MutableStateFlow<String?>(null)

    /**
     * v1.4.30：连续失败停止播放时的回调——PlaybackService 侧用来重建
     * 占位通知。Media3 在 IDLE+空 timeline 时会撤掉媒体通知，用户会
     * 误以为"App 退出了"；补一张暂停态占位通知（与冷启动样式一致），
     * 点播放键走 togglePlayPause 自愈路径恢复，体验是"暂停"而非"退出"。
     */
    var onPlaybackHalted: (() -> Unit)? = null

    /**
     * v1.4.35：熄屏播放中断检测（真机省电策略指纹）。
     * onPlayerError 时屏幕熄灭 → 大概率是 ROM 冻结/限制后台（CPU 休眠
     * 断流走错误链）。UI 回前台时读此标志弹保活引导，消费后清零。
     *
     * v1.4.36 重做：不再看 isPlaying 瞬时值——断流时 ExoPlayer 先进
     * BUFFERING（isPlaying 已 false）再报错，原条件永不成立。改为
     * "用户播放意图"跟踪：主动播放过且未主动暂停/停止 = 想播；错误时
     * 屏幕熄灭即算中断（亮屏下的网络错误是正常场景，不算）。
     */
    @Volatile var screenOffInterrupted = false
        private set

    /**
     * v1.4.36：用户播放意图——true 表示用户主动播放过且未主动暂停/停止。
     * 区别于 isPlaying（播放器瞬时状态，断流 BUFFERING 时会翻 false）：
     * 意图只在用户主动操作（togglePlayPause 播放 / playAt / next 等）
     * 时置 true，在用户主动暂停/停止时置 false。
     */
    @Volatile private var userWantsPlayback = false

    /**
     * v1.4.36：App 是否在前台（MainActivity onStart/onStop 维护——
     * App 仅此一个 Activity，其生命周期即 App 前后台）。
     * 用于熄屏中断检测的补充指纹：MIUI 冻结进程场景下，解冻时错误
     * 才触发，此时屏幕可能已亮（用户刚回前台），但 App 前后状态
     * 切换与错误回调存在时间差，"错误发生时 App 在后台"是更稳的信号。
     */
    @Volatile var appInForeground = false

    /**
     * v1.4.58 第十一轮：打点模式抑制自动切歌。
     *
     * 歌词逐句打点进行中（PlayerScreen tappingMode=true）时歌曲自然播完
     * 不自动切下一首——切歌会丢弃未落盘的打点数据（tappingMap 是 UI 状态，
     * LaunchedEffect(song) 切歌即清），用户只能从头重打。置此标志后
     * onEnded 停在当前曲目（STATE_ENDED），等用户「完成」落盘或
     * 「保存校准歌词」固化；手动切歌/点播放不受影响。
     *
     * v1.4.58 第十二轮：holdAutoAdvance 只应作用于"打点的那首歌"。
     * resolveAndPlay 按 holdKey（source:id）判断曲目是否真的变了：
     * 同曲重播（打点中点播放键重听）保留抑制；换曲装载（手动切歌/
     * 后台通知栏切歌，UI 不在场）即解除——否则抑制残留导致新歌播完
     * 永远停在原地不切歌。
     */
    @Volatile var holdAutoAdvance = false

    /** v1.4.58 第十二轮：holdAutoAdvance 生效中的曲目 key（source:id）。 */
    @Volatile private var holdKey: String? = null

    /** v1.4.58 第十二轮：进入打点模式时由 UI 调用（记录生效曲目）。 */
    fun holdAutoAdvanceFor(song: Song) {
        holdKey = "${song.source}:${song.id}"
        holdAutoAdvance = true
    }

    /** v1.4.58 第十二轮：退出打点/保存后由 UI 调用（解除抑制）。 */
    fun releaseAutoAdvance() {
        holdKey = null
        holdAutoAdvance = false
    }

    /**
     * v1.4.58 第五轮：当前曲目是否"零轨道被选中"（设备无解码器）。
     *
     * 背景：ExoPlayer 对无渲染器支持的轨道不报错——轨道选择失败后
     * 所有渲染器禁用，renderersEnded 恒 true + 时长未知（Unseekable
     * SeekMap）时 doSomeWork 直接 setState(STATE_ENDED)，静默秒结束。
     * onEnded 自动切歌 → 重新装载 → 再秒结束 → 无限循环且无提示
     * （DTS 在无解码器设备上的实测症状："点击后歌曲一直循环"）。
     *
     * 修复：onTracksChanged 检测"有轨道组但零选中"置此标志；
     * onEnded 见标志即停（不自动切歌）+ 提示一次；resolveAndPlay
     * 装载新曲目时重置。
     */
    @Volatile private var noTrackSelected = false

    /** v1.4.35：UI 消费中断标志（读取并清零）。 */
    fun consumeScreenOffInterrupted(): Boolean {
        val v = screenOffInterrupted
        screenOffInterrupted = false
        return v
    }

    /**
     * 启动后台播放服务（幂等）。仅在 App 前台调用（MainActivity.onCreate），
     * 用 startService 即可：Service onCreate 会立即 startForeground 常驻通知，
     * 不触发 startForegroundService 的 5 秒约束。
     *
     * v1.4.34：来电返回等竞态窗口下 startService 可能抛
     * BackgroundServiceStartNotAllowedException（Android 12+）——通话期间
     * uid 已是后台（bg:+5m5s），挂断返回时 onStart 先于 uid 状态回升执行。
     * 此时绝不能让异常穿透闪退：吞掉并延迟重试（此时 App 已真正回到前台，
     * startService 合法）；服务未起时点播放走 ensureServiceAlive 自愈链路。
     */
    fun ensureService(context: Context) {
        val appCtx = context.applicationContext
        bootContext = appCtx
        try {
            appCtx.startService(Intent(appCtx, PlaybackService::class.java))
        } catch (e: Exception) {
            // 竞态窗口：uid 尚为后台态。App 正在回到前台，稍后重试即可。
            Log.w("PlayerManager", "startService 被拒（后台竞态），1.5s 后重试", e)
            scope.launch {
                delay(1500)
                runCatching {
                    appCtx.startService(Intent(appCtx, PlaybackService::class.java))
                }.onFailure {
                    Log.w("PlayerManager", "重试 startService 仍失败，等待点播放自愈", it)
                }
            }
        }
    }

    /**
     * v1.4.13 #61：服务被系统杀掉但进程还活着时，用户从最近任务返回 App
     * 只走 onStart/onResume（不重走 onCreate → ensureService），playerRef
     * 已为 null，点播放将永远无反应。此处在点播放时尝试重启服务；
     * 重启后 attachPlayer 会消费 pendingPlayOnAttach 自动续播。
     *
     * v1.4.34：同样包住 BackgroundServiceStartNotAllowedException——
     * 用户点播放时 App 必在前台，但 uid 状态回升可能滞后于点击（同竞态）。
     */
    private fun ensureServiceAlive() {
        val ctx = bootContext ?: return
        runCatching { ctx.startService(Intent(ctx, PlaybackService::class.java)) }
            .onFailure { Log.w("PlayerManager", "ensureServiceAlive startService 失败", it) }
    }

    /** 由 [PlaybackService.onCreate] 调用：注入 ExoPlayer 与应用上下文并绑定事件。 */
    fun attachPlayer(p: ExoPlayer, context: Context) {
        if (attached) return
        attached = true
        playerRef = p
        appContext = context.applicationContext

        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying.value = isPlayingNow
                // v1.4.36：播放状态翻转即持久化（commit 同步——进程被杀时
                // apply 异步写会丢，此值就是"死前状态"指纹）。仅在翻转
                // 时写盘，播放期间不重复写。
                if (isPlayingNow != Store.wasPlaying()) {
                    runCatching { Store.saveWasPlaying(isPlayingNow) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                }
                if (playbackState == Player.STATE_READY) {
                    // v1.4.30：曲目成功装载即重置失败计数与重试标记——
                    // 计数语义是"连续失败"，成功一次就断链。原实现把重置
                    // 放在 playAt 的 IDLE 分支，自动跳歌链上每次 playAt 都
                    // 清零，"3 连败停止"从未真正生效（API 故障时无限滚队列）。
                    consecutiveFailures = 0
                    // v1.4.59 r22：READY = 曲目真正能播（解码器配置成功），
                    // 此后若再报错（如播放中直链过期）允许再重试一次。
                    // 注意：READY 前的持续解码失败不会走到这里，重试标志
                    // 由 resolveAndPlay 的 lastLoadedKey 判断保留。
                    playerErrorRetryDone = false
                    // v1.5.1 r39：已播时长落盘——GD API 搜索不返回时长
                    // （interval 恒空），播放过的歌用 ExoPlayer duration
                    // 兜底：最近/收藏/歌单里 GD 源歌行尾能显示时长了。
                    // READY 时 duration 可能还是 C.TIME_UNSET（流式源
                    // 头几秒未定）——取不到就跳过，下次 READY/重播再补。
                    // 本地歌（MediaMetadataRetriever 页面已补）也统一存，
                    // SongRow 优先 interval，此表只兜空值。
                    runCatching {
                        val dur = p.duration
                        if (dur > 0) {
                            currentSong.value?.let { Store.savePlayedDuration(it, dur) }
                        }
                    }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // v1.4.58 第五轮：零轨道选中检测（设备无解码器的静默失败）。
                // 有轨道组但没有任何选中 = 渲染器全部无法支持（如 DTS），
                // ExoPlayer 不报错、静默 STATE_ENDED——置标志供 onEnded 阻断
                // 自动切歌循环并给出明确提示。
                noTrackSelected = !tracks.isEmpty() &&
                    tracks.groups.none { it.isSelected() }
            }

            override fun onPlayerError(error: PlaybackException) {
                // v1.4.36：熄屏播放中断指纹（重做）——满足任一即判定：
                // ① 错误发生时屏幕熄灭；② 错误发生时 App 在后台（MIUI 冻结
                // 进程场景：解冻时错误才触发，屏幕可能已亮但 App 仍在
                // 后台状态切换窗口）。前提：用户播放意图为真（主动播放过、
                // 未主动暂停/停止）。亮屏+前台下的网络错误是正常场景不算。
                // 标记后由 UI 回前台弹保活引导（省电策略限制只能引导用户
                // 手动设置，代码无法绕过）。
                // 不看 isPlaying 瞬时值：断流先 BUFFERING（isPlaying 已
                // false）再报错，原 v1.4.35 条件永不成立（实测未弹出根因）。
                runCatching {
                    if (userWantsPlayback) {
                        val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val screenOff = pm != null && !pm.isInteractive
                        if (screenOff || !appInForeground) {
                            if (Store.bgPlayGuideState() == 0) {
                                Store.saveBgPlayGuideState(1)
                                screenOffInterrupted = true
                            }
                        }
                    }
                }
                // v1.4.29：缓存直链兜底播放失败（直链过期且音频未全量缓存等）——
                // 清掉坏直链，重新走完整解析一次（不消耗失败计数，只重试一次）
                if (playingFromUrlCache && !urlCacheRetryDone) {
                    urlCacheRetryDone = true
                    playingFromUrlCache = false
                    urlCacheSong?.let { s -> urlCacheBr?.let { br ->
                        Store.clearUrl(s.source, s.id, br)
                    } }
                    playError.tryEmit("「${songs().getOrNull(currentIndex.value)?.displayName ?: "当前歌曲"}」直链已过期，正在重新解析")
                    playAt(currentIndex.value)
                    return
                }
                // v1.4.30：播放器级错误（解码失败/网络中断/直链过期 403）先原地
                // 重试当前歌——重新解析直链后 setMediaItem+prepare 会重置 IDLE。
                // 在线歌直链普遍几分钟过期，"播着播着断流"大多是过期而非歌坏，
                // 原地重试能无缝恢复；只有重试仍失败才跳下一首。
                if (!playerErrorRetryDone) {
                    playerErrorRetryDone = true
                    playError.tryEmit("「${songs().getOrNull(currentIndex.value)?.displayName ?: "当前歌曲"}」播放中断，正在重试")
                    playAt(currentIndex.value)
                    return
                }
                playerErrorRetryDone = false
                // 重试仍失败：按解析失败处理，自动跳下一首
                consecutiveFailures++
                if (consecutiveFailures < maxConsecutiveFailures) {
                    playError.tryEmit("「${songs().getOrNull(currentIndex.value)?.displayName ?: "当前歌曲"}」播放失败，换下一首")
                    playNext(auto = true)
                } else {
                    consecutiveFailures = 0
                    isPlaying.value = false
                    // v1.4.36：错误链最终停止 = 播放意图终止（用户点播放
                    // 重新置真）
                    userWantsPlayback = false
                    // v1.4.13 #61：停止自动跳歌后必须显式 stop 清出 IDLE 态——
                    // ExoPlayer 出错后停留在 STATE_IDLE，后续 play() 是空操作，
                    // 表现为"点播放无反应"（假死）。stop() 后再点播放会走
                    // togglePlayPause 的 mediaItemCount==0 自愈分支重新装载。
                    playError.tryEmit("连续播放失败，已停止自动切换")
                    runCatching { p.stop(); p.clearMediaItems() }
                    // v1.4.30：Media3 在 IDLE+空 timeline 时会撤掉媒体通知，
                    // 用户会误以为"App 退出了"——回调 Service 重建占位通知
                    // （暂停态样式），点播放键即走自愈路径恢复。
                    onPlaybackHalted?.invoke()
                }
            }
        })

        // 队列恢复：Service 启动即读 Store（进程冷启动或服务被杀后重启都会走到）。
        // 若用户在服务就绪前已点播放（ensureService 后立刻点），此处自动续播。
        if (pendingPlayOnAttach) {
            pendingPlayOnAttach = false
            restoreQueue()
            val idx = currentIndex.value
            if (idx >= 0) playAt(idx) else if (queue.value.isNotEmpty()) playAt(0)
        } else {
            restoreQueue()
            // v1.4.36：播放被系统强制终止指纹——服务重建时 was_playing=true
            // 即上次死前在播放且未走正常退出（stopAndExit 会 pause →
            // onIsPlayingChanged(false) → was_playing=false）。
            // 服务活着时 attachPlayer 被 attached 守卫跳过，不会重复触发；
            // 用户 HOME 后服务若存活，回前台不重走此分支。唯一触发路径：
            // 进程/服务死了又重启 = 播放确实被强制终止过。
            // （v1.4.36 首版加的 !appInForeground 条件会挡掉用户点图标
            // 冷启动的场景——onStart 先置前台再 ensureService，时序上
            // 永远 false，实测不触发，已移除。）
            if (Store.wasPlaying() && Store.bgPlayGuideState() == 0) {
                Store.saveBgPlayGuideState(1)
                screenOffInterrupted = true
            }
        }
    }

    /**
     * 由 [PlaybackService.onDestroy] 调用：重置单例状态。
     *
     * 场景：通知栏 X 退出（stopService）后进程未死，PlayerManager 仍持有
     * 已销毁的旧 player 且 attached=true；用户再启动 App 时新 Service 创建
     * 新 player，但 attachPlayer 被 attached 守卫直接跳过——新 player 永远
     * 不会播放，Media3 不产生事件，自定义媒体通知不再出现（只剩占位通知）。
     * Service 销毁时必须解绑引用，允许下次重新 attach。
     */
    fun detachPlayer() {
        pendingJob?.cancel()
        backfillJob?.cancel()
        backfillJob = null
        attached = false
        playerRef = null
        appContext = null
        isPlaying.value = false
        consecutiveFailures = 0
        playingFromUrlCache = false
        urlCacheSong = null
        urlCacheBr = null
        urlCacheRetryDone = false
        playerErrorRetryDone = false
        noTrackSelected = false
        holdAutoAdvance = false
        holdKey = null
        onPlaybackHalted = null
        // v1.4.36：服务销毁（用户退出 App）时播放意图一并终止
        userWantsPlayback = false
    }

    /** 替换整个播放队列并从指定位置开始播放。 */
    fun setQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        queue.value = songs.toList()
        playAt(startIndex.coerceIn(0, songs.size - 1))
    }

    fun playAt(index: Int) {
        val songs = queue.value
        if (index !in songs.indices) return
        // v1.4.36：用户/自动链装载播放 = 播放意图为真（自动跳歌链也延续
        // 用户最初的播放意图；错误链停止时会显式置 false）
        userWantsPlayback = true
        val p = playerRef
        if (p == null) {
            // 服务未就绪：记住意图，attachPlayer 恢复队列后自动播放；
            // v1.4.13 #61：服务被杀（进程存活）时主动重启
            pendingPlayOnAttach = true
            ensureServiceAlive()
            currentIndex.value = index
            Store.saveQueue(songs, index) // 落盘 + 备份，防进程被杀丢队列
            return
        }
        // v1.4.30：失败计数重置移到 STATE_READY（见 onPlaybackStateChanged）；
        // 此处不再清零，保证自动跳歌链上的连续失败能累计到停止阈值。
        currentIndex.value = index
        Store.saveQueue(songs, index)
        Store.addRecent(songs[index]) // 最近播放：去重头插
        resolveAndPlay(songs[index])
    }

    fun togglePlayPause() {
        val p = playerRef
        if (currentSong.value == null) {
            if (queue.value.isNotEmpty()) playAt(0)
            return
        }
        if (p == null) {
            // 服务尚未就绪（如 ROM 延迟启动）：标记待播，attachPlayer 后自动续播；
            // v1.4.13 #61：若服务已被系统杀掉（进程存活），主动重启它
            pendingPlayOnAttach = true
            ensureServiceAlive()
            return
        }
        if (p.mediaItemCount == 0) {
            // 重启后队列已恢复但播放器未装载曲目（如上次退出时未在播）：
            // 直接 play() 是空操作，必须重新解析装载当前曲目
            playAt(currentIndex.value.coerceAtLeast(0))
            return
        }
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            // v1.4.13 #61（假死修复）：播放器出错后进入 STATE_IDLE、队列自然播完
            // 停在 STATE_ENDED——这两种状态下 play() 都是静默空操作，这就是
            // "长期不播放后再点播放无反应、要杀掉 App 重启才恢复"的根因。
            // 自愈方法：重新装载当前曲目（重新解析直链 + prepare）。
            // v1.4.30：手动点播放属于用户主动行为，重置失败计数与重试标记
            // （与自动跳歌链区分开——用户手动重试永远给他机会）。
            consecutiveFailures = 0
            playerErrorRetryDone = false
            playAt(currentIndex.value.coerceAtLeast(0))
            return
        }
        if (p.isPlaying) {
            // v1.4.36：用户主动暂停 = 播放意图终止
            userWantsPlayback = false
            p.pause()
        } else {
            // v1.4.36：用户主动恢复播放 = 播放意图为真
            userWantsPlayback = true
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val p = playerRef ?: return
        p.seekTo(positionMs.coerceAtLeast(0))
    }

    /** 手动下一首（循环）。 */
    fun next() = step(1)

    /** 供错误提示读取当前曲目名（Listener 内部使用）。 */
    private fun songs(): List<Song> = queue.value

    /**
     * 手动上一首（循环）。
     * v1.4.10：去掉"播放超 3 秒先回开头"逻辑——用户期望点一下就切歌，
     * 回开头行为让"上一首"需要连点两下才生效。
     */
    fun previous() = step(-1)

    fun addToQueue(song: Song, autoplayIfIdle: Boolean = true) {
        val songs = queue.value.toMutableList()
        val wasIdle = songs.isEmpty() && currentIndex.value == -1
        songs.add(song)
        queue.value = songs
        if (autoplayIfIdle && wasIdle) {
            playAt(songs.size - 1)
        } else {
            Store.saveQueue(songs, currentIndex.value)
        }
    }

    /**
     * v1.4.58 第六轮：autoplayNext——删除的是当前播放曲目时的行为开关。
     * true（默认，播放页队列管理等场景）：自动播下一首；
     * false（删除文件场景）：什么都不做——停在顶上来的曲目上但不装载，
     * 播放器保持空载，用户点播放键走 togglePlayPause 的
     * mediaItemCount==0 自愈分支重新装载。
     */
    fun removeAt(index: Int, autoplayNext: Boolean = true) {
        val songs = queue.value.toMutableList()
        if (index !in songs.indices) return
        songs.removeAt(index)
        queue.value = songs
        val cur = currentIndex.value
        when {
            index < cur -> {
                currentIndex.value = cur - 1
                Store.saveQueue(songs, currentIndex.value)
            }

            index == cur -> {
                if (songs.isEmpty()) {
                    stopPlayback(songs)
                } else if (autoplayNext) {
                    playAt(cur.coerceIn(0, songs.size - 1))
                } else {
                    // 删除文件场景的静默移除：不自动播下一首。
                    // 删除前调用方已 stopIfPlaying 清空播放器，此处只需
                    // 校正索引并落盘（播放器空载，点播放自愈重载）
                    currentIndex.value = cur.coerceIn(0, songs.size - 1)
                    isPlaying.value = false
                    userWantsPlayback = false
                    Store.saveQueue(songs, currentIndex.value)
                }
            }

            else -> Store.saveQueue(songs, cur)
        }
    }

    /**
     * 队列内歌曲元数据变更（v1.4.0：本地歌曲重命名后调用）。
     * 仅替换队列数据与落盘；若替换的是当前播放曲目，重载播放器以引用新文件。
     */
    fun replaceSong(old: Song, new: Song) {
        val idx = queue.value.indexOfFirst { it.sameAs(old) }
        if (idx < 0) return
        val songs = queue.value.toMutableList()
        songs[idx] = new
        queue.value = songs
        Store.saveQueue(songs, currentIndex.value)
        if (idx == currentIndex.value && playerRef != null) {
            // 当前曲目文件已被重命名：重载播放器（本地优先策略会重新定位文件）
            playAt(idx)
        }
    }

    fun clearQueue() {
        stopPlayback(emptyList())
    }

    /** 停止播放但不退出 App：供"停止"通知按钮等场景使用（保留队列）。 */
    fun stop() {
        pendingJob?.cancel()
        playerRef?.pause()
        isPlaying.value = false
        // v1.4.36：用户主动停止 = 播放意图终止
        userWantsPlayback = false
    }

    /** 通知栏"停止"按钮：停止播放并退出 App。 */
    fun stopAndExit(context: Context) {
        val appCtx = context.applicationContext
        stop()
        // 队列同步落盘：apply 异步写盘在进程被杀时会丢，退出前必须 commit
        Store.saveQueueNow(queue.value, currentIndex.value)
        // 结束所有 Activity（含 MainActivity），再停掉播放服务
        appCtx.sendBroadcast(Intent(ACTION_EXIT_APP).setPackage(appCtx.packageName))
        appCtx.stopService(Intent(appCtx, PlaybackService::class.java))
    }

    fun cycleMode() {
        playMode.value = when (playMode.value) {
            PlayMode.SEQUENCE -> PlayMode.LIST_LOOP
            PlayMode.LIST_LOOP -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENCE
        }
        Store.savePlayMode(playMode.value)
    }

    private fun stopPlayback(songs: List<Song>) {
        pendingJob?.cancel()
        playerRef?.let {
            it.stop()
            it.clearMediaItems()
        }
        currentIndex.value = -1
        queue.value = songs
        isPlaying.value = false
        Store.saveQueue(songs, -1)
    }

    private fun step(delta: Int) {
        val songs = queue.value
        if (songs.isEmpty()) return
        val size = songs.size
        val i = ((currentIndex.value + delta) % size + size) % size
        playAt(i)
    }

    /** 自然播放结束或解析失败后的自动前进。 */
    private fun playNext(auto: Boolean) {
        val songs = queue.value
        if (songs.isEmpty()) return
        val nextIndex = when (playMode.value) {
            PlayMode.REPEAT_ONE -> currentIndex.value
            PlayMode.SHUFFLE -> randomIndex(songs.size, currentIndex.value)
            // 顺序播放：播完最后一首就停（手动切歌仍可绕回第一首）
            PlayMode.SEQUENCE -> {
                val n = currentIndex.value + 1
                if (n >= songs.size) {
                    if (auto) {
                        playerRef?.pause()
                        return
                    }
                    0
                } else {
                    n
                }
            }
            // 顺序循环：播完最后一首自动回到第一首（v1.4.26 修复：此前
            // 旧"顺序播放"模式在最后一首自然播完会停下，不会绕回）
            PlayMode.LIST_LOOP -> (currentIndex.value + 1) % songs.size
        }
        playAt(nextIndex)
    }

    private fun onEnded() {
        // v1.4.58 第五轮：零轨道选中（设备无解码器）的静默秒结束——
        // 不自动切歌（否则无限循环），提示一次后停在当前曲目。
        // 用户手动切歌/点播放会走 resolveAndPlay 重置标志，不受影响。
        if (noTrackSelected) {
            isPlaying.value = false
            userWantsPlayback = false
            playError.tryEmit(
                "「${songs().getOrNull(currentIndex.value)?.displayName ?: "当前歌曲"}」" +
                    "无法播放：设备不支持该音频格式的解码"
            )
            return
        }
        // v1.4.58 第十一轮：打点模式抑制自动切歌——歌曲自然播完停在
        // 当前曲目（STATE_ENDED），保住未落盘的打点数据等用户保存。
        // isPlaying 置 false 让 UI 恢复播放按钮；userWantsPlayback 置
        // false 避免熄屏中断误报（打点时用户在屏幕上操作，不会熄屏，
        // 但保持语义干净）。手动切歌/点播放不受影响（togglePlayPause
        // 的 STATE_ENDED 分支会重新装载当前曲目）。
        if (holdAutoAdvance) {
            isPlaying.value = false
            userWantsPlayback = false
            return
        }
        if (playMode.value == PlayMode.REPEAT_ONE) {
            playerRef?.seekTo(0)
            playerRef?.play()
        } else {
            playNext(auto = true)
        }
    }

    private fun resolveAndPlay(song: Song) {
        val p = playerRef ?: return
        pendingJob?.cancel()
        // v1.4.58 第十二轮：装载曲目变了（source:id 不同）即解除自动切歌
        // 抑制——holdAutoAdvance 只应作用于打点的那首歌；同曲重播（打点中
        // 点播放键重听继续打点）保留抑制。UI 在场时 LaunchedEffect(song)
        // 也会清；这里覆盖 UI 不在场的场景（后台/通知栏切歌）。
        if (holdKey != null && holdKey != "${song.source}:${song.id}") {
            holdKey = null
            holdAutoAdvance = false
        }
        // v1.4.58 第五轮：装载新曲目重置零轨道选中标志（onTracksChanged
        // 会按新曲目的实际选择结果重新赋值）
        noTrackSelected = false
        pendingJob = scope.launch {
            // v1.4.58 第六轮：id 有 local: 前缀即本地文件定位记录
            // （本地导入歌 + 在线下载歌改名后的混合记录）——
            // 聚合接口没有这个 id，在线解析注定失败且白等 20 秒，直接跳过
            val isLocalImport = song.id.startsWith("local:")
            // 本地已下载：优先播本地文件（离线可用），否则解析在线直链
            val localUri = withContext(Dispatchers.IO) {
                appContext?.let { DownloadManager.findLocalPlayableUri(it, song) }
            }
            val quality = Store.settings.value.quality
            // v1.4.28：本地导入歌（source=local）聚合接口没有这个源，
            // resolveUrl 注定失败且故障时白等 20 秒——直接跳过在线解析
            val pbKey = PlaybackCache.keyOf(song.source, song.id, quality)
            // v1.5.1 r38：**已 100% 缓存的歌优先走缓存**——先查缓存直链，
            // 命中则跳过全部在线解析（脚本/GD/换源都省掉，秒开零流量），
            // 播放页显示「缓存」标记。直链过期风险：isFullyCached 保证
            // 全区间命中，CacheDataSource 不碰上游，上游挂了也能播。
            var usedCachedUrl = false
            val cachedUrlFirst = if (localUri == null && !isLocalImport) {
                Store.cachedUrl(song.source, song.id, quality)
                    ?.takeIf { PlaybackCache.isFullyCached(pbKey) }
            } else null
            // v1.5.1 r26：自定义音源——优先模式先走自定义源（失败回落 GD API）；
            // 兜底模式 GD API 失败后再试自定义源。本地导入歌不走自定义源
            // （脚本按 songId 取各平台歌，local id 无意义）。
            // v1.5.1 r38：缓存命中时跳过（不白跑脚本）。
            val customSourceUrl = if (cachedUrlFirst == null && localUri == null && !isLocalImport &&
                CustomSourceManager.isPreferred && CustomSourceManager.sandboxReady
            ) {
                try { resolveFromCustomSource(song, quality) } catch (e: Exception) { null }
            } else null
            // v1.5.1 r28：兜底模式下 GD API 失败后由自定义源解析成功的标志
            // （用于 resolveSource 标记——区分 api/custom）
            var fallbackCustomResolved = false
            // v1.5.1 r37：自动换源结果（原源取歌失败后切其它源的同名歌）
            var toggled: com.solara.music.data.MusicSourceToggler.ToggleResult? = null
            val onlineUrl = if (cachedUrlFirst == null && localUri == null && !isLocalImport) {
                customSourceUrl ?: run {
                    try { MusicApi.resolveUrl(song, quality) } catch (e: Exception) { null }
                        ?: if (!CustomSourceManager.isPreferred) {
                            // v1.5.1 r26：兜底模式——GD API 失败后试自定义源
                            try {
                                resolveFromCustomSource(song, quality)?.also {
                                    fallbackCustomResolved = true
                                }
                            } catch (e: Exception) { null }
                        } else null
                        // v1.5.1 r37：原源全链路取歌失败——自动换源（LX music
                        // 同款）：全平台搜同名歌，逐源试取直链。QQ 源部分歌
                        // 不能播（版权/风控）、独家音源 V6 对网易歌取歌失败的
                        // 场景由此兜底——换到其它源的同名歌继续播。
                        ?: run {
                            toggled = try {
                                com.solara.music.data.MusicSourceToggler.toggle(song, quality)
                            } catch (e: Exception) { null }
                            toggled?.url
                        }
                }
            } else {
                null
            }
            // v1.4.29：在线解析成功顺手持久化直链；API 失败时兜底——音频已
            // 100% 缓存的歌用过期直链也能播（CacheDataSource 全命中不碰
            // 上游），GD API 故障（如 522 宕机）时缓存过的歌照样能放
            // v1.5.1 r37：换源成功的直链也持久化到原歌名下（key=原歌
            // source:id）——重播/断网兜底直接命中，不必每次重新换源
            val url = when {
                localUri != null -> localUri
                cachedUrlFirst != null -> cachedUrlFirst.also { usedCachedUrl = true }
                onlineUrl != null -> onlineUrl.also {
                    Store.saveUrl(song.source, song.id, quality, it)
                }
                else -> null
            }
            // v1.5.1 r37：换源成功提示（用户可感知——歌还是那首歌，
            // 但直链来自其它源）
            if (toggled != null && onlineUrl != null) {
                playError.tryEmit(
                    "「${song.displayName}」原源取歌失败，已自动切换到 ${sourceLabel(toggled!!.song.source)} 源播放"
                )
            }
            // v1.4.29：记录兜底状态供 onPlayerError 清直链重解析
            playingFromUrlCache = usedCachedUrl
            urlCacheSong = if (usedCachedUrl) song else null
            urlCacheBr = if (usedCachedUrl) quality else null
            urlCacheRetryDone = false
            // v1.5.1 r28：记录取歌链路（播放页显示标记）
            // v1.5.1 r38：缓存优先（usedCachedUrl 在 onlineUrl 之前判断）——
            // 100% 缓存的歌显示「缓存」而非「自定义源/GD API」
            resolveSource.value = when {
                localUri != null -> "local"
                usedCachedUrl -> "cache"
                onlineUrl != null -> {
                    // 优先模式：customSourceUrl 非空即自定义源；
                    // 兜底模式：GD API 成功时 onlineUrl 来自 resolveUrl，
                    // 失败才走 resolveFromCustomSource——用来源标志区分
                    if (customSourceUrl != null) "custom"
                    else if (fallbackCustomResolved) "custom"
                    // v1.5.1 r37：自动换源成功——标记实际取到直链的链路
                    else if (toggled != null) if (toggled!!.fromCustom) "custom" else "api"
                    else "api"
                }
                else -> null
            }
            if (url.isNullOrBlank()) {
                consecutiveFailures++
                // v1.4.13 #65：解析失败给用户明确提示（网络差/音源不可用）
                playError.tryEmit(
                    if (isLocalImport && localUri == null)
                        "「${song.displayName}」本地文件已丢失，请重新扫描本地歌曲"
                    else
                        "「${song.displayName}」暂时无法播放（网络差或音源解析失败）"
                )
                // 连续失败达上限：停止滚动，停在当前曲目等待用户手动操作
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    consecutiveFailures = 0
                    isPlaying.value = false
                    return@launch
                }
                playNext(auto = true)
            } else {
                consecutiveFailures = 0

                // 封面：内存缓存 → 磁盘持久化（v1.4.25）→ 在线解析（结果写盘）
                // v1.4.28：封面解析绝不阻塞播放启动——
                // ① 本地导入歌：在线 API 没有 local 源，调了注定失败（API 故障时
                //   白等 20 秒，这就是"点本地歌要等 20 秒才响"的根因）；只读
                //   matchCover 匹配成功后写盘的 URL（无网络请求），没有就 null
                // ② 在线歌：API 兜底加 3 秒超时，封面只是通知栏显示用，
                //   不值得拖住 prepare()
                // v1.5.1 r48：本地导入歌**内嵌封面优先**——通知栏封面先读
                // 文件里嵌的图（APIC/PICTURE，写临时文件传 file URI，
                // BitmapLoader 直读不走网络），没有才回落 URL 缓存
                val cacheKey = "${song.source}:${song.picId.ifBlank { song.id }}"
                val coverUrl = if (isLocalImport) {
                    val embeddedFileUri = appContext?.let {
                        runCatching {
                            com.solara.music.data.LocalCoverExtractor
                                .notificationCoverFile(it, song)
                        }.getOrNull()
                    }
                    embeddedFileUri ?: Store.localCoverUrl(song)
                } else {
                    CoverCache.get(cacheKey)
                        ?: Store.onlineCoverUrl(song)?.also { CoverCache.put(cacheKey, it) }
                        ?: withTimeoutOrNull(3000) {
                            // v1.5.1 r35：平台直连源码（kw/kg/tx/wy/mg）——GD API
                            // 不支持这些源的 pic 查询，先走自定义源脚本（与取歌同链路）
                            if (song.source == "kw" || song.source == "kg" ||
                                song.source == "tx" || song.source == "wy" || song.source == "mg"
                            ) {
                                // v1.5.1 r35：平台直连源码——先平台官方接口（lx-music
                                // musicSdk 同源），失败回落脚本
                                runCatching {
                                    com.solara.music.data.PlatformMediaApi.fetchPicUrl(song)
                                }.getOrNull()
                                    ?: runCatching {
                                        com.solara.music.customsource.CustomSourceManager.getPicUrl(song)
                                    }.getOrNull()
                            } else {
                                MusicApi.fetchPicUrl(song)
                            }
                        }?.also { fetched ->
                            CoverCache.put(cacheKey, fetched)
                            Store.saveOnlineCoverUrl(song, fetched)
                        }
                }

                val metadata = MediaMetadata.Builder()
                    .setTitle(song.name)
                    .setArtist(song.artistName)
                    .setArtworkUri(coverUrl?.let(Uri::parse))
                    .build()

                val item = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setUri(url)
                    .setMediaMetadata(metadata)
                    // v1.4.25：缓存 key 带音质（source:id:br）——重听命中磁盘
                    // 缓存秒开零流量，切音质不串缓存
                    .setCustomCacheKey(
                        PlaybackCache.keyOf(song.source, song.id, quality)
                    )
                    .build()

                p.setMediaItem(item)
                p.prepare()
                p.playWhenReady = true
                // v1.4.59 r22：重置播放器错误重试标记——只在曲目真正变化时。
                // 原实现 prepare() 后无条件清零：持续性错误（如 DTS 5.1 进
                // 声道混合处理器抛 UnhandledAudioFormatException）每次重试
                // 都先清标志 → onPlayerError 永远走"首次错误"分支 →
                // playAt → resolveAndPlay → 又清 → 无限「正在重试」循环。
                // READY 回调（onPlaybackStateChanged）的重置保留——曲目
                // 真正装载成功（能播）才允许下次错误再重试一次。
                val loadedKey = "${song.source}:${song.id}"
                if (lastLoadedKey != loadedKey) {
                    playerErrorRetryDone = false
                    lastLoadedKey = loadedKey
                }

                // v1.4.59 r19：后台补全缓存——边播边缓存只写预读窗口内的
                // 数据，听一半切走的歌缓存不完整，断网兜底（要求全量缓存）
                // 用不上。装载成功后把缺口在后台补到 100%，"听过的歌"
                // 断网也能完整重播。只对在线直链播放的歌补（本地文件/
                // 直链兜底播放不补——后者本来就是离线场景）；同一首歌
                // 同时只跑一个补全任务；补全失败静默（下次播放再试）。
                if (!usedCachedUrl && onlineUrl != null) {
                    backfillJob?.cancel()
                    val bfKey = pbKey
                    val bfUrl = onlineUrl
                    backfillJob = scope.launch(Dispatchers.IO) {
                        runCatching {
                            PlaybackCache.backfill(appContext ?: return@launch, bfKey, bfUrl)
                        }
                    }
                }
            }
        }
    }

    /**
     * v1.5.1 r26：自定义音源取歌——把 D Music 的 Song 转成 lx-music 脚本
     * 约定的 musicInfo 结构。
     * v1.5.1 r29：musicInfo.source 统一转 lx 源码（平台直连搜的歌本来就是
     * lx 源码；GD 搜的歌是 netease/tencent 等——脚本按 lx 源码分发）。
     * v1.5.1 r32：结构对齐 lx-music MusicInfo 完整契约——顶层
     * id/name/singer/source/interval + 嵌套 meta{songId,albumName,hash,
     * strMediaMid,copyrightId}。此前只传 6 个平铺字段，脚本访问
     * musicInfo.meta.songId 拿到 undefined → 拼请求/签名报错
     * （「解析失败：脚本报错」根因）。构造逻辑统一收在
     * CustomSourceManager.buildMusicInfo（与测试取歌同路径）。
     */
    private suspend fun resolveFromCustomSource(song: Song, quality: String): String? {
        // v1.5.1 r33：链路诊断日志
        android.util.Log.d("CustomSourceJS",
            "resolveFromCustomSource song=${song.name.take(30)} source=${song.source} quality=$quality")
        val url = CustomSourceManager.getMusicUrl(song.source, quality, song)
        android.util.Log.d("CustomSourceJS",
            "resolveFromCustomSource 结果: ${url?.take(100) ?: "null"}")
        return url
    }

    private fun randomIndex(size: Int, exclude: Int): Int {
        if (size <= 1) return 0
        var r = exclude
        while (r == exclude) r = (0 until size).random()
        return r
    }

    /** v1.5.1 r37：源码 → 显示名（换源提示用）。 */
    private fun sourceLabel(source: String): String = when (source) {
        "kw" -> "酷我"; "kg" -> "酷狗"; "tx" -> "QQ"; "wy" -> "网易"; "mg" -> "咪咕"
        "netease" -> "网易"; "tencent" -> "QQ"; "kuwo" -> "酷我"; "kugou" -> "酷狗"; "migu" -> "咪咕"
        else -> source
    }

    /**
     * 预载上次队列（进程冷启动时立即恢复 UI 显示）。
     *
     * 队列恢复原本只发生在 Service.onCreate → attachPlayer → restoreQueue，
     * 但服务创建存在延迟窗口（ROM 限制/启动慢时更久），期间队列 UI 为空、
     * 点歌无反应。本方法由 Application.onCreate 在 Store.init 后立即调用，
     * 让队列与 MiniPlayer 第一时间可见；服务就绪后 attachPlayer 内部的
     * restoreQueue 会用相同数据覆盖（Store 每次变更都会落盘，两者一致）。
     * 仅在内存队列为空时执行，避免覆盖已恢复/已变更的活队列。
     */
    fun restoreQueueIfEmpty() {
        if (queue.value.isNotEmpty()) return
        val (songs, index) = Store.readQueue()
        if (songs.isNotEmpty()) {
            queue.value = songs
            currentIndex.value = index.coerceIn(-1, songs.size - 1)
        }
    }

    private fun restoreQueue() {
        val (songs, index) = Store.readQueue()
        if (songs.isNotEmpty()) {
            queue.value = songs
            currentIndex.value = index.coerceIn(-1, songs.size - 1)
        }
    }
}
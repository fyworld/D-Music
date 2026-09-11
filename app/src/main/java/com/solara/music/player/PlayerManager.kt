package com.solara.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlayMode(val label: String) {
    SEQUENCE("顺序播放"),
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
    private var consecutiveFailures = 0
    private var attached = false

    /** 服务尚未就绪时用户已点播放：attachPlayer 后自动从当前曲目续播。 */
    @Volatile private var pendingPlayOnAttach = false

    /** 连续解析失败达到此次数后停止自动跳歌，避免"一直滚动曲目"。 */
    private val maxConsecutiveFailures = 3

    /**
     * 启动后台播放服务（幂等）。仅在 App 前台调用（MainActivity.onCreate），
     * 用 startService 即可：Service onCreate 会立即 startForeground 常驻通知，
     * 不触发 startForegroundService 的 5 秒约束。
     */
    fun ensureService(context: Context) {
        val appCtx = context.applicationContext
        bootContext = appCtx
        appCtx.startService(Intent(appCtx, PlaybackService::class.java))
    }

    /**
     * v1.4.13 #61：服务被系统杀掉但进程还活着时，用户从最近任务返回 App
     * 只走 onStart/onResume（不重走 onCreate → ensureService），playerRef
     * 已为 null，点播放将永远无反应。此处在点播放时尝试重启服务；
     * 重启后 attachPlayer 会消费 pendingPlayOnAttach 自动续播。
     */
    private fun ensureServiceAlive() {
        val ctx = bootContext ?: return
        runCatching { ctx.startService(Intent(ctx, PlaybackService::class.java)) }
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
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 播放器级错误（解码失败/网络中断）：按解析失败处理，自动跳下一首
                consecutiveFailures++
                if (consecutiveFailures < maxConsecutiveFailures) {
                    playError.tryEmit("「${songs().getOrNull(currentIndex.value)?.displayName ?: "当前歌曲"}」播放失败，换下一首")
                    playNext(auto = true)
                } else {
                    consecutiveFailures = 0
                    isPlaying.value = false
                    // v1.4.13 #61：停止自动跳歌后必须显式 stop 清出 IDLE 态——
                    // ExoPlayer 出错后停留在 STATE_IDLE，后续 play() 是空操作，
                    // 表现为"点播放无反应"（假死）。stop() 后再点播放会走
                    // togglePlayPause 的 mediaItemCount==0 自愈分支重新装载。
                    playError.tryEmit("连续播放失败，已停止自动切换")
                    runCatching { p.stop(); p.clearMediaItems() }
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
        attached = false
        playerRef = null
        appContext = null
        isPlaying.value = false
        consecutiveFailures = 0
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
        // v1.4.13 #61：出错后残留的 IDLE 态在这里一并清理——
        // resolveAndPlay 走 setMediaItem+prepare 会自动重置状态
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            consecutiveFailures = 0
        }
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
            consecutiveFailures = 0
            playAt(currentIndex.value.coerceAtLeast(0))
            return
        }
        if (p.isPlaying) p.pause() else p.play()
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

    fun removeAt(index: Int) {
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
                } else {
                    playAt(cur.coerceIn(0, songs.size - 1))
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
            PlayMode.SEQUENCE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENCE
        }
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
        }
        playAt(nextIndex)
    }

    private fun onEnded() {
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
        pendingJob = scope.launch {
            // 本地已下载：优先播本地文件（离线可用），否则解析在线直链
            val localUri = withContext(Dispatchers.IO) {
                appContext?.let { DownloadManager.findLocalPlayableUri(it, song) }
            }
            val url = localUri ?: run {
                val quality = Store.settings.value.quality
                try {
                    MusicApi.resolveUrl(song, quality)
                } catch (e: Exception) {
                    null
                }
            }
            if (url.isNullOrBlank()) {
                consecutiveFailures++
                // v1.4.13 #65：解析失败给用户明确提示（网络差/音源不可用）
                playError.tryEmit("「${song.displayName}」暂时无法播放（网络差或音源解析失败）")
                // 连续失败达上限：停止滚动，停在当前曲目等待用户手动操作
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    consecutiveFailures = 0
                    isPlaying.value = false
                    return@launch
                }
                playNext(auto = true)
            } else {
                consecutiveFailures = 0

                // 封面：先查内存缓存，没有就同步拉一次（系统通知需要真实 URL）
                val cacheKey = "${song.source}:${song.picId.ifBlank { song.id }}"
                val coverUrl = CoverCache.get(cacheKey) ?: MusicApi.fetchPicUrl(song).also {
                    if (it != null) CoverCache.put(cacheKey, it)
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
                    .build()

                p.setMediaItem(item)
                p.prepare()
                p.playWhenReady = true
            }
        }
    }

    private fun randomIndex(size: Int, exclude: Int): Int {
        if (size <= 1) return 0
        var r = exclude
        while (r == exclude) r = (0 until size).random()
        return r
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
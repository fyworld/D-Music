package com.solara.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.solara.music.MainActivity
import com.solara.music.R
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台播放服务：ExoPlayer + MediaSession + 自定义媒体通知。
 *
 * - onCreate 立即 startForeground：满足 startForegroundService 的 5 秒约束。
 * - 自定义 MediaNotification.Provider：**完全自定义 RemoteViews 布局（非 MediaStyle）**。
 *   原因：MIUI/HyperOS 等定制系统会用自家媒体通知模板（全幅封面+歌词）
 *   接管 MediaStyle 通知渲染，App 样式被忽略；改用普通自定义布局后
 *   所有系统显示一致（仿系统媒体面板样式：应用名+右上X / 封面+标题歌手 / 四键）。
 * - 四键：收藏 / 上一首 / 播放暂停 / 下一首；右上角 X = 退出 App。
 * - 按钮全部走自建 PendingIntent.getService（onStartCommand 拦截分发），
 *   队列由 PlayerManager 自管（player 只装单首）。
 * - 收藏按钮图标实时反映当前歌曲收藏状态：收藏变化 → setCustomLayout
 *   （extras 带自增计数确保内容变化）→ onCustomLayoutChanged → 通知重建
 *   → Provider 重新读取 Store 收藏状态。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var notificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 收藏状态刷新计数：写入 customLayout extras，确保每次内容不同以触发重建。 */
    private var favoriteRev = 0

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()

        // v1.4.25：播放缓存——在线歌曲边播边落盘，重听秒开零流量
        PlaybackCache.init(this)

        val playerBuilder = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)

        // 挂缓存：CacheDataSource 优先读本地缓存，未命中走网络边下边存。
        // 上限 0（设置关闭）时不挂，直连播放
        val pbCache = PlaybackCache.get()
        val player = if (pbCache != null) {
            @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
            val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(pbCache)
                .setUpstreamDataSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(this)
                )
                // 缓存 key 由 MediaItem.customCacheKey 提供（带音质）
                .setCacheKeyFactory { dataSpec -> dataSpec.key ?: dataSpec.uri.toString() }
            playerBuilder
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory)
                )
                .build()
        } else {
            playerBuilder.build()
        }

        // 把 player 注入给全局控制器，状态流与队列恢复都会在这里触发
        // （PlayerManager 持有原始 ExoPlayer，播放逻辑零改动）
        PlayerManager.attachPlayer(player, this)

        // v1.4.24：MediaSession 挂切歌转发包装器——蓝牙耳机/系统媒体面板
        // 的上一首/下一首命令才能到达应用层队列（详见 QueueForwardingPlayer）
        mediaSession = MediaSession.Builder(this, QueueForwardingPlayer(player))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // 允许通知栏自定义命令（供系统媒体面板/控制器调用）
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_PREVIOUS, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_TOGGLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_NEXT, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_STOP_EXIT, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_PREVIOUS -> PlayerManager.previous()
                        ACTION_TOGGLE -> PlayerManager.togglePlayPause()
                        ACTION_NEXT -> PlayerManager.next()
                        ACTION_FAVORITE -> {
                            val song = PlayerManager.currentSong.value
                            if (song != null) {
                                Store.toggleFavorite(song)
                                refreshFavorite()
                            }
                        }
                        ACTION_STOP_EXIT -> PlayerManager.stopAndExit(applicationContext)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                /**
                 * v1.4.25：媒体按键终极兜底——在 Media3 内部分发之前直接拦截
                 * 四种切歌键码，转发给应用层队列。
                 *
                 * 为什么需要：ForwardingPlayer 覆盖了标准命令路径，但部分
                 * 蓝牙栈/ROM（MIUI 等）可能走 KeyEvent 直达或 Legacy 分支，
                 * 绕过命令可用性检查。此处按键码硬拦截，无论哪条路都生效；
                 * 返回 false 的键（播放/暂停等）交回 Media3 默认处理。
                 *
                 * 注意：只处理 ACTION_DOWN 且 repeatCount=0（长按连发会
                 * 一次切多首）。
                 */
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    intent: android.content.Intent
                ): Boolean {
                    val keyEvent = intent.getParcelableExtra<android.view.KeyEvent>(
                        android.content.Intent.EXTRA_KEY_EVENT
                    ) ?: return super.onMediaButtonEvent(session, controller, intent)
                    if (keyEvent.action != android.view.KeyEvent.ACTION_DOWN ||
                        keyEvent.repeatCount > 0
                    ) {
                        // ACTION_UP / 长按连发：吞掉避免重复触发（DOWN 已处理）
                        return keyEvent.action != android.view.KeyEvent.ACTION_DOWN
                    }
                    return when (keyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                        android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            PlayerManager.next(); true
                        }
                        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                        android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            PlayerManager.previous(); true
                        }
                        else -> super.onMediaButtonEvent(session, controller, intent)
                    }
                }
            })
            .build()

        // App 内收藏状态变化时（如播放页/收藏页切换），同步刷新通知上的收藏图标
        scope.launch {
            combine(Store.favorites, PlayerManager.currentSong) { _, _ -> Unit }
                .drop(1) // 跳过初始值，避免启动时空队列触发无谓刷新
                .collect { refreshFavorite() }
        }

        // 自定义媒体通知（RemoteViews 布局），替换 Media3 默认通知
        setMediaNotificationProvider(CustomMediaNotificationProvider(this))

        // 关键：主动注册 session，建立内部 MediaController 连接。
        // 否则播放状态变化不会触发自定义 Provider（App 内直接操作 player，
        // 没有外部 controller 连接，MediaNotificationManager 收不到事件）。
        addSession(mediaSession!!)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildServiceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        // v1.4.22：冷启动异步加载当前歌曲封面，完成后刷新占位通知
        loadPlaceholderCover()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /**
     * 常驻占位通知：未播放时显示（播放后被自定义媒体通知替换，同 ID）。
     *
     * v1.4.20：队列有歌时直接用 APP 自定义媒体样式（与播放中通知完全一致），
     * 完全退出后再进入，通知栏不再先出现"系统默认样式"、点播放才变样。
     * 时序保证：onCreate 中 attachPlayer（内含队列恢复）先于本方法执行，
     * currentSong 已就绪。无歌（首次安装）时退回简单文本样式。
     * v1.4.22：封面异步加载（见 [loadPlaceholderCover]），加载完成后刷新。
     */
    private fun buildServiceNotification(cover: Bitmap? = placeholderCover): Notification {
        val current = PlayerManager.currentSong.value
        if (current != null) {
            return buildMediaStyleNotification(
                this,
                current.displayName,
                current.artistName,
                playing = false, // 占位 = 未播放，显示播放▶图标
                isFavorite = Store.isFavorite(current),
                cover = cover
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("D Music")
            .setContentText("点击打开 D Music")
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** 占位通知封面缓存（v1.4.22）：加载成功后复用，避免重复网络请求。 */
    @Volatile private var placeholderCover: Bitmap? = null

    /**
     * v1.4.22：冷启动异步加载当前歌曲封面，完成后刷新占位通知。
     * v1.4.23 修复"封面一直是默认图标"：
     * - URL.openStream() 无超时且只试一次（冷启动网络未就绪即静默失败）——
     *   改 OkHttp（10s 超时）+ 最多 3 次重试（间隔 2s，等网络就绪）
     * - 原图可能超 RemoteViews 的 Binder 事务 1MB 限制（刷新静默失败）——
     *   统一缩放到 256x256 再 setImageViewBitmap
     * - 本地歌：LocalCoverExtractor（URL 缓存/内嵌图/同名图片）
     * - 仅当 player 仍未装载曲目时刷新（Provider 接管后占位通知已无意义）
     */
    private fun loadPlaceholderCover() {
        val song = PlayerManager.currentSong.value ?: return
        scope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            if (song.source == "local") {
                bitmap = runCatching {
                    LocalCoverExtractor.getCover(this@PlaybackService, song)
                }.getOrNull()
            } else {
                val cacheKey = "${song.source}:${song.picId.ifBlank { song.id }}"
                // v1.4.25：URL 优先走磁盘持久化（Store.onlineCoverUrl）——
                // 冷启动不再依赖 API 解析；磁盘没有才调 API 并写盘
                val cachedUrl = Store.onlineCoverUrl(song)
                if (cachedUrl != null) {
                    com.solara.music.ui.components.CoverCache.put(cacheKey, cachedUrl)
                    bitmap = runCatching { downloadBitmap(cachedUrl) }.getOrNull()
                }
                // 最多 3 次尝试（间隔 2s）：冷启动时网络可能尚未就绪
                if (bitmap == null) {
                    repeat(3) { attempt ->
                        bitmap = runCatching {
                            val url = com.solara.music.ui.components.CoverCache.get(cacheKey)
                                ?: com.solara.music.data.MusicApi.fetchPicUrl(song)?.also {
                                    com.solara.music.ui.components.CoverCache.put(cacheKey, it)
                                    Store.saveOnlineCoverUrl(song, it)
                                }
                            url?.let { u -> downloadBitmap(u) }
                        }.getOrNull()
                        if (bitmap != null) return@repeat
                        if (attempt < 2) kotlinx.coroutines.delay(2000)
                    }
                }
            }
            val scaled = bitmap?.let { scaleForNotification(it) }
            if (scaled != null) {
                placeholderCover = scaled
                // v1.4.23 崩溃修复：ExoPlayer 只能主线程访问——IO 线程读
                // mediaSession.player.mediaItemCount 会抛 IllegalStateException
                // 闪退。封面在 IO 线程下载完成后切回主线程再检查与刷新通知。
                withContext(Dispatchers.Main) {
                    // player 仍空载（占位期间）才刷新；已装载则 Provider 接管，无需处理
                    if ((mediaSession?.player?.mediaItemCount ?: 0) == 0) {
                        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(scaled))
                    }
                }
            }
        }
    }

    /** OkHttp 下载封面位图（10s 超时，替代无超时的 URL.openStream）。 */
    private fun downloadBitmap(url: String): Bitmap? {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /**
     * 封面缩放到 256x256 内（v1.4.23）：RemoteViews 的 setImageViewBitmap 走
     * Binder 事务（异步池上限约 1MB），大位图会超限导致通知刷新静默失败。
     */
    private fun scaleForNotification(src: Bitmap): Bitmap {
        val maxSide = 256
        if (src.width <= maxSide && src.height <= maxSide) return src
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        return runCatching {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        }.getOrDefault(src)
    }

    /**
     * v1.4.20：刷新通知收藏图标——统一入口。
     * 播放中（player 已装载）走 setCustomLayout 触发 Media3 重建；
     * 占位通知期间（player 空载但队列有歌）直接重建占位通知。
     */
    private fun refreshFavorite() {
        refreshFavoriteOnNotification()
        if ((mediaSession?.player?.mediaItemCount ?: 0) == 0 &&
            PlayerManager.currentSong.value != null
        ) {
            notificationManager.notify(NOTIFICATION_ID, buildServiceNotification())
        }
    }

    /**
     * 通过变更 customLayout 触发 MediaNotificationManager 重建通知，
     * 使通知上的收藏图标与 [Store] 最新收藏状态一致。
     *
     * 仅在 player 已装载曲目（媒体通知真实可见）时执行：空队列时调用
     * setCustomLayout 会走 Media3 的 stopForeground 分支，剥离占位前台通知。
     */
    private fun refreshFavoriteOnNotification() {
        val session = mediaSession ?: return
        if (session.player.mediaItemCount == 0) return
        val song = PlayerManager.currentSong.value ?: return
        val favorite = Store.isFavorite(song)
        favoriteRev++
        session.setCustomLayout(
            ImmutableList.of(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
                    .setIconResId(
                        if (favorite) R.drawable.ic_stat_heart_filled else R.drawable.ic_stat_heart
                    )
                    .setDisplayName(if (favorite) "取消收藏" else "收藏")
                    .setEnabled(true)
                    .setExtras(Bundle().apply { putInt("rev", favoriteRev) })
                    .build()
            )
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 拦截通知按钮（自建 PendingIntent）发出的命令并分发。
     * 其余 intent（Media3 控制器命令）交给父类处理。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_EXIT -> {
                PlayerManager.stopAndExit(applicationContext)
                return START_NOT_STICKY
            }
            ACTION_PREVIOUS -> PlayerManager.previous()
            ACTION_TOGGLE -> PlayerManager.togglePlayPause()
            ACTION_NEXT -> PlayerManager.next()
            ACTION_FAVORITE -> {
                val song = PlayerManager.currentSong.value
                if (song != null) {
                    Store.toggleFavorite(song)
                    refreshFavorite()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        scope.cancel()
        // 队列同步落盘：进程可能随服务销毁被杀，apply 异步写盘会丢
        runCatching {
            Store.saveQueueNow(PlayerManager.queue.value, PlayerManager.currentIndex.value)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        mediaSession?.release()
        mediaSession = null
        // 重置 PlayerManager 单例状态：X 退出后进程未死时，允许新 Service
        // 重新 attach 新 player（否则媒体通知不再出现，只剩占位通知）
        PlayerManager.detachPlayer()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "d_music_playback"
        /** 与自定义 Provider 使用的通知 ID 一致。 */
        const val NOTIFICATION_ID = 100
        const val ACTION_PREVIOUS = "com.solara.music.action.PREVIOUS"
        const val ACTION_TOGGLE = "com.solara.music.action.TOGGLE"
        const val ACTION_NEXT = "com.solara.music.action.NEXT"
        const val ACTION_FAVORITE = "com.solara.music.action.FAVORITE"
        const val ACTION_STOP_EXIT = "com.solara.music.action.STOP_EXIT"
    }
}

/**
 * v1.4.24/25：切歌命令转发包装器（Media3 官方推荐做法）。
 *
 * 背景：本 App 队列由 PlayerManager 应用层管理，ExoPlayer 时间线只装当前
 * 一首（setMediaItem 单首加载）→ hasNextMediaItem() 恒为 false → Media3
 * 判定 SEEK_TO_NEXT/SEEK_TO_PREVIOUS 命令不可用 → 蓝牙耳机（AVRCP）和
 * 系统媒体面板的切歌键被系统静默丢弃。通知栏按钮走自建 PendingIntent
 * 不受影响，蓝牙却完全无法切歌——这就是"蓝牙上一首/下一首无法控制"的根因。
 *
 * 方案：包装 player 传给 MediaSession——
 * 1. isCommandAvailable + getAvailableCommands：队列非空时对外宣称切歌
 *    命令可用，系统才会下发（部分蓝牙栈/系统面板按命令集合判断）；
 * 2. seekToNext/seekToPrevious/seekToNextMediaItem/seekToPreviousMediaItem：
 *    转发给 PlayerManager.next()/previous()（应用层队列含循环/随机/单曲
 *    循环逻辑），不再透传给内层 player；
 * 3. v1.4.25：seekForward/seekBack 也转发切歌——部分蓝牙耳机"下一首"
 *    发的是 AVRCP FORWARD，Android 映射为 KEYCODE_MEDIA_FAST_FORWARD
 *    （87=NEXT 的兄弟键 90/89），走 seekForward 命令；ExoPlayer 默认
 *    seekForward 是无操作（无 seekBack/ForwardIncrement 配置），表现为
 *    "上一首好了、下一首没反应"的不对称症状；
 * 4. 其余命令全部透传，播放/暂停/进度条等行为不变。
 *
 * 注意：PlayerManager 持有的仍是原始 ExoPlayer（attachPlayer 在包装前
 * 注入），播放逻辑零改动；本包装器只影响"系统侧看到的 player"。
 */
private class QueueForwardingPlayer(player: Player) : ForwardingPlayer(player) {

    /** 应用层队列非空即允许切歌（含循环模式，永远有上/下一首）。 */
    private fun queueReady(): Boolean = PlayerManager.queue.value.isNotEmpty()

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_FORWARD, COMMAND_SEEK_BACK -> queueReady()
            else -> super.isCommandAvailable(command)
        }
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return if (!queueReady()) base else base.buildUpon()
            .add(COMMAND_SEEK_TO_NEXT)
            .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(COMMAND_SEEK_FORWARD)
            .add(COMMAND_SEEK_BACK)
            .build()
    }

    override fun seekToNext() {
        if (queueReady()) PlayerManager.next() else super.seekToNext()
    }

    override fun seekToPrevious() {
        if (queueReady()) PlayerManager.previous() else super.seekToPrevious()
    }

    override fun seekToNextMediaItem() {
        if (queueReady()) PlayerManager.next() else super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        if (queueReady()) PlayerManager.previous() else super.seekToPreviousMediaItem()
    }

    override fun seekForward() {
        if (queueReady()) PlayerManager.next() else super.seekForward()
    }

    override fun seekBack() {
        if (queueReady()) PlayerManager.previous() else super.seekBack()
    }
}

/**
 * 自定义媒体通知 Provider：完全自定义 RemoteViews 布局（非 MediaStyle）。
 *
 * 为什么不用 MediaStyle：MIUI/HyperOS 等定制系统会用自己的媒体通知模板
 * （全幅封面+歌词）接管 MediaStyle 通知的渲染，App 设置的样式被忽略。
 * 改用普通自定义布局后，所有系统显示一致（仿系统媒体面板样式）。
 *
 * - 展开态 notification_media.xml：顶部行（应用名+右上X=退出）+
 *   封面 + 标题/歌手 + 四键（收藏/上一首/播放暂停/下一首）。
 * - 收起态 notification_media_compact.xml：封面 + 标题/歌手 + 三键。
 * - 按钮全部走自建 PendingIntent.getService（onStartCommand 拦截分发），
 *   队列由 PlayerManager 自管（player 单 MediaItem）。
 * - 收藏图标实时反映当前歌曲收藏状态（点击后 Service 触发通知重建刷新）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CustomMediaNotificationProvider(private val context: Context) :
    MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val player = mediaSession.player
        val playing = player.isPlaying
        val metadata = player.mediaMetadata
        val title = metadata.title ?: "D Music"
        val artist = metadata.artist ?: ""

        val song = PlayerManager.currentSong.value
        val isFavorite = song != null && Store.isFavorite(song)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun servicePendingIntent(action: String, requestCode: Int) =
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val primaryColor = context.getColor(R.color.notif_text_primary)
        val favoriteColor = 0xFFE53935.toInt() // 红色实心♥（收藏高亮）

        // ---- 展开态布局 ----
        val expanded = RemoteViews(context.packageName, R.layout.notification_media).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_artist, artist)
            setOnClickPendingIntent(R.id.notif_close, servicePendingIntent(PlaybackService.ACTION_STOP_EXIT, 1))
            setOnClickPendingIntent(R.id.notif_btn_favorite, servicePendingIntent(PlaybackService.ACTION_FAVORITE, 2))
            setOnClickPendingIntent(R.id.notif_btn_prev, servicePendingIntent(PlaybackService.ACTION_PREVIOUS, 3))
            setOnClickPendingIntent(R.id.notif_btn_play_pause, servicePendingIntent(PlaybackService.ACTION_TOGGLE, 4))
            setOnClickPendingIntent(R.id.notif_btn_next, servicePendingIntent(PlaybackService.ACTION_NEXT, 5))

            setImageViewResource(
                R.id.notif_btn_favorite,
                if (isFavorite) R.drawable.ic_stat_heart_filled else R.drawable.ic_stat_heart
            )
            // 收藏键高亮薄荷绿，未收藏用主文字色
            setInt(
                R.id.notif_btn_favorite, "setColorFilter",
                if (isFavorite) favoriteColor else primaryColor
            )
            setImageViewResource(
                R.id.notif_btn_play_pause,
                if (playing) R.drawable.ic_stat_pause else R.drawable.ic_stat_play
            )
            setImageViewResource(R.id.notif_cover, R.drawable.notif_cover_placeholder)
        }

        // ---- 收起态布局 ----
        val compact = RemoteViews(context.packageName, R.layout.notification_media_compact).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_artist, artist)
            // v1.4.1：收起态（锁屏）也带收藏键，四键与展开态一致
            setOnClickPendingIntent(R.id.notif_btn_favorite, servicePendingIntent(PlaybackService.ACTION_FAVORITE, 2))
            setOnClickPendingIntent(R.id.notif_btn_prev, servicePendingIntent(PlaybackService.ACTION_PREVIOUS, 3))
            setOnClickPendingIntent(R.id.notif_btn_play_pause, servicePendingIntent(PlaybackService.ACTION_TOGGLE, 4))
            setOnClickPendingIntent(R.id.notif_btn_next, servicePendingIntent(PlaybackService.ACTION_NEXT, 5))
            setImageViewResource(
                R.id.notif_btn_favorite,
                if (isFavorite) R.drawable.ic_stat_heart_filled else R.drawable.ic_stat_heart
            )
            setInt(
                R.id.notif_btn_favorite, "setColorFilter",
                if (isFavorite) favoriteColor else primaryColor
            )
            setImageViewResource(
                R.id.notif_btn_play_pause,
                if (playing) R.drawable.ic_stat_pause else R.drawable.ic_stat_play
            )
            setImageViewResource(R.id.notif_cover, R.drawable.notif_cover_placeholder)
        }

        val builder = NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setSmallIcon(R.drawable.ic_stat_music)
            // 可滑掉 + 自绘右上角 X；点 X / 滑掉 → 退出 App
            .setOngoing(false)
            .setDeleteIntent(servicePendingIntent(PlaybackService.ACTION_STOP_EXIT, 1))
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)

        // 封面：通过 session 的 BitmapLoader 异步加载，完成后回调刷新通知
        val bitmapLoader: BitmapLoader = mediaSession.bitmapLoader
        val bitmapFuture: ListenableFuture<Bitmap>? =
            bitmapLoader.loadBitmapFromMetadata(metadata)
        if (bitmapFuture != null) {
            try {
                if (bitmapFuture.isDone) {
                    Futures.getDone(bitmapFuture)?.let {
                        expanded.setImageViewBitmap(R.id.notif_cover, it)
                        compact.setImageViewBitmap(R.id.notif_cover, it)
                    }
                } else {
                    Futures.addCallback(
                        bitmapFuture,
                        object : com.google.common.util.concurrent.FutureCallback<Bitmap> {
                            override fun onSuccess(result: Bitmap?) {
                                result?.let {
                                    expanded.setImageViewBitmap(R.id.notif_cover, it)
                                    compact.setImageViewBitmap(R.id.notif_cover, it)
                                    onNotificationChangedCallback.onNotificationChanged(
                                        MediaNotification(PlaybackService.NOTIFICATION_ID, builder.build())
                                    )
                                }
                            }

                            override fun onFailure(t: Throwable) {
                                // 封面加载失败不处理，保持占位封面
                            }
                        },
                        Runnable::run
                    )
                }
            } catch (e: Exception) {
                // 忽略封面加载异常
            }
        }

        return MediaNotification(PlaybackService.NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(
        mediaSession: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        // 必须返回 false：false = "Provider 未处理，转发给 MediaSession.Callback.onCustomCommand"。
        // 返回 true 表示 Provider 已自行处理，命令会被吞掉、按钮点击无效
        // （MediaNotificationManager.onCustomAction: if (!handleCustomCommand) forward）。
        // 实际处理逻辑全部在 PlaybackService 的 onCustomCommand 回调里。
        return false
    }
}

/**
 * v1.4.20：构建 APP 自定义媒体样式通知（占位通知复用）。
 * 与 [CustomMediaNotificationProvider] 的布局完全一致：展开态四键 + 收起态四键，
 * 按钮全部走自建 PendingIntent.getService（onStartCommand 拦截分发）。
 * v1.4.22：cover 参数——占位场景异步加载真实封面后刷新传入；null 用占位图。
 */
internal fun buildMediaStyleNotification(
    context: Context,
    title: String,
    artist: String,
    playing: Boolean,
    isFavorite: Boolean,
    cover: Bitmap? = null
): Notification {
    fun servicePendingIntent(action: String, requestCode: Int) =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val primaryColor = context.getColor(R.color.notif_text_primary)
    val favoriteColor = 0xFFE53935.toInt()

    fun RemoteViews.applyMediaLayout() {
        setTextViewText(R.id.notif_title, title)
        setTextViewText(R.id.notif_artist, artist)
        setOnClickPendingIntent(R.id.notif_close, servicePendingIntent(PlaybackService.ACTION_STOP_EXIT, 1))
        setOnClickPendingIntent(R.id.notif_btn_favorite, servicePendingIntent(PlaybackService.ACTION_FAVORITE, 2))
        setOnClickPendingIntent(R.id.notif_btn_prev, servicePendingIntent(PlaybackService.ACTION_PREVIOUS, 3))
        setOnClickPendingIntent(R.id.notif_btn_play_pause, servicePendingIntent(PlaybackService.ACTION_TOGGLE, 4))
        setOnClickPendingIntent(R.id.notif_btn_next, servicePendingIntent(PlaybackService.ACTION_NEXT, 5))
        setImageViewResource(
            R.id.notif_btn_favorite,
            if (isFavorite) R.drawable.ic_stat_heart_filled else R.drawable.ic_stat_heart
        )
        setInt(
            R.id.notif_btn_favorite, "setColorFilter",
            if (isFavorite) favoriteColor else primaryColor
        )
        setImageViewResource(
            R.id.notif_btn_play_pause,
            if (playing) R.drawable.ic_stat_pause else R.drawable.ic_stat_play
        )
        if (cover != null) setImageViewBitmap(R.id.notif_cover, cover)
        else setImageViewResource(R.id.notif_cover, R.drawable.notif_cover_placeholder)
    }

    val expanded = RemoteViews(context.packageName, R.layout.notification_media).apply { applyMediaLayout() }
    val compact = RemoteViews(context.packageName, R.layout.notification_media_compact).apply { applyMediaLayout() }

    return NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(artist)
        .setContentIntent(contentIntent)
        .setSmallIcon(R.drawable.ic_stat_music)
        .setOngoing(false)
        .setDeleteIntent(servicePendingIntent(PlaybackService.ACTION_STOP_EXIT, 1))
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setCustomContentView(compact)
        .setCustomBigContentView(expanded)
        .build()
}

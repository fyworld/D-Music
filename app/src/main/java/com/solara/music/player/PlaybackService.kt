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
import com.solara.music.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 把 player 注入给全局控制器，状态流与队列恢复都会在这里触发
        PlayerManager.attachPlayer(player, this)

        mediaSession = MediaSession.Builder(this, player)
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
     */
    private fun buildServiceNotification(): Notification {
        val current = PlayerManager.currentSong.value
        if (current != null) {
            return buildMediaStyleNotification(
                this,
                current.displayName,
                current.artistName,
                playing = false, // 占位 = 未播放，显示播放▶图标
                isFavorite = Store.isFavorite(current)
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
 * 占位场景 player 空载、无封面元数据，用占位封面图。
 */
internal fun buildMediaStyleNotification(
    context: Context,
    title: String,
    artist: String,
    playing: Boolean,
    isFavorite: Boolean
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
        setImageViewResource(R.id.notif_cover, R.drawable.notif_cover_placeholder)
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

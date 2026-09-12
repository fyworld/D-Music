package com.solara.music

import android.app.Application
import com.solara.music.data.Store
import com.solara.music.player.PlaybackCache
import com.solara.music.player.PlayerManager

class SolaraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 仅初始化数据层；播放服务由 MainActivity 启动，避免应用未打开就常驻后台
        Store.init(this)
        // v1.4.25：播放缓存初始化（设置读取上限；Service 创建时也会兜底调用）
        PlaybackCache.init(this)
        // 立即恢复上次队列：不等 Service（其创建有延迟窗口），让队列 UI 与
        // MiniPlayer 第一时间可见；服务就绪后 attachPlayer 会做同样恢复
        PlayerManager.restoreQueueIfEmpty()
        // v1.4.26：恢复上次使用的播放模式
        PlayerManager.restorePlayMode()
    }
}

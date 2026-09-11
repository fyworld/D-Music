package com.solara.music

import android.app.Application
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager

class SolaraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 仅初始化数据层；播放服务由 MainActivity 启动，避免应用未打开就常驻后台
        Store.init(this)
        // 立即恢复上次队列：不等 Service（其创建有延迟窗口），让队列 UI 与
        // MiniPlayer 第一时间可见；服务就绪后 attachPlayer 会做同样恢复
        PlayerManager.restoreQueueIfEmpty()
    }
}

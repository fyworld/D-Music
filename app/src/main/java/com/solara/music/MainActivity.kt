package com.solara.music

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.solara.music.data.Store
import com.solara.music.data.ThemeMode
import com.solara.music.player.ACTION_EXIT_APP
import com.solara.music.player.PlayerManager
import com.solara.music.ui.SolaraApp
import com.solara.music.ui.theme.SolaraTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 即使拒绝也不影响播放，只是系统媒体通知可能被系统隐藏 */ }

    /** 音频读取权限：用于扫描本地歌曲（卸载重装后 MediaStore owner 关系已断）。 */
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 权限刚授予：重试卸载重装恢复（Store.init 时可能因无权限而落空）
            if (Store.retryRestoreAfterPermission()) {
                PlayerManager.restoreQueueIfEmpty()
            }
        }
    }

    /** 通知栏"停止"按钮：结束所有 Activity，退出 App。 */
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_EXIT_APP) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureNotificationPermission()
        ensureAudioReadPermission()

        // 启动后台播放服务（Service 在 onCreate 中创建 ExoPlayer 并注入 PlayerManager）
        PlayerManager.ensureService(applicationContext)

        // 通知栏"停止退出"广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                exitReceiver,
                IntentFilter(ACTION_EXIT_APP),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(exitReceiver, IntentFilter(ACTION_EXIT_APP))
        }

        setContent {
            val settings by Store.settings.collectAsState()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SolaraTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                accentColor = settings.accentColor
            ) {
                SolaraApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // v1.4.13 #61：从后台切回时确保播放服务存活——服务可能已被系统
        // 回收（进程仍在），此时 playerRef 为 null，点播放会无反应。
        // startService 幂等：服务活着时无副作用。
        PlayerManager.ensureService(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(exitReceiver) }
    }

    /** Android 13+ 必须运行时申请通知权限，否则后台播放的系统媒体通知不会显示。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * 读取音频权限：扫描本地歌曲/查询 MediaStore 必需（Android 10+）。
     * App 自己下载的文件本无需权限，但卸载重装后 owner 关系断裂，
     * 再查公共媒体库就需要读取权限了。
     */
    private fun ensureAudioReadPermission() {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                Manifest.permission.READ_EXTERNAL_STORAGE
            else -> return // Android 9-：app 专属目录无需权限
        }
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            audioPermissionLauncher.launch(permission)
        }
    }
}

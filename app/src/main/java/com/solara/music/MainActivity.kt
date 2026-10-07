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

    /**
     * v1.5.1 r70q：权限请求串行化。
     *
     * 此前 onCreate 里通知权限与音频/存储权限背靠背 launch——Android 权限
     * 弹窗一次只显示一个，Activity Result API 连续 launch 两个请求时第二个
     * 会被系统权限队列静默丢弃（不弹窗、不回调）。表现为 Android 10+ 全新
     * 安装只弹「是否允许发送通知」，媒体读取权限从未申请 → 卸载重装恢复
     * 落空（无权限时 MediaStore 查询只能看到自己贡献的条目 = 空）。
     *
     * 修复：通知权限回调（无论允许/拒绝）后再发起音频/存储权限请求，
     * 保证每个弹窗独立显示、回调必然到达。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 即使拒绝也不影响播放，只是系统媒体通知可能被系统隐藏。
        // 回调到达后串行发起下一个权限请求（r70q，见类头注释）。
        ensureAudioReadPermission()
    }

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

    /**
     * v1.5.1 r70n：Android 9- 存储权限（legacy 备份读写公共 Downloads 必需）。
     * 此前 Android 9 分支直接 return 不申请任何权限，而 READ/WRITE_EXTERNAL_STORAGE
     * 是 dangerous 权限必须运行时申请——导致备份写不出去、恢复也读不到，
     * 「卸载重装恢复」在 Android 9 上双向静默失败。READ 与 WRITE 同组
     * （STORAGE），一并申请，授予即全组生效。
     */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
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

        // v1.5.1 r70q：权限请求串行化——单一入口。Android 13+ 且通知权限
        // 未授予时先弹通知权限，音频/存储权限在其回调后链式发起（背靠背
        // launch 第二个请求会被系统权限队列吞掉，见 launcher 注释）；
        // 其余情况（10-12 无通知权限一说 / 13+ 已授予）直接发起音频权限。
        ensureStartupPermissions()

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
                accentColor = settings.accentColor,
                uiScaleLevel = settings.uiScaleLevel
            ) {
                SolaraApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // v1.4.36：App 前台标志（熄屏中断检测的补充指纹——App 仅此一个
        // Activity，其生命周期即 App 前后台）
        PlayerManager.appInForeground = true
        // v1.4.13 #61：从后台切回时确保播放服务存活——服务可能已被系统
        // 回收（进程仍在），此时 playerRef 为 null，点播放会无反应。
        // startService 幂等：服务活着时无副作用。
        PlayerManager.ensureService(applicationContext)
        // v1.4.21：回前台节流补查更新（30 分钟内不重复）——进程被播放服务
        // 保活时，界面不会重新组合，启动时的静默检查不会再触发。
        com.solara.music.data.UpdateManager.checkIfStale()
    }

    override fun onStop() {
        super.onStop()
        // v1.4.36：App 退到后台（含熄屏）——错误链若在此期间触发即为
        // 后台播放中断指纹
        PlayerManager.appInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(exitReceiver) }
    }

    /**
     * v1.5.1 r70q：启动权限串行入口。
     *
     * Android 13+ 且通知权限未授予：先弹通知权限，回调（无论允许/拒绝）
     * 里链式发起音频权限请求。
     * 其余情况：直接发起音频/存储权限请求。
     * 目的：保证任意时刻只有一个权限请求在途，每个弹窗独立显示、
     * 回调必然到达（背靠背 launch 会丢请求，见 launcher 注释）。
     */
    private fun ensureStartupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // 先弹通知权限；音频权限在回调后链式发起
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        ensureAudioReadPermission()
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
            else -> {
                // v1.5.1 r70n：Android 9- 申请存储组权限（legacy 备份读写
                // 公共 Downloads 必需）。此前直接 return 不申请，备份/恢复
                // 双向静默失败。READ 与 WRITE 同属 STORAGE 组，授予即全组生效。
                val readGranted = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
                val writeGranted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
                if (!readGranted || !writeGranted) {
                    storagePermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
                return
            }
        }
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            audioPermissionLauncher.launch(permission)
        }
    }
}

package com.solara.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.solara.music.data.UpdateManager
import java.io.File

/**
 * 更新安装落地页（v1.4.27）：
 * 「新版本下载完成」通知的点击目标——透明 Activity，只负责拉起系统安装器。
 *
 * 为什么需要它：Android 10+ 禁止后台 App 直接弹出界面，下载完成后无法
 * 自动弹安装器；通知的 PendingIntent 点击不受此限制，是唯一合规通道。
 * 用独立 Activity 而非 MainActivity：
 * - 不打断 MainActivity 的界面状态恢复（tab/播放页等）
 * - 主题透明，拉起安装器后立即 finish，无感
 *
 * Android 8+ 未获「安装未知应用」授权时引导去系统设置，返回后自动重试安装。
 */
class InstallApkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val apk = apkPath?.let { File(it) }
        if (apk == null || !apk.exists()) {
            // APK 被清理（卸载/取消）——直接撤通知回 App
            UpdateManager.cancelUpdateNotification()
            finish()
            return
        }
        val ok = UpdateManager.installApk(this, apk)
        if (!ok) {
            // 未获「安装未知应用」授权 → 引导去设置；用户返回后 onResume 重试
            UpdateManager.installPermissionSettingsIntent(this)?.let {
                runCatching { startActivity(it) }
            }
        } else {
            UpdateManager.cancelUpdateNotification()   // 安装器已拉起，撤掉通知
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置授权页返回：重试拉起安装器
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: return
        val apk = File(apkPath)
        if (apk.exists() && UpdateManager.installApk(this, apk)) {
            UpdateManager.cancelUpdateNotification()
            finish()
        }
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
    }
}

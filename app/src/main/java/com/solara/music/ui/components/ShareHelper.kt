package com.solara.music.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.solara.music.data.DownloadManager
import com.solara.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.4.59：播放页「分享」。
 *
 * r17 策略（用户重定）：分享歌曲必须有本地文件——
 * - 有本地文件（已下载/本地导入）：直接分享音频文件本体
 *   - Android 10+：优先 MediaStore content URI（queryLocalUris 命中）——
 *     系统媒体库 URI 自带临时读权限，直接 FLAG_GRANT_READ_URI_PERMISSION
 *   - 文件路径 URI（file:// 或本地导入直查命中）：经 FileProvider 转
 *     content URI 分享（root-path 覆盖公共存储任意位置）
 * - 无本地文件（在线未下载）：调用方弹「分享需要下载」确认框——
 *   确认跳到下载流程（品质选择下载），下载完成后用户手动再点分享；
 *   取消即退出（不再分享"歌名 - 歌手" 文字——文字模式已按用户要求移除）
 *
 * v1.4.59 r15：返回键回不到 D Music 修复——旧实现用 applicationContext
 * 启动分享面板（非 Activity context 强制 FLAG_ACTIVITY_NEW_TASK，微信
 * 成独立任务，返回键回不去）。现优先取 Compose LocalContext 链上的
 * Activity 启动（同任务栈，返回键自然回 App）；无 Activity 才退回
 * application + NEW_TASK。
 */
object ShareHelper {

    /** 从 context 链上找 Activity（Compose LocalContext 通常是 Activity）。 */
    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * 分享歌曲文件本体（IO 查询在 Dispatchers.IO，回主线程发系统分享面板）。
     * 返回 false = 没有本地文件（调用方走下载确认流程）。
     */
    suspend fun shareFile(context: Context, song: Song): Boolean {
        val ctx = context.applicationContext
        val fileShare = withContext(Dispatchers.IO) {
            resolveShareUri(ctx, song)
        } ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            // 文件模式：音频 MIME（微信/QQ 识别为音乐文件可转发）
            type = guessMime(fileShare)
            putExtra(Intent.EXTRA_STREAM, fileShare)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // r15：Activity context 同任务栈启动——分享完返回键直接回 D Music
        val activity = activityOf(context)
        if (activity != null) {
            val chooser = Intent.createChooser(intent, "分享歌曲文件")
            runCatching { activity.startActivity(chooser) }
                .onFailure {
                    Toast.makeText(ctx, "分享失败：没有可用的分享目标", Toast.LENGTH_SHORT).show()
                }
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = Intent.createChooser(intent, "分享歌曲文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(chooser) }
                .onFailure {
                    Toast.makeText(ctx, "分享失败：没有可用的分享目标", Toast.LENGTH_SHORT).show()
                }
        }
        return true
    }

    /**
     * 解析可分享的 content URI：
     * 1. Android 10+：MediaStore 命中（queryLocalUris——含 D_Music 下载
     *    与本地导入的媒体库收录文件）
     * 2. findLocalPlayableUri 返回 file:// 路径（本地导入直查/Android 9-）：
     *    转 FileProvider content URI
     * 都没有返回 null。
     */
    private fun resolveShareUri(context: Context, song: Song): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DownloadManager.queryLocalUris(context, song).firstOrNull()?.let { return it }
        }
        val playable = DownloadManager.findLocalPlayableUri(context, song) ?: return null
        val uri = runCatching { Uri.parse(playable) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "content" -> uri
            "file" -> {
                val f = File(uri.path ?: return null)
                if (f.exists() && f.canRead()) toProviderUri(context, f) else null
            }
            else -> null
        }
    }

    /**
     * file:// → FileProvider content URI。
     * file_paths.xml 的 root-path 覆盖外部存储根（公共 Music/D_Music、
     * 任意浏览目录的本地导入文件都能映射）。
     */
    private fun toProviderUri(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )

    /** 按扩展名猜 MIME（默认 audio/mpeg）。 */
    private fun guessMime(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase() ?: return "audio/mpeg"
        return when {
            name.endsWith(".flac") -> "audio/flac"
            name.endsWith(".dts") -> "audio/vnd.dts"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".ogg") -> "audio/ogg"
            name.endsWith(".m4a") || name.endsWith(".aac") -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }
}

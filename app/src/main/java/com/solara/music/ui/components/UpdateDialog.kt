package com.solara.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.solara.music.BuildConfig
import com.solara.music.data.UpdateManager

/**
 * v1.4.19：版本更新弹窗（共享组件——启动提示与关于页入口共用）。
 * - 更新内容（GitHub Release body 去 markdown 展示）+ 下载进度 + 安装
 * - 下载到应用外部私有目录（无需存储权限），完成后拉起系统安装器
 * - Android 8+ 首次安装需「安装未知应用」授权，未授权时引导去设置
 */
@Composable
fun UpdateDialog(
    info: UpdateManager.UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val dlState by UpdateManager.downloadState.collectAsState()

    AlertDialog(
        onDismissRequest = { if (dlState !is UpdateManager.DownloadState.Progress) onDismiss() },
        title = { Text("发现新版本") },
        text = {
            Column {
                Text(
                    text = "v${BuildConfig.VERSION_NAME} → v${info.versionName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                // 更新说明（去 markdown 标记，最多 12 行防超长）
                val notes = remember(info.notes) { plainNotes(info.notes) }
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 12
                )
                // 下载进度
                when (val s = dlState) {
                    is UpdateManager.DownloadState.Progress -> {
                        Spacer(Modifier.height(12.dp))
                        if (s.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "正在下载 ${(s.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "正在下载…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is UpdateManager.DownloadState.Done -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "下载完成，点击「安装」开始更新",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is UpdateManager.DownloadState.Failed -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "下载失败：${s.reason}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (dlState) {
                is UpdateManager.DownloadState.Progress -> {
                    // 下载中：确认键变「后台下载」（不中断，关弹窗）
                    TextButton(onClick = onDismiss) { Text("后台下载") }
                }
                is UpdateManager.DownloadState.Done -> {
                    TextButton(onClick = {
                        val apk = (dlState as UpdateManager.DownloadState.Done).apkFile
                        val ok = UpdateManager.installApk(context, apk)
                        if (!ok) {
                            // Android 8+ 未获「安装未知应用」授权 → 引导去设置
                            val intent = UpdateManager.installPermissionSettingsIntent(context)
                            if (intent != null) runCatching { context.startActivity(intent) }
                            Toast.makeText(
                                context, "请先允许本应用安装未知应用", Toast.LENGTH_LONG
                            ).show()
                        }
                    }) { Text("安装") }
                }
                is UpdateManager.DownloadState.Failed -> {
                    TextButton(onClick = {
                        UpdateManager.downloadApk(context, info)
                    }) { Text("重试下载") }
                }
                else -> {
                    TextButton(onClick = {
                        UpdateManager.downloadApk(context, info)
                    }) { Text("下载更新") }
                }
            }
        },
        dismissButton = {
            when (dlState) {
                is UpdateManager.DownloadState.Progress -> {
                    // 下载中：取消 = 中断下载
                    TextButton(onClick = {
                        UpdateManager.resetDownload()
                    }) { Text("取消下载") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dlState is UpdateManager.DownloadState.Failed) {
                            TextButton(onClick = {
                                uriHandler.openUri(info.htmlUrl)
                            }) { Text("下载页") }
                        }
                        // v1.4.23：记住跳过的版本——重启不再自动弹窗，直到更新的版本出现
                        TextButton(onClick = {
                            UpdateManager.skipVersion(info.versionName)
                            onDismiss()
                        }) { Text("暂不更新") }
                    }
                }
            }
        }
    )
}

/** Release notes 去 markdown 标记（标题符/加粗/斜体/链接/代码/引用），纯文本展示。 */
private fun plainNotes(md: String): String {
    return md
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
        .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        .trim()
        .ifBlank { "性能优化与问题修复。" }
}

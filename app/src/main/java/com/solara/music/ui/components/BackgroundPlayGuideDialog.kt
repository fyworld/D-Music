package com.solara.music.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.solara.music.data.Store

/**
 * v1.4.35：后台播放保活引导弹窗。
 *
 * 检测到熄屏播放中断（ROM 冻结/限制后台导致断流走错误链）后，回前台
 * 首次弹出：说明原因 + 引导去系统设置调整省电策略。这是 MIUI/HyperOS
 * 等国产 ROM 的特有限制——厂商云控白名单外的应用（个人开发者无法申请）
 * 被「智能限制」严格执行，代码层面无法绕过，只能引导用户手动设置。
 *
 * 状态机（Store.bgPlayGuideState）：0 未触发 → 1 检测到中断待引导 →
 * 2 已完成设置/永久忽略（不再弹出）。
 */
@Composable
fun BackgroundPlayGuideDialog(
    onFinished: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onFinished,
        title = { Text("让后台播放更稳定") },
        text = {
            Column {
                Text(
                    text = "检测到熄屏后播放被系统中断。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "部分手机系统（如小米/华为/OPPO 等）会自动限制后台应用以省电，导致熄屏一段时间后音乐停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "设置方法：进入本应用的系统设置页 →「省电策略」→ 改为「无限制」（或类似选项），部分机型还需开启「自启动」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Store.saveBgPlayGuideState(2)
                // 直达本应用的系统详情页（省电策略/自启动都在这里）
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }
                onFinished()
            }) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = {
                // 「暂不」= 本次不设置，但下次中断仍会提醒（状态保持 1，
                // 回前台不再重复弹，直到再次检测到中断时 UI 重新读取）
                Store.saveBgPlayGuideState(0)
                onFinished()
            }) { Text("暂不") }
        }
    )
}

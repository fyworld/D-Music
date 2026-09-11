package com.solara.music.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.solara.music.data.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App 内文件夹浏览器（v1.4.7：替代系统 SAF 选目录）。
 *
 * 背景：OpenDocumentTree 在部分 ROM（MIUI/HyperOS）上会记住上次授权的
 * tree URI，第二次打开直接自动返回旧目录，无法重新选择；且点击文件夹
 * 即触发回调，没有"确认"步骤，无法进入子目录。
 *
 * 本组件：从内部存储根开始浏览，点击文件夹 = 进入子目录（不扫描），
 * 底部「扫描此文件夹」按钮确认后才回调。需要「所有文件访问」权限
 * （或媒体读取权限）才能列出目录；无权限时提示去开启。
 *
 * @param initialRelPath 初始定位的相对路径（如 "Music/D_Music"），null = 根
 * @param onConfirm 用户确认扫描的相对路径（如 "Music/我的音乐"）；空串 = 根
 */
@Composable
fun FolderPickerDialog(
    initialRelPath: String?,
    onDismiss: () -> Unit,
    onConfirm: (relPath: String) -> Unit
) {
    val context = LocalContext.current
    // null = 内部存储根；"" 不使用（根用 null 表示）
    var currentPath by remember { mutableStateOf(initialRelPath?.takeIf { it.isNotBlank() }) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var canRead by remember { mutableStateOf(true) }

    LaunchedEffect(currentPath) {
        loading = true
        withContext(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()
            val dir = if (currentPath == null) root else File(root, currentPath)
            val list = runCatching {
                dir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    // Android/data、Android/obb 对 App 不可读，列出只会误导
                    ?.filterNot { it.parentFile?.name == "Android" && (it.name == "data" || it.name == "obb") }
                    ?.sortedBy { it.name.lowercase() }
            }.getOrNull()
            canRead = list != null
            folders = list ?: emptyList()
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .padding(16.dp)
            ) {
                // 标题行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "选择扫描文件夹",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                // 当前路径 + 返回上级
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    IconButton(
                        onClick = {
                            currentPath = currentPath?.substringBeforeLast('/', "")?.ifBlank { null }
                        },
                        enabled = currentPath != null
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "返回上级")
                    }
                    Text(
                        text = "内部存储" + (currentPath?.let { "/$it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 文件夹列表
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                ) {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                        }

                        !canRead -> Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "无法读取文件夹列表\n请开启「所有文件访问」权限后重试",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                DownloadManager.allFilesAccessSettingsIntent(context)?.let {
                                    runCatching { context.startActivity(it) }
                                }
                            }) { Text("去开启") }
                        }

                        folders.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                            Text(
                                text = "此文件夹下没有子文件夹",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        else -> LazyColumn {
                            items(folders, key = { it.absolutePath }) { folder ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentPath = if (currentPath == null) folder.name
                                            else "$currentPath/${folder.name}"
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 10.dp)
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部确认按钮
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { onConfirm(currentPath ?: "") },
                        enabled = currentPath != null
                    ) { Text("扫描此文件夹") }
                }
                Text(
                    text = "扫描包含子目录内的全部音频文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

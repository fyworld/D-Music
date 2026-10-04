package com.solara.music.ui.customsource

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solara.music.customsource.CustomSourceManager
import com.solara.music.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.5.1 r26：自定义音源管理页。
 *
 * - 脚本列表（名称/版本/作者/导入时间），当前启用项高亮
 * - 导入：本地 .js 文件（SAF）+ 在线 URL 两种方式
 * - 触发策略：GD API 兜底（默认）/ 自定义源优先
 * - 每项支持启用/停用、删除
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSourceScreen(onBack: () -> Unit, onShowMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scripts by CustomSourceManager.scripts.collectAsState()
    val activeId by CustomSourceManager.activeId.collectAsState()
    val triggerMode by CustomSourceManager.triggerMode.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // v1.5.1 r28：测试取歌状态
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // SAF 文件选择器（本地 .js 导入）
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val script = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val entry = script?.let { withContext(Dispatchers.IO) { CustomSourceManager.importScript(it) } }
            importing = false
            onShowMessage(
                if (entry != null) {
                    val enableErr = CustomSourceManager.lastImportEnableError
                    if (enableErr != null) "已导入「${entry.name}」，但自动启用失败：$enableErr"
                    else "已导入「${entry.name}」"
                } else "导入失败：不是有效的音源脚本（缺少头部注释或 @name）"
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义音源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showImportDialog = true },
                        enabled = !importing
                    ) {
                        Icon(Icons.Filled.Add, "导入")
                    }
                }
            )
        }
    ) { padding ->
        if (importing) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("导入中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 触发策略
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "触发策略",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "兜底：GD音乐台 API 解析失败时才使用自定义源（默认）\n" +
                                "优先：先走自定义源，失败再回落 GD音乐台 API",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = triggerMode == CustomSourceManager.TRIGGER_FALLBACK,
                                onClick = { CustomSourceManager.setTriggerMode(CustomSourceManager.TRIGGER_FALLBACK) },
                                label = { Text("API 失败时兜底") }
                            )
                            FilterChip(
                                selected = triggerMode == CustomSourceManager.TRIGGER_PREFERRED,
                                onClick = { CustomSourceManager.setTriggerMode(CustomSourceManager.TRIGGER_PREFERRED) },
                                label = { Text("自定义源优先") }
                            )
                        }
                    }
                }
            }

            // v1.5.1 r28：测试取歌——用当前播放的歌直接调脚本解析，
            // 立即验证自定义源可用（与播放链路完全同路径）
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val currentSong = PlayerManager.currentSong.value
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "测试取歌",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    currentSong?.let {
                                        "用当前播放的歌验证：「${it.displayName} - ${it.artistName}」"
                                    } ?: "先播放一首歌再来测试（需要歌曲信息）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = {
                                    val s = currentSong ?: return@Button
                                    scope.launch {
                                        testing = true
                                        testResult = null
                                        testResult = withContext(Dispatchers.IO) {
                                            runCatching {
                                                // v1.5.1 r32：传完整 Song（含 hash/
                                                // strMediaMid/copyrightId 平台字段）
                                                CustomSourceManager.testResolve(s)
                                            }.getOrElse { "测试异常：${it.message}" }
                                        }
                                        testing = false
                                    }
                                },
                                enabled = currentSong != null && !testing && activeId.isNotBlank()
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else Text("测试")
                            }
                        }
                        testResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                result,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.startsWith("解析成功"))
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }

            if (scripts.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(24.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "还没有导入音源脚本",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "点右上角「+」导入 lx-music 格式的 .js 音源脚本",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "已导入 ${scripts.size} 个脚本（同时启用一个）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(scripts, key = { it.id }) { entry ->
                    val isActive = entry.id == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isActive)
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        else CardDefaults.cardColors()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val meta = buildString {
                                        if (entry.version.isNotBlank()) append("v${entry.version}  ")
                                        if (entry.author.isNotBlank()) append(entry.author)
                                    }
                                    if (meta.isNotBlank()) {
                                        Text(
                                            meta,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (isActive) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "已启用",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (entry.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    entry.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isActive) {
                                    OutlinedButton(onClick = { CustomSourceManager.deactivate() }) {
                                        Text("停用")
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val err = CustomSourceManager.setActive(entry.id)
                                                onShowMessage(
                                                    err ?: "已启用「${entry.name}」"
                                                )
                                            }
                                        }
                                    ) { Text("启用") }
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            CustomSourceManager.remove(entry.id)
                                            onShowMessage("已删除「${entry.name}」")
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) { Text("删除") }
                            }
                        }
                    }
                }
            }

            // 说明
            item {
                Text(
                    "音源脚本在应用内沙箱运行（无网络权限，请求由 D Music 代发），" +
                        "兼容 lx-music 自定义音源格式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    if (showImportDialog) {
        ImportDialog(
            onDismiss = { showImportDialog = false },
            onImportFile = {
                showImportDialog = false
                filePicker.launch(arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"))
            },
            onImportUrl = { url ->
                showImportDialog = false
                    scope.launch {
                        importing = true
                        val entry = withContext(Dispatchers.IO) {
                            CustomSourceManager.importFromUrl(url)
                        }
                        importing = false
                        onShowMessage(
                            if (entry != null) {
                                val enableErr = CustomSourceManager.lastImportEnableError
                                if (enableErr != null) "已导入「${entry.name}」，但自动启用失败：$enableErr"
                                else "已导入「${entry.name}」"
                            } else "导入失败：下载失败或脚本格式无效"
                        )
                    }
            }
        )
    }
}

/** 导入方式选择对话框（本地文件 / 在线 URL）。 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: (String) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var urlMode by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入音源脚本") },
        text = {
            Column {
                if (!urlMode) {
                    ListItem(
                        headlineContent = { Text("本地文件") },
                        supportingContent = { Text("选择设备上的 .js 音源脚本文件") },
                        leadingContent = { Icon(Icons.Filled.FolderOpen, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ListItem(
                        headlineContent = { Text("在线 URL") },
                        supportingContent = { Text("从网络地址下载音源脚本") },
                        leadingContent = { Icon(Icons.Filled.Link, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(onClick = onImportFile) { Text("选择文件") }
                        TextButton(onClick = { urlMode = true }) { Text("输入 URL") }
                    }
                } else {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("脚本 URL") },
                        singleLine = true,
                        placeholder = { Text("https://...") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { urlMode = false }) { Text("返回") }
                        TextButton(
                            onClick = { if (urlInput.isNotBlank()) onImportUrl(urlInput.trim()) },
                            enabled = urlInput.isNotBlank()
                        ) { Text("下载导入") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

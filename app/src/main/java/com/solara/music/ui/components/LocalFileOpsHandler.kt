package com.solara.music.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.solara.music.data.DownloadManager
import com.solara.music.data.LocalCoverExtractor
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 待授权操作：移除或重命名（v1.4.4）。 */
private data class PendingAuth(
    val song: Song,
    val isDelete: Boolean,
    val renameInput: String? = null
)

/** v1.4.5：待「所有文件访问」授权的操作（跳系统设置返回后自动续办）。 */
private data class PendingAllFiles(
    val song: Song,
    val isDelete: Boolean,
    val renameInput: String? = null
)

/**
 * v1.4.58：本地歌曲文件操作公共组件（从 LocalSongsScreen 抽出）。
 *
 * 封装「删除文件 + 移出记录」「重命名文件 + 同步记录/队列/封面」的完整链路：
 * - 他建文件 Scoped Storage 授权（系统弹窗 / 「所有文件访问」永久授权）
 * - 目标在播时先停止播放释放文件句柄
 * - 成功后回调 onFilesChanged（调用方刷新列表）
 *
 * 用法：页面里放一个本组件 + 状态提升：
 * ```
 * var opsTarget by remember { mutableStateOf<Song?>(null) }        // 删除
 * var renameTarget by remember { mutableStateOf<Song?>(null) }     // 重命名
 * LocalFileOpsHandler(
 *     deleteTarget = opsTarget, onDeleteTargetChange = { opsTarget = it },
 *     renameTarget = renameTarget, onRenameTargetChange = { renameTarget = it },
 *     onDone = { message -> ... }
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFileOpsHandler(
    deleteTarget: Song?,
    onDeleteTargetChange: (Song?) -> Unit,
    renameTarget: Song?,
    onRenameTargetChange: (Song?) -> Unit,
    onDone: (message: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAuth by remember { mutableStateOf<PendingAuth?>(null) }
    var askAllFiles by remember { mutableStateOf<PendingAllFiles?>(null) }
    var pendingAllFiles by remember { mutableStateOf<PendingAllFiles?>(null) }

    /** 停止目标播放并释放文件句柄（删除/重命名前调用）。 */
    fun stopIfPlaying(target: Song) {
        val cur = PlayerManager.currentSong.value
        if (cur != null && cur.sameAs(target)) {
            PlayerManager.stop()
            PlayerManager.playerOrNull?.let { p ->
                runCatching { p.stop(); p.clearMediaItems() }
            }
        }
    }

    /** 执行删除并清理记录/队列。 */
    fun performDelete(target: Song, onResult: (DownloadManager.LocalFileResult) -> Unit) {
        scope.launch {
            stopIfPlaying(target)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadManager.deleteLocalFileWithAuth(context.applicationContext, target)
                }.getOrDefault(DownloadManager.LocalFileResult.FAILED)
            }
            when (result) {
                DownloadManager.LocalFileResult.DELETED,
                DownloadManager.LocalFileResult.NOT_FOUND -> {
                    Store.removeDownload(target)
                    PlayerManager.queue.value.forEachIndexed { i, s ->
                        if (s.sameAs(target)) {
                            // v1.4.58 第六轮：删除文件后什么都不做——
                            // 静默移除，不自动播下一首
                            PlayerManager.removeAt(i, autoplayNext = false)
                            return@forEachIndexed
                        }
                    }
                }

                else -> {}
            }
            onResult(result)
        }
    }

    /** 执行重命名并同步记录/队列/封面。 */
    fun performRename(target: Song, input: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            stopIfPlaying(target)
            val (result, newFile) = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadManager.renameLocalFileWithAuth(
                        context.applicationContext, target, input
                    )
                }.getOrDefault(DownloadManager.RenameResult.FAILED to null)
            }
            when (result) {
                DownloadManager.RenameResult.OK -> {
                    Store.renameDownload(target, newFile!!)
                    LocalCoverExtractor.invalidate(target)
                    LocalCoverExtractor.bumpRevision()
                    PlayerManager.replaceSong(
                        target,
                        Store.downloads.value.firstOrNull {
                            it.id == "local:$newFile"
                        } ?: target.copy(id = "local:$newFile")
                    )
                    onResult(true)
                }

                DownloadManager.RenameResult.NEEDS_AUTH -> {
                    askAllFiles = PendingAllFiles(target, isDelete = false, renameInput = input)
                }

                DownloadManager.RenameResult.NOT_FOUND ->
                    onResult(false)

                else -> onResult(false)
            }
        }
    }

    // v1.4.5：执行「所有文件访问」授权后的待办操作。
    // 从系统设置返回（onResume）且权限已开 → 自动重试一次
    fun runPendingAllFiles() {
        val pending = pendingAllFiles ?: return
        pendingAllFiles = null
        if (!DownloadManager.hasAllFilesAccess()) {
            onDone("未开启「所有文件访问」，操作已取消")
            return
        }
        if (pending.isDelete) {
            performDelete(pending.song) { result ->
                if (result == DownloadManager.LocalFileResult.DELETED) {
                    onDone("已删除文件")
                } else if (result != DownloadManager.LocalFileResult.NOT_FOUND) {
                    onDone("文件删除失败")
                }
            }
        } else {
            val input = pending.renameInput ?: return
            performRename(pending.song, input) { ok ->
                onDone(if (ok) "重命名成功" else "重命名失败")
            }
        }
    }

    // 从系统设置开启权限返回本页时，自动续办刚才的操作
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingAllFiles != null) runPendingAllFiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v1.4.4：Scoped Storage 授权弹窗结果（删除/写入他建文件）
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingAuth
        pendingAuth = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            if (pending.isDelete) {
                // 系统已删文件（或授权成功），清理记录
                Store.removeDownload(pending.song)
                PlayerManager.queue.value.forEachIndexed { i, s ->
                    if (s.sameAs(pending.song)) {
                        // v1.4.58 第六轮：静默移除，不自动播下一首
                        PlayerManager.removeAt(i, autoplayNext = false)
                        return@forEachIndexed
                    }
                }
                onDone("已删除文件")
            } else {
                // 写权限已授予：重试重命名
                val input = pending.renameInput ?: return@rememberLauncherForActivityResult
                scope.launch {
                    val newFile = withContext(Dispatchers.IO) {
                        runCatching {
                            DownloadManager.renameLocalFile(
                                context.applicationContext, pending.song, input
                            )
                        }.getOrNull()
                    }
                    if (newFile != null) {
                        Store.renameDownload(pending.song, newFile)
                        LocalCoverExtractor.invalidate(pending.song)
                        LocalCoverExtractor.bumpRevision()
                        PlayerManager.replaceSong(
                            pending.song,
                            Store.downloads.value.firstOrNull {
                                it.id == "local:$newFile"
                            } ?: pending.song.copy(id = "local:$newFile")
                        )
                        onDone("重命名成功")
                    } else {
                        onDone("重命名失败")
                    }
                }
            }
        } else if (pending != null && !pending.isDelete) {
            onDone("未获得文件修改权限，重命名已取消")
        }
    }

    // 删除确认
    if (deleteTarget != null) {
        val song = deleteTarget
        AlertDialog(
            onDismissRequest = { onDeleteTargetChange(null) },
            title = { Text("移除本地歌曲") },
            text = {
                Text(
                    "将「${song.artistName} - ${song.displayName}」从手机中删除，无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTargetChange(null)
                    performDelete(song) { result ->
                        when (result) {
                            DownloadManager.LocalFileResult.DELETED -> onDone("已删除文件")
                            DownloadManager.LocalFileResult.NEEDS_AUTH ->
                                askAllFiles = PendingAllFiles(song, isDelete = true)
                            DownloadManager.LocalFileResult.NOT_FOUND -> onDone("文件已不存在")
                            else -> onDone("音频文件删除失败")
                        }
                    }
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { onDeleteTargetChange(null) }) { Text("取消") }
            }
        )
    }

    // 重命名对话框
    if (renameTarget != null) {
        val song = renameTarget
        // v1.4.58 第六轮：默认值必须显示真正的文件名——
        // 本地导入歌：id 含文件定位信息（可能带路径前缀 Music/DTS/xxx.mp3），
        //   取纯文件名去扩展名；
        // 在线下载记录：id 是 API 数字 id 解析不出文件名，文件名由元数据
        //   派生（下载时就是「歌手 - 歌名.ext」落盘的），按同样规则预填
        val isLocalImport = song.source == "local" && song.id.startsWith("local:")
        val oldBase = if (isLocalImport) {
            song.id.removePrefix("local:")
                .substringAfterLast('/')
                .substringBeforeLast('.', "")
        } else {
            "${song.artistName} - ${song.displayName}"
        }
        var newName by remember(song.id) { mutableStateOf(oldBase) }
        AlertDialog(
            onDismissRequest = { onRenameTargetChange(null) },
            title = { Text("重命名本地歌曲") },
            text = {
                Column {
                    Text(
                        "修改文件名（不含扩展名）。建议保持「歌手 - 歌名」格式，" +
                            "列表标题和歌手会按此重新解析。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val input = newName.trim()
                        onRenameTargetChange(null)
                        if (input.isBlank() || input == oldBase) return@TextButton
                        performRename(song, input) { ok ->
                            if (ok) onDone("重命名成功")
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { onRenameTargetChange(null) }) { Text("取消") }
            }
        )
    }

    // v1.4.5：他建文件操作的自家选择框——
    // 「去开启」= 跳系统设置开「所有文件访问」（一次开启，永久静默）；
    // 「仅此一次」= 旧流程（系统单次授权弹窗）
    if (askAllFiles != null) {
        val pending = askAllFiles!!
        AlertDialog(
            onDismissRequest = { askAllFiles = null },
            title = { Text("需要文件管理权限") },
            text = {
                Text(
                    "「${pending.song.displayName}」由其他应用创建，系统要求授权后才能" +
                        (if (pending.isDelete) "删除" else "重命名") + "。\n\n" +
                        "推荐开启「所有文件访问」：只需设置一次，之后删除、重命名" +
                        "任何歌曲都不会再弹窗。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askAllFiles = null
                    val intent = DownloadManager.allFilesAccessSettingsIntent(context)
                    if (intent != null) {
                        pendingAllFiles = pending
                        runCatching { context.startActivity(intent) }
                    } else {
                        // 系统不支持直达设置页：退回单次授权
                        scope.launch {
                            val sender = withContext(Dispatchers.IO) {
                                if (pending.isDelete) {
                                    DownloadManager.createDeleteAuth(context, pending.song)
                                } else {
                                    DownloadManager.createRenameAuth(context, pending.song)
                                }
                            }
                            if (sender != null) {
                                pendingAuth = PendingAuth(
                                    pending.song, pending.isDelete, pending.renameInput
                                )
                                runCatching {
                                    authLauncher.launch(
                                        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                                    )
                                }
                            } else {
                                onDone("当前系统不支持此授权方式")
                            }
                        }
                    }
                }) { Text("去开启（推荐）") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askAllFiles = null
                    // 仅此一次：走旧的单次系统授权弹窗
                    scope.launch {
                        val sender = withContext(Dispatchers.IO) {
                            if (pending.isDelete) {
                                DownloadManager.createDeleteAuth(context, pending.song)
                            } else {
                                DownloadManager.createRenameAuth(context, pending.song)
                            }
                        }
                        if (sender != null) {
                            pendingAuth = PendingAuth(
                                pending.song, pending.isDelete, pending.renameInput
                            )
                            runCatching {
                                authLauncher.launch(
                                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                                )
                            }
                        } else {
                            onDone("当前系统不支持此授权方式")
                        }
                    }
                }) { Text("仅此一次") }
            }
        )
    }
}

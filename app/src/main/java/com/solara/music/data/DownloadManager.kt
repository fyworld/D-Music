package com.solara.music.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.solara.music.MainActivity
import com.solara.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 单个下载任务状态。 */
data class DownloadTask(
    val song: Song,
    val quality: String,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val filePath: String? = null,
    val error: String? = null
) {
    val id: String get() = "${song.source}:${song.id}:$quality"
    val fileName: String
        get() = "${song.artistName} - ${song.displayName}.${if (quality == "999") "flac" else "mp3"}"
}

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, FAILED }

/**
 * 多码率下载管理器：先解析直链，再流式下载。
 * - Android 10+：MediaStore.Audio 写入公共 Music/D_Music（无需存储权限）
 * - Android 9-：直接写外部存储 app 专属目录（无需权限）
 */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 任务列表（新任务插到头部）。 */
    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * v1.4.13 #63：并发下载信号量——批量下载 30 首时防止同时打满网络
     * （单曲下载同样受限，全局统一最多 3 路并行）。
     */
    private val downloadSemaphore = Semaphore(permits = 3)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---- v1.4.22：下载进度通知 ----

    /** 下载通知 ID（与播放通知 100 区分）。 */
    private const val DOWNLOAD_NOTIFICATION_ID = 200
    private const val DOWNLOAD_CHANNEL_ID = "d_music_download"

    /** 通知刷新节流（与任务进度节流同频，300ms）。 */
    private var lastNotifyAt = 0L

    /** Application context（首次 enqueue 时捕获，通知用）。 */
    private var notifContext: Context? = null

    /** 创建下载通知渠道（低重要性：不响铃不弹横幅，进度静默刷新）。 */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        DOWNLOAD_CHANNEL_ID,
                        "音乐下载",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    /** 是否有通知权限（Android 13+ 未授权时静默跳过通知，不影响下载）。 */
    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 刷新聚合下载进度通知（v1.4.22）：
     * - 进行中：显示「正在下载 n/m」+ 当前歌曲名 + 总进度条（300ms 节流）
     * - 全部结束：显示「下载完成 n 首成功 / k 首失败」，5 秒后自动清除
     * 任何异常静默吞掉——通知失败绝不能影响下载本身。
     */
    private fun refreshDownloadNotification() {
        val context = notifContext ?: return
        runCatching {
            if (!canNotify(context)) return
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val all = tasks.value
            val active = all.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
            val done = all.count { it.status == DownloadStatus.DONE }
            val failed = all.count { it.status == DownloadStatus.FAILED }

            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            if (active.isNotEmpty()) {
                // 进行中：节流刷新
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt < 300) return
                lastNotifyAt = now

                val current = active.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                    ?: active.first()
                val totalProgress = active.sumOf { it.progress.toDouble() } / active.size
                val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle("下载中 ${done}/${all.size}")
                    .setContentText("${current.song.displayName} - ${current.song.artistName}")
                    .setProgress(100, (totalProgress * 100).toInt(), false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent)
                    .build()
                nm.notify(DOWNLOAD_NOTIFICATION_ID, builder)
            } else if (done > 0 || failed > 0) {
                // 全部结束：完成通知（可滑掉，5 秒后自动清除）
                lastNotifyAt = 0L
                val title = if (failed == 0) "下载完成（${done} 首）"
                else if (done == 0) "下载失败（${failed} 首）"
                else "下载完成：${done} 首成功，${failed} 首失败"
                val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(title)
                    .setContentText("已保存到 Music/D_Music")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent)
                    .build()
                nm.notify(DOWNLOAD_NOTIFICATION_ID, builder)
                // 5 秒后自动清除（完成通知不宜久留）
                scope.launch {
                    kotlinx.coroutines.delay(5000)
                    runCatching {
                        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .cancel(DOWNLOAD_NOTIFICATION_ID)
                    }
                }
            } else {
                // 任务列表被清空（removeTask/clearFinished）：撤掉残留的进度通知
                nm.cancel(DOWNLOAD_NOTIFICATION_ID)
            }
        }
    }

    /** 入队下载。同一首歌同品质重复提交会被忽略。 */
    fun enqueue(context: Context, song: Song, quality: String) {
        val id = "${song.source}:${song.id}:$quality"
        if (jobs.containsKey(id)) return
        val task = DownloadTask(song = song, quality = quality)
        updateTask { list -> listOf(task) + list.filterNot { it.id == id } }
        notifContext = context.applicationContext   // v1.4.22：通知用
        refreshDownloadNotification()

        jobs[id] = scope.launch {
            try {
                updateTask { list ->
                    list.map { if (it.id == id) it.copy(status = DownloadStatus.DOWNLOADING) else it }
                }
                refreshDownloadNotification()
                downloadSemaphore.withPermit {
                    val url = MusicApi.resolveUrl(song, quality)
                    if (url.isNullOrBlank()) {
                        fail(id, "无法解析直链（音源可能不支持该品质）")
                        return@withPermit
                    }
                    downloadTo(context.applicationContext, id, url, task)
                }
            } catch (e: Exception) {
                fail(id, e.message ?: "下载失败")
            } finally {
                jobs.remove(id)
            }
        }
    }

    fun removeTask(id: String) {
        jobs.remove(id)?.cancel()
        updateTask { list -> list.filterNot { it.id == id } }
        refreshDownloadNotification()
    }

    fun clearFinished() {
        updateTask { list -> list.filter { it.status == DownloadStatus.DOWNLOADING } }
        refreshDownloadNotification()
    }

    /**
     * 查询歌曲对应的全部 MediaStore URI（v1.4.4）。
     * local: 导入歌曲按文件名全库精确查；在线下载记录按 D_Music 目录 +
     * 元数据派生文件名查。返回空列表 = 文件不存在。
     */
    fun queryLocalUris(context: Context, song: Song): List<Uri> {
        val safe = fun(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val selection =
            "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?"
        val isImported = song.source == "local" && song.id.startsWith("local:")
        val fileNames = mutableListOf<String>()
        if (isImported) {
            fileNames.add(song.id.removePrefix("local:"))
        } else {
            listOf("mp3", "flac").forEach { ext ->
                fileNames.add(safe("${song.artistName} - ${song.displayName}.$ext"))
            }
        }
        val dirFilter = if (isImported) "%" else "%/D_Music/%"
        val uris = mutableListOf<Uri>()
        fileNames.forEach { fileName ->
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                selection,
                arrayOf(dirFilter, fileName),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (cursor.moveToNext()) {
                    uris.add(
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)
                        )
                    )
                }
            }
        }
        return uris
    }

    /**
     * 删除结果（v1.4.4）。
     * - [DELETED] 全部删除成功
     * - [NEEDS_AUTH] 有文件是其他 App 创建的（Scoped Storage 不让直接删），
     *   需调 [createDeleteAuth] 拿系统授权 IntentSender 发起用户确认
     * - [NOT_FOUND] 没找到任何文件
     * - [FAILED] 删除时出错
     */
    enum class LocalFileResult { DELETED, NEEDS_AUTH, NOT_FOUND, FAILED }

    /**
     * 删除歌曲本地文件（v1.4.4：区分"自有文件直接删"与"他建文件需授权"）。
     * Android 10+ Scoped Storage：App 只能直接删除自己创建的媒体文件；
     * 其他 App 创建的（扫描导入的歌多属此类）必须用户通过系统弹窗授权。
     */
    fun deleteLocalFileWithAuth(context: Context, song: Song): LocalFileResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return if (deleteLocalFile(context, song)) LocalFileResult.DELETED
            else LocalFileResult.NOT_FOUND
        }
        val ctx = context.applicationContext
        val uris = queryLocalUris(ctx, song)
        if (uris.isEmpty()) return LocalFileResult.NOT_FOUND

        // 先删自有的（能删掉的），剩下的就是需要授权的
        val remaining = uris.filter { uri ->
            runCatching { ctx.contentResolver.delete(uri, null, null) }.getOrDefault(0) <= 0
        }
        return if (remaining.isEmpty()) LocalFileResult.DELETED else LocalFileResult.NEEDS_AUTH
    }

    /**
     * 构造"请求删除授权"的 IntentSender（Android 10+）。
     * 用户确认后系统直接删除文件；返回 null 表示构造失败（如 Android 9-）。
     * 调用方用 startIntentSenderForResult 发起。
     */
    fun createDeleteAuth(context: Context, song: Song): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val ctx = context.applicationContext
        val uris = queryLocalUris(ctx, song)
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
        }.getOrNull()
    }

    /**
     * 构造"请求写入授权"的 IntentSender（Android 11+，重命名用）。
     * 用户确认后 App 获得这些 URI 的写权限（本会话内）。
     */
    fun createRenameAuth(context: Context, song: Song): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val ctx = context.applicationContext
        val uris = queryLocalUris(ctx, song)
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createWriteRequest(ctx.contentResolver, uris).intentSender
        }.getOrNull()
    }

    /**
     * 是否已获得「所有文件访问」权限（Android 11+）。
     * 开启后 App 可直接删除/重命名任何媒体文件，无需逐个弹系统授权框。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    /**
     * 跳系统「所有文件访问」设置页的 Intent（Android 11+）。
     * 返回 null 表示当前系统不支持（Android 10 及以下走旧逻辑）。
     */
    fun allFilesAccessSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        }.getOrNull() ?: runCatching {
            // 某些 ROM 不支持带 package 数据的直达页，兜底到全局列表
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }.getOrNull()
    }

    /**
     * 删除歌曲已下载的本地文件（所有品质）。
     * - Android 10+：按 DATA 路径 + 文件名在 MediaStore 查询后删除
     * - Android 9-：直接删除 app 专属目录下的文件
     * 返回是否至少删除了一个文件。
     */
    fun deleteLocalFile(context: Context, song: Song): Boolean {
        val safe = fun(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var deleted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 用 DATA LIKE 匹配目录：RELATIVE_PATH 各 ROM 格式不一致，不可靠
            val selection =
                "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?"

            val fileNames = mutableListOf<String>()
            // 本地扫描导入的歌曲：id 里存了原始文件名，直接精确删除
            val isImported = song.source == "local" && song.id.startsWith("local:")
            if (isImported) {
                fileNames.add(song.id.removePrefix("local:"))
            } else {
                listOf("mp3", "flac").forEach { ext ->
                    fileNames.add(safe("${song.artistName} - ${song.displayName}.$ext"))
                }
            }
            // v1.4.2：导入歌曲可能在全库任意位置（不限 D_Music），
            // 在线下载记录仍限定 D_Music 目录
            val dirFilter = if (isImported) "%" else "%/D_Music/%"
            fileNames.forEach { fileName ->
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    selection,
                    arrayOf(dirFilter, fileName),
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)
                        )
                        if (context.contentResolver.delete(uri, null, null) > 0) deleted = true
                    }
                }
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
            val fileNames = mutableListOf<String>()
            if (song.source == "local" && song.id.startsWith("local:")) {
                fileNames.add(song.id.removePrefix("local:"))
            } else {
                listOf("mp3", "flac").forEach { ext ->
                    fileNames.add(safe("${song.artistName} - ${song.displayName}.$ext"))
                }
            }
            fileNames.forEach { name ->
                val f = File(dir, name)
                if (f.exists() && f.delete()) deleted = true
            }
        }
        return deleted
    }

    /**
     * 查找歌曲已下载的本地文件，返回可直接给 ExoPlayer 播放的 URI 字符串。
     * - Android 10+：MediaStore 查询（优先 flac，其次 mp3），返回 content:// Uri
     * - Android 9-：检查 app 专属目录，返回 file:// URI
     * 找不到返回 null（调用方回退到在线播放）。
     */
    fun findLocalPlayableUri(context: Context, song: Song): String? {
        val safe = fun(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 本地扫描导入的歌曲：id 里存了原始文件名（local:xxx.mp3），直接精确查询
            if (song.source == "local" && song.id.startsWith("local:")) {
                val fileName = song.id.removePrefix("local:")
                val exact = queryByDisplayName(context, fileName)
                if (exact != null) return exact
            }

            // 用 DATA LIKE 匹配目录：RELATIVE_PATH 各 ROM 格式不一致，不可靠
            val selection =
                "${MediaStore.Audio.Media.DATA} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            // 文件名里的 % _ 是 SQL LIKE 通配符，需转义
            val pattern = (safe("${song.artistName} - ${song.displayName}."))
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val found = mutableListOf<Pair<Long, String>>()
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                selection,
                arrayOf("%/D_Music/%", pattern),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".mp3") && !name.endsWith(".flac")) continue
                    found.add(cursor.getLong(idCol) to name)
                }
            }
            // 优先 flac（无损），其次 mp3
            val best = found.firstOrNull { it.second.endsWith(".flac") }
                ?: found.firstOrNull { it.second.endsWith(".mp3") }
            return best?.let {
                ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.first
                ).toString()
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
            // 本地扫描导入的歌曲：id 里存了原始文件名，直接精确匹配
            if (song.source == "local" && song.id.startsWith("local:")) {
                val fileName = song.id.removePrefix("local:")
                val f = File(dir, fileName)
                if (f.exists()) return Uri.fromFile(f).toString()
            }
            listOf("flac", "mp3").forEach { ext ->
                val f = File(dir, safe("${song.artistName} - ${song.displayName}.$ext"))
                if (f.exists()) return Uri.fromFile(f).toString()
            }
            return null
        }
    }

    /**
     * 按精确文件名查询 MediaStore，命中返回 content:// URI。
     * v1.4.2：不再限定 D_Music 目录——本地扫描的歌曲可能来自全库任意位置。
     */
    private fun queryByDisplayName(context: Context, fileName: String): String? {
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            selection,
            arrayOf(fileName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                )
                return uri.toString()
            }
        }
        return null
    }

    /**
     * 扫描本地音乐库，重建本地歌曲列表。
     *
     * v1.4.3：按指定文件夹扫描（用户反馈全库扫描会把不需要的文件都加进来）。
     * - folderPath 为 null 时扫 App 默认下载目录 Music/D_Music
     * - folderPath 为相对路径（如 "Music/我的歌"）时扫该目录
     * - Android 10+：MediaStore 按 DATA LIKE '…/folder/%' 查询（排除备份目录）
     * - Android 9-：仍只扫 app 专属目录 D_Music（无权限读公共库）
     *
     * 文件名约定「歌手 - 歌名.扩展名」反推 Song 元数据：
     * id = "local:" + 文件名（含扩展名），source = "local"。
     * 已在 Store.downloads 里的记录（含在线歌曲的下载）原样保留。
     */
    fun scanLocalLibrary(context: Context, folderPath: String? = null): List<Song> {
        val result = LinkedHashMap<String, Song>() // key=source:id，保序去重
        Store.downloads.value.forEach { result["${it.source}:${it.id}"] = it }

        val folder = folderPath ?: "${Environment.DIRECTORY_MUSIC}/D_Music"

        // v1.4.6：有「所有文件访问」权限时直接扫文件系统——彻底绕过
        // MediaStore（App 下载的文件 IS_MUSIC 常为 0，媒体库查询永远漏掉它们）
        if (hasAllFilesAccess()) {
            val root = resolveScanRoot(folder) ?: return scanViaMediaStore(
                context, folderPath, result
            )
            runCatching {
                root.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
                    .filter { !it.absolutePath.contains("/D_Music_Backup/") }
                    .sortedBy { it.name.lowercase() }
                    .forEach { f -> songFromFileName(f.name)?.let {
                        result.putIfAbsent("local:${it.id}", it)
                    } }
            }
            return result.values.toList()
        }
        return scanViaMediaStore(context, folderPath, result)
    }

    /** 文件系统扫描支持的扩展名（小写，不带点）。 */
    private val AUDIO_EXTS =
        listOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "ape", "wma")

    /**
     * 相对路径 → 绝对路径根目录（如 "Music/D_Music" →
     * /storage/emulated/0/Music/D_Music）。解析失败返回 null。
     */
    private fun resolveScanRoot(folder: String): File? {
        val external = Environment.getExternalStorageDirectory() ?: return null
        val dir = File(external, folder.trimStart('/'))
        return if (dir.exists() && dir.isDirectory && dir.canRead()) dir else null
    }

    /**
     * MediaStore 扫描（无所有文件访问权限时的兜底）。
     * v1.4.6：去掉 IS_MUSIC != 0 条件——App 下载的文件该标志常为 0
     * （insert 时未设置、ROM 扫描器懒惰未跑），按扩展名过滤即可。
     */
    private fun scanViaMediaStore(
        context: Context,
        folderPath: String?,
        result: LinkedHashMap<String, Song>
    ): List<Song> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val folder = folderPath ?: "${Environment.DIRECTORY_MUSIC}/D_Music"
            // DATA 是绝对路径（/storage/emulated/0/Music/D_Music/xxx.mp3），
            // 按文件夹前缀匹配；排除备份目录的伪装音频条目
            val selection =
                "${MediaStore.Audio.Media.DATA} LIKE ? AND " +
                    "${MediaStore.Audio.Media.DATA} NOT LIKE ?"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                selection,
                arrayOf("%/$folder/%", "%/D_Music_Backup/%"),
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    songFromFileName(name)?.let { result.putIfAbsent("local:${it.id}", it) }
                }
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
            if (dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) songFromFileName(f.name)?.let {
                        result.putIfAbsent("local:${it.id}", it)
                    }
                }
            }
        }
        return result.values.toList()
    }

    /**
     * 列出含音频文件的文件夹（v1.4.3：供"选择扫描文件夹"用；v1.4.6 已被
     * 系统文件管理器选文件夹取代，保留作备用）。
     * 返回相对路径列表（如 "Music/D_Music"、"Download/我的音乐"），按名称排序。
     * v1.4.6：去掉 IS_MUSIC 条件（App 下载文件该标志常为 0），按扩展名过滤。
     */
    fun listAudioFolders(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val folders = sortedSetOf<String>()
        // BUCKET_DISPLAY_NAME 是纯文件夹名；用 RELATIVE_PATH 拼出相对路径更直观
        val projection = arrayOf(
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val relCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val bucketCol =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    // 扩展名过滤（替代 IS_MUSIC：App 下载文件该标志常为 0）
                    if (AUDIO_EXTENSIONS.none { name.endsWith(it, ignoreCase = true) }) continue
                    val rel = cursor.getString(relCol) ?: continue
                    val bucket = cursor.getString(bucketCol) ?: continue
                    if (bucket.isBlank()) continue
                    // 拼成 "Music/D_Music" 样式（去尾斜杠）
                    val path = (rel.trimEnd('/') + "/" + bucket).trim('/')
                    if (path.isNotBlank()) folders.add(path)
                }
            }
        }
        return folders.toList()
    }

    /**
     * 从「歌手 - 歌名.mp3」文件名反推 Song。
     * v1.4.3：支持 mp3/flac/m4a/aac/ogg/wav 等常见格式。
     * 无 " - " 分隔时歌名=整个文件名（去扩展名）、歌手=未知。
     */
    private fun songFromFileName(fileName: String): Song? {
        val ext = AUDIO_EXTENSIONS.firstOrNull { fileName.endsWith(it, ignoreCase = true) }
            ?: return null
        val base = fileName.removeSuffix(ext)
        if (base.isBlank()) return null
        val idx = base.indexOf(" - ")
        val (artist, name) = if (idx > 0) {
            base.substring(0, idx).trim() to base.substring(idx + 3).trim()
        } else {
            "" to base.trim()
        }
        if (name.isEmpty()) return null
        return Song(
            id = "local:$fileName",
            name = name,
            artist = artist,
            source = "local"
        )
    }

    /** 本地扫描支持的音频扩展名（小写）。 */
    private val AUDIO_EXTENSIONS =
        listOf(".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav", ".ape", ".wma")

    /**
     * 重命名本地歌曲文件，返回新的完整文件名（含扩展名）；失败返回 null。
     * 自动沿用原扩展名；非法字符替换为 _；10+ 走 MediaStore DISPLAY_NAME 更新，
     * 9- 直接 File.renameTo。
     *
     * v1.4.0：10+ update 失败（文件被播放器/其他进程占用等）时降级为
     * "复制重建"——新建目标条目、拷贝数据、删除旧条目。文件内容级操作
     * 不受 MediaStore 行级写锁影响，绝大多数占用场景都能成功。
     */
    fun renameLocalFile(context: Context, song: Song, newBaseName: String): String? {
        val oldFileName = when {
            song.source == "local" && song.id.startsWith("local:") ->
                song.id.removePrefix("local:")
            else -> return null // 在线下载记录：文件名由元数据派生，不支持改名
        }
        val ext = oldFileName.substringAfterLast('.', "mp3")
        val safe = newBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (safe.isEmpty()) return null
        val newFileName = "$safe.$ext"
        if (newFileName == oldFileName) return newFileName

        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // v1.4.2：导入歌曲不限 D_Music，全库按文件名精确查；
            // 同时取原 RELATIVE_PATH 供复制重建时沿用目录
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
            var oldRelativePath: String? = null
            val uri = runCatching {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.RELATIVE_PATH
                    ),
                    selection,
                    arrayOf(oldFileName),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        oldRelativePath = c.getString(1)
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                        )
                    } else null
                }
            }.getOrNull() ?: return null

            // 1) 首选：MediaStore 原地更新 DISPLAY_NAME
            val updated = runCatching {
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, newFileName) },
                    null, null
                )
            }.getOrDefault(0)
            if (updated > 0) return newFileName

            // 2) 降级：复制重建（insert 新条目 + 拷贝数据 + 删旧条目）
            return runCatching { renameViaCopy(ctx, uri, newFileName, oldRelativePath) }.getOrNull()
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
            val ok = File(dir, oldFileName).renameTo(File(dir, newFileName))
            return if (ok) newFileName else null
        }
    }

    /** 重命名结果（v1.4.4）。 */
    enum class RenameResult {
        /** 成功，newFileName 有效 */
        OK,
        /** 文件是其他 App 创建的，需先 createRenameAuth 授权再重试 */
        NEEDS_AUTH,
        /** 没找到文件 */
        NOT_FOUND,
        /** 其他失败 */
        FAILED
    }

    /**
     * 重命名（v1.4.4）：先试常规路径（自有文件直接改），失败且文件是他建时
     * 返回 NEEDS_AUTH 让 UI 发起系统授权弹窗，用户确认后重试即可成功。
     * Android 11+ 的 createWriteRequest 授权后 update 才会被放行。
     */
    fun renameLocalFileWithAuth(
        context: Context,
        song: Song,
        newBaseName: String
    ): Pair<RenameResult, String?> {
        val direct = renameLocalFile(context, song, newBaseName)
        if (direct != null) return RenameResult.OK to direct

        // 失败：判断是"需要授权"还是"文件不存在"
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = queryLocalUris(ctx, song)
            if (uris.isEmpty()) return RenameResult.NOT_FOUND to null
            // 文件存在但改不动 → 大概率是 Scoped Storage 写权限
            return RenameResult.NEEDS_AUTH to null
        }
        return RenameResult.FAILED to null
    }

    /** 10+ 降级重命名：新建目标条目、拷贝数据、删旧条目（带重试）。 */
    private fun renameViaCopy(
        ctx: Context,
        oldUri: Uri,
        newFileName: String,
        keepRelativePath: String?
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, newFileName)
            put(
                MediaStore.Audio.Media.MIME_TYPE,
                if (newFileName.endsWith(".flac")) "audio/flac" else "audio/mpeg"
            )
            // v1.4.2：沿用原条目所在目录（导入歌曲可能不在 D_Music），
            // 查不到原路径时才落到 D_Music
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                keepRelativePath ?: "${Environment.DIRECTORY_MUSIC}/D_Music"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val newUri = ctx.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        try {
            ctx.contentResolver.openInputStream(oldUri)?.use { input ->
                ctx.contentResolver.openOutputStream(newUri)?.use { out ->
                    input.copyTo(out)
                } ?: return null
            } ?: return null
            ctx.contentResolver.update(
                newUri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null, null
            )
            // 数据已完整拷贝。删旧条目：FUSE 句柄释放有延迟，失败时重试
            // （v1.4.0 静默吞掉失败导致旧文件残留——用户看到"多了一个改名后的文件"）
            var oldDeleted = false
            repeat(3) { attempt ->
                if (runCatching { ctx.contentResolver.delete(oldUri, null, null) }
                        .getOrDefault(0) > 0) {
                    oldDeleted = true
                    return@repeat
                }
                if (attempt < 2) runCatching { Thread.sleep(300) }
            }
            if (!oldDeleted) {
                // 旧文件删不掉：回滚——删新条目，返回 null 让 UI 提示失败，
                // 避免留下两个文件
                runCatching { ctx.contentResolver.delete(newUri, null, null) }
                return null
            }
            return newFileName
        } catch (e: Exception) {
            runCatching { ctx.contentResolver.delete(newUri, null, null) }
            return null
        }
    }

    private suspend fun downloadTo(context: Context, id: String, url: String, task: DownloadTask) {
        val safeName = task.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) SolaraAndroid/1.0")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应体为空")
            val total = body.contentLength()
            var downloaded = 0L
            var lastEmit = 0L

            val sink = openSink(context, safeName) ?: throw IOException("无法创建下载文件")
            sink.use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 300) {
                            lastEmit = now
                            val p = if (total > 0) downloaded.toFloat() / total else 0f
                            updateTask { list ->
                                list.map { if (it.id == id) it.copy(progress = p) else it }
                            }
                            refreshDownloadNotification()
                        }
                    }
                    out.flush()
                }
            }

            // 下载完成：拉封面/歌词并嵌入文件（失败不影响下载结果）
            runCatching { embedTags(context, sink, safeName, task) }

            val savedPath = sink.savedPath
            updateTask { list ->
                list.map {
                    if (it.id == id) it.copy(
                        status = DownloadStatus.DONE,
                        progress = 1f,
                        filePath = savedPath
                    ) else it
                }
            }
            // 下载完成：记入本地歌曲列表（Store 负责去重与持久化）
            Store.addDownload(task.song)
            refreshDownloadNotification()
        }
    }

    /**
     * 嵌入封面/歌词到刚下载的文件（v1.3.9）。
     * - 先解析封面直链并下载图片字节、拉歌词文本（任一失败跳过对应项）
     * - MP3：mp3agic 写 ID3v2（APIC+USLT+标题歌手）；FLAC：PICTURE 嵌封面
     * - Android 10+：MediaStore URI → 读到临时文件 → 嵌入 → 写回 URI
     * - Android 9-：直接对本地文件做 src→dest 重写
     * - FLAC 歌词写伴生 .lrc（10+ MediaStore 无文本集合权限，仅 9- 直写）
     */
    private suspend fun embedTags(context: Context, sink: CountingSink, safeName: String, task: DownloadTask) {
        val song = task.song

        // 1) 封面字节 + 歌词文本（并行拉，失败为 null）
        val coverUrl = MusicApi.fetchPicUrl(song)
        val coverBytes: ByteArray? = coverUrl?.let { u ->
            runCatching {
                client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        val lyric: String? = runCatching { MusicApi.fetchLyric(song) }.getOrNull()
        if (coverBytes == null && lyric == null) return

        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = sink.pendingUri ?: return
            // 读到临时文件 → 嵌入 → 写回
            val tmpSrc = File.createTempFile("dmusic_src", ".tmp", ctx.cacheDir)
            val tmpDst = File.createTempFile("dmusic_dst", ".tmp", ctx.cacheDir)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmpSrc).use { input.copyTo(it) }
                } ?: return

                TagEmbedder.embed(tmpSrc, tmpDst, safeName, song, coverBytes, lyric)

                ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    tmpDst.inputStream().use { it.copyTo(out) }
                }
            } finally {
                runCatching { tmpSrc.delete() }
                runCatching { tmpDst.delete() }
            }
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
            val src = File(dir, safeName)
            val dst = File(dir, "dmusic_tmp_${System.currentTimeMillis()}")
            try {
                TagEmbedder.embed(src, dst, safeName, song, coverBytes, lyric)
                // 原子替换：嵌入版改名回原文件
                if (dst.exists() && src.delete()) {
                    if (!dst.renameTo(src)) {
                        // 改名失败：把嵌入版拷回去
                        dst.inputStream().use { i -> FileOutputStream(src).use { i.copyTo(it) } }
                        runCatching { dst.delete() }
                    }
                }
            } finally {
                runCatching { dst.delete() }
            }
        }
    }

    /** 打开下载输出流：10+ 走 MediaStore（公共 Music/D_Music），9- 走 app 专属目录。 */
    private fun openSink(context: Context, fileName: String): CountingSink? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, if (fileName.endsWith("flac")) "audio/flac" else "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/D_Music")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val out = context.contentResolver.openOutputStream(uri) ?: return null
            object : CountingSink(out) {
                override val savedPath: String
                    get() = "${Environment.DIRECTORY_MUSIC}/D_Music/$fileName"
            }.also {
                it.pendingUri = uri
                it.contextRef = context
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            object : CountingSink(file.outputStream()) {
                override val savedPath: String get() = file.absolutePath
            }
        }
    }

    /** 包装 OutputStream，close 时把 MediaStore 的 IS_PENDING 置为完成。 */
    private abstract class CountingSink(out: java.io.OutputStream) : java.io.OutputStream() {
        var pendingUri: android.net.Uri? = null
        var contextRef: Context? = null

        override fun write(b: Int) = unit { delegate.write(b) }
        override fun write(b: ByteArray) = unit { delegate.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = unit { delegate.write(b, off, len) }
        override fun flush() = unit { delegate.flush() }

        override fun close() {
            runCatching { delegate.close() }
            // MediaStore：下载完成，解除 pending 状态使其对其他应用可见
            pendingUri?.let { uri ->
                contextRef?.contentResolver?.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                        // v1.4.6：补设 IS_MUSIC——insert 时未设该字段，
                        // ROM 扫描器懒惰未跑时该标志一直是 0，
                        // 导致带 IS_MUSIC != 0 条件的查询（含其他音乐 App）
                        // 永远查不到 App 下载的文件
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    },
                    null, null
                )
            }
        }

        abstract val savedPath: String
        protected val delegate: java.io.OutputStream = out

        private inline fun unit(block: () -> Unit) {
            block()
        }
    }

    private fun fail(id: String, msg: String) {
        updateTask { list ->
            list.map {
                if (it.id == id) it.copy(status = DownloadStatus.FAILED, error = msg) else it
            }
        }
        refreshDownloadNotification()
    }

    private fun updateTask(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        tasks.value = transform(tasks.value)
    }
}

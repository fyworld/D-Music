package com.solara.music.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.solara.music.InstallApkActivity
import com.solara.music.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 版本更新管理（v1.4.19 建立，v1.4.21 增强）：
 * - 启动时静默请求 GitHub releases/latest，失败静默跳过（不打扰用户）
 * - v1.4.21：直连 GitHub 失败自动经 gh-proxy.com 镜像重试（国内直连时通时断）
 * - v1.4.21：回前台节流补查（进程被播放服务保活时，启动检查不会再触发）
 * - v1.4.21：关于页「检查更新」手动入口（[checkNow]，失败抛异常供 UI 提示）
 * - 有新版本 → [updateInfo] 置位；关于页版本号下显示「发现新版本」入口
 * - 下载 APK（带进度，直连失败同样走镜像重试）→ 完成后拉起系统安装器
 * - lite / full 自动匹配各自 APK 资产（按文件名含不含 "lite" 区分）
 * - v1.4.27：更新下载通知栏进度 + 完成后「点击安装」通知——切后台也能看进度、
 *   点通知即装，不再依赖弹窗在前台
 * - v1.4.31：修复「取消下载」无效——call.cancel() 硬中断阻塞 IO + 代数计数
 *   丢弃取消后旧协程的迟到进度/终态发布（此前点取消后进度通知会重新弹出）
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val LATEST_API =
        "https://api.github.com/repos/fyworld/D-Music/releases/latest"

    /** v1.4.21：GitHub 直连失败时的镜像前缀（API 查询与 APK 下载共用）。 */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /** 回前台补查节流间隔（30 分钟）。 */
    private const val RECHECK_INTERVAL_MS = 30 * 60 * 1000L

    /** 更新信息：null = 无新版或未检查。 */
    data class UpdateInfo(
        val versionName: String,      // 如 "1.4.21"
        val versionTag: String,       // 如 "v1.4.21"
        val notes: String,            // 更新说明（markdown 纯文本化后展示）
        val apkUrl: String,           // 与当前 flavor 匹配的 APK 下载直链
        val apkSize: Long,            // APK 字节数（进度分母；0 = 未知）
        val htmlUrl: String           // Release 页面（备用手动下载）
    )

    /** 下载状态。 */
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Progress(val progress: Float) : DownloadState()  // 0f..1f
        data class Done(val apkFile: File) : DownloadState()
        data class Failed(val reason: String) : DownloadState()
    }

    val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    /** v1.4.31：当前下载的 OkHttp Call——取消下载时硬中断（cancel() 使阻塞的 read 立即抛 IOException）。 */
    @Volatile private var currentCall: Call? = null

    /** v1.4.31：下载代数计数。每次发起下载 +1；取消后旧协程的迟到发布被丢弃，防止「点取消又被拉回下载」。 */
    private val downloadEpoch = AtomicInteger(0)

    /** 上次发起检查的时间戳（回前台补查节流用）。 */
    private var lastCheckAt = 0L

    /** 并发去重：冷启动时 LaunchedEffect 与 ON_RESUME 几乎同时触发。 */
    private val checkingNow = AtomicBoolean(false)

    // ---- v1.4.27：更新下载通知（后台进度 + 完成后点击安装） ----

    /** 更新下载通知 ID（与播放 100 / 歌曲下载 200 区分）。 */
    private const val UPDATE_NOTIFICATION_ID = 300

    /** 更新下载渠道 ID（独立于歌曲下载，避免用户关掉歌曲下载通知连带更新进度）。 */
    private const val UPDATE_CHANNEL_ID = "d_music_update"

    /** 通知刷新节流（300ms，与歌曲下载通知同频）。 */
    private var lastNotifyAt = 0L

    /** Application context（downloadApk 首次捕获，通知与安装用）。 */
    private var appContextRef: Context? = null

    /** 当前下载的版本号（进度/完成通知标题用）。 */
    private var downloadingVersion: String = ""

    /** 创建更新下载通知渠道：IMPORTANCE_DEFAULT（MIUI 不折叠）+ 静音（进度通知不出声）。 */
    private fun ensureUpdateChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(UPDATE_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        UPDATE_CHANNEL_ID,
                        "版本更新下载",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
        }
    }

    /** 是否有通知权限（Android 13+ 未授权静默跳过，不影响下载）。 */
    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 撤掉更新下载通知（取消下载 / 安装成功时）。 */
    fun cancelUpdateNotification() {
        val context = appContextRef ?: return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(UPDATE_NOTIFICATION_ID)
        }
    }

    /**
     * 刷新更新下载进度通知（v1.4.27）：
     * - Progress：标题「正在下载新版本 vX.Y.Z」+ 进度条（300ms 节流）
     * - Done：标题「新版本下载完成」+ 文本「点按安装 vY」；点击直接拉系统安装器，
     *   不需要 App 在前台——后台弹界面被系统禁止，通知点击是唯一合规通道
     */
    private fun refreshUpdateNotification(state: DownloadState, versionName: String) {
        val context = appContextRef ?: return
        runCatching {
            if (!canNotify(context)) return
            ensureUpdateChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            when (state) {
                is DownloadState.Progress -> {
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyAt < 300) return
                    lastNotifyAt = now
                    val builder = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_download)
                        .setContentTitle("正在下载新版本 v$versionName")
                        .setContentText("已下载 ${(state.progress * 100).toInt()}%")
                        .setProgress(100, (state.progress * 100).toInt(), false)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build()
                    nm.notify(UPDATE_NOTIFICATION_ID, builder)
                }
                is DownloadState.Done -> {
                    lastNotifyAt = 0L
                    val installIntent = PendingIntent.getActivity(
                        context, 1,
                        Intent(context, InstallApkActivity::class.java).apply {
                            putExtra(InstallApkActivity.EXTRA_APK_PATH, state.apkFile.absolutePath)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val builder = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_download)
                        .setContentTitle("新版本 v$versionName 下载完成")
                        .setContentText("点按安装")
                        .setOngoing(false)
                        .setAutoCancel(true)   // 点击后消失
                        .setOnlyAlertOnce(true)
                        .setContentIntent(installIntent)
                        .build()
                    nm.notify(UPDATE_NOTIFICATION_ID, builder)
                }
                is DownloadState.Failed -> {
                    // 失败：撤掉进度通知，避免用户以为还在下载
                    nm.cancel(UPDATE_NOTIFICATION_ID)
                }
                is DownloadState.Idle -> {
                    // 取消下载：撤通知
                    nm.cancel(UPDATE_NOTIFICATION_ID)
                }
            }
        }
    }

    /** 当前 App 是否 lite 纯净版。 */
    private val isLite: Boolean
        get() = com.solara.music.BuildConfig.VERSION_NAME.contains("lite")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 启动时静默检查更新。任何失败（网络 / 解析 / 无匹配资产）都静默跳过，
     * 不弹任何提示——更新检查永远不能打扰用户。
     */
    fun checkSilently() {
        if (!checkingNow.compareAndSet(false, true)) return
        scope.launch {
            try {
                lastCheckAt = System.currentTimeMillis()
                val info = runCatching { fetchLatest() }.getOrNull()
                if (info != null && isNewer(info.versionName, currentVersion())) {
                    Log.i(TAG, "发现新版本 ${info.versionName}（当前 ${currentVersion()}）")
                    updateInfo.value = info
                }
            } finally {
                checkingNow.set(false)
            }
        }
    }

    /**
     * 回前台补查（v1.4.21）：进程被播放服务保活时，从后台切回只是 resume，
     * 界面不会重新组合、启动检查不会再跑——在 ON_RESUME 时节流补查。
     */
    fun checkIfStale() {
        if (System.currentTimeMillis() - lastCheckAt > RECHECK_INTERVAL_MS) {
            checkSilently()
        }
    }

    /**
     * 手动检查（关于页「检查更新」用）：
     * - 有新版：置 [updateInfo]（SolaraApp 全局弹窗自动弹出）并返回信息
     * - 无新版：返回 null（调用方提示"已是最新"）
     * - 失败：抛异常（调用方提示原因）——与静默检查不同，手动检查必须给反馈
     */
    suspend fun checkNow(): UpdateInfo? {
        val info = fetchLatest()
        lastCheckAt = System.currentTimeMillis()
        return if (isNewer(info.versionName, currentVersion())) {
            updateInfo.value = info
            info
        } else null
    }

    /** 当前版本（去掉 -lite 后缀的主版本，如 "1.4.23"）。 */
    fun currentVersion(): String =
        com.solara.music.BuildConfig.VERSION_NAME.substringBefore("-")

    /** 记住用户「暂不更新」的版本（v1.4.23）：重启不再自动弹窗，直到更新的版本出现。 */
    fun skipVersion(version: String) = Store.saveSkippedVersion(version)

    /** 被跳过的版本号；null = 无跳过记录。 */
    fun skippedVersion(): String? = Store.skippedVersion()

    /**
     * 语义化版本比较：latest > current 才提示。
     * "1.4.19" > "1.4.9"（按数字比，不是字符串比）。
     */
    fun isNewer(latest: String, current: String): Boolean = runCatching {
        val l = latest.trim().removePrefix("v").split(".").map { it.toInt() }
        val c = current.trim().removePrefix("v").split(".").map { it.toInt() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrNull(i) ?: 0
            val b = c.getOrNull(i) ?: 0
            if (a != b) return@runCatching a > b
        }
        false
    }.getOrDefault(false)

    /** 拉取 latest：直连失败自动经镜像重试（v1.4.21）。 */
    private suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            fetchLatestFrom(LATEST_API)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "直连 GitHub API 失败（${e.message}），改走镜像重试")
            fetchLatestFrom(MIRROR_PREFIX + LATEST_API)
        }
    }

    /** 从指定 API 地址（直连或镜像）拉取并解析 latest Release（阻塞 IO，须在 IO 线程调用）。 */
    private fun fetchLatestFrom(apiUrl: String): UpdateInfo {
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "D-Music-Updater")   // GitHub API 强制要求 UA
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IllegalStateException("empty body")
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")   // "v1.4.21"
            if (tagName.isBlank()) throw IllegalStateException("no tag_name")
            val versionName = tagName.removePrefix("v")

            // 跳过 draft / prerelease
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                throw IllegalStateException("latest is draft/prerelease")
            }

            // 找与当前 flavor 匹配的 APK 资产：lite 版找文件名含 "lite"，full 版找不含
            val assets = json.optJSONArray("assets") ?: throw IllegalStateException("no assets")
            var apkObj: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "").lowercase()
                if (!name.endsWith(".apk")) continue
                val isLiteAsset = name.contains("lite")
                if (isLiteAsset == isLite) { apkObj = a; break }
            }
            val apk = apkObj ?: throw IllegalStateException("no matching apk for flavor")

            return UpdateInfo(
                versionName = versionName,
                versionTag = tagName,
                notes = json.optString("body", "").trim(),
                apkUrl = apk.optString("browser_download_url", ""),
                apkSize = apk.optLong("size", 0L),
                htmlUrl = json.optString("html_url", "")
            )
        }
    }

    /**
     * 下载更新 APK 到应用外部私有目录（getExternalFilesDir，卸载自动清理，
     * 无需任何存储权限）。带进度回调，完成后置 Done 状态（由 UI 拉起安装）。
     * v1.4.21：直连下载失败自动经镜像重试。
     */
    fun downloadApk(context: Context, info: UpdateInfo) {
        downloadJob?.cancel()
        currentCall?.cancel()   // v1.4.31：硬中断旧下载（若有）
        val epoch = downloadEpoch.incrementAndGet()   // v1.4.31：本次下载的代数
        val appContext = context.applicationContext
        appContextRef = appContext   // v1.4.27：通知与安装用
        downloadingVersion = info.versionName
        lastNotifyAt = 0L   // v1.4.31：重置通知节流，重试/重新下载时首条进度通知立即显示
        downloadState.value = DownloadState.Progress(0f)
        refreshUpdateNotification(DownloadState.Progress(0f), info.versionName)   // 首条立即显示
        downloadJob = scope.launch {
            val dir = appContext.getExternalFilesDir(null) ?: File(appContext.filesDir, "updates")
            val fileName = "D.Music-${info.versionTag}.apk"
            val target = File(dir, fileName)
            // v1.4.31：捕获本协程 Job 传给 downloadTo（downloadJob 字段可能已被 resetDownload 置 null）
            val selfJob = coroutineContext[Job]!!
            try {
                if (!dir.exists()) dir.mkdirs()
                if (target.exists()) target.delete()

                try {
                    downloadTo(target, info.apkUrl, info.apkSize, epoch, selfJob)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // v1.4.31：仅「协程仍在运行 + 非 call.cancel() 中断」才镜像重试——
                    // 取消时 OkHttp 可能抛 Socket closed 等各种 IOException，一律不重试
                    if (!isCallCancelled(e) && selfJob.isActive) {
                        Log.w(TAG, "直连下载失败（${e.message}），改走镜像重试")
                        if (target.exists()) target.delete()
                        downloadTo(target, MIRROR_PREFIX + info.apkUrl, info.apkSize, epoch, selfJob)
                    } else throw e
                }
                // v1.4.31：只有最新代数才允许发布终态（旧协程迟到完成不覆盖新状态）
                if (downloadEpoch.get() == epoch) {
                    downloadState.value = DownloadState.Done(target)
                    refreshUpdateNotification(DownloadState.Done(target), info.versionName)  // v1.4.27
                    Log.i(TAG, "APK 下载完成: $target (${target.length()} bytes)")
                }
            } catch (e: Exception) {
                // v1.4.31：取消的判定——CancellationException / call.cancel() 的 IOException /
                // 代数已过期（resetDownload 后任何迟到的异常都视为取消，不误报"下载失败"）
                if (e is CancellationException || isCallCancelled(e) || downloadEpoch.get() != epoch) {
                    // 用户取消：只清理本次的 APK 半成品文件
                    runCatching { if (target.exists()) target.delete() }
                    if (downloadEpoch.get() == epoch) {
                        Log.i(TAG, "APK 下载已取消")
                    }
                    return@launch
                }
                Log.w(TAG, "APK 下载失败: ${e.message}")
                if (downloadEpoch.get() == epoch) {
                    downloadState.value = DownloadState.Failed(e.message ?: "下载失败")
                    refreshUpdateNotification(
                        DownloadState.Failed(e.message ?: "下载失败"), info.versionName
                    )   // v1.4.27：失败撤进度通知
                }
            } finally {
                // v1.4.31：本次协程退出时清 Call 引用（防泄漏；最新代数才清，避免误清新下载的）
                if (downloadEpoch.get() == epoch) currentCall = null
            }
        }
    }

    /** v1.4.31：判断异常是否由 call.cancel() 硬中断引起（OkHttp 抛「Canceled」IOException）。 */
    private fun isCallCancelled(e: Throwable): Boolean =
        e is IOException && e.message?.contains("cancel", ignoreCase = true) == true

    /**
     * 从指定 URL（直连或镜像）下载 APK 到 target，进度写 [downloadState]（阻塞 IO）。
     * v1.4.31：记录 Call 供硬取消；循环内检查协程取消（双保险）+ 代数校验（迟到发布丢弃）。
     */
    private fun downloadTo(target: File, url: String, expectedSize: Long, epoch: Int, selfJob: Job) {
        val req = Request.Builder().url(url).build()
        val call = client.newCall(req)
        currentCall = call   // v1.4.31：暴露给 resetDownload 硬中断
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = if (expectedSize > 0) expectedSize else body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        // v1.4.31：协程取消 → 立即退出循环（call.cancel() 通常已使 read 抛异常，此处兜底）
                        if (!selfJob.isActive) break
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // 每 256KB 刷新一次进度，避免重组风暴
                        if (total > 0) {
                            val p = downloaded.toFloat() / total
                            if (downloaded - lastPublish > 256 * 1024 || p >= 1f) {
                                // v1.4.31：仅最新代数才发布（取消后旧循环不再覆盖 Idle）
                                if (downloadEpoch.get() == epoch) {
                                    downloadState.value = DownloadState.Progress(p.coerceIn(0f, 1f))
                                    refreshUpdateNotification(
                                        DownloadState.Progress(p.coerceIn(0f, 1f)), downloadingVersion
                                    )   // v1.4.27：后台进度通知
                                }
                                lastPublish = downloaded
                            }
                        }
                    }
                    output.flush()
                }
            }
            // v1.4.31：取消导致的提前退出不算「下载不完整」失败
            if (selfJob.isActive && total > 0 && downloaded < total) {
                throw IllegalStateException("下载不完整")
            }
        }
    }

    /**
     * 拉起系统安装器安装 APK。
     * - Android 7+：FileProvider content URI + FLAG_GRANT_READ_URI_PERMISSION
     * - Android 8+：需要 REQUEST_INSTALL_PACKAGES 权限，未授权时引导去设置开启
     * 返回 false = 未获安装权限（调用方引导用户去系统设置）。
     */
    fun installApk(context: Context, apk: File): Boolean {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.fileprovider", apk
                )
            } else {
                Uri.fromFile(apk)
            }
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Android 8+ 「安装未知应用」权限的设置页 Intent（未授权时引导用）。 */
    fun installPermissionSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        }.getOrNull()
    }

    /** 取消下载并重置状态（「取消下载」按钮 / 关闭弹窗时）。 */
    fun resetDownload() {
        Log.i(TAG, "APK 下载已取消（用户点击）")
        downloadEpoch.incrementAndGet()   // v1.4.31：旧协程的迟到发布全部失效
        downloadJob?.cancel()
        currentCall?.cancel()   // v1.4.31：硬中断——阻塞中的 execute/read 立即抛 IOException
        currentCall = null
        downloadJob = null
        downloadState.value = DownloadState.Idle
        cancelUpdateNotification()   // v1.4.27：取消时撤掉通知栏进度
    }
}

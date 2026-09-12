package com.solara.music.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 版本更新管理（v1.4.19 建立，v1.4.21 增强）：
 * - 启动时静默请求 GitHub releases/latest，失败静默跳过（不打扰用户）
 * - v1.4.21：直连 GitHub 失败自动经 gh-proxy.com 镜像重试（国内直连时通时断）
 * - v1.4.21：回前台节流补查（进程被播放服务保活时，启动检查不会再触发）
 * - v1.4.21：关于页「检查更新」手动入口（[checkNow]，失败抛异常供 UI 提示）
 * - 有新版本 → [updateInfo] 置位；关于页版本号下显示「发现新版本」入口
 * - 下载 APK（带进度，直连失败同样走镜像重试）→ 完成后拉起系统安装器
 * - lite / full 自动匹配各自 APK 资产（按文件名含不含 "lite" 区分）
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

    /** 上次发起检查的时间戳（回前台补查节流用）。 */
    private var lastCheckAt = 0L

    /** 并发去重：冷启动时 LaunchedEffect 与 ON_RESUME 几乎同时触发。 */
    private val checkingNow = AtomicBoolean(false)

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

    /** 当前版本（去掉 -lite 后缀的主版本，如 "1.4.21"）。 */
    fun currentVersion(): String =
        com.solara.music.BuildConfig.VERSION_NAME.substringBefore("-")

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
        val appContext = context.applicationContext
        downloadState.value = DownloadState.Progress(0f)
        downloadJob = scope.launch {
            try {
                val dir = appContext.getExternalFilesDir(null) ?: File(appContext.filesDir, "updates")
                if (!dir.exists()) dir.mkdirs()
                val fileName = "D.Music-${info.versionTag}.apk"
                val target = File(dir, fileName)
                if (target.exists()) target.delete()

                try {
                    downloadTo(target, info.apkUrl, info.apkSize)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 直连失败 → 镜像重试（v1.4.21）
                    Log.w(TAG, "直连下载失败（${e.message}），改走镜像重试")
                    if (target.exists()) target.delete()
                    downloadTo(target, MIRROR_PREFIX + info.apkUrl, info.apkSize)
                }
                downloadState.value = DownloadState.Done(target)
                Log.i(TAG, "APK 下载完成: $target (${target.length()} bytes)")
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // 用户取消：只清理 APK 半成品文件
                    runCatching {
                        appContext.getExternalFilesDir(null)?.let { dir ->
                            dir.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
                        }
                    }
                    Log.i(TAG, "APK 下载已取消")
                    return@launch
                }
                Log.w(TAG, "APK 下载失败: ${e.message}")
                downloadState.value = DownloadState.Failed(e.message ?: "下载失败")
            }
        }
    }

    /** 从指定 URL（直连或镜像）下载 APK 到 target，进度写 [downloadState]（阻塞 IO）。 */
    private fun downloadTo(target: File, url: String, expectedSize: Long) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = if (expectedSize > 0) expectedSize else body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // 每 256KB 刷新一次进度，避免重组风暴
                        if (total > 0) {
                            val p = downloaded.toFloat() / total
                            if (downloaded - lastPublish > 256 * 1024 || p >= 1f) {
                                downloadState.value = DownloadState.Progress(p.coerceIn(0f, 1f))
                                lastPublish = downloaded
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (total > 0 && downloaded < total) throw IllegalStateException("下载不完整")
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
        downloadJob?.cancel()
        downloadJob = null
        downloadState.value = DownloadState.Idle
    }
}

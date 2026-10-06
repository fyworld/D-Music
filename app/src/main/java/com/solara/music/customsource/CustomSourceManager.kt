package com.solara.music.customsource

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.5.1 r26：自定义音源管理器。
 *
 * 职责：
 * - 脚本导入：本地文本 / 在线 URL 下载 → parseMeta 校验 → 持久化
 * - 脚本列表管理：启用（同时只有一个生效）/删除
 * - 沙箱生命周期：App 启动时装载启用的脚本；切换脚本时重载
 *
 * 存储（SharedPreferences，与 Store 其他数据同方案）：
 * - KEY_SCRIPTS：脚本元信息列表 JSON
 * - KEY_SCRIPT_<id>：脚本全文
 * - KEY_ACTIVE：当前启用的脚本 id（空=未启用）
 */
object CustomSourceManager {

    private const val PREFS = "custom_source_store"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_TRIGGER = "trigger_mode"
    private const val SCRIPT_PREFIX = "script_"

    /** 触发策略：GD音乐台 API 失败时兜底（默认）/ 自定义源优先。 */
    const val TRIGGER_FALLBACK = "fallback"
    const val TRIGGER_PREFERRED = "preferred"

    data class ScriptEntry(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val homepage: String,
        val importTime: Long
    )

    /** 脚本列表（导入/删除后更新）。 */
    private val _scripts = MutableStateFlow<List<ScriptEntry>>(emptyList())
    val scripts: StateFlow<List<ScriptEntry>> = _scripts

    /** 当前启用的脚本 id（空=未启用）。 */
    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId

    /** 触发策略（fallback=兜底 preferred=优先）。 */
    private val _triggerMode = MutableStateFlow(TRIGGER_FALLBACK)
    val triggerMode: StateFlow<String> = _triggerMode

    /** 沙箱就绪状态（脚本 init 成功）。 */
    val sandboxReady: Boolean get() = CustomSourceSandbox.ready

    /** 沙箱能力（source → actions/qualitys）。 */
    val sandboxSources: Map<String, CustomSourceSandbox.SourceInfo>
        get() = CustomSourceSandbox.sources

    private lateinit var appContext: Context
    private val loadMutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ---------------- 初始化 ----------------

    /** App 启动时调用：读持久化数据 + 装载启用的脚本。 */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _scripts.value = readScripts(prefs)
        _activeId.value = prefs.getString(KEY_ACTIVE, "") ?: ""
        _triggerMode.value = prefs.getString(KEY_TRIGGER, TRIGGER_FALLBACK) ?: TRIGGER_FALLBACK
        // 启动装载（异步，失败不打断 App 启动）
        CoroutineScope(Dispatchers.IO).launch {
            loadActive()
        }
    }

    private fun readScripts(prefs: android.content.SharedPreferences): List<ScriptEntry> =
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_SCRIPTS, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScriptEntry(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    description = o.optString("description"),
                    version = o.optString("version"),
                    author = o.optString("author"),
                    homepage = o.optString("homepage"),
                    importTime = o.optLong("importTime")
                )
            }
        }.getOrDefault(emptyList())

    private fun persistScripts() {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        _scripts.value.forEach { s ->
            arr.put(JSONObject()
                .put("id", s.id).put("name", s.name)
                .put("description", s.description).put("version", s.version)
                .put("author", s.author).put("homepage", s.homepage)
                .put("importTime", s.importTime))
        }
        prefs.edit().putString(KEY_SCRIPTS, arr.toString()).apply()
    }

    // ---------------- 导入 ----------------

    /** 最近一次导入自动启用的失败原因（null=成功/未触发）。 */
    @Volatile var lastImportEnableError: String? = null
        private set

    /**
     * 导入脚本（本地文本或已下载的脚本全文）。
     * 校验头部元信息 → 存脚本全文 + 元信息 → 自动设为启用。
     * @return 成功的 ScriptEntry；脚本非法返回 null
     */
    suspend fun importScript(script: String): ScriptEntry? = withContext(Dispatchers.IO) {
        if (script.isBlank()) return@withContext null
        val meta = CustomSourceSandbox.parseMeta(script) ?: return@withContext null
        val entry = ScriptEntry(
            id = meta.id, name = meta.name, description = meta.description,
            version = meta.version, author = meta.author, homepage = meta.homepage,
            importTime = System.currentTimeMillis()
        )
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(SCRIPT_PREFIX + meta.id, script).apply()
        _scripts.value = _scripts.value + entry
        persistScripts()
        // 首个导入的脚本自动启用（失败不回滚导入，错误供 UI 展示）
        lastImportEnableError = null
        if (_activeId.value.isBlank()) {
            lastImportEnableError = setActive(meta.id)
        }
        entry
    }

    /** 从在线 URL 下载脚本并导入。 */
    suspend fun importFromUrl(url: String): ScriptEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpClient.newCall(Request.Builder().url(url).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string() ?: return@withContext null
                }
            importScript(body)
        }.getOrNull()
    }

    // ---------------- 启用/删除 ----------------

    /** 设为启用（装载到沙箱）。
     * @return null=成功；否则为具体错误信息（UI 直接展示） */
    suspend fun setActive(id: String): String? = loadMutex.withLock {
        val entry = _scripts.value.find { it.id == id } ?: return "脚本不存在"
        val script = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SCRIPT_PREFIX + id, "") ?: ""
        if (script.isBlank()) return "脚本数据丢失"
        val err = CustomSourceSandbox.loadScript(
            appContext,
            ScriptMeta(
                id = entry.id, name = entry.name, description = entry.description,
                version = entry.version, author = entry.author, homepage = entry.homepage
            ),
            script
        )
        if (err != null) return err
        _activeId.value = id
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, id).apply()
        null
    }

    /** 停用（卸载沙箱）。 */
    fun deactivate() {
        _activeId.value = ""
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, "").apply()
        CustomSourceSandbox.destroy()
    }

    /** 删除脚本（若是启用的，先停用）。 */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (_activeId.value == id) deactivate()
        _scripts.value = _scripts.value.filterNot { it.id == id }
        persistScripts()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(SCRIPT_PREFIX + id).apply()
    }

    // ---------------- 触发策略 ----------------

    fun setTriggerMode(mode: String) {
        val m = if (mode == TRIGGER_PREFERRED) TRIGGER_PREFERRED else TRIGGER_FALLBACK
        _triggerMode.value = m
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRIGGER, m).apply()
    }

    /** 当前是否「自定义源优先」。 */
    val isPreferred: Boolean get() = _triggerMode.value == TRIGGER_PREFERRED

    // ---------------- 取歌 ----------------

    /**
     * 取歌直链（透传给沙箱）。
     * @param source D Music 源码（netease/tencent/...）——内部映射到 lx 源码
     * v1.5.1 r32：签名改为接收完整 Song——musicInfo 按 lx-music 完整契约
     * 构造（buildMusicInfo），平台特有字段（hash/strMediaMid/copyrightId）
     * 从 Song 透传。
     */
    suspend fun getMusicUrl(source: String, quality: String, song: com.solara.music.data.Song): String? {
        if (!sandboxReady) return null
        val lxSource = mapToLxSource(source) ?: return null
        val lxQuality = mapToLxQuality(quality) ?: return null
        val musicInfo = buildMusicInfo(song, lxSource)
        return CustomSourceSandbox.getMusicUrl(lxSource, lxQuality, musicInfo)
    }

    /**
     * v1.5.1 r35：脚本歌词——平台直连搜的歌（kw/kg/tx/wy/mg 源码）
     * GD API 不支持这些源码的 lyric 查询，走脚本（与取歌同链路）。
     * @return LRC 原文；脚本不可用/失败返回 null（调用方回落 GD API）
     */
    suspend fun getLyric(song: com.solara.music.data.Song): String? {
        if (!awaitSandboxReady()) return null
        val lxSource = mapToLxSource(song.source) ?: return null
        val musicInfo = buildMusicInfo(song, lxSource)
        val lyric = CustomSourceSandbox.getLyric(lxSource, musicInfo)
        if (lyric != null) return lyric
        // v1.5.1 r35：脚本歌词依赖 extraCache（取歌时后端返回的 lrc）——
        // URL 缓存命中/冷启动恢复场景没走取歌，缓存空。主动触发一次
        // 取歌（填 extraCache）再查一次。去重：同一首歌只补一次。
        if (warmExtraCache(song, lxSource)) {
            return CustomSourceSandbox.getLyric(lxSource, musicInfo)
        }
        return null
    }

    /**
     * v1.5.1 r35：脚本封面——平台直连搜的歌（kw/kg/tx/wy/mg 源码）
     * GD API 不支持这些源码的 pic 查询，走脚本（与取歌同链路）。
     * @return 封面 URL；脚本不可用/失败返回 null（调用方回落 GD API）
     */
    suspend fun getPicUrl(song: com.solara.music.data.Song): String? {
        if (!awaitSandboxReady()) return null
        val lxSource = mapToLxSource(song.source) ?: return null
        val musicInfo = buildMusicInfo(song, lxSource)
        val url = CustomSourceSandbox.getPicUrl(lxSource, musicInfo)
        if (url != null) return url
        // v1.5.1 r35：与 getLyric 同理——extraCache 空时主动取歌填充
        if (warmExtraCache(song, lxSource)) {
            return CustomSourceSandbox.getPicUrl(lxSource, musicInfo)
        }
        return null
    }

    /**
     * v1.5.1 r35：主动触发一次取歌填 extraCache（脚本 lyric/pic 依赖它）。
     * URL 缓存命中/冷启动恢复场景播放不走脚本取歌 → extraCache 空 →
     * lyric/pic 返回 null。取歌成功后 extraCache 已填，再查即命中。
     * 去重：同一首歌（source:id）只补一次（失败也不重试——避免每次
     * 歌词封面请求都打一次取歌接口）。
     * @return true=取歌成功（extraCache 已填，值得再查一次）
     */
    private suspend fun warmExtraCache(song: com.solara.music.data.Song, lxSource: String): Boolean {
        val key = "${song.source}:${song.id}"
        if (key in warmedKeys) return false
        warmedKeys.add(key)
        android.util.Log.d("CustomSourceJS", "warmExtraCache: 取歌填缓存 $key")
        val musicInfo = buildMusicInfo(song, lxSource)
        // 取歌成功即代表 extraCache 已填（脚本 fetchMusicUrl 末尾 set）
        return CustomSourceSandbox.getMusicUrl(lxSource, "320k", musicInfo) != null
    }

    /** v1.5.1 r35：已补取过歌的 key（防重复取歌）。 */
    private val warmedKeys = mutableSetOf<String>()

    /**
     * v1.5.1 r35：等待沙箱就绪（最多 8 秒）。
     * 冷启动时序竞争：PlayerViewModel 歌词请求在 App 启动后立即触发
     * （恢复队列的 currentSong collect），此时脚本可能仍在装载
     * （Preload+init 需 1-3 秒）——立即返回 null 会永久错过歌词
     * （currentSong 不变 collect 不重触发）。等待就绪再查。
     */
    private suspend fun awaitSandboxReady(): Boolean {
        if (sandboxReady) return true
        // 未启用脚本：不等待
        if (_activeId.value.isBlank()) return false
        val start = System.currentTimeMillis()
        while (!sandboxReady && System.currentTimeMillis() - start < 8_000L) {
            kotlinx.coroutines.delay(200)
        }
        return sandboxReady
    }

    /**
     * v1.5.1 r28：测试取歌（管理页「测试」按钮）——直接调当前启用的脚本
     * 解析指定歌曲，返回结果详情（成功=直链前 80 字符，失败=原因）。
     * 与播放链路完全同路径（getMusicUrl），测过即代表播放可用。
     * v1.5.1 r32：musicInfo 结构对齐 lx-music 完整契约（嵌套 meta +
     * 顶层字段 + 平铺旧字段）——与 resolveFromCustomSource 保持一致；
     * 签名改为接收完整 Song（平台特有字段 hash/strMediaMid/copyrightId
     * 从当前播放歌曲透传，GD API 搜的歌这些字段为空则用 songId 兜底）。
     */
    suspend fun testResolve(song: com.solara.music.data.Song, quality: String = "320"): String {
        if (!sandboxReady) return "沙箱未就绪（脚本未启用或初始化失败）"
        val lxSource = mapToLxSource(song.source)
            ?: return "不支持的源：${song.source}（脚本只支持 kw/kg/tx/wy/mg）"
        val lxQuality = mapToLxQuality(quality) ?: "320k"
        val supported = sandboxSources[lxSource]
        if (supported == null) {
            return "脚本不支持「${sourceName(lxSource)}」" +
                "（支持：${sandboxSources.keys.joinToString("、") { sourceName(it) }}）"
        }
        if ("musicUrl" !in supported.actions) return "脚本声明了该源但不支持 musicUrl 动作"
        if (lxQuality !in supported.qualitys) {
            return "脚本不支持 ${lxQuality} 音质（支持：${supported.qualitys.joinToString("、")}）"
        }
        val musicInfo = buildMusicInfo(song, lxSource)
        val url = CustomSourceSandbox.getMusicUrl(lxSource, lxQuality, musicInfo)
            ?: return "解析失败：${CustomSourceSandbox.lastResolveError ?: "未知原因"}"
        return "解析成功：${url.take(80)}${if (url.length > 80) "…" else ""}"
    }

    /**
     * v1.5.1 r32：Song → lx-music MusicInfo 完整契约 JSON。
     * 顶层 id/name/singer/source/interval + 嵌套 meta{songId,albumName,
     * hash,strMediaMid,copyrightId,...} + 平铺旧字段（兼容旧结构脚本）。
     */
    private fun buildMusicInfo(song: com.solara.music.data.Song, lxSource: String): JSONObject {
        val meta = JSONObject()
            .put("songId", song.id)
            .put("albumName", song.album)
            .put("picUrl", "")
        when (lxSource) {
            "kg" -> meta.put("hash", song.hash.ifBlank { song.id })
            "tx" -> {
                meta.put("strMediaMid", song.strMediaMid)
                meta.put("albumMid", song.albumMid)
                meta.put("id", song.id)
            }
            "mg" -> meta.put("copyrightId", song.copyrightId.ifBlank { song.id })
        }
        return JSONObject()
            .put("id", "${lxSource}_${song.id}")
            .put("name", song.name)
            .put("singer", song.artist)
            .put("source", lxSource)
            .put("interval", song.interval)
            .put("meta", meta)
            // 平铺旧字段（兼容按 lx-music 旧结构/桌面版写的脚本）
            .put("songId", song.id)
            .put("songmid", song.id)
            .put("hash", song.hash.ifBlank { song.id })
            .put("albumName", song.album)
            .put("albumId", "")
            .put("strMediaMid", song.strMediaMid)
            .put("copyrightId", song.copyrightId.ifBlank { song.id })
            .put("img", "")
    }

    /** lx 源码 → 显示名（测试结果展示用）。r70：改为通用代号（源 1-5）。 */
    private fun sourceName(lx: String): String = when (lx) {
        "kw" -> "源 1"; "kg" -> "源 2"; "tx" -> "源 3"; "wy" -> "源 4"; "mg" -> "源 5"
        else -> lx
    }

    /** D Music 源码 → lx 源码映射。 */
    private fun mapToLxSource(source: String): String? = when (source) {
        "netease" -> "wy"
        "tencent" -> "tx"
        "kuwo" -> "kw"
        "kugou" -> "kg"
        "migu" -> "mg"
        // v1.5.1 r29：平台直连搜索的歌 source 已是 lx 源码（kw/kg/tx/wy/mg），直接透传
        "kw", "kg", "tx", "wy", "mg" -> source
        else -> null
    }

    /** D Music 音质 → lx 音质映射。 */
    private fun mapToLxQuality(quality: String): String? = when (quality) {
        "128" -> "128k"
        "320" -> "320k"
        "flac" -> "flac"
        "flac24bit" -> "flac24bit"
        else -> "320k"
    }

    /** 启动时装载当前启用的脚本。 */
    private suspend fun loadActive() {
        val id = _activeId.value
        if (id.isBlank()) return
        loadMutex.withLock {
            val entry = _scripts.value.find { it.id == id } ?: return
            val script = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SCRIPT_PREFIX + id, "") ?: ""
            if (script.isBlank()) return
            CustomSourceSandbox.loadScript(
                appContext,
                ScriptMeta(
                    id = entry.id, name = entry.name, description = entry.description,
                    version = entry.version, author = entry.author, homepage = entry.homepage
                ),
                script
            )
        }
    }
}

package com.solara.music.customsource

import android.content.Context
import android.util.Base64
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * v1.5.1 r26：自定义音源沙箱——QuickJS 执行 lx-music 生态音源脚本。
 *
 * 架构（移植自 lx-music-mobile 的 userApi 模块，Apache-2.0）：
 * - preload 脚本（assets/custom_source_preload.js）构建 globalThis.lx 环境：
 *   lx.request（HTTP 转发到宿主）/ lx.send('inited') / lx.on('request') /
 *   lx.utils.crypto（aes/rsa/md5）/ lx.utils.buffer / setTimeout
 * - 音源脚本在沙箱内运行：init 声明能力（sources×qualitys×actions），
 *   播放时宿主发 request{source, action:'musicUrl', info} → 脚本回调 URL
 * - 宿主侧：QuickJSContext.evaluate 执行脚本；__lx_native__ 消息入口
 *   处理 init/request/response/cancelRequest；HTTP 用 OkHttp 代发
 *
 * 线程模型：QuickJS 非线程安全——所有 JS 调用收敛到单一 HandlerThread
 * （JS_THREAD）。Kotlin 侧用挂起函数桥接（消息 + 协程回调）。
 */
object CustomSourceSandbox {

    private const val JS_THREAD_NAME = "CustomSourceJS"

    /** JS 引擎线程（QuickJS 非线程安全，全部调用收敛于此线程）。 */
    private lateinit var jsThread: android.os.HandlerThread
    private lateinit var jsHandler: android.os.Handler

    private var context: QuickJSContext? = null

    /** 每次装载生成的随机 key——脚本调 native 时校验（防脚本伪造）。 */
    private var authKey: String = ""

    /** init 完成后的回调（脚本声明能力）。 */
    private var onInited: ((Boolean, String?, String?) -> Unit)? = null

    /** init 失败标志（脚本主动 send('inited', {status:false}) 或 init 消息解析失败）。 */
    @Volatile private var initError: String? = null

    /**
     * v1.5.1 r30：最近一次取歌失败的具体原因（null=成功/未发起）。
     * getMusicUrl 失败路径原本全部静默返回 null，用户只看到笼统的
     * "解析失败"——现在把脚本报错/超时/空直链等各失败点记录下来，
     * 供 testResolve 和播放链路展示。
     */
    @Volatile var lastResolveError: String? = null
        private set

    /** 脚本发起的 HTTP 请求表：requestKey → 完成回调。 */
    private val httpCallbacks = ConcurrentHashMap<String, (JSONObject?) -> Unit>()

    /** 宿主发起的取歌请求表：requestKey → (resolve, reject)（挂起函数桥）。 */
    private val pendingRequests = ConcurrentHashMap<String, Pair<(Any?) -> Unit, (Throwable) -> Unit>>()

    /** 脚本注册的 request 处理器是否就绪（init 成功后置 true）。 */
    @Volatile var ready = false
        private set

    /** init 声明的能力（source → actions/qualitys）。 */
    @Volatile var sources: Map<String, SourceInfo> = emptyMap()
        private set

    /** 当前脚本元信息（导入时解析的头部注释）。 */
    @Volatile var scriptName: String = ""
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class SourceInfo(
        val actions: List<String>,
        val qualitys: List<String>
    )

    // ---------------- 生命周期 ----------------

    @Synchronized
    fun init(appContext: Context) {
        if (::jsThread.isInitialized) return
        jsThread = android.os.HandlerThread(JS_THREAD_NAME)
        jsThread.start()
        jsHandler = android.os.Handler(jsThread.looper)
        QuickJSLoader.init()
    }

    /**
     * 装载音源脚本（在 JS 线程执行 + 等待异步 init 完成）。
     * @param meta 导入时解析的头部元信息（name/version/author/homepage）
     * @param script 脚本全文
     * @return null=成功；否则错误信息
     */
    suspend fun loadScript(
        appContext: Context,
        meta: ScriptMeta,
        script: String
    ): String? = withContext(Dispatchers.IO) {
        init(appContext)
        // 阶段一：JS 线程同步装载（evaluate 脚本）
        val syncErr = suspendCancellableCoroutine { cont ->
            jsHandler.post {
                val err = runCatching { loadScriptSync(appContext, meta, script) }
                    .getOrElse { "load failed: ${it.message}" }
                if (cont.isActive) cont.resume(err)
            }
        }
        if (syncErr != null) return@withContext syncErr
        // 阶段二：等待脚本异步 init（脚本可能先发 HTTP 再 send('inited')）
        withTimeoutOrNull(8_000L) {
            while (!ready && initError == null) kotlinx.coroutines.delay(100)
        }
        initError ?: if (ready) null else "脚本初始化超时（未调用 lx.send('inited')）"
    }

    private fun loadScriptSync(appContext: Context, meta: ScriptMeta, script: String): String? {
        destroySync()
        authKey = UUID.randomUUID().toString()
        scriptName = meta.name
        ready = false
        initError = null
        sources = emptyMap()

        val ctx = QuickJSContext.create()
        context = ctx

        // r27 关键修复：安装 console——quickjs-android-wrapper 2.4.0 原生库
        // 在上下文创建时注入 globalThis.console，其 stdout 未设置时调用
        // console.log/info/warn/error 会直接抛
        // "When invoke console stuff, you should be set a stdout of platform to console.stdout."
        // （preload 末行 console.log('Preload finished.') 必炸——v1.5.0 r26
        // 「启用失败：脚本初始化异常」根因；lx-music QuickJS.java 同样在
        // evaluate preload 前调用 setConsole）
        ctx.setConsole(object : QuickJSContext.Console {
            private fun send(type: String, msg: String) {
                Log.d("CustomSourceJS", "[$type] $msg")
            }
            override fun log(info: String) = send("log", info)
            override fun info(info: String) = send("info", info)
            override fun warn(info: String) = send("warn", info)
            override fun error(info: String) = send("error", info)
        })

        // native 函数注入（脚本通过 __lx_native_call__ 前缀访问）
        val global = ctx.globalObject
        global.setProperty("__lx_native_call__") { args ->
            if (authKey == args[0]?.toString()) {
                callNative(args[1]?.toString() ?: "", args[2]?.toString() ?: "{}")
            }
            null
        }
        global.setProperty("__lx_native_call__utils_str2b64") { args ->
            runCatching {
                Base64.encodeToString(args[0]?.toString()?.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_b642buf") { args ->
            runCatching {
                val bytes = Base64.decode(args[0]?.toString()?.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                // 返回 JSON 数组字符串（与 lx-music 一致）
                bytes.joinToString(",", "[", "]") { it.toString() }
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_str2md5") { args ->
            runCatching {
                val str = URLDecoder.decode(args[0]?.toString() ?: "", "UTF-8")
                val md = MessageDigest.getInstance("MD5")
                md.digest(str.toByteArray(Charsets.UTF_8))
                    .joinToString("") { String.format("%02x", it) }
            }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__set_timeout") { args ->
            val id = args[0]?.toString() ?: return@setProperty null
            val delay = (args[1] as? Number)?.toLong() ?: 0L
            jsHandler.postDelayed({ callJS("__set_timeout__", id) }, delay)
            null
        }
        // AES/RSA 加密：脚本用到时按需实现（先注册占位，返回空串）
        global.setProperty("__lx_native_call__utils_aes_encrypt") { args ->
            runCatching { AesRsaHelper.aesEncrypt(args) }.getOrDefault("")
        }
        global.setProperty("__lx_native_call__utils_rsa_encrypt") { args ->
            runCatching { AesRsaHelper.rsaEncrypt(args) }.getOrDefault("")
        }

        // preload 环境脚本 + lx_setup（脚本信息注入）
        try {
            val preload = appContext.assets.open("custom_source_preload.js")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            ctx.evaluate(preload)
            ctx.globalObject.getJSFunction("lx_setup").call(
                authKey, meta.id, meta.name, meta.description,
                meta.version, meta.author, meta.homepage, script
            )
        } catch (e: Exception) {
            destroySync()
            return "沙箱环境初始化失败: ${e.message}"
        }

        // 执行音源脚本
        return try {
            ctx.evaluate(script)
            null
        } catch (e: Exception) {
            // lx-music 同款处理：脚本抛错时通知 JS 侧标记已 init（防后续误报）
            runCatching { callJS("__run_error__", "null") }
            val msg = e.message ?: "unknown"
            if (msg.length > 200) msg.take(200) + "…" else msg
        }
    }

    @Synchronized
    fun destroy() {
        if (!::jsThread.isInitialized) return
        jsHandler.post { destroySync() }
    }

    private fun destroySync() {
        runCatching { context?.destroy() }
        context = null
        ready = false
        initError = null
        sources = emptyMap()
        httpCallbacks.clear()
    }

    // ---------------- native ← JS 消息 ----------------

    private fun callNative(action: String, data: String) {
        when (action) {
            "init" -> handleInit(data)
            "request" -> handleScriptRequest(data)
            "cancelRequest" -> httpCallbacks.remove(
                JSONObject(data).optString("requestKey")
            )
            "response" -> handleScriptResponse(data)
        }
    }

    private fun handleInit(data: String) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        val status = o.optBoolean("status")
        val errMsg = o.optString("errorMessage").takeIf { it.isNotBlank() }
        if (status) {
            val info = o.optJSONObject("info")
            val srcMap = mutableMapOf<String, SourceInfo>()
            info?.optJSONObject("sources")?.let { srcs ->
                for (key in srcs.keys()) {
                    val src = srcs.optJSONObject(key) ?: continue
                    if (src.optString("type") != "music") continue
                    srcMap[key] = SourceInfo(
                        actions = src.optJSONArray("actions")?.toStringList() ?: emptyList(),
                        qualitys = src.optJSONArray("qualitys")?.toStringList() ?: emptyList()
                    )
                }
            }
            sources = srcMap
            ready = true
        } else {
            // 脚本主动上报 init 失败——记录详情供 loadScript 阶段二读取
            initError = errMsg ?: "脚本初始化失败"
        }
        onInited?.invoke(status, errMsg, scriptName)
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it) }

    /** 脚本发 HTTP 请求（宿主 OkHttp 代发）。 */
    private fun handleScriptRequest(data: String) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        val requestKey = o.optString("requestKey")
        val url = o.optString("url")
        val options = o.optJSONObject("options") ?: JSONObject()
        httpCallbacks[requestKey] = { responseBody ->
            // 回调进 JS 线程（callJS 线程安全收敛）
            jsHandler.post {
                val resp = JSONObject()
                if (responseBody == null) {
                    resp.put("requestKey", requestKey)
                        .put("error", "request failed")
                        .put("response", JSONObject.NULL)
                } else {
                    resp.put("requestKey", requestKey)
                        .put("error", JSONObject.NULL)
                        // v1.5.1 r34：response 必须是嵌套 JSON 对象（lx 契约）——
                        // 之前 put 的是 JSON 字符串，preload JSON.parse 后 response
                        // 仍是 string，脚本 resp.statusCode = undefined
                        // →「后端接口状态undefined」取歌全失败
                        .put("response", responseBody)
                }
                callJS("response", resp.toString())
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { executeHttp(url, options) }.getOrNull()
            httpCallbacks.remove(requestKey)?.invoke(result)
        }
    }

    /** OkHttp 执行脚本请求（返回 response JSON 对象）。 */
    private fun executeHttp(url: String, options: JSONObject): JSONObject? {
        val method = options.optString("method", "get").lowercase()
        val headers = options.optJSONObject("headers") ?: JSONObject()
        val timeoutMs = options.optLong("timeout", 13_000L)

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36")
        for (key in headers.keys()) {
            builder.header(key, headers.optString(key))
        }
        when (method) {
            "post" -> {
                val body: okhttp3.RequestBody? = when {
                    options.has("body") && options.opt("body") != null -> {
                        val bodyStr = options.opt("body").toString()
                        val ct = headers.optString("Content-Type",
                            "application/json").ifBlank { "application/json" }
                        bodyStr.toRequestBody(ct.toMediaType())
                    }
                    options.has("form") && options.opt("form") != null -> {
                        val form = options.optJSONObject("form")
                        val fb = FormBody.Builder()
                        form?.keys()?.forEach { k -> fb.add(k, form.optString(k)) }
                        fb.build()
                    }
                    else -> null
                }
                builder.post(body ?: ByteArray(0).toRequestBody(null))
            }
            "get" -> builder.get()
        }

        val client = httpClient.newBuilder()
            .callTimeout(timeoutMs.coerceAtMost(60_000L), TimeUnit.MILLISECONDS)
            .build()
        val startMs = System.currentTimeMillis()
        return runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                // v1.5.1 r30：脚本 HTTP 请求日志——诊断脚本服务器问题的关键证据
                Log.d("CustomSourceJS",
                    "HTTP ${options.optString("method", "get").uppercase()} $url" +
                        " → ${resp.code} (${System.currentTimeMillis() - startMs}ms, ${bodyStr.length}B)")
                if (resp.code >= 400) {
                    Log.d("CustomSourceJS", "HTTP error body: ${bodyStr.take(300)}")
                }
                val headersJson = JSONObject()
                resp.headers.forEach { headersJson.put(it.first, it.second) }
                JSONObject()
                    .put("statusCode", resp.code)
                    .put("statusMessage", resp.message)
                    .put("headers", headersJson)
                    // v1.5.1 r34：body 保持字符串——脚本 safeParseBody 自行
                    // JSON.parse（lx 契约 body 是原始字符串）
                    .put("body", bodyStr)
            }
        }.getOrElse { e ->
            // v1.5.1 r30：网络层失败（DNS/连接超时/TLS）——脚本侧只看到 request failed，
            // 真实原因记录到 logcat 供诊断
            Log.w("CustomSourceJS", "HTTP FAILED $url: ${e.message}")
            throw e
        }
    }

    /** 脚本回结果（宿主发起的取歌请求完成）。 */
    private fun handleScriptResponse(data: String) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        val requestKey = o.optString("requestKey")
        val target = pendingRequests.remove(requestKey)
        // v1.5.1 r33：链路诊断日志——脚本回传结果全貌
        Log.d("CustomSourceJS", "script response key=$requestKey status=${o.optBoolean("status")}" +
            " result=${o.optJSONObject("result")?.toString()?.take(200)}" +
            " err=${o.optString("errorMessage").take(100)}")
        if (target == null) return
        if (o.optBoolean("status")) {
            target.first(o.optJSONObject("result"))
        } else {
            target.second(Exception(o.optString("errorMessage", "failed")))
        }
    }

    // ---------------- native → JS 调用 ----------------

    /** 宿主调 JS（必须已在 JS 线程）。 */
    private fun callJS(action: String, data: String) {
        val ctx = context ?: return
        runCatching {
            ctx.globalObject.getJSFunction("__lx_native__").call(authKey, action, data)
        }
    }

    /**
     * 取歌直链（挂起函数，20 秒超时）。
     * @param source lx 源码（kw/kg/tx/wy/mg）
     * @param quality lx 音质（128k/320k/flac/flac24bit）
     * @param songInfo 歌曲信息（songId/songmid/name/singer/albumName 等透传给脚本）
     * @return 直链 URL；失败返回 null
     */
    suspend fun getMusicUrl(
        source: String,
        quality: String,
        songInfo: JSONObject
    ): String? {
        lastResolveError = null
        // v1.5.1 r33：链路诊断日志
        Log.d("CustomSourceJS", "getMusicUrl 入口 source=$source quality=$quality ready=$ready" +
            " sources=${sources.keys} songId=${songInfo.opt("songId")}")
        if (!ready) {
            lastResolveError = "沙箱未就绪"
            return null
        }
        val supported = sources[source] ?: run {
            lastResolveError = "脚本不支持源 $source"
            return null
        }
        if ("musicUrl" !in supported.actions) {
            lastResolveError = "脚本不支持 musicUrl 动作"
            return null
        }
        val result = requestScript(source, "musicUrl", quality, songInfo)
        val url = (result as? JSONObject)?.optJSONObject("data")
            ?.optString("url")?.takeIf { it.isNotBlank() }
        if (url == null && result != null) lastResolveError = "脚本返回空直链"
        if (url == null && result == null && lastResolveError == null) {
            lastResolveError = "取歌失败"
        }
        return url
    }

    /**
     * v1.5.1 r35：脚本歌词（挂起函数，15 秒超时）。
     * 脚本 request 处理器 action='lyric' 返回 {lyric, tlyric, rlyric, lxlyric}。
     * @return LRC 原文；脚本不支持/失败返回 null（调用方回落 GD API）
     */
    suspend fun getLyric(source: String, songInfo: JSONObject): String? {
        if (!ready) return null
        val supported = sources[source] ?: return null
        if ("lyric" !in supported.actions) return null
        // v1.5.1 r35：脚本 lyric 依赖 extraCache（取歌时后端返回的 lrc 缓存）——
        // 歌词请求可能与取歌并发（甚至更早），首次查询时缓存未填 → 脚本
        // 返回 null → preload verifyLyricInfo 抛 'failed' → reject。
        // 重试 4 次（间隔 1.5s，共约 6s 窗口），等取歌完成填充缓存
        repeat(4) { attempt ->
            val result = requestScript(source, "lyric", "", songInfo)
            val lyric = (result as? JSONObject)?.optJSONObject("data")
                ?.optString("lyric")?.takeIf { it.isNotBlank() }
            if (lyric != null) {
                Log.d("CustomSourceJS", "getLyric 成功: ${lyric.length} 字符 (第${attempt + 1}次)")
                return lyric
            }
            if (attempt < 3) kotlinx.coroutines.delay(1500)
        }
        Log.d("CustomSourceJS", "getLyric: 脚本 4 次均空（extraCache 未命中）")
        return null
    }

    /**
     * v1.5.1 r35：脚本封面（挂起函数，15 秒超时）。
     * 脚本 request 处理器 action='pic' 返回封面 URL 字符串。
     * @return 封面 URL；脚本不支持/失败返回 null（调用方回落 GD API）
     */
    suspend fun getPicUrl(source: String, songInfo: JSONObject): String? {
        if (!ready) return null
        val supported = sources[source] ?: return null
        if ("pic" !in supported.actions) return null
        // v1.5.1 r35：与 getLyric 同理——pic 依赖 extraCache，重试 4 次
        repeat(4) { attempt ->
            val result = requestScript(source, "pic", "", songInfo)
            // pic 的 result.data 是字符串（preload 原样放 data 字段）
            val url = when (val data = (result as? JSONObject)?.opt("data")) {
                is String -> data.takeIf { it.isNotBlank() && it.startsWith("http") }
                else -> null
            }
            if (url != null) {
                Log.d("CustomSourceJS", "getPicUrl 成功: ${url.take(120)} (第${attempt + 1}次)")
                return url
            }
            if (attempt < 3) kotlinx.coroutines.delay(1500)
        }
        Log.d("CustomSourceJS", "getPicUrl: 脚本 4 次均空（extraCache 未命中）")
        return null
    }

    /**
     * v1.5.1 r35：通用脚本请求桥——musicUrl/lyric/pic 三动作共用。
     * 发 request 消息给脚本，挂起等待 response 回传 result。
     * @return result JSON 对象；失败/超时返回 null（lastResolveError 记录原因）
     */
    private suspend fun requestScript(
        source: String,
        action: String,
        quality: String,
        songInfo: JSONObject
    ): Any? {
        val requestKey = "req_${UUID.randomUUID()}"
        val result = withTimeoutOrNull(if (action == "musicUrl") 20_000L else 15_000L) {
            suspendCancellableCoroutine { cont ->
                pendingRequests[requestKey] = Pair(
                    { result ->
                        if (cont.isActive) {
                            Log.d("CustomSourceJS", "$action resolve: " +
                                "${(result as? JSONObject)?.toString()?.take(150) ?: "null"}")
                            cont.resume(result)
                        }
                    },
                    { err ->
                        // v1.5.1 r30：脚本主动报错——记录错误消息（超时前到达）
                        if (cont.isActive) {
                            Log.d("CustomSourceJS", "$action reject: ${err.message?.take(150)}")
                            if (action == "musicUrl") {
                                lastResolveError = "脚本报错: ${err.message ?: "unknown"}"
                            }
                            cont.resume(null)
                        }
                    }
                )
                jsHandler.post {
                    val payload = JSONObject()
                        .put("requestKey", requestKey)
                        .put("data", JSONObject()
                            .put("source", source)
                            .put("action", action)
                            .put("info", JSONObject()
                                .put("type", quality)
                                .put("musicInfo", songInfo)
                            )
                        )
                    callJS("request", payload.toString())
                }
            }
        }
        if (result == null) {
            if (action == "musicUrl" && lastResolveError == null) {
                lastResolveError = if (pendingRequests.remove(requestKey) == null)
                    "脚本未响应（可能未注册 request 处理器）"
                else "取歌超时（20 秒无结果）"
            }
            pendingRequests.remove(requestKey)
            Log.d("CustomSourceJS", "$action 失败: $lastResolveError")
        }
        return result
    }

    // ---------------- 导入解析 ----------------

    /**
     * 解析脚本头部注释元信息（与 lx-music addUserApi 同规则）：
     * 脚本开头必须有块注释，内含 @name、@version、@author、@homepage
     * 等 @ 字段（每行一个）；无块注释或缺 @name 返回 null（非法脚本）。
     */
    fun parseMeta(script: String): ScriptMeta? {
        val block = Regex("^/\\*[\\s\\S]+?\\*/").find(script)?.value ?: return null
        val fields = mapOf(
            "name" to 24, "description" to 36,
            "author" to 56, "homepage" to 1024, "version" to 36
        )
        val infos = mutableMapOf<String, String>()
        for (line in block.split("\r\n", "\n")) {
            val m = Regex("^\\s?\\*\\s?@(\\w+)\\s(.+)$").find(line) ?: continue
            val key = m.groupValues[1]
            if (key !in fields) continue
            infos[key] = m.groupValues[2].trim().take(fields[key]!!)
        }
        if (infos["name"].isNullOrBlank()) return null
        return ScriptMeta(
            id = "user_api_${UUID.randomUUID().toString().take(8)}_${System.currentTimeMillis()}",
            name = infos["name"]!!,
            description = infos["description"] ?: "",
            version = infos["version"] ?: "",
            author = infos["author"] ?: "",
            homepage = infos["homepage"] ?: ""
        )
    }
}

data class ScriptMeta(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = ""
)

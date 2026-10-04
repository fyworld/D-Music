package com.solara.music.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * v1.5.1 r29：五平台直连搜索（移植自 lx-music-mobile musicSdk，Apache-2.0）。
 *
 * 用途：启用自定义音源（优先模式）时，搜索页直连各平台官方接口搜歌，
 * 完全绕开 GD音乐台聚合 API——搜歌+播放双链路去 GD 化。
 *
 * 平台与加密：
 * - kw 酷我：search.kuwo.cn 纯 GET，无加密
 * - kg 酷狗：songsearch.kugou.com 纯 GET，无加密
 * - tx QQ：u.y.qq.com POST，zzcSign 签名（SHA1→索引取值→XOR→base64）
 * - wy 网易：interface.music.163.com eapi（MD5 摘要+AES-ECB 固定密钥）
 * - mg 咪咕：jadeite.migu.cn GET，MD5 签名（固定盐值）
 *
 * 聚合搜索（source="all"）：并发全平台、失败平台返回空、合并去重、
 * 按关键词与「歌名 歌手」相似度排序（与 lx-music 同策略）。
 *
 * songId 说明：返回的 Song.id 用平台原生 id（与 GD 聚合 API 返回的
 * id 同源——GD 本身也是查这些平台），自定义源脚本按 songId 取直链。
 */
object PlatformSearchApi {

    /** 平台源码（与 lx-music 一致）。 */
    const val SRC_ALL = "all"
    const val SRC_KW = "kw"
    const val SRC_KG = "kg"
    const val SRC_TX = "tx"
    const val SRC_WY = "wy"
    const val SRC_MG = "mg"

    /** 平台显示名（搜索页 tab 用）。 */
    val sourceNames = mapOf(
        SRC_KW to "酷我", SRC_KG to "酷狗", SRC_TX to "QQ",
        SRC_WY to "网易", SRC_MG to "咪咕"
    )

    /** D Music 源码 → lx 平台码映射（与 CustomSourceManager 一致）。 */
    fun toPlatform(source: String): String? = when (source) {
        "netease" -> SRC_WY
        "tencent" -> SRC_TX
        "kuwo" -> SRC_KW
        "kugou" -> SRC_KG
        "migu" -> SRC_MG
        else -> null
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

    // ---------------- 对外入口 ----------------

    /**
     * 平台搜索（或聚合搜索）。
     * @param source "all" 或 kw/kg/tx/wy/mg
     * @return 搜索结果（失败返回空列表——聚合模式单平台失败不影响整体）
     */
    suspend fun search(source: String, keyword: String, page: Int, count: Int = 30): List<Song> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()
            if (source == SRC_ALL) {
                searchAll(kw, page, count)
            } else {
                runCatching { searchPlatform(source, kw, page, count) }.getOrDefault(emptyList())
            }
        }

    /** 聚合搜索：并发全平台 + 去重 + 相似度排序。 */
    private suspend fun searchAll(keyword: String, page: Int, count: Int): List<Song> =
        kotlinx.coroutines.coroutineScope {
            val sources = listOf(SRC_KW, SRC_KG, SRC_TX, SRC_WY, SRC_MG)
            val results = sources.map { src ->
                async {
                    runCatching { searchPlatform(src, keyword, page, count) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
            // 去重（同源同 id）
            val seen = HashSet<Int>()
            val deduped = results.filter { s -> seen.add("${s.source}:${s.id}".hashCode()) }
            // 相似度排序（关键词 vs "歌名 歌手"，降序——与 lx-music handleSortList 一致）
            deduped.sortedByDescending { s -> similarity(keyword, "${s.name} ${s.artist}") }
        }

    // ---------------- 各平台实现 ----------------

    private suspend fun searchPlatform(source: String, keyword: String, page: Int, count: Int): List<Song> =
        when (source) {
            SRC_KW -> searchKw(keyword, page, count)
            SRC_KG -> searchKg(keyword, page, count)
            SRC_TX -> searchTx(keyword, page, count)
            SRC_WY -> searchWy(keyword, page, count)
            SRC_MG -> searchMg(keyword, page, count)
            else -> emptyList()
        }

    /** 酷我：纯 GET，无加密。 */
    private fun searchKw(keyword: String, page: Int, count: Int): List<Song> {
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${urlEnc(keyword)}" +
            "&pn=${page - 1}&rn=$count&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
            "&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8" +
            "&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val body = httpGet(url)
        val root = parseJson(body) ?: return emptyList()
        val arr = root.optJSONArray("abslist") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val songId = o.optString("MUSICRID").removePrefix("MUSIC_")
            if (songId.isBlank()) null else Song(
                id = songId,
                name = o.optString("SONGNAME"),
                artist = o.optString("ARTIST"),
                album = o.optString("ALBUM"),
                picId = "",
                source = SRC_KW,
                // v1.5.1 r32：lx-music 契约字段（脚本解析直链必需）
                interval = formatPlayTimeSecs(o.optInt("DURATION"))
            )
        }
    }

    /** 酷狗：纯 GET，无加密。 */
    private fun searchKg(keyword: String, page: Int, count: Int): List<Song> {
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=${urlEnc(keyword)}" +
            "&page=$page&pagesize=$count&userid=0&clientver=&platform=WebFilter&filter=2" +
            "&iscorrection=1&privilege_filter=0&area_code=1"
        val body = httpGet(url)
        val root = parseJson(body) ?: return emptyList()
        if (root.optInt("error_code", -1) != 0) return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val songId = o.optString("Audioid")
            val hash = o.optString("FileHash")
            val key = songId + hash
            if (songId.isBlank() || !seen.add(key)) null else Song(
                id = songId,
                name = o.optString("OriSongName") + o.optString("Suffix").let {
                    if (it.isNotBlank()) " $it" else ""
                },
                artist = formatSingersJsonArray(o.optJSONArray("Singers"), "name"),
                album = o.optString("AlbumName"),
                picId = "",
                source = SRC_KG,
                // v1.5.1 r32：kg 脚本解析直链必需 FileHash（lx-music meta.hash）
                hash = hash,
                interval = formatPlayTimeSecs(o.optInt("Duration"))
            )
        }
    }

    /** QQ 音乐：POST + zzcSign 签名。 */
    private fun searchTx(keyword: String, page: Int, count: Int): List<Song> {
        val reqBody = JSONObject()
            // v1.5.1 r36：完整 comm 13 字段（lx-music 原版）——简版 comm 被
            // 服务端拒（code 2001 签名校验失败）
            .put("comm", JSONObject()
                .put("_channelid", "0")
                .put("_os_version", "6.2.9200-2")
                .put("ct", "19")
                .put("cv", "2151")
                .put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
                .put("patch", "118")
                .put("psrf_access_token_expiresAt", 0)
                .put("psrf_qqaccess_token", "")
                .put("psrf_qqopenid", "")
                .put("psrf_qqunionid", "")
                .put("tmeAppID", "qqmusic")
                .put("tmeLoginType", 0)
                .put("uin", "0")
                .put("wid", "7223299733393904640"))
            .put("music.search.SearchCgiService", JSONObject()
                .put("module", "music.search.SearchCgiService")
                .put("method", "DoSearchForQQMusicDesktop")
                .put("param", JSONObject()
                    .put("grp", 1)
                    .put("num_per_page", count)
                    .put("page_num", page)
                    .put("query", keyword)
                    .put("remoteplace", "txt.newclient.top")
                    .put("search_type", 0)
                    .put("searchid", txSearchId())))
        val sign = zzcSign(reqBody.toString())
        val url = "https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sign"
        val body = httpPostJson(url, reqBody.toString(), "QQMusic 14090508(android 12)")
        val root = parseJson(body) ?: return emptyList()
        val req = root.optJSONObject("music.search.SearchCgiService")
            ?: root.optJSONObject("req") ?: return emptyList()
        // v1.5.1 r36：当前接口返回 data.body.song.list（多一层 body），
        // 兼容旧结构 data.song.list
        val d = req.optJSONObject("data") ?: return emptyList()
        val arr = d.optJSONObject("song")?.optJSONArray("list")
            ?: d.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val mediaMid = o.optJSONObject("file")?.optString("media_mid") ?: ""
            if (mediaMid.isBlank()) null else Song(
                id = o.optString("id"),
                name = o.optString("title"),
                artist = formatSingersJsonArray(o.optJSONArray("singer"), "name"),
                album = o.optJSONObject("album")?.optString("name") ?: "",
                picId = o.optJSONObject("album")?.optString("mid") ?: "",
                source = SRC_TX,
                // v1.5.1 r32：tx 脚本解析直链必需 media_mid（lx-music meta.strMediaMid）
                strMediaMid = mediaMid,
                albumMid = o.optJSONObject("album")?.optString("mid") ?: "",
                // v1.5.1 r38 修复：tx interval 原始值是**秒**（实测晴天=269），
                // 原按毫秒 formatPlayTimeMs(269)→269/1000=0→返回空串——
                // tx 搜的歌时长全空（"有的歌有时长有的没有"根因）
                interval = formatPlayTimeSecs(o.optInt("interval"))
            )
        }
    }

    /** 网易：eapi（MD5 摘要 + AES-ECB 固定密钥）。 */
    private fun searchWy(keyword: String, page: Int, count: Int): List<Song> {
        val urlPath = "/api/search/song/list/page"
        val params = JSONObject()
            .put("keyword", keyword)
            .put("needCorrect", "1")
            .put("channel", "typing")
            .put("offset", count * (page - 1))
            .put("scene", "normal")
            .put("total", page == 1)
            .put("limit", count)
        val text = params.toString()
        val message = "nobody${urlPath}use${text}md5forencrypt"
        val digest = md5Hex(message)
        val data = "$urlPath-36cd479b6b5-$text-36cd479b6b5-$digest"
        // AES-128-ECB 固定密钥 e82ckenh8dichen8，输出 hex 大写
        val paramsHex = aesEcbEncryptHex(data, "e82ckenh8dichen8")
        val form = FormBody.Builder().add("params", paramsHex).build()
        val request = Request.Builder()
            .url("http://interface.music.163.com/eapi/batch")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36")
            .header("Origin", "https://music.163.com")
            .post(form)
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        val root = parseJson(body) ?: return emptyList()
        if (root.optInt("code", -1) != 200) return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("resources") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // 新版接口：baseInfo.simpleSongData
            val item = o.optJSONObject("baseInfo")?.optJSONObject("simpleSongData")
                ?: o.optJSONObject("simpleSongData") ?: return@mapNotNull null
            val songId = item.optString("id")
            if (songId.isBlank() || songId == "0") null else Song(
                id = songId,
                name = item.optString("name"),
                artist = formatSingersJsonArray(item.optJSONArray("ar"), null),
                album = item.optJSONObject("al")?.optString("name") ?: "",
                picId = "",
                source = SRC_WY,
                // v1.5.1 r32：lx-music 契约字段
                interval = formatPlayTimeMs((item.optDouble("dt", 0.0) / 1000).toInt())
            )
        }
    }

    /** 咪咕：GET + MD5 签名（固定盐值）。 */
    private fun searchMg(keyword: String, page: Int, count: Int): List<Song> {
        val time = System.currentTimeMillis().toString()
        val deviceId = "963B7AA0D21511ED807EE5846EC87D20"
        val signatureMd5 = "6cdc72a439cef99a3418d2a78aa28c73"
        val sign = md5Hex("$keyword$signatureMd5" +
            "yyapp2d16148780a1dcc7408e06336b98cfd50$deviceId$time")
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=0" +
            "&isCopyright=1&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0" +
            "%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0" +
            "%2C%22lyricSong%22%3A0%7D&pageSize=$count&text=${urlEnc(keyword)}&pageNo=$page" +
            "&sort=0&sid=USS"
        val request = Request.Builder()
            .url(url)
            .header("uiVersion", "A_music_3.6.1")
            .header("deviceId", deviceId)
            .header("timestamp", time)
            .header("sign", sign)
            .header("channel", "0146921")
            .header("User-Agent", "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
            .get()
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        val root = parseJson(body) ?: return emptyList()
        if (root.optString("code") != "000000") return emptyList()
        val songData = root.optJSONObject("songResultData") ?: return emptyList()
        val outer = songData.optJSONArray("resultList") ?: return emptyList()
        val seen = HashSet<String>()
        val list = mutableListOf<Song>()
        for (i in 0 until outer.length()) {
            val inner = outer.optJSONArray(i) ?: continue
            for (j in 0 until inner.length()) {
                val o = inner.optJSONObject(j) ?: continue
                val songId = o.optString("songId")
                val copyrightId = o.optString("copyrightId")
                if (songId.isBlank() || copyrightId.isBlank() || !seen.add(copyrightId)) continue
                list.add(Song(
                    id = songId,
                    name = o.optString("name"),
                    // v1.5.1 r34：mg singerList 歌手名字段是 "name"（实测接口返回
                    // [{id,name,img,nameSpelling}]），之前取 "singerName" 恒空 →
                    // 全部显示「未知歌手」
                    artist = formatSingersJsonArray(o.optJSONArray("singerList"), "name")
                        .ifBlank { o.optString("singer") },
                    album = o.optString("album"),
                    picId = "",
                    source = SRC_MG,
                    // v1.5.1 r32：mg 脚本解析直链必需 copyrightId（lx-music meta.copyrightId）
                    copyrightId = copyrightId,
                    // v1.5.1 r34：mg 时长字段是 duration（秒）——interval 恒空
                    interval = formatPlayTimeSecs(o.optInt("duration"))
                ))
            }
        }
        return list
    }

    // ---------------- 工具函数 ----------------

    private fun httpGet(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA_PC).get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }

    private fun httpPostJson(url: String, json: String, ua: String): String =
        client.newCall(Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }

    private fun parseJson(body: String): JSONObject? = runCatching {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) JSONObject(trimmed) else null
    }.getOrNull()

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }

    /** AES-128-ECB 加密输出 hex 大写（wy eapi 用）。 */
    private fun aesEcbEncryptHex(data: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { String.format("%02X", it) }
    }

    /** tx searchid：32 位大写 hex + 5 位补零随机数（与 lx-music getSearchId 一致）。 */
    private fun txSearchId(): String {
        val guid = StringBuilder()
        repeat(32) { guid.append("0123456789abcdef".random()) }
        return guid.toString().uppercase() +
            String.format("%05d", (0 until 100000).random())
    }

    /**
     * tx zzcSign 签名（与 lx-music crypto.js 一致）：
     * SHA1 → 按两组索引取字符 → 20 个固定值 XOR hash 前 40 hex → base64 去特殊字符。
     */
    private fun zzcSign(text: String): String {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
        val part1Indexes = listOf(23, 14, 6, 36, 16, 40, 7, 19)
        val part2Indexes = listOf(16, 1, 32, 12, 19, 27, 8, 5)
        val scramble = listOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123,
            120, 64, 242, 133, 143, 161, 121, 179)
        val part1 = part1Indexes.joinToString("") { hash.getOrNull(it)?.toString() ?: "" }
        val part2 = part2Indexes.joinToString("") { hash.getOrNull(it)?.toString() ?: "" }
        val part3 = scramble.mapIndexed { i, v ->
            (v xor hash.substring(i * 2, i * 2 + 2).toInt(16)).toByte()
        }.toByteArray()
        val b64 = Base64.encodeToString(part3, Base64.NO_WRAP)
            .replace(Regex("[/+=]"), "")
        return "zzc$part1$b64$part2".lowercase()
    }

    /** JSONArray 歌手列表 → "歌手1 / 歌手2"。 */
    private fun formatSingersJsonArray(arr: JSONArray?, nameField: String?): String {
        if (arr == null || arr.length() == 0) return ""
        val names = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (nameField != null) o.optString(nameField) else o.optString("name")
        }.filter { it.isNotBlank() }
        return names.joinToString(" / ")
    }

    /** v1.5.1 r32：秒 → "mm:ss"（lx-music formatPlayTime 同款，interval 契约格式）。 */
    private fun formatPlayTimeSecs(totalSec: Int): String {
        if (totalSec <= 0) return ""
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** v1.5.1 r32：毫秒 → "mm:ss"（tx/mg 接口返回毫秒）。 */
    private fun formatPlayTimeMs(totalMs: Int): String =
        formatPlayTimeSecs(totalMs / 1000)

    /**
     * 关键词相似度（聚合搜索排序用，简化版 Levenshtein 比率）。
     * 与 lx-music similar() 目标一致：关键词越接近「歌名 歌手」排越前。
     */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val la = a.length
        val lb = b.length
        // 快速路径：包含关系给高分
        if (b.contains(a)) return 1.0 - (lb - la).toDouble() / (lb + la)
        // Levenshtein DP（短文本足够快）
        val prev = IntArray(lb + 1) { it }
        val cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, lb + 1)
        }
        val dist = prev[lb]
        return 1.0 - dist.toDouble() / (la + lb)
    }
}

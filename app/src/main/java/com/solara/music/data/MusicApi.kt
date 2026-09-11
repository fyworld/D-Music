package com.solara.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * GD 音乐台聚合 API 客户端。
 * 接口契约与 Solara 网页版保持一致：
 * - 搜索:  ?types=search&source=netease&name=keyword&count=30&pages=1
 * - 播放:  ?types=url&id=xxx&source=netease&br=320
 * - 歌词:  ?types=lyric&id=xxx&source=netease
 * - 封面:  ?types=pic&id=xxx&source=netease&size=300
 */
object MusicApi {

    const val DEFAULT_BASE_URL = "https://music-api.gdstudio.xyz/api.php"

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val picCache = ConcurrentHashMap<String, String>()

    /** 跨站曲库检索，返回结构化歌曲列表。 */
    suspend fun search(src: String, keyword: String, page: Int, count: Int = 30): List<Song> =
        withContext(Dispatchers.IO) {
            val body = httpGet(
                urlOf(
                    "types" to "search",
                    "source" to src,
                    "name" to keyword,
                    "count" to count.toString(),
                    "pages" to page.toString()
                )
            ).trim()

            if (!body.startsWith("[")) {
                val errMsg = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
                throw IOException(errMsg.ifBlank { "接口返回异常，请稍后重试或更换音源" })
            }

            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Song(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    picId = o.optString("pic_id"),
                    urlId = o.optString("url_id"),
                    lyricId = o.optString("lyric_id"),
                    source = o.optString("source").ifBlank { src }
                )
            }.filter { it.name.isNotBlank() }
        }

    /** 解析播放直链，失败返回 null。 */
    suspend fun resolveUrl(song: Song, br: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(
                urlOf(
                    "types" to "url",
                    "id" to song.id,
                    "source" to song.source,
                    "br" to br
                )
            ).trim()
            JSONObject(body).optString("url")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 获取 LRC 原始歌词文本，失败返回 null。 */
    suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(
                urlOf(
                    "types" to "lyric",
                    "id" to song.lyricId.ifBlank { song.id },
                    "source" to song.source
                )
            ).trim()
            JSONObject(body).optString("lyric")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 获取封面图片地址（接口返回 JSON，需二次解析），带内存缓存。 */
    suspend fun fetchPicUrl(song: Song): String? {
        val id = song.picId.ifBlank { song.id }
        val key = "${song.source}:$id"
        picCache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = httpGet(
                    urlOf(
                        "types" to "pic",
                        "id" to id,
                        "source" to song.source,
                        "size" to "300"
                    )
                ).trim()
                JSONObject(body).optString("url")
            }.getOrNull()?.takeIf { it.isNotBlank() }?.also { picCache[key] = it }
        }
    }

    private fun urlOf(vararg params: Pair<String, String>): String {
        val base = baseUrl.ifBlank { DEFAULT_BASE_URL }.trim()
        val sep = if (base.contains('?')) '&' else '?'
        return buildString {
            append(base).append(sep)
            params.forEachIndexed { i, (k, v) ->
                if (i > 0) append('&')
                append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) SolaraAndroid/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }
}

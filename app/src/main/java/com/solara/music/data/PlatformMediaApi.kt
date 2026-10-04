package com.solara.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * v1.5.1 r35/r36：平台直连歌词/封面 API——lx 五平台（kw/kg/tx/wy/mg）。
 *
 * 背景：平台直连搜索（PlatformSearchApi）搜出的歌 source 是 lx 源码，
 * GD API 不支持这些源的 lyric/pic 查询（实测 "Value of source is not
 * supported"），自定义源脚本的 lyric/pic 又依赖 extraCache（取歌时后端
 * 返回，多数脚本后端不返回 lrc/picture）。
 *
 * 方案：照搬 lx-music 内置 musicSdk 的平台官方接口（lx-music 的歌词
 * 封面从不走用户脚本，全部平台直连）：
 * - kw 歌词: mlyric.kuwo.cn/mobi.s（二进制：TP=content 头+zlib+base64+XOR yeelion）
 * - kw 封面: artistpicserver.kuwo.cn rid_pic
 * - kg 歌词: lyrics.kugou.com search → download（krc 解密：base64→跳4字节→XOR enc_key→zlib）
 * - kg 封面: media.store.kugou.com get_res_privilege
 * - tx 歌词: u.y.qq.com musicu.fcg GetPlayLyricInfo（hex→3DES→zlib→qrc XML）
 * - tx 封面: y.gtimg.cn 拼接（albumMid）
 * - wy 歌词: music.163.com/api/song/lyric（老接口 GET，eapi 真机空响应弃用）
 * - wy 封面: music.163.com/api/song/detail（al.picUrl）
 * - mg 歌词: c.musicapp.migu.cn resourceinfo（resourceId=songId）→ lrcUrl 直取
 * - mg 封面: 同 resourceinfo（albumImgs 数组 imgSizeType 03）
 *
 * 接口来源：lx-music-mobile src/utils/musicSdk（GPL-3.0，接口 URL 与
 * 请求参数为公开的平台官方接口，实现为独立重写）。
 */
object PlatformMediaApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 是否 lx 平台直连源码。 */
    fun isPlatformSource(source: String): Boolean =
        source == "kw" || source == "kg" || source == "tx" || source == "wy" || source == "mg"

    // ---------------- 入口 ----------------

    /**
     * 平台直连歌词（LRC 原文）。
     * @return LRC 文本；平台不支持/失败返回 null
     */
    suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (song.source) {
                "kw" -> kwLyric(song.id)
                "kg" -> kgLyric(song)
                "tx" -> txLyric(song.id)
                "wy" -> wyLyric(song.id)
                // v1.5.1 r36：mg resourceinfo 必须传 songId（copyrightId 返回空）
                "mg" -> mgLyric(song.id)
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 平台直连封面 URL。
     * @return 图片 URL；失败返回 null
     */
    suspend fun fetchPicUrl(song: Song): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (song.source) {
                "kw" -> kwPic(song.id)
                "kg" -> kgPic(song)
                "tx" -> txPic(song.albumMid)
                "wy" -> wyPic(song.id)
                // v1.5.1 r36：mg resourceinfo 必须传 songId（copyrightId 返回空）
                "mg" -> mgPic(song.id)
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ---------------- 通用 HTTP ----------------

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    /** 二进制 GET（kw mlyric 等二进制响应用）。 */
    private fun httpGetBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun httpPostJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun httpPostForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val b = Request.Builder().url(url).post(fb.build())
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    // ---------------- kw（酷我） ----------------

    /**
     * kw 歌词：mlyric.kuwo.cn/mobi.s（lx-music 实际用的接口）。
     *
     * v1.5.1 r36：原 h5 接口（m.kuwo.cn/newh5/singles/songinfoandlrc）真机
     * 被风控（返回空/拦截），换 lx-music 同款 mlyric 二进制接口：
     * 响应 = "TP=content\r\n\r\n" 头 + zlib 压缩的 base64 文本，
     * base64 解码后逐字节 XOR 密钥 "yeelion" 即明文 LRC。
     * 解出的 LRC 含 kw 逐字标签 <offset,dur>（如 <1120,-1120>），需剥离。
     */
    private fun kwLyric(songId: String): String? {
        val raw = httpGetBytes(
            "http://mlyric.kuwo.cn/mobi.s?f=web&type=lyric&lrcx=1&rid=$songId&encode=utf8"
        )
        // 头校验：TP=content（无歌词返回 tp=none）
        val head = String(raw.copyOfRange(0, minOf(10, raw.size)), Charsets.UTF_8).lowercase()
        if (head != "tp=content") return null
        // \r\n\r\n 分隔头与压缩体
        val sepIdx = raw.indexOfSequence(byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A))
        if (sepIdx < 0) return null
        // zlib inflate
        val inflater = java.util.zip.Inflater()
        inflater.setInput(raw, sepIdx + 4, raw.size - sepIdx - 4)
        val output = ByteArray(1 shl 18)
        val len = runCatching { inflater.inflate(output) }.getOrDefault(-1)
        inflater.end()
        if (len <= 0) return null
        // inflate 出的是 base64 文本 → 解码 → XOR yeelion
        val b64Text = String(output, 0, len, Charsets.UTF_8).trim()
        val b64 = android.util.Base64.decode(b64Text, android.util.Base64.DEFAULT)
        val key = "yeelion".toByteArray(Charsets.UTF_8)
        for (i in b64.indices) {
            b64[i] = (b64[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val lrc = String(b64, Charsets.UTF_8)
        // 剥离 kw 逐字标签 <offset,dur>
        return lrc.replace(Regex("<-?\\d+,-?\\d+>"), "").takeIf { it.isNotBlank() }
    }

    /** ByteArray.indexOfSequence（kw mlyric 头分隔用）。 */
    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** kw 封面：artistpicserver rid_pic（纯文本 URL 响应）。 */
    private fun kwPic(songId: String): String? {
        val body = httpGet(
            "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$songId"
        )
        return body.trim().takeIf { it.startsWith("http") }
    }

    // ---------------- kg（酷狗） ----------------

    /**
     * kg 歌词：lyrics.kugou.com search → download。
     *
     * v1.5.1 r36 两处修复：
     * 1. timelength 单位改秒（lx-music getIntv 返回秒；原毫秒差 1000 倍
     *    导致搜索匹配失败 → 没歌词）
     * 2. krc 格式实现解密（base64 → 跳前 4 字节 → XOR enc_key →
     *    zlib inflate），原直接返回 null
     */
    private fun kgLyric(song: Song): String? {
        val intervalSec = intervalToSec(song.interval)
        val name = URLEncoder.encode(song.name, "UTF-8")
        val headers = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager"
        )
        val searchBody = httpGet(
            "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$name" +
                "&hash=${song.hash}&timelength=$intervalSec&lrctxt=1",
            headers
        )
        val candidates = JSONObject(searchBody).optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val id = first.optString("id")
        val accessKey = first.optString("accesskey")
        // krctype==1 且 contenttype!=1 → krc（需解密），否则 lrc（base64）
        val fmt = if (first.optInt("krctype") == 1 && first.optInt("contenttype") != 1) "krc" else "lrc"
        val dlBody = httpGet(
            "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=$fmt&charset=utf8",
            headers
        )
        val dl = JSONObject(dlBody)
        val content = dl.optString("content")
        if (content.isBlank()) return null
        return when (dl.optString("fmt", fmt)) {
            "lrc" -> {
                // content 是 base64 的 LRC 原文
                val bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            }
            "krc" -> kgDecodeKrc(content)
            else -> null
        }
    }

    /**
     * kg krc 解密（lx-music kg/util.js decodeLyric 同款）：
     * base64 → 跳过前 4 字节 → XOR enc_key（16 字节）→ zlib inflate →
     * krc 明文（[start,dur]<offset,dur,0>字 逐字格式）。
     * 解密后转普通 LRC（剥离逐字标签，行时间 [ms,dur] → [mm:ss.xxx]）。
     */
    private fun kgDecodeKrc(content: String): String? {
        return runCatching {
            val encKey = byteArrayOf(
                0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
                0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(),
                0x6e.toByte(), 0x69.toByte()
            )
            var buf = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            if (buf.size <= 4) return null
            buf = buf.copyOfRange(4, buf.size)
            for (i in buf.indices) {
                buf[i] = (buf[i].toInt() xor encKey[i % 16].toInt()).toByte()
            }
            // zlib inflate
            val inflater = java.util.zip.Inflater()
            inflater.setInput(buf)
            val output = ByteArray(1 shl 18)
            val len = runCatching { inflater.inflate(output) }.getOrDefault(-1)
            inflater.end()
            if (len <= 0) return null
            val krc = String(output, 0, len, Charsets.UTF_8)
            // krc → LRC：[start,dur]<offset,dur,0>字 → [mm:ss.xxx]字
            val sb = StringBuilder()
            val lineTimeRegex = Regex("^\\[(\\d+),(\\d+)\\]")
            val wordTimeRegex = Regex("<-?\\d+,-?\\d+,\\d+>")
            for (line in krc.split('\n')) {
                val m = lineTimeRegex.find(line)
                if (m == null) {
                    // 元数据行（[ti:...] 等）原样保留
                    if (line.startsWith('[')) sb.append(line).append('\n')
                    continue
                }
                val startMs = m.groupValues[1].toLongOrNull() ?: continue
                val text = line.removePrefix(m.value).replace(wordTimeRegex, "")
                sb.append('[').append(formatMsLrc(startMs)).append(']').append(text).append('\n')
            }
            sb.toString().takeIf { it.isNotBlank() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** kg 封面：media.store.kugou.com get_res_privilege。 */
    private fun kgPic(song: Song): String? {
        val audioId = if (song.id.length == 32) song.id.split("_").first() else song.id
        val body = JSONObject()
            .put("appid", 1001)
            .put("area_code", "1")
            .put("behavior", "play")
            .put("clientver", "9020")
            .put("need_hash_offset", 1)
            .put("relate", 1)
            .put("resource", org.json.JSONArray().put(JSONObject()
                .put("album_audio_id", audioId)
                .put("album_id", song.albumMid)
                .put("hash", song.hash)
                .put("id", 0)
                .put("name", "${song.artist} - ${song.name}.mp3")
                .put("type", "audio")))
            .put("token", "")
            .put("userid", 2626431536)
            .put("vip", 1)
        val resp = httpPostJson(
            "http://media.store.kugou.com/v1/get_res_privilege",
            body.toString(),
            mapOf(
                "KG-RC" to "1",
                "KG-THash" to "expand_search_manager.cpp:852736169:451",
                "User-Agent" to "KuGou2012-9020-ExpandSearchManager"
            )
        )
        val info = JSONObject(resp).optJSONArray("data")?.optJSONObject(0)
            ?.optJSONObject("info") ?: return null
        val image = info.optString("image")
        if (image.isBlank()) return null
        val imgsize = info.optJSONArray("imgsize")
        return if (imgsize != null && imgsize.length() > 0) {
            image.replace("{size}", imgsize.optString(0))
        } else image
    }

    // ---------------- tx（腾讯） ----------------

    /**
     * tx 歌词：musicu.fcg GetPlayLyricInfo（qrc）。
     *
     * v1.5.1 r36 修复：data.lyric 是 hex 字符串（不是 base64），需
     * 3DES-ECB 解密（QRC_KEY 24 字节，非标准 S-box）+ zlib inflate，
     * 解出 XML 壳（LyricContent="..."）内含 qrc 逐字歌词。
     */
    private fun txLyric(songId: String): String? {
        val body = JSONObject()
            .put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
            .put("req", JSONObject()
                .put("method", "GetPlayLyricInfo")
                .put("module", "music.musichallSong.PlayLyricInfo")
                .put("param", JSONObject()
                    .put("format", "json").put("crypt", 1).put("ct", 19).put("cv", 1873)
                    .put("interval", 0).put("lrc_t", 0).put("qrc", 1).put("qrc_t", 0)
                    .put("roma", 1).put("roma_t", 0).put("songID", songId.toLongOrNull() ?: 0)
                    .put("trans", 1).put("trans_t", 0).put("type", -1)))
        val resp = httpPostJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body.toString(),
            mapOf(
                "referer" to "https://y.qq.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36"
            )
        )
        val data = JSONObject(resp).optJSONObject("req")?.optJSONObject("data") ?: return null
        val lyricHex = data.optString("lyric")
        if (lyricHex.isBlank() || lyricHex.length % 2 != 0) return null
        // hex → 3DES 解密 → zlib inflate → XML 壳
        val qrcXml = qrcDecode(lyricHex) ?: return null
        // 提取 LyricContent="..." 内的 qrc 文本
        val content = extractQrcContent(qrcXml) ?: return null
        // qrc 逐字格式转普通 LRC：[start,dur]字(offset,dur)字... → [mm:ss.xxx]字字...
        return qrcToLrc(content)
    }

    /** 从 QRC XML 壳提取 LyricContent 内容（含转义换行）。 */
    private fun extractQrcContent(xml: String): String? {
        // LyricContent="..." 值内换行是字面 \n（两字符）
        val start = xml.indexOf("LyricContent=\"")
        if (start < 0) return null
        val contentStart = start + "LyricContent=\"".length
        val end = xml.indexOf("\"/>", contentStart)
        if (end < 0) return null
        return xml.substring(contentStart, end)
    }

    /** qrc 逐字格式 → 普通 LRC（[0,2250]晴(0,160)天(160,160) → [00:00.000]晴天）。 */
    private fun qrcToLrc(qrc: String): String? {
        val sb = StringBuilder()
        // 实测 LyricContent 属性值内是真实换行符（非字面 \n）
        val lines = qrc.split('\n')
        val lineTimeRegex = Regex("^\\[(\\d+),(\\d+)\\]")
        val wordTimeRegex = Regex("\\(\\d+,\\d+\\)")
        for (line in lines) {
            val m = lineTimeRegex.find(line) ?: continue
            val startMs = m.groupValues[1].toLongOrNull() ?: continue
            val text = line.removePrefix(m.value).replace(wordTimeRegex, "")
            sb.append('[').append(formatMsLrc(startMs)).append(']').append(text).append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 毫秒 → "mm:ss.xxx"（LRC 时间标签）。 */
    private fun formatMsLrc(ms: Long): String {
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        val milli = (ms % 1000).toInt()
        return "%02d:%02d.%03d".format(mm, ss, milli)
    }

    /**
     * QRC 3DES-ECB 解密（lx-music qrcDecode.js 逐位移植，已 Python 验证）。
     * hex → 3DES 解密（非标准 S-box：S2[23]=15、S4[53]=10）→ zlib inflate。
     */
    private fun qrcDecode(hexData: String): String? {
        return runCatching {
            val encrypted = hexToBytes(hexData)
            if (encrypted.isEmpty()) return null
            val schedule = tripledesKeySetup(QRC_KEY, DES_DECRYPT)
            val block = ByteArray(8)
            var i = 0
            while (i + 8 <= encrypted.size) {
                tripledesCrypt(encrypted.copyOfRange(i, i + 8), schedule, block)
                System.arraycopy(block, 0, encrypted, i, 8)
                i += 8
            }
            // zlib inflate（raw deflate：跳过 2 字节 zlib 头）
            if (encrypted.size < 3) return null
            val inflater = java.util.zip.Inflater(true)
            inflater.setInput(encrypted, 2, encrypted.size - 2)
            val output = ByteArray(1 shl 18)
            val len = runCatching { inflater.inflate(output) }.getOrDefault(-1)
            inflater.end()
            if (len <= 0) return null
            String(output, 0, len, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    // ---------------- QRC 3DES 实现（lx-music qrcDecode.js 移植） ----------------

    private const val DES_ENCRYPT = 1
    private const val DES_DECRYPT = 0

    /** QRC 固定密钥：!@#)(*$%123ZXC!@!@#)(NHL（24 字节）。 */
    private val QRC_KEY = byteArrayOf(
        0x21, 0x40, 0x23, 0x29, 0x28, 0x2a, 0x24, 0x25, 0x31, 0x32, 0x33, 0x5a,
        0x58, 0x43, 0x21, 0x40, 0x21, 0x40, 0x23, 0x29, 0x28, 0x4e, 0x48, 0x4c
    )

    /** 非标准 DES S-box（S2[23]=15、S4[53]=10 与标准 DES 不同——不可改为标准值）。 */
    private val QRC_SBOX = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
        )
    )

    private val QRC_KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val QRC_KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    )
    private val QRC_KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    )
    private val QRC_KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46,
        54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    )

    /** bitnum(a: bytes, b: bit, c: shift)——无符号 32 位语义。 */
    private fun qrcBitnum(a: ByteArray, b: Int, c: Int): Int =
        ((((a[(b / 32) * 4 + 3 - ((b % 32) / 8)].toInt() and 0xFF) ushr (7 - (b % 8))) and 1) shl c)

    private fun qrcBitnumIntr(a: Int, b: Int, c: Int): Int =
        (((a ushr (31 - b)) and 1) shl c)

    private fun qrcBitnumIntl(a: Int, b: Int, c: Int): Int =
        (((a shl b) and 0x80000000.toInt()) ushr c)

    private fun qrcSboxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    /** 初始置换：返回 [s0, s1]。 */
    private fun qrcInitialPermutation(input: ByteArray): IntArray {
        val s0 = (qrcBitnum(input, 57, 31) or qrcBitnum(input, 49, 30) or qrcBitnum(input, 41, 29) or qrcBitnum(input, 33, 28) or
            qrcBitnum(input, 25, 27) or qrcBitnum(input, 17, 26) or qrcBitnum(input, 9, 25) or qrcBitnum(input, 1, 24) or
            qrcBitnum(input, 59, 23) or qrcBitnum(input, 51, 22) or qrcBitnum(input, 43, 21) or qrcBitnum(input, 35, 20) or
            qrcBitnum(input, 27, 19) or qrcBitnum(input, 19, 18) or qrcBitnum(input, 11, 17) or qrcBitnum(input, 3, 16) or
            qrcBitnum(input, 61, 15) or qrcBitnum(input, 53, 14) or qrcBitnum(input, 45, 13) or qrcBitnum(input, 37, 12) or
            qrcBitnum(input, 29, 11) or qrcBitnum(input, 21, 10) or qrcBitnum(input, 13, 9) or qrcBitnum(input, 5, 8) or
            qrcBitnum(input, 63, 7) or qrcBitnum(input, 55, 6) or qrcBitnum(input, 47, 5) or qrcBitnum(input, 39, 4) or
            qrcBitnum(input, 31, 3) or qrcBitnum(input, 23, 2) or qrcBitnum(input, 15, 1) or qrcBitnum(input, 7, 0))
        val s1 = (qrcBitnum(input, 56, 31) or qrcBitnum(input, 48, 30) or qrcBitnum(input, 40, 29) or qrcBitnum(input, 32, 28) or
            qrcBitnum(input, 24, 27) or qrcBitnum(input, 16, 26) or qrcBitnum(input, 8, 25) or qrcBitnum(input, 0, 24) or
            qrcBitnum(input, 58, 23) or qrcBitnum(input, 50, 22) or qrcBitnum(input, 42, 21) or qrcBitnum(input, 34, 20) or
            qrcBitnum(input, 26, 19) or qrcBitnum(input, 18, 18) or qrcBitnum(input, 10, 17) or qrcBitnum(input, 2, 16) or
            qrcBitnum(input, 60, 15) or qrcBitnum(input, 52, 14) or qrcBitnum(input, 44, 13) or qrcBitnum(input, 36, 12) or
            qrcBitnum(input, 28, 11) or qrcBitnum(input, 20, 10) or qrcBitnum(input, 12, 9) or qrcBitnum(input, 4, 8) or
            qrcBitnum(input, 62, 7) or qrcBitnum(input, 54, 6) or qrcBitnum(input, 46, 5) or qrcBitnum(input, 38, 4) or
            qrcBitnum(input, 30, 3) or qrcBitnum(input, 22, 2) or qrcBitnum(input, 14, 1) or qrcBitnum(input, 6, 0))
        return intArrayOf(s0, s1)
    }

    /** 逆置换：s0/s1 → 8 字节。 */
    private fun qrcInversePermutation(s0: Int, s1: Int): ByteArray {
        val out = ByteArray(8)
        out[3] = (qrcBitnumIntr(s1, 7, 7) or qrcBitnumIntr(s0, 7, 6) or qrcBitnumIntr(s1, 15, 5) or qrcBitnumIntr(s0, 15, 4) or qrcBitnumIntr(s1, 23, 3) or qrcBitnumIntr(s0, 23, 2) or qrcBitnumIntr(s1, 31, 1) or qrcBitnumIntr(s0, 31, 0)).toByte()
        out[2] = (qrcBitnumIntr(s1, 6, 7) or qrcBitnumIntr(s0, 6, 6) or qrcBitnumIntr(s1, 14, 5) or qrcBitnumIntr(s0, 14, 4) or qrcBitnumIntr(s1, 22, 3) or qrcBitnumIntr(s0, 22, 2) or qrcBitnumIntr(s1, 30, 1) or qrcBitnumIntr(s0, 30, 0)).toByte()
        out[1] = (qrcBitnumIntr(s1, 5, 7) or qrcBitnumIntr(s0, 5, 6) or qrcBitnumIntr(s1, 13, 5) or qrcBitnumIntr(s0, 13, 4) or qrcBitnumIntr(s1, 21, 3) or qrcBitnumIntr(s0, 21, 2) or qrcBitnumIntr(s1, 29, 1) or qrcBitnumIntr(s0, 29, 0)).toByte()
        out[0] = (qrcBitnumIntr(s1, 4, 7) or qrcBitnumIntr(s0, 4, 6) or qrcBitnumIntr(s1, 12, 5) or qrcBitnumIntr(s0, 12, 4) or qrcBitnumIntr(s1, 20, 3) or qrcBitnumIntr(s0, 20, 2) or qrcBitnumIntr(s1, 28, 1) or qrcBitnumIntr(s0, 28, 0)).toByte()
        out[7] = (qrcBitnumIntr(s1, 3, 7) or qrcBitnumIntr(s0, 3, 6) or qrcBitnumIntr(s1, 11, 5) or qrcBitnumIntr(s0, 11, 4) or qrcBitnumIntr(s1, 19, 3) or qrcBitnumIntr(s0, 19, 2) or qrcBitnumIntr(s1, 27, 1) or qrcBitnumIntr(s0, 27, 0)).toByte()
        out[6] = (qrcBitnumIntr(s1, 2, 7) or qrcBitnumIntr(s0, 2, 6) or qrcBitnumIntr(s1, 10, 5) or qrcBitnumIntr(s0, 10, 4) or qrcBitnumIntr(s1, 18, 3) or qrcBitnumIntr(s0, 18, 2) or qrcBitnumIntr(s1, 26, 1) or qrcBitnumIntr(s0, 26, 0)).toByte()
        out[5] = (qrcBitnumIntr(s1, 1, 7) or qrcBitnumIntr(s0, 1, 6) or qrcBitnumIntr(s1, 9, 5) or qrcBitnumIntr(s0, 9, 4) or qrcBitnumIntr(s1, 17, 3) or qrcBitnumIntr(s0, 17, 2) or qrcBitnumIntr(s1, 25, 1) or qrcBitnumIntr(s0, 25, 0)).toByte()
        out[4] = (qrcBitnumIntr(s1, 0, 7) or qrcBitnumIntr(s0, 0, 6) or qrcBitnumIntr(s1, 8, 5) or qrcBitnumIntr(s0, 8, 4) or qrcBitnumIntr(s1, 16, 3) or qrcBitnumIntr(s0, 16, 2) or qrcBitnumIntr(s1, 24, 1) or qrcBitnumIntr(s0, 24, 0)).toByte()
        return out
    }

    /** Feistel 轮函数。 */
    private fun qrcDesF(state: Int, key: ByteArray): Int {
        val t1 = (qrcBitnumIntl(state, 31, 0) or ((state and 0xF0000000.toInt()) ushr 1) or qrcBitnumIntl(state, 4, 5) or
            qrcBitnumIntl(state, 3, 6) or ((state and 0x0F000000) ushr 3) or qrcBitnumIntl(state, 8, 11) or
            qrcBitnumIntl(state, 7, 12) or ((state and 0x00F00000) ushr 5) or qrcBitnumIntl(state, 12, 17) or
            qrcBitnumIntl(state, 11, 18) or ((state and 0x000F0000) ushr 7) or qrcBitnumIntl(state, 16, 23))
        val t2 = (qrcBitnumIntl(state, 15, 0) or ((state and 0x0000F000) shl 15) or qrcBitnumIntl(state, 20, 5) or
            qrcBitnumIntl(state, 19, 6) or ((state and 0x00000F00) shl 13) or qrcBitnumIntl(state, 24, 11) or
            qrcBitnumIntl(state, 23, 12) or ((state and 0x000000F0) shl 11) or qrcBitnumIntl(state, 28, 17) or
            qrcBitnumIntl(state, 27, 18) or ((state and 0x0000000F) shl 9) or qrcBitnumIntl(state, 0, 23))
        val lrgstate = intArrayOf(
            (t1 ushr 24) and 0xFF, (t1 ushr 16) and 0xFF, (t1 ushr 8) and 0xFF,
            (t2 ushr 24) and 0xFF, (t2 ushr 16) and 0xFF, (t2 ushr 8) and 0xFF
        )
        for (i in 0 until 6) {
            lrgstate[i] = lrgstate[i] xor (key[i].toInt() and 0xFF)
        }
        val s = ((QRC_SBOX[0][qrcSboxBit(lrgstate[0] ushr 2)] shl 28) or
            (QRC_SBOX[1][qrcSboxBit(((lrgstate[0] and 0x03) shl 4) or (lrgstate[1] ushr 4))] shl 24) or
            (QRC_SBOX[2][qrcSboxBit(((lrgstate[1] and 0x0F) shl 2) or (lrgstate[2] ushr 6))] shl 20) or
            (QRC_SBOX[3][qrcSboxBit(lrgstate[2] and 0x3F)] shl 16) or
            (QRC_SBOX[4][qrcSboxBit(lrgstate[3] ushr 2)] shl 12) or
            (QRC_SBOX[5][qrcSboxBit(((lrgstate[3] and 0x03) shl 4) or (lrgstate[4] ushr 4))] shl 8) or
            (QRC_SBOX[6][qrcSboxBit(((lrgstate[4] and 0x0F) shl 2) or (lrgstate[5] ushr 6))] shl 4) or
            QRC_SBOX[7][qrcSboxBit(lrgstate[5] and 0x3F)])
        return (qrcBitnumIntl(s, 15, 0) or qrcBitnumIntl(s, 6, 1) or qrcBitnumIntl(s, 19, 2) or qrcBitnumIntl(s, 20, 3) or
            qrcBitnumIntl(s, 28, 4) or qrcBitnumIntl(s, 11, 5) or qrcBitnumIntl(s, 27, 6) or qrcBitnumIntl(s, 16, 7) or
            qrcBitnumIntl(s, 0, 8) or qrcBitnumIntl(s, 14, 9) or qrcBitnumIntl(s, 22, 10) or qrcBitnumIntl(s, 25, 11) or
            qrcBitnumIntl(s, 4, 12) or qrcBitnumIntl(s, 17, 13) or qrcBitnumIntl(s, 30, 14) or qrcBitnumIntl(s, 9, 15) or
            qrcBitnumIntl(s, 1, 16) or qrcBitnumIntl(s, 7, 17) or qrcBitnumIntl(s, 23, 18) or qrcBitnumIntl(s, 13, 19) or
            qrcBitnumIntl(s, 31, 20) or qrcBitnumIntl(s, 26, 21) or qrcBitnumIntl(s, 2, 22) or qrcBitnumIntl(s, 8, 23) or
            qrcBitnumIntl(s, 18, 24) or qrcBitnumIntl(s, 12, 25) or qrcBitnumIntl(s, 29, 26) or qrcBitnumIntl(s, 5, 27) or
            qrcBitnumIntl(s, 21, 28) or qrcBitnumIntl(s, 10, 29) or qrcBitnumIntl(s, 3, 30) or qrcBitnumIntl(s, 24, 31))
    }

    /** 单次 DES 加/解密一个 8 字节分组。 */
    private fun qrcDesCrypt(input: ByteArray, schedule: Array<ByteArray>): ByteArray {
        var (s0, s1) = qrcInitialPermutation(input)
        for (i in 0 until 15) {
            val prev = s1
            s1 = qrcDesF(s1, schedule[i]) xor s0
            s0 = prev
        }
        s0 = qrcDesF(s1, schedule[15]) xor s0
        return qrcInversePermutation(s0, s1)
    }

    /** 生成 16 轮子密钥。 */
    private fun qrcKeySchedule(key: ByteArray, mode: Int): Array<ByteArray> {
        val schedule = Array(16) { ByteArray(6) }
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or qrcBitnum(key, QRC_KEY_PERM_C[i], 31 - i)
            d = d or qrcBitnum(key, QRC_KEY_PERM_D[i], 31 - i)
        }
        for (i in 0 until 16) {
            c = (((c shl QRC_KEY_RND_SHIFT[i]) or (c ushr (28 - QRC_KEY_RND_SHIFT[i]))) and 0xFFFFFFF0.toInt())
            d = (((d shl QRC_KEY_RND_SHIFT[i]) or (d ushr (28 - QRC_KEY_RND_SHIFT[i]))) and 0xFFFFFFF0.toInt())
            val togen = if (mode == DES_DECRYPT) 15 - i else i
            for (j in 0 until 24) {
                schedule[togen][j / 8] = (schedule[togen][j / 8].toInt() or
                    qrcBitnumIntr(c, QRC_KEY_COMPRESSION[j], 7 - (j % 8))).toByte()
            }
            for (j in 24 until 48) {
                schedule[togen][j / 8] = (schedule[togen][j / 8].toInt() or
                    qrcBitnumIntr(d, QRC_KEY_COMPRESSION[j] - 27, 7 - (j % 8))).toByte()
            }
        }
        return schedule
    }

    /** 3DES（EDE3）密钥编排。 */
    private fun tripledesKeySetup(key: ByteArray, mode: Int): Array<Array<ByteArray>> =
        if (mode == DES_ENCRYPT) {
            arrayOf(
                qrcKeySchedule(key.copyOfRange(0, 8), DES_ENCRYPT),
                qrcKeySchedule(key.copyOfRange(8, 16), DES_DECRYPT),
                qrcKeySchedule(key.copyOfRange(16, 24), DES_ENCRYPT)
            )
        } else {
            arrayOf(
                qrcKeySchedule(key.copyOfRange(16, 24), DES_DECRYPT),
                qrcKeySchedule(key.copyOfRange(8, 16), DES_ENCRYPT),
                qrcKeySchedule(key.copyOfRange(0, 8), DES_DECRYPT)
            )
        }

    /** 3DES 加/解密一个 8 字节分组。 */
    private fun tripledesCrypt(input: ByteArray, schedule: Array<Array<ByteArray>>, output: ByteArray) {
        val buf = qrcDesCrypt(input, schedule[0])
        val out = qrcDesCrypt(buf, schedule[1])
        val buf2 = qrcDesCrypt(out, schedule[2])
        System.arraycopy(buf2, 0, output, 0, 8)
    }

    /** tx 封面：albumMid 拼接（无网络请求）。 */
    private fun txPic(albumMid: String): String? {
        if (albumMid.isBlank()) return null
        return "https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumMid}.jpg"
    }

    // ---------------- wy（网易云） ----------------

    /**
     * wy 歌词：老接口 GET（无加密）。
     *
     * v1.5.1 r36：原 eapi 接口（interface3.music.163.com/eapi/song/lyric/v1）
     * 在真机网络返回空响应（content-length: 0，服务端拒绝），换老接口
     * music.163.com/api/song/lyric（GET 无加密，实测秒回）。
     */
    private fun wyLyric(songId: String): String? {
        val body = httpGet(
            "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1",
            mapOf(
                "Referer" to "https://music.163.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            )
        )
        val lrc = JSONObject(body).optJSONObject("lrc")?.optString("lyric") ?: return null
        return lrc.takeIf { it.isNotBlank() }
    }

    /** wy 封面：weapi song/detail 太重，直接用 eapi 同款接口取 song/detail。 */
    private fun wyPic(songId: String): String? {
        // 网易云歌曲详情（含封面）——用公开的 song/detail 接口
        val body = httpGet(
            "https://music.163.com/api/song/detail?id=$songId&ids=[$songId]",
            mapOf(
                "Referer" to "https://music.163.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            )
        )
        val songs = JSONObject(body).optJSONArray("songs") ?: return null
        val picUrl = songs.optJSONObject(0)?.optJSONObject("album")?.optString("picUrl")
        return picUrl?.takeIf { it.isNotBlank() }
    }

    // ---------------- mg（咪咕） ----------------

    /**
     * mg 歌词：resourceinfo → lrcUrl 直取（明文 LRC）。
     *
     * v1.5.1 r36：resourceId 必须传 songId（lx-music getMusicInfo(songmid)
     * 传的就是 songmid=songId）——传 copyrightId 返回空 resource 数组。
     */
    private fun mgLyric(songId: String): String? {
        val info = mgResourceInfo(songId) ?: return null
        val lrcUrl = info.optString("lrcUrl")
        if (lrcUrl.isBlank()) return null
        return httpGet(
            lrcUrl,
            mapOf(
                "Referer" to "https://app.c.nf.migu.cn/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36"
            )
        )
    }

    /**
     * mg 封面：resourceinfo 的 albumImgs 数组。
     *
     * v1.5.1 r36：响应字段是 albumImgs 数组（imgSizeType 01/02/03，03
     * 最大）——不是 img1/img2/img3 平铺字段（实测恒空）。
     */
    private fun mgPic(songId: String): String? {
        val info = mgResourceInfo(songId) ?: return null
        val imgs = info.optJSONArray("albumImgs") ?: return null
        // imgSizeType 03 最大尺寸；兜底取最后一个
        var url = ""
        for (i in 0 until imgs.length()) {
            val o = imgs.optJSONObject(i) ?: continue
            val img = o.optString("img")
            if (img.isBlank()) continue
            url = img
            if (o.optString("imgSizeType") == "03") break
        }
        return url.takeIf { it.isNotBlank() }
    }

    /** mg resourceinfo（POST resourceId=songId）。 */
    private fun mgResourceInfo(songId: String): JSONObject? {
        val resp = httpPostForm(
            "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2",
            mapOf("resourceId" to songId)
        )
        val o = JSONObject(resp)
        val data = o.optJSONArray("resource") ?: o.optJSONObject("data")?.optJSONArray("resource")
            ?: return null
        return data.optJSONObject(0)
    }

    // ---------------- 工具 ----------------

    /** "mm:ss" → 秒（v1.5.1 r36：lx-music getIntv 契约单位是秒）。 */
    private fun intervalToSec(interval: String): Long {
        if (interval.isBlank()) return 0
        val parts = interval.split(":")
        return try {
            if (parts.size == 2) {
                parts[0].toLong() * 60 + parts[1].toDouble().toLong()
            } else 0
        } catch (e: Exception) { 0 }
    }
}

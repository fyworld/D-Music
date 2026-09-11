package com.solara.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地歌曲封面提取（v1.3.9；v1.4.0 增强在线匹配）：
 * 1. 内存缓存 / 磁盘持久化（Store.localCoverUrl）的在线封面 URL
 * 2. 音频文件内嵌封面（EMBEDDED_PICTURE）
 * 3. 兜底：同目录同名 .jpg/.png 伴生图片
 *
 * 结果（含"确认无封面"的负缓存）存 LruCache，列表滚动不重复解码。
 * revision 自增触发 UI 重新加载（批量匹配封面/重命名后调用 bumpRevision）。
 * 所有方法设计为在 IO 线程调用；在线搜索匹配（matchCover）为 suspend。
 */
object LocalCoverExtractor {

    /** key=source:id → 封面 Bitmap；负缓存哨兵。 */
    private const val NO_COVER = "no_cover"
    private val cache = LruCache<String, Any>(48) // 约 48 张 300x300 封面

    /** 每次封面数据可能变化时自增，UI 侧观察它触发重新加载。 */
    val revision = MutableStateFlow(0L)

    /** 是否为本地导入（扫描）的歌曲。 */
    fun isLocalSong(song: Song): Boolean =
        song.source == "local" && song.id.startsWith("local:")

    /** 本地歌曲对应的原始文件名（含扩展名）。 */
    fun localFileName(song: Song): String =
        if (isLocalSong(song)) song.id.removePrefix("local:") else ""

    /** 封面数据变化后调用：清全部缓存并广播 UI 刷新。 */
    fun bumpRevision() {
        synchronized(cache) { cache.evictAll() }
        revision.value = revision.value + 1
    }

    /**
     * 获取本地歌曲封面；无封面返回 null。
     * 顺序：内存缓存 → 磁盘 URL 缓存 → 内嵌图 → 同名图片文件。
     * （在线搜索匹配由 UI 层异步兜底，避免列表滚动时打接口）
     */
    fun getCover(context: Context, song: Song): Bitmap? {
        val key = "${song.source}:${song.id}"
        synchronized(cache) {
            when (val cached = cache.get(key)) {
                NO_COVER -> return null
                is Bitmap -> return cached
                else -> { /* 未缓存，继续提取 */ }
            }
        }
        val ctx = context.applicationContext
        val bitmap = extractFromUrlCache(ctx, song)
            ?: extractEmbedded(ctx, song)
            ?: extractSiblingImage(ctx, song)
        synchronized(cache) {
            if (bitmap != null) cache.put(key, bitmap)
            else cache.put(key, NO_COVER)
        }
        return bitmap
    }

    /** 清缓存（重命名/删除文件后调用）。 */
    fun invalidate(song: Song) {
        synchronized(cache) { cache.remove("${song.source}:${song.id}") }
    }

    /**
     * 在线搜索并匹配封面（v1.4.0）：
     * 用「歌手 歌名」调聚合接口搜索，命中同名（且歌手相近）的结果则取其
     * picId 解析封面直链。返回封面 URL；未命中返回 null。
     * 结果写入 Store 的本地封面缓存，下次直接命中磁盘缓存。
     */
    suspend fun matchCover(song: Song): String? {
        val query = buildString {
            if (song.artistName.isNotBlank() && song.artistName != "未知歌手") {
                append(song.artistName).append(' ')
            }
            append(song.name)
        }.trim()
        if (query.isBlank()) return null
        val src = Store.settings.value.source.ifBlank { "netease" }
        val results = runCatching { MusicApi.search(src, query, page = 1, count = 10) }
            .getOrNull() ?: return null
        // 精确匹配：歌名相等且歌手互含（本地文件名解析的歌手常不完整）
        val hit = results.firstOrNull { r ->
            r.name == song.name &&
                (song.artistName.isBlank() ||
                    r.artistName.contains(song.artistName, ignoreCase = true) ||
                    song.artistName.contains(r.artistName, ignoreCase = true))
        } ?: return null
        val url = runCatching { MusicApi.fetchPicUrl(hit) }.getOrNull() ?: return null
        Store.saveLocalCoverUrl(song, url)
        return url
    }

    // ---- URL 磁盘缓存（Store 持久化） ----

    private fun extractFromUrlCache(context: Context, song: Song): Bitmap? {
        val url = Store.localCoverUrl(song) ?: return null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    // ---- 内嵌封面 ----

    private fun extractEmbedded(context: Context, song: Song): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            // v1.4.0 修复：fd 必须 close，否则 FUSE 层认为文件被占用，
            // 导致 MediaStore 重命名/删除持续失败（"文件被占用"）
            val fd = openFileDescriptor(context, song) ?: return@runCatching null
            try {
                retriever.setDataSource(fd.fileDescriptor)
                val bytes = retriever.embeddedPicture ?: return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                runCatching { fd.close() }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    // ---- 同名图片兜底 ----

    private fun extractSiblingImage(context: Context, song: Song): Bitmap? {
        val fileName = localFileName(song)
        if (fileName.isEmpty()) return null
        val base = fileName.substringBeforeLast('.', fileName)
        val candidates = listOf("$base.jpg", "$base.jpeg", "$base.png")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore 查同名图片（DATA LIKE 匹配 D_Music 目录）
            val selection =
                "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME}=?"
            for (name in candidates) {
                val uri = runCatching {
                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID),
                        selection,
                        arrayOf("%/D_Music/%", name),
                        null
                    )?.use { c ->
                        if (c.moveToFirst()) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            .buildUpon()
                            .appendPath(c.getLong(0).toString())
                            .build()
                        else null
                    }
                }.getOrNull() ?: continue
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp != null) return bmp
            }
        } else {
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
                "D_Music"
            )
            for (name in candidates) {
                val f = File(dir, name)
                if (f.exists()) {
                    val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
                    if (bmp != null) return bmp
                }
            }
        }
        return null
    }

    // ---- 文件定位（与 DownloadManager.findLocalPlayableUri 同策略） ----

    private fun openFileDescriptor(
        context: Context,
        song: Song
    ): android.content.res.AssetFileDescriptor? {
        val fileName = localFileName(song)
        if (fileName.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // v1.4.2：导入歌曲不限 D_Music，全库按文件名精确查
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
            val uri = runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    selection,
                    arrayOf(fileName),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        .buildUpon()
                        .appendPath(c.getLong(0).toString())
                        .build()
                    else null
                }
            }.getOrNull() ?: return null
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } else {
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
                "D_Music"
            )
            val f = File(dir, fileName)
            if (f.exists()) context.contentResolver.openAssetFileDescriptor(
                android.net.Uri.fromFile(f), "r"
            ) else null
        }
    }
}

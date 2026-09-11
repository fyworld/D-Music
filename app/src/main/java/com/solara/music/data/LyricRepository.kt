package com.solara.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词仓库（v1.4.9）：
 * - 本地歌曲（local:）：内嵌歌词（USLT/LYRICS 标签）→ 磁盘缓存 → 在线搜索匹配
 * - 在线歌曲：API 查词 → 成功后写磁盘缓存（离线可读）
 *
 * 所有方法为 suspend，内部已切 IO 线程。
 */
object LyricRepository {

    /**
     * 获取歌曲歌词（LRC 原文），失败/无歌词返回 null。
     * 顺序：磁盘缓存 → 本地内嵌（USLT / 同名 .lrc）→ 在线（API 或搜索匹配）。
     * 在线拿到的歌词写磁盘缓存，并尝试嵌入本地歌曲文件
     * （MP3 USLT / FLAC 伴生 .lrc，v1.4.10），其他播放器也能显示。
     */
    suspend fun fetchLyric(context: Context, song: Song): String? =
        withContext(Dispatchers.IO) {
            // 1) 磁盘缓存（离线首选）
            Store.cachedLyric(song)?.let { return@withContext it }

            // 2) 本地歌曲：读内嵌歌词标签 / 同名 .lrc 伴生文件
            if (LocalCoverExtractor.isLocalSong(song)) {
                val embedded = readEmbeddedLyric(context, song)
                    ?: readSidecarLrc(context, song)
                if (!embedded.isNullOrBlank()) {
                    Store.saveCachedLyric(song, embedded)
                    return@withContext embedded
                }
            }

            // 3) 在线获取
            val online = fetchOnline(context, song) ?: return@withContext null
            Store.saveCachedLyric(song, online)
            // v1.4.10：本地歌曲把歌词嵌进文件（MP3 USLT / FLAC 伴生 .lrc），
            // 失败不影响本次显示（已有缓存兜底）
            if (LocalCoverExtractor.isLocalSong(song)) {
                runCatching {
                    TagEmbedder.embedLyricInto(context, song, online)
                }
            }
            online
        }

    /** 在线获取：在线歌曲直接查 API；本地歌曲先搜索匹配再查词。 */
    private suspend fun fetchOnline(context: Context, song: Song): String? {
        // 在线歌曲：直接按 lyricId/id 查词
        if (!LocalCoverExtractor.isLocalSong(song)) {
            return runCatching { MusicApi.fetchLyric(song) }.getOrNull()
        }
        // 本地歌曲：按「歌手 歌名」搜索精确匹配，再查匹配结果的歌词
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
        // 精确匹配：歌名相等且歌手互含（与封面匹配同策略）
        val hit = results.firstOrNull { r ->
            r.name == song.name &&
                (song.artistName.isBlank() ||
                    r.artistName.contains(song.artistName, ignoreCase = true) ||
                    song.artistName.contains(r.artistName, ignoreCase = true))
        } ?: return null
        return runCatching { MusicApi.fetchLyric(hit) }.getOrNull()
    }

    /** 读音频文件内嵌歌词（MP3 的 ID3v2 USLT 帧），失败返回 null。 */
    private fun readEmbeddedLyric(context: Context, song: Song): String? = runCatching {
        val fd = openLocalFileDescriptor(context, song) ?: return@runCatching null
        try {
            // 拷到临时文件供 mp3agic 读取（fd 直接读部分格式不支持）
            val tmp = java.io.File.createTempFile(
                "dm_lyric", ".tmp", context.applicationContext.cacheDir
            )
            try {
                java.io.FileOutputStream(tmp).use { out ->
                    fd.createInputStream().use { it.copyTo(out) }
                }
                readUslt(tmp)
            } finally {
                runCatching { tmp.delete() }
            }
        } finally {
            runCatching { fd.close() }
        }
    }.getOrNull()

    /** mp3agic 读 ID3v2 USLT 帧歌词。 */
    private fun readUslt(file: java.io.File): String? = runCatching {
        val mp3 = com.mpatric.mp3agic.Mp3File(file)
        if (!mp3.hasId3v2Tag()) return@runCatching null
        mp3.id3v2Tag.lyrics?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 读歌曲同目录同名 .lrc 伴生文件（FLAC 等格式的歌词载体，v1.4.10）。
     * 需要所有文件访问权限直读文件系统；失败返回 null。
     */
    private fun readSidecarLrc(context: Context, song: Song): String? = runCatching {
        val fileName = LocalCoverExtractor.localFileName(song)
        if (fileName.isEmpty()) return@runCatching null
        val ctx = context.applicationContext
        // 先查 MediaStore 拿音频绝对路径（DATA 列）
        val absPath = ctx.contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Audio.Media.DATA),
            "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME}=?",
            arrayOf(fileName),
            null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return@runCatching null
        val lrcFile = java.io.File(
            java.io.File(absPath).parentFile ?: return@runCatching null,
            java.io.File(absPath).nameWithoutExtension + ".lrc"
        )
        if (lrcFile.exists() && lrcFile.canRead()) {
            lrcFile.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } else null
    }.getOrNull()

    /** 定位本地歌曲文件（全库 DISPLAY_NAME 精确匹配，与封面提取同策略）。 */
    private fun openLocalFileDescriptor(
        context: Context,
        song: Song
    ): android.content.res.AssetFileDescriptor? {
        val fileName = LocalCoverExtractor.localFileName(song)
        if (fileName.isEmpty()) return null
        val ctx = context.applicationContext
        val uri = runCatching {
            ctx.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Audio.Media._ID),
                "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME}=?",
                arrayOf(fileName),
                null
            )?.use { c ->
                if (c.moveToFirst()) android.net.Uri.parse(
                    "${android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/${c.getLong(0)}"
                ) else null
            }
        }.getOrNull() ?: return null
        return runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")
        }.getOrNull()
    }
}

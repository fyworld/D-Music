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
     * 顺序：本地文件歌词（伴生 .lrc → 内嵌 USLT）→ App 磁盘缓存 → 在线。
     *
     * v1.5.1 r46：**本地文件歌词优先于 App 缓存**——用户可能用外部编辑器
     * 手动改过 .lrc / 其他播放器写过 USLT（或校准/编辑后文件是新版），
     * App 缓存里可能还是旧版。文件系统是真实状态，缓存只是 App 内部
     * 副本。命中文件歌词后同步刷新缓存保持一致。
     * 伴生 .lrc 排在 USLT 前：.lrc 是明文可直接编辑，外部修改最常见；
     * USLT 藏在标签里一般只有 App 自己写。
     */
    suspend fun fetchLyric(context: Context, song: Song): String? =
        withContext(Dispatchers.IO) {
            // 1) v1.5.1 r46：本地文件歌词优先（本地导入歌 + 在线下载存量歌）
            //    1a) 同名 .lrc 伴生文件（外部编辑最常见载体）
            val sidecar = readSidecarLrcForAny(context, song)
            if (!sidecar.isNullOrBlank()) {
                Store.saveCachedLyric(song, sidecar)
                return@withContext sidecar
            }
            //    1b) MP3 内嵌 USLT（其他播放器/App 自己写入的标签）
            if (isLocalFileSong(song)) {
                val embedded = readEmbeddedLyricForAny(context, song)
                if (!embedded.isNullOrBlank()) {
                    Store.saveCachedLyric(song, embedded)
                    return@withContext embedded
                }
            }

            // 2) 磁盘缓存
            val cached = Store.cachedLyric(song)
            if (cached != null) {
                // v1.5.1 r42：缓存命中但本地文件缺歌词 → 补嵌入（存量 FLAC
                // 伴生 .lrc 缺失的修复点——此前缓存命中直接返回，嵌入
                // 逻辑永远不再执行）
                if (isLocalFileSong(song)) {
                    runCatching { backfillLyricToFile(context, song, cached) }
                }
                return@withContext cached
            }

            // 3) 在线获取
            val online = fetchOnline(context, song) ?: return@withContext null
            Store.saveCachedLyric(song, online)
            // v1.4.10：本地歌曲把歌词嵌进文件（MP3 USLT / FLAC 伴生 .lrc），
            // 失败不影响本次显示（已有缓存兜底）
            if (isLocalFileSong(song)) {
                runCatching {
                    TagEmbedder.embedLyricInto(context, song, online)
                }
            }
            online
        }

    /**
     * v1.5.1 r46：读 MP3 内嵌 USLT（本地导入歌 + 在线下载存量歌通用）。
     * - 本地导入歌：readEmbeddedLyric（原有）
     * - 在线下载存量歌：定位本地文件后读 USLT
     * 无文件/读不到返回 null。
     */
    private fun readEmbeddedLyricForAny(context: Context, song: Song): String? {
        if (LocalCoverExtractor.isLocalSong(song)) {
            return readEmbeddedLyric(context, song)
        }
        // 在线下载存量歌：定位本地文件（找不到=没下载过，纯在线歌）
        val absPath = runCatching {
            DownloadManager.findLocalFileAbsPath(context, song)
        }.getOrNull() ?: return null
        if (!absPath.endsWith(".mp3", ignoreCase = true)) return null
        return runCatching {
            val tmp = java.io.File.createTempFile("dm_uslt", ".tmp", context.cacheDir)
            try {
                java.io.File(absPath).inputStream().use { input ->
                    java.io.FileOutputStream(tmp).use { input.copyTo(it) }
                }
                readUslt(tmp)
            } finally {
                runCatching { tmp.delete() }
            }
        }.getOrNull()
    }

    /**
     * v1.5.1 r46：读同名 .lrc 伴生文件（本地导入歌 + 在线下载存量歌通用）。
     * - 本地导入歌：readSidecarLrc（按文件名查 MediaStore 拿 DATA 路径）
     * - 在线下载存量歌：findLocalFileAbsPath 定位后读同名 .lrc
     * 无文件/读不到返回 null。
     */
    private fun readSidecarLrcForAny(context: Context, song: Song): String? {
        if (LocalCoverExtractor.isLocalSong(song)) {
            return readSidecarLrc(context, song)
        }
        // 在线下载存量歌：定位本地文件（找不到=没下载过，纯在线歌）
        val absPath = runCatching {
            DownloadManager.findLocalFileAbsPath(context, song)
        }.getOrNull() ?: return null
        return runCatching {
            val lrcFile = java.io.File(
                java.io.File(absPath).parentFile ?: return null,
                java.io.File(absPath).nameWithoutExtension + ".lrc"
            )
            if (lrcFile.exists() && lrcFile.canRead()) {
                lrcFile.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }

    /**
     * v1.5.1 r42：歌曲是否有对应的本地音频文件（本地导入或在线已下载）。
     * 在线下载存量歌（id 非 local: 但文件已落盘）也算——用文件定位探查。
     */
    private fun isLocalFileSong(song: Song): Boolean {
        if (LocalCoverExtractor.isLocalSong(song)) return true
        // 在线下载歌：source 是在线源码（netease/kw/…），文件在 D_Music
        return song.source != "local" && song.id.isNotBlank()
    }

    /**
     * v1.5.1 r42：缓存命中后补嵌入——本地文件缺歌词时把缓存歌词写进文件。
     * - 本地导入歌：embedLyricInto（MP3 USLT / FLAC 伴生 .lrc）
     * - 在线下载存量歌：定位本地文件（findLocalFileAbsPath）后同样处理
     * 已有歌词（USLT/伴生 .lrc）时跳过（embedLyricInto 内部不查重，
     * 这里先探查避免重复嵌入/重复写伴生文件）
     */
    private suspend fun backfillLyricToFile(context: Context, song: Song, lrc: String) {
        if (lrc.isBlank()) return
        val ctx = context.applicationContext
        if (LocalCoverExtractor.isLocalSong(song)) {
            // 已有内嵌/伴生歌词 → 不重复写
            if (!readEmbeddedLyric(ctx, song).isNullOrBlank()) return
            if (!readSidecarLrc(ctx, song).isNullOrBlank()) return
            TagEmbedder.embedLyricInto(ctx, song, lrc)
            return
        }
        // 在线下载存量歌：定位本地文件
        val absPath = DownloadManager.findLocalFileAbsPath(ctx, song) ?: return
        if (absPath.endsWith(".flac", ignoreCase = true)) {
            // FLAC：已有伴生 .lrc → 不重复写
            val sidecar = java.io.File(
                java.io.File(absPath).parentFile ?: return,
                java.io.File(absPath).nameWithoutExtension + ".lrc"
            )
            if (sidecar.exists()) return
            TagEmbedder.writeSidecarLrc(absPath, lrc)
        } else if (absPath.endsWith(".mp3", ignoreCase = true)) {
            // MP3：已有 USLT → 不重复写（拷临时文件读标签）
            val tmp = java.io.File.createTempFile("dm_lyrchk", ".tmp", ctx.cacheDir)
            try {
                ctx.contentResolver.openInputStream(
                    android.net.Uri.parse(
                        DownloadManager.findLocalPlayableUri(ctx, song) ?: return
                    )
                )?.use { input ->
                    java.io.FileOutputStream(tmp).use { input.copyTo(it) }
                }
                val mp3 = com.mpatric.mp3agic.Mp3File(tmp)
                if (mp3.hasId3v2Tag() && !mp3.id3v2Tag.lyrics.isNullOrBlank()) return
                // 无 USLT → 嵌入（embedLyricInto 按文件名定位，与下载链路同款）
                TagEmbedder.embedLyricInto(ctx, song, lrc)
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    /** 在线获取：在线歌曲直接查 API；本地歌曲先搜索匹配再查词。 */
    private suspend fun fetchOnline(context: Context, song: Song): String? {
        // 在线歌曲：直接按 lyricId/id 查词。
        // v1.4.58 第六轮：混合记录（在线下载歌改名后 id=local:xxx）的
        // lyricId 仍是有效 API id，同样直查（比搜索匹配准）
        if (!LocalCoverExtractor.isLocalSong(song) || song.source != "local") {
            // v1.5.1 r35：平台直连搜的歌（kw/kg/tx/wy/mg 源码）——GD API
            // 不支持这些源码的 lyric 查询（实测 "Value of source is not
            // supported"）。走平台官方接口（与 lx-music 内置 musicSdk 同源），
            // 失败回落自定义源脚本（脚本 lyric 依赖 extraCache，取歌后才有）
            if (isLxPlatformSource(song.source)) {
                val fromPlatform = runCatching { PlatformMediaApi.fetchLyric(song) }.getOrNull()
                if (!fromPlatform.isNullOrBlank()) return fromPlatform
                val fromScript = runCatching {
                    com.solara.music.customsource.CustomSourceManager.getLyric(song)
                }.getOrNull()
                if (!fromScript.isNullOrBlank()) return fromScript
            }
            return runCatching { MusicApi.fetchLyric(song) }.getOrNull()
        }
        // 本地歌曲：自动精确匹配后查词
        val hit = matchLyricOnline(song) ?: return null
        // v1.5.1 r42：匹配候选可能是 lx 源码（r40 统一路由回落平台直连
        // 搜出的 kw/kg/tx/wy/mg）——GD API 对这些源码取词必失败，改走
        // fetchLyricBySource 统一取词（平台直连优先，与下载歌词对话框同款）
        return fetchLyricBySource(hit)
    }

    /** v1.5.1 r35：是否平台直连源码（lx 五平台）——GD API 不支持这些源的 lyric/pic。 */
    private fun isLxPlatformSource(source: String): Boolean =
        source == "kw" || source == "kg" || source == "tx" || source == "wy" || source == "mg"

    /**
     * v1.4.39：本地歌曲自动精确匹配在线歌曲（歌名+歌手搜索 → 歌名相等且歌手互含）。
     * 供取词链路与「下载歌词」共用；无匹配返回 null。
     * v1.5.1 r40：改走统一路由 searchCandidates（聚合 tab + GD 失效源修复）。
     */
    suspend fun matchLyricOnline(song: Song): Song? {
        val query = defaultSearchQuery(song)
        if (query.isBlank()) return null
        val src = Store.settings.value.source.ifBlank { "netease" }
        val results = searchCandidates(src, query, count = 10)
        if (results.isEmpty()) return null
        // 精确匹配：歌名相等且歌手互含（与封面匹配同策略）
        return results.firstOrNull { r ->
            r.name == song.name &&
                (song.artistName.isBlank() ||
                    r.artistName.contains(song.artistName, ignoreCase = true) ||
                    song.artistName.contains(r.artistName, ignoreCase = true))
        }
    }

    /**
     * v1.5.1 r40：统一候选搜索路由（封面编辑 / 下载歌词 / 本地歌自动匹配
     * 封面歌词共用）。
     *
     * 背景（r40 双根因）：
     * ① GD API 2026-09 服务端变更后只剩 netease/joox/bilibili 三个稳定源
     *   ——kuwo/tencent/kugou/migu 的 search/url/lyric/pic 全部 400
     *   （GD 模式下设置源=源B kuwo 时候选搜索直接报错空列表）
     * ② 聚合 tab（src="all"）在自定义源优先模式下 toPlatform("all")
     *   返回 null → 直接空列表（「未找到候选」）
     *
     * 路由：
     * - lx 源码（kw/kg/tx/wy/mg）→ 平台直连（r37 已有）
     * - "all"（聚合 tab / GD 模式残留）→ 平台聚合搜索（GD API 不支持 all）
     * - 自定义源优先 + 沙箱就绪 + GD 源码有 lx 映射 → 平台直连（r37 策略；
     *   joox/bilibili 无映射 → 落到 GD 分支，这两个源 GD 侧正常）
     * - 其余（GD 模式）→ GD API 优先，失败回落平台直连（toPlatform 映射，
     *   不依赖自定义源沙箱——平台直连是纯 HTTP）
     */
    suspend fun searchCandidates(src: String, query: String, count: Int = 20): List<Song> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        // 1) lx 源码 → 平台直连
        if (PlatformMediaApi.isPlatformSource(src)) {
            return runCatching { PlatformSearchApi.search(src, q, page = 1, count = count) }
                .getOrDefault(emptyList())
        }
        // 2) 聚合（搜索页聚合 tab / GD 模式残留 "all"）→ 平台聚合
        if (src == PlatformSearchApi.SRC_ALL) {
            return runCatching {
                PlatformSearchApi.search(PlatformSearchApi.SRC_ALL, q, page = 1, count = count)
            }.getOrDefault(emptyList())
        }
        // 3) 自定义源优先模式 + GD 源码有 lx 映射 → 平台直连
        if (com.solara.music.customsource.CustomSourceManager.isPreferred &&
            com.solara.music.customsource.CustomSourceManager.sandboxReady
        ) {
            PlatformSearchApi.toPlatform(src)?.let { lxSrc ->
                return runCatching { PlatformSearchApi.search(lxSrc, q, page = 1, count = count) }
                    .getOrDefault(emptyList())
            }
        }
        // 4) GD API 优先；失败/空回落平台直连（GD 失效源兜底）
        val gd = runCatching { MusicApi.search(src, q, page = 1, count = count) }.getOrNull()
        if (!gd.isNullOrEmpty()) return gd
        val lxSrc = PlatformSearchApi.toPlatform(src) ?: return gd ?: emptyList()
        return runCatching { PlatformSearchApi.search(lxSrc, q, page = 1, count = count) }
            .getOrDefault(emptyList())
    }

    /**
     * v1.4.39：按搜索词搜索歌词候选（「下载歌词」对话框用）。
     * query 为空时回退默认搜索词（歌手 + 歌名）。
     * v1.5.1 r40：改走统一路由 searchCandidates（聚合 tab + GD 失效源修复）。
     */
    suspend fun searchLyricCandidates(song: Song, query: String): List<Song> {
        val q = query.trim().ifBlank { defaultSearchQuery(song) }
        val src = Store.settings.value.source.ifBlank { "netease" }
        return searchCandidates(src, q, count = 20)
    }

    /**
     * v1.4.39：下载指定候选的歌词并固化到本地歌曲——
     * 写磁盘缓存（本 App 显示）+ 嵌入音频文件（MP3 USLT / FLAC 伴生 .lrc，
     * 跨播放器生效）。嵌入失败不影响缓存，返回 embedded=false。
     * v1.5.1 r37：候选是 lx 源码（kw/kg/tx/wy/mg）时走平台直连取词
     * （GD API 不支持这些源码的 lyric 查询——r37-3 根因）。
     * v1.5.1 r43：**强制在线取词 + 强制覆盖嵌入**——取词永远在线
     * （fetchLyricBySource 不查缓存）；嵌入对本地导入歌和在线下载
     * 存量歌都生效（后者定位本地文件后传 fileNameOverride），
     * 重复下载即覆盖（USLT 重写 / .lrc 重写）。
     */
    suspend fun downloadLyric(
        context: Context,
        song: Song,
        candidate: Song
    ): Pair<String, Boolean>? = withContext(Dispatchers.IO) {
        // 强制在线取词（不走缓存——用户点「下载歌词」就是要重新取）
        val lrc = fetchLyricBySource(candidate)
            ?.takeIf { it.isNotBlank() } ?: return@withContext null
        Store.saveCachedLyric(song, lrc)
        // 嵌入：本地导入歌直接嵌；在线下载存量歌定位本地文件后同样嵌
        val embedded = if (LocalCoverExtractor.isLocalSong(song)) {
            runCatching { TagEmbedder.embedLyricInto(context, song, lrc) }.getOrDefault(false)
        } else {
            // v1.5.1 r43：在线下载存量歌（文件在 D_Music）——定位后强制覆盖嵌入
            val absPath = DownloadManager.findLocalFileAbsPath(context, song)
            if (absPath != null) {
                val fileName = java.io.File(absPath).name
                runCatching {
                    TagEmbedder.embedLyricInto(context, song, lrc, fileNameOverride = fileName)
                }.getOrDefault(false)
            } else false
        }
        lrc to embedded
    }

    /**
     * v1.5.1 r37：按候选源码取词——lx 源码走平台直连（GD API 不支持），
     * GD 源码走 GD API。与 fetchOnline 的 lx 分支同策略。
     */
    private suspend fun fetchLyricBySource(candidate: Song): String? {
        if (isLxPlatformSource(candidate.source)) {
            // 平台官方接口优先，失败回落自定义源脚本
            val fromPlatform = runCatching { PlatformMediaApi.fetchLyric(candidate) }.getOrNull()
            if (!fromPlatform.isNullOrBlank()) return fromPlatform
            val fromScript = runCatching {
                com.solara.music.customsource.CustomSourceManager.getLyric(candidate)
            }.getOrNull()
            if (!fromScript.isNullOrBlank()) return fromScript
            // lx 候选不走 GD API（不支持）——直接 null
            return null
        }
        return runCatching { MusicApi.fetchLyric(candidate) }.getOrNull()
    }

    /**
     * v1.4.39：歌曲是否已有歌词（缓存 / 内嵌标签 / 伴生 .lrc 任一）。
     * 供「批量下载歌词」跳过已有歌词的歌曲；在线匹配不在此列。
     */
    suspend fun hasLyric(context: Context, song: Song): Boolean = withContext(Dispatchers.IO) {
        if (!Store.cachedLyric(song).isNullOrBlank()) return@withContext true
        if (LocalCoverExtractor.isLocalSong(song)) {
            val embedded = readEmbeddedLyric(context, song)
                ?: readSidecarLrc(context, song)
            !embedded.isNullOrBlank()
        } else false
    }

    /** 默认搜索词：歌手 + 歌名（歌手未知时只用歌名）。 */
    private fun defaultSearchQuery(song: Song): String = buildString {
        if (song.artistName.isNotBlank() && song.artistName != "未知歌手") {
            append(song.artistName).append(' ')
        }
        append(song.name)
    }.trim()

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

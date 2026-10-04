package com.solara.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.5.1 r37：取歌失败自动换源（LX music 同款机制）。
 *
 * 背景：某源取不到直链（版权下架/脚本不支持该歌/接口风控）时，
 * LX music 会自动切到其它源搜同名歌取直链播放。D Music 此前
 * 直接报「暂时无法播放」——现在移植该机制：
 *
 * 1. findMusic（移植自 lx-music musicSdk/index.js findMusic）：
 *    全平台（kw/kg/tx/wy/mg）并发搜「歌名 歌手」，多维匹配
 *    （时长±5s → 歌名+歌手 → 专辑+歌手+歌名）选出各源最佳候选
 * 2. 逐源试取直链（优先模式走自定义源脚本，失败回落 GD API），
 *    拿到第一个可用直链即返回
 *
 * 使用方：PlayerManager.playAt（播放换源）、DownloadManager.enqueue
 * （下载换源）。
 */
object MusicSourceToggler {

    /** lx 五平台源码（换源候选池）。 */
    private val LX_SOURCES = listOf("kw", "kg", "tx", "wy", "mg")

    /** 换源结果：直链 + 命中的候选歌（null=全部源都失败）。 */
    data class ToggleResult(
        val url: String,
        val song: Song,
        /** 直链来自哪条链路（custom=自定义源脚本 / api=GD API）。 */
        val fromCustom: Boolean
    )

    /**
     * 取歌失败后自动换源：全平台搜同名歌 → 逐源试取直链。
     * @param quality D Music 音质（128/320/999 等）
     * @return 第一个取到直链的候选；全部失败返回 null
     */
    suspend fun toggle(
        song: Song,
        quality: String
    ): ToggleResult? = withContext(Dispatchers.IO) {
        // 1) 全平台搜同名歌（排除原源——原源已失败）
        val candidates = findMusic(song, excludeSource = song.source)
        if (candidates.isEmpty()) return@withContext null
        // 2) 逐源试取直链：优先模式先脚本（与 playAt 同策略），失败回落 GD
        val preferred = com.solara.music.customsource.CustomSourceManager.isPreferred &&
            com.solara.music.customsource.CustomSourceManager.sandboxReady
        for (candidate in candidates) {
            if (preferred) {
                val fromScript = runCatching {
                    com.solara.music.customsource.CustomSourceManager
                        .getMusicUrl(candidate.source, quality, candidate)
                }.getOrNull()
                if (!fromScript.isNullOrBlank()) {
                    return@withContext ToggleResult(fromScript, candidate, fromCustom = true)
                }
            }
            val fromApi = runCatching {
                MusicApi.resolveUrl(candidate, quality)
            }.getOrNull()
            if (!fromApi.isNullOrBlank()) {
                return@withContext ToggleResult(fromApi, candidate, fromCustom = false)
            }
        }
        null
    }

    // ---------------- findMusic（lx-music 移植） ----------------

    /**
     * 全平台搜「歌名 歌手」并按 lx-music findMusic 策略选出各源最佳候选。
     * 每个源最多返回 1 首（该源的最佳匹配），按匹配度排序。
     * @param excludeSource 排除的源码（原源已失败，不再试）
     */
    suspend fun findMusic(song: Song, excludeSource: String = ""): List<Song> {
        val query = buildString {
            if (song.artistName.isNotBlank() && song.artistName != "未知歌手") {
                append(song.artistName).append(' ')
            }
            append(song.name)
        }.trim()
        if (query.isBlank()) return emptyList()

        // 并发全平台搜索（失败平台返回空）
        val results = coroutineScope {
            LX_SOURCES.filter { it != excludeSource }.map { src ->
                async {
                    runCatching { PlatformSearchApi.search(src, query, page = 1, count = 25) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        if (results.isEmpty()) return emptyList()

        // lx-music findMusic 多维匹配（简化版：保留时长/歌名/歌手三级匹配）
        val fMusicName = filterStr(song.name)
        val fSinger = filterStr(sortSinger(song.artistName))
        val fInterval = getIntv(song.interval)

        // 每源选最佳候选：先精确（歌名+歌手+时长），再放宽
        val best = LinkedHashMap<String, Song>()
        for (r in results) {
            val rName = filterStr(r.name)
            val rSinger = filterStr(sortSinger(r.artistName))
            val rInterval = getIntv(r.interval)
            // 时长差 >5s 直接淘汰（不同版本/live 差异大）
            if (fInterval > 0 && rInterval > 0 && kotlin.math.abs(fInterval - rInterval) > 5) continue
            // 歌名必须互含（排除同名不同歌）
            if (!(fMusicName.contains(rName) || rName.contains(fMusicName))) continue
            // 歌手必须互含（排除翻唱/伴奏版）
            if (fSinger.isNotBlank() && rSinger.isNotBlank() &&
                !(fSinger.contains(rSinger) || rSinger.contains(fSinger))
            ) continue
            val existing = best[r.source]
            if (existing == null) {
                best[r.source] = r
            } else {
                // 同源多候选：歌手全等 > 歌名全等 > 时长相等，优先级高者胜
                val newScore = score(r, rName, rSinger, rInterval, fMusicName, fSinger, fInterval)
                val oldScore = score(existing, filterStr(existing.name), filterStr(sortSinger(existing.artistName)), getIntv(existing.interval), fMusicName, fSinger, fInterval)
                if (newScore > oldScore) best[r.source] = r
            }
        }
        // 排序：歌手+歌名全等的源排最前（最可信）
        return best.values.sortedByDescending { r ->
            score(r, filterStr(r.name), filterStr(sortSinger(r.artistName)), getIntv(r.interval), fMusicName, fSinger, fInterval)
        }
    }

    /** 候选打分（lx-music sortMusic 分级简化版）。 */
    private fun score(
        r: Song, rName: String, rSinger: String, rInterval: Int,
        fMusicName: String, fSinger: String, fInterval: Int
    ): Int {
        var s = 0
        if (rSinger == fSinger) s += 8
        if (rName == fMusicName) s += 4
        if (rInterval == fInterval) s += 2
        return s
    }

    /** lx-music getIntv：mm:ss / m:ss → 秒。 */
    private fun getIntv(interval: String): Int {
        if (interval.isBlank()) return 0
        var intv = 0
        var unit = 1
        interval.split(':').reversed().forEach { seg ->
            val n = seg.trim().toIntOrNull() ?: return@forEach
            intv += n * unit
            unit *= 60
        }
        return intv
    }

    /** lx-music filterStr：去空白/标点后小写（匹配用）。 */
    private fun filterStr(s: String): String =
        s.replace(Regex("[\\s'.,，&\"、()（）`~\\-<>|/\\]\\[!！]"), "").lowercase()

    /** lx-music sortSingle：多歌手按分隔符拆开排序后用、连接（归一化比较用）。 */
    private fun sortSinger(singer: String): String {
        val rxp = Regex("、|&|;|；|/|,|，|\\|")
        return if (rxp.containsMatchIn(singer)) {
            singer.split(rxp).map { it.trim() }.sorted().joinToString("、")
        } else singer
    }
}

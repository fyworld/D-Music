package com.solara.music.lyrics

/**
 * LRC 歌词解析器，与 Solara 网页版解析规则一致：
 * [mm:ss.ms] 歌词文本，支持 2-3 位毫秒。
 */
data class LrcLine(val time: Double, val text: String)

object LrcParser {

    private val LINE_PATTERN = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    fun parse(raw: String?): List<LrcLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val timed = raw.split('\n').mapNotNull { line ->
            val m = LINE_PATTERN.find(line) ?: return@mapNotNull null
            val ms = m.groupValues[3].padEnd(3, '0').take(3).toInt()
            val time = m.groupValues[1].toInt() * 60.0 +
                m.groupValues[2].toInt() +
                ms / 1000.0
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) null else LrcLine(time, text)
        }.sortedBy { it.time }
        if (timed.isNotEmpty()) return timed

        // v1.4.9：无时间轴的纯文本歌词（内嵌 USLT 常见此格式）——
        // 按行生成"伪时间轴"（每行 +1 秒），高亮随播放逐行推进，
        // 虽不精确同步但能完整显示歌词全文
        return raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, line -> LrcLine(i * 1.0, line) }
    }

    /** 二分查找当前播放位置对应的歌词行索引。 */
    fun indexOf(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val sec = positionMs / 1000.0
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].time <= sec) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

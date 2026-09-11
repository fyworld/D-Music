package com.solara.music.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 歌曲实体，与 GD 音乐台聚合 API 的搜索结果字段一一对应。
 */
data class Song(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val picId: String = "",
    val urlId: String = "",
    val lyricId: String = "",
    val source: String = "netease"
) {
    val displayName: String get() = name.ifBlank { "未知曲目" }
    val artistName: String get() = cleanArtist(artist).ifBlank { "未知歌手" }

    fun sameAs(other: Song): Boolean = id == other.id && source == other.source
}

/**
 * 清理 API 返回的歌手字段：可能形如 ["赵雷"]（JSON 数组字符串）或
 * 带方括号/引号的普通字符串，统一转为 赵雷；多位歌手用 " / " 连接。
 */
internal fun cleanArtist(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    // JSON 数组形式：["歌手1","歌手2"]
    if (s.startsWith("[")) {
        runCatching {
            val arr = JSONArray(s)
            val names = (0 until arr.length())
                .mapNotNull { i ->
                    when (val v = arr.opt(i)) {
                        is String -> v.trim()
                        is JSONObject -> v.optString("name").trim()
                        else -> null
                    }
                }
                .filter { it.isNotEmpty() }
            if (names.isNotEmpty()) return names.joinToString(" / ")
        }
    }
    // 普通字符串带引号包裹："歌手"
    return s.removeSurrounding("\"")
}

data class MusicSource(val id: String, val label: String)

object Sources {
    // v1.4.17：源显示名改为通用代号（源 A/B/C/D）——id 不变，API 调用与已存设置不受影响
    val all = listOf(
        MusicSource("netease", "源 A"),
        MusicSource("kuwo", "源 B"),
        MusicSource("joox", "源 C"),
        MusicSource("bilibili", "源 D")
    )

    fun labelOf(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}

object Qualities {
    val all = listOf(
        Quality("128", "标准", "128 kbps"),
        Quality("192", "高品", "192 kbps"),
        Quality("320", "极高", "320 kbps"),
        Quality("999", "无损", "FLAC")
    )
}

data class Quality(val value: String, val label: String, val description: String)

/** 探索雷达：可随机抽取的音乐风格（与原版 Solara 网页版一致）。 */
object ExploreGenres {
    val all = listOf(
        "流行", "摇滚", "古典音乐", "民谣", "电子", "爵士",
        "说唱", "乡村", "蓝调", "R&B", "金属", "嘻哈", "轻音乐"
    )
}

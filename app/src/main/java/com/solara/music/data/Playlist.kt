package com.solara.music.data

import java.util.UUID

/**
 * 本地歌单：自建多个歌单整理歌曲，持久化于 SharedPreferences。
 */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songs: List<Song> = emptyList()
) {
    fun contains(song: Song): Boolean = songs.any { it.sameAs(song) }
}

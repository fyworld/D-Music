package com.solara.music.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mpatric.mp3agic.Mp3File
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 音频元数据嵌入（v1.3.9）：下载完成后把封面/歌词/标题/歌手写进音频文件。
 *
 * - MP3：mp3agic 写 ID3v2 —— APIC 封面 + USLT 歌词 + 标题/歌手/专辑
 * - FLAC：手写 METADATA_BLOCK #6（PICTURE）嵌入封面（插在 STREAMINFO 之后）；
 *   歌词不嵌（VORBIS_COMMENT 重建风险高），由调用方写伴生 .lrc 文件
 *
 * v1.4.0 新增 [embedCoverInto]：对已存在的本地歌曲文件（MediaStore URI 或
 * 专属目录文件）嵌入封面，供"匹配在线封面"批量任务使用。
 *
 * 所有方法在 IO 线程调用；任何失败向上抛，由调用方回退用原文件。
 */
object TagEmbedder {

    /**
     * 按目标文件名选格式嵌入。[src] 与 [dest] 必须是不同文件（mp3agic 限制）。
     * 返回 [dest]；无封面可嵌的 FLAC 直接拷贝。
     */
    fun embed(src: File, dest: File, targetName: String, song: Song, cover: ByteArray?, lyric: String?) {
        if (targetName.endsWith(".flac", ignoreCase = true)) {
            embedFlac(src, dest, cover)
        } else {
            embedMp3(src, dest, song, cover, lyric)
        }
    }

    /**
     * 对已存在的本地歌曲文件嵌入封面（v1.4.0）。
     * - Android 10+：按文件名定位 MediaStore 条目 → 拷到临时文件 → 嵌入 →
     *   写回 URI（"wt" 模式整文件覆盖）
     * - Android 9-：专属目录直接 src→dst 重写后原子改名
     * 返回是否成功（失败不抛异常，调用方按需降级）。
     */
    fun embedCoverInto(context: Context, song: Song, cover: ByteArray?): Boolean {
        if (cover == null || cover.isEmpty()) return false
        val fileName = LocalCoverExtractor.localFileName(song)
        if (fileName.isEmpty()) return false
        val ctx = context.applicationContext

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // v1.4.2：导入歌曲不限 D_Music，全库按文件名精确查
                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
                val uri = ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    selection,
                    arrayOf(fileName),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                    ) else null
                } ?: return false

                val tmpSrc = File.createTempFile("dm_cover_src", ".tmp", ctx.cacheDir)
                val tmpDst = File.createTempFile("dm_cover_dst", ".tmp", ctx.cacheDir)
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmpSrc).use { input.copyTo(it) }
                    } ?: return false

                    embed(tmpSrc, tmpDst, fileName, song, cover, lyric = null)

                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        tmpDst.inputStream().use { it.copyTo(out) }
                    } ?: return false
                    true
                } finally {
                    runCatching { tmpSrc.delete() }
                    runCatching { tmpDst.delete() }
                }
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
                val src = File(dir, fileName)
                if (!src.exists()) return false
                val dst = File(dir, "dm_cover_tmp_${System.currentTimeMillis()}")
                try {
                    embed(src, dst, fileName, song, cover, lyric = null)
                    if (dst.exists() && src.delete()) {
                        if (!dst.renameTo(src)) {
                            dst.inputStream().use { i -> FileOutputStream(src).use { i.copyTo(it) } }
                        }
                    }
                    true
                } finally {
                    runCatching { dst.delete() }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 对已存在的本地歌曲文件嵌入歌词（v1.4.10）。
     * - MP3：ID3v2 USLT 帧写入（临时文件中转写回，同 embedCoverInto 模式）
     * - FLAC/其他：写同名 .lrc 伴生文件到歌曲所在目录
     * 返回是否成功（失败不抛异常；歌词已有缓存兜底，不影响显示）。
     */
    fun embedLyricInto(context: Context, song: Song, lrc: String): Boolean {
        if (lrc.isBlank()) return false
        val fileName = LocalCoverExtractor.localFileName(song)
        if (fileName.isEmpty()) return false
        val ctx = context.applicationContext

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
                val row = ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
                    selection,
                    arrayOf(fileName),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: "") else null
                } ?: return false
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.first
                )

                if (fileName.endsWith(".flac", ignoreCase = true)) {
                    // FLAC：写同名 .lrc 到歌曲所在目录
                    writeSidecarLrc(row.second, lrc)
                } else {
                    // MP3：临时文件中转嵌入 USLT 后写回
                    val tmpSrc = File.createTempFile("dm_lyric_src", ".tmp", ctx.cacheDir)
                    val tmpDst = File.createTempFile("dm_lyric_dst", ".tmp", ctx.cacheDir)
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tmpSrc).use { input.copyTo(it) }
                        } ?: return false
                        val mp3 = Mp3File(tmpSrc)
                        val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag
                        else com.mpatric.mp3agic.ID3v24Tag()
                        tag.lyrics = lrc
                        mp3.save(tmpDst.absolutePath)
                        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            tmpDst.inputStream().use { it.copyTo(out) }
                        } ?: return false
                        true
                    } finally {
                        runCatching { tmpSrc.delete() }
                        runCatching { tmpDst.delete() }
                    }
                }
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "D_Music")
                val src = File(dir, fileName)
                if (!src.exists()) return false
                if (fileName.endsWith(".flac", ignoreCase = true)) {
                    writeSidecarLrc(src.absolutePath, lrc)
                } else {
                    val dst = File(dir, "dm_lyric_tmp_${System.currentTimeMillis()}")
                    try {
                        val mp3 = Mp3File(src)
                        val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag
                        else com.mpatric.mp3agic.ID3v24Tag()
                        tag.lyrics = lrc
                        mp3.save(dst.absolutePath)
                        if (dst.exists() && src.delete()) {
                            if (!dst.renameTo(src)) {
                                dst.inputStream().use { i ->
                                    FileOutputStream(src).use { i.copyTo(it) }
                                }
                            }
                        }
                        true
                    } finally {
                        runCatching { dst.delete() }
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 写同名 .lrc 伴生文件（FLAC 等无法内嵌歌词的格式）。
     * [audioAbsPath] 音频绝对路径；.lrc 写到同目录同名。
     */
    private fun writeSidecarLrc(audioAbsPath: String, lrc: String): Boolean {
        if (audioAbsPath.isBlank()) return false
        return try {
            // 10+ 公共目录：经 MediaStore RELATIVE_PATH 写（insert 音频表会被扫描，
            // 写非媒体表（Download）更合适；但歌曲目录在 Music 下，直接 insert
            // 一个 .lrc 到同 RELATIVE_PATH 的 Download 表不可行——改用文件直写，
            // 有所有文件访问权限时可行；失败返回 false 走缓存兜底）
            val lrcFile = File(
                File(audioAbsPath).parentFile ?: return false,
                File(audioAbsPath).nameWithoutExtension + ".lrc"
            )
            FileOutputStream(lrcFile).use { it.write(lrc.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- MP3（ID3v2：APIC 封面 + USLT 歌词） ----------

    private fun embedMp3(src: File, dest: File, song: Song, cover: ByteArray?, lyric: String?) {
        val mp3 = Mp3File(src)
        val tag = if (mp3.hasId3v2Tag()) mp3.id3v2Tag else com.mpatric.mp3agic.ID3v24Tag()
        tag.title = song.displayName
        tag.artist = song.artistName
        if (song.album.isNotBlank()) tag.album = song.album
        cover?.let { tag.setAlbumImage(it, "image/jpeg") }
        lyric?.let { tag.lyrics = it }
        mp3.save(dest.absolutePath)
    }

    // ---------- FLAC（PICTURE block） ----------

    private fun embedFlac(src: File, dest: File, cover: ByteArray?) {
        val data = src.readBytes()
        // 校验 FLAC 魔数 + 首 block（STREAMINFO）头
        val isFlac = data.size > 8 &&
            String(data, 0, 4, Charsets.US_ASCII) == "fLaC"
        if (!isFlac || cover == null) {
            src.copyTo(dest, overwrite = true)
            return
        }
        val firstFlags = data[4].toInt() and 0xFF
        val siWasLast = (firstFlags and 0x80) != 0
        val siLen = ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or
            (data[7].toInt() and 0xFF)
        val insertAt = 8 + siLen // "fLaC"(4) + STREAMINFO 头(4) + 体
        if (insertAt > data.size) {
            src.copyTo(dest, overwrite = true)
            return
        }

        // 头部 = 魔数 + STREAMINFO；若 STREAMINFO 是最后一块，清其 last 标志
        val head = data.copyOfRange(0, insertAt)
        if (siWasLast) head[4] = (head[4].toInt() and 0x7F).toByte()
        val pic = buildPictureBlock(cover, last = siWasLast)

        FileOutputStream(dest).use {
            it.write(head)
            it.write(pic)
            it.write(data, insertAt, data.size - insertAt)
        }
    }

    /**
     * 构造 FLAC PICTURE 元数据块（front cover）。
     * 块结构：1B 头（bit7=last + type=6）+ 3B 长度 + 体。
     */
    private fun buildPictureBlock(image: ByteArray, mime: String = "image/jpeg", last: Boolean): ByteArray {
        val mimeB = mime.toByteArray(Charsets.US_ASCII)
        val body = ByteArrayOutputStream(32 + mimeB.size + image.size)
        fun be32(v: Int) {
            body.write((v ushr 24) and 0xFF)
            body.write((v ushr 16) and 0xFF)
            body.write((v ushr 8) and 0xFF)
            body.write(v and 0xFF)
        }
        be32(3) // picture type: 3 = front cover
        be32(mimeB.size); body.write(mimeB)
        be32(0) // description（空）
        be32(0) // width（未知）
        be32(0) // height（未知）
        be32(0) // color depth（未知）
        be32(0) // colors（未知）
        be32(image.size); body.write(image)

        val bodyBytes = body.toByteArray()
        val header = byteArrayOf(
            ((if (last) 0x80 else 0x00) or 0x06).toByte(),
            ((bodyBytes.size ushr 16) and 0xFF).toByte(),
            ((bodyBytes.size ushr 8) and 0xFF).toByte(),
            (bodyBytes.size and 0xFF).toByte()
        )
        return header + bodyBytes
    }
}

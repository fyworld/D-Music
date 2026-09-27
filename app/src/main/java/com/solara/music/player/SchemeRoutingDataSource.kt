package com.solara.music.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * v1.4.43：按 URI scheme 分流的数据源——在线流（http/https）走播放缓存，
 * 本地文件（file/content）绕过缓存直读。
 *
 * 背景（"播到一半无声"根因）：此前所有歌曲（含本地文件）播放都经过
 * CacheDataSource 写入播放缓存。写缓存途中进程被杀（MIUI 查杀后台），
 * 或封面/歌词嵌入重写音频文件后旧缓存整体过期——缓存里留下坏数据段。
 * 之后播放命中坏段时，解码器把坏字节静默解码成静音样本（不抛错），
 * 播放头照常推进——表现为"播到一半无声、进度条走到结束、向前拖动
 * 跳过坏段才恢复"。实测清空播放缓存即恢复，实锤坏缓存。
 *
 * 本地文件直读从根上消除该路径：本地播放不写缓存，就没有坏缓存可命中；
 * 且省掉"读本地→写缓存→再读缓存"的双倍磁盘 IO。
 *
 * 在线流仍走缓存：边播边存、重听秒开的核心价值不受影响。
 */
@UnstableApi
class SchemeRoutingDataSource(
    private val cached: DataSource,
    private val direct: DataSource
) : DataSource {

    /** 当前请求实际使用的数据源（打开时确定）。 */
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme ?: ""
        val useCache = scheme.equals("http", true) || scheme.equals("https", true)
        active = if (useCache) cached else direct
        return active!!.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkActive().read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        val a = active
        active = null
        // close 必须在 finally 语义下执行：即使 active 为 null 也调两个的
        // close，防止 open 抛异常时泄漏已打开的底层句柄
        runCatching { a?.close() }
    }

    private fun checkActive(): DataSource =
        active ?: throw IOException("DataSource 未打开（open 先于 read/close 调用）")
}

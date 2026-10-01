package com.solara.music.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.solara.music.data.Store
import java.io.File

/**
 * 播放缓存（v1.4.25）：在线歌曲边播边缓存到磁盘，重听秒开零流量。
 *
 * - SimpleCache + LRU 淘汰：达到 [Store.AppSettings.playbackCacheLimitBytes]
 *   上限后自动删最久未访问的缓存（正在播放的条目不会被删——SimpleCache
 *   淘汰时跳过被 touch 锁定的资源）。
 * - 缓存 key = "source:id:br"（音质参与 key，切音质不串缓存）。
 * - 上限可在设置页调整；调小立即生效（LRU 淘汰器在写入时检查）；
 *   0 = 关闭（不建缓存，直连播放）。
 * - 缓存目录在 App 私有目录 cache/playback/，卸载自动清理，不污染公共存储。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackCache {

    private var cache: SimpleCache? = null

    @Volatile var limitBytes: Long = 30L * 1024 * 1024 * 1024
        private set

    /** 初始化（App 启动时调用一次；上限从设置读取）。已初始化时只更新上限。 */
    @Synchronized
    fun init(context: Context) {
        val limit = Store.settings.value.playbackCacheLimitBytes
        limitBytes = limit
        if (limit <= 0) return
        if (cache != null) return
        cache = runCatching {
            SimpleCache(
                File(context.applicationContext.cacheDir, "playback"),
                LeastRecentlyUsedCacheEvictor(limit),
                StandaloneDatabaseProvider(context.applicationContext)
            )
        }.getOrNull()
    }

    /** 设置页调整上限后调用：重建缓存实例（旧数据保留，按新上限淘汰）。 */
    @Synchronized
    fun applyLimit(context: Context, newLimitBytes: Long) {
        limitBytes = newLimitBytes
        if (newLimitBytes <= 0) {
            runCatching { cache?.release() }
            cache = null
            return
        }
        if (cache == null) {
            init(context)
            return
        }
        // SimpleCache 的淘汰器上限创建后不可变：release 后按新上限重建
        // （缓存文件保留在磁盘，重建后继续可用）
        runCatching { cache?.release() }
        cache = runCatching {
            SimpleCache(
                File(context.applicationContext.cacheDir, "playback"),
                LeastRecentlyUsedCacheEvictor(newLimitBytes),
                StandaloneDatabaseProvider(context.applicationContext)
            )
        }.getOrNull()
    }

    fun get(): SimpleCache? = cache

    /** 当前缓存占用（字节）。Media3 Cache 接口方法：getCacheSpace()。 */
    fun cacheSize(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)
    /** 缓存条目数。 */
    fun count(): Int = runCatching { cache?.keys?.size ?: 0 }.getOrDefault(0)

    /** 清空全部播放缓存（设置页"清空缓存"）。 */
    @Synchronized
    fun clear() {
        runCatching {
            cache?.keys?.forEach { key -> cache?.removeResource(key) }
        }
    }

    /** 构造缓存 key：source:id:br（音质参与，切音质不串）。 */
    fun keyOf(source: String, id: String, br: String): String = "$source:$id:$br"

    /**
     * v1.4.59 r19：后台补全缓存——把部分缓存的歌补到 100%。
     *
     * 背景：播放器边播边缓存只写"预读窗口"内的数据（约 50 秒），
     * 听一半就切走的歌缓存不完整；断网兜底（v1.4.29 直链持久化）
     * 要求 isFullyCached，部分缓存用不上——用户反馈"听过的歌断网
     * 还是放不了"。
     *
     * 实现：CacheWriter 对指定 key 做全区间下载补全（已缓存 span
     * 自动跳过，只补缺口）。与播放器的边播边写共用 SimpleCache 锁，
     * 并发安全；补全期间正常播放不受影响（CacheDataSource 优先读
     * 已有缓存，缺口部分各自下载不冲突）。
     *
     * @param context 用于构造上游网络数据源
     * @param key 缓存 key（source:id:br）
     * @param url 音频直链（与播放用的同一 URL）
     * @return 补全成功（或本来就全量）返回 true；失败/取消返回 false
     */
    @Synchronized
    fun backfill(
        context: android.content.Context,
        key: String,
        url: String
    ): Boolean {
        val c = cache ?: return false
        return runCatching {
            // 本来就全量：无需补全
            if (isFullyCached(key)) return true
            // media3 1.3.1：CacheDataSource.Factory（1.5 起才有 Builder）
            val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(context.applicationContext)
                )
                .setCacheKeyFactory { dataSpec -> dataSpec.key ?: dataSpec.uri.toString() }
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSourceForDownloading()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setKey(key)
                .build()
            androidx.media3.datasource.cache.CacheWriter(
                cacheDataSource, spec, ByteArray(64 * 1024), null
            ).cache()
            true
        }.getOrDefault(false)
    }

    /**
     * 该 key 的音频是否已 100% 缓存（v1.4.29：直链过期离线兜底的前提）。
     * contentLength 由 CacheDataSource 写入时自动记录（无记录时按未全量
     * 处理）；Media3 1.2.1 无 isFullyCached(key)，用 isCached(0, len)
     * 检查全区间覆盖。cache 未启用（上限=0）恒 false。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun isFullyCached(key: String): Boolean {
        val c = cache ?: return false
        return runCatching {
            val len = c.getContentMetadata(key)
                .get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            len > 0 && c.isCached(key, 0, len)
        }.getOrDefault(false)
    }
}

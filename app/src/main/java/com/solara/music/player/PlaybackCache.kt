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
}

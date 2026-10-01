package com.solara.music.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.4.59 r20：通知栏封面磁盘位图缓存——断网也能显示封面。
 *
 * 背景：通知封面走 Media3 默认 BitmapLoader（loadBitmapFromMetadata →
 * artworkUri 网络下载），无任何缓存——断网播放缓存歌曲时下载失败，
 * 通知栏只剩占位封面（用户反馈）。播放页 UI 封面走 Coil 有磁盘缓存
 * 不受影响，唯独通知栏裸奔。
 *
 * 实现：
 * - loadBitmapFromMetadata：artworkUri 是 http(s) → 先查磁盘缓存
 *   （cache/covers/<md5(url)>.img）命中直接解码返回；未命中网络下载
 *   （成功写缓存）。本地 file/content URI 直读。
 * - loadBitmap(uri)：同策略（Media3 内部其他路径调用）。
 * - decodeBitmap(data)：纯解码（无缓存语义）。
 * - 缓存文件按 URL 的 MD5 命名，同 URL 永远同文件；上限不设——
 *   封面图每张几十 KB，千首也才几十 MB，且在 cache/ 目录系统
 *   空间紧张时可自动清理、卸载即删。
 * - 下载用 OkHttp（10s 超时），失败返回 failed future（通知保持占位）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CachedBitmapLoader(
    private val context: Context
) : BitmapLoader {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cacheDir: File by lazy {
        File(context.applicationContext.cacheDir, "covers").apply { mkdirs() }
    }
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun supportsMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        executor.execute {
            val bmp = runCatching {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }.getOrNull()
            if (bmp != null) future.set(bmp) else future.setException(
                IllegalArgumentException("无法解码位图")
            )
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        executor.execute {
            val bmp = runCatching { loadOrFetch(uri) }.getOrNull()
            if (bmp != null) future.set(bmp) else future.setException(
                IllegalArgumentException("无法加载封面：$uri")
            )
        }
        return future
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val artworkUri = metadata.artworkUri ?: return null
        return loadBitmap(artworkUri)
    }

    /** 加载位图：本地 URI 直读；http(s) 先缓存后网络（成功写缓存）。 */
    private fun loadOrFetch(uri: Uri): Bitmap? {
        val scheme = uri.scheme ?: return null
        return when {
            scheme.equals("http", true) || scheme.equals("https", true) -> {
                val cached = cacheFileFor(uri.toString())
                if (cached.exists()) {
                    decodeFile(cached)?.let { return it }
                }
                val bytes = download(uri.toString()) ?: return null
                runCatching { cached.writeBytes(bytes) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            scheme.equals("file", true) ->
                decodeFile(File(uri.path ?: return null))
            scheme.equals("content", true) ->
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            else -> null
        }
    }

    private fun decodeFile(f: File): Bitmap? =
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null

    /** URL → 缓存文件（MD5 命名防路径非法字符）。 */
    private fun cacheFileFor(url: String): File {
        val md = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$md.img")
    }

    /** OkHttp 下载（10s 超时）。 */
    private fun download(url: String): ByteArray? = runCatching {
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    }.getOrNull()
}

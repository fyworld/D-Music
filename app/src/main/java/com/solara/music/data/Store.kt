package com.solara.music.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

/** 把 [from] 位置的元素移动到 [to]；下标越界或相同返回 null。 */
fun <T> List<T>.moved(from: Int, to: Int): List<T>? {
    if (from == to || from !in indices || to !in indices) return null
    return toMutableList().apply { add(to, removeAt(from)) }
}

data class AppSettings(
    val quality: String = "320",
    val source: String = "netease",
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val dynamicColor: Boolean = false,
    val apiBaseUrl: String = MusicApi.DEFAULT_BASE_URL,
    /** 探索雷达偏好的音乐风格，空则使用全部风格。 */
    val radarGenres: List<String> = emptyList(),
    /** 主题色（v1.4.13 #64）：mint/blue/purple/pink/orange/sky。 */
    val accentColor: String = "mint",
    /**
     * 播放缓存上限（字节，v1.4.25）：在线歌曲边播边缓存到磁盘，
     * 重听秒开零流量；LRU 自动淘汰（满了删最久没听的）。默认 30GB
     * （约 3000 首 320k），0 = 关闭缓存。
     */
    val playbackCacheLimitBytes: Long = 30L * 1024 * 1024 * 1024
)

/**
 * 轻量本地存储：收藏、歌单、最近播放、队列与偏好设置。
 * SharedPreferences + JSON（与网页版 localStorage 方案对等）。
 *
 * 卸载重装备份（v1.3.8）：App 私有数据随卸载清空，公共存储的备份文件保留。
 * - Android 10+：备份写入 MediaStore 音频集合 Music/D_Music_Backup/
 *   dmusic_backup.mp3（内容实为 JSON；伪装 audio/mpeg 是因为重装后凭
 *   READ_MEDIA_AUDIO 权限即可读取任意音频条目，普通文件做不到）
 * - Android 9-：公共 Downloads/D_Music_Backup/dmusic_backup.json
 *   （WRITE_EXTERNAL_STORAGE 在这些版本覆盖公共存储读写）
 * 数据每次变更同步写备份（单线程异步）；Store.init 检测全新安装时自动恢复。
 */
object Store {

    private const val PREFS_NAME = "solara_store"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_QUEUE = "queue"
    private const val KEY_QUEUE_INDEX = "queue_index"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_DOWNLOADS = "downloads"
    private const val KEY_RECENT = "recent"
    // 探索/搜索结果持久化（v1.3.9：退出重启后列表保留）
    private const val KEY_EXPLORE = "explore_state"
    private const val KEY_SEARCH = "search_state"
    // 本地歌曲在线匹配封面 URL 缓存（v1.4.0：文件名 → 封面直链）
    private const val KEY_LOCAL_COVERS = "local_covers"
    // 在线歌曲封面 URL 磁盘缓存（v1.4.25：key=source:picId → 封面直链，
    // 冷启动免 API 解析直接命中 Coil 磁盘图片缓存，离线也有封面）
    private const val KEY_ONLINE_COVERS = "online_covers"
    // 歌词磁盘缓存（v1.4.9：key=source:id → LRC 文本，离线可读）
    private const val KEY_LYRIC_CACHE = "lyric_cache"
    // 按歌歌词偏移（v1.4.15：key=source:id → 偏移秒数，每首歌独立校准）
    private const val KEY_LYRIC_OFFSETS = "lyric_offsets"
    // 逐句打点时间戳（v1.4.16：key=source:id → [行索引,毫秒,...] 扁平数组，手动标记真实开唱时刻）
    private const val KEY_LYRIC_TIMESTAMPS = "lyric_timestamps"
    // 扫描文件夹记忆（v1.4.3：null=默认 Music/D_Music）
    private const val KEY_SCAN_FOLDER = "scan_folder"

    // 播放直链持久化（v1.4.29：key=source:id:br → 直链 URL。
    // API 故障时的离线兜底：音频已全量缓存的歌用过期直链也能播——
    // CacheDataSource 100% 命中根本不会碰上游）
    private const val KEY_URL_CACHE = "url_cache"

    /** v1.4.20：最后退出时的主界面状态（tab / mePage / showPlayer）。 */
    private const val KEY_UI_STATE = "ui_state"

    /** v1.4.26：播放模式（顺序播放/顺序循环/单曲循环/随机）。 */
    private const val KEY_PLAY_MODE = "play_mode"

    /**
     * v1.4.35：后台播放保活引导状态。
     * 0 = 未触发（默认）；1 = 检测到熄屏播放中断，待引导；
     * 2 = 用户已完成设置（或永久忽略），不再提示。
     */
    private const val KEY_BG_PLAY_GUIDE = "bg_play_guide"

    /**
     * v1.4.36：服务死前是否在播放（进程被杀指纹）。
     * 播放状态变化时持久化；服务重启（attachPlayer）时读取对比——
     * was_playing=true 且非用户主动续播（pendingPlayOnAttach）=
     * 进程/服务被系统杀（正常退出走 stopAndExit 会先置 false）。
     */
    private const val KEY_WAS_PLAYING = "was_playing"

    /** 最近播放列表上限：超出裁掉最旧的。 */
    private const val RECENT_LIMIT = 300

    // ---- 备份位置（见类注释）----
    private const val BACKUP_DIR_Q = "Music/D_Music_Backup"
    private const val BACKUP_FILE_Q = "dmusic_backup.mp3"
    private const val BACKUP_DIR_LEGACY = "D_Music_Backup"
    private const val BACKUP_FILE_LEGACY = "dmusic_backup.json"

    private lateinit var prefs: SharedPreferences

    @Volatile private var appContextRef: Context? = null
    @Volatile private var restoredFromBackup = false

    /** 备份写盘单线程池：顺序化防并发写坏文件，且不阻塞主线程。 */
    private val backupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 队列快照：PlayerManager 在队列变化时同步（避免 Store 反向依赖）。 */
    @Volatile private var queueSnapshot: List<Song> = emptyList()
    @Volatile private var queueIndexSnapshot: Int = -1

    val settings = MutableStateFlow(AppSettings())
    val favorites = MutableStateFlow<List<Song>>(emptyList())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    /** 已下载到本地的歌曲（本地歌曲列表）。 */
    val downloads = MutableStateFlow<List<Song>>(emptyList())
    /** 最近播放（去重，最新在前，手动拖动可调序）。 */
    val recent = MutableStateFlow<List<Song>>(emptyList())

    fun init(context: Context) {
        val appCtx = context.applicationContext
        prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appContextRef = appCtx

        if (isFreshInstall()) {
            // 全新安装（或卸载重装）：尝试从公共目录备份恢复。
            // 注意：首次安装时 READ_MEDIA_AUDIO 可能尚未授予，恢复会落空，
            // 由 MainActivity 在权限授予后调 retryRestoreAfterPermission 补救。
            restoredFromBackup = tryRestoreFromBackup()
        } else {
            restoredFromBackup = true // 已有本地数据，无需恢复
        }

        loadAll()
    }

    private fun loadAll() {
        settings.value = readSettings()
        favorites.value = readSongs(KEY_FAVORITES)
        playlists.value = readPlaylists()
        downloads.value = readSongs(KEY_DOWNLOADS)
        recent.value = readSongs(KEY_RECENT)
        scanFolder.value = prefs.getString(KEY_SCAN_FOLDER, null)
        MusicApi.baseUrl = settings.value.apiBaseUrl
    }

    /** prefs 里所有业务键都为空 → 全新安装（或数据被清/卸载重装）。 */
    private fun isFreshInstall(): Boolean =
        prefs.getString(KEY_FAVORITES, null) == null &&
            prefs.getString(KEY_PLAYLISTS, null) == null &&
            prefs.getString(KEY_RECENT, null) == null &&
            prefs.getString(KEY_QUEUE, null) == null

    /**
     * 音频读取权限授予后重试恢复（首次启动权限弹窗晚于 Store.init，
     * 无权限时 MediaStore 查询只能看到自己贡献的条目 = 空）。
     * 返回是否恢复成功；成功后调用方应刷新播放队列。
     */
    fun retryRestoreAfterPermission(): Boolean {
        if (!this::prefs.isInitialized) return false
        if (restoredFromBackup) return false
        if (!isFreshInstall()) {
            restoredFromBackup = true
            return false
        }
        restoredFromBackup = tryRestoreFromBackup()
        if (restoredFromBackup) loadAll()
        return restoredFromBackup
    }

    // ---------- 备份：读 ----------

    private fun tryRestoreFromBackup(): Boolean = runCatching {
        val ctx = appContextRef ?: return false
        val text = readBackupText(ctx) ?: return false
        val root = JSONObject(text)
        val fav = songsFromJsonArray(root.optJSONArray(KEY_FAVORITES))
        val rec = songsFromJsonArray(root.optJSONArray(KEY_RECENT))
        val pls = playlistsFromJsonArray(root.optJSONArray(KEY_PLAYLISTS))
        val q = songsFromJsonArray(root.optJSONArray(KEY_QUEUE))
        val qi = root.optInt(KEY_QUEUE_INDEX, -1)
        if (fav.isEmpty() && rec.isEmpty() && pls.isEmpty() && q.isEmpty()) return false

        prefs.edit().apply {
            if (fav.isNotEmpty()) putString(KEY_FAVORITES, songsToJsonArray(fav).toString())
            if (rec.isNotEmpty()) putString(KEY_RECENT, songsToJsonArray(rec).toString())
            if (pls.isNotEmpty()) putString(KEY_PLAYLISTS, playlistsToJsonArray(pls).toString())
            if (q.isNotEmpty()) {
                putString(KEY_QUEUE, songsToJsonArray(q).toString())
                putInt(KEY_QUEUE_INDEX, qi)
            }
        }.commit()
        true
    }.getOrDefault(false)

    private fun readBackupText(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) readBackupQ(context)
        else readBackupLegacy(context)

    // ---------- 备份：Android 10+（MediaStore 音频条目） ----------

    /** 查询备份条目（按修改时间新→旧）。DATA LIKE 匹配目录，规避 ROM 差异。 */
    private fun queryBackupEntriesQ(context: Context): List<Uri> {
        val selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
        return context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            selection,
            arrayOf("%/D_Music_Backup/%"),
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val list = mutableListOf<Uri>()
            while (c.moveToNext()) {
                list.add(
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                    )
                )
            }
            list
        } ?: emptyList()
    }

    private fun writeBackupQ(context: Context, json: String) {
        val bytes = json.toByteArray()
        // 1) 原地更新已有条目（仅本应用创建的条目可写；重装后的旧条目
        //    owner 关系已断，写入会失败 → 走新建）
        for (uri in queryBackupEntriesQ(context)) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                true
            }.getOrDefault(false)
            if (ok) return
        }
        // 2) 新建条目（IS_PENDING → 写入 → 解除 pending）
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, BACKUP_FILE_Q)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, BACKUP_DIR_Q)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null, null
            )
        }
    }

    private fun readBackupQ(context: Context): String? {
        for (uri in queryBackupEntriesQ(context)) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    // ---------- 备份：Android 9-（公共 Downloads 直写） ----------

    private fun backupFileLegacy(): File =
        File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BACKUP_DIR_LEGACY
            ),
            BACKUP_FILE_LEGACY
        )

    private fun writeBackupLegacy(context: Context, json: String) {
        runCatching {
            val f = backupFileLegacy()
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            f.writeText(json)
        }
    }

    private fun readBackupLegacy(context: Context): String? = runCatching {
        val f = backupFileLegacy()
        if (f.exists()) f.readText() else null
    }.getOrNull()

    /** 异步写备份（数据变更时调用）。 */
    private fun backupAsync() {
        val ctx = appContextRef ?: return
        val json = JSONObject().apply {
            put("version", 1)
            put(KEY_FAVORITES, songsToJsonArray(favorites.value))
            put(KEY_RECENT, songsToJsonArray(recent.value))
            put(KEY_PLAYLISTS, playlistsToJsonArray(playlists.value))
            put(KEY_QUEUE, songsToJsonArray(queueSnapshot))
            put(KEY_QUEUE_INDEX, queueIndexSnapshot)
        }.toString()
        backupExecutor.execute {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeBackupQ(ctx, json)
                else writeBackupLegacy(ctx, json)
            }
        }
    }

    // ---------- 设置 ----------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.value)
        settings.value = next
        prefs.edit().putString(KEY_SETTINGS, settingsToJson(next)).apply()
        MusicApi.baseUrl = next.apiBaseUrl.ifBlank { MusicApi.DEFAULT_BASE_URL }
    }

    // ---------- 播放模式（v1.4.26 持久化） ----------

    fun savePlayMode(mode: com.solara.music.player.PlayMode) {
        prefs.edit().putString(KEY_PLAY_MODE, mode.name).apply()
    }

    fun readPlayMode(): com.solara.music.player.PlayMode? = runCatching {
        prefs.getString(KEY_PLAY_MODE, null)?.let {
            com.solara.music.player.PlayMode.valueOf(it)
        }
    }.getOrNull()

    // ---------- 收藏 ----------

    fun isFavorite(song: Song): Boolean = favorites.value.any { it.sameAs(song) }

    /** 返回切换后的收藏状态。 */
    fun toggleFavorite(song: Song): Boolean {
        val current = favorites.value
        val exists = current.any { it.sameAs(song) }
        favorites.value = if (exists) current.filterNot { it.sameAs(song) } else current + song
        writeSongs(KEY_FAVORITES, favorites.value)
        backupAsync()
        return !exists
    }

    fun removeFavorite(song: Song) {
        favorites.value = favorites.value.filterNot { it.sameAs(song) }
        writeSongs(KEY_FAVORITES, favorites.value)
        backupAsync()
    }

    /** v1.4.26：批量移除收藏（返回实际移除数）。 */
    fun removeFavorites(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val before = favorites.value.size
        favorites.value = favorites.value.filterNot { f -> songs.any { it.sameAs(f) } }
        val removed = before - favorites.value.size
        if (removed > 0) {
            writeSongs(KEY_FAVORITES, favorites.value)
            backupAsync()
        }
        return removed
    }

    /** v1.4.26：批量收藏（跳过已收藏的，返回新收藏数）。 */
    fun addFavorites(songs: List<Song>): Int {
        val current = favorites.value
        val added = songs.filter { s -> current.none { it.sameAs(s) } }
        if (added.isNotEmpty()) {
            favorites.value = current + added
            writeSongs(KEY_FAVORITES, favorites.value)
            backupAsync()
        }
        return added.size
    }

    /** v1.4.26：清空全部收藏。 */
    fun clearFavorites() {
        if (favorites.value.isEmpty()) return
        favorites.value = emptyList()
        writeSongs(KEY_FAVORITES, emptyList())
        backupAsync()
    }

    /** 收藏列表手动拖动排序。 */
    fun moveFavorite(from: Int, to: Int): Boolean {
        val next = favorites.value.moved(from, to) ?: return false
        favorites.value = next
        writeSongs(KEY_FAVORITES, next)
        backupAsync()
        return true
    }

    // ---------- 播放队列 ----------

    fun saveQueue(songs: List<Song>, index: Int) {
        writeSongs(KEY_QUEUE, songs)
        prefs.edit().putInt(KEY_QUEUE_INDEX, index).apply()
        updateQueueSnapshot(songs, index)
        backupAsync()
    }

    /**
     * 同步落盘版队列保存（commit）：退出/服务销毁路径用。
     * apply() 是异步写盘，进程被系统强杀时可能丢失最后一次写入——
     * 这正是"完全退出再启动队列清空"的根因之一。
     */
    fun saveQueueNow(songs: List<Song>, index: Int) {
        prefs.edit()
            .putString(KEY_QUEUE, songsToJsonArray(songs).toString())
            .putInt(KEY_QUEUE_INDEX, index)
            .commit()
        updateQueueSnapshot(songs, index)
        backupAsync()
    }

    fun readQueue(): Pair<List<Song>, Int> {
        val songs = readSongs(KEY_QUEUE)
        val index = prefs.getInt(KEY_QUEUE_INDEX, -1)
        return songs to index
    }

    /** 由 PlayerManager 在队列变化时同步快照（备份用）。 */
    fun updateQueueSnapshot(songs: List<Song>, index: Int) {
        queueSnapshot = songs
        queueIndexSnapshot = index
    }

    // ---------- 界面状态恢复（v1.4.20） ----------

    /**
     * 持久化最后退出时的主界面状态。完全退出后（无论从通知栏、桌面图标
     * 还是后台切换）再进入，恢复到最后所在的界面而不是默认探索页。
     * tab：0 探索 / 1 搜索 / 2 收藏 / 3 最近；mePage：SETTINGS/DOWNLOADS/
     * LOCAL_SONGS/ABOUT 或空；showPlayer：是否停在播放页。
     */
    fun saveUiState(tab: Int, mePage: String?, showPlayer: Boolean) {
        prefs.edit()
            .putInt("ui_state_tab", tab)
            .putString("ui_state_me_page", mePage)
            .putBoolean("ui_state_show_player", showPlayer)
            .apply()
    }

    /** 读取持久化的界面状态：null = 无记录（首次安装），恢复默认探索页。 */
    fun readUiState(): Triple<Int, String?, Boolean>? {
        if (!prefs.contains("ui_state_tab")) return null
        return Triple(
            prefs.getInt("ui_state_tab", 0),
            prefs.getString("ui_state_me_page", null),
            prefs.getBoolean("ui_state_show_player", false)
        )
    }

    // ---------- 更新跳过版本（v1.4.23：「暂不更新」后本版本不再自动弹窗） ----------

    /** 用户点了「暂不更新」的版本号；null = 从未跳过。 */
    fun skippedVersion(): String? = prefs.getString("skipped_version", null)

    /** 记录跳过的版本：同一版本重启不再自动弹更新提示，直到更新的版本出现。 */
    fun saveSkippedVersion(version: String) {
        prefs.edit().putString("skipped_version", version).apply()
    }

    // ---------- 后台播放保活引导（v1.4.35） ----------

    /** 0=未触发 1=检测到熄屏中断待引导 2=已完成设置/永久忽略。 */
    fun bgPlayGuideState(): Int = prefs.getInt(KEY_BG_PLAY_GUIDE, 0)

    fun saveBgPlayGuideState(state: Int) {
        prefs.edit().putInt(KEY_BG_PLAY_GUIDE, state).apply()
    }

    // ---------- 服务死前播放状态（v1.4.36：进程被杀指纹） ----------

    fun wasPlaying(): Boolean = prefs.getBoolean(KEY_WAS_PLAYING, false)

    fun saveWasPlaying(playing: Boolean) {
        // commit 同步写：进程随时可能被杀，apply 异步会丢
        prefs.edit().putBoolean(KEY_WAS_PLAYING, playing).commit()
    }

    // ---------- 最近播放（v1.3.8） ----------

    /** 记录一次播放：去重后插到最前，超上限裁旧。 */
    fun addRecent(song: Song) {
        val cur = recent.value
        val next = (listOf(song) + cur.filterNot { it.sameAs(song) })
            .take(RECENT_LIMIT)
        if (next == cur) return
        recent.value = next
        writeSongs(KEY_RECENT, next)
        backupAsync()
    }

    /** 最近播放手动拖动排序。 */
    fun moveRecent(from: Int, to: Int): Boolean {
        val next = recent.value.moved(from, to) ?: return false
        recent.value = next
        writeSongs(KEY_RECENT, next)
        backupAsync()
        return true
    }

    fun removeRecent(song: Song) {
        recent.value = recent.value.filterNot { it.sameAs(song) }
        writeSongs(KEY_RECENT, recent.value)
        backupAsync()
    }

    /** v1.4.26：批量移除最近播放记录。 */
    fun removeRecentBatch(songs: List<Song>) {
        if (songs.isEmpty()) return
        recent.value = recent.value.filterNot { r -> songs.any { it.sameAs(r) } }
        writeSongs(KEY_RECENT, recent.value)
        backupAsync()
    }

    fun clearRecent() {
        recent.value = emptyList()
        writeSongs(KEY_RECENT, emptyList())
        backupAsync()
    }

    // ---------- 探索/搜索结果持久化（v1.3.9） ----------

    /** 保存探索页状态（风格 + 本次结果）。 */
    fun saveExploreState(genre: String?, songs: List<Song>) {
        val o = JSONObject().apply {
            put("genre", genre ?: "")
            put("songs", songsToJsonArray(songs))
        }
        prefs.edit().putString(KEY_EXPLORE, o.toString()).apply()
    }

    /** 读取探索页状态；无记录返回 null。 */
    fun readExploreState(): Pair<String?, List<Song>>? = runCatching {
        val raw = prefs.getString(KEY_EXPLORE, null) ?: return null
        val o = JSONObject(raw)
        val genre = o.optString("genre").ifBlank { null }
        val songs = songsFromJsonArray(o.optJSONArray("songs"))
        genre to songs
    }.getOrNull()

    /** 保存搜索页状态（关键词 + 音源 + 结果 + 页码）。 */
    fun saveSearchState(query: String, source: String, songs: List<Song>, page: Int) {
        val o = JSONObject().apply {
            put("query", query)
            put("source", source)
            put("page", page)
            put("songs", songsToJsonArray(songs))
        }
        prefs.edit().putString(KEY_SEARCH, o.toString()).apply()
    }

    /** 读取搜索页状态；无记录返回 null。 */
    fun readSearchState(): SearchState? = runCatching {
        val raw = prefs.getString(KEY_SEARCH, null) ?: return null
        val o = JSONObject(raw)
        SearchState(
            query = o.optString("query"),
            source = o.optString("source").ifBlank { "netease" },
            page = o.optInt("page", 1),
            songs = songsFromJsonArray(o.optJSONArray("songs"))
        )
    }.getOrNull()

    /** 搜索页持久化快照。 */
    data class SearchState(
        val query: String,
        val source: String,
        val page: Int,
        val songs: List<Song>
    )

    // ---------- 歌单 CRUD ----------

    fun createPlaylist(name: String): Playlist {
        val p = Playlist(name = name.trim().ifBlank { "新建歌单" })
        playlists.value = playlists.value + p
        writePlaylists()
        backupAsync()
        return p
    }

    fun renamePlaylist(id: String, newName: String) {
        updatePlaylist(id) { it.copy(name = newName.trim().ifBlank { it.name }) }
    }

    fun deletePlaylist(id: String) {
        playlists.value = playlists.value.filterNot { it.id == id }
        writePlaylists()
        backupAsync()
    }

    /** 返回添加后的歌单。 */
    fun addToPlaylist(id: String, song: Song): Playlist? {
        var updated: Playlist? = null
        val next = playlists.value.map { p ->
            if (p.id == id) {
                val t = if (p.contains(song)) p else p.copy(songs = p.songs + song)
                updated = t
                t
            } else p
        }
        playlists.value = next
        writePlaylists()
        backupAsync()
        return updated
    }

    fun removeFromPlaylist(id: String, song: Song) {
        updatePlaylist(id) { it.copy(songs = it.songs.filterNot { s -> s.sameAs(song) }) }
    }

    /** v1.4.26：批量移出歌单（返回实际移除数）。 */
    fun removeFromPlaylist(id: String, songs: List<Song>): Int {
        var removed = 0
        updatePlaylist(id) { p ->
            val before = p.songs.size
            val next = p.copy(songs = p.songs.filterNot { s -> songs.any { it.sameAs(s) } })
            removed = before - next.songs.size
            next
        }
        return removed
    }

    /** 歌单内歌曲手动拖动排序。 */
    fun movePlaylistSong(id: String, from: Int, to: Int): Boolean {
        var ok = false
        val next = playlists.value.map { p ->
            if (p.id == id) {
                val songs = p.songs.moved(from, to)
                if (songs != null) {
                    ok = true
                    p.copy(songs = songs)
                } else p
            } else p
        }
        if (ok) {
            playlists.value = next
            writePlaylists()
            backupAsync()
        }
        return ok
    }

    private fun updatePlaylist(id: String, transform: (Playlist) -> Playlist): Playlist? {
        val next = playlists.value.map { p ->
            if (p.id == id) transform(p) else p
        }
        playlists.value = next
        writePlaylists()
        backupAsync()
        return next.firstOrNull { it.id == id }
    }

    // ---------- 本地歌曲（已下载记录） ----------

    fun addDownload(song: Song) {
        if (downloads.value.any { it.sameAs(song) }) return
        downloads.value = downloads.value + song
        writeSongs(KEY_DOWNLOADS, downloads.value)
    }

    fun removeDownload(song: Song) {
        downloads.value = downloads.value.filterNot { it.sameAs(song) }
        writeSongs(KEY_DOWNLOADS, downloads.value)
    }

    /** v1.4.26：批量移出本地歌曲列表（仅清记录，不删除文件）。 */
    fun removeDownloads(songs: List<Song>) {
        if (songs.isEmpty()) return
        downloads.value = downloads.value.filterNot { d -> songs.any { it.sameAs(d) } }
        writeSongs(KEY_DOWNLOADS, downloads.value)
    }

    /**
     * 用扫描结果整体替换本地歌曲列表（卸载重装/记录丢失后重建用）。
     * 保留原有记录的顺序在前，扫描新增的条目追加在后。
     */
    fun replaceDownloads(songs: List<Song>) {
        downloads.value = songs
        writeSongs(KEY_DOWNLOADS, songs)
    }

    /** 本地歌曲列表手动拖动排序。 */
    fun moveDownload(from: Int, to: Int): Boolean {
        val next = downloads.value.moved(from, to) ?: return false
        downloads.value = next
        writeSongs(KEY_DOWNLOADS, next)
        return true
    }

    /**
     * 本地歌曲重命名后更新记录（文件已由 DownloadManager.renameLocalFile 改名）。
     * 按新文件名重新解析 歌手/歌名，id 同步为新文件名。
     * 封面 URL 缓存 key（文件名）同步迁移。
     */
    fun renameDownload(song: Song, newFileName: String) {
        val idx = downloads.value.indexOfFirst { it.sameAs(song) }
        if (idx < 0) return
        val base = newFileName.substringBeforeLast('.', newFileName)
        val dot = base.indexOf(" - ")
        val (artist, name) = if (dot > 0) {
            base.substring(0, dot).trim() to base.substring(dot + 3).trim()
        } else {
            "" to base.trim()
        }
        val updated = song.copy(
            id = "local:$newFileName",
            name = name.ifBlank { song.name },
            artist = artist
        )
        downloads.value = downloads.value.toMutableList().apply { set(idx, updated) }
        writeSongs(KEY_DOWNLOADS, downloads.value)
        // 封面缓存 key 迁移：旧文件名 → 新文件名
        migrateLocalCoverKey(song, updated)
    }

    // ---------- 本地歌曲封面 URL 缓存（v1.4.0） ----------

    // ---------- 扫描文件夹（v1.4.3） ----------

    /** 上次选择的扫描文件夹；null = 默认 Music/D_Music。 */
    val scanFolder = MutableStateFlow<String?>(null)

    fun saveScanFolder(path: String?) {
        scanFolder.value = path
        if (path == null) prefs.edit().remove(KEY_SCAN_FOLDER).apply()
        else prefs.edit().putString(KEY_SCAN_FOLDER, path).apply()
    }

    /** 读取本地歌曲匹配到的在线封面 URL；无返回 null。key=原始文件名。 */
    fun localCoverUrl(song: Song): String? {
        if (song.source != "local" || !song.id.startsWith("local:")) return null
        val fileName = song.id.removePrefix("local:")
        return readLocalCovers().optString(fileName).takeIf { it.isNotBlank() }
    }

    /** 保存本地歌曲匹配到的在线封面 URL。 */
    fun saveLocalCoverUrl(song: Song, url: String) {
        if (song.source != "local" || !song.id.startsWith("local:")) return
        val fileName = song.id.removePrefix("local:")
        val o = readLocalCovers()
        o.put(fileName, url)
        prefs.edit().putString(KEY_LOCAL_COVERS, o.toString()).apply()
    }

    // ---------- 歌词磁盘缓存（v1.4.9：离线可读） ----------

    /** 读取歌曲缓存的歌词文本（LRC），无缓存返回 null。 */
    fun cachedLyric(song: Song): String? {
        val key = "${song.source}:${song.id}"
        return readLyricCache().optString(key).takeIf { it.isNotBlank() }
    }

    /** 缓存歌曲歌词文本（LRC 原文）。空文本不存。 */
    fun saveCachedLyric(song: Song, lrc: String) {
        if (lrc.isBlank()) return
        val key = "${song.source}:${song.id}"
        val o = readLyricCache()
        o.put(key, lrc)
        prefs.edit().putString(KEY_LYRIC_CACHE, o.toString()).apply()
    }

    private fun readLyricCache(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(prefs.getString(KEY_LYRIC_CACHE, null) ?: "{}")
        }.getOrDefault(org.json.JSONObject())

    // ---------- 按歌歌词偏移（v1.4.15：每首歌独立校准） ----------

    /** 读取歌曲的歌词偏移秒数（正值=歌词延后，负值=提前），未校准返回 0。 */
    fun lyricOffsetOf(song: Song): Float =
        readLyricOffsets().optDouble("${song.source}:${song.id}", 0.0).toFloat()

    /**
     * 保存歌曲的歌词偏移秒数。
     * 偏移为 0（重置）时删除该条目，避免 map 无限膨胀。
     */
    fun saveLyricOffset(song: Song, offsetSec: Float) {
        val key = "${song.source}:${song.id}"
        val o = readLyricOffsets()
        if (offsetSec == 0f) o.remove(key) else o.put(key, offsetSec.toDouble())
        prefs.edit().putString(KEY_LYRIC_OFFSETS, o.toString()).apply()
    }

    private fun readLyricOffsets(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(prefs.getString(KEY_LYRIC_OFFSETS, null) ?: "{}")
        }.getOrDefault(org.json.JSONObject())

    // ---------- 逐句打点时间戳（v1.4.16：手动标记每句真实开唱时刻） ----------

    /**
     * 读取歌曲的打点时间戳：行索引 → 毫秒。
     * LRC 原始时间轴不可靠时（偏移校准救不了的），用户在打点模式下
     * 逐句标记真实开唱时刻，显示歌词时优先使用打点时间。
     */
    fun lyricTimestampsOf(song: Song): Map<Int, Long> {
        val arr = readLyricTimestamps().optJSONArray("${song.source}:${song.id}") ?: return emptyMap()
        val map = HashMap<Int, Long>()
        var i = 0
        while (i + 1 < arr.length()) {
            map[arr.optInt(i)] = arr.optLong(i + 1)
            i += 2
        }
        return map
    }

    /** 保存歌曲的打点时间戳（行索引 → 毫秒）。空 map 删除条目。 */
    fun saveLyricTimestamps(song: Song, timestamps: Map<Int, Long>) {
        val key = "${song.source}:${song.id}"
        val o = readLyricTimestamps()
        if (timestamps.isEmpty()) {
            o.remove(key)
        } else {
            val arr = org.json.JSONArray()
            timestamps.toSortedMap().forEach { (idx, ms) ->
                arr.put(idx).put(ms)
            }
            o.put(key, arr)
        }
        prefs.edit().putString(KEY_LYRIC_TIMESTAMPS, o.toString()).apply()
    }

    private fun readLyricTimestamps(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(prefs.getString(KEY_LYRIC_TIMESTAMPS, null) ?: "{}")
        }.getOrDefault(org.json.JSONObject())

    private fun readLocalCovers(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(prefs.getString(KEY_LOCAL_COVERS, null) ?: "{}")
        }.getOrDefault(org.json.JSONObject())

    // ---------- 在线歌曲封面 URL 磁盘缓存（v1.4.25：冷启动免 API 解析） ----------

    /** 读取在线歌曲缓存过的封面 URL，无缓存返回 null。 */
    fun onlineCoverUrl(song: Song): String? {
        val key = "${song.source}:${song.picId.ifBlank { song.id }}"
        return readOnlineCovers().optString(key).takeIf { it.isNotBlank() }
    }

    /** 缓存在线歌曲的封面 URL（key=source:picId）。空 URL 不存。 */
    fun saveOnlineCoverUrl(song: Song, url: String) {
        if (url.isBlank()) return
        val key = "${song.source}:${song.picId.ifBlank { song.id }}"
        val o = readOnlineCovers()
        if (o.optString(key) == url) return
        o.put(key, url)
        prefs.edit().putString(KEY_ONLINE_COVERS, o.toString()).apply()
    }

    /** 清除在线歌曲缓存的封面 URL（直链过期时调用，触发重新解析）。 */
    fun clearOnlineCoverUrl(song: Song) {
        val key = "${song.source}:${song.picId.ifBlank { song.id }}"
        val o = readOnlineCovers()
        if (!o.has(key)) return
        o.remove(key)
        prefs.edit().putString(KEY_ONLINE_COVERS, o.toString()).apply()
    }

    private fun readOnlineCovers(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(prefs.getString(KEY_ONLINE_COVERS, null) ?: "{}")
        }.getOrDefault(org.json.JSONObject())

    // ---------- 播放直链持久化（v1.4.29：API 故障时离线兜底） ----------

    /** 读取歌曲缓存过的播放直链（key=source:id:br，音质参与），无则 null。 */
    fun cachedUrl(source: String, id: String, br: String): String? {
        val key = "$source:$id:$br"
        return readUrlCache().optString(key).takeIf { it.isNotBlank() }
    }

    /** 缓存播放直链。空 URL 不存；与已存值相同不重复写盘。 */
    fun saveUrl(source: String, id: String, br: String, url: String) {
        if (url.isBlank()) return
        val key = "$source:$id:$br"
        val o = readUrlCache()
        if (o.optString(key) == url) return
        o.put(key, url)
        prefs.edit().putString(KEY_URL_CACHE, o.toString()).apply()
    }

    /** 清除单曲直链（缓存直链播放失败时调用，触发重新解析）。 */
    fun clearUrl(source: String, id: String, br: String) {
        val key = "$source:$id:$br"
        val o = readUrlCache()
        if (!o.has(key)) return
        o.remove(key)
        prefs.edit().putString(KEY_URL_CACHE, o.toString()).apply()
    }

    /** 清空全部直链缓存（设置页清空播放缓存时同步调用）。 */
    fun clearUrlCache() {
        if (!prefs.contains(KEY_URL_CACHE)) return
        prefs.edit().remove(KEY_URL_CACHE).apply()
    }

    private fun readUrlCache(): org.json.JSONObject =
        runCatching {
            org.json.JSONObject(prefs.getString(KEY_URL_CACHE, null) ?: "{}")
        }.getOrDefault(org.json.JSONObject())

    private fun migrateLocalCoverKey(old: Song, new: Song) {
        val oldName = old.id.removePrefix("local:")
        val newName = new.id.removePrefix("local:")
        if (oldName == newName) return
        val o = readLocalCovers()
        val url = o.optString(oldName).takeIf { it.isNotBlank() } ?: return
        o.remove(oldName)
        o.put(newName, url)
        prefs.edit().putString(KEY_LOCAL_COVERS, o.toString()).apply()
    }

    // ---------- 序列化 ----------

    private fun writePlaylists() {
        prefs.edit()
            .putString(KEY_PLAYLISTS, playlistsToJsonArray(playlists.value).toString())
            .apply()
    }

    private fun readPlaylists(): List<Playlist> =
        runCatching {
            val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
            playlistsFromJsonArray(JSONArray(raw))
        }.getOrDefault(emptyList())

    private fun readSettings(): AppSettings = runCatching {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        val o = JSONObject(raw)
        AppSettings(
            quality = o.optString("quality", "320").ifBlank { "320" },
            source = o.optString("source", "netease").ifBlank { "netease" },
            themeMode = parseThemeMode(o.optString("themeMode")),
            dynamicColor = o.optBoolean("dynamicColor", false),
            apiBaseUrl = o.optString("apiBaseUrl", MusicApi.DEFAULT_BASE_URL)
                .ifBlank { MusicApi.DEFAULT_BASE_URL },
            radarGenres = parseRadarGenres(o),
            accentColor = o.optString("accentColor", "mint").ifBlank { "mint" },
            playbackCacheLimitBytes = o.optLong(
                "playbackCacheLimitBytes", 30L * 1024 * 1024 * 1024
            )
        )
    }.getOrDefault(AppSettings())

    private fun parseThemeMode(name: String): ThemeMode =
        runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.FOLLOW_SYSTEM)

    private fun parseRadarGenres(o: JSONObject): List<String> =
        runCatching {
            val arr = o.optJSONArray("radarGenres") ?: return@runCatching emptyList<String>()
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).ifBlank { null } }
        }.getOrDefault(emptyList())

    private fun settingsToJson(s: AppSettings): String = JSONObject().apply {
        put("quality", s.quality)
        put("source", s.source)
        put("themeMode", s.themeMode.name)
        put("dynamicColor", s.dynamicColor)
        put("apiBaseUrl", s.apiBaseUrl)
        put("radarGenres", JSONArray(s.radarGenres))
        put("accentColor", s.accentColor)
        put("playbackCacheLimitBytes", s.playbackCacheLimitBytes)
    }.toString()

    private fun songToJson(s: Song): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("artist", s.artist)
        put("album", s.album)
        put("pic_id", s.picId)
        put("url_id", s.urlId)
        put("lyric_id", s.lyricId)
        put("source", s.source)
    }

    private fun songFromJson(o: JSONObject): Song? = runCatching {
        Song(
            id = o.optString("id"),
            name = o.optString("name"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            picId = o.optString("pic_id"),
            urlId = o.optString("url_id"),
            lyricId = o.optString("lyric_id"),
            source = o.optString("source").ifBlank { "netease" }
        )
    }.getOrNull()

    private fun songsToJsonArray(songs: List<Song>): JSONArray =
        JSONArray().apply { songs.forEach { put(songToJson(it)) } }

    private fun songsFromJsonArray(arr: JSONArray?): List<Song> =
        runCatching {
            if (arr == null) return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { songFromJson(it) }
            }
        }.getOrDefault(emptyList())

    private fun playlistsToJsonArray(list: List<Playlist>): JSONArray =
        JSONArray().apply {
            list.forEach { p ->
                put(
                    JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("created_at", p.createdAt)
                        put("songs", songsToJsonArray(p.songs))
                    }
                )
            }
        }

    private fun playlistsFromJsonArray(arr: JSONArray?): List<Playlist> =
        runCatching {
            if (arr == null) return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Playlist(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name").ifBlank { "未命名歌单" },
                    createdAt = o.optLong("created_at", System.currentTimeMillis()),
                    songs = songsFromJsonArray(o.optJSONArray("songs"))
                )
            }
        }.getOrDefault(emptyList())

    private fun writeSongs(key: String, songs: List<Song>) {
        prefs.edit().putString(key, songsToJsonArray(songs).toString()).apply()
    }

    private fun readSongs(key: String): List<Song> =
        runCatching {
            val raw = prefs.getString(key, null) ?: return emptyList()
            songsFromJsonArray(JSONArray(raw))
        }.getOrDefault(emptyList())
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.solara.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solara.music.data.DownloadManager
import com.solara.music.data.DownloadStatus
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.player.PlayerManager
import kotlinx.coroutines.launch
import com.solara.music.ui.about.AboutScreen
import com.solara.music.ui.components.AddToPlaylistSheet
import com.solara.music.ui.components.CoverImage
import com.solara.music.ui.download.DownloadScreen
import com.solara.music.ui.download.QualityPickerDialog
import com.solara.music.ui.explore.ExploreScreen
import com.solara.music.ui.explore.ExploreViewModel
import com.solara.music.ui.library.LibraryScreen
import com.solara.music.ui.local.LocalSongsScreen
import com.solara.music.ui.player.PlayerScreen
import com.solara.music.ui.player.PlayerViewModel
import com.solara.music.ui.recent.RecentScreen
import com.solara.music.ui.search.SearchScreen
import com.solara.music.ui.search.SearchViewModel
import com.solara.music.ui.settings.SettingsScreen

/** "我的"页面内的子页面。 */
private enum class MePage { SETTINGS, DOWNLOADS, LOCAL_SONGS, ABOUT }

@Composable
fun SolaraApp() {
    // v1.4.20：初始界面状态从 Store 恢复——完全退出后（通知栏/桌面图标/
    // 后台切换）再进入，回到最后退出的界面而不是默认探索页。
    val savedUi = remember { Store.readUiState() }
    var tab by rememberSaveable { mutableStateOf(savedUi?.first ?: 0) }
    var showPlayer by remember { mutableStateOf(savedUi?.third == true) }
    var meMenuOpen by remember { mutableStateOf(false) }

    // "我的"页面：null 表示未进入；进入后显示设置或下载管理
    var mePage by rememberSaveable { mutableStateOf<String?>(savedUi?.second) }

    // 全局弹窗状态：加入歌单 / 下载品质选择
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    var downloadTarget by remember { mutableStateOf<Song?>(null) }

    // v1.4.20：界面状态变化即持久化（apply 异步，无性能负担）——
    // 完全退出后从任何入口再进入，恢复最后所在界面
    LaunchedEffect(tab, mePage, showPlayer) {
        Store.saveUiState(tab, mePage, showPlayer)
    }

    val currentSong by PlayerManager.currentSong.collectAsState()
    val searchVm: SearchViewModel = viewModel()
    val playerVm: PlayerViewModel = viewModel()
    val exploreVm: ExploreViewModel = viewModel()
    val context = LocalContext.current

    // v1.4.13 #65：全局播放失败提示（解析失败/网络错误）
    val snackbarHostState = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg ->
        appScope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
    }
    LaunchedEffect(Unit) {
        PlayerManager.playError.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // v1.4.19：启动静默检查版本更新——失败静默跳过（不打扰用户）；
    // 发现新版本时弹更新提示（用户可「暂不更新」，之后仍可从关于页进入）。
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updateInfo by com.solara.music.data.UpdateManager.updateInfo.collectAsState()
    LaunchedEffect(Unit) {
        com.solara.music.data.UpdateManager.checkSilently()
    }
    LaunchedEffect(updateInfo) {
        val info = updateInfo
        // v1.4.23：「暂不更新」过的版本重启不再自动弹窗（关于页入口仍显示，新版本出现再弹）
        if (info != null && info.versionName != com.solara.music.data.UpdateManager.skippedVersion()) {
            showUpdateDialog = true
        }
    }

    val downloadTasks by DownloadManager.tasks.collectAsState()
    val activeDownloads = downloadTasks.count { it.status == DownloadStatus.DOWNLOADING }

    // v1.4.18：系统返回键分层拦截——
    // 弹窗 > 播放页 > "我的"子页面（设置/下载/本地歌曲/关于）> 主界面（正常退出）。
    // 在子页面按返回不退出 App，先回主界面；主界面再按返回才退出。
    val canExitDirectly = mePage == null && !showPlayer &&
        playlistTarget == null && downloadTarget == null
    BackHandler(enabled = !canExitDirectly) {
        when {
            playlistTarget != null -> playlistTarget = null
            downloadTarget != null -> downloadTarget = null
            showPlayer -> showPlayer = false
            mePage != null -> mePage = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    MiniPlayer(
                        onOpen = { showPlayer = true },
                        onToggleFavorite = { Store.toggleFavorite(it) },
                        onAddToPlaylist = { playlistTarget = it },
                        onDownload = { downloadTarget = it }
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0 && mePage == null,
                        onClick = { tab = 0; mePage = null },
                        icon = { Icon(Icons.Filled.Explore, contentDescription = null) },
                        label = { Text("探索") }
                    )
                    NavigationBarItem(
                        selected = tab == 1 && mePage == null,
                        onClick = { tab = 1; mePage = null },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("搜索") }
                    )
                    NavigationBarItem(
                        selected = tab == 2 && mePage == null,
                        onClick = { tab = 2; mePage = null },
                        icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                        label = { Text("收藏") }
                    )
                    NavigationBarItem(
                        selected = tab == 3 && mePage == null,
                        onClick = { tab = 3; mePage = null },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        label = { Text("最近") }
                    )
                    NavigationBarItem(
                        selected = mePage != null,
                        onClick = { meMenuOpen = true },
                        icon = {
                            Box {
                                BadgedBox(badge = {
                                    if (activeDownloads > 0) Badge { Text(activeDownloads.toString()) }
                                }) {
                                    Icon(Icons.Filled.Person, contentDescription = null)
                                }
                                // "我的"弹出门户：设置 / 下载管理
                                DropdownMenu(
                                    expanded = meMenuOpen,
                                    onDismissRequest = { meMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("设置") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Settings, contentDescription = null)
                                        },
                                        onClick = {
                                            meMenuOpen = false
                                            mePage = MePage.SETTINGS.name
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("本地歌曲") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Smartphone, contentDescription = null)
                                        },
                                        onClick = {
                                            meMenuOpen = false
                                            mePage = MePage.LOCAL_SONGS.name
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("下载管理") },
                                        leadingIcon = {
                                            BadgedBox(badge = {
                                                if (activeDownloads > 0) {
                                                    Badge { Text(activeDownloads.toString()) }
                                                }
                                            }) {
                                                Icon(
                                                    Icons.Filled.Download,
                                                    contentDescription = null
                                                )
                                            }
                                        },
                                        onClick = {
                                            meMenuOpen = false
                                            mePage = MePage.DOWNLOADS.name
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("关于") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Info, contentDescription = null)
                                        },
                                        onClick = {
                                            meMenuOpen = false
                                            mePage = MePage.ABOUT.name
                                        }
                                    )
                                }
                            }
                        },
                        label = { Text("我的") }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (mePage != null) {
                // "我的"子页面：设置 / 下载管理
                when (mePage) {
                    MePage.SETTINGS.name -> SettingsScreen()
                    MePage.DOWNLOADS.name -> DownloadScreen(
                        onBack = { mePage = null },
                        onAddToPlaylist = { playlistTarget = it }
                    )
                    MePage.LOCAL_SONGS.name -> LocalSongsScreen(
                        onBack = { mePage = null },
                        onAddToPlaylist = { playlistTarget = it },
                        onShowMessage = showMessage
                    )
                    MePage.ABOUT.name -> AboutScreen(onBack = { mePage = null })
                    else -> SettingsScreen()
                }
            } else {
                when (tab) {
                    0 -> ExploreScreen(
                        vm = exploreVm,
                        onAddToPlaylist = { playlistTarget = it },
                        onDownload = { downloadTarget = it },
                        onShowMessage = showMessage
                    )
                    1 -> SearchScreen(
                        vm = searchVm,
                        onAddToPlaylist = { playlistTarget = it },
                        onDownload = { downloadTarget = it },
                        onShowMessage = showMessage
                    )
                    2 -> LibraryScreen(
                        onAddToPlaylist = { playlistTarget = it },
                        onDownload = { downloadTarget = it },
                        onShowMessage = showMessage
                    )
                    3 -> RecentScreen(
                        onAddToPlaylist = { playlistTarget = it },
                        onDownload = { downloadTarget = it },
                        onShowMessage = showMessage
                    )
                    else -> ExploreScreen(
                        vm = exploreVm,
                        onAddToPlaylist = { playlistTarget = it },
                        onDownload = { downloadTarget = it },
                        onShowMessage = showMessage
                    )
                }
            }
        }
    }

    if (showPlayer) {
        PlayerScreen(
            vm = playerVm,
            onDismiss = { showPlayer = false },
            onDownload = { currentSong?.let { downloadTarget = it } },
            onAddToPlaylist = { playlistTarget = it }
        )
    }

    playlistTarget?.let { song ->
        AddToPlaylistSheet(song = song, onDismiss = { playlistTarget = null })
    }

    downloadTarget?.let { song ->
        QualityPickerDialog(
            song = song,
            onDismiss = { downloadTarget = null },
            onStart = { s, q ->
                DownloadManager.enqueue(context, s, q)
                downloadTarget = null
            }
        )
    }

    // v1.4.19：启动时发现新版本的更新弹窗（关于页入口共用同一组件）
    updateInfo?.let { info ->
        if (showUpdateDialog) {
            com.solara.music.ui.components.UpdateDialog(
                info = info,
                onDismiss = { showUpdateDialog = false }   // 关弹窗不取消下载（后台继续）
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    onOpen: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDownload: (Song) -> Unit
) {
    val song by PlayerManager.currentSong.collectAsState()
    val isPlaying by PlayerManager.isPlaying.collectAsState()
    val favorites by Store.favorites.collectAsState()
    val s: Song = song ?: return
    var menuOpen by remember { mutableStateOf(false) }
    val isFavorite = favorites.any { it.sameAs(s) }
    // 上划展开：累计向上位移超过阈值即触发（向下划忽略）
    var dragAccum by remember { mutableStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onDragEnd = { dragAccum = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount < 0) dragAccum += -dragAmount
                            if (dragAccum > 40f) {
                                dragAccum = 0f
                                onOpen()
                            }
                        }
                    )
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(song = s, size = 44.dp, corner = 10.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    text = s.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = s.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = { PlayerManager.togglePlayPause() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放或暂停"
                )
            }
            IconButton(onClick = { PlayerManager.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("上一首") },
                        leadingIcon = {
                            Icon(Icons.Filled.SkipPrevious, null)
                        },
                        onClick = { menuOpen = false; PlayerManager.previous() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                        leadingIcon = {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite
                                else Icons.Filled.FavoriteBorder,
                                null
                            )
                        },
                        onClick = { menuOpen = false; onToggleFavorite(s) }
                    )
                    DropdownMenuItem(
                        text = { Text("加入歌单") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        onClick = { menuOpen = false; onAddToPlaylist(s) }
                    )
                    DropdownMenuItem(
                        text = { Text("下载") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { menuOpen = false; onDownload(s) }
                    )
                }
            }
        }
    }
}

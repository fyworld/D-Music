@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.solara.music.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solara.music.data.Song
import com.solara.music.data.Store
import com.solara.music.ui.favorites.FavoritesScreen
import com.solara.music.ui.playlists.PlaylistsScreen
import kotlinx.coroutines.launch

/**
 * 收藏 + 歌单合并页（底部导航「收藏」Tab）：
 * 顶部两栏 Tab（收藏 / 歌单），支持左右滑动（HorizontalPager）
 * 与点击 Tab 切换。
 */
@Composable
fun LibraryScreen(
    onAddToPlaylist: (Song) -> Unit = {},
    onDownload: (Song) -> Unit = {}
) {
    val favorites by Store.favorites.collectAsState()
    val playlists by Store.playlists.collectAsState()

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(4.dp))
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    if (pagerState.currentPage != 0) {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                },
                text = { Text("收藏（${favorites.size}）") }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    if (pagerState.currentPage != 1) {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                },
                text = { Text("歌单（${playlists.size}）") }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> FavoritesScreen(
                    onAddToPlaylist = onAddToPlaylist,
                    onDownload = onDownload
                )
                else -> PlaylistsScreen(
                    onAddToPlaylist = onAddToPlaylist,
                    onDownload = onDownload
                )
            }
        }
    }
}

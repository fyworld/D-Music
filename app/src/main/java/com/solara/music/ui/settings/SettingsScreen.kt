@file:OptIn(ExperimentalMaterial3Api::class)

package com.solara.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.solara.music.data.DownloadManager
import com.solara.music.data.ExploreGenres
import com.solara.music.data.MusicApi
import com.solara.music.data.Qualities
import com.solara.music.data.Store
import com.solara.music.data.ThemeMode

@Composable
fun SettingsScreen() {
    val settings by Store.settings.collectAsState()
    var apiInput by remember(settings.apiBaseUrl) { mutableStateOf(settings.apiBaseUrl) }
    val context = LocalContext.current
    // v1.4.5：所有文件访问状态——从系统设置返回（ON_RESUME）时刷新
    var hasAllFilesAccess by remember { mutableStateOf(DownloadManager.hasAllFilesAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = DownloadManager.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        SettingsCard(title = "播放音质") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Qualities.all.size) { i ->
                    val q = Qualities.all[i]
                    FilterChip(
                        selected = q.value == settings.quality,
                        onClick = { Store.updateSettings { it.copy(quality = q.value) } },
                        label = { Text("${q.label} ${q.description}") }
                    )
                }
            }
            Text(
                text = "无损音质需要曲源支持，部分歌曲可能回落到有损格式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        SettingsCard(title = "外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.FOLLOW_SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { Store.updateSettings { it.copy(themeMode = mode) } },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "动态取色（Material You）",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.dynamicColor,
                    onCheckedChange = { checked ->
                        Store.updateSettings { it.copy(dynamicColor = checked) }
                    }
                )
            }
            Text(
                text = "开启后跟随系统壁纸取色（仅 Android 12 及以上生效）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard(title = "探索雷达风格") {
            Text(
                text = "选择喜欢的音乐风格，探索雷达将从中随机推荐。不选则使用全部风格。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val selected = settings.radarGenres.toHashSet()
            FlowGenreGrid(
                genres = ExploreGenres.all,
                selected = selected,
                onToggle = { g ->
                    val next = if (g in selected) selected - g else selected + g
                    Store.updateSettings { it.copy(radarGenres = ExploreGenres.all.filter { f -> f in next }) }
                }
            )
        }

        SettingsCard(title = "聚合 API 地址") {
            OutlinedTextField(
                value = apiInput,
                onValueChange = { apiInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Base URL") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        Store.updateSettings { it.copy(apiBaseUrl = apiInput.trim()) }
                    }
                ) { Text("保存") }
                OutlinedButton(
                    onClick = {
                        apiInput = MusicApi.DEFAULT_BASE_URL
                        Store.updateSettings { it.copy(apiBaseUrl = MusicApi.DEFAULT_BASE_URL) }
                    }
                ) { Text("恢复默认") }
            }
            Text(
                text = "默认使用 GD音乐台聚合接口，被拦截时可在部署端更换备用地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SettingsCard(title = "本地文件管理权限") {
            Text(
                text = if (hasAllFilesAccess) {
                    "已开启「所有文件访问」：删除、重命名本地歌曲时不会再弹出授权提示。"
                } else {
                    "未开启。删除、重命名其他应用创建的歌曲文件时，需要逐次确认系统授权。" +
                        "开启后只需设置一次，之后所有操作完全静默。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val intent = DownloadManager.allFilesAccessSettingsIntent(context)
                    if (intent != null) {
                        runCatching { context.startActivity(intent) }
                    }
                },
                enabled = !hasAllFilesAccess
            ) { Text(if (hasAllFilesAccess) "已开启" else "去开启") }
        }

        // 底部版本信息
        Text(
            text = "版本：D music v1.4.12",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * 风格多选网格：固定高度可滚动，避免嵌套滚动冲突。
 */
@Composable
private fun FlowGenreGrid(
    genres: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(genres) { g ->
            FilterChip(
                selected = g in selected,
                onClick = { onToggle(g) },
                label = { Text(g, maxLines = 1) }
            )
        }
    }
}

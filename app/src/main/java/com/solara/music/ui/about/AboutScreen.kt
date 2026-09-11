package com.solara.music.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.solara.music.R

/** 三个超链接的跳转目标。 */
private const val URL_REPO = "https://github.com/fyworld/D-Music.git"
private const val URL_RELEASES = "https://github.com/fyworld/D-Music/releases"
private const val URL_ISSUES = "https://github.com/fyworld/D-Music/issues"

/**
 * 关于页面：应用图标 + "D music" 标题 + 介绍文案 + 三个超链接
 * （开源地址 / 下载地址 / 提交问题）+ 尾部免责声明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(24.dp))

        // 顶部：应用图标 + 标题
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "D music",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "v1.4.12",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))

        // 正文卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "由 Dong 一个厌烦音乐平台“广告弹窗、音质垃圾、音乐收费”，" +
                    "喜欢“共享、免费，高品质音乐”的老登音乐爱好者，" +
                    "基于开源项目 Solara 开发的极简音乐播放器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "免费聆听无损音质，免费下载无损音乐。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "本软件完全免费，代码已开源（CC BY-NC-SA 4.0，禁止商用）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "开源地址：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinkRow(text = "https://github.com/fyworld/D-Music") {
                    uriHandler.openUri(URL_REPO)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "下载地址：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinkRow(text = "https://github.com/fyworld/D-Music/releases") {
                    uriHandler.openUri(URL_RELEASES)
                }
            }

            IssueLinkRow(onClick = { uriHandler.openUri(URL_ISSUES) })

            HorizontalDivider()

            // 尾部免责声明（逐条列出，与 README 保持一致）
            Text(
                text = "免责声明",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DisclaimerItem("本软件不提供、不存储、不分发任何音乐文件，音频内容来自第三方免费聚合 API。")
            DisclaimerItem("音乐版权归原权利人所有，本软件仅供个人学习交流使用，禁止任何商业用途。")
            DisclaimerItem("本软件基于开源项目 Solara 重构，继承 CC BY-NC-SA 4.0 协议。")
            DisclaimerItem("本软件按\u201c原样\u201d提供，不提供任何明示或暗示的保证，使用产生的一切法律责任由用户自行承担。")
            DisclaimerItem("版权投诉请通过 GitHub Issues 提交。")
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** "有问题可以到 Github 提交问题"——仅"提交问题"四字为主题色可点超链接。 */
@Composable
private fun IssueLinkRow(onClick: () -> Unit) {
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
            append("有问题可以到 Github ")
        }
        pushStringAnnotation(tag = "issue_link", annotation = URL_ISSUES)
        withStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("提交问题")
        }
        pop()
    }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) { offset ->
        if (annotated.getStringAnnotations("issue_link", offset, offset).isNotEmpty()) {
            onClick()
        }
    }
}

/** 免责声明条目：圆点 + 文字。 */
@Composable
private fun DisclaimerItem(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "· ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 可点击的超链接行：主题色 + 外链小图标。 */
@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "打开链接",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

package com.solara.music.ui.about

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.solara.music.BuildConfig
import com.solara.music.R
import com.solara.music.data.UpdateManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch

/** 三个超链接的跳转目标。 */
private const val URL_REPO = "https://github.com/fyworld/D-Music.git"
private const val URL_RELEASES = "https://github.com/fyworld/D-Music/releases"
private const val URL_ISSUES = "https://github.com/fyworld/D-Music/issues"

/**
 * 关于页面：应用图标 + "D music" 标题 + 介绍文案 + 三个超链接
 * （开源地址 / 下载地址 / 提交问题）+ 尾部免责声明 + 打赏入口。
 * v1.4.19：版本号下显示「发现新版本」入口（启动检查有更新时）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var showDonate by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    val updateInfo by UpdateManager.updateInfo.collectAsState()
    val context = LocalContext.current

    // v1.4.21：手动检查更新——失败/无新版都给明确反馈（静默检查失败时用户无从得知）
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checkState by remember { mutableStateOf<String?>(null) }   // null=空闲 "checking"=检查中 其他=结果文案

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
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // v1.4.19：发现新版本——版本号下入口（启动静默检查有更新时显示）
            if (updateInfo != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showUpdate = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "发现新版本 v${updateInfo?.versionName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (checkState != null && checkState != "checking") {
                // v1.4.21：手动检查的结果反馈（已是最新/失败原因——检查中由按钮内 spinner 展示）
                val result = checkState
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result?.startsWith("检查失败") == true)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }
            // v1.4.21：手动「检查更新」入口——启动静默检查失败时（GitHub 直连时通时断）
            // 用户无从得知也无从重试，这里给一个带反馈的手动入口
            if (updateInfo == null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        if (checkState == "checking") return@TextButton
                        checkState = "checking"
                        scope.launch {
                            checkState = try {
                                val info = UpdateManager.checkNow()
                                if (info != null) "发现新版本 v${info.versionName}"
                                else "已是最新版本（v${UpdateManager.currentVersion()}）"
                            } catch (e: Exception) {
                                "检查失败：${e.message ?: "网络错误"}"
                            }
                        }
                    },
                    enabled = checkState != "checking"
                ) {
                    if (checkState == "checking") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (checkState == "checking") "正在检查…" else "检查更新",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
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

        // v1.4.18：打赏入口（自愿，不影响任何功能）——lite 纯净版不显示
        if (BuildConfig.DONATE_ENABLED) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledTonalButton(onClick = { showDonate = true }) {
                    Text("☕ 请我喝杯咖啡")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "本项目完全免费、无广告，所有功能对所有用户开放。如果这个软件帮到了你，" +
                    "欢迎自愿打赏支持开发——但完全不影响任何功能的使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    // v1.4.18：打赏弹层
    if (showDonate && BuildConfig.DONATE_ENABLED) {
        DonateSheet(onDismiss = { showDonate = false })
    }

    // v1.4.19：版本更新弹窗（共享组件 ui/components/UpdateDialog.kt）
    updateInfo?.let { info ->
        if (showUpdate) {
            com.solara.music.ui.components.UpdateDialog(
                info = info,
                onDismiss = { showUpdate = false }   // 关弹窗不取消下载（后台继续）
            )
        }
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

/**
 * v1.4.18：打赏底部弹窗——自愿打赏支持开发。
 * 收款码内嵌 App 资源（drawable-nodpi/donate_wechat_qr.jpg，不打 API 请求，
 * 离线可看）。**长按收款码弹菜单**：保存到相册 / 打开微信扫一扫——
 * 同屏二维码无法直接摄像头扫描，标准路径是保存图片后用微信
 * 「扫一扫 → 相册」选图识别。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DonateSheet(onDismiss: () -> Unit) {
    var qrMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "支持开发（自愿）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "D Music 是一个个人维护的开源项目，基于 Solara（网页版）重构开发，" +
                    "遵循 CC BY-NC-SA 4.0 协议，永久免费、无广告、无内购。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "开发占用了大量业余时间，如果你觉得好用，可以扫下方二维码自愿打赏，金额不限，一分也是鼓励。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // 微信收款码（内嵌资源，离线可显示；长按弹菜单）
            Box {
                Image(
                    painter = painterResource(R.drawable.donate_wechat_qr),
                    contentDescription = "微信收款码",
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = { },
                            onLongClick = { qrMenuOpen = true }
                        )
                )
                DropdownMenu(
                    expanded = qrMenuOpen,
                    onDismissRequest = { qrMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("保存收款码到相册") },
                        leadingIcon = { Icon(Icons.Filled.Save, null) },
                        onClick = {
                            qrMenuOpen = false
                            val ok = saveQrToGallery(context)
                            Toast.makeText(
                                context,
                                if (ok) "收款码已存入相册" else "保存失败，可截屏识别",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("保存并打开微信") },
                        leadingIcon = { Icon(Icons.Filled.QrCodeScanner, null) },
                        onClick = {
                            qrMenuOpen = false
                            // 一步到位：先把收款码存入相册，再拉起微信主界面
                            // （微信已封禁第三方深链直达扫一扫，只能到主界面）。
                            // 需要 Manifest <queries> 声明包可见（Android 11+）。
                            val saved = saveQrToGallery(context)
                            val launchIntent = runCatching {
                                context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                            }.getOrNull()
                            if (launchIntent != null) {
                                runCatching {
                                    context.startActivity(
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                Toast.makeText(
                                    context,
                                    if (saved) "已存相册并打开微信，扫一扫→相册选图"
                                    else "已打开微信，可截屏后扫一扫识别",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    if (saved) "收款码已存相册，未检测到微信"
                                    else "保存失败且未检测到微信，可截屏识别",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "微信 · 自愿打赏",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "长按二维码：存入相册 / 保存并打开微信",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "请注意：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DisclaimerItem("打赏纯属自愿，不打赏可正常使用全部功能，两者没有任何区别。")
                DisclaimerItem("打赏不解锁任何功能、不提供任何专属服务或技术支持承诺。")
                DisclaimerItem("打赏不是购买行为，无法退款。")
                DisclaimerItem("所有音频内容来自第三方接口，版权归原权利人所有。")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * 收款码保存到相册 Pictures/D_Music：
 * - Android 10+：MediaStore insert（自有文件无需权限）
 * - Android 9-：直写公共 Pictures 目录（需 WRITE_EXTERNAL_STORAGE，未声明则失败，
 *   调用方 Toast 引导截屏兜底）+ MediaScanner 通知媒体库
 */
private fun saveQrToGallery(context: Context): Boolean = runCatching {
    val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.donate_wechat_qr)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "D_Music_打赏码_${System.currentTimeMillis()}.jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/D_Music")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: return false
        true
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "D_Music"
        )
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "D_Music_打赏码_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        true
    }
}.getOrDefault(false)

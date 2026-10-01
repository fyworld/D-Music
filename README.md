# D Music · Android

> 由 Dong —— 一个厌烦音乐平台"广告弹窗、音质垃圾、音乐收费"，喜欢"共享、免费，高品质音乐"的老登音乐爱好者 —— 基于开源项目 [Solara](https://github.com/akudamatata/Solara) 开发的极简音乐播放器。

**免费聆听无损音质，免费下载无损音乐。**

音乐数据来自 [GD音乐台](https://music.gdstudio.xyz)（music.gdstudio.xyz）提供的免费聚合 API。

## 功能特性

### 播放
- **跨站曲库检索**：4 个音源切换（搜索框左侧下拉），支持分页加载
- **多码率播放**：128K / 192K / 320K / 无损 FLAC
- **探索雷达**：13 种音乐风格随机探索，队列空自动续播；主卡片背景为当前播放歌曲封面
- **后台播放**：自定义通知栏播放控制（收藏/上一首/播放/下一首），锁屏可控；冷启动通知即带当前歌曲封面与四键样式；通知封面带磁盘缓存，断网播放缓存歌曲也能正常显示
- **蓝牙切歌**：蓝牙耳机/系统媒体面板的上一首/下一首直达应用层队列
- **播放队列**：顺序播放 / 顺序循环 / 单曲循环 / 随机（四种模式，重启记忆）；打开队列自动定位当前歌曲
- **左右声道平衡**：设置页滑杆调节（偏左/偏右百分比显示），单声道耳机、蓝牙单耳或某声道无声时把声像拉到有声那侧；仅 App 内生效
- **本地优先**：已下载的歌曲自动播本地文件，完全离线可用
- **断网兜底**：在线歌播放后自动后台补全缓存（听一半切走的歌也能补到全量），断网时全量缓存的歌照常播放

### 界面
- **主页面滑动切换**：探索/搜索/收藏/最近四页左右滑动，与底部导航双向同步
- **播放页**：封面页 ↔ 整页歌词无限循环左右滑动，歌词逐行高亮自动滚动；拖动歌词暂停跟随、松手数秒后自动恢复
- **手势操作**：播放栏上划展开播放页、播放页下划收缩
- **清新主题**：Material 3 + 圆角卡片，亮/暗双主题 + 动态取色（Android 12+）+ 六色主题色（薄荷绿/天空蓝/梦幻紫/樱花粉/活力橙/玫瑰红）
- **列表播放标记**：所有歌曲列表（搜索/探索/收藏/歌单/最近/本地/下载管理）当前播放歌曲名前显示播放中图标+主色；本地文件夹浏览时当前播放歌所属文件夹（含子目录）同样标记
- **列表排序**：搜索/探索/收藏/歌单支持长按拖动排序
- **批量操作**：收藏/歌单/搜索/探索/最近播放/本地歌曲/下载管理支持多选——批量收藏、批量加入歌单、批量移除；多选中按返回键退出多选

### 本地音乐管理
- **多码率下载**：保存到公共 `Music/D_Music`，命名「歌手 - 歌名.扩展名」；后台下载时通知栏显示聚合进度，完成后通知汇总
- **下载管理页**：进行中/失败/等待任务（进度条+取消/移除）与已完成歌曲（完整操作：播放、加入歌单、收藏、下载歌词、重命名、删除、多选批量、播放全部）分区管理；已完成列表持久化，重启不丢
- **标签嵌入**：下载完成后自动把封面、歌词、标题、歌手嵌入音频文件（MP3 ID3v2 / FLAC PICTURE）
- **本地歌曲文件夹浏览**：打开即浏览内部存储根目录，逐级进入文件夹（记住上次浏览位置）；支持 mp3 / flac / m4a / aac / ogg / wav / ape / wma / dts 九种格式，任意目录直接播放
- **DTS 播放**：自研 DTS 解析器（支持 16bit/14bit 大小端四种同步字变体）+ FFmpeg 软解兜底——无硬件解码器的设备也能播 DTS；支持 DTS 文件拖动进度条
- **文件管理**：歌曲/文件夹重命名（同步更新所有引用）、删除（其他应用创建的文件走系统授权流程）
- **文件分享**：播放页「分享」把音频文件本体发到微信等应用（无本地文件时提示先下载）
- **封面体系**：自定义封面（相册选图，最高优先级）→ 内嵌封面 → 同名图片 → 在线搜索匹配（匹配后嵌入文件，其他播放器可见）；支持批量匹配当前目录
- **歌词体系**：App 缓存 → 内嵌歌词 → 同名 .lrc 文件 → 在线搜索匹配；「下载歌词」对话框可搜索候选手动挑选，支持批量下载；匹配成功后自动嵌入文件（MP3）或写伴生 .lrc（FLAC），**换任何播放器都能显示歌词**
- **歌词校准**：整曲偏移微调 + 逐句打点模式（唱到当前句点「打点」记录真实开唱时刻），校准结果固化成标准 LRC；「歌词编辑」支持纯文本歌词编辑后打点对齐

### 数据
- **全量持久化**：收藏 / 歌单 / 播放队列 / 播放模式 / 最近播放 / 探索搜索结果 / 本地浏览位置
- **播放缓存**：在线歌曲边播边缓存（默认 30GB LRU，10/30/50GB 档位可调），重听秒开零流量；播放后自动后台补全到全量（断网可完整重播）；设置页可调档位/清空
- **封面缓存**：在线封面 URL 磁盘持久化 + 通知封面位图磁盘缓存，冷启动秒显封面不联网
- **卸载重装恢复**：公共目录备份 + Android Auto Backup 双保险
- **API 可配置**：默认 GD音乐台聚合接口，被拦截时可在设置中更换备用地址
- **应用内更新**：启动静默检查 GitHub 新版本（失败自动走镜像重试，仍失败静默跳过），回前台节流补查；关于页可手动「检查更新」；发现新版提示并可下载安装（下载直连失败也自动走镜像）；下载时通知栏显示进度，完成后点通知即可安装

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 播放 | Media3 (ExoPlayer 1.3.1) + MediaSession |
| DTS 软解 | FFmpeg 6.0（NDK r26d 预编译静态库，仅 dts 解码器极简构建） |
| 网络 | OkHttp + org.json |
| 图片 | Coil |
| 标签 | mp3agic（ID3v2 读写） |
| 持久化 | SharedPreferences (JSON) |
| 架构 | MVVM（ViewModel + StateFlow） |

## 构建

**环境要求**：JDK 17、Android SDK 34（compileSdk 34 / minSdk 24）、CMake 3.22.1、NDK r26d（DTS 软解模块构建用）

FFmpeg 静态库已预编译提交在 `app/src/main/cpp/ffmpeg/android-libs/arm64-v8a/`（仅 arm64-v8a），无需自行编译 FFmpeg。

```bash
# Android Studio：打开项目目录，Sync 后直接 Run

# 或命令行（full 标准版 / lite 纯净版）
gradle assembleFullDebug
gradle assembleLiteDebug
# 产物：app/build/outputs/apk/full/debug/app-full-debug.apk
#      app/build/outputs/apk/lite/debug/app-lite-debug.apk
```

## 项目结构

```
app/src/main/java/com/solara/music/
├── MainActivity.kt            # 入口：权限申请、主题
├── InstallApkActivity.kt      # 更新安装落地页（通知点击拉起安装器）
├── SolaraApp.kt               # Application：Store 初始化、队列恢复
├── data/
│   ├── Song.kt                # 歌曲实体 / 音源 / 音质 / 探索风格定义
│   ├── MusicApi.kt            # GD音乐台聚合 API 客户端
│   ├── DownloadManager.kt     # 下载 / 文件夹浏览 / 文件操作 / 存储授权
│   ├── LocalCoverExtractor.kt # 封面提取 + 在线匹配
│   ├── LyricRepository.kt     # 歌词三层架构（缓存/内嵌/在线）+ 下载歌词
│   ├── TagEmbedder.kt         # mp3agic 标签嵌入（封面/歌词）
│   ├── UpdateManager.kt       # 应用内更新（GitHub + 镜像重试）
│   └── Store.kt               # 全状态持久化
├── lyrics/LrcParser.kt        # LRC 解析（含纯文本伪时间轴）
├── player/
│   ├── PlaybackService.kt     # MediaSessionService + 自定义通知
│   ├── PlaybackCache.kt       # 播放缓存（SimpleCache LRU + 后台补全）
│   ├── PlayerManager.kt       # 全局播放控制
│   ├── SchemeRoutingDataSource.kt  # 本地/在线数据源分流（本地绕过缓存）
│   ├── DtsExtractor.kt        # 自研 DTS 解析器（四种同步字变体）
│   ├── ChannelBalanceRenderersFactory.kt  # 左右声道平衡渲染器工厂
│   └── CachedBitmapLoader.kt  # 通知封面位图磁盘缓存
├── ui/                        # Compose 界面
│   ├── SolaraApp.kt           # 主界面（四页滑动 + 底部导航 + 播放页）
│   ├── explore/ search/ library/ favorites/ playlists/
│   ├── recent/ local/ download/ settings/ about/ player/
│   └── components/            # SongRow / 封面与歌词编辑对话框 /
│                               # 分享 / 本地文件操作 / 批量操作 / 更新弹窗
└── cpp/                       # FFmpeg DTS 软解 JNI 模块（CMake + 预编译静态库）
```

## API 说明

默认聚合接口为 GD音乐台免费 API（`https://music-api.gdstudio.xyz/api.php`），接口契约：

| 类型 | 参数 |
|------|------|
| 搜索 | `?types=search&source=netease&name=关键词&count=30&pages=1` |
| 播放 | `?types=url&id=歌曲ID&source=音源&br=320` |
| 歌词 | `?types=lyric&id=歌词ID&source=音源` |
| 封面 | `?types=pic&id=封面ID&source=音源&size=300` |

接口被拦截时，可在 App「设置 → 聚合 API 地址」中替换为自建或备用地址。

## 许可与免责声明

### 数据来源

本项目**不提供、不存储、不分发任何音乐文件**。所有音频内容、歌词与封面均来自 [GD音乐台](https://music.gdstudio.xyz)（music.gdstudio.xyz）提供的免费聚合 API，播放与下载行为由用户自行发起。

### 许可协议

本项目基于 [Solara](https://github.com/akudamatata/Solara)（网页版）重构开发，**继承其 CC BY-NC-SA 4.0 协议**：

- **署名（BY）**：须保留原作者署名与原项目链接
- **非商业（NC）**：**禁止任何商业用途**（收费分发、广告变现、商业集成等）
- **相同方式共享（SA）**：衍生项目必须以相同协议开源

完整协议文本见 [LICENSE](LICENSE)。

### 免责声明

1. **本项目仅供个人学习交流使用**，不得用于任何商业用途。
2. **本项目不提供、不存储、不分发任何音乐文件**。所有音频内容均来自第三方免费聚合 API（GD音乐台），播放与下载行为由用户自行发起。
3. **音乐版权归原始权利人所有**。用户使用本软件产生的任何法律责任（包括但不限于侵犯版权），由用户自行承担。
4. 本软件按"原样"提供，不提供任何明示或暗示的保证。作者不对因使用本软件造成的任何损失承担责任。
5. 如果您是音乐版权所有者，认为本项目的接口调用侵犯了您的权益，请提交 [Issue](https://github.com/fyworld/D-Music/issues)，我们会及时处理。

### 致谢

- [Solara](https://github.com/akudamatata/Solara) —— 原开源网页播放器（CC BY-NC-SA）
- [GD音乐台](https://music.gdstudio.xyz) —— 免费音乐聚合 API，本项目全部音乐数据的来源
- 所有为本项目提出建议和反馈的用户

---

**开源地址**：https://github.com/fyworld/D-Music
**下载地址**：https://github.com/fyworld/D-Music/releases
**问题反馈**：https://github.com/fyworld/D-Music/issues

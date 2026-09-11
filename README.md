# D Music · Android

> 由 Dong —— 一个厌烦音乐平台"广告弹窗、音质垃圾、音乐收费"，喜欢"共享、免费，高品质音乐"的老登音乐爱好者 —— 基于开源项目 [Solara](https://github.com/akudamatata/Solara) 开发的极简音乐播放器。

**免费聆听无损音质，免费下载无损音乐。**

当前版本：**v1.4.18**（提供两个版本，任选其一安装，可共存）

| 版本 | 包名 | 说明 |
|------|------|------|
| 标准版 full | `com.solara.music` | 含全部功能，包括「关于」页的自愿打赏入口 |
| 纯净版 lite | `com.solara.music.lite` | 功能完全相同，仅去掉打赏入口 |

## 功能特性

### 播放
- **跨站曲库检索**：网易云 / 酷我双源，支持分页加载
- **多码率播放**：128K / 192K / 320K / 无损 FLAC
- **探索雷达**：13 种音乐风格随机探索，队列空自动续播
- **后台播放**：自定义通知栏播放控制（收藏/上一首/播放/下一首），锁屏可控
- **播放队列**：顺序 / 单曲循环 / 随机；打开队列自动定位当前歌曲
- **本地优先**：已下载的歌曲自动播本地文件，完全离线可用

### 界面
- **播放页**：封面页 ↔ 整页歌词无限循环左右滑动，歌词逐行高亮自动滚动
- **手势操作**：播放栏上划展开播放页、播放页下划收缩
- **清新主题**：Material 3 薄荷绿 + 圆角卡片，亮/暗双主题 + 动态取色（Android 12+）
- **列表排序**：搜索/探索/收藏/歌单/本地歌曲支持长按拖动排序

### 本地音乐管理
- **多码率下载**：保存到公共 `Music/D_Music`，命名「歌手 - 歌名.扩展名」
- **标签嵌入**：下载完成后自动把封面、歌词、标题、歌手嵌入音频文件（MP3 ID3v2 / FLAC PICTURE）
- **本地扫描**：默认扫描下载目录，也可用内置文件夹浏览器选任意目录（含子目录）
- **文件管理**：重命名、删除（其他应用创建的文件走系统授权流程）
- **封面体系**：内嵌封面 → 同名图片 → 在线搜索匹配（匹配后嵌入文件，其他播放器可见）
- **歌词体系**：App 缓存 → 内嵌歌词 → 同名 .lrc 文件 → 在线搜索匹配；匹配成功后自动嵌入文件（MP3）或写伴生 .lrc（FLAC），**换任何播放器都能显示歌词**

### 数据
- **全量持久化**：收藏 / 歌单 / 播放队列 / 最近播放 / 探索搜索结果
- **卸载重装恢复**：公共目录备份 + Android Auto Backup 双保险
- **API 可配置**：默认 GD音乐台聚合接口，被拦截时可在设置中更换备用地址

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 播放 | Media3 (ExoPlayer 1.2.1) + MediaSession |
| 网络 | OkHttp + org.json |
| 图片 | Coil |
| 标签 | mp3agic（ID3v2 读写） |
| 持久化 | SharedPreferences (JSON) |
| 架构 | MVVM（ViewModel + StateFlow） |

## 构建

**环境要求**：JDK 17、Android SDK 34（compileSdk 34 / minSdk 24）

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
├── SolaraApp.kt               # Application：Store 初始化、队列恢复
├── data/
│   ├── Song.kt                # 歌曲实体 / 音源 / 音质定义
│   ├── MusicApi.kt            # GD音乐台聚合 API 客户端
│   ├── DownloadManager.kt     # 下载 / 本地扫描 / 文件操作 / 存储授权
│   ├── LocalCoverExtractor.kt # 封面提取 + 在线匹配
│   ├── LyricRepository.kt     # 歌词三层架构（缓存/内嵌/在线）
│   ├── TagEmbedder.kt         # mp3agic 标签嵌入（封面/歌词）
│   └── Store.kt               # 全状态持久化
├── lyrics/LrcParser.kt        # LRC 解析（含纯文本伪时间轴）
├── player/
│   ├── PlaybackService.kt     # MediaSessionService + 自定义通知
│   └── PlayerManager.kt       # 全局播放控制
└── ui/                        # Compose 界面（explore/search/playlists/
                               # favorites/local/settings/about/player）
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
- [GD音乐台](https://music.gdstudio.xyz) —— 免费音乐聚合 API
- 所有为本项目提出建议和反馈的用户

---

**开源地址**：https://github.com/fyworld/D-Music
**下载地址**：https://github.com/fyworld/D-Music/releases
**问题反馈**：https://github.com/fyworld/D-Music/issues

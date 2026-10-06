# D Music · Android

> 由 Dong —— 一个厌烦音乐平台"广告弹窗、音质垃圾、音乐收费"，喜欢"共享、免费，高品质音乐"的老登音乐爱好者 —— 开发的极简音乐播放器。

**支持无损音质播放与下载。**

音乐数据来自 [GD音乐台](https://music.gdstudio.xyz)（music.gdstudio.xyz）提供的免费聚合 API；支持导入 lx-music 生态的自定义音源脚本。

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

### 开源组件

本项目使用以下开源组件，感谢这些项目的作者与社区：

| 组件 | 用途 | 许可证 |
|------|------|--------|
| [FFmpeg](https://ffmpeg.org) 6.0 | DTS 软解（静态链接，仅 dts 解码器） | LGPL-2.1-or-later |
| [QuickJS](https://github.com/quickjs-ng/quickjs)（quickjs-ng） | 自定义音源脚本引擎（经 [quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper) 封装） | MIT |
| [quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper) | QuickJS 的 Android/JVM 绑定 | Apache-2.0 |
| [Kotlin](https://kotlinlang.org) / [Compose](https://developer.android.com/jetpack/compose) / [Material 3](https://m3.material.io) | UI 框架 | Apache-2.0 |
| [Media3 / ExoPlayer](https://developer.android.com/media/media3) 1.3.1 | 播放器 | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) 4.12 | 网络 | Apache-2.0 |
| [Coil](https://coil-kt.github.io/coil/) 2.6 | 图片加载 | Apache-2.0 |
| [mp3agic](https://github.com/mpatric/mp3agic) 0.9.1 | ID3v2 标签读写 | MIT |

各组件的完整许可证文本见其官方仓库。

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

## API 与音源说明

### 聚合 API（默认）

默认聚合接口为 GD音乐台免费 API（`https://music-api.gdstudio.xyz/api.php`），接口契约：

| 类型 | 参数 |
|------|------|
| 搜索 | `?types=search&source=netease&name=关键词&count=30&pages=1` |
| 播放 | `?types=url&id=歌曲ID&source=音源&br=320` |
| 歌词 | `?types=lyric&id=歌词ID&source=音源` |
| 封面 | `?types=pic&id=封面ID&source=音源&size=300` |

接口被拦截时，可在 App「设置 → 聚合 API 地址」中替换为自建或备用地址。

### 自定义音源（v1.5.1+）

支持导入 lx-music 生态的 `.js` 音源脚本（在脚本运行沙箱中执行，与主程序隔离）。启用后：

- 搜索页可直连各平台公开接口搜歌（界面内以「源 1 ~ 源 5」等通用代号显示）
- 取歌、歌词、封面由用户导入的脚本或内置公开接口提供
- 本项目**不内置、不维护、不分发任何音源脚本**，脚本由用户自行获取与导入，使用行为由用户自行负责

## 项目协议

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可证发行，以下条款是对 Apache License 2.0 的补充，如有冲突，以以下条款为准。

---

*词语约定：本协议中的"本项目"指 D Music（Android 音乐播放器）项目；"使用者"指下载、安装或使用本项目的任何人；"聚合 API"指 GD音乐台等第三方提供的音乐数据聚合服务；"自定义音源"指使用者自行导入的第三方音源脚本及其返回的数据。*

### 一、数据来源

1.1 本项目不提供、不存储、不分发任何音乐文件。在线搜索、歌词、封面等元数据来自第三方聚合 API 或各音乐平台的公开接口，本项目仅对返回数据进行筛选与展示，不对数据的合法性、准确性负责。

1.2 本项目本身不内置任何取歌能力。启用自定义音源时，音频链接由使用者自行导入的音源脚本返回——本项目所做的只是将希望播放的歌曲信息传递给脚本，若脚本返回链接则进行播放，本项目无法校验其内容，可能出现播放内容与预期不符或无法播放的情况。

1.3 使用者的收藏、歌单、播放队列等本地数据存储在使用者设备上，本项目不对这些数据的丢失负责。

### 二、版权数据

2.1 使用本项目的过程中产生的任何版权数据（包括但不限于音频、图像、歌词、名称），版权归原始权利人所有。为避免侵权，使用者务必在 **24 小时内** 清除使用过程中产生的版权数据。

### 三、资源使用

3.1 本项目内使用的部分资源（包括但不限于图标、图片）来源于互联网。如果出现侵权可联系本项目移除。

### 四、免责声明

4.1 由于使用本项目产生的任何直接、间接、特殊、偶然或结果性损害（包括但不限于商誉损失、数据丢失、设备故障）由使用者自行承担。

### 五、使用限制

5.1 本项目完全免费，开源发布于 GitHub，仅用于技术学习交流。本项目不对项目内的技术可能存在违反当地法律法规的行为作保证。

5.2 **禁止在违反当地法律法规的情况下使用本项目。** 使用者在明知或不知当地法律法规不允许的情况下使用本项目所造成的任何违法违规行为由使用者承担，本项目不承担由此造成的任何责任。

### 六、版权保护

6.1 音乐平台不易，请尊重版权，支持正版。

### 七、非商业性质

7.1 本项目仅用于对技术可行性的探索及研究，**禁止任何商业用途**（收费分发、广告变现、商业集成等），不接受任何商业合作。

### 八、接受协议

8.1 若你使用了本项目，即代表你接受本协议。

---

若对此有疑问，请通过 [GitHub Issues](https://github.com/fyworld/D-Music/issues) 联系。

## 致谢

- [Solara](https://github.com/akudamatata/Solara) —— 项目初期参考其产品设计
- [lx-music](https://github.com/lyswhut/lx-music-mobile) —— 自定义音源脚本契约与平台接口实现的参考（Apache-2.0）
- [GD音乐台](https://music.gdstudio.xyz) —— 免费音乐聚合 API
- 所有为本项目提出建议和反馈的用户

---

**开源地址**：https://github.com/fyworld/D-Music
**下载地址**：https://github.com/fyworld/D-Music/releases
**问题反馈**：https://github.com/fyworld/D-Music/issues

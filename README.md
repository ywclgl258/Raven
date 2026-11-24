# Raven · 渡鸦

> **多源原生媒体浏览、影院级播放与沉浸式阅读 Android 客户端**  
> Package: `com.raven.app`（Debug: `com.raven.app.debug`） · Kotlin `2.3.21` · Jetpack Compose · Material 3 Adaptive · Media3 `1.11.1` · Room `2.8.5` · Koin `4.2.2`

Raven（**渡鸦**）是一款采用 **17 模块 Clean Architecture** 构建的原生 Android 多源媒体应用，将 **Dark Obsidian（纯黑曜石 OLED）× Apple HIG Precision（苹果级克制与精致）× Netflix Cinema（流媒体影院氛围）** 设计语言贯通至 19 个视频、漫画与写真站点，提供统一的沉浸浏览、离线下载、个人片库、全能播放器与四模式阅读器体验。

---

## 核心亮点

- **Dark Obsidian × Apple HIG × Netflix Cinema 设计系统**
  - 纯黑曜石画布 `#09090B` + 三级暗色表面阶梯（`#121216` / `#1B1B22` / `#24242D`）+ `0.5dp` Translucent Hairline + 唯一品牌点缀色 **Raven Crimson `#FF4D58`**。
  - **视频站点首页**：全出血 Hero Banner 轮播 + 顶部毛玻璃/暗色顶栏 + 继续观看（`2.5dp` 绯红进度条）+ Top 10 排行 + 横向片单货架。
  - **漫画与写真站点首页**：无边框原生宽高比瀑布流（`2–6` 列自适应）+ 顶部续读卡片 + 单行语言筛选栏。
  - **Apple HIG 分组内嵌列表 & 单屏表单**：设置、高级筛选、播放偏好与阅读覆盖全面采用圆角分组卡片与单屏 `ModalBottomSheet`，彻底杜绝递归弹窗。
  - **自适应导航壳**：手机 `< 600dp` 采用极简底部导航栏，平板 / 折叠屏 `>= 600dp` 自动切换左侧持久 `NavigationRail`。
- **3-Activity 无堆栈泄漏边界**
  - `MainActivity`（`singleTop`）：承载 25 路由 Type-Safe Compose `NavHost`、`ACTION_SEND` 分享链接识别与底部确认卡片、SAF 文档读写与硬件音量键翻页。
  - `PlayerActivity`（`singleTop` + API 26+ PiP）：独立画中画播放壳，相关视频与队列切换全程 **in-place** 切换，支持手势调光/音量/快进、倍速、A-B 循环、睡眠定时器、本地预览帧与清晰度自动降级。
  - `SiteAuthActivity`（`FLAG_SECURE`）：隔离式 WebView 官方登录状态机，禁用 JS 桥接与本地文件访问、强制拒绝无效 SSL 证书、退出彻底清空 WebView 缓存与 Cookie。
- **全链路安全、离线与数据迁移**
  - `SessionVault`（AndroidKeyStore AES-256-GCM）统一加密隔离全部登录态，凭据绝不发往媒体 CDN 或更新服务器。
  - 统一 `DownloadCoordinator` 支持视频（Media3 HLS/MP4）与图集（含 JMComic 条带还原 / 完整性审计修复）离线缓存，支持 `/storage/emulated/0/Raven` 公共目录迁移与重装自动接管。
  - 加密备份 `RVNBK01`（PBKDF2 600,000 次 + AES-256-GCM）完整兼容旧版 `R34VBK01` 无损导入，并在单次 Room `@Transaction` 内原子合并。

---

## 17 模块架构总览

| 层级 | 模块 | 核心职责 |
|---|---|---|
| **App Shell** | `:app` | `RavenApplication`（13 模块 Koin 根 + 单例 Coil 3）、`MainActivity`（25 路由 `NavHost`）、`PlayerActivity`（PiP）、自适应图标与网络安全配置 |
| **Feature UI** | `:feature:browse` | 影院/瀑布流首页、发现、单站/跨站搜索与高级筛选、站点切换抽屉、站点功能与目录 |
| | `:feature:library` | 我的（Profile）、片库总览、自定义/智能片单、收藏、历史、单图收藏、本地导入、下载中心、标签订阅与全局更新收件箱 |
| | `:feature:player` | Media3 视频详情与播放器界面、可视化手势 HUD、分组控制 Sheet、队列连播与 PiP 宿主 |
| | `:feature:reader` | 图集详情（含内嵌视频）与 4 模式沉浸阅读器（条漫 / 左到右 / 右到左 / 双页）、缩放裁边、缩略图总览、书签与 CBZ/图片导出 |
| | `:feature:settings` | Apple HIG 分组设置、多站点账号管理、`SiteAuthActivity`、屏蔽词、存储迁移、重复作品清理、加密备份恢复、GitHub Release 签名校验更新器 |
| **Site Plugins** | `:sites:video` | 8 个视频站点插件（独立多绑定 `SitePlugin`） |
| | `:sites:comic` | 6 个漫画站点插件（独立多绑定 `SitePlugin`） |
| | `:sites:gallery` | 5 个写真/图集站点插件（共享 `WordPressPhotoEngine`） |
| **Core Engine** | `:core:media` | Media3 播放控制器、4 级缓存、`RouPngDataSource`、图集页缓存与 JMComic 反混淆渲染、统一视频/图集下载引擎、存储迁移与去重扫描 |
| | `:core:designsystem` | Dark Obsidian × Apple HIG × Netflix Cinema 令牌、等宽数字排版、`RavenAdaptiveScaffold`、影院/图集卡片、分组列表与底部表单 |
| | `:core:database` | Room 18 表数据库（全响应式 `Flow` DAO）、Preferences DataStore（17 项设置键）与 11 个领域仓储 |
| | `:core:network` | 共享 OkHttp 连接池、IPv4 优先 DNS、系统代理监听、`HostPacer` 协程限速、302 媒体重定向缓存、加密 CookieJar、单实例分站 Coil 3 `ImageLoader` |
| | `:core:security` | `SessionVault`（AndroidKeyStore AES-256-GCM）、`BackupCodec`（`RVNBK01` + `R34VBK01`）、`BackupValidator` |
| **Pure JVM SPI** | `:core:site-spi` | 组合式 `SitePlugin` SPI、不可变 `SiteRegistry`、9 个站点协议工具（`Base62Packer`、`RouPngProtocol`、`CossoraAesProtocol`、`JmcomicStripMath`、`MirrorLeaseRouter` 等） |
| | `:core:common` | 协程调度器、自然排序、URL 净化、165 条中英标签词典、页码选择器与内容过滤器 |
| | `:core:model` | 全量 `@Serializable` 领域模型、25 路由 `RavenRoute` 契约与结构化 `SiteException` |

---

## 支持的 19 个在线站点（+ 本地库）

| 类别 | 数量 | 站点列表 |
|---|---|---|
| **视频（Video）** | 8 | `R34Video` · `Hanime1` · `Iwara`（双媒体：视频 + 图集）· `MissAV` · `Jable` · `Rou.video`（PNG-HLS 协议）· `KBJFree` · `Pornhub` |
| **漫画（Comic）** | 6 | `E-Hentai` · `ExHentai`（官方 WebView 登录态门禁）· `nhentai` · `Hitomi.la`（Nozomi + B-Tree）· `WNACG` · `JMComic`（镜像租约 + 条带还原） |
| **写真（Photo / Gallery）** | 5 | `4KHD` · `Everia` · `4KUP` · `GalleryEpic` · `CosplayTele`（双媒体：图集 + Cossora AES HLS） |
| **本地源（Local）** | 1 | 支持通过 SAF 导入本地 CBZ / ZIP 漫画包、图片目录与视频文件离线管理 |

---

## 构建与测试

环境要求：**JDK 17** · **Android SDK API 36**（`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`, `buildTools = 36.0.0`）· **Gradle Wrapper `9.1.0`**。

```powershell
# 运行全部 17 模块 JVM 单元测试
.\gradlew.bat testDebugUnitTest --no-daemon

# 构建 Debug APK（输出至 app/build/outputs/apk/debug/app-debug.apk）
.\gradlew.bat :app:assembleDebug --no-daemon

# 运行 Android Lint 静态检查
.\gradlew.bat :app:lintDebug --no-daemon

# 在已连接设备或模拟器上运行 Compose 冒烟测试
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

更详尽的架构设计、持久化表结构与站点协议不变量请参阅 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

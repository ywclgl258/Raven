# Raven · 渡鸦

> **多源原生媒体浏览、影院级播放与沉浸式阅读 Android 客户端**  
> Package: `com.raven.app`（Debug: `com.raven.app.debug`） · 当前版本见 [release.properties](release.properties) · Kotlin `2.3.21` · Jetpack Compose · Material 3 Adaptive · Media3 `1.11.1` · Room `2.8.5` · Koin `4.2.2`

Raven（**渡鸦**）是一款采用 **17 模块 Clean Architecture** 构建的原生 Android 多源媒体应用，将 **Dark Obsidian（纯黑曜石 OLED）× Apple HIG Precision（苹果级克制与精致）× Netflix Cinema（流媒体影院氛围）** 设计语言贯通至 24 个视频、漫画、连载漫画与写真站点，提供统一的沉浸浏览、离线下载、个人资料库、全能播放器与四模式阅读器体验。

---

## 开发与接手文档索引

| 文档 | 定位 | 适用场景 / 核心内容 |
|---|---|---|
| **[AGENTS.md](AGENTS.md)** | 仓库协作与发布红线（Agent / IDE / CI 必读） | 核心工程不变量、正式版统一打包与签名一致性红线、锁定 SHA-256 指纹、Debug/Release 边界、交付前 `apksigner` 强制验签清单 |
| **[docs/SIGNING.md](docs/SIGNING.md)** | 正式发布签名与覆盖安装操作指南 | 签名一致性原因、外部 `RAVEN_SIGNING_HOME/signing.properties` 占位模板、密钥备份清单、多机与 GitHub Actions CI 注入、`validateReleaseSigning` + `RavenUpdater` 双重校验与 `apksigner` 验签步骤 |
| **[docs/ONBOARDING.md](docs/ONBOARDING.md)** | 新开发者接手与日常开发指南 | 5 分钟环境配置、Windows `kotlin.incremental=false` / AGP 9 / API 37 红线、17 模块全景地图、常用命令速查、8 条代码红线（含 ICU `PatternNative` 正则与自动播放不自动全屏）、新增页面/Feature 7 步 SOP、Git 与 Release 签名规范 |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | 系统架构与工程蓝图 | 17 模块依赖 DAG、Koin 13 模块装配约定、设计令牌、27 路由契约、Room 20 表与 DataStore 37 个固定 Key + 2 类动态站点 Key、加密备份线协议与 14 条站点协议不变量 |
| **[docs/SITE_PLUGIN_GUIDE.md](docs/SITE_PLUGIN_GUIDE.md)** | 站点插件架构、24 站维护与新站接入 | 组合式 `SitePlugin` SPI 与 10 项可选能力、`SiteKit` 9 个协议工具、24 站维护总表与排障手册、从零接入新站的实战骨架与契约测试 |
| **[docs/DESIGN_AND_UI_GUIDE.md](docs/DESIGN_AND_UI_GUIDE.md)** | 设计系统与 UI 架构手册 | 品牌与自适应图标（`0.68` 安全区缩放）、色板/排版(`tnum`)/圆角/动效磨砂令牌、`:core:designsystem` 全量组件目录、3-Activity 拓扑与 27 路由、Feature Navigator 解耦、无障碍与 `testTag` 规范 |
| **[docs/DATA_MEDIA_SECURITY.md](docs/DATA_MEDIA_SECURITY.md)** | 数据、网络、媒体内核与安全备份手册 | Room 21 表与版本迁移、5 类 OkHttp 客户端与单例 Coil 3、Media3 播放与 4 级缓存、阅读器预读与反混淆、双引擎下载/双存储根迁移、`SessionVault` / `SiteAuthActivity` / `RVNBK01` 备份与签名更新器 |
| **[docs/audit-env.md](docs/audit-env.md)** | 历史审计：构建环境与工具链基线 | JDK 17、Android SDK API 36、Gradle 缓存、Windows 增量编译与 API 37 依赖断层实测依据 |
| **[docs/audit-app-ux.md](docs/audit-app-ux.md)** | 历史审计：应用架构与 UX 重构输入 | 旧版 R34V 功能盘点与 Raven 现代化重构对比基线 |
| **[docs/audit-sites.md](docs/audit-sites.md)** | 历史审计：19 站网络协议规范基准（新增站点未覆盖） | 19 个在线站点的抓包/协议细节与边界条件基线 |

---

## 核心亮点

- **Dark Obsidian × Apple HIG × Netflix Cinema 设计系统**
  - 纯黑曜石画布 `#09090B` + 三级暗色表面阶梯（`#121216` / `#1B1B22` / `#24242D`）+ `0.5dp` Translucent Hairline + 唯一品牌点缀色 **Raven Crimson `#FF4D58`**。
  - **视频站点首页**：全出血 Hero Banner 轮播 + 顶部毛玻璃/暗色顶栏 + 继续观看（`2.5dp` 绯红进度条）+ Top 10 排行 + 横向片单货架。
  - **漫画与写真站点首页**：无边框原生宽高比瀑布流（`2–6` 列自适应）+ 置顶续读卡 + 多本未读完“继续阅读”横排 + 单行语言筛选栏。
  - **Apple HIG 分组内嵌列表 & 单屏表单**：设置、高级筛选、播放偏好与阅读覆盖全面采用圆角分组卡片与单屏 `ModalBottomSheet`，彻底杜绝递归弹窗。
  - **自适应导航壳**：手机 `< 600dp` 采用极简底部导航栏，平板 `>= 600dp` 切换左侧持久 `NavigationRail`，大屏 `>= 1200dp` 展开 `NavigationDrawer`；支持无痕模式提示条、应用 PIN/生物识别锁（`RavenAppLockOverlay`）与隐私防截屏保护（`FLAG_SECURE`）。
- **3-Activity 无堆栈泄漏边界**
  - `MainActivity`（`singleTop`）：承载 27 路由 Type-Safe Compose `NavHost`、`ACTION_SEND` 分享链接识别与底部确认卡片、SAF 文档读写与硬件音量键翻页。
  - `PlayerActivity`（`singleTop` + API 26+ PiP）：独立画中画播放壳，相关视频与队列切换全程 **in-place** 切换，支持完整队列面板/任意跳转/移除、可取消的自动连播倒计时、手势调光/音量/快进、倍速、A-B 循环、睡眠定时器与清晰度自动降级。
  - `SiteAuthActivity`（`FLAG_SECURE`）：隔离式 WebView 官方登录状态机，禁用 JS 桥接与本地文件访问、强制拒绝无效 SSL 证书、退出彻底清空 WebView 缓存与 Cookie。
- **全链路安全、离线与数据迁移**
  - `SessionVault`（AndroidKeyStore AES-256-GCM）统一加密隔离全部登录态，凭据绝不发往媒体 CDN 或更新服务器。
  - 统一 `DownloadCoordinator` 支持视频（Media3 HLS/MP4）与图集（含 JMComic 条带还原 / 完整性审计修复）离线缓存，支持 `/storage/emulated/0/Raven` 公共目录迁移与重装自动接管。
  - 加密备份 `RVNBK01`（PBKDF2 600,000 次 + AES-256-GCM）完整兼容旧版 `R34VBK01` 无损导入，并在单次 Room `@Transaction` 内原子合并。

---

## 17 模块架构总览

| 层级 | 模块 | 核心职责 |
|---|---|---|
| **App Shell** | `:app` | `RavenApplication`（13 模块 Koin 根 + 单例 Coil 3 + `RavenBackgroundMaintenanceWorker`）、`MainActivity`（27 路由 `NavHost` + `RavenAppLockOverlay`）、`PlayerActivity`（PiP）、自适应图标与网络安全配置 |
| **Feature UI** | `:feature:browse` | 影院/瀑布流首页、多本继续阅读、发现高级与日期筛选、全站最近搜索、单站/跨站搜索与来源分页、站点切换搜索、站点功能与目录 |
| | `:feature:library` | 我的（内容/订阅/本地/账号分组入口）、资料库总览与统计、订阅设置与订阅更新（单/多/全站点标签组、瀑布流/列表切换）、下载与收藏（统一瀑布流/列表切换）、自定义/智能片单、历史、单图收藏、本地导入与关联清理、标签订阅与全局更新收件箱 |
| | `:feature:player` | Media3 视频详情与播放器界面、完整播放队列与任意切项、自动连播倒计时、可视化手势 HUD、分组控制 Sheet、音轨字幕切换、局域网投屏（`LanCastManager`）与 PiP 宿主 |
| | `:feature:reader` | 图集详情（内嵌视频、片单、底部相似推荐、标签/作者长按菜单）与 4 模式沉浸阅读器（条漫 / 左到右 / 右到左 / 双页）、连载选话/已读标记/下一未读、三区翻页、宽图拆分、缩放裁边、缩略图总览、书签与 CBZ/图片导出 |
| | `:feature:settings` | Apple HIG 分组设置、多站点账号管理、`SiteAuthActivity`、屏蔽词、存储迁移、重复作品清理、加密备份恢复、WebDAV 云同步、隐私锁与 GitHub Release 签名校验更新器 |
| **Site Plugins** | `:sites:video` | 7 个视频站点插件（独立多绑定 `SitePlugin`） |
| | `:sites:comic` | 12 个漫画站点插件（普通漫画 7 + 连载漫画 5，独立多绑定 `SitePlugin`） |
| | `:sites:gallery` | 5 个写真/图集站点插件（共享 `WordPressPhotoEngine`） |
| **Core Engine** | `:core:media` | Media3 播放控制器、4 级缓存、`RouPngDataSource`、局域网投屏、图集页缓存与 JMComic 反混淆渲染、统一视频/图集下载引擎、存储迁移与去重扫描 |
| | `:core:designsystem` | Dark Obsidian × Apple HIG × Netflix Cinema 令牌、等宽数字排版、`RavenAdaptiveScaffold`（支持 `>= 1200dp` Drawer）、影院/图集卡片、分组列表与底部表单 |
| | `:core:database` | Room 21 表数据库（Schema v5，全响应式 `Flow` DAO）、Preferences DataStore（37 个固定 Key + 2 类动态站点 Key）与 13 个领域仓储 |
| | `:core:network` | 共享 OkHttp 连接池、IPv4 优先 DNS、系统代理监听、`HostPacer` 协程限速、302 媒体重定向缓存、加密 CookieJar、单实例分站 Coil 3 `ImageLoader` |
| | `:core:security` | `SessionVault`、`PrivacyLockStore`、`WebDavCredentialStore`（AndroidKeyStore AES-256-GCM）、`BackupCodec`（`RVNBK01` + `R34VBK01` import）、`BackupValidator` |
| **Pure JVM SPI** | `:core:site-spi` | 组合式 `SitePlugin` SPI、不可变 `SiteRegistry`、9 个站点协议工具（`Base62Packer`、`RouPngProtocol`、`CossoraAesProtocol`、`JmcomicStripMath`、`MirrorLeaseRouter` 等） |
| | `:core:common` | 协程调度器、自然排序、URL 净化、165+ 条分领域中英标签词典、页码选择器与内容过滤器 |
| | `:core:model` | 全量 `@Serializable` 领域模型、27 路由 `RavenRoute` 契约与结构化 `SiteException` |

---

## 支持的 24 个在线站点（+ 本地库）

| 类别 | 数量 | 站点列表 |
|---|---|---|
| **视频（Video）** | 7 | `R34Video` · `Hanime1` · `Iwara`（双媒体：视频 + 图集）· `MissAV` · `Jable` · `Rou.video`（PNG-HLS 协议）· `Pornhub` |
| **漫画（Comic）** | 7 | `E-Hentai` · `ExHentai`（官方 WebView 登录态门禁）· `nhentai` · `Hitomi.la`（Nozomi + B-Tree）· `WNACG` · `JMComic`（镜像租约 + 条带还原）· `HDoujin`（API 聚合流） |
| **连载漫画（Serial Comic）** | 5 | `拷贝漫画`（镜像 + API 列表）· `漫画160` · `瓜子漫画`（章节图页直读）· `MyComic` · `嬉皮漫畫` |
| **写真（Photo / Gallery）** | 5 | `4KHD` · `Everia` · `4KUP` · `GalleryEpic` · `CosplayTele`（双媒体：图集 + Cossora AES HLS） |
| **本地源（Local）** | 1 | 支持通过 SAF 导入本地 CBZ / ZIP 漫画包、图片目录与视频文件离线管理 |

---

## 构建与测试

环境要求：**JDK 17** · **Android SDK API 36**（`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`, `buildTools = 36.0.0`）· **Gradle Wrapper `9.1.0`** · **AGP `9.0.1`**（内置 Kotlin，覆盖至 `2.3.21`）。首次构建需在根目录创建 `local.properties` 指定 `sdk.dir`（已纳入 `.gitignore`）。

`build-logic/` 是独立 included build，提供 `raven.android.library` 与 `raven.compose.library` 两个约定插件，集中维护 SDK、JDK 17、单测和 Compose stability 默认配置；它不计入 17 个业务模块。模块脚本仍自行声明 namespace、项目依赖和特殊配置。

```powershell
# 运行全部 17 模块 JVM 单元测试
# 截至当前源码静态计数 384 处 @Test；静态计数不代表测试已执行或通过
.\gradlew.bat :core:model:test :core:common:test :core:site-spi:test :sites:video:test :sites:comic:test :sites:gallery:test testDebugUnitTest --no-daemon

# 仅运行单模块单测（推荐日常开发，适配 Windows kotlin.incremental=false 环境）
.\gradlew.bat :feature:player:testDebugUnitTest --no-daemon

# 构建 Debug APK（输出至 app/build/outputs/apk/debug/app-debug.apk）
.\gradlew.bat :app:assembleDebug --no-daemon

# 运行全部 Android 模块 Lint 静态检查
.\gradlew.bat lintDebug --no-daemon

# 在已连接设备或模拟器上运行 androidTest（截至当前源码静态计数 6 处 @Test）
.\gradlew.bat :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest --no-daemon

# 需要更新上述数量时，重新执行静态计数（不运行测试）
(rg -o --no-filename '@Test\b' -g '**/src/test/**/*.kt' . | Measure-Object).Count
(rg -o --no-filename '@Test\b' -g '**/src/androidTest/**/*.kt' . | Measure-Object).Count
```

> **代码基线说明**：本文按当前源码静态核对；测试数量仅描述 `@Test` 注解计数，执行结果以本地或 CI 测试报告为准。构建与发布均以 [release.properties](release.properties) 为唯一版本元数据来源。初次上手请从 [docs/ONBOARDING.md](docs/ONBOARDING.md) 开始，按需查阅上方**开发与接手文档索引**中的专题手册。

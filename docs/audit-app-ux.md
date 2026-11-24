# R34V 应用架构、功能全量与 UX 审计报告（Raven 现代化重构输入）

> **审计基准**：`D:\rule34`（commit `a7fdc5f`，`release.properties` 版本 `2.12.1` / `versionCode=57`，`applicationId=com.r34v.r34v`）  
> **审计范围**：全量 `app/src/main`（107 个 `.kt` 文件 / 21,038 LOC）、`app/src/test`（43 文件 / 4,162 LOC）、`app/src/androidTest`（75 文件 / 11,798 LOC）、`app/src/debug`（2 文件 / 609 LOC）、`docs/`、`design/raven/` 及 `AndroidManifest.xml` / `app/build.gradle.kts`。  
> **核心结论先行**：
> 1. **功能完整度极高，协议与离线容错打磨扎实**：R34V 已沉淀 19 个在线站点 + 1 个本地导入源（视频 8 / 漫画 6 / 写真 5 + Local）的完整媒体能力，视频播放器（Media3 + 带宽测速选档/自动降档/HLS&MP4 会话 Seek 缓存/PiP/A-B 循环/时间书签）、漫画写真阅读器（条漫/分页/横屏双页/3 页预读/24MiB 预算/防误触裁边/CBZ 导出/失效页自愈）、统一下载中心（双引擎 + 公共目录重装继承 + 页级完整性审计修复）与加密备份（`R34VBK01` AES-256-GCM）均达到生产级可用度。
> 2. **但架构负债与视觉债务已接近演进拐点**：
>    - **单模块单体 + 上帝类严重**：仅 `:app` 单模块；`MainActivity.kt`（1,178 行）、`PlayerActivity.kt`（1,095 行）、`GalleryActivity.kt`（923 行）、`GalleryRepository.kt`（892 行）、`ComposeDownloads.kt`（786 行）承载了绝大部分路由、状态与业务逻辑。
>    - **无 DI / 无 Navigation / 无数据库**：全靠 `R34Application` 手动 Service Locator + 全局可变单例 `SiteRegistry.current`；路由靠手写 `screen: String` + `libraryTab: String` + 4 个并行 Bundle 数组；持久化无 Room/SQLite，所有个人库 13 类数据塞进**单个 SharedPreferences 字符串键** `personal_library_v1` 并用 Gson 整块序列化，靠 47 处 `revision++` 强刷 Compose；13 处主线程/同步 `.commit()`。
>    - **五代视觉设计沉积，文档与线上代码严重脱节**：代码内同时残留 Netflix 暗黑、LiquidGlass（`io.github.kyant0:backdrop:1.0.0` 生产零调用）、2.6/2.10 纯黑、录像带租赁店（`RentalStyle.kt`）与最新动态封面粉红主题（`ArtworkStyle.kt`，`#FF8DB4` + `ArtworkPill`）五层样式与双分支死代码；同时大量复杂表单/设置/多步操作被塞进递归套娃的 `AlertDialog`/`ModalBottomSheet`。
> 3. **Raven 重写建议**：保留 R34V 经实测验证的媒体内核/离线恢复/安全隔离/备份协议不变量，以 **多模块 Clean Architecture + Hilt/Koin DI + Room/DataStore/`kotlinx.serialization` + Typed Navigation Compose + Material 3 Adaptive (`NavigationSuiteScaffold`) + 统一 Raven Obsidian 视觉系统**（对齐 `design/raven/raven-icon.svg` 的 `#09090B` / `#F5F5F7` / `#FF4D58`）彻底重建。

---

## 1. 代码库结构与体量总览

### 1.1 工程与源码分布

`D:\rule34\settings.gradle.kts` 仅包含单一模块 `include(":app")`，`app/build.gradle.kts` 使用 AGP `9.0.1` + Kotlin `2.2.10` + Compose BOM `2025.08.01`，`minSdk=24`、`compileSdk=36`、`targetSdk=36`。

| Source Set / Package | 文件数 | 代码行数（LOC） | 核心职责 |
| --- | ---: | ---: | --- |
| `app/src/main`（总计） | **107** | **21,038** | 生产代码 |
| ├─ `ui/` | 34 | 5,735 | Compose 页面、组件、四套主题文件、`FeedStates`、`CrossSearchState` |
| ├─ `data/` | 33 | 5,367 | 模型、SharedPreferences 存储、个人库、本地导入、画廊缓存/导出、备份、网络 |
| ├─ `data/adapter/` | 19 | 4,189 | 19 站解析适配器与 `SiteAdapter` 接口（详见 `audit-sites.md`） |
| ├─ `(root)` | 6 | 3,786 | `R34Application`、`MainActivity`、`PlayerActivity`、`GalleryActivity`、`SiteAuthActivity`、`UpdateController` |
| ├─ `playback/` | 10 | 1,307 | Media3 下载/缓存/HLS Seek 会话/链路测速/起播选档/降档/本地帧预览/Rou PNG 解包 |
| └─ `downloads/` | 5 | 654 | 图集下载队列、前台服务、网络策略、批量下载、错误恢复分类 |
| `app/src/test` | **43** | **4,162** | 374 个 JVM `@Test`（解析契约、协议、备份编解码、状态算法） |
| `app/src/androidTest` | **75** | **11,798** | 318 个 Instrumented `@Test`（UI 回归、Media3/离线验证、性能探针、实站探针） |
| `app/src/debug` | **2** | **609** | `GlassPreviewActivity.kt`（362 行）+ `GlassPreviewComponents.kt`（247 行） |

### 1.2 Top 12 最大生产文件（上帝类分布）

| 排名 | 文件路径 | 行数 | 过度承担的职责 |
| ---: | --- | ---: | --- |
| 1 | `app/src/main/kotlin/com/r34v/r34v/MainActivity.kt` | 1,178 | 18 路由字符串状态机 + 返回栈序列化 + 搜索页 + 设置页 + 账号控件 + 重复文件扫描弹窗 + 存储迁移弹窗 + 分享/导入分发 |
| 2 | `app/src/main/kotlin/com/r34v/r34v/PlayerActivity.kt` | 1,095 | ExoPlayer 生命周期 + 动态缓冲/重建 + 自动降档 + PiP + 定时暂停 + A-B 循环 + View 手势 + 封面/播放/详情/标签/评论/推荐 UI + 6 个弹窗 |
| 3 | `app/src/main/kotlin/com/r34v/r34v/GalleryActivity.kt` | 923 | 图集加载 + 离线/稀疏页判定 + 阅读器外壳 + 自动翻页/幻灯片 + 音量键拦截 + 详情页 + 导出宿主 + 10+ 工具菜单/弹窗 |
| 4 | `app/src/main/kotlin/com/r34v/r34v/data/GalleryRepository.kt` | 892 | 图集详情/图页网络拉取 + 256MiB LRU 阅读缓存 + 永久下载读写 + 原子落盘 + 位图解码/缩略图 + 完整性审计 + 重复扫描 + EH `nl` 刷新 |
| 5 | `app/src/main/kotlin/com/r34v/r34v/ui/ComposeDownloads.kt` | 786 | IO 快照扫描 + 750ms 轮询 + 5 类异构下载实体（视频任务/漫画任务/旧离线/磁盘恢复视频/磁盘恢复漫画）的统一筛选、排序、批量管理与卡片渲染 |
| 6 | `app/src/main/kotlin/com/r34v/r34v/data/Network.kt` | 617 | OkHttp 三客户端、分站 Cookie/JWT、429 退避、镜像切换、系统代理、网络诊断 |
| 7 | `app/src/main/kotlin/com/r34v/r34v/data/adapter/PhotoArchiveAdapter.kt` | 503 | 5 个写真站共用引擎 + 混合内嵌视频提取 |
| 8 | `app/src/main/kotlin/com/r34v/r34v/ui/ComposeBrowse.kt` | 487 | `FeedScreen` 分页/过滤/位置恢复 + 发现页 `ExploreScreen` + 目录 `IndexScreen` + `MediaLibrary` |
| 9 | `app/src/main/kotlin/com/r34v/r34v/data/StorageRoot.kt` | 483 | 私有/公共存储双根布局 + 权限检测 + 重装自动收编 + 校验迁移 + 回滚 |
| 10 | `app/src/main/kotlin/com/r34v/r34v/ui/ComposeCinema.kt` | 462 | 浮囊底栏 `CinemaNavigation` + 影院首页 `CinemaHome` + Hero 轮播 `CinemaHero` + 榜单/横排片架 |
| 11 | `app/src/main/kotlin/com/r34v/r34v/playback/PlaybackStore.kt` | 458 | Media3 `DownloadManager` + 4 个 `SimpleCache`（下载/512MB流/64MB MP4 Seek/32MB HLS Seek）+ 删除代次防护 + `VideoDownloadService` |
| 12 | `app/src/main/kotlin/com/r34v/r34v/ui/ComposeSubscriptions.kt` | 426 | 标签管理 + 订阅聚合流 + 订阅更新收件箱 + 单标签检查状态机 |

---

## 2. 完整功能盘点与用户流（源码实证）

### 2.1 导航结构、站点切换与大屏自适应

* **四大 Activity 壳**（`AndroidManifest.xml`）：
  * `MainActivity`（launcher + `ACTION_SEND` `text/plain` 分享接收）
  * `PlayerActivity`（`supportsPictureInPicture="true"`，处理全部视频详情与播放）
  * `GalleryActivity`（处理漫画/写真/Iwara 图库详情与阅读）
  * `SiteAuthActivity`（`FLAG_SECURE`，仅用于 E-Hentai / ExHentai 官方论坛 WebView 认证）
* **主壳五项底栏**（`ComposeCinema.kt:77-78`）：
  1. **首页 (`siteHome`)**：当前站点的原生首页。视频站走 `CinemaHome`（精选轮播 `CinemaHero` + 快捷过滤芯片 + 人气榜 Top 10 + 最新更新 8 部/行分片 + 高分佳作 + 继续观看）；漫画/写真站走 `LazyVerticalStaggeredGrid` 瀑布流 + `GalleryResumeCard` 续读卡 +（漫画站）顶栏下方随下滑 18dp 收起的单排语言条（`全部 / 中文 / 日文 / 中＋日`）。
  2. **发现 (`explore`)**：`ExploreScreen`，顶部单行工具栏（分类下拉 + 排序下拉 + 语言下拉 + 分类目录入口 + 刷新）与生效筛选摘要/一键清除；按站点持久化上次筛选条件（`${site.id}:explore_filter_v1`）。
  3. **搜索 (`search` / `multiSearch`)**：支持单站搜索（视频/漫画/写真、标签、作者范围切换）与跨站多源搜索（默认全选支持搜索的站点，可勾选子集并持久化 `cross_search_sites`）；主搜索框与高级搜索弹窗共用 165 词条中文词典候选（`TagNames.suggestions`）；集成高级筛选弹窗（包含/排除标签、年份/月份、时长、品牌、分类、页数、星级评分）、常用搜索方案保存/管理与最近 30 条搜索历史。
  4. **片库 (`library`)**：跨来源统一中心 `LibraryHubScreen`，包含 4 个轻量快捷入口（`记录 / 收藏 / 下载 / 更新`）+ 3 个切换分区（`我的片单 / 智能片单 / 本地内容`）+ 底部工具列表（`单图收藏 / 标签订阅 / 导入 CBZ·ZIP·图片目录·视频 / 从链接打开作品`）。
  5. **我的 (`home`)**：`PersonalHomeScreen`，顶部 `全部 / 视频 / 漫画 / 写真` 类型筛选 + `PersonalFeature` 主续看/续读封套卡（自动跳过已完成作品，无进行中记录则回落到稍后看）+ `继续观看与阅读` 横排 + `我的稍后看` 横排 + 快捷统计入口 + 常用搜索芯片 + 固定发现频道（`我的频道` 及管理弹窗）。
* **切站抽屉与站点功能页**：
  * `SiteSwitcher`（`ModalNavigationDrawer`）：19 个在线站点按 `VIDEO(8) / COMIC(6) / PHOTO(5)` 分组，自适应网格（单元格基准 `98dp * fontScale`），手机竖屏可一屏展示全部站点；支持折叠式「常用站点」置顶与管理模式（`pinned_site_ids_v1`）；各站点标注 `accessNote`（如 ExHentai「需登录及账号权限」、MissAV「受限时切换镜像」、JMComic「自动选择镜像」）。
  * **站点功能页 (`siteFunctions`)**：从首页/发现页进入，集中展示当前站点的推荐入口、站内搜索、站点提示、漫画语言/阅读方式/原图优先、以及锁定当前站点的「本站收藏 / 本站下载 / 本站标签订阅 / 站点账户」和全部站内栏目。
* **首次切站教程**：`LibraryStore.SITE_SWITCH_TUTORIAL_SEEN`，仅全新安装（原生库与旧 Flutter 库均为空）首次进入 `siteHome` 时，在顶栏 `RentalSiteSwitch` 下弹出 `DropdownMenu` 提示卡，显示前即同步落盘消费，升级/迁移用户不触发。
* **浏览位置跨进程恢复**（`FeedState.kt`）：`FeedStates` 在内存 LRU 缓存最多 8 份列表（5 分钟过期），并对 `siteHome` 和 `explore` 持久化最近 32 条 `FeedPosition(work, throughPage, shelf)` 至 `browse_positions_v1`；进程重建后自动翻页至最多 `throughPage + 1` 并对齐到原作品（含视频首页片架级定位）。
* **平板与大屏自适应**：
  * `LocalUiDensity`（`ComposeUi.kt:70-72`）：当 `windowSizeDp().width >= 840` 且 `fontScale <= 1.3f` 时切入紧凑密度（控件 38dp、字号整体下调一档、外层仍保留 `minimumInteractiveComponentSize` 48dp 触控区）。
  * 瀑布流列数（`ComposeBrowse.kt:203-210`）：1 列（`fontScale > 1.5 && width < 400`）/ 2 列（默认手机）/ 3 列（`>=600dp` 或 `>=360dp` 漫画首页）/ 4 列（`>=840dp`）/ 5 列（`>=1024dp`）/ 6 列（`>=1280dp`）。
  * 列表-详情分栏（`AdaptiveCatalogList`，`ComposeLibraryHub.kt:29-81`）：`width >= 840dp` 时左列表（weight 1）右详情（weight 1.2）并列；片单封面在宽屏下双列；短窗口（`height < 500dp`）下 `ShelfScreen` 切换为整页可滚动避免挤压。
  * **缺失项**：大屏下底栏 `CinemaNavigation` 仍固定在底部（仅限宽 `444dp`），未使用 Material 3 `NavigationRail` 或 `NavigationDrawer` 常驻侧栏。

### 2.2 Media3 视频播放器（`PlayerActivity.kt` + `playback/`）

* **起播选档与弱网自适应**：
  * `StreamSelection` + `LinkSpeed` + `MediaProbe`：多档位视频起播先查进程内链路带宽估计（实测优先于探测，不落盘，网络路由 `routeEpoch` 变化即失效）；未知带宽且非 HLS 时先用 `MediaProbe.measureStartup` 探测 1MB 载荷，再按 `HEADROOM = 0.7` 预算选档（不会覆写用户默认清晰度设置）。
  * 动态 `BufferPlan`：按档位码率与链路压力选 `DefaultLoadControl` 窗口（高码率 `8000/40000/500/800ms`、720p `4000/15000/500/1000ms`、弱网/低码 `2500/12000/500/1000ms`），仅窗口签名变化时无缝重建引擎并解绑/重绑 `PlayerView`。
  * 自动降档 `StallPolicy`：起播卡顿 ≥6s、播放中单次卡顿 ≥8s 或 30s 窗口内 ≥3 次卡顿自动降一档（每部片最多降 2 次、冷却 20s），并提示用户；播放出错支持 1 次自动刷新详情与过期播放地址（`refreshed` 标记，保留原位置与清晰度）。
* **缓存体系（四重隔离）**：
  * 完整离线下载缓存：`DownloadCaches`（支持 private/shared 双根，离线播放 `upstream = null` 严禁回源）。
  * 可选流式播放缓存：`streamCache`（512 MiB LRU，默认关闭，设置项 `stream_cache`）。
  * 在线 MP4 会话回拖缓存：`seekCache`（64 MiB LRU，仅保留当前视频，换片即清，默认开启 `seek_cache`）。
  * 在线 HLS 会话回拖缓存：`hlsSeekCache`（32 MiB LRU，`HlsSeekSession` 仅缓存非直播点播分片，离开播放清理）。
  * 特殊协议透明解包：`RouPng.UnwrappingDataSource` 对 `rou.video` 的 PNG 伪装 HLS 清单与 TS 分片透明解包。
* **播放控制与手势**（`PlayerActivity.kt:1064-1085`）：
  * 单击显隐控制层（播放中 4.5s 自动隐藏）；双击左 40% 后退 / 右 40% 前进（步长按站点可配 5–60s）/ 中间 20% 切全屏；长按临时倍速（按站点可配 1.5–4.0×，松手恢复）；水平滑动快进/快退（灵敏度按站点可配整屏 30–600s）；左侧上下滑调窗口亮度、右侧上下滑调媒体音量。
  * **锁屏防误触**（`locked`）：隐藏所有控件并拦截手势，仅保留左侧「解锁画面」按钮与返回键解锁。
  * **A-B 区间循环**（`VideoLoopRange`）：支持设 A 点、设 B 点（至少间隔 1s）自动循环、清除；100ms 轮询检出到点回跳，手动跳出区间或时长变化自动清除。
  * **倍速体系**：全局默认倍速（`0.25–3.0×`）+ 按站点覆盖的 `PlaybackPreferences`（默认倍速、长按倍速、快进步长、手势灵敏度）+ 本次会话 `userRate`（随 Bundle 保存且优先于站点默认）。
  * **画中画（PiP）**：API 26+ 显式触发，按视频真实宽高比设置 `Rational` 与 `sourceRectHint`，`autoEnterEnabled=false`；PiP 期间 `onPause` 不暂停引擎，退出恢复原全屏与控制态。
  * **定时暂停**：预设 `5 / 15 / 30 / 60 分钟` 与自定义 `1–180 分钟`，基于 `elapsedRealtime()` 倒计时，到点暂停并保存进度。
  * **进度恢复与封面详情模式**：支持从首页轮播直播（`autoplay=true`）或从列表进海报详情页（`autoplay=false`，`PosterDetails` 预览封面再点播放）；自动全屏（按视频宽高比判断横/竖屏方向，不强转竖屏视频）；每 5 秒与生命周期切出保存 `Watch`（短片 ≤40s 与长片区分完播阈值）。
  * **本地视频拖动帧预览**（`LocalSeekPreview`）：仅对本地导入视频生效，拖动进度条时并发 conflate 解码 160×90 缩略帧（12 帧 LRU）。
  * **富详情交互**：简介与评论中的时间戳（`HH:MM:SS` / `MM:SS`）可点击直接跳转（`TimestampText`）；时间书签与片段支持备注和分组并随备份导出；标签支持中文词典翻译、点击跳转对应站内流、长按收藏/屏蔽；支持站外播放器链接（`externalPlayers`）；支持片单顺序续播（`QueueNextAction`）。

### 2.3 漫画与写真阅读器（`GalleryActivity.kt` + `ComposeReader*`）

* **四种阅读模式**（`ReaderMode`）：
  * `STRIP`（上下连续条漫，当前漫画与写真**默认**模式）
  * `LEFT_TO_RIGHT`（横向左到右翻页）
  * `RIGHT_TO_LEFT`（横向右到左翻页，通过 `HorizontalPager(reverseLayout = true)`）
  * `SPREAD`（横屏双页，`width > height` 时双页并排，`combineCover` 控制封面单独成页或与第 2 页合并）
* **缩放、裁边与视口几何**（`ComposeReader.kt:200-274`）：
  * 双击 1×/2× 切换、双指缩放 `1f..5f`，滚离当前页自动复位缩放。
  * `readerPanLimit` 按实际位图尺寸、容器尺寸、`ReaderFit`（`WIDTH / HEIGHT / ORIGINAL`）、手动裁边比例（`0%–20%`）和缩放倍率严格约束拖动位移，容器尺寸变化时自动重新钳制。
  * `ReaderViewportModel`（ViewModel）在条漫/分页模式切换与配置重建间共享每页宽高比 `ratios`，不持有 `Bitmap` 也不写库，有效防止跳位。
* **预读、解码与内存治理**（`ReaderBuffer`，`ComposeReader.kt:36-82`）：
  * 独立于 LazyItem 生命周期管理加载请求：保留 `[current-1 .. current+3]` 窗口（当前页 + 后续 3 页预读），`Semaphore(2)` 限制并发，屏外位图总字节预算上限 **24 MiB**，超限按距当前页最远逐页移除引用（不调用 `Bitmap.recycle()`，避免 RenderThread 崩溃）。
  * 像素上限 400 万像素（`inSampleSize` 2 的幂降采样，见 `LocalLibrary.kt:138` / `GalleryRepository.kt`），单页失败支持原位「重试此页」；非离线场景下缓存/历史页地址解析失败自动清理映射并重解析一次自愈。
* **辅助阅读与工具**：
  * 顶部与底部工具栏单击画布显隐，隐藏时同步隐藏系统栏（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`）并展示可定制角标状态栏 `ReaderStatusBar`（时间、电量、页码、百分比）。
  * 音量键翻页（支持正/反向、长按跳转首/末页）、自动翻页（`3/5/10/20/30s`，严格等当前页渲染就绪后计时）、图集幻灯片（顺序/随机间隔播放）。
  * 屏幕控制：独立阅读亮度滑块（`-1` 跟随系统，否则 `0–100%`）与 `FLAG_KEEP_SCREEN_ON` 保持亮屏；EH/EX 支持能力门控的「原图优先」开关（`Capability.ORIGINAL_IMAGES`）。
  * 单本覆写 vs 分站默认：单本 `ReaderPreferences`（模式/适配/起始位置 FIRST·LAST·RESUME/裁边/双页封面）与分站 `ReadingBehavior` 分离，支持「恢复来源默认」。
  * 缩略图总览（`ReaderThumbnails` 自适应网格 + 写真详情页 `PhotoPageShortcuts` 3 缩略图直达）、页码书签（`PageBookmark`，含条漫页内像素偏移）、单图收藏（`ImageFavorite`，上限 20,000 张）、跳页弹窗、阅读进度 750ms 防抖合并写入 + 退出同步刷盘（`ReadingProgressSaver`）。
  * **保存、分享与 CBZ 导出**（`GalleryExport.kt`）：支持当前页或自选页码（如 `1,3,5-8`）保存原图（单张 `ACTION_CREATE_DOCUMENT` / 多张 `OpenDocumentTree`）、系统分享（`FileProvider`，单次上限 100 张）、导出完整或选页 `.cbz`（上限 2,000 页 / 1 GiB，失败自动清理新创建文档但不删已有用户文件）。
  * **混合内容支持**：Cosplaytele 图集详情中若含 `embeddedVideos`（`CossoraProtocol` AES-CBC 解码 HLS），直接提供跳转 `PlayerActivity` 的入口，身份与图页隔离。

### 2.4 统一片库、发现、订阅与本地导入

* **片单体系**（`PersonalLibrary.kt` + `ShelfOrganization.kt` + `ComposeCollections.kt`）：
  * 内置「稍后看」（`watch-later`）+ 无限自定义合集（名称 1–80 字、备注 ≤1000 字、置顶、自定义封面作品或前 3 张拼接、手动上移/下移排序、完成状态标记）。
  * 片单内检索（标题/来源）+ 多维筛选（完成状态、媒体类型、来源、最近 7/30/90 天加入）+ 视图排序（手动/最近加入/最早加入/标题 A-Z，不破坏手动顺序）。
  * **顺序观看/阅读会话**（`PlaybackQueueStore` + `MediaRouter`）：仅传小体积 UUID token（避免 Binder 超限），支持在线顺序与「仅离线顺序打开」（启动前校验完整离线并跳过未离线项，打开下一项前二次确认并标记当前项完成，离线文件缺失时中止队列）。
  * **离线准备**（`OfflinePlanDialog`）：统计片单中已就绪/待下载/已知剩余字节/未知项与可用存储空间，一键勾选送入批量下载。
* **智能片单**（`SmartShelf`）：按 `UNFINISHED`（未看完）、`OFFLINE_UNSEEN`（已离线未看）、`RECENT`（最近 30 天加入）+ 可选媒体类型/站点动态过滤跨来源目录快照 `personalCatalog()`。
* **收藏与历史**：分站存储（视频/图集/标签/作者/角色收藏，观看/阅读历史各 500 条），片库「收藏」页支持跨站汇总、关键词检索、类型/来源/排序筛选，以及单站锁定时多选批量加入片单。
* **跨站搜索、保存搜索与更新检测**：
  * `CrossSearchState`（ViewModel）：`Semaphore(3)` 并发控制，混合瀑布流展示，支持来源筛选与单站刷新/下一页，目录型站点（如 GalleryEpic）转为目录跳转卡片。
  * `SavedSearchUpdateModel`（ViewModel）：每批 3 并发（单源 45s 超时），拉取各保存搜索按最新排序的第 1 页，首次建立基线，后续新结果入未读收件箱；支持按方案/未读过滤、查看各源检查详情与全部已读。
* **标签订阅与全局更新收件箱**（`ComposeSubscriptions.kt` + `ComposeGlobalInbox.kt`）：
  * 分站存储订阅标签（支持手动重排、单标签检查更新、取消订阅）；三视图切换（管理标签 / 订阅内容聚合 / 更新收件箱）；聚合排序支持按订阅顺序分组或合并排序；全局收件箱汇总「标签更新」与「保存搜索」未读。
* **本地内容导入与重复检查**：
  * `LocalLibrary`：支持 SAF 导入 CBZ/ZIP 压缩包（防 Zip Bomb，上限 10,000 条目 / 2,000 页 / 单页 50 MiB / 整本 1 GiB，忽略 `__MACOSX` 和隐藏文件）、图片目录（深度 ≤8）、以及本地视频（mp4/webm/mkv/m4v/mov/3gp/ts，上限 8 GiB，实时检查剩余空间 >1 GiB，生成首帧 `thumb.png`）；按自然序重命名、整本/整文件 SHA-256 摘要去重；逻辑身份 `local` 不混入网络切站列表。
  * `DuplicateScan`（F41）：扫描所有下载图集与本地漫画，先按 `(pages, bytes)` 预筛，再按自然页序逐页 SHA-256 串联比对整本指纹，只报告浪费空间并交由用户逐本确认走安全删除路径。
* **分享链接接管**（`IncomingLink.kt`）：支持系统分享（`ACTION_SEND`）与片库粘贴链接，自动提取首个受支持站点的 HTTPS URL（含 Iwara API 链接、JMComic `#chapters`），二次确认后自动识别进入列表/目录、视频播放器或图集阅读器。

### 2.5 统一下载中心与双存储根

* **双引擎统一呈现**（`ComposeDownloads.kt`）：
  * **视频下载**（`PlaybackStore` + `VideoDownloadService`）：Media3 `DownloadManager`（并发 2、重试 3、支持 MP4 Range 与 HLS 分片下载、`PlatformScheduler` 网络调度、Foreground Service `dataSync` + Android 15 `onTimeout` 安全暂停）；失败自动尝试刷新 1 次源地址；采用 `generations` 与 `removals` 防止删除期间过期刷新任务复活已删记录。
  * **图集下载**（`GalleryDownloadStore` + `GalleryDownloadService`）：单本串行队列（`PartialWakeLock` 6 小时上限 + `dataSync` 前台通知 + `onTimeout` 暂停），按页断点续传，支持**选择页码部分下载**、优先级上移/下移/置顶、自定义分组、按页原子写入（`.part` 临时文件重命名）与整本 `complete` 标记提交；内置 `DownloadRecovery` 细粒度错误分类（NETWORK/RATE_LIMIT/AUTH/NOT_FOUND/QUOTA/STORAGE/TLS/SOURCE）与 `Retry-After` 限流等待（`RETRY_WAIT`）；提供任务详情内「检查完整性」与「仅修复缺失/损坏页」。
  * **共同策略与批处理**：`DownloadPolicy` 统一监听网络能力并将「仅非计费网络」同步给双引擎（不影响手动暂停态）；`BatchDownloads` 支持多作品逐项解析入队与中途取消；下载中心支持按状态标签（全部/进行中/已完成/需处理）、类型、站点、分组、关键词、排序筛选，以及批量暂停/继续与「一键重试当前列表失败项」。
* **双存储根与重装恢复**（`StorageRoot.kt` + `LibraryIndex.kt`）：
  * 支持 `PRIVATE`（`files/`）与 `SHARED`（`/storage/emulated/0/R34V`，需 API 30+ `MANAGE_EXTERNAL_STORAGE` 或旧版写权限，自动写入 `.nomedia`）。
  * 通过磁盘 sidecar（视频 meta JSON + 漫画 `book.json`/`complete`）实现卸载重装后重新扫描识别；切换存储位置时先停写双引擎、逐文件 SHA-256 校验复制到公共目录，写 `pending` 标记待下次启动绑定新路径（规避 Media3 `SimpleCache` 运行期路径不可迁移限制）。

### 2.6 数据持久化、加密备份、安全、词典与更新器

* **持久化现状**：除 Media3 内部 SQLite 外，应用层**零数据库**，全部基于 `SharedPreferences` + `Gson` JSON 文本：
  * `r34v_native.xml`：按 `<siteId>:` 前缀分站存储收藏、观看/阅读历史、订阅、屏蔽词、搜索历史、漫画语言、订阅排序、加密 Cookie/JWT、本地视频索引，加全局 `settings_v1_*` 设置、`browse_positions_v1`、`playback_queue_v1`，以及单键巨物 `personal_library_v1`（包含全部 13 类跨站个人数据）。
  * `gallery_downloads_v1.xml`：每个图集下载任务一个 JSON 键。
  * `pending_upgrade.xml`：待安装更新状态。
  * 一次性幂等迁移逻辑：兼容旧版 Flutter `FlutterSharedPreferences`（含安全受限白名单 `ObjectInputStream` 反序列化旧搜索列表）与单站→多站命名空间迁移。
* **加密备份与恢复**（`LibraryBackup.kt` + `ComposeBackup.kt`）：
  * 二进制格式：`magic("R34VBK01", 8B) + salt(16B) + iv(12B) + AES-256-GCM(ciphertext + 16B tag)`，header 作为 AAD；KDF 为 `PBKDF2WithHmacSHA1`（600,000 次迭代，256-bit，兼容 API 24），密码至少 8 字符，明文上限 16 MiB。
  * 恢复前强制 `validateBackup()` 校验 schema v1、字段上限、HTTPS 同源域名归属、本地 URI 格式等，展示汇总预览，合并去重（观看/阅读取较新 `at`，片单条目取最早 `addedAt` + 最新状态，书签/收藏/屏蔽并集），单次 `commit()` 失败回滚；不导出会话、密钥、下载路径与媒体文件，恢复完成后主动释放 SAF 持久 URI 授权。
* **安全与会话**：`SessionVault` 使用 `AndroidKeyStore` AES-GCM 加密存储 EH/EX Cookie 与 Iwara JWT（绝不明文降级）；`SiteAuthActivity` 与凭据弹窗启用 `FLAG_SECURE` 防截屏；内置 `res/raw/isrg_root_x1.pem` + `network_security_config.xml` 解决 Android 7 Let's Encrypt 信任问题，禁用明文 HTTP（除 Debug）。
* **内容屏蔽与中文标签词典**：屏蔽词按站点隔离，统一匹配 `tagSlugs`、标题（连字符视为空格）与 URL；`assets/tag-names.json` 内置 165 条高频英文→中文标签映射，提供展示翻译与前缀优先检索建议。
* **应用内更新**（`UpdateController.kt`）：检查 GitHub Releases 最新 tag（12 小时自动静默检查，Debug 版拦截），按签名继承兼容性挑选标准 APK 或 `compat-2.0.0` APK，无 25s 页面总超时流式下载（100ms 进度节流），校验包名/版本/证书指纹与本地缓存摘要后经 `FileProvider` 调起系统安装器（授权未知来源返回后复用已校验缓存，无需重下）。

---

## 3. UI/UX 设计系统审计：历史沉积、不一致与体验痛点

### 3.1 致命视觉债：五代设计语言在源码内叠床架屋

结合 `docs/ui.md`、`docs/dev/ui-upgrade-2026-11-22.md`、git 历史（`652710e feat(ui): unify Android screens under the dark artwork theme`）与源码扫描，R34V 经历了 **5 次彻底的视觉转向**，每次都未彻底清理前代代码，导致当前生产代码出现严重的风格分裂与大量死分支：

| 演进代际 | 来源文件 / 依赖 | 代表色值与圆角 | 当前在生产环境中的真实状态 |
| --- | --- | --- | --- |
| 1. Netflix 暗黑（2.2.0） | `ComposeUi.kt:43-50` 顶层常量 | `#000000` / `#EF4954` / 14-24dp | 常量名被保留但重新指向了 Rental/Artwork 常量（如 `BrandColor = RentalRed`），造成命名误导 |
| 2. LiquidGlass（2.4.0） | `ComposeLiquid.kt` + `io.github.kyant0:backdrop:1.0.0` | API 31 18dp 模糊 / 33 折射 | **生产环境 0 调用！** 全局 grep 证实 `LocalLiquidBackdrop` 在 `app/src/main` 从未 provide，`GlassSurface`/`liquidSurface`/`liquidContent` 零生产引用；甚至 `debug/GlassPreviewActivity.kt` 也未使用 `backdrop` 库，属纯死依赖 |
| 3. 2.6 / 2.10 纯黑（2.6–2.10） | `docs/ui.md:19-54` | `#09090B` / `#131316` / `#F8F6F6` / `#FF4D58` | 仅留在文档与 `design/raven/raven-icon.svg` 中，未在主代码中作为实际色板运行 |
| 4. 录像带租赁店（2.10 dev） | `RentalStyle.kt`（146 行）+ `docs/ui.md:3-18` | 墨色 `#0E1419` / 米色 `#F1DFBD` / 暖红 `#EE6953` / `4dp` 方角 / 纸纹 `stockGrain` / 三色斜纹 `RentalStripes` | **一半被覆盖、一半仍生效**：`R34Theme` 用它初始化底色与圆角，但随后立即被 `ArtworkColors` 覆盖；结果 `rentalStock()`、`RentalStripes`、`RentalPaper` 在生产分支几乎全部不可达，却仍留着 `rentalKeyPress` 按压下沉动画与 `RentalSiteSwitch` 命名 |
| 5. 暗调封面动态配色（`652710e` / 2.12） | `ArtworkStyle.kt`（227 行） | 画布 `#0B0B10` / **粉红强调色 `#FF8DB4`** / `16dp` 卡片 / `50%` 胶囊 / 封面主色相渐变背景 | **实际线上生效层**，但**完全未写入 `docs/ui.md`**！`MainActivity`/`PlayerActivity`/`GalleryActivity`/`SiteAuthActivity` 均包裹 `ArtworkColors(palette)` |

**实证问题**：
* `ComposeUi.kt`、`ComposeAdvancedSearch.kt`、`ComposeBrowse.kt`、`ComposeDownloads.kt`、`ComposeNavigationRow.kt`、`RentalStyle.kt` 中存在 **25+ 处** `val palette = LocalArtworkPalette.current; if (palette == null) Rental... else Artwork...` 双轨判断；其中 `palette == null` 分支在所有正式 Activity 中均不可达，纯属维护负担。
* **色板与组件失调**：`ArtworkPalette.accent` 硬编码为粉红色 `#FF8DB4`（与 `design/raven` 的黑曜石 + 银白 + 猩红 `#FF4D58` 品牌定位冲突），且部分页面仍硬编码了旧色值（如 `PlayerActivity.kt:766` `Color(0xffffb0b9)`、`ComposeCinema.kt:251` `MutedColor`、`ComposeBrowse.kt:450` `RaisedColor`、`ComposeCinema.kt:409` `Color(0xfffff9fc)`）；搜索框在不同页面混用 `filledFieldColors()`（`ComposeFavorites.kt:99`、`ComposeSubscriptions.kt:331`、`ComposeSiteFunctions.kt:79`）与 `rentalSearchFieldColors()`（`MainActivity.kt:707`、`ComposeDownloads.kt:287`、`ComposeBackup.kt:99`），导致外观不统一。

### 3.2 具体 UX 痛点与陈旧交互模式

1. **弹窗套娃与对话框滥用（最突出的交互短板）**：
   * `ComposeLibraryHub.kt:284-292` 新建智能片单需要**连续弹出 4 层模态弹窗**（规则选择 → 媒体类型 → 来源选择 → 输入名称），无法在一个表单页里预览与修改。
   * `PlayerActivity.kt:907-934`（播放偏好）、`GalleryActivity.kt:475-542`（阅读行为 / 本书显示设置）、`MainActivity.kt:1139-1156`（屏蔽词管理）每点击一项就关闭并**递归重开同一 `AlertDialog`**，闪烁感强、动效生硬。
   * 「存储位置迁移」（`MainActivity.kt:1033-1088`）和「重复文件检查」（`MainActivity.kt:956-1030`）本是重型工作流，却全部塞在 `AlertDialog` 中，信息密度拥挤且缺乏空间。
   * `readerTools()`（11 项扁平文本列表）和 `playbackOptions()`（9 项扁平文本列表）缺乏图标与分组，用户高频操作（缩略图、书签、锁屏、画中画、亮度）埋得过深。
2. **自定义重绘浮囊底栏 + 平板布局不原生**：
   * `CinemaNavigation`（`ComposeCinema.kt:60-127`）使用 6 个动画颜色 + 3 层嵌套渐变 `drawWithCache` 绘制双层胶囊槽，高度随字体缩放，但在 `>=600dp` 平板/横屏设备上依然横在底部浪费垂直空间，未采用自适应 `NavigationRail`。
   * 一级导航状态语义割裂：进入 `siteFunctions` 时 `navigationTab` 仍显示高亮「首页」；进入片库二级页（`下载 / 收藏`）在 `MainActivity.kt:484` 靠 `libraryTab` 特判返回键，而非统一导航栈。
3. **多媒体播放与阅读器细节缺失**：
   * **视频播放器**：音量/亮度/进度拖动仅在画面中央显示一段纯文本胶囊（`hint`），缺少直观的进度条/亮度条/音量条指示器；进度拖动帧预览仅限本地导入视频，已完整离线下载的 MP4 视频无法享受预览；全屏切换与手势绑在传统 View `GestureDetector` 上，和 Compose 控件层的触摸仲裁耦合脆弱。
   * **漫画阅读器**：底部滑块拖动仅显示数字页码，缺少对应页缩略图浮窗预览；页面缩略图总览塞在 `AlertDialog`（最高 440dp）内而非全屏或高占比 `ModalBottomSheet`，在百页漫画中浏览效率偏低；全屏工具栏缺少一键快速切模式/亮度/自动翻页的快捷图标区。
4. **可发现性与信息架构冗余**：
   * 「我的」（`home`）与「片库」（`library`）在稍后看、更新、搜索等入口存在明显重叠；同时顶栏存在「站点切换」+「本站功能」+「设置」三个全局概念，用户需理解「发现页」与「本站功能页」的微妙边界。

---

## 4. 代码架构与技术债务深度剖析

### 4.1 架构与依赖注入债务

* **零 DI + 隐式 Service Locator**：全项目通过 `(LocalContext.current.applicationContext as R34Application)` 在 Composable 和 data 层函数（`personalCatalog(app)`、`MediaRouter`、`ComposeDownloads`、`CrossSearchState`）中直接拉取依赖，组件无法脱离 `R34Application` 独立预览或做纯单测。
* **全局可变单例 `SiteRegistry.current`**：`SiteContext.kt:258` 暴露 `@Volatile var current`，且 `Models.kt:77,131,157,162,167` 中 `Video`/`Watch`/`Favorite`/`DownloadMeta`/`LocalVideo` 的默认参数均隐式读取 `SiteRegistry.current.id`。历史上已多次因此导致跨站操作写错站点分区（见 `docs/dev/roadmap-2026-11-21.md:155-166` 的 3 起 P0/P1 事故）。

### 4.2 状态管理与重组性能债务

* **`revision++` 强制重组反模式（47 处）**：全项目仅 5 个 `ViewModel`（`FeedStates`、`CrossSearchState`、`SavedSearchUpdateModel`、`GalleryExportModel`、`ReaderViewportModel`）。由于 `LibraryStore` 非响应式，`MainActivity.kt`（32 处）、`PlayerActivity.kt`（7 处）、`GalleryActivity.kt`（5 处）靠声明 `@Suppress("UNUSED_VARIABLE") val observedRevision = revision` 并手动 `revision++` 触发整棵页面树大范围重组。
* **卡片级订阅放大开销**：`ComposeUi.kt:258-268` 中每个 `MediaCard` 内部都独立调用 `personal.changes.collectAsState()` 并 `remember { ComposeDialogs() }`！在跨站搜索返回 200+ 作品的网格中，任一与卡片无关的个人库变动（如阅读另一本书或标记未读）都会唤醒全部可见卡片的收集与重组，且每个卡片维护独立的 Dialog Host。
* **`FeedState` 持有 UI 滚动状态**：`FeedState.kt:43-44` 直接在 `AndroidViewModel` 缓存的对象里实例化 `LazyListState()` 和 `LazyStaggeredGridState()`，虽然规避了切换页面丢滚动位置，但将 Compose UI 句柄长期驻留在 ViewModel 层，不利于解耦与进程恢复标准化。
* **`ComposeDialogs` 单槽覆盖与无状态恢复**：`ComposeUi.kt:325-328` 全局仅有一个 `content: (@Composable () -> Unit)?` 槽位。后台静默更新检查（`UpdateController`）、异步下载失败提示或子对话框可能无声覆盖用户当前正在操作的弹窗，且配置变更/进程重建后弹窗状态完全丢失。

### 4.3 导航与多 Activity 栈缺陷

* **手写字符串路由与双轨导航**：`MainActivity.kt:57-78` 维护 18 个魔法字符串路由（`"siteHome"`, `"home"`, `"explore"`, `"search"`, `"multiSearch"`, `"feed"`, `"index"`, `"library"`, `"settings"`, `"backup"`, `"updates"`, `"searchUpdates"`, `"subscriptions"`, `"siteFunctions"`, `"siteFavorites"`, `"siteDownloads"`, `"siteSubscriptions"`, `"siteAccount"`）外加独立的 `libraryTab` 字符串子状态，并通过 4 个并行 `ArrayList<String>`（`backScreens`/`backRequests`/`backSites`/`backLanguages`）手动序列化返回栈到 `Bundle`。
* **Activity 任务栈泄漏 / 无限叠加**：
  * 从 `PlayerActivity.kt:797` 或 `GalleryActivity.kt:734` 点击标签/作者链接时，直接 `startActivity(Intent(this, MainActivity::class.java).putExtra("link", link.url))` 且**未加 `FLAG_ACTIVITY_CLEAR_TOP`/`SINGLE_TOP`**，会在任务栈里反复叠加新的 `MainActivity` 实例。
  * 在 `PlayerActivity.kt:820` 连续点击相关推荐视频，会不断叠加新的 `PlayerActivity` 实例（每次新建 Activity 与 Gson 序列化完整 `Video` JSON 走 Intent Extra），没有栈深限制或页内切换。
* **返回键处理不统一**：`SiteAuthActivity` 使用 `OnBackPressedDispatcher`，其余 3 个 Activity 使用 Compose `BackHandler`，且 `MainActivity.kt:459-466` 需要手动协调教程、抽屉、`libraryTab` 与 `backStack` 四层优先级。

### 4.4 持久化、序列化与并发/生命周期债务

* **单键 SharedPreferences JSON 巨物（最大扩展性隐患）**：
  * `PersonalStore`（`PersonalLibrary.kt:92-127`）将片单（最多 1,000 个 / 每单 50,000 项）、页码书签（50,000）、单图收藏（20,000）、时间书签（5,000）、订阅收件箱、搜索更新、偏好设置全部打包进 `r34v_native.xml` 的**单个字符串键 `personal_library_v1`**。每次微小改动（如标记 1 条已读或点 1 次稍后看）都要在 `synchronized(library)` 锁内将整个大对象 `gson.toJson()` 写回整个 XML 文件。一旦用户数据增长至数 MB，必然触发主线程卡顿、ANR 或解析损坏（为此代码被迫写了 `.corrupt.<ts>` 兜底抢救逻辑）。
  * `LibraryStore` 同样把每站 500 条观看历史、500 条阅读历史、50,000 条收藏整块存成单键 JSON，且读取即同步全量反序列化。
* **同步 `.commit()` 阻塞风险（13 处）**：
  * `LibraryStore.kt:67,112,146,226,238`、`PlaybackQueue.kt:54,59`、`StorageRoot.kt:333,337,341`、`GalleryDownloadStore.kt:54,55`、`LibraryBackup.kt:250` 使用同步磁盘 `commit()`；其中 `PlaybackQueueStore.write/end` 和 `GalleryActivity.onStop -> ReadingProgressSaver.flush() -> saveReading()` 会在主线程触发全量 JSON 序列化 + 同步 fsync 写入。
* **Gson 破坏 Kotlin 空安全**：
  * `PersonalLibrary.kt:26-31`、`NextFeatures.kt:146-150`、`LibraryStore.kt:214-219` 中明确记录了 Gson 通过反射绕过构造函数默认值、将非空 Kotlin 字段赋为 `null` 导致整站数据崩溃的历史坑，被迫到处手写 `.restored()` 防御方法。
* **生命周期与协程隐患**：
  * `PlayerActivity.kt:162-169` 在 `onCreate` 中直接 `lifecycleScope.launch { while (isActive) { ... delay(500/100) } }`，**未使用 `repeatOnLifecycle(Lifecycle.State.STARTED)`**，导致页面退至后台（非 PiP）时两个轮询协程仍在空转耗电。
  * 4 个核心单例（`BatchDownloads`、`DownloadPolicy`、`GalleryDownloadStore`、`PlaybackStore`）各自持有永不取消的 `CoroutineScope(SupervisorJob() + ...)`，缺乏统一 `ApplicationScope` 注入，导致测试难以确定性排空异步任务。
  * `R34Application.onCreate` 注册的 `ConnectivityManager.NetworkCallback` 与 `PROXY_CHANGE_ACTION` 广播接收器、以及 `GalleryDownloadService` 的 6 小时 `PARTIAL_WAKE_LOCK` 均需在 Raven 中以明确生命周期组件封装。

### 4.5 测试体系现状与债务

* **优势**：测试资产极其丰富——374 个 JVM 单元测试 + 318 个 Android 仪器测试（共 692 个 `@Test`），覆盖了协议契约、备份加解密、离线下载完整性、Media3 解码、大字体/宽屏 UI 和跨进程滚动位置恢复。
* **债务**：
  1. **目录扁平杂糅**：75 个 `androidTest` 文件和 43 个 `test` 文件全部堆在 `com.r34v.r34v` 根包下，将单元测试、UI 回归、性能实验（`PlaybackPerformanceProbe.kt` 790 行、`PlayerEnginePerformanceProbe.kt` 522 行）和依赖外网的实站探针（`Live*Probe.kt`）混在同一个测试源集中。
  2. **缺乏标准测试设施**：未使用 `kotlinx-coroutines-test`（`runTest` / `TestDispatcher`）、`Turbine`（Flow 断言）或 Hilt/Koin 测试替换，导致大量逻辑只能靠重型模拟器 `androidTest` 验证；UI 测试过度依赖中文文案匹配与 `ComposeFixture` 静态注入，缺乏语义化 `testTag` 契约与可独立运行的 Compose Screenshot/Preview 测试体系。

---

## 5. Raven 现代化重构蓝图（P0 核心 vs P1 增强）

### 5.1 功能优先级分级矩阵

| 领域 | **P0（首版必须达标的核心架构与核心能力）** | **P1（首版架构预留接口、第二阶段平滑交付）** |
| --- | --- | --- |
| **应用外壳与导航** | 单 Activity（或主壳 + 独立 PiP 播放器 Activity）+ Type-Safe Navigation Compose；自适应导航（手机底栏 / 平板 `NavigationRail`）；5 主入口（首页 / 发现 / 搜索 / 片库 / 我的）；19 站分组切换抽屉 + 常用置顶；ExHentai 官方 WebView 登录与权限门禁；系统代理/DNS 遵循与 ISRG Root X1；首次安装切站引导 | 平板真双栏分屏详情（列表-详情联动扩展至浏览与搜索页）；App Shortcuts / 桌面快捷切站；更精细的站内功能页定制 |
| **浏览与发现** | 视频影院首页（大画幅 Hero 轮播 + 榜单 + 最新片架 + 续看）与漫画/写真原生比例瀑布流；发现页单行筛选（分类/排序/语言/目录）；跨进程浏览位置恢复；按站点屏蔽词过滤；离线列表快照兜底 | 首页发现频道（`DiscoveryChannel`）自定义编排；封面自适应氛围光效开关 |
| **视频播放器** | Media3 引擎统一播放（MP4/HLS/Rou PNG 解包）；全手势（亮度/音量/进度/双击快退进与全屏/长按倍速）+ 可视化 HUD；清晰度切换 + 带宽估测选档 + 弱网卡顿自动降档 + 1 次过期地址刷新；会话级 MP4(64MB)/HLS(32MB) Seek 缓存 + 可选 512MB 流缓存；倍速与按站播放偏好；锁屏防误触；画中画 PiP；定时暂停；进度保存与续播；详情（简介/标签翻译与屏蔽收藏/评论/相关推荐） | A-B 区间循环；时间书签与备注分组；简介/评论时间戳点击跳转（`TimestampText`）；离线/本地视频拖动缩略帧预览；站外播放器直达 |
| **漫画与写真阅读器** | 4 模式阅读（上下连续条漫 / LTR / RTL / 横屏双页含封面规则）；1–5× 缩放与几何边界约束 + 适配模式 + 手动裁边；3 页预读 + 24MiB 屏外位图预算 + 宽高比 ViewModel 共享；失效页地址自愈；单击全屏与阅读角标状态栏；缩略图总览（高占比底部面板）；页码书签；进度防抖保存与页内偏移恢复；离线整本与稀疏选页阅读；Cosplaytele 内嵌视频跳转 | 音量键翻页与反向；自动翻页与图集幻灯片；单图收藏；独立阅读亮度与保持亮屏；EH/EX 原图优先策略；原图保存/分享与 CBZ 导出 |
| **统一片库与搜索** | 跨站统一搜索（3 并发、混合瀑布流、来源筛选、失败重试、目录直达）+ 高级筛选（包含/排除标签、时长/页数/评分/年份等）+ 165 词条中文标签自动补全；跨站收藏/历史汇总；稍后看 + 自定义合集（备注/置顶/封面/手动重排/视图排序）；分享链接与粘贴链接识别（`ACTION_SEND`） | 保存搜索方案与定期/手动更新收件箱；标签订阅管理、聚合流与全局更新收件箱；智能片单（未看完/已离线未看/最近加入）；片单顺序观看/离线队列；本地 CBZ/ZIP/目录/视频导入与重复整本指纹检查（F41） |
| **统一下载与存储** | 统一下载中心（状态标签 + 折叠筛选 + 批量暂停/继续/失败一键重试）；Media3 视频下载（MP4/HLS、2 并发、前台服务、删除代次保护）；漫画/写真按页断点续传下载（原子落盘、选页下载、错误分类与 `Retry-After` 限流等待、完整性审计与缺页修复）；统一「仅非计费网络」策略；应用内存储 + 离线播放/阅读严禁回源 | 公共目录 `Raven/` 卸载保留与双根迁移重装自动识别（SAF/All-Files 权限流）；下载任务分组与队列优先级调整；片单离线准备空间估算 |
| **持久化、备份与更新** | Room 数据库 + Preferences DataStore + `kotlinx.serialization`；AndroidKeyStore 加密会话库（`SessionVault`）；加密备份导入/导出（兼容读取 `R34VBK01` 或平滑升级 `RVNBK01` AES-256-GCM 合并去重）；GitHub Release 更新检查与签名校验安装 | 局域网迁移、完整旧版 R34V `.r34vbackup` 一键导入迁移向导 |

---

### 5.2 目标代码架构升级方案（彻底消除 R34V 核心技术债）

#### 1. 清晰的多模块 Gradle 架构（替代 `:app` 单模块）

```text
Raven/
├── app/                      # 仅负责 Application、DI 根图、顶层导航装配、Manifest
├── core/
│   ├── model/                # 纯 Kotlin 领域模型（kotlinx.serialization，无 Android 依赖，显式 siteId）
│   ├── common/               # AppDispatchers、ApplicationScope、Result/AppError、自然排序等纯工具
│   ├── designsystem/         # 唯一统一 Raven Obsidian 主题、Tokens、共享组件（零双轨分支）
│   ├── network/              # OkHttp 客户端池、分站 Cookie/JWT、代理监听、429 退避、Coil ImageLoader
│   ├── database/             # Room DB（分表：watch_history、reading_history、favorites、shelves、bookmarks、downloads、subscriptions 等）
│   ├── datastore/            # Preferences / Proto DataStore（全局设置、分站设置、浏览锚点）
│   ├── security/             # SessionVault (AndroidKeyStore AES-GCM)、BackupCodec (PBKDF2+AES-GCM)
│   ├── player/               # Media3 引擎控制器、StreamSelection、StallPolicy、LinkSpeed、缓存策略
│   ├── reader/               # ReaderBuffer、页预读、几何缩放计算、GalleryCache & PageStore、CBZ 导出
│   └── download/             # 统一 DownloadCoordinator 抽象层（收敛 Media3 Video + Gallery 引擎）
├── sites/
│   ├── spi/                  # 插件化 SiteProvider SPI（与 audit-sites.md 对齐，无硬编码 switch）
│   └── impl/                 # 19 站独立适配器实现 + 对应纯 JVM 契约测试
└── feature/
    ├── home/                 # 站点首页（影院首页 + 漫画/写真瀑布流）与切站抽屉
    ├── discover/             # 发现页与站内索引目录、站点功能页
    ├── search/               # 单站/跨站搜索、高级筛选、保存搜索更新
    ├── library/              # 统一片库、合集整理、智能片单、单图收藏、本地导入
    ├── profile/              # 「我的」续看/续读中心、频道管理
    ├── player/               # 视频详情与播放器界面（MVI ViewModel）
    ├── reader/               # 图集详情与阅读器界面（MVI ViewModel）
    ├── downloads/            # 统一下载中心 UI 与状态流
    ├── subscriptions/        # 标签订阅管理、聚合流与全局更新收件箱
    ├── settings/             # 设置、屏蔽词、存储迁移、重复扫描、加密备份、更新器
    └── auth/                 # 官方 WebView 认证（E-Hentai / ExHentai）
```

#### 2. 彻底替换五大核心技术债

| R34V 痛点 | Raven 现代化方案 | 直接收益 |
| --- | --- | --- |
| `SharedPreferences` 单键巨物 `personal_library_v1` + 13 处同步 `.commit()` + Gson 破坏空安全 | **Room (SQLite) + Preferences DataStore + `kotlinx.serialization`**。将收藏、历史、片单、片单条目、书签、单图收藏、时间书签、订阅、收件箱、图集下载任务拆为规范化关系表（带复合主键与索引），DAO 直接暴露 `Flow<List<T>>` | 写入从 O(全库序列化) 降至 O(单行事务)；零主线程阻塞；彻底消灭 `revision++`（47 处）与 Gson null-safety 崩溃 |
| 手动 `R34Application` Service Locator + 全局可变 `SiteRegistry.current` | **Hilt（或 Koin）构造函数注入 + 显式 `siteId: String` 传递**。彻底删除 `SiteRegistry.current` 全局可变状态，所有路由、UseCase、Entity 强制携带 `siteId` | 根除跨站写错分区/发错凭据类 P0 缺陷；每个 ViewModel 与 Repository 可纯 JVM Mock 单测 |
| `MainActivity` 18 字符串路由 + 4 Activity 叠加泄漏 + `ComposeDialogs` 单槽覆盖 | **Type-Safe Navigation Compose**（`@Serializable` 路由对象）+ 统一 Single-Activity 主壳（仅 PiP 视频播放器按需使用 `launchMode="singleTop"` 独立 Activity 或平滑共享壳）+ 各页面 ViewModel 驱动的声明式 `ModalBottomSheet`/Dialog 状态 | 消除 `MainActivity`/`PlayerActivity` 无限堆叠；支持预测性返回动画；弹窗不再互踩且配置变更不丢状态 |
| 上帝 Activity（900–1,178 行）+ 业务写在 `@Composable` 里 + 后台空转轮询 | **UDF / MVI `StateFlow<UiState>` ViewModel**：每个 Feature 独立 ViewModel，将 Media3 生命周期封装在 `PlayerSessionController`，使用 `repeatOnLifecycle(STARTED)` 托管定时器与进度同步，`MediaCard` 改为纯无状态渲染组件（状态由父列表聚合传入，点击事件上抛） | 单文件严格控制在 300 行内；200 卡片网格零重复订阅；后台零空转协程 |
| 混合 5 类异构实体的 `ComposeDownloads.kt`（786 行） | 引入 **`DownloadRepository` 统一领域投影层**：将 Media3 `Download`、图集 `GalleryDownload`、磁盘恢复 `LibraryRecord` 映射为统一的 `sealed interface DownloadItem`，在 Repository 层完成状态归类、损坏判定、筛选和排序，UI 层只消费 `Flow<DownloadsUiState>` | UI 文件瘦身 60%+，删除/重试/暂停逻辑统一经过领域层事务防护 |

---

### 5.3 UI/UX 现代化升级方案（Raven Obsidian 设计系统）

#### 1. 终结五代视觉分裂，建立唯一「Raven Obsidian」设计语言

直接承接 `D:\rule34\design\raven\raven-icon.svg` 已确立的视觉基因（深黑曜石背景 `#1B1B20 → #07070A`、银白几何羽翼 `#FFFFFF → #D8D5D9`、猩红点睛 `#FF4D58`），彻底删除 `io.github.kyant0:backdrop:1.0.0`、`ComposeLiquid.kt`、`RentalStyle.kt` 的死分支及 `#FF8DB4` 粉红覆盖：

* **色彩系统（单一真源，高可读深色主题）**：
  * Canvas：`#09090B`（纯净近黑，适配 OLED 省电且突出作品封面）
  * Surface / Card：`#131317`；Elevated / Sheet：`#1C1C22`；Outline：`#2A2A33`（低对比 1dp 轻描边，不抢封面）
  * Primary Text：`#F5F5F7`；Secondary Text：`#9E9EA8`
  * Primary Action（主 CTA）：高对比纯白 `#F5F5F7` 底 + `#09090B` 字（沿用经实测最高对比的影院播放主按钮）
  * Brand / Active Accent：**Raven Crimson `#FF4D58`**（仅用于当前导航指示、进度条、关键激活态、品牌点睛，绝不大面积滥用）
  * 可选柔和封面氛围光：仅作用于视频 Hero 顶部与详情顶栏背景的低饱和度径向/线性暗晕（alpha ≤ 0.22），**不再篡改全局 `MaterialTheme.colorScheme.primary`**。
* **圆角与排版标尺**：卡片 `12dp`、容器/面板 `16dp`、按钮/胶囊 `10dp` 或 `CircleShape`（语义分明，全站统一）；排版严格对齐 Material 3 TypeScale 并支持系统 200% 字体自适应不裁切。
* **组件库洁净原则**：全站输入框仅保留 1 套 `RavenSearchField` / `RavenTextField`；所有卡片、行、面板强制走 `MaterialTheme.colorScheme` 语义 Token，禁止页面私自硬编码 `Color(0x...)`。

#### 2. 关键页面与交互升级建议

1. **自适应主导航（`NavigationSuiteScaffold`）**：
   * 手机（`<600dp`）：简洁高对比实底 5 项底栏（取消复杂 6 色重绘双层胶囊，用清晰图标 + 猩红小指示条/高亮色，降低 GPU 离屏渲染开销）。
   * 平板/折叠屏（`>=600dp`）：自动切换为左侧 `NavigationRail` + 顶栏常驻站点切换器，释放 100% 垂直阅读/观影空间。
2. **彻底消灭套娃弹窗**：
   * 「新建智能片单」「高级筛选」「合集设置」「播放偏好」「单本阅读显示设置」全部升级为**单页表单 `ModalBottomSheet` 或独立二级页面**（所有开关/滑块/选项同屏可见、一次提交或即时生效）。
   * 「存储位置迁移」与「重复文件检查」升级为**独立设置子页面**，展示清晰进度、空间柱状对比与可展开副本对比卡片。
3. **播放器 UX 升级**：
   * 手势反馈升级为带图标 + 进度条的半透明浮层 HUD（亮度/音量/快进退可视化）。
   * 控件栏底部/更多面板重构为**分组网格 BottomSheet**（常用快捷开关如锁屏/PiP/定时/倍速一触即达，不再埋在 9 行纯文字列表里）。
   * 扩展 `LocalSeekPreview` 支持已完整离线下载的 MP4 视频拖动帧预览（P1）。
4. **阅读器 UX 升级**：
   * 将 11 项扁平「阅读工具」拆分为底部快捷图标栏（`缩略图 / 书签 / 模式 / 亮度与状态 / 更多设置`）+ 分组控制面板；缩略图总览改用全高 `ModalBottomSheet`（85% 屏高，带页码快跳条）。
   * 底部进度条拖动时在滑块上方浮现当前目标页小缩略图预览（缓存命中时即时显示，P1）。
5. **信息架构微调（减轻「我的」与「片库」重叠）**：
   * 明确分工：**「我的」= 消费与动态中心**（跨站续看/续读、稍后看队列、订阅与搜索未读动态、常用搜索与固定频道）；**「片库」= 资产与管理中心**（自定义片单、智能片单、跨站收藏、历史记录、下载中心、单图收藏、本地导入与链接打开）。
   * 顶栏策略统一：站点级页面（首页 / 发现 / 本站功能）常驻站点切换胶囊，跨站级页面（片库 / 我的）不再混淆展示无关站点名。

#### 3. 现代化测试金字塔

* **JVM 单元测试（毫秒级主防线）**：按模块拆分（`:core:*`、`:sites:*`、`:feature:*`），引入 `kotlinx-coroutines-test`（`runTest`）+ `Turbine` + `MockWebServer`，确保全部 ViewModel 状态机、Room DAO（或 Robolectric/in-memory DB）、备份编解码、选档/降档策略、阅读几何计算 100% 可在无设备环境秒级回归。
* **Instrumented / Compose UI 测试（精准分层）**：将性能实验与在线实站探针拆离到独立 Gradle task / `benchmark` 或 `androidTest` 过滤注解，常规 `connectedDebugAndroidTest` 仅保留确定性的离线/MockWebServer 端到端冒烟（Media3 真解码、离线阅读、PiP、权限与大字体/平板视觉断言），全部使用稳定 `testTag` 而非脆弱的中文文案断言。

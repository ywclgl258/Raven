# R34V 站点适配器与网络协议审计规范（Raven 重写基准）

> 审计基准仓库：`D:\rule34`（只读审计，源码位于 `D:\rule34\app\src\main\kotlin\com\r34v\r34v\`）  
> 目标输出：`d:\Raven\docs\audit-sites.md`

---

## 1. 结论总览

1. **站点覆盖规模**：现行注册表（`data/SiteContext.kt:245-252`）共 **19 个活跃网络站点** + **1 个非网络本地源 `local`** + **2 个已退役视频站标识**（`kbj` -> `kbj.im`、`netflav` -> `netflav.com`，仅保留给旧片库显示，打开详情会被 `Repository.detail()` 显式拒绝）。
   - **视频站（8）**：`r34v`、`hanime1`、`iwara`、`missav`、`jable`、`rouvideo`、`kbjfree`、`pornhub`
   - **漫画站（6）**：`ehentai`、`exhentai`、`nhentai`、`hitomi`、`wnacg`、`jmcomic`
   - **写真站（5）**：`4khd`、`everia`、`4kup`、`galleryepic`、`cosplaytele`
   - **双模态特例（2）**：`iwara`（`SiteCategory.VIDEO` 但同时提供 `/images` + `/image/<id>` 图库）、`cosplaytele`（`SiteCategory.PHOTO` 但文章内可嵌 `cossora.stream` AES 加密 HLS 视频）。
2. **协议复杂度分布**：19 站跨越 7 种截然不同的协议形态——传统 SSR/HTML（KVS/Laravel/WordPress/MacCMS）、公开 REST JSON v2 + 动态 CDN（nhentai/Iwara）、二进制 Range 索引 + 磁盘 B-Tree + 动态分片路由（Hitomi）、混淆脚本解包（MissAV Base-62 Packer、rou.video 字节偏移 `ev`、WNACG `document.writeln`）、Next.js RSC Flight 流提取（KBJFree/GalleryEpic）、自定义媒体容器/加密拆壳（`rou.video` PNG `roUd` + zlib、`Cosplaytele` Cossora AES-256-CBC、`JMComic` Canvas 分条 MD5 反混淆）、以及 WebView 论坛认证状态机（E-Hentai/ExHentai）。
3. **Top 3 架构改进机会（Raven 重写核心抓手）**：
   - **消除 16+ 处跨层站点硬编码**：将散布在 `Network.kt`、`GalleryRepository.kt`、`PlaybackStore.kt`、`IncomingLink.kt`、`SearchSupport.kt`、`SiteFunctionPage.kt`、`MainActivity.kt` 等处的 `when (site.id)` / `if (site.id == ...)` 全部收回插件化 **`SitePlugin` SPI + 组合能力接口**，并移除进程级可变全局单例 `SiteRegistry.current` / `LibraryStore.siteId`。
   - **非阻塞网络调度 + 统一凭据安全 + 强类型错误体系**：用协程 `Mutex + delay` 的 `HostPacer` 取代 OkHttp 拦截器内阻塞线程池的 `Thread.sleep`（`Network.kt:409`）；消除每请求重建 `OkHttpClient`（`JmcomicAdapter.kt:105`）；将 `r34v`/`hanime1` 明文 Cookie 统一纳入 `SessionVault` 加密；以 `sealed class SiteException` 取代 `DownloadRecovery.classify()` 的中文子串匹配。
   - **收敛重复协议脚手架与跨适配器依赖**：解除 4 个视频适配器对 `MissavProtocol.encode/clean` 的跨站直接调用；将 `FourKhdAdapter` 合并进 `WordPressPhotoEngine`；抽离通用 `core:site-kit`（LRU+TTL 缓存、JS 花括号/Packer/RSC 提取器、双语流合并游标、统一 `HostRouter` 镜像路由）。

---

## 2. 全量站点清单与能力矩阵

来源：`data/SiteContext.kt:71-253`、`data/Network.kt:565-602`、`data/SearchSupport.kt:5-24`、`ui/SiteFunctionPage.kt:47-86`。

### 2.1 核心元数据总表（19 在线站 + `local` + 2 退役站）

| # | `id` | 名称 | `category` / `mediaKind` | 主 `base` / `host` | `mirrors` / `apiHosts` | `mediaDomains` | 特殊 UA / 认证 / 备注 | 适配器实现 |
|---|---|---|---|---|---|---|---|---|
| 1 | `r34v` | Rule34Video | VIDEO / VIDEO | `https://rule34video.com` | 无 | 无（走主域 `/get-file` 302 CDN） | 默认移动 UA；KVS CSRF 表单登录（`PHPSESSID`） | `KvsAdapter` + `HtmlParser` |
| 2 | `hanime1` | Hanime1 | VIDEO / VIDEO | `https://hanime1.me` | 无 | 无（`mediaReferer=https://hanime1.me/`） | 默认移动 UA（禁空 UA）；Laravel `_token` 登录 | `HanimeAdapter` |
| 3 | `iwara` | Iwara | VIDEO / VIDEO（兼 GALLERY） | `https://www.iwara.tv` / `iwara.tv` | `apiBase=https://apiq.iwara.tv`<br>`apiHosts={apiq.iwara.tv, api.iwara.tv}` | `iwara.tv` | `UA="R34V/Android"`；JWT Bearer 登录；需 `Origin`/`x-site` | `IwaraAdapter` + `IwaraProtocol` + `IwaraSignature` |
| 4 | `missav` | MissAV | VIDEO / VIDEO | `https://missav.ws` | `mirrors=[missav.media]` | `mrstcdn.store` | 默认移动 UA；公开；`.ws` 常 CF 挑战自动切 `.media` | `MissavAdapter` + `MissavProtocol` |
| 5 | `jable` | Jable | VIDEO / VIDEO | `https://jable.vip` | `mirrors=[jable.tv]` | `senlin2026.com`, `sl260204.top` | 默认移动 UA；公开；`jable.tv` 长期 CF 挑战，仅作镜像识别 | `JableAdapter` + `JableProtocol` |
| 6 | `rouvideo` | rou.video | VIDEO / VIDEO | `https://rou.video` | 无 | `rn221.xyz`, `rn252.xyz`（分片域动态轮换） | 默认移动 UA；公开；PNG `roUd` 拆壳 HLS | `RouVideoAdapter` + `RouVideoProtocol` + `RouPng` |
| 7 | `kbjfree` | KBJFree | VIDEO / VIDEO | `https://kbjfree.com` | 无 | `marshlecdn.com` | 默认移动 UA；公开；仅静音预览 MP4（整片需站内积分） | `KbjFreeAdapter` + `KbjFreeProtocol` |
| 8 | `pornhub` | Pornhub | VIDEO / VIDEO | `https://www.pornhub.com` | `mirrors=[pornhub.com]` | `phncdn.com` | 默认移动 UA（双 DOM 兼容桌面+移动）；公开 | `PornhubAdapter` + `PornhubProtocol` |
| 9 | `ehentai` | E-Hentai | COMIC / GALLERY | `https://e-hentai.org` | 无 | `ehgt.org`, `hath.network` | `UA="R34V/Android"`；可选 WebView/Cookie 导入；自动注 `nw=1`；支持原图 | `EhAdapter` + `EhProtocol` + `EhLogin` |
| 10 | `exhentai` | ExHentai | COMIC / GALLERY | `https://exhentai.org` | 无 | `ehgt.org`, `hath.network` | `UA="R34V/Android"`；**强门禁**（必须导入含权限会话）；自动注 `nw=1`；支持原图 | `EhAdapter` + `EhProtocol` + `EhLogin` |
| 11 | `nhentai` | nhentai | COMIC / GALLERY | `https://nhentai.net` | 无 | `nhentai.net`（`/api/v2/cdn` 动态下发 `i*/t*.nhentai.net`） | `UA="R34V/2.2.0 (Android)"`；公开 v2 REST API | `NhentaiAdapter` + `NhentaiProtocol` |
| 12 | `hitomi` | Hitomi | COMIC / GALLERY | `https://hitomi.la` | CDN `ltn.gold-usergeneratedcontent.net` | `gold-usergeneratedcontent.net` | `UA="R34V/2.2.0 (Android)"`；公开二进制索引 + `gg.js` | `HitomiAdapter` + `HitomiProtocol` |
| 13 | `wnacg` | WNACG | COMIC / GALLERY | `https://www.wnacg.com` / `wnacg.com` | 无 | `t2/t3/img2/img5.wnimg1.ru`, `t4/img5.qy0.ru` | **强制桌面 Windows Chrome UA**；公开 | `WnacgAdapter` + `WnacgProtocol` |
| 14 | `jmcomic` | JMComic | COMIC / GALLERY | `https://jmcomic-zzz.one` | `mirrors=[18comic.ink, jmcomic-zzz.org, 18comic.vip]` | `cdn-msp{,2,3}.{18comic.ink,jmcomic-zzz.one}` | 默认移动 UA；公开；自动路由探测镜像 + Canvas 分条还原 | `JmcomicAdapter` + `JmcomicMirrorRouter` + `JmcomicProtocol` |
| 15 | `4khd` | 4KHD | PHOTO / GALLERY | `https://www.4khd.com` / `4khd.com` | 无 | `i0.wp.com`, `pic.4khd.com` | 默认移动 UA；`mediaReferer=https://www.4khd.com/`；公开 | `FourKhdAdapter` + `FourKhdProtocol` |
| 16 | `everia` | Everia | PHOTO / GALLERY | `https://everia.club` | 无 | **空集合**（外链 CDN 按帖轮换 `fujibox.top`/`karubox.top` 等） | 默认移动 UA；公开；图片靠 `SiteContext` tag 透传归属 | `PhotoArchiveAdapter` + `PhotoProfiles.everia()` |
| 17 | `4kup` | 4KUp | PHOTO / GALLERY | `https://4kup.net` | 无 | `blogger.googleusercontent.com` | 默认移动 UA；公开；正文 `img[src]` 为 `data:` 占位，取 `a.thumb-photo[href]` | `PhotoArchiveAdapter` + `PhotoProfiles.fourKup()` |
| 18 | `galleryepic` | GalleryEpic | PHOTO / GALLERY | `https://galleryepic.com` | 无 | `static.galleryepic.xyz` | 默认移动 UA；公开；Next.js payload 抽完整 UUID 图表；搜索走 Coser 目录 | `PhotoArchiveAdapter` + `GalleryEpicProfile` |
| 19 | `cosplaytele` | Cosplaytele | PHOTO / GALLERY（兼内嵌 VIDEO） | `https://cosplaytele.com` | 无 | `cossora.stream`（图片在主域 `/wp-content/uploads/`） | 默认移动 UA；公开；文章可嵌 Cossora AES-256-CBC HLS 视频 | `PhotoArchiveAdapter` + `PhotoProfiles.cosplayTele()` + `CossoraProtocol` |
| — | `local` | 本地导入 | COMIC / GALLERY（兼本地 VIDEO） | `""` | 无 | 无 | 非网络源，`SiteRegistry.librarySources = all + LOCAL` | `LocalLibrary` |
| — | `kbj` / `netflav` | 已退役 | 仅 `retiredVideoHosts` 映射 | `kbj.im` / `netflav.com` | — | — | `Repository.detail()` 拦截并报错 `"该作品的来源站点已移除"` | 无 |

> 默认移动 UA 常量（`SiteContext.DEFAULT_UA`）：  
> `Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36`

### 2.2 能力、排序、语言过滤与高级筛选矩阵

| `id` | `capabilities` | 支持的 `Sort` | 搜索维度（`SiteFunctionPage.kt`） | `supportsLanguage` | 分页机制 | 高级筛选（`SearchSupport.kt`） |
|---|---|---|---|---|---|---|
| `r34v` | FEED, SEARCH, TAG, MODEL, CATEGORY, RANKING, LOGIN, DOWNLOAD | RELEVANCE, NEWEST, VIEWS, RATING, DURATION, RANDOM | 关键词 / 标签 / 作者（含目录索引） | `false` | 路径 `/N/` | `DURATION`, `RATING`（**仅本地过滤已加载页**） |
| `hanime1` | FEED, SEARCH, TAG, MODEL, CATEGORY, LOGIN, DOWNLOAD | NEWEST, VIEWS, RATING, DURATION（含 POPULAR=RANDOM/SEARCH=RELEVANCE） | 关键词 / 标签 | `false` | `?page=N` | `TAGS`, `YEAR`, `MONTH`, `DURATION`, `BRAND`, `CATEGORY`（服务端支持年月/品牌/分类/包含标签/最小时长；最大时长与评分本地过滤；**不支持排除标签**） |
| `iwara` | FEED, SEARCH, TAG, MODEL, RANKING, DOWNLOAD, LOGIN | RELEVANCE, NEWEST, TRENDING, POPULARITY, RATING(点赞), VIEWS | 视频 / 标签 / 作者 / 图库 | `false` | 0 基 `?page=N-1&limit=20` | `TAGS`（服务端）, `DURATION`（本地过滤；不含图库） |
| `missav` | FEED, SEARCH, TAG, MODEL, CATEGORY, DOWNLOAD | NEWEST, VIEWS, POPULARITY（搜索/标签/分类/女优仅 NEWEST） | 关键词 / 类型(TAG) / 女优(MODEL) | `false` | `?page=N` | 无 |
| `jable` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST, POPULARITY（分类仅 NEWEST） | 关键词 | `false` | `/page/N/` 或 MacCMS 搜索段 `-----N---/` | 无 |
| `rouvideo` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST | 关键词 | `false` | `?page=N` | 无 |
| `kbjfree` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST, POPULARITY | 关键词 | `false` | `?page=N` | 无 |
| `pornhub` | FEED, SEARCH, TAG, MODEL, CATEGORY, DOWNLOAD | NEWEST, VIEWS, RATING | 关键词 / 标签 / 演员(MODEL) | `false` | `?page=N` / `&page=N` | 无 |
| `ehentai` | DOWNLOAD, FEED, SEARCH, TAG, MODEL, CATEGORY, RANKING, SESSION_IMPORT, ORIGINAL_IMAGES | NEWEST | 画廊 / 上传者(MODEL)（订阅支持标签） | `true`（`f_search` + `f_sfl=on`；热门页本地滤语言） | **`#unext` 游标 URL** | `TAGS`(含排除 `-`), `CATEGORY`(XOR掩码), `RATING`(`f_sr=on&f_srdd`), `PAGES`(`f_spf`/`f_spt`)，全站服务端 |
| `exhentai` | 同 `ehentai` | NEWEST | 同 `ehentai` | `true` | **`#unext` 游标 URL** | 同 `ehentai` |
| `nhentai` | DOWNLOAD, FEED, SEARCH, TAG, MODEL, CATEGORY, RANKING | NEWEST, POPULARITY | 漫画 / 画师(MODEL)（订阅支持标签） | `true`（单语走 `language:`，中日双语双流游标归并） | `?page=N`（双语用 4 整数游标） | `TAGS`(含排除 `-`), `CATEGORY`, `PAGES`(`pages:>=`/`<=`)，全站服务端 |
| `hitomi` | DOWNLOAD, FEED, SEARCH, TAG, MODEL, CATEGORY, RANKING | NEWEST, POPULARITY | 漫画 / 画师(MODEL)（订阅支持标签） | `true`（单语 `.nozomi`，中日双语双流 Range 游标） | Range 偏移 `(page-1)*48`（12 条/页）或内存切片 | 无独立表单（查询串内原生支持 `-` 排除词与 `field:val`） |
| `wnacg` | FEED, SEARCH, TAG, DOWNLOAD | NEWEST | 漫画 / 标签 | `false`（显式覆写） | 路径 `-page-N.html` / `&p=N` | 无 |
| `jmcomic` | FEED, SEARCH, DOWNLOAD | NEWEST（列表）, RELEVANCE（搜索） | 漫画 | `false`（显式覆写） | `?page=N` | 无 |
| `4khd` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST, POPULARITY | 写真集 | `false` | `?query-3-page=N`；搜索用 `/search/<q>/page/N` | 无 |
| `everia` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST | 写真集 | `false` | WordPress `/page/N/` | 无 |
| `4kup` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST | 写真集 | `false` | WordPress `/page/N/` | 无 |
| `galleryepic` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST | Coser（跳目录 `indexSection`） | `false` | 路径 `/en/cosplays/N`、`/en/coser/<id>/N` | 无 |
| `cosplaytele` | FEED, SEARCH, CATEGORY, DOWNLOAD | NEWEST | 写真集 | `false` | WordPress `/page/N/` | 无 |

---

## 3. 逐站协议与解析细则

### 3.1 视频站点（8 站）

#### 1. `r34v` — Rule34Video (`KvsAdapter.kt` + `HtmlParser.kt`)
- **站点引擎**：Kernel Video Sharing (KVS) SSR HTML。
- **路由规则**（`Models.kt:63-67`、`KvsAdapter.kt:36-42,62-68`）：
  - 列表：`/{segment}/[target/][page/][?sort_by={post_date|video_viewed|rating|duration|pseudo_rand}]`，`segment` 由 `FeedKind` 映射（`latest-updates`、`most-popular`、`top-rated`、`for-you`、`search`、`tags`、`categories`、`models`）。
  - 目录索引：`/categories/`、`/tags/`、`/models/`、`/top-characters/`（`works`）、`/top-model/?sort=az`（`artists`）、`/top-characters/<slug>/`（`work:<slug>`）。
- **列表解析**（`HtmlParser.kt:14-44`）：
  - 容器 `#custom_list_videos_latest_videos_list_items, .thumbs[id], .thumbs`，卡片 `div[data-video-card-id]` 回退 `div.item.thumb`；链接 `a.th, a[href*=/video/]` 提取 `/video/(\d+)/`。
  - 封面按序取 `data-rd-src` -> `src` -> `data-rd-jpg` -> `data-src`；预览图 `[data-preview]`；分页读 `button.page-of, [data-rd-jump], nav.fs-pager button` 中 `Page X of Y`，回退分页链接最大页号（刻意避开视频数字 ID）。
- **详情与片源**（`HtmlParser.kt:73-145`）：
  - 逐字符花括号配对解析 `<script>` 中 `flashvars = { ... }`（含 `\uXXXX`/`\xXX` 反转义），提取 `video_url`、`video_alt_url`、`video_alt_url2..4` 及其 `_text` 清晰度标签（按高度降序）；媒体地址经 `/get-file` 302 跳转至 CDN（由 `MediaRedirectCache` 缓存 30 分钟）。
  - 分组元数据解析 `.vp-group__title`（映射 `characters/categories/artists/tags`，`/characters/` 归一化为 `/tags/`），回退 `[data-suggest-type]`；下载直链取 `a[href*=download=true]`；评论取 `#tab_comments`（上限 100 条）。
- **认证**（`KvsAdapter.kt:70-92`）：GET `/login/` 抓 `input[name=remember_me_csrf_token]` -> POST 表单（`username`, `pass`, `action=login`, `remember_me=1`, `remember_me_csrf_token`）-> 校验无 `.generic-error/.alert-error` 且 Cookie 含 `PHPSESSID`。

#### 2. `hanime1` — Hanime1 (`HanimeAdapter.kt`)
- **站点引擎**：Laravel SSR + Cloudflare（禁止空 UA）。
- **路由规则**（`HanimeAdapter.kt:129-162`）：全站列表/分类/标签/搜索统一经 `/search?...` 查询参数驱动：
  - `query`（搜索/作者）、`tags[]`（主标签 + `includeTags` 多值追加）、`genre`（分类：`裏番`/`泡麵番`/`Motion Anime`/`3DCG`/`2.5D`/`2D動畫`/`AI生成`/`MMD`/`Cosplay`/`新番預告`）、`brands[]`、`year`（`"$year 年"`）、`month`（`"$month 月"`）、`duration`（`"$min 分鐘 +"` 或 `"0 - $max 分鐘"`）、`sort`（繁体中文值：`最新上市`/`最新上傳`/`本日排行`/`本週排行`/`本月排行`/`觀看次數`/`讚好比例`/`時長最長`/`他們在看`）、`page`。
- **列表与详情**（`HanimeAdapter.kt:173-261`）：
  - 卡片：`div.video-item-container`，**必须包含 `a.video-link[href*=watch]`** 以过滤同容器广告；视频 ID 正则 `[?&]v=(\d+)`；封面 `img.main-thumb`；分页 `ul.pagination a[href*=page]`。
  - 片源：优先 `video#player source[src]`（`size` 属性转 `${size}p`），回退脚本字面量 `const source = '...'`；标题 `#shareBtn-title`；作者 `#video-artist-name`；标签 `div.single-video-tag > a`；相关视频 `#related-tabcontent div.video-item-container`。
- **认证**（`HanimeAdapter.kt:97-108`）：GET `/login` 取 `input[name=_token]` -> POST `email`+`password`+`_token` -> **成功判据为再次 GET `/login` 返回 HTTP 404**。

#### 3. `iwara` — Iwara (`IwaraAdapter.kt`)
- **协议形态**：公开 JSON REST API（`https://apiq.iwara.tv`，请求头别名含 `api.iwara.tv`），**同站双模态（视频 + 图库）**。
- **路由与分页**（`IwaraAdapter.kt:154-182`）：
  - 1 基页码转 0 基 `page = request.page - 1`，固定 `limit=20`。
  - 列表：`/videos` 或 `/images`；搜索：`/search?type=videos&query=...`（或 `type=images`；**不变量：不能传 `type=video`，服务端会 500**）；关注动态：`/subscriptions/videos`（强依赖 JWT，禁止 HTTP 缓存并绑定 `IwaraSubscriptionRequest(generation)`）。
  - 作者解析：若 `target` 非 UUID（`[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}`），先 GET `/profile/<username>` 取 `user.id` UUID。
  - 排序：`sort={date|trending|popularity|views|likes}`（搜索且 `Sort.RELEVANCE` 时省略 `sort`）。
  - **分页不变量**：响应 `count` 在实测中随页码递增，必须每页按 `((count + limit - 1) / limit)` 动态重算总页数，不可缓存首屏 `count`。
- **视频详情与签名**（`IwaraAdapter.kt:76-90,356-439`）：详见第 4.6 节。并行拉取 `/video/<id>/related` 与 `/video/<id>/comments?limit=12&page=0`（各 1.2s 超时降级为空数组）；拒绝 `embedUrl` 站外嵌入与 `private=true` 无 `fileUrl` 视频。
- **图库解析**（`IwaraAdapter.kt:99-116,257-329`）：`/image/<id>` 返回 `files[]`，显式过滤掉视频附件（`type=="video"` / `mime.startsWith("video/")` / 视频后缀），按 `https://i.iwara.tv/image/{thumbnail|original}/{id}/{name}` 构造缩略图与原图；页址使用 `#page=N`，重载用 `?reload=1#page=N`；内存 `ConcurrentHashMap` 缓存 16 份图库 JSON。
- **认证**（`IwaraAdapter.kt:120-130,144-152`）：`POST https://apiq.iwara.tv/auth/login`（JSON `{"user":...,"password":...}`），走 `SingleUseBody` + 禁重试 + 禁重定向，取响应 `token` 后经 `SessionVault` 加密持久化并递增 `authEpoch`。

#### 4. `missav` — MissAV (`MissavAdapter.kt`)
- **路由与镜像**：主域 `missav.ws`，镜像 `missav.media`（CF 挑战/403/429/5xx 时 `SiteHtml.load` 自动回退）。
  - 列表：`/{new|release|uncensored-leak|chinese-subtitle}[?sort={views|monthly_views}][&page=N]`；搜索 `/search/<q>`；类型 `/genres/<slug>`；女优 `/actresses/<slug>`；目录 `/genres`、`/actresses`。
- **列表与规范化**：卡片 `div.thumbnail.group`，标题 `div.truncate a`，封面优先 `data-src`（`src` 为 1x1 `data:` 占位），预览 `video[data-src]`；作品路径正则 `^/([a-z0-9]+(?:-[a-z0-9]+)*-[a-z]*\d+)/?$`，规范化剥离语言前缀与查询串为 `https://<host>/<code>`。
- **片源解包**（`MissavAdapter.kt:217-297`）：循环扫描 `eval(function(p,a,c,k,e,` 块，纯 Kotlin 实现 2–62 进制 Packer 解码器（单遍 token 替换防止词典二次展开），提取 `https?://[^'"\s]+?/[0-9a-f-]{32,36}/playlist.m3u8` 作为单一 `"自动"` 档位（master HLS 内含 360/480/720/1080p）。

#### 5. `jable` — Jable (`JableAdapter.kt`)
- **路由与镜像**：`jable.tv` 长期 Cloudflare 托管挑战，主站使用 MacCMS 前端 `jable.vip`，`jable.tv` 保留于 `mirrors` 用于分享链接识别。
  - 列表：`/label/{new|hot}/[page/N/]`、分类 `/vodtype/{28|21|22|20|23|24}/[page/N/]`；搜索 `/vodsearch/<q>{-time|-hits}----------/`（分页 `/vodsearch/<q>{-time|-hits}-----{page}---/`）。
- **列表与片源**（`JableAdapter.kt:123-226`）：
  - 卡片 `div.video-img-box`，跳过 `/template/` 占位图；作品 ID `/vodplay/(\d+)-\d+-\d+/?$`。
  - 片源：在 HTML 中定位 `player_aaaa`，**按花括号配对扫描**提取对象文本（不依赖结尾分号），正则取 `"url":"..."` 并反转义 `\/` 为单条 `"自动"` HLS 流。
  - 标题：作品页无 `h1`，从 `<title>` 抽取 `《(.+?)》`，回退 `og:title`。

#### 6. `rouvideo` — rou.video (`RouVideoAdapter.kt` + `playback/RouPng.kt`)
- **站点引擎**：TanStack Start SSR。
- **路由**：首页 `/home`、全部 `/v`、分类页 `/cat`、题材筛选 `/v?tag=<t>`、系列 `/s/<id>`（`[A-Za-z0-9]{16,40}`）、搜索 `/search?q=<q>`，均用 `?page=N`/`&page=N` 分页。
- **列表**：`a[href^='/v/']`（ID 长度 16–40，按 ID 去重以剔除 hero 重复卡），固定预设 `thumbWidth=1280, thumbHeight=800`，解析中文时长（`X小時Y分Z秒` 或 `MM:SS`）。
- **片源加密与 PNG 拆壳**：详见第 4.2 节。

#### 7. `kbjfree` — KBJFree (`KbjFreeAdapter.kt`)
- **站点引擎**：Next.js App Router (RSC Flight)。
- **路由**：`/videos?sort={recent|popular}`、`/videos?filter=for-you`、`/search?q=<q>`，均以 `&page=N` 分页；详情 `/watch/<uuid>`（`[0-9a-f-]{16,40}`）。
- **RSC 解析**（`KbjFreeAdapter.kt:108-140`）：拼接所有 `self.__next_f.push([1,"(.*?)"])` 并反转义；由于 RSC 含 `$undefined` 非标准 JSON，采用正则锚定 `{"id":"<uuid>","name":"...","categoryId"` 后取 2600 字符窗口抽 `mainThumbUrl`/`coverUrl`/`previewMediaUrl`/`duration`。
- **详情与预览边界**（`KbjFreeAdapter.kt:154-220`）：仅解析 `script[type=application/ld+json]` 的 `contentUrl`（回退 `video[src]`），如实标注为 `"预览"`（静音预览 MP4，完整片源需站内积分解锁）；将 ISO 8601 时长 `PT1H31M0S` 格式化为 `H:MM:SS`。

#### 8. `pornhub` — Pornhub (`PornhubAdapter.kt`)
- **路由**：`/video?o={cm|mv|tr}`、`/video?c=<id>`、`/categories/<slug>`、`/pornstar/<slug>[?page=N]`、`/video/search?search=<q>[&t=tag]`；目录 `/categories`、`/pornstars`（上限 400）。
- **双 UA 列表解析**（`PornhubAdapter.kt:135-183`）：先解析桌面端 `li.pcVideoListItem`（`var.duration`、`span.views var`、`data-mediabook`），为空时回退移动端 `li[data-video-vkey]`（`.title a`、`div.duration span.time`、`div.views`、`data-webm`）；`viewkey` 为 8–24 位小写字母数字。
- **片源解析**（`PornhubAdapter.kt:198-217`）：在含 `mediaDefinitions` 的 `<script>` 内按同对象非嵌套花括号匹配 `"height":(\d+)[^{}]*?"format":"(hls|mp4)"[^{}]*?"videoUrl":"(https?:[^"]+)"`，HLS 标 `${height}p`、MP4 标 `"原画"`，按高度降序；兜底匹配 `.m3u8` URL。

---

### 3.2 漫画站点（6 站）

#### 9 & 10. `ehentai` / `exhentai` — E-Hentai & ExHentai (`EhAdapter.kt` + `EhLogin.kt` + `SiteAuthActivity.kt`)
- 共用 `EhAdapter`/`EhProtocol`，通过 `site.id` 区分访问门禁与白屏检测。
- **分类排除位掩码**（`EhAdapter.kt:102,133,146`）：`f_cats = 1023 xor catId`（10 类：2 同人志、4 漫画、8 画师 CG、16 游戏 CG、32 图集、64 Cosplay、128 真人、256 非成人、512 西方作品、1 其他）。
- **路由与游标分页**（`EhAdapter.kt:106-152`）：
  - 强制 `inline_set=dm_l`（列表紧凑布局）；语言过滤写 `f_search`（单语 `language:chinese$`，双语 `~language:chinese$ ~language:japanese$`）+ `f_sfl=on`；高级筛选写 `f_spf`/`f_spt`、`f_sr=on&f_srdd=<1..5>`、`advsearch=1`。
  - 热门页 `/popular` 服务端忽略语言参数，改为解析后在客户端按 `[title^=language:]` 过滤。
  - 翻页严格跟随 `#unext[href]`（回退 `.ptt a` `>`）游标 URL；校验 HTTPS + 同站 + 路径属于 `{/, /popular, /popular/}`；追加查询项时按词去重防止 `f_search` 逐页膨胀。
- **详情、按需预览分页与单页**（`EhAdapter.kt:60-79,208-260`）：
  - 详情 `/g/<gid>/<token>/`：解析 `#gn`/`#gj`、`#gdd tr`（`Length`/`Language`/`Posted`/`File Size`）、`#gd1 [style]` CSS `url(...)` 封面、`#taglist a`、`#gdn a`、`#gdt a`（第 0 组预览链接，记为 `previewPageSize`）。
  - 跨预览组跳页：`galleryPageLink(detail, page)` 计算 `p = (page - 1) / previewPageSize`，按需拉取 `/g/<gid>/<token>/?p=<p>` 并缓存到内存 `previewPages` Map。
  - 单页 `/s/<key>/<gid>-<page>`：读 `img#img`（遇 `509.gif`/`509s.gif` 抛配额异常）、前后页 `a#prev`/`a#next`、重载令牌 `nl('...')` -> `?nl=<token>`、原图入口 `a[href*=fullimg.php]` 及体积文案。
- **WebView 登录与 Cookie 导入**：详见第 4.4 节。

#### 11. `nhentai` — nhentai (`NhentaiAdapter.kt`)
- **协议形态**：公开 v2 REST API（`/api/v2/*`，弃用旧 v1 `/api/gallery`）。
- **端点**（`NhentaiAdapter.kt:43-49,128-155`）：
  - `/api/v2/cdn`（缓存 30 分钟）：返回 `image_servers` / `thumb_servers`，**严格校验 `https://[it]\d*.nhentai.net/`**。
  - `/api/v2/galleries?page=N`（最新）、`/api/v2/galleries/popular`（今日热门，返回无分页数组）。
  - `/api/v2/search?query=<q>&sort={date|popular|popular-today}&page=N`（支持 `artist:"..."`、`category:...`、`language:...`、`pages:>=N`/`<=N`、`-tag` 排除；无条件按热门排序时用 `query=pages:>0`）。
  - `/api/v2/galleries/<id>`（内存 LRU 缓存 32 份）。
- **双语并流与图片校验**（`NhentaiAdapter.kt:63-100,167-213`）：详见第 4.7 节。

#### 12. `hitomi` — Hitomi (`HitomiAdapter.kt`)
- **协议形态**：纯静态 CDN（`https://ltn.gold-usergeneratedcontent.net`）二进制 `.nozomi` 索引 + 磁盘 B-Tree + `galleries/<id>.js` JSON + `gg.js` 正则静态解析（零 JS 执行）。详见第 4.3 节。

#### 13. `wnacg` — WNACG (`WnacgAdapter.kt`)
- **特殊要求**：`SiteContext.userAgent` 固定为桌面 Windows Chrome UA（桌面与移动 DOM 差异大）。
- **路由**（`WnacgAdapter.kt:77-121`）：
  - 最新 `/albums-index-page-<N>.html`；标签 `/albums-index-page-<N>-tag-<tag>.html`；搜索 `/search/index.php?s=create_time_DESC&q=<q>&p=<N>`。
  - 详情规范化 `/photos-index-aid-<id>.html`；阅读器图页列表 `/photos-gallery-aid-<id>.html`；单页虚拟页址 `<canonical>#<page>`。
- **列表与图页解析**（`WnacgAdapter.kt:123-229`）：
  - 列表 `.gallary_item`，页数正则 `([0-9][0-9,]*)\s*[張张][圖图]片`，分页校验 `span.thispage + a[href]`。
  - 阅读器 HTML 提取 `imglist` 数组：先检查是否被包在 `document.writeln("...")`（若匹配先用 `JsonParser.parseString` 反转义），再用带独占量词正则抽 `url` 字段（兼容 `fast_img_host + "..."`），剥离末尾收藏提醒图 `/themes/weitu/images/bg/shoucang.jpg`。
  - **严格媒体域白名单**：封面限定 `t2/t3.wnimg1.ru, t4.qy0.ru`，正文图限定 `img2/img5.wnimg1.ru, img5.qy0.ru`（及主域），强制 HTTPS 443 + 合法图片后缀；详情 JSON 与阅读器页数必须一致。内存 LRU 缓存 16 本图集 URL 列表。

#### 14. `jmcomic` — JMComic (`JmcomicAdapter.kt` + `GalleryImageRendering.kt`)
- **路由**：最新 `/albums?o=mr[&page=N]`；搜索 `/search/photos?search_query=<q>[&page=N]`；漫画详情 `/album/<id>`；章节阅读 `/photo/<id>`；多章节列表通过虚拟 `FeedKind.CATEGORY` + URL fragment `#chapters` 打开（`IncomingLink.kt:18-20` 专门为其保留 `#chapters`）。
- **镜像探测与图片反混淆**：详见第 4.5 节。

---

### 3.3 写真站点（5 站）

#### 15. `4khd` — 4KHD (`FourKhdAdapter.kt`)
- **站点引擎**：WordPress + Jetpack CDN（`i0.wp.com/pic.4khd.com/...`）。
- **路由**（`FourKhdAdapter.kt:154-166`）：首页 `/`、热门 `/pages/popular`、分类 `/pages/{cosplay|album}`，均用 `?query-3-page=N` 分页；搜索走独立路径 `/search/<q>[/page/N]`（因搜索页带 `?query-3-page` 会退回第 1 页）。
- **内容分页与无状态页址**：帖子路径 `/content/<year>/<slug>.html[/<page>]`，**稳定 ID 是 `<slug>`**（`<year>` 为年份桶不可作键）。总分页数由 `ul.page-links li.numpages` 统计；每页图取 `div.entry-content img`（优先父 `a[href]`，过滤含 `pic.4khd.com` 或 `/4KHD-beautifulGirls` 的 HTTPS 链接）；所有分页展平为 `#<index>-<page>-<offset>` 虚拟页址（`PhotoPages`）；预设封面尺寸 `850x1158`。
- **CF 被动脚本陷阱**：4KHD 正常页含 `/cdn-cgi/challenge-platform/scripts/jsd/main.js`，因此 `SiteHtml.challenge()` 必须结合 `CONTENT_MARKERS` 才不会把整站误判成验证页。

#### 16. `everia` — Everia (`PhotoArchiveAdapter.kt` + `PhotoProfiles.everia()`)
- **路由与选择器**：标准 WordPress `/page/N/` + `?s=<q>`；帖子路径带日期 `^/(\d{4}/\d{2}/\d{2}/[^/]+?)(?:/\d+)?/?$`；支持 `/slug/N/` 多内容分页（`div.page-links span/a.post-page-numbers`）；卡片 `div.rt-holder`，正文 `figure img`。
- **关键协议点**：图片托管在按帖轮换的外部 CDN（`fujibox.top`/`karubox.top` 等），因此 `imageHosts = emptySet()` 且 `SiteContext.mediaDomains = emptySet()`，完全不锁图片域；同时该站双栈 AAAA 记录在某些网络不可达，促成了全局 `Ipv4FirstDns`。

#### 17. `4kup` — 4KUp (`PhotoArchiveAdapter.kt` + `PhotoProfiles.fourKup()`)
- **路由与选择器**：WordPress (GeneratePress)，单页无内容分页；卡片 `div.generate-columns-container article`；正文 `div#gallery, div.entry-content, article` 中选 `a.thumb-photo[href], img`，限定 `googleusercontent.com`（Blogger CDN）。
- **懒加载封面修复**：正文 `img[src]` 为 `data:` 占位符，真实图在 `<a href>`，因此 `WordPressPhotoProfile.cover()` 直接取 `images(doc).firstOrNull()`。

#### 18. `galleryepic` — GalleryEpic (`PhotoArchiveAdapter.kt` + `GalleryEpicProfile`)
- **站点引擎**：Next.js (Turbopack) Cosplay 图库。
- **路由与目录模式**（`PhotoArchiveAdapter.kt:405-461`）：
  - 列表 `/en/cosplays/<page>`；Coser 作品 `/en/coser/<id>/<page>`；角色作品 `/en/character/<id>`；作品页 `/en/cosplay/<id>`。
  - **目录型搜索**：搜索（`/en/cosers/<page>?coserName=<q>`）、Coser 索引（`/en/cosers/<page>`）、系列角色（`/en/parody/<id>`）均通过 `indexSection(request)` 路由到索引目录 UI（`indexLinks`），而非卡片流。
- **全图提取**（`PhotoArchiveAdapter.kt:536-544`）：HTML 仅渲染 12 张预览，完整图集 UUID 列表藏在 RSC payload 的 `\"images\":[...]`（或 `"images":[...]`）中，正则提取全部 UUID 拼为 `https://static.galleryepic.xyz/image/<uuid>`。元数据从 `dl dt` 抽取 `Cosplayer` / `Character` / `Series`。

#### 19. `cosplaytele` — Cosplaytele (`PhotoArchiveAdapter.kt` + `PhotoProfiles.cosplayTele()` + `CossoraProtocol.kt`)
- **图片图集**：WordPress (Flatsome) 单页内联；卡片 `div.col.post-item`；正文优先锁定 `div#gallery-1`（防止把文章末尾推荐区图片混入本集），限定 `cosplaytele.com/wp-content/uploads`。
- **内嵌视频（Cossora）**：文章若含 `iframe[src^="https://cossora.stream/embed/<uuid>"]`，`PhotoArchiveAdapter.gallery()` 在 `GalleryDetail.videoEntries` 中暴露 `Video(id="video-<uuid>", url="<article>#video-<uuid>")`，点播时走 `PhotoArchiveAdapter.detail()` -> `CossoraProtocol` 解密 HLS（见第 4.8 节）。

---

## 4. 高复杂度专项协议规范（Raven 必保不变量）

### 4.1 视频流、HLS 下载与分级缓存（`playback/PlaybackStore.kt`、`HlsDownloads.kt`、`HlsSeekSession.kt`、`StreamSelection.kt`、`MediaProbe.kt`）
- **流协议支持**：当前覆盖 **Progressive MP4**（R34V/Hanime1/Iwara/KBJFree/Pornhub fallback）与 **HLS (`.m3u8`)**（MissAV/Jable/rou.video/Pornhub/Cosplaytele-Cossora），**无 DASH 站点**。
- **HLS MIME 判定**（`HlsDownloads.kt:22-26`）：除 `.m3u8` 后缀外，**必须匹配 `^https?://[^/]+/api/hls/`**（否则 `rou.video` 无扩展名清单会被 Media3 当渐进式流解析失败）。
- **缓存键隔离与持久化**：
  - 视频任务 ID：`"$siteId:$videoId:$quality"`（防跨站同 ID 碰撞）。
  - HLS 下载：`HlsDownloads.keys(id)` 为每个下载任务加上 `"$id|hls|" + spec.uri` 命名空间前缀，下载完成后写入 1 字节锚点 + `ContentMetadata("r34v_hls_download")` JSON 快照（含 manifest URI 与全部 `(key, position, length)`），使 `SimpleCache` 在重装/存储迁移后仍能校验完整性并离线恢复。
  - **四级缓存分离**：
    1. `downloadCache`（持久下载，无上限）
    2. `streamCache`（512 MiB LRU，仅用户显式开启边下边存时才写，避免低带宽链路同步写缓存加剧卡顿）
    3. `seekCache`（64 MiB LRU，单会话渐进式视频回退 seek 缓存，按 `sessionKey` 忽略令牌刷新，切视频即清）
    4. `hlsSeekCache`（32 MiB LRU，`HlsSeekSession` 用 UUID 前缀隔离单次播放，**仅在解析到 `#EXT-X-ENDLIST` (`hasEndTag == true`) 的 VOD 列表时启用写入**，直播/滑动窗口列表禁写；清单始终走独立 live HTTP 通路）。
- **带宽自适应与降档**（`StreamSelection.kt` + `MediaProbe.kt`）：起播前对非 HLS 流可做 Range 探测（`MediaProbe`：基准 1 MiB / 预算模式 128 KiB 2s 超时），带宽预算按实测 `70%`（`HEADROOM=0.7`）对比参考码率表（144p=200k … 720p=2500k, 1080p=4500k, 2160p=18000k）选档与动态设置 Media3 `BufferPlan`；卡顿策略（`StallPolicy`：起播前 5s 内缓冲 >=6s / 播放中单次 >=8s / 30s 内 >=3 次）自动降一档。
- **失败自动刷新流地址**（`PlaybackStore.kt:145-160,307-324`）：下载失败时重新调用 `adapter.detail(video.url)` 匹配原 `quality` 刷新过期签名令牌（限单次防循环）。

### 4.2 `rou.video`：`ev` 载荷解密 + PNG/`roUd` 分块拆壳（`RouVideoAdapter.kt` + `playback/RouPng.kt`）
1. **详情页 `ev` 提取**（`RouVideoAdapter.kt:174-218`）：
   - 匹配 `ev:\s*\$R\[\d+\]\s*=\s*\{d:"([^"]+)",k:(\d+)\}`（回退 `\{d:"([A-Za-z0-9+/=]{40,})",k:(\d+)\}`）。
   - 标准 Base64 解码得到字节数组，逐字节计算 `((byte.toInt() and 0xFF) - k).toChar()`，得 JSON `{"videoUrl":"/api/hls/<id>","thumbVTTUrl":"..."}`，解析为绝对地址。
2. **PNG `roUd` 拆壳协议**（`playback/RouPng.kt:20-172`）：
   - `/api/hls/<id>` 302 到 CDN 后，返回的 `.m3u8` 清单及 `.ts` 分片均为合法 PNG（8 字节魔数 `89 50 4E 47 0D 0A 1A 0A`）。
   - 遍历 PNG Chunk（4B big-endian length + 4B type + `length`B data + 4B CRC），定位自定义块类型 `roUd`（`0x726f5564`）：
     - `data[0]` 为标志位：`flag & 1 != 0` 表示 `data[1..]` 为 **zlib 封装压缩数据**（必须用 `Inflater(nowrap = false)`，**非 raw deflate**），解压上限 64 MiB；`flag & 1 == 0` 为明文负载。
   - **DataSource 包装不变量**（`RouPng.UnwrappingDataSource`）：
     - 发起上游请求时强制 `position=0, length=-1`（Range 偏移针对解码后字节，绝不可直接向压缩 PNG 发 Range）。
     - 先仅读 8 字节检测 PNG 魔数：非 PNG 响应直接流式透传（不缓冲整文件，避免普通图/MP4 爆内存）；确认为 PNG 且含 `roUd` 后才整包缓冲拆壳。
     - 按解包后内容重写响应头：`#EXTM3U` 开头设 `application/vnd.apple.mpegurl`，首字节 `0x47` 且长度 >= 189 设 `video/mp2t`，同步更新 `Content-Length` 并在内存上应用 `dataSpec.position` 与 `dataSpec.length`。

### 4.3 `Hitomi`：`.nozomi` 二进制索引、B-Tree 关键词搜索与 `gg.js` 路由（`HitomiAdapter.kt`）
1. **`.nozomi` 索引与 Range 分页**（`HitomiAdapter.kt:37-62,87-139,228-242,271-284`）：
   - 索引全为大端 32-bit 无符号/正整数数组（每条 4 字节）：
     - 全站：`index-all.nozomi` / `popular/year-all.nozomi`
     - 语言：`index-<lang>.nozomi` / `popular/year-<lang>.nozomi`
     - 单维度（`artist|group|series|character|tag|type|female|male`）：`<dir>/[popular/year/]<val>-all.nozomi`（`female`/`male` 归入 `tag/` 且文件名为 `female:<val>-all.nozomi`）。
   - 列表翻页采用 HTTP `Range: bytes=<offset>-<offset+47>`（每次只拉 48B = 12 个 ID），**严格校验 HTTP 206 及 `Content-Range: bytes start-end/total`**，由 `total / 4` 计算总条数；404/416 视为越界空集，403/429 明确抛错不伪装为空。
   - 中日双语（`CHINESE_JAPANESE`）采用双路 48B Range 归并（最新按 ID 降序并去重，热门交替取样），游标格式 `"offset0,offset1"`。
2. **SHA-256 磁盘 B-Tree 关键词搜索**（`HitomiAdapter.kt:140-185,243-257`）：
   - 拉取 `/galleriesindex/version` 得版本号 `<ver>`。
   - 关键词（1–6 词，至少 1 正向词，下划线转空格）求 `SHA-256(term).copyOf(4)`。
   - 从偏移 `0` 开始对 `galleries.<ver>.index` 发起 464 字节 Range 读取解析 B-Tree 节点：`[keyCount: Int][keys: (len: Int, bytes)*][dataCount: Int][data: (offset: Long, len: Int)*][children: 17 * Long]`（上限 16 层 + `seen` 环检测）。
   - 命中后按 `(offset, len)` Range 读取 `galleries.<ver>.data`（首 4B 为 count，其后为 `count * 4` 字节 ID 列表；单次上限 16 MiB / 50 万 ID）。
   - 多词先正向取交集再减排除集，热门排序流式扫描 `year-all.nozomi` 保序过滤；<=50,000 条结果缓存 3 分钟（LRU 8 组）。
3. **`galleries/<id>.js` 与 `gg.js` 动态 WebP 路由**（`HitomiAdapter.kt:63-71,205-227,296-327`）：
   - 列表页通过 `Semaphore(3)` 并发拉取 `galleries/<id>.js`，剥离 `var galleryinfo = ` 前缀解析 JSON（检查 `blocked == true`；404 跳过已删画廊；LRU 缓存 32 份）。
   - `gg.js` 缓存 10 分钟（`?reload=1` 强刷），**纯正则提取**：`b: '<digits>/'`、`var o = 0|1;` 及所有 `case <0..4095>: ... o = 0|1; break;`。
   - 64 位 hex `hash` 计算 12-bit 桶号：`bucket = (hash.takeLast(1) + hash.takeLast(3).take(2)).toInt(16)`；`server = overrides[bucket] ?: default`：
     - 正文 WebP：`https://w${1 + server}.gold-usergeneratedcontent.net/$prefix$bucket/$hash.webp`
     - 缩略图：`https://${'a' + server}tn.gold-usergeneratedcontent.net/webpbigtn/${hash.takeLast(1)}/${hash.takeLast(3).take(2)}/$hash.webp`
   - 页址采用 `/reader/<id>.html#<page>`，发图片请求时 `GalleryRepository` 会对 Referer 剥离 `#<page>`。

### 4.4 `E-Hentai / ExHentai`：官方 WebView 认证状态机、安全边界与图页恢复（`SiteAuthActivity.kt`、`EhAdapter.kt`、`EhLogin.kt`、`GalleryRepository.kt`）
1. **7 状态官方登录 FSM**（`SiteAuthActivity.kt:35-93,159-385`）：
   - 状态流转：`RESETTING`（清空 WebView Cookie）→ `FORUM`（加载 `https://forums.e-hentai.org/index.php?act=Login`）→ `COPYING_IDENTITY`（将 `ipb_member_id` + `ipb_pass_hash` 同步写入目标站 `e-hentai.org` / `exhentai.org`）→ `LOADING_TARGET`（打开目标站首页以签发 `igneous` 并校验权限）→ `TARGET_READY` / `TARGET_FAILED` → `VERIFYING`（交原生 `EhAdapter.importSession` 验证）。
   - **强安全不变量**：
     - 窗口加 `FLAG_SECURE` 防截屏；无 JS Bridge；禁用文件/内容访问与混合内容；禁用第三方 Cookie；SSL 错误一律 `handler.cancel()`。
     - 顶层导航白名单 `EhLogin.allowed()` 仅放行 HTTPS 的 `forums.e-hentai.org` / `(www.)e-hentai.org` / `(www.)exhentai.org`。
     - 退出 `SiteAuthActivity` 时彻底清除 WebView 临时 Cookie。
2. **原子化会话导入与加密落盘**（`EhAdapter.kt:81-98`、`Network.kt:112-128`、`SessionVault.kt`）：
   - 校验 `ipb_member_id` 为正整数、`ipb_pass_hash` 匹配 `[A-Za-z0-9_-]{16,128}`、可选 `igneous`。
   - 先用临时隔离 `CookieJar`（附带 `nw=1`）请求目标站首页并成功解析画廊列表，**通过后才覆盖** `SessionCookies` 并经 `AndroidKeyStore` AES-256-GCM（`SessionVault`，12B IV + 128-bit tag，**无明文回退**）写入 `${site.id}:encrypted_cookies`。
   - ExHentai 强门禁：无会话直接抛 `GalleryLoginRequiredException`；遇 403、重定向循环（`ProtocolException("...follow-up...")`）或白屏/非 HTML 响应立即触发登录门禁，**不回退展示已缓存私有详情**。
3. **单页自愈与原图模式**（`GalleryRepository.kt:274-291,374-377,493-497`）：
   - 下载/读取图页时：仅对 EH/EX 检测响应 URL 异常跳转（抛 `GalleryRedirectException`）及 TLS 连接中断（`DownloadRecovery.isTlsConnectionInterruption`），连同 HTTP 403 自动用 `image.reloadUrl`（`?nl=<token>`）重抓图页再重试一次。
   - HTTP 509 与 `509.gif`/`509s.gif` 映射为配额耗尽错误。
   - 开启站点级“原图优先”设置（F34）且 `image.originalUrl`（`fullimg.php`）非空时，切取原图字节；元数据仍保留显示图与 `originalUrl` 字段。

### 4.5 `JMComic`：路由感知镜像探测与 Canvas 分条反混淆（`JmcomicAdapter.kt` + `GalleryImageRendering.kt`）
1. **路由感知镜像路由器 `JmcomicMirrorRouter`**（`JmcomicAdapter.kt:39-47,103-188`）：
   - 候选镜像按序：`jmcomic-zzz.one`、`18comic.ink`、`jmcomic-zzz.org`、`18comic.vip`。
   - 租约时长 10 分钟，绑定 `routeKey = network.systemRouteKey(mirrorUrls)`（由 `activeNetwork.networkHandle` + `routeEpoch` + 每主机 `SystemProxySelector.describe` 组成；切网或改系统代理立即失效重探）。
   - 探测请求：`CookieJar.NO_COOKIES`、禁缓存、禁自动重定向、禁连接失败重试、超短超时（connect 3s / read 4s / call 5s / coroutine 5.5s）、最多 3 跳严格校验同站且同身份的 `redirectAllowed`、单页正文上限 4 MiB、检出 `cf-mitigated: challenge` 立即切下一个镜像。
2. **Canvas 分条反混淆算法（精确不变量）**（`JmcomicAdapter.kt:328-348`、`GalleryImageRendering.kt:12-53`）：
   - 判定条件：`img[id^=album_photo]` 父节点含 `.scramble-page` 类、脚本变量 `speed != "1"`、且非 `.gif` 文件；同时校验脚本 `aid == chapterId` 且容器 `id == filename`。
   - `stripCount(id: Long, scrambleId: Long, filenameStem: String): Int`：
     - `id < scrambleId` -> `0`（不还原）
     - `id < 268850` -> `10`
     - 否则：计算 `MD5("$id$filenameStem")`，取末字节低 4 位对应的小写十六进制字符 `lastHex`（`'0'..'9'`, `'a'..'f'`），**对其 ASCII 码 `lastHex.code` 取模**：
       - `id < 421926`：`((lastHex.code % 10) * 2) + 2`（值域 `{2,4,...,20}`）
       - `id >= 421926`：`((lastHex.code % 8) * 2) + 2`（值域 `{2,4,...,16}`）
   - 重组几何（`galleryStripBounds(height, strips)`）：`stripHeight = height / strips`、`remainder = height % strips`；第 `index` 条（`0..strips-1`，自顶向下画到目标图）取源图底部向上第 `index` 块：`sourceTop = height - (index + 1) * stripHeight - remainder`，**余数 `remainder` 归并到 `index == 0`（即源图最底部、目标图最顶部那条）**。
   - 解码约束：`strips in 2..minOf(height, 20)`，总像素 `width * height <= 12_000_000`，通过 `BitmapRegionDecoder` 逐条绘制到 `ARGB_8888` 位图并重编码为 JPEG(quality=96) 或 PNG（<=24 MiB）；缩略图预览阶段支持将原始未还原字节暂存为 `.strips` 文件，直接以 `decodeStripThumbnail` 低内存拼出缩略图。

### 4.6 `Iwara`：API 签名、动态盐值热提取与会话纪元（`IwaraAdapter.kt` + `Network.kt`）
1. **片源签名 `IwaraSignature`**（`IwaraAdapter.kt:395-439`）：
   - `fileUrl` 强校验为 HTTPS 且 host 属于 `{"files.iwara.tv", "filesq.iwara.tv"}`、首路径段为 `"file"`、含整数查询参数 `expires`。
   - `X-Version = SHA-1("${fileId}_${expires}_${salt}")`（40 位小写 hex）。
   - 默认盐值：`DEFAULT_SALT = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"`。
   - **动态自愈机制**：若首次请求 `fileUrl` 成功但返回数组不含 `name == "Source"`，加锁（10 分钟冷却、5 秒超时）抓取 `https://www.iwara.tv` -> 正则 `src=["'](/main\.[a-fA-F0-9]+\.js)["']` -> 正则 `"_"\+[\w$.]+expires\+"_([A-Za-z0-9]{16,})"` 提取新盐值；仅当用新盐重试并成功拿到 `"Source"` 才更新内存 `salt`，否则降级回首次可播结果。
   - 清晰度处理：剔除 `preview` 与非 HTTPS 源，`Source` 借用 `metadata.file.height` 显示为 `${height}p 原画`；同时分别生成 `view`（播放）与 `download`（下载）两组流。
2. **专用请求头与会话纪元**（`Network.kt:305-313,416-422,469-540`）：
   - 所有归属 Iwara 的请求自动附带 `Origin: https://www.iwara.tv`、`x-site: www.iwara.tv`。
   - `Authorization: Bearer <jwt>` **仅发往 `apiHosts`（`apiq.iwara.tv`/`api.iwara.tv`），绝不发往 `i.iwara.tv` / `files*.iwara.tv` 媒体域**。
   - 登录/登出递增 `authEpoch`（`MutableStateFlow`），通过 `withIwaraSession` 立即取消在途 `/subscriptions/videos` 协程并在拦截器层拒发过期纪元请求。

### 4.7 `nhentai` v2 API：动态 CDN 与双语并流游标（`NhentaiAdapter.kt`）
- `/api/v2/cdn` 下发 `image_servers` 与 `thumb_servers`（30 分钟内存缓存），多图节点按 `(page - 1) % cdn.images.size` 轮询分摊。
- 修正 API 偶发重复后缀：`(\.(?:webp|jpg|png|gif))\1+$` -> `$1`；严格校验路径符合 `galleries/<media_id>/<file>.<ext>` 且 `pages[page-1].number == page`。
- **中日双语模拟**（`NhentaiAdapter.kt:63-100`）：因 v2 搜索无官方 OR 语法，并发/交替维护两路互斥查询——流 0（`language:chinese`）与流 1（`language:japanese -language:chinese`），4 整数游标 `"page0,idx0,page1,idx1"` 每页归并 24 条（最新按 `id` 降序、热门交替取样），单页结果内存缓存 3 分钟（LRU 12 项）。

### 4.8 其他特色协议（Cossora AES、PhotoPages 无状态寻址）
- **Cosplaytele 内嵌 `CossoraProtocol`**（`CossoraProtocol.kt:13-69`）：
  - 提取 `iframe[src^="https://cossora.stream/embed/<uuid>"]`。
  - 拉取嵌入页 HTML，正则提取 `const videoURL = '...'`、可选 `const portadaURL = '...'`、`decryptLink(videoURL, '<32-hex-chars>')`、`defaultQuality: '<N>p'`。
  - 解密算法：Base64 解码 `payload`（>=32B 且 16B 对齐），前 16 字节为 IV，其余为密文；密钥为 32 字符 hex 串的 **UTF-8 原始字节（32B = AES-256）**，执行 `AES/CBC/PKCS5Padding`；校验解密 URL 属于 HTTPS `*.cossora.stream` 且路径以 `.m3u8` 结尾。
- **写真站无状态页址 `PhotoPages`**（`PhotoArchiveAdapter.kt:131-178`）：
  - 多分页帖子统一展平为 `<contentPageUrl>#<flatIndex>-<contentPage>-<offset>`（`flatIndex` 1 基，`contentPage` 1 基，`offset` 0 基）。
  - 进程重启或离线恢复无需记录“每内容分页多少张”；若原站分页长度变动导致 `offset` 越界，自动向后顺延查找最多 4 个内容分页。

---

## 5. 网络层、会话隔离、代理、图片加载与标签词典审计

### 5.1 OkHttp 客户端谱系与传输安全
- **基线 `network.client`**（`Network.kt:391-426`）：
  - 超时：`connect=5s`、`read=12s`、`call=25s`；磁盘 HTTP 缓存 `cacheDir/http`（32 MiB）；`cookieJar(session)`；`dns(Ipv4FirstDns)`；`proxySelector(systemRoutes)`；`EventListener` 记录最近 `lastPeer` 供网络诊断。
  - 全局拦截器链路：`HostGate` 起点节流 -> `SiteContext` 解析（优先 `request.tag(SiteContext)`，回退 `SiteRegistry.forMediaHost(host)`）-> 强制注入 `User-Agent`（缺省 `DEFAULT_UA`）与 `Referer`（未显式设置时用 `site.referer`）-> Iwara 专用头/Bearer 注入 -> `applyBrowserShape`。
- **派生客户端**：
  - `network.mediaClient`（`Network.kt:344-352`）：`callTimeout=0`、`readTimeout=30s`、`cache=null`，挂载 `MediaRedirectCache`（应用层 + 网络层双拦截器）与 `applyMediaShape`（`Accept: */*`, `Sec-Fetch-Dest: video`, `Sec-Fetch-Mode: no-cors`, 移除 `Upgrade-Insecure-Requests`）。
  - `network.imageClient`（`Network.kt:361-363`）：`callTimeout=0`、`readTimeout=30s`、`cache=null`，保留 Cookie/浏览器形态/节流，防止慢链路下载 <=24 MiB 大图被 25s 总超时截断。
  - **临时派生（技术债）**：`JmcomicAdapter.document()`（每请求 `.newBuilder().build()`）、`EhAdapter.importSession()`（替换临时 CookieJar）、`Network.postJson()`（Iwara 登录禁用重试与重定向）。
- **DNS、代理与 TLS 安全**：
  - `Ipv4FirstDns`（`data/Ipv4FirstDns.kt`）：对 `Dns.SYSTEM.lookup()` 结果稳定排序使 `Inet4Address` 优先，解决双栈网络 AAAA 不可达耗尽 5s 连接超时；纯 IPv6 环境无损回退。
  - `SystemProxySelector`（`data/SystemProxySelector.kt`）：仅取系统/PAC 返回的首个 `Proxy`（**拒绝自动回退 `DIRECT`**，避免代理故障静默裸连泄漏）；`R34Application.onCreate()` 监听 `ConnectivityManager.registerDefaultNetworkCallback` 与 `Proxy.PROXY_CHANGE_ACTION` 广播，触发 `network.invalidate()` 清空连接池并递增 `routeGeneration`。
  - `res/xml/network_security_config.xml`：全局 `cleartextTrafficPermitted="false"`，信任 `system` + 随包内置的 Let's Encrypt `res/raw/isrg_root_x1.pem`（兼容 Android 7.0 API 24 出厂根库缺失），**不信任用户安装证书**。

### 5.2 反限流、镜像故障转移与浏览器请求形态
- **`HostGate`**（`Network.kt:152-172,402-410`）：仅针对 `SiteRegistry.forRequestHost(host) != null`（站点主域及 `apiHosts`），媒体 CDN 与更新服不限速。按 URL 路径后缀区分快慢两档：图片（`jpg/jpeg/png/gif/webp/avif/webm`）起点间隔 `150ms`，页面/API `400ms`（≈2.5 req/s）。
- **`MediaRedirectCache`**（`Network.kt:183-238`）：针对已注册站点主域 URL（如 KVS `/get-file`），在**网络层拦截器**捕获 302 后的最终跨主机 CDN 地址，缓存 30 分钟（超 256 条时清过期项）；命中后直连 CDN，若 CDN 返回 `403/404/410` 立即作废并回主域重解析，同时写入 `RateLimitLog`（保留最近 12 条事件）。
- **`SiteHtml.load` 与 429 退避**（`data/SiteHtml.kt:9-93`）：
  - 同址遇 HTTP 429 时解析 `Retry-After`（整数秒或 RFC 1123 日期，**代码实际封顶 `120s`**，`SiteHtml.kt:50`；缺省退避 `1000ms`），退避后重试同一地址一次；注意 `Network.kt:278` 注释写着“封顶 15 秒”，**注释与实现存在口径漂移**。
  - 仍失败或遇 `403/5xx/IOException`/CF 挑战页时，依次切换 `site.mirrors` 重试；404 与 `CancellationException` 立即终止。
  - API GET 路径（`Network.text()`/`json()`）经 `withRateLimitRetry` 复用同一 `SiteHtml.retryWaitMs` 逻辑；POST 请求不自动重试。
- **`applyBrowserShape`**（`Network.kt:246-274`）：对已注册站点/媒体域，在不覆盖显式头的前提下按三类目的地补齐 `Accept`、`Sec-Fetch-Dest/Mode/Site`、`Upgrade-Insecure-Requests` 与 `Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`。
- **离线降级快照 `FeedSnapshots`**（`Network.kt:604-638`）：以 `SHA-256(["feed-v2", site.id, resolve(), request])` 落盘 `cacheDir/feeds/`（LRU 保留 40 份），网络 `IOException`/`GalleryResponseException` 时自动回退 `cached=true`；刻意跳过 `FOR_YOU`、`SUBSCRIPTIONS` 与 `exhentai`。

### 5.3 会话与 Cookie 隔离机制（`Network.kt:36-141`、`SessionVault.kt`、`LibraryStore.kt:233-239,330-364`）
- **分桶与防泄漏原则**：`SessionCookies` 按 `site.host` 分桶；`saveFromResponse`/`loadForRequest` 均先验 `SiteRegistry.isRegistered(url.host)` 及 `site.isSite(cookie.domain)`——**媒体 CDN、API 别名、更新服务器一律不读写 Cookie**。
- **Host-only 编码**：因 `OkHttp Cookie.toString()` 省略 host-only 的 `Domain`，存储时用 `h\u0001<host>\u0001<raw>` 与 `d\u0001<raw>` 显式区分，防止域范围扩张。
- **单站清除**：`session.clear(site)` 仅删除该站桶、Prefs 键、用户名及（若为 Iwara）JWT，触发 `onCleared` 递增会话纪元，绝不影响其他站点。
- **安全落差（需在 Raven 补齐）**：画廊站 Cookie（`ehentai`/`exhentai` 等）与 Iwara JWT 均走 `SessionVault`（AndroidKeyStore AES-GCM）密文存储；而视频站（`r34v`/`hanime1`）Cookie 仍以明文 `StringSet` 存于 SharedPreferences `${site.id}:cookies`（`Network.kt:119`）。

### 5.4 图片加载隔离：Coil 3 封面通道 vs `GalleryRepository` 正文通道
1. **封面通道（Coil 3）**（`R34Application.kt:40-51`、`ui/ArtworkStyle.kt:92-129`）：
   - `R34Application.coverLoader(site)` 按 `site.id` 缓存独立的 Coil 3 `ImageLoader`，底层**共享** `coverMemory`（24 MiB）与 `coverDisk`（64 MiB，`cacheDir/coil-covers`）。
   - 网络层通过 `OkHttpNetworkFetcherFactory` 每次调用实时解析 `network.client`（确保系统代理切换立即生效）并给请求打上 `.tag(SiteContext::class.java, site)`，从而精确绑定 UA/Referer（尤其解决 Everia 无固定媒体域与 EH/EX 共享 `ehgt.org` 的归属问题）。
   - `ArtworkStyle.coverRequest()` 强制用 `"${site.id}|$url"` 同时作为 `memoryCacheKey` 与 `diskCacheKey`，防止跨站共享缓存键污染；Release 包仅允许 `https://` 与 `local` 已授权 URI（Debug 放行 `http://` 供本地测试夹具）。
2. **图集正文通道（`GalleryRepository`）**（`data/GalleryRepository.kt:403-592,678-892`）：
   - **完全绕过 Coil**，直连 `network.imageClient`，按站点隔离落盘于 `files/gallery-cache/<siteId>/`（每站 256 MiB LRU）及持久下载目录 `R34V/comics/<siteId>/<sha256(entryId)>/`。
   - **严格流式与二进制完整性校验**：单页读取硬上限 `24 MiB`（`MAX_GALLERY_IMAGE_BYTES`）；通过 `ImageStructure` 校验 JPEG（扫描后 EOI `FF D9`）、PNG（`IDAT`+`IEND` CRC）、GIF（`0x3B` 终止符）、WebP（RIFF chunk 闭合）、BMP、AVIF/HEIC（ISO-BMFF `ftyp`+`mdat/meta`）防止半截损坏图被写入缓存或标记下载完成；阅读解码自动降采样至 `<= 6_000_000` 像素防 OOM。

### 5.5 标签中文词典（`data/TagNames.kt` + `assets/tag-names.json`）
- 词典规模：共 **165 条**英文连字符 slug -> 简体中文映射（主要面向 R34V/Booru 体系常见标签，如 `big-breasts` -> `巨乳`、`futanari` -> `扶她`、`tentacles` -> `触手`）。
- 加载与匹配：`TagNames` 首次调用时同步用 Gson 解析至内存单例 Map；`match()` 剥离 `-` 排除前缀与 `namespace:`（如 `female:`），按“前缀匹配优先 -> slug 更短优先 -> 字典序”返回最多 8 条建议。
- 局限：仅 165 词，缺乏 EH/Hitomi/nhentai 庞大的命名空间标签翻译库（如 EhTagTranslation 离线包支持），且无繁体/别名归一化。

---

## 6. 现行架构痛点与技术债清单（精确证据）

| 类别 | 具体问题 | 源码位置证据 | 影响 |
|---|---|---|---|
| **1. 跨层站点硬编码（最严重）** | 新增/修改单个站点需改动 5–10 个非适配器核心文件，业务逻辑大量按 `site.id` 分叉 | - `Network.kt:48,112,135,416,471,489,497,505,536,560,572-595,606`（12 处：R34V 迁移、EH `nw`、Iwara 全套鉴权与纪元、EX 快照绕过、巨型 `AdapterRegistry` `when`）<br>- `GalleryRepository.kt:280,285,494`（EH/EX 专属重定向/TLS 重载）<br>- `PlaybackStore.kt:191`（`ROUVIDEO` 专属 `RouPng`）<br>- `IncomingLink.kt:13-20`（Iwara `apiHosts`、JMComic `#chapters`）<br>- `SearchSupport.kt:5-24` & `NextFeatures.kt:55`（筛选字段与排除语法）<br>- `ShelfOrganization.kt:9` & `PersonalLibrary.kt:9`（Cosplaytele 视频正则、Iwara `entryKey`）<br>- `SiteFunctionPage.kt:47-86` & `ComposeSiteFunctions.kt:92-109`（19 站主推栏目、额外目录、搜索范围、说明文案）<br>- `MainActivity.kt:352,373,417,563,680,730`、`GalleryActivity.kt:50,51,136`、`SiteAuthActivity.kt:112`、`ComposeBrowse.kt:287`、`ComposeCrossSearch.kt:72,223`、`SavedSearchUpdates.kt:89`、`FeedState.kt:100` | 违反开闭原则，高回归风险，站点无法独立演进或模块化 |
| **2. 进程级可变全局状态** | `SiteRegistry.current`（`@Volatile var`）与 `LibraryStore.siteId` 全局可变；老 `Site` 门面和 `Repository.index()` 仍隐式读全局当前站 | `SiteContext.kt:258`、`Models.kt:12-20`、`LibraryStore.kt:48,150-155`、`Network.kt:642,652,656` | 后台订阅/下载/跨站搜索/登录弹窗极易与前台切站产生竞态（`Network.kt:553` 注释本身就是修过竞态后的补丁） |
| **3. 阻塞式节流与客户端滥建** | `HostGate` 在 OkHttp 拦截器中直接 `Thread.sleep(wait)`；`JmcomicAdapter.document()` 每次 HTTP 调用新建 `OkHttpClient`；`NhentaiAdapter`/`HitomiAdapter` 绕过 `Network.text()` 自行封装响应解析 | `Network.kt:409`、`JmcomicAdapter.kt:105-109`、`NhentaiAdapter.kt:34-42`、`HitomiAdapter.kt:41-61` | 高并发下 `Thread.sleep` 阻塞 OkHttp Dispatcher 线程波及其他站点，且无法协作响应协程取消；重复创建 client 浪费对象与配置开销 |
| **4. 庞大单体接口（违反 ISP）** | `SiteAdapter` 把视频 `detail()`、漫画 `gallery()`/`galleryImage()`、登录 `login()`、会话 `importSession()` 全塞进同一接口；同时 `SiteContext.mediaKind` 单值无法表达双模态站点 | `SiteAdapter.kt:13-68`、`EhAdapter.kt:56,58`、`KvsAdapter.kt:50`（未覆写默认抛异常）、`IwaraAdapter.kt`、`PhotoArchiveAdapter.kt:87-102` | 大量 `error("不支持...")` 桩代码；调用方无法靠编译期类型区分站点能力，被迫运行时查 `site.id` |
| **5. 跨适配器耦合与重复脚手架** | 4 个视频站直接依赖 `MissavProtocol.encode()`/`clean()`；6 个适配器各自手写 LRU `LinkedHashMap`；`FourKhdAdapter` 与 `PhotoArchiveAdapter` 大量重复；`JableAdapter.kt:53` 存在冗余条件 `if (kind == SEARCH) SEARCH else SEARCH` | `JableAdapter.kt:53,193`、`RouVideoAdapter.kt:71,111-114,136,222`、`KbjFreeAdapter.kt:87,90`、`PornhubAdapter.kt:82,105-111,145`、`FourKhdAdapter.kt:65-120` | 修改 `MissavAdapter` 易误伤另外 4 站；公共逻辑缺乏归口与单测复用 |
| **6. 非结构化错误与全局 KVS 泄漏** | 适配器大量抛无类型 `IllegalStateException`/`error("中文")`；`DownloadRecovery.classify()` 靠正则/子串匹配中文错误文案归类失败原因；`Network.html()` 对**所有站点**硬编码检测 KVS 登录框 `id="login_username"` | `DownloadRecovery.kt:43-54`、`Network.kt:450`、`EhAdapter.kt:46`（匹配 `error.message == "请求失败：HTTP 403"`） | 改一句中文报错文案就会破坏自动重试/登录门禁/故障分类逻辑 |
| **7. 凭据加密不一致与注释漂移** | `r34v`/`hanime1` Cookie 明文落盘，画廊站 Cookie 与 Iwara JWT 才走 `SessionVault`；`Network.kt:278` 注释称 `Retry-After` 封顶 15s，实际 `SiteHtml.kt:50` 封顶 120s | `Network.kt:118-119,278`、`SiteHtml.kt:50` | 安全基线不统一；运维与排障容易误判限流等待上限 |

---

## 7. Raven 插件化 `SiteProvider` SPI 架构设计建议

针对 Raven 原生 Kotlin/Compose 重写，建议建立独立的 Gradle 模块分层：`core:network`、`core:site-spi`、`core:site-kit`、`sites:<group>`（如 `sites:video`、`sites:comic`、`sites:photo`），通过 Hilt/Koin Multibinding (`Set<SitePlugin>`) 自动注册，**彻底消灭全局可变单例与跨层 `when (site.id)`**。

### 7.1 核心 SPI 契约设计（组合优于巨型接口）

```kotlin
// core:site-spi
interface SitePlugin {
    val descriptor: SiteDescriptor

    // 可选能力以显式组合/属性暴露（null 表示不支持，编译期类型安全）
    val feedSource: FeedSource? get() = null
    val videoSource: VideoSource? get() = null
    val gallerySource: GallerySource? get() = null
    val directorySource: DirectorySource? get() = null
    val authProvider: AuthProvider get() = AuthProvider.None
    val linkResolver: LinkResolver
    val hostRouter: HostRouter get() = HostRouter.Standard(descriptor.hosts)
    val mediaTransportHooks: MediaTransportHooks get() = MediaTransportHooks.None
}
```

关键子契约职责映射（将现有全部 16+ 处硬编码“物归原主”）：

1. **`SiteDescriptor`（纯不可变声明式元数据）**：
   - `id`, `displayName`, `category: SiteCategory`, `supportedMediaKinds: Set<MediaKind>`（天然支持 `iwara` 与 `cosplaytele` 双模态！）, `primaryHost`, `mirrors`, `apiHosts`, `mediaDomains`, `networkProfile`（UA、Referer、页面/图片节流间隔、是否禁快照缓存）。
   - `uiProfile`：收拢原 `SiteFunctionPage.kt`、`ComposeSiteFunctions.kt`、`SearchSupport.kt` 的全部配置——`featuredSectionKeys`、`sections`、`searchScopes`（含自定义文案如“类型”“Coser”“图库”）、`filterSpec`（支持哪些 `FilterField`、服务端 vs 客户端范围说明、排除标签语法）、`usageNotice`、`itemNoun`/`actionVerb`/`creatorLabel`/`catalogueLabel`、`supportsLanguageFilter`。
2. **`FeedSource` + 强类型分页令牌 `PageToken`**：
   ```kotlin
   sealed interface PageToken {
       data class Numbered(val page: Int) : PageToken           // KVS/Hanime/MissAV/WNACG/Photo...
       data class ZeroBased(val index: Int) : PageToken         // Iwara
       data class Cursor(val raw: String) : PageToken           // EH/EX #unext、nhentai/Hitomi 双语归并
       data class RangeOffset(val offset: Long) : PageToken     // Hitomi 单路 .nozomi
   }
   ```
   解决 `FeedRequest` 同时塞入 `page` 与 `cursor` 的语义混乱。
3. **`VideoSource` + `MediaTransportHooks`**：
   - `StreamSource(label, url, height, format: StreamFormat /* MP4 | HLS | DASH */, isPreview: Boolean)`（显式标记 `KBJFree` 预览流，无需文案猜）。
   - `MediaTransportHooks.wrapDataSource(upstream: DataSource): DataSource`——`rou.video` 插件自行挂载 `RouPng.UnwrappingDataSource`，`PlaybackStore` 不再引用 `ROUVIDEO` 或 `RouPng`！
4. **`GallerySource` + 可插拔后处理/恢复钩子**：
   - `GalleryPage` 携带可选 `imageProcessor: ImagePostProcessor?`（`JMComic` 自行提供 `StripUnscrambleProcessor(strips)`，`GalleryImage` 模型不再污染 `imageStripCount`）。
   - `reloadOnError(cause: SiteException, page: GalleryPage): String?` + `rejectCrossHostImageRedirect: Boolean`——将 `GalleryRepository.kt` 中 3 处 `EHENTAI/EXHENTAI` 特判彻底移回 `EhPlugin`！
5. **`AuthProvider` 密封体系 + 请求拦截钩子**：
   - 密封类型：`None`、`FormCredentials`、`JwtBearer`、`WebViewCookieAuth(loginUrl, allowedHosts, requiredCookies, cookieVerifier)`、`CookieImport`。
   - 属性 `requiresAuthGate: Boolean`（`ExHentai` 设为 `true`，消除 5 处 `siteId == "exhentai"` 门禁判断）。
   - 钩子 `decorateRequest(request, targetHost)` + `defaultCookies(host)`——把 Iwara 的 `Origin`/`x-site`/`Bearer` + `authEpoch` 校验和 EH 的 `nw=1` 从 `Network.kt` 彻底抽离进各自插件！
6. **`LinkResolver`**：
   - 统一负责主机匹配（含 Iwara `apiHosts`）、URL 规范化（含 JMComic `#chapters` fragment 保留）、链接转 `FeedRequest` / `WorkRef(siteId, workId, mediaKind, canonicalUrl)`（彻底替代 `IncomingLink.kt` 与 `ShelfOrganization.kt` 的 Cosplaytele 正则）。
7. **统一强类型异常 `SiteException`**：
   - `NetworkError`、`RateLimited(retryAfterMs)`、`ChallengeRequired`、`AuthRequired`、`QuotaExceeded`、`NotFound`、`ParseError`、`AccessDenied`、`StorageError`。
   - `DownloadRecovery`、UI 门禁、`FeedSnapshots` 均只做 `when (error)` 类型分派，**零字符串匹配**。

### 7.2 网络与图片层升级要点
1. **协程非阻塞节流 `HostPacer`**：在发起 OkHttp `Call` 之前（`suspend` 层），按 `host` 维度用轻量 `Mutex` 计算下一槽位时间片并 `delay(waitMs)`——不占用任何 OkHttp 线程，且立即响应协程取消。
2. **共享连接池的预构建客户端**：`OkHttpClient` 单一底座共享 `ConnectionPool`、`Dispatcher`、`Ipv4FirstDns`、`SystemProxySelector`；按需预建 `pageClient`、`apiClient`、`mediaClient`、`imageClient`、`probeClient`（给 `JmcomicMirrorRouter` 复用），禁止在热路径每请求 `.newBuilder().build()`。
3. **统一凭据加密**：所有站点的 Cookie 与 Token（含 `r34v`、`hanime1`、`iwara`、`ehentai`、`exhentai`）一律经 `SessionVault`（AndroidKeyStore AES-GCM）持久化，并保留按站清除与 CDN 零凭据不变量。
4. **单 `ImageLoader` + `SiteId` Extra（Coil 3）**：用 1 个全局 Coil 3 `ImageLoader` + 自定义 `Keyer`（基于 `ImageRequest.Extras[SiteIdKey]` 生成 `"$siteId|$url"` 缓存键）+ 动态 `Call.Factory`（将 `SiteIdKey` 映射到对应插件的 UA/Referer 装饰器），取代维护 19 个 `ImageLoader` 实例的 `Map`。
5. **共享协议工具包 `core:site-kit`**：
   - `UrlKit`（统一 UTF-8 URL 编解码、HTML 实体与空白清理——替换全代码库对 `MissavProtocol.encode/clean` 的调用）。
   - `JsExtractor`（花括号配对提取器、Dean Edwards Base-62 Packer 解码器、Next.js RSC Flight 拼装反转义器）。
   - `WordPressPhotoEngine`（吸收 `FourKhdAdapter`、`Everia`、`4KUp`、`Cosplaytele` 4 站，支持可配置分页参数模板与可选内嵌视频提取插件如 `CossoraProtocol`）。
   - `HostRouter`（统一简单镜像回退与 `LeasedMirrorRouter`，替代 `SiteHtml` 与 `JmcomicMirrorRouter` 两套割裂机制）。
   - `LruTtlCache<K, V>` 与 `DualStreamMerger`（供 `nhentai` 与 `Hitomi` 共用双语流游标归并）。
   - `TagDictionary`：保留并扩展 `assets/tag-names.json`，设计可插拔词典源接口以便未来挂载命名空间翻译包（如 EhTagTranslation）。

### 7.3 迁移回归核对表（绝不可破坏的 14 条协议不变量）

| # | 站点 / 模块 | 关键不变量 |
|---|---|---|
| 1 | 全局 DNS | 保留 `Ipv4FirstDns`（防 `everia.club` 等双栈站 AAAA 不可达拖死 5s 超时） |
| 2 | 全局代理 | 保留 `SystemProxySelector` 仅取首路径不回退 `DIRECT` + `ConnectivityManager`/`PROXY_CHANGE_ACTION` 清连接池与递增 `routeEpoch` |
| 3 | 全局 TLS | 保留 `cleartextTrafficPermitted="false"` + `res/raw/isrg_root_x1.pem`（兼顾 API 24+） |
| 4 | 凭据隔离 | Cookie 与 Bearer Token 严禁发往 `mediaDomains` 与更新服；单站登出只清该站 |
| 5 | KVS / 媒体 | 保留 `MediaRedirectCache`（网络层拦截器捕获 302 后 CDN，403/404/410 自愈） |
| 6 | `rou.video` | `HlsDownloads.mime()` 必须识别 `/api/hls/`；`RouPng` 必须 `position=0` 拉全块、`Inflater(nowrap=false)` 解 `roUd`、非 PNG 流式透传 |
| 7 | `iwara` | 搜索必须用 `type=videos`/`type=images`；保留 `X-Version` SHA-1 + 动态盐值热提取 + `Origin`/`x-site` + `count` 动态分页 + `authEpoch` 取消在途订阅 |
| 8 | `missav` / `jable` | MissAV 62 进制单遍解包；Jable 主域用 `jable.vip`、花括号配对提取 `player_aaaa`、`《》` 标题提取 |
| 9 | `pornhub` | 同时兼容桌面 `li.pcVideoListItem` 与移动 `li[data-video-vkey]` 两套 DOM |
| 10 | `ehentai` / `exhentai` | 保留 `SiteAuthActivity` 7 状态安全边界（`FLAG_SECURE`/禁 Bridge/白名单/清临时 Cookie）、`nw=1`、`f_cats` XOR 掩码、`#unext` 游标、`?nl=` 重载与 `fullimg.php` |
| 11 | `nhentai` | 使用 `/api/v2/*` + 动态 `/api/v2/cdn` 白名单 + 重复后缀清洗 + 双语互斥流游标 |
| 12 | `hitomi` | 保留 `.nozomi` Range 206 校验、SHA-256 464B B-Tree 搜索、`gg.js` 正则路由（不执行 JS）、WebP 路径公式 |
| 13 | `wnacg` / `jmcomic` | WNACG 固定桌面 UA + `document.writeln` 解包 + 剥离 `shoucang.jpg`；JMComic 路由绑定镜像租约 + MD5 `lastHex.code` 取模 `stripCount` + 余数归顶条的 `BitmapRegionDecoder` 还原 |
| 14 | `cosplaytele` / 写真 | Cosplaytele `#gallery-1` 隔离 + `CossoraProtocol` AES-256-CBC（16B IV + 32B UTF-8 hex 密钥）；写真多分页保留 `#index-page-offset` 无状态寻址与 4 页漂移容错 |

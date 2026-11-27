# 拷贝漫画 Android 3.1.1 · APK、接口与阅读实现

本资料对应 `com.copymanga.app` 3.1.1（85）。样本的 Flutter 业务库中可以定位到首页、搜索、作品详情、章节、收藏、历史和线路配置等路径。对照 Raven 当前源码，可以进一步说明浏览、搜索、详情和分组目录四种请求怎样构造。两份客户端的域名、参数默认值及正文读取方式应分别核对。

## 资料入口

| 要查什么 | 从哪里看 |
|---|---|
| 复查原始样本、重新验签 | 本机 `original/copymanga.release_20261110_3.1.1.apk`（不入库） |
| 查看全部文件，按名称查找或比对后续版本 | 本机 `unpacked/`（不入库） · [逐文件大小与 SHA-256 清单](files.csv) |
| 搜索接口、找二进制中的原始位置 | [路由与偏移 CSV](routes.csv) · 本机 `unpacked/lib/arm64-v8a/libapp.so` |
| 查包名、版本、组件、权限和网络配置 | [包信息](evidence/package.txt) · [可读 Android 清单](evidence/manifest.txt) · [可读网络配置](evidence/network-security.txt) |
| 复核签名 | [Android SDK 验签输出](evidence/signature.txt) |

`unpacked/` 保留 APK 内部的原始目录和二进制格式，`evidence/` 中的文本由 Android SDK Build Tools 36.0.0 从同一 APK 导出。文中写作 `0x…` 的位置，除特别标注外，均为 **`unpacked/lib/arm64-v8a/libapp.so` 内的文件偏移**，其他 CPU 架构的偏移不同。`original/` 与 `unpacked/` 只在本机保留，见下节。

## 仓库保留范围

`original/`（约 101 MB APK）与 `unpacked/`（2,098 个文件，约 100 MB）已列入 `.gitignore`，不再纳入版本控制。样本自带拷贝漫画的 Firebase 配置：`unpacked/resources.arsc` 与三份 `libapp.so` 内嵌同一把 `google_api_key`，GitHub Secret Scanning 会将其报告为公开泄漏的 Google API Key。该凭据归属 `com.copymanga.app`，不是 Raven 的凭据；Raven 源码与构建配置未引用 Firebase 或该 Key。

仓库保留可读证据与清单：[包信息](evidence/package.txt)、[清单](evidence/manifest.txt)、[网络配置](evidence/network-security.txt)、[验签输出](evidence/signature.txt)、[逐文件指纹](files.csv)、[路由表](routes.csv)。复核二进制时按「样本与验证范围」的 SHA-256 从官方渠道取回同一版本 APK，再在本地解包。

这两类文件由提交 `96a2cd40` 引入，本次起不再跟踪；该提交仍在既有分支与标签历史中。要彻底清除需重写历史并同步标签，属破坏性操作，另行确认。

## 样本与验证范围

| 项目 | 值 |
|---|---|
| 应用名、包名、入口 | `拷貝漫畫` · `com.copymanga.app` · `com.copymanga.app.MainActivity` |
| 应用版本 | `versionName=3.1.1`，`versionCode=85` |
| Android 版本 | 最低 API 24，目标和编译 API 35 |
| 原始 APK | 105,038,112 字节 |
| APK SHA-256 | `8ba48550e47d52cc1ec976f8e8d9992a6fe99d818f2d2844700fdf39ea7a5930` |
| 签名证书 SHA-256 | `1800b4b60e9fee73916860717b97a11d89ed6c643a923b9c36a9c7b868c04c52`。SDK 验签通过，v2、v3 均通过。发行者归属未与独立来源的证书比对。 |
| 解包核对 | 2,098 个文件，共 104,809,071 字节。文件名、每个文件的内容和哈希均与本资料所附 APK 核对。 |

本次做了离线静态分析，即直接检查安装包和已编译的业务库。下文把 APK 中读到的字符串、Raven 源码中能确认的调用、需要运行客户端才能确认的行为分别注明。未运行样本，未采集网络请求或服务端响应。

## 域名和线路

| APK 中的 HTTPS 地址 | ARM64 偏移 | 证据能说明什么 |
|---|---|---|
| `https://mapi.mangacopy.com` | `0x4dee5` | 客户端内置的 API 候选地址 |
| `https://api.manga2026.xyz` | `0x342d9` | 客户端内置的 API 候选地址 |
| `https://api.copy202602.com` | `0x69df1` | 客户端内置的 API 候选地址 |

线路相关的路径包括 `/api/v3/system/network2`、`/api/v3/system/config/2020/3`、`/api/v3/system/config/6000`。另可找到 `apiUrl`（`0x90f67`）、`host_comic`（`0x8ff25`）、`host_novel`（`0x8a635`）、`host_cartoon`（`0x29232`）。由包内证据可以区分这些字段名，当前默认线路、下发响应结构、按地区切换和失败重试顺序仍未知。`www.copymanga.tv` 和 `www.2026copy.com` 出现在隐私政策页面的完整链接中，证据所指用途是展示网页。

## API 路径索引

[路由表](routes.csv) 收录在 ARM64 业务库中找到的 74 个 v3 路径或前缀、10 个 v2 路径或前缀，另列 8 个路径片段。三列依次是原始字符串、文件偏移和类型。`path` 表示独立的路径字符串，`prefix` 表示末尾带 `/` 的路径字符串，`fragment` 表示可能参与拼接的片段。92 行均已按偏移和字符串长度复核。此数量统计的是字面量；一个请求可能由多个字面量组成。

| 业务 | APK 中的路径，表内省略公共前缀 `/api/v3/` | 解释 |
|---|---|---|
| 首页、推荐、更新 | `h5/homeIndex2`、`recs`、`update/newest` | 三个独立路径，实际首页加载顺序未知 |
| 漫画列表、筛选 | `comics`、`h5/filter/`、`theme/comic/count` | 同一业务库含排序和题材相关字段 |
| 搜索 | `search/comic`、`search/books`、`search/cartoons`、`search/posts` | 漫画路径使用单数 `comic`，其余三项使用复数 |
| 作品详情、目录和阅读 | `comic2/`、`comic/` | 另见独立片段 `/group/`、`/chapters`、`/chapter/`、`/chapter2/`、`/chapterdownload/`、`/chapter2download/` |
| 排行、专题 | `ranks`、`topics`、`topic/` | 有 `rankingDay`、`rankingWeek`、`rankingMonth`、`rankingTotal` 标识；这些标识在请求中的序列化值未知 |
| 小说、动画和帖子 | `books`、`book/`、`cartoons`、`cartoon/`、`h5/cartoonIndex`、`posts`、`post/`、`h5/postIndex` | 分别存在列表、详情前缀或首页入口，完整目录见 CSV |
| 用户、收藏、历史 | `login`、`logout`、`register`、`member/info`、`member/collect/comic`、`member/collect/comics`、`member/browse/comics` | 收藏的单数和复数路径都存在。登录态、HTTP 方法和请求体未逐项验证 |
| 评论、反馈、订单 | `comments`、`bookcomments`、`roasts`、`member/feedback`、`member/order`、`member/orders` | 此处仅确认路径，未执行写入或交易 |
| 配置、版本与广告 | `system/network2`、`system/appVersion/last`、`member/ads/verify` | CSV 中另有 10 个以 `/api/v2/adopr/` 开头的广告相关路径或前缀 |

### 字段与排序值怎样定位

| 用途线索 | 独立字符串及文件偏移 |
|---|---|
| 分页 | `limit` `0x7b2ee`，`offset` `0x5b664`，`total` `0x87790` |
| 搜索、筛选与排行 | `q_type` `0x3f812`，`theme` `0x939d1`，`region` `0x4b07e`，`free_type` `0x7bf3f`，`period` `0x57be0` |
| 排序 | `ordering` `0x97408`，`-datetime_updated` `0x3f0e0`，`-popular` `0x8f3a9` |
| 作品与章节 | `path_word` `0x2d39b`，`group_path_word` `0x3dce5`，`comic_uuid` `0x5b54d`，`chapter_uuid` `0x38e7b`，`groups` `0x90e7f` |
| 常见返回字段 | `code` `0x94b1f`，`message` `0x67dfc`，`results` `0x274a6`，`list` `0x996e3`，`cover` `0x972b0` |
| 客户端与图片 | `platform` `0x46adf`，`version` `0x5dea2`，`imageQuality` `0x61138`，`webp` `0x67cd9` |

这些位置可以复核字段拼写。要确定某字段是否必填、放在 URL 还是请求头、由哪个端点读取，需要看到对应调用或实际请求。下面四项有 Raven 源码可供对照。

## Raven 当前实现对照

Raven 源码在 `sites/comic/src/main/kotlin/com/raven/app/sites/comic/MangacopySitePlugin.kt`。以下请求均通过 `api()` 调用 `getText()`，使用 GET。基址为 `https://api.copy4000.com/api/v3`。本节按当前工作区实现记录，Raven 的版本头、分页大小和桌面阅读路径不代表 APK 已验证的运行时行为。[插件源码](../../sites/comic/src/main/kotlin/com/raven/app/sites/comic/MangacopySitePlugin.kt)

| 请求 | URL 路径及参数 | 解析行为 | 源码行 |
|---|---|---|---|
| 浏览 | `/comics`，`ordering=-datetime_updated` 或 `-popular`，`limit=30`，`offset=(页码-1)×30`，可选 `theme` | 读 `results.list[].name`、`path_word`、`cover`，用 `offset + 已解析条数 < total` 判断下一页 | 65–107、267–277 |
| 搜索 | `/search/comic`，`platform=1`，`q` 为去除首尾空白的关键词，`q_type` 为空字符串，`limit=30`，`offset` 同上 | 与浏览共用摘要解析，读取 `results.total` 和 `results.offset`。空搜索词在发请求前被拒绝 | 115–122、243–264 |
| 详情 | `/comic2/{path_word}?platform=1` | `results.comic` 中读取 `name`、`cover`、`brief`，`results.groups` 提供章节分组 | 127–136、169 |
| 分组目录 | `/comic/{path_word}/group/{group}/chapters?limit=500&offset=0`。`group` 取分组对象的 `path_word`，无可用分组时用 `default` | 从 `results.list[]` 取 `uuid`、`name`。每个分组请求一次；`500` 是 Raven 发送的数量，服务端上限未知 | 280–301 |

共同请求头为 `platform: 1`、`version: 2026.10.16`、`Referer: https://www.mangacopy.com/`、`Accept: application/json`。搜索和详情在 URL 里另传 `platform=1`。应用包版本、API 路径大版本和请求头版本是三个独立值。源码中请求构造见 346–380 行，基址和请求头见 457–469、498–502 行。

Raven 打开正文时取桌面网页 `/comic/{path_word}/chapter/{uuid}`，再解析图片地址，入口在 320 行。APK 中直接可见两套阅读片段和两套下载片段。哪种操作选择哪套前缀、是否按线路切换、章节响应的实际图片字段和页序，仍缺少该 APK 的运行记录。

## 客户端技术与本地存储

| 部分 | 观察到的内容 | 原始依据 |
|---|---|---|
| 运行时 | Flutter + Dart AOT，业务代码提前编译为机器码。ARM64 引擎带有 `3.1.0 (stable)` 和 `android_arm64` 版本字符串 | `lib/arm64-v8a/libflutter.so` 中 `0x138a7f`，另见 `libapp.so` 的 Dart 快照标志 |
| CPU 架构 | `arm64-v8a`、`armeabi-v7a`、`x86_64` 各有 `libapp.so` 和 `libflutter.so`。APK 还包含其他库的 `x86` 条目 | 本机 `unpacked/lib/` |
| 网络与图片 | 依赖声明列有 `dio`、`http`、`connectivity_plus`、`cached_network_image` 和 `flutter_cache_manager`。尚未确定每个端点使用哪个网络库 | 本机 `unpacked/assets/flutter_assets/NOTICES.Z`（gzip 压缩） |
| 状态、偏好和网页 | 依赖声明列有 `provider`、`shared_preferences`、`sqflite` 和 `webview_flutter` | 同一份 Flutter 依赖清单 |
| Android 宿主与广告 | 清单含 Flutter 入口和多个广告 SDK 组件。包中有 `libijiami_1011.so`、`assets/ijiami_1011.VData`、穿山甲及广点通相关文件 | [组件清单](evidence/manifest.txt) · [文件索引](files.csv) |
| 网络配置 | Android 的 `base-config` 允许明文流量，声明信任系统和用户证书。实际业务流量协议和 Dart 网络栈行为未经实测 | [可读网络配置](evidence/network-security.txt) |

业务库保留了 SQLite 建表语句，因此可以直接查到本地字段。下表描述建表定义；未从已安装应用读取任何用户记录。

| 表 | ARM64 偏移 | 可确认的字段与用途 |
|---|---|---|
| `chapter` | `0x849ac` | `path_word`、`comic_uuid`、`chapter_uuid`、`chapter_name`、`pageIndex`、`axisType`，用于记录漫画章节和阅读位置 |
| `chapter_novel` | `0x8e64f` | `book_uuid`、`chapter_uuid`、`page_index`、`chapter_index`、`font_size`、`line_height` 等，小说有独立的进度和排版字段 |
| `download_comic` | `0x75ea0` | `comic_id`、`comic_name`、`comic_path_word`、`comic_local_path`、`user_id` |
| `download_chapter` | `0x2b9b4`、`0x8877a` | 两处建表文本包含 `chapter_id`、`status`、`image_count`、`image_split_path`、`image_local_path`、`next`、`prev`、`sort` 等字段 |

## 后续怎样复查

- **确认拿到的是同一版样本**：在本目录运行 PowerShell 命令 `Get-FileHash .\original\*.apk -Algorithm SHA256`，与上方 APK 指纹比对。应用版本相同也可能有不同构建。
- **核对某条路由**：在 `routes.csv` 搜索原文，例如 `/api/v3/search/comic`。用十六进制查看器打开解包后的 ARM64 `libapp.so`，跳到 `0x537b0`，即可读到该字符串。偏移从未压缩文件开头算起。
- **比较新版**：先用 `files.csv` 对照同路径文件的 SHA-256，再按路由原文比较新增、删除和重命名项。新版二进制布局变化后应重新定位偏移。
- **区分待验证问题**：要确认实际线路，记录所选地区、线路及请求主机；要确认接口参数，分别记录搜索、翻页、打开作品和打开章节时的请求方法、路径、查询参数与返回结构。当前资料没有这些运行时观测值。

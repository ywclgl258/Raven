# Raven 无用代码审计报告

生成时间：2026-11-27  
审计范围：`D:\Raven` 全仓库 Kotlin/Gradle 源码与测试。  
外部证据来源：仓库内 Git 历史、代码引用、编译验证；Slack/Statsig/远端遥测在当前环境不可达，未编造生产取值。

## 1. 审计方法

- 全文检索 `@Deprecated`、`legacy`、`compat`、`alias`、`TODO/FIXME`、Statsig/feature flag 关键字。
- 对每个候选项执行仓库内引用计数（`rg`）并交叉检查测试。
- 编译受影响模块验证删除不破坏构建。
- 无法确认生产取值的候选项保留为“待遥测确认”，不删除。

## 2. 已清理候选项

| 候选项 | 位置 | 证据 | 处理 | CPU 开销 |
|---|---|---|---|---|
| `rememberRavenShimmerBrush()` | `core/designsystem/.../RavenStates.kt` | `@Deprecated`，仓库内引用计数 0（排除定义） | 删除函数与过期 KDoc | 移除每次重组的 `rememberInfiniteTransition` + 线性渐变分配；120Hz shimmer 路径减少重组 |
| `RavenMotion.AppleDecelerationEasing` | `core/designsystem/.../RavenMotion.kt` | `@Deprecated` 兼容别名，引用计数 0 | 删除别名 val | 无运行时开销变化，仅减少符号表面 |
| `PlayerTagDisplay.isFavorited` | `feature/player/.../PlayerUiState.kt` | `@Deprecated`，仅测试引用；生产代码已使用 `isSubscribed` | 删除属性，并将 `GalleryDetailViewModelTest` 改为 `isSubscribed` | 去除每次访问的额外 getter |

## 3. 编译验证

执行：

```powershell
.\gradlew.bat :core:designsystem:compileDebugKotlin :feature:player:compileDebugKotlin :feature:reader:compileDebugUnitTestKotlin --no-daemon
```

结果：`BUILD SUCCESSFUL`。

## 4. 待遥测/生产数据确认的候选项

以下代码仍被兼容路径或测试引用，当前证据不足以删除；需 Slack/Statsig/生产遥测确认后处理。

| 候选项 | 位置 | 当前证据 | 需要的证据 | 预估 CPU/内存开销 |
|---|---|---|---|---|
| `SettingsDataStore.LEGACY_PORTABLE_SETTINGS` | `core/database/.../SettingsDataStore.kt` | 仍用于导入旧备份与 portable settings merge | 生产中 legacy portable settings 命中率、最近 N 版本导入日志 | 仅迁移时执行，稳态无额外开销 |
| `HlsDownloadSupport.LEGACY_METADATA_KEY` | `core/media/.../HlsDownloadSupport.kt`, `DownloadCaches.kt` | 读取旧 HLS 下载元数据并迁移 | 旧下载缓存存在率 | 仅下载缓存读取时分支判断 |
| `BackupCodec.LEGACY_R34V_V1` | `core/security/.../BackupCodec.kt`, settings UI | 仍支持旧备份恢复 | 旧备份导入频率 | 解码分支，仅导入时执行 |
| `ManwaImageAesProtocol.LEGACY_2022_KEY` | `core/site-spi/.../ManwaImageAesProtocol.kt` | 旧图片仍按 `?v=20220724` 走 AES-CBC | 生产图片 URL 版本分布 | 每张旧图解密一次 |
| `JmcomicStripMath.LEGACY_FIXED_TEN_THRESHOLD` | `core/site-spi/.../JmcomicStripMath.kt` | 旧章节 ID 兼容阈值 | 新/旧章节 ID 分布 | 每次 strip 计算一次比较 |

## 5. Statsig / Feature Flag 审计结论

- 仓库内未发现 Statsig SDK、feature flag 服务、experiment/layer 常量或远程配置读取代码。
- 未发现 `isFeatureEnabled`、`FeatureFlags`、rollout、experiment 相关实现。
- 因此本轮没有可清理的 Statsig 开关、实验或分层；若外部服务存在，需提供 Slack/Statsig 导出或生产遥测后再审计。

## 6. 建议后续

1. 对“待遥测确认”候选项增加临时计数日志或遥测事件，确认命中率后再删除。
2. 为设计系统弃用别名建立 CI 引用检查，避免新增无引用兼容层。
3. 若需要全仓库死代码自动化扫描，建议引入 Kotlin 编译器/IDE 不可达声明分析或 Detekt unused-declaration 规则，当前仓库未配置。
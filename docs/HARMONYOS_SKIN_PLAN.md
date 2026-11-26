# HarmonyOS 视觉皮肤落地方案（Raven Obsidian × Crimson）

> **状态**：已实施；当前实现以 `:core:designsystem` 源码为准，本文件保留方案及实施前对照，不记录本轮测试执行结论
> **品牌不变量**：Obsidian `#09090B` + Crimson `#FF4D58` 保持不变  
> **字体策略**：不打包 HarmonyOS Sans，使用系统默认字体 `FontFamily.SansSerif`，仅调整字距为 HarmonyOS 可读性策略

---

## 1. 目标

在保留 Raven 品牌识别的前提下，把视觉、布局、动效统一到 HarmonyOS 设计语言，并把控件规则收敛到 `:core:designsystem` 公共组件，不再由 feature 页面各自决定。

覆盖范围：

1. 切换字距策略（系统字体不变）
2. 为 Button / Sheet / Dialog 定义统一尺寸、高度、交互规则并写入公共组件
3. 动效曲线调整为官方 `(0.2, 0.0, 0.1, 1.0)`
4. 折叠屏 / 平板 / 电脑端专项适配

---

## 2. 实施前基线（历史对照，非当前令牌来源）

| 区域 | 当时状态 | 关键文件 |
|---|---|---|
| 字体 | 全部 `FontFamily.SansSerif`，负字距偏 Apple HIG | `core/designsystem/.../theme/RavenTypography.kt` |
| 动效曲线 | `HarmonyDecelerationEasing = CubicBezier(0.18, 0.96, 0.28, 1f)`；已有三组重力弹簧、互动光、材质层级 | `core/designsystem/.../theme/RavenMotion.kt` |
| 形状 | Card 16 / Button 20 / Dialog 28 / Sheet 32 | `core/designsystem/.../theme/RavenShapes.kt` |
| 响应式 | 320/600/840 断点，2/4/8/12 栅格；导航 600 rail / 1200 drawer | `core/designsystem/.../theme/RavenLayout.kt`、`RavenAdaptiveScaffold.kt` |
| Button | 无公共 `RavenButton` 家族；settings 有私有 `SettingsPrimaryButton/SecondaryButton`；feature 大量直用 M3 Button/TextButton/IconButton/OutlinedButton | `feature/settings/.../SettingsCommonComponents.kt`、`RavenStates.kt` |
| Sheet | 公共 `RavenBottomSheet` 已有，但 `skipPartiallyExpanded = true`、无多档位、无宽屏居中形态 | `core/designsystem/.../component/RavenBottomSheet.kt` 及既有调用点 |
| Dialog | 无 `RavenDialog`；无 M3 AlertDialog；确认流目前全部用 BottomSheet 替代 | 代码检索无 AlertDialog 调用 |
| 品牌色 | Crimson 已集中在 token；Material primary = Crimson | `core/designsystem/.../theme/RavenColors.kt` |

---

## 3. 字体与字距（系统字体版）

### 3.1 字体族

- **不引入** HarmonyOS Sans 字体文件，继续使用 `FontFamily.SansSerif`（系统默认）。
- `RavenTypography` 所有样式保持 `FontFamily.SansSerif`，不改字体源。
- 业务代码禁止直接写 `fontFamily`，统一走 `MaterialTheme.typography`。

### 3.2 字距策略（Apple HIG 紧缩 → HarmonyOS 可读）

| 样式组 | 当前字距 | 目标字距 | 说明 |
|---|---:|---:|---|
| Display / Headline | `-0.4 ~ -0.25sp` | `0sp`（超大展示字可 `-0.1sp`） | 去强压缩，提升中文/混排可读性 |
| Title | `-0.25 ~ -0.1sp` | `0sp` | 标题清晰不拥挤 |
| Body | `0 ~ 0.1sp` | `0sp` | 正文自然阅读 |
| Label | `0 ~ 0.2sp` | `0 ~ 0.1sp` | 按钮/标签保持紧凑但不挤压 |

- `tnum` 等宽数字策略不变，仍强制开启。

---

## 4. 动效曲线调整

### 4.1 Token 变更

文件：`core/designsystem/.../theme/RavenMotion.kt`

| 项 | 当前 | 目标 |
|---|---|---|
| `HarmonyDecelerationEasing` | `CubicBezier(0.18f, 0.96f, 0.28f, 1f)` | `CubicBezier(0.2f, 0.0f, 0.1f, 1.0f)`（官方 Friction） |
| `HarmonyStandardEasing` | `CubicBezier(0.20f, 0f, 0f, 1f)` | 保留，校准注释 |
| `AppleDecelerationEasing` | 兼容别名 | `@Deprecated`，内部指向 `HarmonyDecelerationEasing`，后续删除 |

### 4.2 使用规则

- 页面/Sheet/Dialog/Tab 转场：默认 `HarmonyDecelerationEasing` + 现有时长 token。
- 手势跟手/按压/可中断切换：继续使用物理弹簧（`responsiveSpringSpec` / `interactiveSpringSpec` / `gentleSpatialSpringSpec`）。
- 减弱动效：`LocalMotionReduced == true` 时统一 `snap()`，保留状态变化，取消位移/缩放/弹性过程。

### 4.3 影响面

- `app/.../RavenNavHost.kt` 页面转场
- `core/designsystem/.../component/RavenBottomSheet.kt` 出入场
- 后续 `RavenDialog` 出入场
- 所有显式 `tween(easing = ...)` 的组件

验收：`RavenFeedbackAndMotionTest` 更新曲线断言；实机检查 Sheet/页面 push-pop/Tab 切换无突兀跳变。

---

## 5. 公共组件契约

### 5.1 Button 家族（新增 `RavenButton.kt`）

在 `:core:designsystem` 新增统一按钮 API，feature 禁止新增裸 M3 Button 作为主交互。

| 组件 | 用途 | 视觉 |
|---|---|---|
| `RavenPrimaryButton` | 主操作：播放、确认、保存、安装 | Crimson 容器 / `inkPrimary` 文本；媒体 CTA 场景用 `variant = Cta`（高对比白底黑字） |
| `RavenSecondaryButton` | 次操作：稍后看、取消、返回 | `Surface2/3` + Hairline/Rim |
| `RavenTextButton` | 低强调：查看全部、跳过 | 无背板，Crimson 或 InkSecondary 文本 |
| `RavenIconButton` | 图标操作 | `>=48dp` 触控，圆形/方圆，互动光+弹簧 |
| `RavenDangerButton` | 不可逆操作：删除、清空、登出 | Error 色系，必须配合确认 Dialog/Sheet |

**统一规则：**

- **尺寸**：`ButtonSize = Small(40dp) | Medium(48dp, 默认) | Large(52dp)`；水平 padding `16/20/24dp`。
- **宽度**：单按钮最大 `448dp`；双按钮同行等宽间距 `12dp`；三按钮及以上纵向排列间距 `12dp`。提供 `RavenButtonRow` 布局助手强制执行。
- **形状**：`RavenShapes.ButtonShape`（20dp），图标按钮 `CircleShape`。
- **状态**：Default / Pressed / Focused / Disabled / Loading / Success(可选)。Loading 使用按钮内小型进度指示，不弹额外加载框。
- **交互**：`ravenSpringPress` + `ravenInteractiveLight` + `ravenPressHaptic`；Disabled 无缩放、无光效、色值降级。
- **无障碍**：最小触控 `48dp`；Loading 时 `contentDescription` 含“加载中”。

**迁移：**

- `SettingsPrimaryButton/SecondaryButton` → `RavenPrimaryButton/SecondaryButton` 薄封装或直接替换。
- `RavenStates` 重试按钮改用 `RavenPrimaryButton`。
- feature 裸 M3 Button 分批替换，grep 作回归检查。

### 5.2 BottomSheet（升级 `RavenBottomSheet.kt`）

既有调用保持 API 兼容，新增可选能力。

**统一规则：**

| 规则 | 目标 |
|---|---|
| 高度档位 | `SheetDetent = Medium(60%) | Large(屏高-状态栏-8dp) | Fixed(dp) | Auto`；默认 `Medium` 或 `Auto`；危险/复杂流程指定 Large |
| 默认展开 | 简单选择 `Medium` 可半展；复杂表单/长列表 `Large`；禁止一刀切 `skipPartiallyExpanded = true` |
| 宽屏形态 | `width >= 600dp` 时支持 `SheetPresentation = Bottom | Center`。筛选/简单选择 Bottom；登录/重要确认/设置分组 Center（居中面板，宽 480dp，最大高 90% 屏高） |
| 拖拽 | 顶部拖拽柄保留；多档位时上滑升档、下滑降档/关闭；内容滚动与档位切换按官方规则 |
| 关闭 | 下滑关闭、点击 scrim 关闭（危险确认可禁用 scrim 关闭）；关闭按钮可选 |
| 按钮区 | 底部操作区使用 `RavenButtonRow` |
| 安全区 | 保留 `statusBarsPadding`、`navigationBarsPadding`、`imePadding` |
| 材质 | `Surface2` + 顶部光学 rim + scrim `canvas@62%`；reduceTransparency 时降级 |

**API 演进：**

```kotlin
@Composable
fun RavenBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    presentation: RavenSheetPresentation = RavenSheetPresentation.Bottom,
    detent: RavenSheetDetent = RavenSheetDetent.Auto,
    title: String? = null,
    subtitle: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    confirmValueChange: (SheetValue) -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit,
)
```

实现：Bottom 形态用 `ModalBottomSheet`；Center 形态在宽屏用 `Dialog`/`BasicAlertDialog` 承载同一内容壳（`RavenSheetSurface`），保证视觉一致。

### 5.3 Dialog（新增 `RavenDialog.kt`）

当前无 Dialog 组件，确认流全部用 Sheet。需补齐 HarmonyOS “强打断模态”能力。

| 组件 | 用途 |
|---|---|
| `RavenAlertDialog` | 信息确认、高风险操作、前置条件缺失 |
| `RavenConfirmDialog` | 双按钮确认/取消 |
| `RavenInputDialog` | 简单输入（重命名、创建片单） |
| `RavenLoadingDialog` | 仅用于不可避免的阻塞等待，优先用按钮 Loading 替代 |

**统一规则：**

- **宽度**：`min(屏幕宽 - 32dp, 400dp)`；平板/桌面居中，最大宽 400dp。
- **高度**：内容自适应，最大 `0.9 * (屏高 - 状态栏 - 导航栏)`；超出内部滚动。
- **形状**：`RavenShapes.DialogShape`（28dp）。
- **按钮**：1 个按钮横向铺满；2 个按钮横向等宽间距 12dp；3 个及以上纵向排列。危险操作用 `RavenDangerButton`。
- **关闭**：信息弹窗可点击 scrim/返回关闭；危险确认默认禁用 scrim 关闭，必须明确选择。
- **材质**：`Surface2/3` + 光学 rim + scrim；不使用大面积模糊遮挡正文。
- **无障碍**：标题 heading；焦点默认落在安全操作或主要输入；TalkBack 播报 dialog 角色。

**迁移策略：**

- 高风险确认（删除、清空、登出、覆盖安装、重置）从 BottomSheet 迁移到 `RavenAlertDialog`/`RavenConfirmDialog`。
- 低风险选择（排序、筛选、片单选择）继续用 BottomSheet。
- 设计文档明确 “Sheet vs Dialog 选型表”。

---

## 6. 折叠屏 / 平板 / 电脑端专项适配

### 6.1 断点与窗口类

扩展 `core/designsystem/.../theme/RavenLayout.kt`：

| Window Class | 宽度 | 导航 | 内容策略 |
|---|---:|---|---|
| Compact | `<600dp` | BottomBar | 单栏，BottomSheet 全宽 |
| Medium | `600–839dp` | NavigationRail | 双栏可选，Sheet 居中 480dp |
| Expanded | `840–1199dp` | NavigationRail/Drawer | 双栏，详情侧边分栏，Dialog/Sheet 居中 |
| Large/Desktop | `>=1200dp` | NavigationDrawer | 多栏，内容最大宽度约束，鼠标 hover/focus 态 |

补充 `RavenWindowClass` 或直接使用 Material3 `WindowSizeClass` + `RavenLayout` 映射，避免页面自行判断像素。

### 6.2 导航适配

- `<600dp`：底栏，5 个入口不变。
- `600–1199dp`：Rail；设置/详情子页可配合分栏。
- `>=1200dp`：Permanent Drawer；标签、片库分组、站点切换可利用纵向空间。
- 折叠屏展开态：按窗口宽度实时切换，不按设备型号写死。

### 6.3 Sheet/Dialog 适配

- **手机竖屏**：Sheet 底部，Medium 60% / Large 几乎全屏。
- **手机横屏**：Sheet 最大宽 480dp，顶部保留 8dp 安全间距。
- **折叠屏展开 / 平板**：默认居中面板（480dp 宽，560dp 默认高，最小 320dp，最大 90% 短边）；简单筛选可仍用底部 Sheet。
- **桌面/大窗**：Dialog 居中 400dp；复杂面板可用侧边面板或居中面板，避免全屏铺满。

### 6.4 输入与焦点

- 桌面/平板键盘：为 Dialog、Sheet、搜索框、列表补充 `FocusRequester`、Tab 键顺序、Esc 关闭。
- 鼠标 hover：IconButton、列表行、卡片增加 hover 状态（表面抬升或 Hairline/Glow），但不依赖 hover 才能发现功能。
- 触控目标仍 `>=48dp`，不可因桌面密度压缩。

### 6.5 播放器/阅读器

- 播放器：横屏、折叠屏、桌面窗保持控制层自适应；PiP 规则不变。
- 阅读器：宽屏双页/限宽居中由阅读模块评估，但设置 Sheet 必须走统一 Center/Bottom 规则。

---

## 7. 实施阶段

| 阶段 | 内容 | 主要文件 | 验收 |
|---|---|---|---|
| P0 Token | Typography 字距、deceleration 曲线、Dialog/Sheet 尺寸 token | `RavenTypography.kt`、`RavenMotion.kt`、`RavenShapes.kt`、`RavenLayout.kt` | 单测通过；曲线断言更新 |
| P1 Button | 新增 `RavenButton` 家族 + `RavenButtonRow`，替换 designsystem/settings 共用按钮 | `component/RavenButton.kt`、`RavenStates.kt`、`SettingsCommonComponents.kt` | grep 裸 Button 减少；加载/禁用/危险态统一 |
| P2 Sheet | `RavenBottomSheet` 支持 detent/presentation；抽取 `RavenSheetSurface`；宽屏居中形态 | `RavenBottomSheet.kt`、调用点按场景配置 | 既有调用不回归；手机/平板截图对比 |
| P3 Dialog | 新增 `RavenDialog` 家族；高风险确认从 Sheet 迁移 | `component/RavenDialog.kt`、library/settings 删除/登出/清空流程 | 危险操作不可 scrim 误关；焦点和 TalkBack 正确 |
| P4 适配 | 折叠屏/平板/桌面窗口类、导航、hover/focus、居中面板规则 | `RavenLayout.kt`、`RavenAdaptiveScaffold.kt`、各 Sheet/Dialog 调用点 | 600/840/1200dp、横屏、折叠展开、桌面窗口验证 |
| P5 清理 | 废弃旧 easing 别名、移除私有按钮/分段重复实现、补设计文档 | feature 模块、`docs/DESIGN_AND_UI_GUIDE.md` | 文档与代码一致；无新增裸 M3 弹窗 |

---

## 8. 验收清单

- **视觉**：系统字体 + HarmonyOS 可读字距；Crimson 仅用于激活/进度/关键状态；材质在 reduceTransparency 下正确降级。
- **动效**：转场 easing 为 `(0.2,0,0.1,1)`；弹簧仍用于按压/跟手；减弱动效全部 snap。
- **Button**：主/次/文字/图标/危险/加载状态统一；单按钮最大 448dp；双按钮 12dp 间距。
- **Sheet**：支持 Medium/Large/Auto；宽屏可居中；手势与关闭规则符合场景。
- **Dialog**：高风险操作使用模态 Dialog；宽度 `min(屏宽-32dp,400dp)`；最大高度 90%。
- **适配**：320/360/600/840/1200dp 宽度、横屏、折叠展开、200% 字体、TalkBack、减弱动效/降低透明度通过检查。
- **工程**：无新增 `AlertDialog`；新增裸 `Button/OutlinedButton/TextButton` 需有理由并封装；设计文档同步更新。

---

## 9. 风险与待确认

1. **Sheet 语义迁移**：既有 BottomSheet 调用中，哪些属于“高风险确认”需要产品规则确认后迁移到 Dialog，不能机械替换。
2. **居中 Sheet 的实现形态**：Compose `ModalBottomSheet` 天然底部锚定；宽屏居中形态建议复用同一内容壳但换容器（Dialog/Surface），需保证动效和焦点一致。
3. **官方曲线视觉感受**：从当前弹性 deceleration `(0.18,0.96,0.28,1)` 切到官方 friction `(0.2,0,0.1,1)` 后，页面会更“稳”、少“弹”，需要在播放器、阅读器、Sheet 上实机确认是否符合想要的 HarmonyOS 观感。

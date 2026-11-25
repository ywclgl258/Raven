# Raven · 02 绯红疾翼

本文件夹包含 Raven 的启动图标替换资源、可编辑源稿和 Google Play 商店图标。前景使用 Raven Crimson `#FF4D58`，背景使用 Dark Obsidian `#09090B`。

## 替换 Raven 工程图标

将本包 `res/` 内的子目录合并到 `D:\Raven\app\src\main\res\`，覆盖同名图标文件。资源名与项目的 Manifest 一致，普通入口为 `@mipmap/ic_launcher`，圆形入口为 `@mipmap/ic_launcher_round`。

- `res/drawable/` 包含 108 × 108 dp 的前景、背景和主题单色 VectorDrawable XML。三层采用同一个画布，其中前景和单色图形使用相同轮廓与位置。
- `res/mipmap-anydpi-v26/` 包含两个自适应图标入口。Android 8.0+ 由桌面应用选择遮罩，支持主题图标的桌面可使用单色层。
- `res/mipmap-*/` 包含各密度的旧版圆角方形及圆形 PNG。Raven 支持的 Android 7.0–7.1 使用这组资源。

| 密度 | PNG 尺寸 |
| --- | --- |
| `mdpi` | 48 × 48 px |
| `hdpi` | 72 × 72 px |
| `xhdpi` | 96 × 96 px |
| `xxhdpi` | 144 × 144 px |
| `xxxhdpi` | 192 × 192 px |

## 用其他工具导入

- 分层导入时，从 `source/` 分别选用 `adaptive-foreground`、`adaptive-background`、`adaptive-monochrome` 的 SVG 或 432 × 432 PNG。三层已按 Android 自适应图标画布对齐，保持原始缩放和居中即可。
- 单图导入时，使用 `source/raven-crimson-wing.svg` 或 `source/raven-crimson-wing-1024.png`。这两份源稿是合成后的完整图标。
- Google Play 商店展示图使用 `store/google-play-icon-512.png`，格式为 512 × 512 px、32 位 RGBA PNG、sRGB，背景铺满画布。

[Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) · [Google Play 图标规范](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)

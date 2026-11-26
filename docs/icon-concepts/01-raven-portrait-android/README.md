# Raven 01 高马尾头像 · Android 启动图标

基于 01 高马尾候选。彩色图标保留原画的黑发、豆豆眼和绯红羽饰，底色使用 Dark Obsidian `#09090B`。主题图标用单色轮廓保留羽饰、眼睛和笑脸。

## 工程资源

将 `res/` 的子目录合并到 `app/src/main/res/` 即可替换启动图标。文件名与 Raven 现有 Manifest 对应，入口为 `@mipmap/ic_launcher` 和 `@mipmap/ic_launcher_round`。

- `res/mipmap-anydpi-v26/` 提供 Android 8.0 及以上的自适应图标入口。
- `res/drawable/` 包含 108 × 108 dp 的前景引用、背景和单色 VectorDrawable。支持主题图标的桌面会按系统设置为单色层着色。
- `res/drawable-xxxhdpi/raven_portrait_foreground.png` 为已定位的 432 × 432 透明前景，头像占 66 × 66 dp。
- Android 7.0–7.1 使用各密度目录中的 `ic_launcher.png`（圆角方形）和 `ic_launcher_round.png`（圆形）。

| 密度 | 每张兼容 PNG 的尺寸 |
| --- | --- |
| `mdpi` | 48 × 48 px |
| `hdpi` | 72 × 72 px |
| `xhdpi` | 96 × 96 px |
| `xxhdpi` | 144 × 144 px |
| `xxxhdpi` | 192 × 192 px |

## 商店和设计源稿

- `store/google-play-icon-512.png` 是 512 × 512、32 位 RGBA、sRGB 的商店上传图，使用铺满画布的方形背景。
- `source/raven-portrait-1024.png` 是带背景的 1024 × 1024 合成稿。分层导入可使用前景 PNG 和 `source/` 中的背景、单色 SVG。三者使用同一自适应画布比例。
- [1254 × 1254 透明原画](../../brand-ip/raven-avatar-candidates-v2/01-high-ponytail.png) · [遮罩及尺寸预览](preview.png)

[Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) · [Google Play 图标规范](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)

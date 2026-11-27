# Raven 绯红疾翼 SVG 头像

四张原创女性头像，仅包含头部和肩部，统一使用 Raven Crimson `#FF4D58` 作为唯一彩色强调。图形采用接近参考的贴脸 Q 版比例、大块平涂、豆豆眼、腮红和夸张发型，可直接用于菜单栏、个人资料或品牌 IP。

| 文件 | 特征 | 适合场景 |
| --- | --- | --- |
| `01-crimson-flight.svg` | 黑色短发、单眼眨眼 | 默认头像、新手引导 |
| `02-moonlit-projection.svg` | 黑色长发、银色挑染、半眯眼 | 影院首页、播放器 |
| `03-silver-archivist.svg` | 银发、圆框眼镜 | 片库、收藏、阅读 |
| `04-night-side-tail.svg` | 黑色侧马尾、大笑 | 空状态、操作反馈 |

## SVG 结构

- 全部文件使用 `viewBox="0 0 512 512"`，画布透明，没有背景板或外部阴影。
- 角色按 `hair-back`、`face`、`eyes`、`mouth`、`hair-front`、`feather-clasp` 等独立图层组织。
- 需要做动画时可直接移动、缩放或旋转对应图层。文件中的眨眼和羽饰摆动由内嵌 CSS 驱动，并遵循 `prefers-reduced-motion`。
- 如需纯静态资源，删除各文件顶部的 `<style>` 元素即可。

主要动画钩子：

- `.both-eyes-blink`：双眼同步眨眼。
- `#left-eye-blink`、`#eyes-right-blink`：单眼眨眼。
- `#feather-clasp`：绯红三羽发饰。
- `#side-tail`：侧马尾摆动。
- `#hair-front`、`#hair-back`：头发整体形变。

在原生 Android 使用前，需要将最终选定的 SVG 图层转换为应用实际使用的动画格式。当前文件作为矢量源稿和 SVG 动画基准。

## 预览

打开 `preview.html` 可查看大图、48 px 圆形裁切和菜单栏尺寸预览。参考项目只用于理解 SVG 分层和纯时间函数的动画思路，头像造型、路径和动效均为 Raven 原创设计。

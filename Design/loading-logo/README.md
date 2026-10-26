# 文件加载动画

> ⚠️ **项目已改名为 Orcarness**；本文件路径与引用的 `whaleness-orca-mark/` 目录是历史遗留。

使用 `Design/whaleness-orca-mark/whaleness-orca-dark.svg` 的原始路径生成，不改变品牌轮廓。

- 透明 APNG，192 × 192 像素，48 帧，20 fps，2.4 秒无限循环。
- 鲸鱼轻微浮动、扫光和上升气泡；每帧都是完整透明画布，使用 SOURCE 混合，不留残影。
- iOS 与 Android 使用完全相同的 APNG 文件；系统减少动态效果时显示首帧，切到后台或关闭预览后停止播放。
- `poster.png` 为静态首帧，解码完成前显示，不闪烁空白。

在 macOS 仓库根目录重新生成全部资源：

```sh
swift -module-cache-path /private/tmp/dsh-logo-module-cache scripts/generate_loading_logo.swift
```

脚本使用系统 CoreGraphics 和 ImageIO，无第三方依赖。输出同时写入此目录、iOS Data Asset 和 Android raw 资源目录。

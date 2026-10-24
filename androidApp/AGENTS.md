# Android 菜单规范

- 所有应用内菜单必须使用项目统一的 `DshMenu`、`DshMenuItem`、`DshMenuPopup` 和 `DshMenuSurface` 组件，沿用圆角毛玻璃、轻阴影及淡入缩放动画。
- 禁止在业务页面直接使用 Material `DropdownMenu` / `DropdownMenuItem`、平台 `PopupMenu`、自行绘制的 `PopupWindow` 菜单或系统默认文字操作工具栏。
- 特殊锚点、箭头面板、横向文字工具栏可保留业务定位逻辑，但必须复用统一的菜单容器、菜单项和背景采样宿主。
- 输入框及正文的文字操作保留原生选区、手柄、剪贴板和编辑行为，操作菜单使用项目组件。
- 原生网页必须使用 `DshMenuWebView`，将浏览器文字选择动作交给项目菜单组件，不能直接实例化 `WebView`。
- 新增页面和独立窗口必须接入 `DshMenuHost`，以获得真实背景模糊与统一文字操作菜单。
- 修改菜单时检查窄屏边界、长菜单滚动、禁用项、返回键/点击外部关闭、危险操作和文字选区焦点。

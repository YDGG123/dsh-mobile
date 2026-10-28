# iOS 27.1 工具栏 API 暂缓启用（记录）

> 状态：**代码已隔离，等 SDK 升级后自动启用**。2026-10-27 记录。
> 起因：合并上游 26 个提交（`d5d0510`）时，上游引入了 iOS 27.1 的工具栏 API，
> 但当前 Xcode 26.3 / **SDK 26.2 里没有这些符号**，导致测试模块编译失败。

## 1. 涉及哪些 API

| API | 类型 | 用途 |
| --- | --- | --- |
| `\.toolbarVerticalEdge` | `@Environment` 值 | 读系统栏当前是**竖向还是横向** |
| `.axisBehavior(.verticalPreferred)` | Toolbar modifier | 建议系统把工具栏项排成竖向 |
| `.sharedBackgroundVisibility(.hidden)` | Toolbar modifier | 隐藏工具栏项的共享背景 |

来源：上游 `DeepSeekHarnessMobile/Views/WorkspaceView.swift`
（`WorkspaceVerticalToolbarLayout`，第 39–62 行左右）。

## 2. 它做什么

iOS 27.1 起，系统可能把 navigation bar 的工具栏**竖着排**。上游这段代码：

```swift
if #available(iOS 27.1, *) {
    WorkspaceVerticalToolbarLayout { usesVerticalToolbar in
        workspaceBody(usesVerticalToolbar: usesVerticalToolbar)
    } toolbar: { /* 两个 ToolbarItem */ }
} else {
    workspaceBody(usesVerticalToolbar: false)   // ← 16/17/26 全走这条
}
```

- `verticalEdge != nil` → 系统栏是竖向 → 把认证菜单/设置按钮挂进 toolbar
- 否则 → 不挂 toolbar，用页面自身的入口（现有行为）

**设计本身是对的**，双分支完整，符合我们「17+/26 走原生、16 走回退」的铁律。

## 3. 为什么现在编不过

**不是设计问题，是 SDK 版本问题。**

`#available(iOS 27.1, *)` 只做**运行时**判断：老系统上不执行这段。但**编译期仍然要求符号存在**。
当前 SDK 26.2 里没有 `toolbarVerticalEdge` / `axisBehavior` 这些符号 → **即使包在 `#available` 里也编不过**。

实测：`grep toolbarVerticalEdge` 在 `iPhoneOS26.2.sdk` 里 0 命中；
`axisBehavior` 同样 0 命中（只有 `sharedBackgroundVisibility` 有 `.swiftdoc` 文档条目，不是接口定义）。

## 4. 当前临时处理

用**编译器版本**隔离，代码保留、不删、不参与当前编译：

```swift
#if compiler(>=6.3)
if #available(iOS 27.1, *) {
    WorkspaceVerticalToolbarLayout { ... } toolbar: { ... }
} else {
    workspaceBody(usesVerticalToolbar: false)
}
#else
// Xcode 26.3 / SDK 26.2 及更早：没有 27.1 的工具栏 API，直接走成熟路径。
workspaceBody(usesVerticalToolbar: false)
#endif
```

同样隔离的还有：
- `WorkspaceVerticalToolbarLayout` 结构体定义
- `toolbarSettingsButton` 属性（只在上面那段 toolbar 里用）

**为什么用 `compiler(>=6.3)`**：当前是 Swift 6.2.4。升到 Xcode 27（Swift 6.3+）时，
这个条件自然成立，**不需要改任何代码，那段分支自动编译并生效**。

## 5. 为什么现在不加这个 API（用户定调）

用户原话：

> 上游用了 iOS 27.1 的 API 这个要写一个记录文档，后面会加上这个 API 的，
> 只是暂时只有我的 iOS16 使用所以可以不加这个 API

具体：
- 用户手上**只有 iOS 16 实机**，27.1+ 的能力现在**没有任何设备能用上**
- 这个功能是**新 UI（竖向工具栏）**，不是核心能力；16.0 走 `else` 分支完全够用
- **不为它提升部署目标、不为它删 16.0 兼容路径**

## 6. 将来怎么启用

**升级 Xcode / SDK 到 27.1+ 即可自动生效**，无需改代码：

1. 升级 Xcode（Swift 6.3+，SDK 27.1+）
2. 跑闸门 `scripts/local-typecheck.sh` —— `#if compiler(>=6.3)` 成立，那段开始参与编译
3. 若 SDK 里确有这些符号，直接绿；此时可**删掉文档第 4 节的 `#else` 分支**（或保留，无副作用）
4. 在 iOS 27.1+ 设备/模拟器上验证竖向工具栏行为

## 7. 一条通用教训（值得记）

**`#available` 只能保护运行时，保护不了编译期。**

判断「这个新 API 我能不能用」要问两个问题：

| 问题 | 靠什么解决 |
| --- | --- |
| 部署目标 / 真机系统版本支持吗？ | `#available(iOS X, *)` 双分支 |
| **编译用的 SDK 里有这个符号吗？** | **`#if` 编译条件，或升级 SDK。`#available` 解决不了** |

上游写 `#available` 是对的（运行时兼容做到了），但用了新 SDK 才有的符号，
所以在我们这台 Xcode 26.3 上仍然编不过 —— **这不是上游写错，是 SDK 代差**。

## 8. 相关

- 合并记录：commit `d5d0510`（`merge: 合并上游 26 个提交（保持 iOS 16.0 部署目标）`）
- 兼容铁律：部署目标 16.0（Widget 16.2），17+/26+ 走原生、16 走回退
- 校验：`scripts/local-typecheck.sh`（四路 typecheck + 测试目标 + 收录检查）

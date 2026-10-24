# MarkdownUI 的 Debug 局部优化尝试（已撤回）

曾在本地 `Vendor/swift-markdown-ui/Package.swift` 仅为 `MarkdownUI` target 的 Debug 配置增加 `-O -assert-config Debug`。主 App 的 `-Onone`、`DEBUG` 条件和调试符号设置保持原状；Release 以及 NetworkImage、cmark 等其他 target 没有新增编译设置。该尝试独立于无行内代码时使用系统 Text 绘制的优化，目前已精确撤回，主 App 与 MarkdownUI 恢复原 Debug 编译配置。

实际构建日志验证 MarkdownUI 参数为 `-Onone -O`（最终 `-O`），仍有 `-assert-config Debug`、`DEBUG` 和调试符号；主 App 仍为 `-Onone`。48 项 native 回归通过，但独立真实会话 8 次滑动未通过验收：加载窗口 display timestamp P95 为 16.667ms，超过 33.3ms 的占比为 4/124（3.226%），最大 callback arrival 间隔为 161.959ms；最慢 hosted 行测量总耗时为 43.669ms，其中 `sizeThatFits` 为 42.181ms。证据目录为 `/tmp/dsh-scroll-markdown-debug-o-smoke`。

前后加载内容量不同，无法据此计算精确加速比例；本次没有获得足以保留该编译设置的可靠收益证据，因此撤回该配置。以下保留当时的设计和限制说明，不代表当前启用。

`-O` 优化 MarkdownUI 模块中的 Swift 代码；`-assert-config Debug` 显式保留该模块的 Debug assertion 配置。它不会恢复 `-Onone` 的全部调试行为：依赖内部的单步、断点映射和局部变量可见性仍可能受优化影响。需要逐行调试该依赖时，可临时撤回这项 target 设置。

这里使用 SwiftPM 的 `unsafeFlags`。包含这类设置的 product 在被其他 Swift package 当作依赖消费时存在限制，因此它只适用于当前项目的私有 vendored 用法，不能作为通用上游包默认配置直接发布。

每次验证须保留真实编译日志，并核对 MarkdownUI 最终有效的优化参数为 `-O`，仍带 `-D DEBUG`、`-g` 和 `-assert-config Debug`；主 App 仍为 `-Onone`。不能仅凭 manifest 推断 Xcode 最终参数。此配置不是整个 Debug 包均为 `-Onone`，也没有把主 App 切换为 Release。

性能记录应标明源码版本、配置、实验开关和真实会话负载，并与之前全 `-Onone` 的结果区分。前后加载页或内容量不同时，不据此计算精确加速比例。是否改善滚动仍以独立构建后的 native 回归和真实会话测量为准；此说明本身不代表测试或性能验收通过。

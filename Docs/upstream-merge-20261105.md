# 上游合并记录（2026-11-05，1.7.12 → 1.7.15）

> 用户 2026-11-05 决定：**全合，含 Tailscale**（接受 IPA 体积从 14MB 涨到 ~60MB 的代价）。
> 本文记录**每项功能来自哪里**，供后续开源发布时写更新说明用。

## 合并范围

- 上游：`Clarklevis1995/dsh-mobile`，`24f12c3` → `3bf35b7`（**18 个提交**，259 个文件，+17862/−4479）
- 我方基线：`2007de4`（vc63 全部修复：抽屉删除、缓存三洞、眨眼胶囊、眼睛对比度、合流顺序）
- 合并策略：`merge`（保留双方提交历史，符合「长期跟随上游」的既定方针）

## 新功能清单（来源 = 上游）

| 功能 | 上游提交 | 涉及文件 | 备注 |
|---|---|---|---|
| 引用回复（quote） | `3bf35b7` | `ConversationView.swift`（`GatewayQuoteTextParts`、`draftSelection`、`.messageQuoteAction`） | 长按消息可引用，回复带原文；支持「移除引用」 |
| 日志导出 | `3bf35b7` | `LogExportView.swift`（新）、`androidApp/.../LogExportScreen.kt` 等 5 个 Kotlin 文件 | 按时间段导出运行日志并分享 |
| 生成图片预览 / 附件恢复 | `60397f3` | `shared/protocol/ToolImages.kt`（新）、`ConversationProjection` 的 `GENERATED_IMAGES` 分支 | 工具生成的图片独立成条、按调用关联去重 |
| 历史分页优化 | `fc12ae4` | `ConversationView`、`AppStore` | 加载历史时先加载更早一页再展示 |
| 设置导航保持在根路径 | `1fdb31b` | `RootView.swift`、`SettingsView.swift` | 修设置页返回后跳错层级 |
| UI 效果控件 + 可展开最近会话 | `60a19ad` | `SettingsView`、`WorkspaceView` | 新增动效开关 |
| Tailscale 组网 | `fbf8d7a`/`7b95085`/`ee192a1`/`60a19ad`/`1fdb31b` | `tsnetgo/`（Go）、`TailscaleTunnelController.swift`、`TailscaleTunnelScreen.kt`、`shared/tailscale/`（4 个 Kotlin） | **跨 5 个提交**，无法单独摘除；见下方专项说明 |
| WebRTC 连接选项 | `fbf8d7a` 等 | SPM 依赖 `webrtc 154.0.0` | 网关实验性 WebRTC，失败回落 WebSocket |
| 玻璃按钮 / 开关组件 | `ee192a1` | `DshGlassButton.kt`、`DshSwitch.kt`、`OceanMeshBackground.kt` | 安卓 UI 组件 |
| 工作区媒体预览 | `fdab38f` 等 | `WorkspaceMediaPreviewUi.kt` 等 5 个文件 | 图片/视频预览 |

## 修复类（来源 = 上游）

| 修复 | 上游提交 |
|---|---|
| Markdown 选区操作导致 StackOverflow 崩溃 | `376feba` |
| iOS 底部对齐 | `5b1cdd0` |
| 设置项行距 | `ad1bf36` |
| composer 高度聚合 + 视口回归夹具 | `866d3e6` |
| 删除过时代码 | `4fdab59` |

## Tailscale 专项（重点）

**为什么用它**：手机与电脑走虚拟内网直连，不依赖 Cloudflare 那一跳，连接更稳。

**为什么用 Go 不是自己写**：
- `tsnet` 是 Tailscale 官方 Go 库，专为「嵌进 App、不建 VPN、不占系统 VPN 槽位」设计
- 依赖 `wireguard-go` + `gvisor` 全套网络栈，用 Swift/Kotlin 重写等于从零实现 WireGuard（数人月工作量）
- 上游注释记录了真机踩过的坑（`tsnet.Loopback()` 当代理会 403、OkHttp 不支持带认证 SOCKS5），换方案要重踩

**构建要求（硬门槛，不是可选）**：
- Go 1.27.1+（实测用 1.27.3 通过）
- gomobile（`go install golang.org/x/mobile/cmd/gomobile@latest`）
- `pbxproj` 内建 `Build Tsnetgo XCFramework` 构建阶段，**每次编译 iOS 都会跑 `gradlew buildTsnetXcframework`**
- **找不到 gomobile 直接抛异常**，不是跳过 ⇒ 没装 Go 的机器完全编不了 iOS 包

**本机验证结果（2026-11-05 23:5x，AndroidCoder 实测）**：
- `BIND_EXIT=0`，产物 `/tmp/Tsnetgo.xcframework`，28s（缓存热）/ 约 4 分钟（冷启动含依赖下载）
- 体积 153M：真机 arm64 **51M** + 模拟器胖二进制 101M
- `Info.plist` Format 1.0 合规，Xcode 可链接
- 共解 5 层报错：brew 无 bottle → 官方 tarball 装 go1.27.3；proxy.golang.org 被墙 → `GOPROXY=goproxy.cn`；`xcrun/xcodebuild` Bus error → `/tmp/xcrun-shim` 拦截；lipo 内层委托 → 用工具链内层 lipo；gomobile 对多架构 sim 传同一路径两遍 → shim 去重

**环境留存**：`/Volumes/应用/dsh-workspace/tools/go`（277M）、`tools/gopath`、`tools/xcrun-shim/`（xcrun + xcodebuild 两个 shim）

## 合并冲突处理

**33 个文件冲突，约 250 个冲突块**，按类型分三类处理：

### 1. 并集（两边各加各的）——占多数
`pbxproj`（16 块）、`.gitignore`、`Info.plist`、`GatewayModels`、`Theme`、`HarnessAnimatedBackground` 等

### 2. 按 key 三方合并
`Localizable.xcstrings`（70 块）：用 Python 按 key 做三方合并
- 结果 **1070 条**（我方 917 + 上游 958 取并集）
- 统计：我方独有 150 / 上游独有 158 / 两边一致 759 / 同语言冲突取我方 2
- 校验：`localizations` 全部为字典形态（0 条异常，防 79 那次出包阻断复发）

### 3. 设计冲突（需决策）
| 位置 | 我方 | 上游 | 决定 |
|---|---|---|---|
| 对话页页头 | `DSHSegmented` 分段按钮 + 副行（模式·状态·第N轮） | `SessionPageIndicator` 小指示条（overlay 形态） | **两者都要**（用户 2026-11-05 原话：「先两个都要，等出了包我实际看到了之后再决定保留还是二选一」） |
| README | 品牌已改名 Orcarness、中文为主 | 仍叫 Whaleness、英文版 | **保留我方**（品牌不能退回） |
| 版本号 Info.plist | build 77 | build 38（上游自己的号） | 保留我方 |

### 4. shared 层逻辑冲突（双端共用）
`ConversationProjection.kt`：`user/message` 分支两边各重写
- 我方：群聊 Bot 转发 → `ASSISTANT` 气泡带 `senderName`
- 上游：工具生成图片 → `GENERATED_IMAGES` 独立条目
- 合成方式：先走我方 bot 转发判定，非 bot 转发再走上游图片逻辑，两者共存

## 待办 / 风险

1. **Gradle 升级**：上游 `9.1.0` → `9.3.1`（AGP 9.1.1 要求），本机需重新下载（官方源慢，走镜像）
2. **SPM 新依赖**：`webrtc 154.0.0`、`swiftmath 1.7.3` 需下载
3. **出包脚本要加 env**：`gradlew buildTsnetXcframework` 必须带 shim PATH，否则 iOS 编不出
4. ~~**合并后未编译验证**~~ → **已收口**：全量闸门 GATE_EXIT=0（2026-11-06 06:58，`/tmp/merge-gate-final4.log`；Chief 07:05 独立复跑同绿）。

## 上游提交逐条清单（merge-base `747939e8` → `3bf35b7`）

**数量口径**：`git rev-list --count 747939e8..3bf35b7` = **30**（含 5 个 merge 节点）；Chief 派单时说的 18 指非 merge 的功能提交口径。**逐条以本表为准**（2026-11-06 01:4x，`git log` 实读）。

| # | 提交 | 标题 | 改动量 | 功能归属 | 我方是否采纳 |
|---|---|---|---|---|---|
| 1 | `4582f50` | Add workspace session list and pause background animation | 20 文件 | 会话列表新屏（`WorkspaceSessionList.swift`）+ 滚动暂停背景动画 | **部分采纳**：新屏收；其「12 条折叠」与我们 75② 裁决冲突，见裁决 ④ |
| 2 | `4979475` | Send background notifications for pending agent requests | 17 文件 | 后台待审请求通知（`AgentUserNotificationManager`） | 采纳 |
| 3 | `7008c11` | Expand app with conversation management, model integrations, and UI | 111 文件 | 会话管理/模型接入大杂烩（含 harmony-validation 脚手架） | 采纳 |
| 4 | `fd8c614` | tsnetgo：把 tsnet 作为进程内节点嵌进 app | 5 文件 | Tailscale 核心（`tsnetgo/` Go 包，两种本地出口） | 采纳（Tailscale 专项起点） |
| 5 | `0948e9d` | 构建：经 gomobile 产出 AAR 与 xcframework，产物不签入 | 3 文件 | gomobile 构建链（AAR + xcframework，不签入） | 采纳（硬门槛，见专项） |
| 6 | `9713d38` | 诊断：两端共用同一组字段名与同一个开关 | 2 文件 | 隧道诊断字段两端对齐（`TunnelDiagnostics.swift`） | 采纳 |
| 7 | `00020a3` | Android：隧道控制器、本地偏好与节点的生命周期 | 6 文件 | Android 隧道控制器 | 采纳 |
| 8 | `523de16` | Android：连接出口可选经过内嵌节点 | 4 文件 | 连接出口可选经内嵌节点（OkHttp/Rtc transport） | 采纳 |
| 9 | `be9087c` | Android：隧道设置页与主机切换条上的状态标记 | 7 文件 | Android 隧道设置页 | 采纳 |
| 10 | `2ac5870` | iOS：节点封装、隧道偏好与控制器 | 6 文件 | iOS `NodeForwarding.swift`、隧道偏好与控制器 | 采纳 |
| 11 | `96ed1d1` | iOS：连接走本地转发器，界面入口与不变量测试 | 5 文件 | iOS 连接走本地转发器 + 入口 + 不变量测试 | 采纳 |
| 12 | `24f12c3` | Merge PR #77（embedded-tailscale 收口） | merge 节点 | Tailscale PR 合并点 | 采纳 |
| 13 | `376feba` | Fix StackOverflowError when a markdown selection action mode is destroyed | 1 文件 | Android Markdown 选区操作栈溢出崩溃修复（`DshMarkdownInteraction.kt`） | 采纳 |
| 14 | `ad1bf36` | Fix settings value row spacing | 1 文件 | 设置项行距修复 | 采纳 |
| 15 | `fbf8d7a` | Improve Android app architecture and user experience | 104 文件 | 架构重构（`GatewayProfile`/`LocalAppLog` 等） | 采纳 |
| 16 | `7b95085` | Improve Android app functionality and user experience | 28 文件 | 网络诊断（`GatewayNetworkDiagnostics`）、日志导出（`LogExportView`）、主题 | 采纳 |
| 17 | `ee192a1` | Improve Android app functionality and user experience | 37 文件 | 玻璃按钮/开关组件（`DshGlassButton`/`DshSwitch`/`OceanMeshBackground`）、iOS 隧道控制器 | 采纳 |
| 18 | `fdab38f` | Improve Android app functionality and UI | 27 文件 | **`abiApks` 多 ABI 变体引入**（R2UpdateManifest 真 bug 来源）、工作区媒体预览 | 采纳（bug 已修，见下） |
| 19 | `60a19ad` | Add UI effects controls and expandable recent sessions | 21 文件 | UI 动效开关 + **最近会话可折叠**（`collapsedRecentLimit=12`） | **部分采纳**：动效开关收；折叠逻辑与我们 75② 裁决冲突（裁决 ④） |
| 20 | `2c8c58e` | Merge PR #81（markdown actionmode 修复收口） | merge 节点 | — | — |
| 21 | `10784c8` | Merge main into PR #76 | merge 节点 | — | — |
| 22 | `5b1cdd0` | Fix iOS bottom alignment and localize permission labels | 7 文件 | iOS 底部对齐修复 + 权限标签本地化 | 采纳 |
| 23 | `866d3e6` | Fix composer height aggregation and stabilize viewport regression fixtures | 2 文件 | composer 高度聚合 + 视口回归夹具 | 采纳 |
| 24 | `e4120e7` | Merge PR #76 收口 | merge 节点 | — | — |
| 25 | `484aa88` | Merge remote-tracking branch 'origin/main' | merge 节点 | — | — |
| 26 | `4fdab59` | Remove obsolete code and simplify the project | 60 文件 | 过时代码清理 | 采纳 |
| 27 | `1fdb31b` | Keep settings navigation in the root path | 5 文件 | 设置导航保持根路径（修返回跳错层级） | 采纳 |
| 28 | `60397f3` | Add generated image previews and attachment recovery | 22 文件 | 工具生成图片预览 + 附件恢复（`ToolImages.kt`、`GENERATED_IMAGES` 分支） | 采纳 |
| 29 | `fc12ae4` | Load an older page before presenting the initial conversation | 6 文件 | 历史分页：先加载更早一页再展示 | 采纳 |
| 30 | `3bf35b7` | Add onboarding, chat, and model management features | 43 文件 | 引用回复（quote）+ 引导页 + 模型管理 | 采纳 |

## 我方自研功能对照（vc5x–vc63 一路，来源 = 我方）

> 这部分**不是本次合并带入**，是我方在上游基线上自研的，开源更新说明里要标「自制」。

| 功能 | 实现要点 | 关键提交 |
|---|---|---|
| 底部三 tab 栏架构 | `DshHomeTab`/`DshTabBar`，Android 全局导航 | vc5x 系列 |
| 首页抽屉删除 | 首页改直排 | `d0e5196` 等同批 |
| Bot 团队页 | 名册/实时状态/降级横幅（团队 feed） | vc5x–vc60 |
| 群聊合流终序按 seq 重排 | `interleaveByTime` 改 (seq, 原相对顺序) 稳定重排 | `07a5dd4` |
| 凭证失效自愈 | 401/4003 后自动重建凭证 | vc5x |
| R2 自更新 | R2UpdateManifest（GitHub 主路 + fallback） | `ac524d6` |
| 眨眼节奏 / 眼睛对比度 | glyph 分支 9 档 blinkSteps | `2007de4` |
| 运行中会话卡 | 运行态独立卡位 | vc6x |
| 工作区选择菜单 | 抽屉替代方案 | vc5x |
| 本地聊天搜索 | 会话内搜索 | vc5x |
| Orcarness 品牌与头像 | 全品牌替换 | 改名专项 |
| 发布脚本与版本规则 | ship-branch + build 号规则 | 运维系列 |

## 关键裁决记录（将来有人问「为什么」看这里）

**① 全合，含 Tailscale**（用户 2026-11-05）：接受 IPA 14MB → ~60MB（xcframework 153M → 真机 arm64 51M）。

**② 页头「两者都要」**（用户 2026-11-05 原话：「先两个都要，等出了包我实际看到了之后再决定保留还是二选一」）：
- 我方 `DSHSegmented`（分段按钮 + 副行）与上游 `SessionPageIndicator`（overlay 指示条）
- **合并树实证两者共存**：`ConversationView.swift:460`（DSHSegmented）与 `:484`（SessionPageIndicator，实现 `:3484`）同屏。

**③ `DshProductApp.kt` / `SettingsScreen.kt` 以我方为基重做**：
- 合并版曾被解成上游版 → 我方整个底部 tab 架构（`DshHomeTab`/`DshTabBar` 引用 7 处 → 0）与设置的 `embedded`/`bottomInset`/sheet 结构全部丢失
- 修复：以我方为基，上游新增的**日志导出 / 网络检测 / Tailscale 隧道**三页作为独立路由**嫁接**进我方结构（不是替换）

**④ `WorkspaceSessionSectionsDeviceTest.kt` 整套移除 —— 有意否决，不是漏做**：
- 该测试断言「12 条后折叠展开」（上游 `60a19ad` 的口径）
- 与我方 2026-11-03 75② 裁决直接冲突：**同工作区会话全列、不折叠、安全上限 500**（Chief 定 A 方案）
- 合并树实证：`WorkspaceView.swift:1351-1353` 保留我方口径（`prefix(500)` + 注释「不做折叠、不出任何折叠提示」）；上游折叠逻辑落在 `WorkspaceSessionList.swift`（02 屏「全部会话」新屏，`collapsedSectionLimit=5`/`collapsedRecentLimit=12`）——**两套并存，互不影响**
- 测试目标（`WorkspaceSessionListTests.swift`，21 文件随 `60a19ad` 进来）保留，但它测的是 02 屏新组件，不再约束会话主列表

**⑤ `HarnessAnimatedBackground` 取上游**：去掉 `isLight` 形参，改鲸鱼粒子方案。

**⑥ README / Info.plist**：README 保留我方（品牌 Orcarness、中文为主）；`Info.plist` build 77 保留我方（上游是 build 38）。

## 已知非问题（查过、判过，明确不动）

**`PluginsView` body 编译期 solver 耗时 ~10.8s**：`PluginsView.swift:13` 的 `var body` 是几十行嵌套视图，全量模块编译时该 getter 的 solver 耗时最高（2026-11-06 05:5x，Chief `-debug-time-function-bodies` 实测 11142ms；被报错的 `:153` 本身只 60ms，是预算被 `:13` 挤占后连累的）。按语义拆成 section 属性后复测仍 ~10.8s。**Chief 裁决（2026-11-06）：不上 `AnyView`**——AnyView 换掉的是 SwiftUI diffing 的视图 identity，代价真实且退化无声（日后往那块加状态就变掉帧问题）；10.8s 是 solver 编译期内部耗时，不是运行时卡顿，用户感知不到，闸门已绿非阻断。「拆到 1 秒内」当初只是判断拆分效果的参考值，对 opaque `some View` 本就不适用。**拆分保留、`-solver-expression-time-threshold=600` 保留**（上限非实际耗时，单文件实测 20s 编完）。下次看到 10.8s 不要再去折腾。

**`TailscaleTunnelView.swift` 不再拆 body**：同因 solver 超时，合并中途曾被脚本写坏（682 行 vs 上游 672 行），已恢复上游原版逐字节一致（2026-11-06 05:4x，iOSDev 复核：`diff /tmp/ttv-upstream.swift` 为空 + `swiftc -parse` EXIT=0）。Chief 裁决：solver 阈值是根治、拆 body 是绕，该文件保持上游原版不再动。

## R2UpdateManifest 真 bug（本次合并抓住并修复）

上游 `fdab38f` 引入 `abiApks` 多 ABI 变体：清单顶层之外多了按 ABI（arm64-v8a 等）的变体条目，`selected` 选中变体后：
- `sha256` 取自 **变体** ✓
- `downloadUrl` 却仍从 **顶层清单** 读 ✗
- 结果：**校验和与下载地址指向不同的包** —— 升级下载下来必然校验失败（或更糟：装了跟校验不对应的包）

修复（`androidApp/.../update/R2UpdateManifest.kt:33-37`，2026-11-06）：`sha256` / `downloadUrl` / `githubDownloadUrl` **三者都取自 `selected`**（上游原实现即 `selected.getValue("downloadUrl")`，我方合并版读回顶层才是 bug——上游注释原话如此，合并时以合并树注释为准）。抓它的用例：`R2UpdateManifestTest.selectsPreferredCompatibleAbiAndUsesItsOwnChecksum`。

## 验证状态（截至 2026-11-06 07:0x，全量闸门全绿）

| 项 | 状态 | 读数 |
|---|---|---|
| 全量闸门（合并树） | **全绿** | GATE_EXIT=0：`/tmp/merge-gate-final4.log`（11-06 06:58，iOSCoder 跑），Chief 07:05 独立复跑同绿（`/tmp/gate-verify.log`）。**注意**：`/tmp/merge-ios-gate-final2.log`（06:43）是测试调用点修复前的中间态（测试目标 8 错）、06:50 final3 卡安卓 buildTsnetAar，均已被 final4 取代，读旧日志勿误判 |
| iOS 四路 typecheck | **全绿** | App / App(-D DEBUG) / Widget / Widget(-D DEBUG) 各 0 错误（final4） |
| 第五路测试目标 | **全绿** | `-enable-testing -D DEBUG`（frontend + `-solver-expression-time-threshold=600`）：Tests 22 文件 0 错误、UITests 2 文件 0 错误（final4 :29/:31） |
| Android 闸门 | **全绿** | assembleDebug + compileDebugAndroidTestKotlin + testDebugUnitTest + shared:jvmTest（final4；01:2x 首绿，06:58 合并树终验） |
| 版本一致 | **绿** | 主 App 1.7.15 (82) ↔ Widget 1.7.15 (82)（final4） |
| 工程收录 / xcstrings / i18n | **绿** | 102/102 挂对 target；xcstrings 1076 条全字典；调用点本地化新增 0（final4） |
| KMP iOS 框架 | **全绿** | `:shared:linkDebugFrameworkIosX64` BUILD SUCCESSFUL（11-06 07:1x，gradle 终端，LINK_EXIT=0；compile/link 均 UP-TO-DATE＝当前源与上次成功构建输入一致，产物有效；此前 01:4x 失败 3m14s 的根因已由 `DEVELOPER_DIR=/tmp/FakeXcode.app/Contents/Developer` 修复） |
| R2UpdateManifest 修复 | **绿**（单测） | `selectsPreferredCompatibleAbiAndUsesItsOwnChecksum` |
| 设备级验收 | **未开始** | 真机装包后 |

## 待办 / 风险（承接骨架，补充）

1. ~~**KMP iOS 框架编译**~~ → **已收口**（2026-11-06 07:1x）：`DEVELOPER_DIR=/tmp/FakeXcode.app/Contents/Developer` 进 Gradle 环境即过，`linkDebugFrameworkIosX64` BUILD SUCCESSFUL（无 shim PATH 也行）。
2. **Gradle 升级**：上游 9.1.0 → 9.3.1（AGP 9.1.1 要求），本机需重新下载（走镜像）。
3. **SPM 新依赖**：`webrtc 154.0.0`、`swiftmath 1.7.3` 需下载；网络不通时 `-disableAutomaticPackageResolution` 走本地已解析包。
4. **出包脚本要加 env**：`gradlew buildTsnetXcframework` 必须带 shim PATH，否则 iOS 编不出（每台构建机都要装 Go 1.27+ + gomobile）。
5. **页头二选一**：出包后用户实际看效果再定（保留 `DSHSegmented`+副行 或 `SessionPageIndicator`）。
6. **开源发布注意**：Tailscale 依赖 tsnet（Tailscale 官方库）与 gomobile——第三方许可清单需补 Go 依赖（`third-party-notices.md` 待更新）。

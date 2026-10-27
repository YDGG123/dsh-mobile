# DSH 在 iOS 和 Android 设备本地运行的可行性调研

调研日期：2026 年 10 月 23 日。移动端代码基线：`ec916e14`；本机安装的 DSH 和上游根包均为 `0.2.1-rc.2`；相邻 Mobile Gateway 仓库版本为 `0.11.1`。

**OpenMinis 源码补充后的方案调整：** 如果目标包含完整 Shell、包管理和 Linux CLI，应优先验证 Android PRoot、iOS iSH ARM64 的 Linux 用户空间路线，并与嵌入式 Node 路线比较。iOS 的宿主子进程限制不适用于模拟器内部的 guest `fork/exec`；上一版把完整 Shell 排除出 iOS 首版的判断过于保守。具体执行链路与源码见 [OpenMinis 本地 Shell 实现分析](openminis-local-shell-research.md)。

原生嵌入路线仍可采用 **Node.js 24 加独立 mobile profile**，复用现有 Gateway 协议与原生界面。其主要改造点是系统能力提供者。Linux 用户空间路线则尝试在 guest 内运行 Linux 版 Node 和 DSH，以减少 Shell、文件系统和工具链的改写；DSH 的真实兼容性仍需两端验证。

这里的本地运行指：Agent 循环、工具调度、会话记录和工作区文件在手机内执行及存储，模型默认仍调用网络 API。完全离线推理是额外项目，不应作为本地 runtime 的第一阶段。

## 现有工程可以复用什么

当前运行链路是：

```text
手机原生界面 → KMP 状态与协议 → WebSocket → 桌面 Mobile Gateway → DSH
```

`shared/.../gateway/GatewayRuntime.kt` 管理连接、鉴权、请求关联、重连与事件；它不是 DSH 执行内核。`GatewayPlatformContracts.kt` 已抽象 `GatewayTransport`，适合继续承载本地 Host 的帧。Android 使用完整共享 Gateway Runtime，iOS 当前仍由 `Core/GatewayClient.swift` 管理网络，再接入 KMP Store。

相邻网关的 `lib/index.mjs` 注入 `webServer`、`typertGateway`、`connection` 和 `agentDefaultModel`；`lib/dsh-host-adapter.mjs` 已通过 `Connection.createPeer` 以及 Typert 的 `invoke/stream` 调用 Host。因此，复用网关需要补齐这些服务，不能只启动 `agent-loop` 就期待现有 App 可以连接。

本地接入可以继续复用 Conversation、Trajectory、历史分页、Question、Approval、文件传输和多主机隔离。当前网关使用 Session V4，适配层必须保留持久事件与临时 assistant stream 的区别，以及 cursor、订阅代次、历史格式版本和取消语义。不能把模型 token 直接拼成聊天文本来替代完整协议。

现有 `ARCHITECTURE.md` 的部分 Android 状态描述已落后于产品实现，本调研以当前代码和 README 为准。

## DSH 的实际运行依赖

上游根包声明 Node.js `^22.19.0 || >=24.0.0`。本机依赖树也存在要求 Node 22 的 `commander`、`undici` 和 `execa`。仅替换 npm 的版本检查不会使旧 Node 获得缺失的内置 API。[上游 package.json](https://github.com/deepseek-ai/deepseek-harness/blob/master/package.json)

下面的依赖由本机已安装的 npm 包、构建代码和类型声明核对，描述的是 `0.2.1-rc.2` 的具体实现：

| 模块 | 实际依赖或行为 | 移动端处理方向 |
| --- | --- | --- |
| `dsh-agent`、`dsh-agent-loop` | TypeScript 编译的 JS、Cordis 服务和工具调度 | 优先复用；仍需审计传递依赖 |
| `dsh-sdk-minimal` | profile 仍加载 subprocess、terminal、persistent bash、sandbox 和 JSONL persistence | 新建独立 mobile profile，不能直接当成无 Shell 内核 |
| `dsh-session-persistence-jsonl` | 原生 `flock`、worker threads、`node:zlib` 的 Zstd API | 优先移植文件锁并验证原有存储；必要时实现移动端 persistence 后端 |
| `node-addon-system` | 当前预编译平台只有 macOS 和 Linux，文件锁加载器只接受 `darwin/linux` | 增加 iOS/Android 原生实现和平台分发；macOS arm64 与 iOS arm64 不通用 |
| `dsh-session-query-sqlite` | 动态加载 `node:sqlite` | 使用完整 Node 构建，或显式替换查询实现 |
| `dsh-fs-local` | Node 文件系统、chokidar；Koffi 的部分用途属于 Windows 分支 | 限定 App 工作区，逐项验证 watcher 与路径语义；不必移植 Windows 专用分支 |
| `dsh-subprocess-local` | `child_process`、node-pty、系统进程检查 | 两端首版禁用；Android 后续独立实现受控执行器 |
| `dsh-tool-fs-search` | `@vscode/ripgrep` 可执行文件和 subprocess 服务 | 改为进程内搜索，或提供原生库接口 |
| `dsh-attachment-local` | `sharp` | 图片缩放与格式处理优先交给 Android/iOS 原生图像 API |
| `dsh-ptc-runtime-node` | `stripTypeScriptTypes` 及运行器相关能力 | 首版移除任意代码运行功能，后续另行验证 |
| `dsh-subagent-spawn-in-process` | 同一进程内的子 Agent | 不等于 OS 子进程；可后续复用并限制并发 |
| `dsh-credentials-local` | 本地文件与配置观察 | Provider 密钥改用 Keystore/Keychain 管理 |

`sdk-minimal` 的 profile 还默认选择 `danger-full-access`。移动端应独立定义权限语义，避免把这份配置直接带入 App。[上游 sdk-minimal 配置](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/bundle/sdk-minimal/cordis.patch.yml)

即使设置 `compression: none`，当前 persistence 模块仍在顶层导入 Zstd API；老 Node 可能在模块加载阶段就失败。这也是选择现代 Node 完整构建的原因之一。

## 嵌入引擎的选择

| 路线 | 对现有 DSH 的复用 | 主要成本 | 判断 |
| --- | --- | --- | --- |
| Node.js 24 移动库与 mobile profile | 高 | 原生扩展、系统能力、打包和生命周期适配 | 首选验证方向 |
| 官方 Node.js Mobile 18.20.4 预编译库 | API 模型接近，但版本不足 | 大量现代 Node API 和依赖回退 | 不作为当前 DSH 基线 |
| JavaScriptCore 或 QuickJS | 只能复用可移植 JS | Node 模块加载、fs、crypto、stream、网络等宿主层适配 | Node 路线失败后的备选 |
| 用 Kotlin/C++ 重写 Agent 内核 | 协议及设计可借鉴，运行行为需重建 | 持续跟进 DSH 的调度、压缩、权限和会话格式 | 首版投入较大 |
| 内嵌 Linux 用户空间，Android PRoot 与 iOS iSH ARM64 | 对 Shell 和 Linux CLI 的复用高 | 系统调用兼容、iOS 解释执行成本、原生模块与分发 | Shell 为核心时优先验证，参见 OpenMinis 补充调研 |
| 外置 Android Termux | 理论上更接近桌面环境 | 原生包兼容、独立安装和系统执行限制 | 适合技术验证或极客模式 |

QuickJS 是可嵌入 JS 引擎，不提供完整 Node 宿主环境。把 DSH bundle 成一个 JS 文件，也不会消除 `node:fs`、原生文件锁和进程 API 依赖。[QuickJS 官方文档](https://bellard.org/quickjs/quickjs.html)

### Node.js 24 移动库候选

`nodejs-mobile/nodejs-mobile` 官方发布页目前列出的最新核心库是 `18.20.4`，不足以直接承载当前 DSH。[官方发布页](https://github.com/nodejs-mobile/nodejs-mobile/releases)

社区分支 `fogtape/nodejs-mobile` 的发布页在 2026 年 10 月 22 日列出 `v24.22.1-0`，标记为预发布。仓库提供 Android `libnode.so` 和 iOS `NodeMobile.xcframework`，可以作为 Node 24 路线的 PoC 候选。这是第三方移动移植项目，不是 Node.js 官方移动发行版，也不是 DSH 已验证支持的平台。[候选仓库](https://github.com/fogtape/nodejs-mobile/tree/recipe)、[预发布记录](https://github.com/fogtape/nodejs-mobile/releases/tag/v24.22.1-0)

首次验证应选择 **full**，因为 lite 移除了 `node:sqlite` 和 TypeScript type stripping 等功能，容易额外引入兼容问题。候选项目的构建说明要求 Android 在 Linux 构建、iOS 在 macOS 构建；当前开发机可以负责 iOS，Android 原生 runtime 宜在 Linux CI 产出。该项目不同发布的功能和验证范围有所变化，应固定版本、源码 revision、归档 SHA-256 及许可证清单。[构建说明](https://github.com/fogtape/nodejs-mobile/blob/recipe/docs/BUILDING.md)

候选项目文档说明 iOS 使用 jitless V8，并以 JS 实现的 WebAssembly 兼容层支持 Undici 所需路径；Android 保留 JIT。这解决了一部分网络 API 的适配问题，但 SSE 长连接、取消、代理和图片上传必须用 DSH 的真实调用验证。文档也说明每进程预期只有一个 Node 实例，因此设计为一个 runtime 管理多个 session。[候选项目 FAQ](https://github.com/fogtape/nodejs-mobile/blob/recipe/docs/FAQ.md)

## 推荐的工程架构

```text
SwiftUI/UIKit 或 Jetpack Compose
                │
        现有 KMP Store 与协议
                │
        RuntimeHost 选择远程或本地
          ┌─────┴─────────────┐
          │                   │
    现有远程 Gateway     LocalRuntimeController
                              │
                       本地协议适配入口
                              │
                     嵌入式 Node.js 24
                              │
                      DSH mobile profile
                 ┌────────────┼───────────┐
              LLM API    会话持久化     本地工具与原生桥
```

`RuntimeHost`、`LocalRuntimeController` 和以下文件名均为建议新增的抽象，并非现有实现。

### 第一阶段复用本机 WebSocket

PoC 可以让本地 DSH 加载必要的 Host 服务和裁剪后的 Mobile Gateway，仅监听 `127.0.0.1` 的动态端口，App 自动取得端点并连接。这样可优先复用两端现有网络实现，先验证 DSH 内核能运行。

本地服务仍需每次启动生成的随机鉴权凭据，不能因为是 loopback 就关闭认证；绑定回环地址也不等于调用者身份验证。使用 App 内部引导传递凭据，关闭 LAN 广播、Cloudflare Tunnel、系统 helper、桌面重启和 npm 安装入口。App 应按稳定的本地 Host ID 隔离数据，不能以每次变化的端口作为唯一缓存身份。

这条方案必须包含网关所需的 Remote API、Connection、模型默认值和 WebServer 等服务。若这些服务牵入大量桌面功能，转为提取网关分发逻辑，而不是继续打包整个 Web profile。

### 后续可替换为进程内消息桥

运行稳定后，可把网关的请求处理和帧生成提取为独立模块，通过 C ABI、JNI 和 Objective-C++ 传递消息，省去本地监听端口。

Android 可以实现新的 `GatewayTransport`；iOS 先在 `GatewayClient` 上方建立可替换的 Host 通道接口，继续送入现有 Router/KMP Adapter。无需把 iOS 切换到完整 KMP Gateway Runtime 作为本地化的前置条件。

桥接保持有序、限流、背压、连接代次与 requestId 关联。不能在主线程执行 Node/libuv 事件循环；高频增量也不应让 UI 每个 token 都进行跨语言全量复制。本地通道不应被“没有 Wi-Fi”判定为无法连接；模型 API 的网络可用性应单独处理。

### 独立组装 mobile profile

保留 Cordis、Agent、Agent Loop、LLM Provider、工具注册、投影、压缩、必要的 Remote API、会话和审批服务。初始只启用一个活跃回合，限制上下文和工具输出大小，验证稳定后再增加并发及子 Agent。

建议通过宿主 API 或精简启动入口创建配置，避免 CLI worker 启动器、TTY、浏览器自动打开、用户任意配置 patch、热更新和桌面 profile 自动加载。具体保留的 Cordis 服务闭包要由 PoC 验证；`sdk-minimal` 可参考其组装方式，但不能直接使用。

会话持久化优先保留现有 JSONL/V4 实现，移植所需 `flock` 并核对 worker 和 Zstd 支持。如果更换后端，需完整实现 `SessionPersistence` 的接口、独占所有权、append 原子性、revision、错误处理和崩溃恢复。不能仅用一个数组或无锁 JSON 文件替代。

## Android 的实现边界

使用 NDK 编译并随 APK 打包 `libnode.so` 和 JNI 桥，在独立执行线程启动 Node。可以放入专用 `:localRuntime` Service 进程，减少 Node 崩溃直接影响 UI 的机会；默认同 UID 的独立进程仍共享 App 权限，不构成不可信插件的安全沙箱。

当前工程 `minSdk=24`、`targetSdk=36`。Android 10 对 target 29+ 的 App 禁止直接执行可写 App 目录中的文件，因此“下载 node、bash、rg 到 filesDir，再 chmod 执行”不能作为实现方案。此限制不等于禁止随 APK 打包的原生库或解释器执行脚本文本；可执行程序的打包、加载位置和 SELinux 行为仍需分别验证。[Android 官方说明](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)

文件工具先操作 App 内部工作区。用户通过 Storage Access Framework 选择的 `content://` 目录不能直接传给 Node `fs` 当作普通路径。首版采用导入副本与显式导出；若后续做原目录编辑，需要实现 URI 桥、持久授权、冲突处理和原子写回。

本地 Agent 不是连接外部设备。现有 Manifest 的 `connectedDevice` 前台服务类型不能自然沿用为通用本地计算任务；根据实际场景评估合法服务类型与声明。`dataSync` 也不能当成无限后台执行容器，Android 15+ 对相应后台服务有时限。首版以用户在前台启动和使用为目标，系统终止后依靠持久化恢复。[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)

所有新增 `.so`，包括 Node、桥接和原生扩展，都需要检查 16 KB 页面与打包对齐，并在相应设备或模拟器验证。[Android 16 KB 页面文档](https://developer.android.com/guide/practices/page-sizes)

完整 Shell 可作为后续 Android 专属能力：优先研究随包交付的受控执行器和工具白名单；Termux 可用于早期兼容性试验，但它的执行环境和 target SDK 策略不能直接套用到本工程。DSH 的平台分发、原生文件锁及 node-pty 在 Termux/PRoot 中仍需实测。[Termux 执行环境](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)

## iOS 的实现边界

通过 Objective-C++ 封装 `NodeMobile.xcframework`，由 Swift 调用 C ABI，在专用线程执行。使用 jitless 构建，不依赖特殊 JIT entitlement。不要把它当成 macOS 命令行程序：嵌入库不提供普通 iOS App 任意启动桌面子进程的能力。

首版禁用 bash、PTY、本地 stdio MCP 服务、桌面进程工具和依赖外部解释器的技能。文件搜索、编辑、图片处理可以实现为 App 内部工具。远端 HTTP MCP 与本地 stdio MCP 的依赖不同，后续可以单独评估前者。

运行数据放在 `Application Support`，缓存放在 `Caches`；用户文档通过系统选择器导入/导出。若直接编辑外部目录，需要处理 security-scoped URL、访问时长和文件协调，不能把获得授权的 URL 简化成一个永久有效路径。

App Store 2.5.2 对下载、安装或执行改变 App 功能的代码有限制。建议把经过核对的 runtime、DSH profile 和可执行插件随应用版本交付；任意 npm 插件市场、热更新执行引擎以及用户代码执行要按实际产品功能另行判断。内置解释器本身不能保证通过审核，教育类例外也不自动适用于本产品。[App Review Guidelines 2.5.2](https://developer.apple.com/app-store/review/guidelines/#software-requirements)

后台能力必须分版本设计：

- iOS 17–25：现有 `beginBackgroundTask` 只提供有限收尾时间。到期前持久化已提交事件、取消在途请求，把未完成回合标为中断，回前台再恢复。
- iOS 26+：可验证 `BGContinuedProcessingTask`，用于用户在前台发起、可报告进度且可取消的任务。系统允许这类任务继续进行网络和 CPU 工作，但仍可能终止；它不是定时唤醒或永久守护进程。

本地任务在后台被终止时，应明确区分“恢复会话”和“继续原网络请求”。已产生副作用的工具不能盲目重试；审批等待、执行中的工具和未提交的 assistant stream 也需要各自的恢复规则。[Apple 长任务说明](https://developer.apple.com/documentation/BackgroundTasks/performing-long-running-tasks-on-ios-and-ipados)、[任务终止与进度](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask?changes=_2)

## 首版能力范围

下表是建议的产品目标，不代表已经在设备验证成功。

| 能力 | Android 首版 | iOS 首版 |
| --- | --- | --- |
| DSH Agent 循环和网络模型调用 | 保留 | 保留 |
| Session V4 历史和流式轨迹 | 保留 | 保留 |
| 读取、搜索、编辑 App 工作区文件 | 保留，替换系统依赖 | 保留，替换系统依赖 |
| Question、Approval、停止当前回合 | 保留 | 保留 |
| bash、PTY、Linux 构建工具 | 原生嵌入方案延后；PRoot 路线另行验证 | 可通过 iSH guest 实现，兼容性与资源成本另行验证 |
| 任意 npm 插件安装 | 延后，按分发渠道评估 | 不纳入首版 |
| 纯提示词技能和声明式配置 | 限定来源与能力后保留 | 限定来源与能力后保留 |
| 子 Agent | 后续验证进程内实现 | 后续验证进程内实现 |
| 精确时间的全天候本地定时任务 | 不承诺 | 不承诺 |
| 关闭 App 后持续执行 | 不承诺 | 不承诺 |
| 完全离线大模型推理 | 另立阶段 | 另立阶段 |

Provider API Key 将从原来的桌面职责变成手机职责，应由 Keychain/Keystore 保护。JS runtime 只在需要时获取凭据，禁写日志与会话记录。OS 沙箱只隔离其他 App；对同一 App 内的 Node 工具，仍要限制工作区路径、符号链接逃逸、可读目录及网络能力。工具白名单也不能阻止任意 Node 插件绕过工具层访问 `fs`，因此只装载随包交付且可信的可执行代码。

## 分阶段验证与投入

以下为单名熟悉本仓库与原生桥接的工程师的粗估，按阶段通过后再决定投入。它们不是已测得的工期，未包括商店审核等待和完整桌面工具生态移植。

| 阶段 | 工作与产物 | 通过条件 | 粗估投入 |
| --- | --- | --- | --- |
| 引擎与模块兼容性 | 两端最小宿主、固定 Node 24 full、DSH 模块依赖清单 | iPhone 与 Android 真机能启动；HTTPS/SSE、crypto、worker、Zstd、所需 SQLite API 及原生锁路径验证 | 3–5 人日 |
| 最小 Agent 与协议 | mobile profile、一个 Provider、只读/写文件工具、本地网关 | 真正完成“读文件→提出修改→审批→写回→记录历史”；取消和重启恢复通过 | 5–10 人日 |
| 接入现有产品 | 本地 Host、模型配置、安全存储、缓存隔离、能力显隐、导入导出 | 两端原生 UI 完整跑通；远程模式回归；离线可打开本地历史和工作区 | 5–10 人日 |
| 稳定性与发布准备 | 内存、耗电、后台中断、恢复、库对齐、签名和许可证 | 实机覆盖通过，无数据损坏；能力和分发策略明确 | 5–10 人日 |

累计约 18–35 人日是受限首版的规划量级。如果文件锁、Host 服务闭包或 iOS 运行库验证失败，需要先重新估算，不应继续按此工期承诺。

第一个兼容性阶段应完成以下有判定价值的实验：

1. 在无调试器的 iPhone Release 构建和 Android arm64 真机启动 Node；验证启动、App 前后台切换和单实例行为，不能只测模拟器。
2. 在 runtime 内逐个导入实际保留的 DSH 模块，记录缺失的内置 API、平台判断及原生模块；验证 full 构建的 `node:sqlite`、Zstd 和 worker 行为。
3. 连接一个真实模型 Provider，验证 SSE 流式输出、取消、超时、断网、重连和 HTTPS 证书。嵌入 Node 的 TLS 信任配置可能与系统 URLSession/OkHttp 不同。
4. 验证文件锁、事件 append、突然终止、截断恢复、迁移和同时打开；恢复后持久序号不重复，也不把临时 token 当作提交记录。
5. 从现有 App 连接本地网关，验证 session create/follow、历史分页、文件读写、审批、取消；让 unsupported 能力显式不可用。
6. 记录冷启动到 ready、首 token、峰值内存、长历史加载和前台连续运行的电量/温升。iOS 同进程包含 UI、KMP 和 V8，不能仅以 JS heap 作为整个 App 的内存预算。

如果现代 Node 在 iOS 的 Release 真机稳定运行，且所需系统服务能被可维护地替换，就继续此路线；如果必须大规模修改 Node 本体或每次升级都修补众多底层依赖，再评估 JavaScriptCore/QuickJS 宿主层，或抽取可移植 Agent 核心。

## 与完全离线推理的关系

设备本地 runtime 可以把网络模型作为 Provider，已经能够实现“无需桌面主机”。若进一步要求断网执行，需要增加本地模型 Provider、模型文件管理、推理引擎，以及 tool calling 和上下文长度适配。届时再按目标机型评估 llama.cpp、MLX/Core ML 或 Android 推理后端；本次不对具体模型、速度、包体和内存作未经实测的承诺。

## 验证状态

本次完成仓库代码、相邻网关实现、本机 DSH npm 包及上述一手文档的核对，产物为可行性结论和验证计划。尚未集成或执行移动端 Node runtime，未验证 DSH 在 iOS/Android 真机可运行，也未修改产品代码。

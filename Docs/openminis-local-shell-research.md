# OpenMinis 本地 Shell 实现分析

调研日期：2026 年 10 月 23 日。源码固定到 OpenMinis/OpenMinis 的 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`；其 iSH 子模块为 `e6521d9cfe9cd48f19917fc3b23aea622ba358b4`，PRoot 子模块为 `1b444ee1cb2f4896c08166e9a12c905af5416f1c`。

OpenMinis 把 Alpine Linux 用户空间装进 App，Agent 通过原生执行协调器调用其中的 Shell。**iOS 使用 ARM64 iSH 模拟器，Android 使用 PRoot 及专用 ELF loader。** 这条路线值得作为 DSH 完整 Shell 模式的优先验证对象；它与直接嵌入 libnode 的系统能力边界不同。

## iOS 通过 iSH 实现 Linux 进程

执行链路是：

```text
Swift Agent 的 shell_execute
  → ISHExecutionCoordinator
  → ISHShellExecutor
  → iSH guest task 与 do_execve
  → /bin/sh
  → Alpine Linux ARM64 程序
```

`deps/build_ish.sh` 使用 `kernel=ish`、`engine=asbestos` 和 `guest_arch=arm64` 构建 `libish.a`、`libish_emu.a` 与 `libfakefs.a`，把 Linux 系统调用实现、CPU 解释器和虚拟文件系统静态链接到 App。rootfs 准备脚本下载 Alpine aarch64，并转换成 fakefs 使用的布局。[构建脚本](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/deps/build_ish.sh)、[rootfs 准备脚本](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/deps/prepare_alpine_rootfs.sh)

Asbestos 使用 threaded-code interpreter：把 guest 基本块解码成预先编译好的 ARM64 gadget 函数指针与操作数序列，再依次调用。源码中历史性的 `jit` 命名不表示宿主运行时机器码生成。该后端明确说明不申请可执行内存、不动态生成机器码，因此不能把它与要求 iOS JIT entitlement 的原生动态编译器混为一谈。[iSH ARM64 后端说明](https://github.com/OpenMinis/ish-arm64/blob/e6521d9cfe9cd48f19917fc3b23aea622ba358b4/README_arm64.md#key-changes-from-upstream)

`ISHShellExecutor.m` 通过 `become_new_init_child()` 创建 guest task，接入 stdin/stdout/stderr 管道，再调用 iSH 的 `do_execve()` 和 `task_start()`。这些是模拟 Linux 内部的进程操作，不是让 iOS 的系统内核直接执行 Linux ELF。[进程创建与执行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/ios/iSH/ISHShellExecutor.m#L368)

当前 `ISHExecutionCoordinator` 每次命令创建一个新的 `/bin/sh`，把脚本内容写入 stdin，接收输出回调、退出码并处理超时和进程组取消。`fs_context` 标记随 guest 子进程继承，`MinisFsRouter` 用它把工作区、附件等 guest 路径映射到对应会话的宿主目录。[命令协调器](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/ios/Agent/ISH/ISHExecutionCoordinator.swift#L26)、[会话文件路由](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/ios/Agent/ISH/MinisFsRouter.swift)

仓库的 `ios-sandbox-ish-summary.md` 仍描述全局 FIFO 和提示符检测；当前执行代码已经采用独立 guest shell 与管道，不应照旧文档设计串行终端协议。

### Node 在 iSH 中也做了专门适配

iSH fork 的 `kernel/exec.c` 会识别名为 `node` 的程序，注入 `--jitless`、`--no-lazy`、内存与并发相关选项；存在相应文件时，还会预加载 WebAssembly/fetch 兼容脚本，并使用 `/lib/zero_free.so`。因此，“可以运行 Node”依赖一组运行器和 rootfs 兼容处理。[Node 执行适配](https://github.com/OpenMinis/ish-arm64/blob/e6521d9cfe9cd48f19917fc3b23aea622ba358b4/kernel/exec.c#L927)

其 README 列出 Node.js 22 等可运行软件，但不能由此断言当前 DSH 已受支持。需要验证具体 Node 版本是否满足 DSH 的 `22.19+ / 24+`、网络流和取消、worker、Zstd、SQLite，以及 Linux 原生扩展。对于 iSH，V8 解释 JavaScript，加上 ARM64 guest 指令解释，会形成额外执行成本，必须测量 DSH 的启动和长期内存占用。

## Android 通过 PRoot 和 ELF loader 执行

执行链路是：

```text
Kotlin Agent 的 shell_execute
  → ExecutionCoordinator / FreshProcessShell
  → ProcessBuilder 启动 nativeLibraryDir/libproot.so
  → PRoot 跟踪系统调用及路径，启动专用 loader
  → Alpine aarch64 /bin/sh
  → Linux ARM64 CLI
```

PRoot 为进程提供 rootfs 路径转换、绑定目录和 fake root 视图；同架构 Linux 程序使用设备 CPU 与 Android 的 Linux 内核，不经过 iOS 那样的 CPU 指令解释。`-0` 是伪装的 guest root 身份，不会赋予 Android 系统 root 权限。

`PRootKernel.buildProotCommand()` 组装 `-0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w /root` 和会话目录绑定，最后执行 `/bin/sh -c ...`。hardlink 转 symlink 是为了处理 Android 文件系统权限造成的包安装失败。[PRoot 启动参数](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/PRootKernel.kt#L663)

### 可写 rootfs 为什么仍能运行程序

OpenMinis 没有靠把 target SDK 降到 28 实现执行。当前配置是 target 35，Manifest 设置 `extractNativeLibs=true`，`RootfsManager` 从 `applicationInfo.nativeLibraryDir` 取得 `libproot.so`。[Gradle 配置](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/build.gradle.kts#L33)、[Manifest](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/AndroidManifest.xml#L182)、[运行器路径](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt#L43)

关键是三个以 `.so` 名称随 APK 打包的 ELF 可执行文件：`libproot.so`、`libproot-loader.so` 和兼容 32 位程序的 `libproot-loader32.so`。它们由安装器放入 nativeLibraryDir，而不是从 App 可写目录直接执行。PRoot 的 `execve` 处理会解析目标 ELF、准备映射信息，并将真正执行的文件替换成 loader；loader 负责装载 guest 程序。宿主通过 `PROOT_LOADER` 环境参数指定已安装的 loader。[执行替换逻辑](https://github.com/OpenMinis/proot/blob/1b444ee1cb2f4896c08166e9a12c905af5416f1c/src/execve/enter.c#L689)、[loader 参数](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt#L291)

这解释了上一轮关于 Android 可执行文件限制的适用范围：直接 `execve(filesDir/程序)` 会受限，但随包安装的 PRoot/loader 可以采用另一条加载链路运行 rootfs 内程序。它没有取消 App 的 UID、权限和系统安全策略。

### 当前代码比构建文档多了一层兼容处理

`deps/build_proot.sh` 当前会编译 PRoot 主程序，但明确禁止用 fork 自编译的 loader 替换现有版本；实际固定使用 Termux `proot 5.1.107-70` 的 loader 二进制，并校验 SHA-256。脚本记录，自编译 loader 在部分设备可运行，在另一些设备 guest 第一条指令即崩溃，且对应 loader 补丁尚未完整移入 fork。

因此，BUILDING 中“全部从源码构建”不足以描述当前 loader 的实际来源。若用于 DSH，应先补齐可复现的 loader 来源、构建补丁和设备验证，再决定是否维护这条分发链路。[当前 loader 安装与校验代码](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/deps/build_proot.sh#L352)

### 命令执行与交互终端是两种通道

当前默认 `ShellExecutionStrategy.FRESH_PROCESS` 每条命令拥有独立 PRoot 进程和输出管道，runner 通过状态文件报告命令退出码。旧的 `PersistentShell` 仍在代码中，但它依赖 stdout 中的完成标记，容易出现分块识别、超时输出串入下一命令等问题。[默认策略](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/ShellExecutionStrategy.kt#L83)、[独立进程执行器](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt)

交互终端通过 JNI 包装 `forkpty()`，连接 PTY master 读写和窗口尺寸操作；这与 Agent 的单次命令执行接口分开。[PTY 原生桥](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/cpp/pty_bridge.c)

## 系统能力通过 Native Offload 暴露

Shell 里的日历、浏览器或媒体命令不必都在 Linux 内实现。iOS 修改版 iSH 拦截注册命令的 guest exec，再派发给原生 handler。Android 的 PRoot 扩展通过 Unix socket 把命令参数、环境和工作目录传给 Kotlin `NativeOffloadServer`，原生处理后返回结果与退出状态。[Android Native Offload](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/NativeOffload.kt)

这套设计适合借鉴为 DSH 的原生能力桥：保留 Linux CLI 的调用形式，由手机平台处理权限与系统 API。PRoot 的路径转换和 iSH 的 guest 环境也不能自动作为所有不可信代码的充分隔离边界；暴露哪些目录、网络和原生 handler 仍需单独约束。

## 对 DSH 本地 runtime 的影响

如果需求重点是 Shell、包管理和开发工具，建议先验证 **把 Linux 版 Node 和 DSH 整体放在 guest 内运行**：

```text
现有 Whaleness App
  → 本地 Gateway WebSocket
  → guest 内 DSH Host 与 Mobile Gateway
  → guest 内 Node、Shell、文件和工具链
```

这种方案有机会直接保留 DSH 的 subprocess、bash 和 ripgrep，减少每个工具的重写。Android 使用 PRoot，iOS 使用 iSH ARM64。它仍属于待验证方案，OpenMinis 自身的原生 Agent 调用 Shell 并不证明 DSH 已能完整运行。

最先验证 Node 版本和 SSE，其次验证文件锁、Linux/musl 原生扩展、node-pty、会话恢复与停止进程组。DSH 依赖的 Linux sandbox 能力也需要单独检查：guest 内显示 `process.platform=linux` 不代表 Landlock、namespace 或其他内核能力均存在。不能因探测失败就自动把所有工具提升为无约束执行。

另一条可比较路线是：DSH 跑在宿主嵌入式 Node，Shell 工具经桥接交给 iSH/PRoot。这能减少 iOS 中整个 Node/DSH 被解释执行的成本，但需要同时统一两侧的 cwd、文件路径、环境、超时及取消，不能只把 `bash` 命令字符串发到另一个文件系统。

直接引入 OpenMinis 代码或其引擎还需先核对许可证：项目声明 iSH 为 GPL-3.0 并带 iOS 分发例外，PRoot 为 GPL-2.0；“可参考架构”和“可直接复制代码或链接库”应分开评估。[第三方许可证清单](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/THIRD_PARTY_LICENSES.md)

本次完成固定 revision 的源码核对，未构建 OpenMinis，也未实测 DSH 在其 guest 中运行。该证据足以把 Linux 用户空间提升为候选主线，尚不足以承诺性能、全部系统调用兼容或 DSH 发布可用性。

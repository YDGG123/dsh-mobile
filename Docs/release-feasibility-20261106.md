# 合并后出包可行性报告（ReleaseBot，2026-11-06 01:46–02:0x 实测）

> 范围：只判「能不能出包、怎么出、卡在哪」。代码未合完，本文不做任何构建动作。
> 环境基线（01:46 实测）：`xcodebuild -version` EXIT=0（Xcode 26.3 / 17C529）；
> **`xcrun -f ld` Bus error 10**（无绕过时 100%）——11-03 的 dyld 缓存修复已失效，坏页问题回归。

## 1. 环境总开关：DEVELOPER_DIR shim（已验证可用，所有工具链入口都要带）

```
export DEVELOPER_DIR=/tmp/FakeXcode.app/Contents/Developer
```

- 原理（11-03 定案）：`/usr/bin/xcrun` 尊重 DEVELOPER_DIR；FakeXcode 里 xcodebuild 是只接管
  `-find` 的 shim，其余工具软链真 Xcode。Chief 02:1x 复验 `-f ld` 直答真实路径。
- **`/tmp` 重启即清**。开工前必查：`ls /tmp/FakeXcode.app/Contents/Developer`；
  不在则用仓库内 `/Volumes/应用/dsh-workspace/tools/xcode-shim/rebuild-xcode-shim.sh` 重建（持久保存）。
- **需要此变量的入口清单（实测/推断标注）**：
  - `xcodebuild archive`（81 起 archive 走它，必经）
  - 本机 Gradle `:shared:linkDebugFrameworkIosX64`（Kotlin/Native 内部调 `/usr/bin/xcrun -f ld`；Chief 02:0x 实测本机必崩）
  - `gomobile bind`（内部调 xcrun 找 clang/SDK——推断，未实测）
  - `scripts/local-typecheck.sh`（swiftc → 间接 xcrun）
  - **写进出包脚本的必要性**：`scripts/ship-branch.sh` / `scripts/r2_release.sh` 当前 grep 无
    PATH/DEVELOPER_DIR 处理（01:5x 实读）⇒ 出包前需在脚本头或调用方 export，否则换机/重启必踩

## 2. Tailscale iOS 构建硬门槛（pbxproj `Build Tsnetgo XCFramework` 阶段）

现状（01:5x 实读 merge 树）：
- pbxproj `A9FC00000000000000000004` shellScript = `set -eu; cd "$SRCROOT"; "$SRCROOT/gradlew" buildTsnetXcframework --quiet`
  —— **`set -eu` ⇒ 任务失败 = 整个 archive 失败**，不是跳过。
- 任务定义（root `build.gradle.kts:19` Exec）：`scripts/with_go_toolchain.sh gomobile bind -target=ios,iossimulator -o build/tsnetgo/Tsnetgo.xcframework`
- **gomobile 定位规则**：`$GOPATH/bin`（缺省 `~/go/bin`）→ `/opt/homebrew/bin` → `/usr/local/bin`。
  **三处实测都没有 gomobile**（01:5x ls 均不存在）；真身在
  `/Volumes/应用/dsh-workspace/tools/gopath/bin/gomobile`（02:0x 实测可执行，但裸跑报「go not in PATH」）。
- **go 定位规则**：with_go_toolchain.sh 只找 `GOROOT/bin`、`/opt/homebrew/bin`、`/usr/local/go/bin`、
  `/usr/local/bin` —— **`tools/go/bin`（go 1.27.3，01:46 实测在）不在兜底列表里**。

结论：**Xcode 从 Finder/IDE 启动（无终端 env）时该阶段必失败**；从终端跑必须
`export GOPATH=/Volumes/应用/dsh-workspace/tools/gopath` + `PATH=tools/go/bin:$PATH` + `DEVELOPER_DIR=…`。

**实测结论（02:0x 两轮真跑，修正本报告初稿的「预置 UP-TO-DATE」方案——不可行）：**
- Exec 任务**首跑无成功历史必执行**，预置产物不跳过（01:5x 预置后仍 executed 实证）。
- 无 shim：gomobile 内部 `xcrun --find clang` 即 Bus error（秒级失败）。
- **带 DEVELOPER_DIR shim：xcrun --find 过、两个真机/模拟器切片编译全部成功**，
  最后死在 `xcodebuild -create-xcframework`（真 xcodebuild 本体 Bus error）——
  **shim 只救 -find，救不了 xcodebuild 子命令**（01:5x/02:0x 两轮日志 /tmp/tsnet-*-test.log、/tmp/tsnet-withshim.log）。
- 但 **2026-11-05 23:54 AndroidCoder 同一任务全程成功过**（/tmp/Tsnetgo.xcframework 153M 实证）
  ⇒ 本机环境**时好时坏**（坏页按需 page-in）：好窗口全链路通，坏窗口秒崩。

**建议方案（Chief 02:1x 已落地更优解，本条修正）：**
- **Tsnetgo 阶段已改「产物在即跳过」**：pbxproj 去掉 alwaysOutOfDate=1、加 inputPaths、
  shellScript 改为存在即 exit 0（`ensure-tsnetgo.sh`，含 Info.plist/AvailableLibraries 校验，
  反向测试齐）；ship-branch.sh 已挂调用。**Go 源码不变 ⇒ 不需要重试**，/tmp/Tsnetgo.xcframework
  （153M）已预置生效；仅 `tsnetgo/*.go` 变更时才需要好窗口真构建。
- **本地 archive 其余阶段仍是重试制**（xcodebuild 本体 Bus error 与该阶段无关）。`xcrun -f ld` 作 5 秒探针（崩=坏窗口，别开长构建）；好窗口下 Tsnetgo 阶段
  真构建（go+gomobile+DEVELOPER_DIR+GOPATH 全带，成功实证过）；/tmp/Tsnetgo.xcframework（153M）
  保留作备份产物（拷回 build/tsnetgo/ 可供 Xcode 直接链接，绕过该阶段——需 iOSCoder 确认
  pbxproj 对该路径的引用方式）。
- **云端：Actions runner 无 dyld 坏页，是坏窗口期的唯一稳定出包路**；workflow 需补 Go/gomobile（见 §7）。
- **B（云端）：GitHub Actions macos-26 需加 Go 环境**：现有 `ios-16.1-trollstore.yml` 无 Go 步骤
  （01:5x 实读），跑 `gradlew buildTsnetXcframework` 会死在 gomobile 缺失。
  补 `setup-go` + `go install gomobile` + `GOPATH` 导出，约 10 行 workflow 改动；无 secrets 依赖。

## 3. SPM 新依赖（合并树 Package.resolved 实读 01:5x）

```
swiftmath 1.7.3  https://github.com/mgriebling/SwiftMath.git   ← 源码包，DerivedData checkouts 已有（77 批解析过）
webrtc   154.0.0 https://github.com/stasel/WebRTC.git          ← DerivedData checkouts/artifacts 均无（02:0x 实读）
```
- SwiftMath：本地缓存命中，`-disableAutomaticPackageResolution` 可走（同 73-81 口径），低风险。
- webrtc：**结论明确——本地可解析，不阻断**。01:5x 实测 `git ls-remote https://github.com/stasel/WebRTC.git` EXIT=0（源仓库可达）；resolved 条目 kind=remoteSourceControl（源码包，SPM 检出后按其 Package.swift 拉工件）。代价：首次解析要拉 WebRTC 大工件，慢且网络间歇需重试；坏窗口期与 dyld 无关（纯网络），重试即可。

## 7. 云端 workflow 具体补丁（macos-26 runner 无 dyld 坏页 = 坏窗口唯一稳定出包路）

`ios-16.1-trollstore.yml` 在「安装 JDK 17」步之后插入：

```yaml
      - name: 安装 Go + gomobile（Tsnetgo 构建阶段需要）
        uses: actions/setup-go@v5
        with:
          go-version: '1.27'
      - name: 安装 gomobile 并初始化
        run: |
          go install golang.org/x/mobile/cmd/gomobile@latest
          "$(go env GOPATH)/bin/gomobile init"
          echo "$(go env GOPATH)/bin" >> "$GITHUB_PATH"
          echo "GOPATH=$(go env GOPATH)" >> "$GITHUB_ENV"
```

配套两件：
1. `scripts/with_go_toolchain.sh` 无需改——runner 上 `setup-go` 已把 go 放进 PATH，脚本首分支即命中；
   gomobile 由 GOPATH/bin 定位（任务定义 :38 的 firstOrNull 首选项）。
2. **必须确认 tsnetgo 源码在触发的分支上**（dispatch 的 ref 含 tsnetgo/ 目录——合并树有，checkout@v4 带 submodules recursive 覆盖）。

剩余差异：runner 的 SPM 解析走网络（webrtc 大工件首次下载，GitHub→GitHub 快）；产物经
upload-artifact 取回后按我方八条校验（版本/aapt2 不适用 iOS，用 PlistBuddy）。

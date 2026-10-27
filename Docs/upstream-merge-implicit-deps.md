# 合并上游时的「隐含依赖」模式（2026-10-27 合并 26 提交的经验）

> 场景：把上游 `origin/main`（Clarklevis1995/dsh-mobile，26 提交 / 146 文件）合进
> `integrate/upstream-ec916e1`，**保持部署目标 iOS 16.0**（上游跟到 16.1）。
> 结果：`d5d0510`（23 冲突全解）→ `cab86d0`（闸门全绿）→ `6f0d4fa`（测试对齐）。

---

## 核心教训：合并不只是「解冲突」

**23 个冲突全部解决、`git diff --diff-filter=U` 归零，不等于能编译。**

这次合并后跑闸门，暴露出 **4 类「上游新代码依赖、但我们没引入」的隐含缺口** ——
它们**不在冲突清单里**（因为是我方独有文件、或冲突已取我方版本），
只有跑闸门 / XCTest 才会现形。

| # | 缺口 | 表现形式 | 为什么冲突清单抓不到 |
| --- | --- | --- | --- |
| 1 | **pbxproj 14 个 23 位非法 ID** | 文件「看着登记了、实际挂不上 target」，代码等于不存在 | 手工生成 ID 时长度写错，git 认为这是「已解决」的干净文件 |
| 2 | **`DshEmptyState` 缺失** | `cannot find 'DshEmptyState' in scope`（多处调用） | `Theme.swift` 是整文件冲突，取我方为基座 → 上游新增的 struct 没进来 |
| 3 | **`DeepOceanBackground` 缺参数** | `argument passed to call that takes no arguments` | 我方 `Theme.swift` 版本没有 `prefersOpenLightArcs`；上游调用点已经带了这个参数 |
| 4 | **`AgentLiveActivityManager` 的 extension 缺可用性标注** | `'AgentActivityAttributes' is only available in iOS 16.1 or newer` | 上游新增 `private extension Activity where Attributes == AgentActivityAttributes`，部署目标 16.0 时类型不可用 |

---

## 每类缺口的识别方法（照着做，别靠运气）

### 1. pbxproj ID 非法 → 跑收录闸门

```bash
python3 scripts/check-project-membership.py
# 期望：✅ 磁盘 N / 工程 N，全部挂在正确的 target
```

**这是唯一能抓「磁盘有、工程没挂上」的检查。** 编译都可能不报错
（文件不在 target 里 = 代码不参与编译 = 不报错，但功能静默消失）。

自查非法 ID：
```bash
python3 - <<'PY'
import re, pathlib
s = pathlib.Path("DeepSeekHarnessMobile.xcodeproj/project.pbxproj").read_text()
bad = [i for i in re.findall(r'(?<![0-9A-F])([0-9A-F]{20,28})(?![0-9A-F])', s) if len(i) != 24]
print("非 24 位 ID 数:", len(bad))   # 必须为 0
PY
```

**教训**：合并时手工生成 pbxproj ID 极易错（23 vs 24 位）。
修的时候**必须带边界锚定**，否则 `CF205…001` 和 `CF205…0010` 会互相误伤：

```python
# 错：s.replace(old, new) —— 会命中更长 ID 的前缀
# 对：re.sub(r'(?<![0-9A-F])' + old + r'(?![0-9A-F])', new, s)
```

### 2./3. 我方为基座的整文件冲突 → 逐符号核对上游新增

`Theme.swift`、`HarnessAnimatedBackground.swift` 这类**整文件冲突**（两边内容完全不同），
取我方为基座时必须**反向检查上游新增了什么**：

```bash
# 对比两边的声明清单，找「上游独有」
git show b0bf36f:path/to/File.swift | grep -oE "(struct|enum|func) [A-Za-z]+" | sort -u > /tmp/o.txt
git show origin/main:path/to/File.swift | grep -oE "(struct|enum|func) [A-Za-z]+" | sort -u > /tmp/t.txt
comm -13 /tmp/o.txt /tmp/t.txt        # 上游独有的 → 逐个判断要不要补
```

**这次就是漏了 `DshEmptyState`（结构体）和 `DeepOceanBackground.prefersOpenLightArcs`（属性）。**

> ⚠️ **我犯过的一个判断错误**：当时看到上游的
> `HarnessBackgroundStyle.resolve(size:isPad:)` 在合并树里「没有调用方」，
> 就判断「不必引入」。**实际调用方是 `DeepOceanBackground`**——它也在上游版本里，
> 而我只看了我方那份 `DeepOceanBackground`（古老版本，不调用 resolve）。
> **结论：「没有调用方」这个判断，必须在上游整棵树里查，不能只看合并后残缺的树。**

### 4. 新类型 + 部署目标更低 → 检查可用性标注

上游部署目标是 **16.1**，我方是 **16.0**。凡是用到
`ActivityAttributes`（16.1+）、`toolbarVerticalEdge`（27.1）这类新 API 的
**类型/extension**，在 16.0 下都必须有 `@available`。

**自查**：编译报 `X is only available in iOS 16.1 or newer` 时，
不要只在调用点加 `if #available`——**先看被用的类型/extension 本身有没有标注**。

---

## `#available` 保护不了编译期（重要区分）

| 问题 | 靠什么解决 |
| --- | --- |
| 部署目标 / 真机系统版本支持吗？ | `#available(iOS X, *)` 双分支 |
| **编译用的 SDK 里有这个符号吗？** | **`#if compiler(>=…)` 或升级 SDK。`#available` 解决不了** |

实例：上游 `WorkspaceView.swift:39-62` 用了 `\.toolbarVerticalEdge` +
`.axisBehavior(.verticalPreferred)`，**已经正确包在 `#available(iOS 27.1, *)` 里**，
但 Xcode 26.3 / SDK 26.2 **没有这些符号** → 仍然编不过。

处置：`#if compiler(>=6.3)` 隔离，**代码完整保留**，
当前走 `#else` 的成熟路径；升到 Xcode 27+ 自动启用。
详见 [`ios-27.1-api-pending.md`](ios-27.1-api-pending.md)。

---

## 冲突处理的三条判据（本次实踩）

| 判据 | 例子 | 结论 |
| --- | --- | --- |
| **测试和实现都可能拒绝我们的产品决策** | 上游测试断言「超时发 `subscription-timeout` 帧」（弹窗版），而我方 build 40 按用户要求改成静默重试 | **保我方实现，改测试断言**；不要因为「上游成套」就改实现 |
| **我方封装通常是超集** | `clearSafeAreaRegions()` / `onChangeCompat` / `scrollBounceBehaviorCompat` 内含 16.4/17 双分支 + 16 回退 | 有我方封装时优先用我方；**但裸 `onChange(of:){}` 单参形式在 16.0 编不过，要用上游的 compat 写法** |
| **平台能力差异要区分「设计」与「SDK 缺符号」** | `#available` 双分支是设计正确；SDK 没符号是代差 | 前者保留，后者 `#if` 隔离 + 写文档 |

---

## 合并后的验收清单（按顺序，缺一不可）

```bash
# 1. KMP 共享层（先跑，最快暴露 shared 层问题）
KONAN_DATA_DIR="$HOME/.konan" ./gradlew :shared:linkDebugFrameworkIosX64
#    注意：本机同时存在 ~/.konan 与 tools/konan 两套同版本，会 KLIB 重复解析冲突，
#    显式指定单一路径可绕开。

# 2. 本地闸门（四路 typecheck + 收录 + 版本 + 测试目标）
bash scripts/local-typecheck.sh

# 3. 全量 XCTest（含 UI 测试）
KONAN_DATA_DIR="$HOME/.konan" xcodebuild test \
  -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -configuration Debug

# 4. 与预期红名单比对（名单外失败才是真回归）
#    Docs/qa-expected-red-build40.md
```

**每一步都要看退出码 + 具体错误，不要只看最后一行。**

---

## 本次四次隐含依赖的修复位置（供将来对照）

| 文件 | 修什么 |
| --- | --- |
| `DeepSeekHarnessMobile.xcodeproj/project.pbxproj` | 14 个 23 位 ID → 24 位（44 行改动） |
| `DeepSeekHarnessMobile/Core/Theme.swift` | 补 `DshEmptyState`（55 行）+ `DeepOceanBackground` 的 `prefersOpenLightArcs` / GeometryReader 版（14 行） |
| `DeepSeekHarnessMobile/Components/HarnessAnimatedBackground.swift` | 补 `HarnessBackgroundStyle.resolve(size:isPad:)` + `prefersOpenLightArcs`（10 行） |
| `DeepSeekHarnessMobile/LiveActivity/AgentLiveActivityManager.swift` | 兼容层 extension 补 `@available(iOS 16.1, *)`（3 行） |
| `scripts/local-typecheck.sh` | 补 MarkdownUI 桩：`TextTableColumnAlignment` / `Table*` 系列 / `Theme.table` / `BlockConfiguration`（26 行） |

---

## 一句话总结

> **合并 = 解冲突 + 补「上游新代码依赖但本地没有的符号」。**
> 前者 git 会告诉你，后者只有**闸门和全量测试**会告诉你。
> 所以**合完必须跑完整闸门，不能只看 `git diff --diff-filter=U` 归零。**

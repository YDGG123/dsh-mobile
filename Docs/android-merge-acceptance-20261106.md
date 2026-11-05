# Android 合并验收预案（2026-11-06，AndroidTester）

> 对象：`merge/upstream-1.7.15` 合并后的 Android 侧。**这是预案，写的是怎么验，不是已验过。**
> 设备：V2337A（`adb devices` 当前为空 ⇒ 标「真机」的项待连线；标「代码级」的本机即可跑）。
> 判据全部写成可判真假的形式；每项标「失败时记什么」。

## 0. 前置事实（2026-11-06 01:4x 实读合并树，判据的锚点）

| 锚点 | 现状 |
|---|---|
| `ConversationPagerChrome.kt` | `ConversationPageIndicator` 在（:122/:154，tag `conversation-page-indicator`）；**`ConversationPageSwitch` 0 处**（AndroidCoder 补建中） |
| `ConversationPagerChromeDeviceTest.kt` | :71-73 断言「正文首行从分段下方开始」（`listBounds.top >= switchBounds.bottom`）；:128 断言 indicator **不存在**；:129-131 断言 switch 显示/可点 |
| `SettingsScreen.kt` | :265 「网络检测」、:272 `TailscaleTunnelRow`、:277 「日志导出与分享」——上游三入口已在 |
| `R2UpdateManifest.kt/:29 用例` | abiApks 修复在；用例 `selectsPreferredCompatibleAbiAndUsesItsOwnChecksum` 在 |
| `build.gradle.kts` | :100 `buildTsnetAar`（Exec 任务）；:141 NDK `27.0.12077973` 检查；:169 tsnet AAR 依赖 |

**判读规则**：闸门（`./scripts/local-android-gate.sh` GATE_EXIT=0）是出包前置；下表「代码级」项本机即可判，「真机」项必须 V2337A 连线后判。**不许为过测试删判据**；唯一允许改写的断言是 :128（indicator 从 assertDoesNotExist 改 assertIsDisplayed——用户拍板「两者都要」）。

---

## A. 页头「三者并存」：分段按钮 + 副行 + 上游指示条【真机】

**怎么验**：进任一会话页（群聊更好，副行有内容）。
1. 页头可见三个元素：分段按钮「对话|轨迹」、副行、顶部小指示条。
2. 点分段「轨迹」→ 页面切到轨迹；点回「对话」→ 切回；页标题/状态跟随。
3. 观察正文首行位置：**正文第一条消息的顶边必须低于分段按钮的底边**（不压在分段下面）。
4. 上下滑动：指示条不挡分段、不挡副行文字；副行文字不被指示条盖住。
5. 重复进出会话 3 次：三元素布局稳定不跳。

**通过判据（数字/可判真假）**：
- `conversation-page-switch`、`-0`、`-1` 均Displayed；`conversation-page-indicator` Displayed（新语义）
- 设备用例那条结构判据成立：`listBounds.top >= switchBounds.bottom`
- 指示条 `align(TopCenter).padding(top = topInset + 4.dp)`（overlay 形态），与分段/副行无重叠（截图目测三边界不相交）

**失败时记**：截图整页 + `adb shell uiautomator dump`（拿三元素 bounds）+ 提交 sha。

**代码级预检（连线前可做）**：`ConversationPagerChrome.kt` 里 `ConversationPageSwitch` 出现 ≥1 处、`segmentedSelectedFill` 在；DeviceTest :128 已改写且全套测试绿。

---

## B. 设置页：内嵌形态 + sheet 不 push + 上游三入口【真机 + 代码级各半】

**怎么验**：
1. 底部 tab 进「设置」：**无顶栏**，页顶「设置」大标题（内嵌形态，vc38 口径）。
2. 点「Agent 预设」「默认模型」「字体大小」：**从底部弹 sheet**，不是整页跳转；sheet 下滑可关。
3. 新入口逐个进：**「网络检测」**（实时延迟/丢包数字）、**「日志导出与分享」**（能生成并拉起分享）、**「Tailscale 内嵌隧道」**（见 C）。
4. 「UI 降级」开关存在且可切（切后界面形态变化可观察则记下，切回恢复）。
5. 返回：设置→子页→返回，**回到原层级**，不跳到别的 tab。

**通过判据**：2 里三个入口弹的都是 sheet（底部圆角面板、可见下层页面边缘），无顶部 push 转场；5 的返回后选中 tab 与滚动位置不变。

**失败时记**：录屏 + 提交 sha + 是哪个入口走了 push。

**代码级预检**：三个入口 onClick 均接 `onOpen*` 回调且回调在 `DshProductApp` 走 sheet 状态（grep `onOpenLogs`/`onOpenTunnel`/网络检测 的挂接点）。

---

## C. Tailscale 内嵌隧道【构建代码级 + 真机】

**怎么验**：
1. **构建代码级（本机可先做）**：`buildTsnetAar` 任务能跑（需 Go 1.27+、gomobile、NDK 27.0.12077973）——产出 AAR 路径/体积记录；跑不起来是出包阻断，不是验收失败。
2. **开启**：设置 → Tailscale 开关打开 → 观察状态行（上游应有状态文案/进度）。
3. **真走内网**：开「网络检测」看探测是否出现 **100.x.x.x（Tailscale CGNAT 段）** 的目标/来源；或 `adb shell logcat | grep -i tsnet` 有启动行。
4. **回落**：关开关 → 连接回到 Cloudflare 隧道路径（原网关地址照常可用）。
5. **静默失败专项（重点）**：在无 Tailscale 登录/无 key 的状态下开开关——**必须有可见反馈**（状态行报错/失败文案）；若开关变「开」但状态行纹丝不动、无任何提示 ⇒ 记「静默失败」，附 logcat。

**通过判据**：3 出现 100.x 段或 tsnet 日志；4 回落后原地址可用；5 存在失败文案（截图文案原文）。

**失败时记**：`adb logcat -d | grep -iE "tsnet|tailscale"` 全文 + 开关状态截图 + 是否有错误 toast/行内提示。

---

## D. 更新包 abiApks 变体【代码级 + 真机】

**代码级（本机）**：`R2UpdateManifestTest` 全绿，特别是 `selectsPreferredCompatibleAbiAndUsesItsOwnChecksum`——**断言的是 sha256 与 downloadUrl 取自同一个 ABI 桶**（vc 前那个 bug 是两层各读各的）。
**真机**：设置 → 检查更新（若服务端清单已有 abiApks）：显示的新版本与设备 ABI 匹配；下载后安装前校验的 sha256 与清单该 ABI 桶一致。
**失败时记**：清单 JSON 原文 + App 显示的版本/下载地址截图。

---

## E. 回归项（我方功能不被合并弄坏）【真机为主】

| 项 | 怎么验 | 通过判据 |
|---|---|---|
| vc63 眨眼 | Bot 页看干活中 bot 头像（**录屏**，静图不判） | 干活节奏快于空闲；系统关动画不动 |
| vc63 名册缓存 | 退出 App 重进 Bot 页 | 头像/行立现（不闪空白） |
| 群聊顺序 | 群里连发 3 条（同秒），看顺序 | 与发送顺序一致（vc63 同毫秒乱序修复） |
| 三 tab | 工作区/Bot/设置 来回切 | 状态保持、无重建闪烁 |
| 凭证自愈 | 使 token 失效后操作 | 引导重新配对/自动恢复，不白屏 |
| 群聊粗体 | bot 发 `**粗体**` | 粗体显示（vc54 口径：仅 bot 消息） |
| 会话全列 | 选某工作区看列表 | 全列无「全部 N 个」、≤500 截断无提示（75②） |

---

## 汇总
- 代码级可先做：A 预检、B 预检、C1、D（R2UpdateManifestTest）、E 全量 JVM 闸门。
- 必须真机：A 全部、B2-5、C2-5、D 真机更新流、E 全部。
- 出包顺序建议：闸门绿 + A/B 代码级预检过 → 出包 → 用户装机按本单验 → 反馈按编号回。

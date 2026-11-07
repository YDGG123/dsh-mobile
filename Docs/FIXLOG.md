# 修复台账（FIXLOG）

> **制度**：用户 2026-11-06 明确要求——**每次修复都要记录，不只是这次合并的坑**。
> **用途**：① 回溯「这个问题以前修过没有、怎么修的、为什么又坏了」 ② 安卓版/后续版本直接照用 ③ 开源写更新说明的素材。
> **谁写**：谁修的谁写（iOSCoder / AndroidCoder / ReleaseBot / Chief 各自负责自己的条目），不等 Chief 催。
> **写在哪**：本文件（`Docs/FIXLOG.md`）。**不要**写进 `releases/`——那里被 `.gitignore` 排除，跟着包走不跟代码走，查不到历史。

---

## 一条记录长什么样（照这个填，别省字段）

```
### YYYY-MM-DD · build N · 一句话标题

- **症状**（用户视角，原话更好）
- **根因**（机制级，不是"改了这个就好了"）
- **怎么查**（复现/定位方法，让下一个人能重复）
- **修法**（改了什么，提交 sha）
- **判据**（怎么证明真修好了；反向测试在哪）
- **踩过的弯路**（走过但不对的方向，防止下次重走）
```

**为什么这几个字段都要**：
- 没有「症状」→ 下次同样的用户描述搜不到
- 没有「根因」→ 只会照抄改法，换个场景又坏
- 没有「怎么查」→ 下一个人从零开始定位，白烧一轮
- 没有「踩过的弯路」→ 重复推导同一个错误结论

---

## 2026-11-06 · build 82→87 · 上游 1.7.15 合并修复（iOS）

> 这一批的完整分析在 `upstream-merge-pitfalls-20261106.md`（349 行，含三类漏网机制 + 自查清单）。
> 这里只记**索引**，细节查那份文档。

| # | 症状 | 根因 | 提交 |
|---|---|---|---|
| 1 | 设置页整页空白 | 解冲突把 `settingsBody` 换成 `EmptyView()`，七分组+7 sheet 全成死代码 | `7855295` |
| 2 | 浅色下列表看不见字 | 底色写死 navy + 文字按主题算（1.2:1）；动画底浅色分支被裁（isLight 22→0） | `db4bea2` |
| 3 | 淡字对比度不足 | 最淡档 3.98:1 / 4.44:1 不过 AA | `5a0e1bb` |
| 4 | 鲸鱼动效不动 | `usesStaticBackground` 有 iOS 26 伪门槛，16 恒静态 | `2576ce6` |
| 5 | 主机行不是我们的设计 | body 被上游替换，`trailingText`/`hostLabel`/`activeEndpoint` 变死代码 | `c06dffe` |
| 6 | 相册选图白选 | 合并吃掉 `.onChangeCompat(of: selectedPhotoItems)` 调用点 | `c06dffe` |
| 7 | 设置页铺太宽 | 重建 body 时漏了 `.padding(.horizontal)` / `.padding(.top)` | `af7e52f` |
| 8 | 版本号还是 1.7.x | 合并跟着上游走，冲掉「从 1.0.0 起号」的裁定 | `db4bea2` |
| 9 | 新消息到达抽一下（抖动） | `tailFollowHardJumpLimit=24` 差距小直接硬跳，撞上量高 200ms 未落位 | `5b39b76` |

**这批最重要的一条**：**9 处里 7 处是用户真机发现，闸门一处没抓到**。闸门证明"能编译"，证明不了"是对的"。

---

## 2026-11-06 · 云端构建三连修（CI 侧）

> 本地 xcodebuild 有 dyld 坏页固疾，用户授权「本地坏了就走云端」。走通过程中连踩三个坑。

### 云端 `go install` 静默不产出二进制
- **症状**：`gomobile init: No such file or directory`，但日志显示依赖下载成功
- **根因**：runner 上 `go install golang.org/x/mobile/cmd/gomobile@latest` 没产出 `$(go env GOPATH)/bin/gomobile`
- **修法**：`607844e` —— **干脆不装 gomobile**。pbxproj 的 Tsnetgo 阶段已是「产物存在即跳过」，改为从 release asset 下载预置的 xcframework（真机 arm64 切片，15M）
- **判据**：`预置 Tsnetgo XCFramework` 步骤输出「✅ 已预置」

### `set -u` 下全角括号并入变量名
- **症状**：`DEST: unbound variable`
- **根因**：`echo "已预置：$DEST（..."` —— **全角括号 `（` 被 bash 当成变量名的一部分**
- **修法**：`80cab8a` —— 全角紧邻变量一律用 `${}` 包裹
- **通用教训**：**中文文案里的全角标点紧贴变量是 bash 雷区**，写 CI 脚本时一律 `${VAR}`

### 编译器版本 ≠ SDK 版本
- **症状**：云端 `WorkspaceView.swift` 报 `toolbarVerticalEdge` / `axisBehavior` 不可用，本机全绿
- **根因**：代码用 `#if compiler(>=6.3)` 隔离 iOS 27.1 API。云端 Xcode 26.6 的 **Swift 已 6.3+**（条件成立），但 **SDK 只有 26.5**（没有 27.1 符号）。本机 Xcode 26.3 是 Swift 6.2.4，两道门槛都跳过
- **修法**：`9f846b7` —— 门槛抬到 `compiler(>=6.4)`（Xcode 27 才带 SDK 27）
- **通用教训**：**`compiler(>=x)` 是编译器版本、`#available(iOS y)` 是运行时版本、SDK 版本是第三个东西**，三者不能混用。云端 runner 的 Xcode 常比本机新 → **本地绿、云端红**就是这个原因

---

## 2026-11-06 · WebRTC 符号包下载在数学上永远完不成

- **症状**：84 的 archive 卡在下载，进度极慢
- **根因**：`collect_webrtc_symbols.py` 用 `curl --max-time 600 --retry 3` 下 397M 的 `symbols.zip`。按 ~100KB/s，每次 600 秒只能拿 ~60M 就被掐断重来，**永远完不成**；且直连 GitHub asset API 返回 403
- **修法**：`19a80f3` —— 脚本本来就「缓存文件存在即跳过下载」，把 397M 缓存作为 release asset 预置即可
- **判据**：archive log 里零下载、符号包直收
- **通用教训**：**长下载 + `--max-time` 上限 = 数学上不可能成功**。要么断点续传，要么预置缓存

---

## 2026-11-06 · 追尾逐帧化根治抖动（细节）

> 因为用户反馈过很多次、涉及三层历史积累，单独展开。

- **症状**：对话页偶发上下抽搐。用户「不确定触发条件，也不确定是自己做了什么」
- **三层根因**：
  1. **10-30 已修**：贴底判定单阈值 28pt 无迟滞 → pinned 反复翻转（`6e96bee`）
  2. **11-03 已修**：`keepTail` 把思考行文案变化当结构变化触发滚回
  3. **本次根治**：`tailFollowHardJumpLimit = 24` —— 差距 ≤24pt 直接一帧硬跳。新消息通常只长十几到几十 pt，**大多数落这档**；自尺寸 cell 量高要 ~200ms，硬跳在落位前、落位后再纠正 = 抽一下
- **修法**：`5b39b76` —— 废除 24pt 档，`gap > 2pt` 一律逐帧追平（目标每帧重算，天然吸收落位）；≤2pt 单帧微补齐
- **为什么敢设 2pt**：实算指数逼近帧数只随距离**对数**增长——1pt→3 帧、3pt→7、10pt→11、24pt→14（60fps）。「小距离收敛慢」不成立
- **判据**：`TailFollowMathTests` 4 用例**模拟器真跑全过**（收敛帧数对数增长 / 脉冲增长无单帧跳完 / 匀速无限增长差距有界不发散 / 无过冲）
- **踩过的弯路**：
  - 第一版断言写错（限幅算成 25×0.25 而非 49×0.25）
  - 第二版「匀速无限增长」的测试场景不真实——数学稳态 `r* = g(1−d)/d = 75pt`，单帧位移恰等于增长率，**这种极端下数学上不可能归零**，如实断言「有界不发散」而不是「收敛到 0」

---

### 2026-11-04 · build 76 · 群聊切模型三群聊全卡死（MODEL_SWITCH_COMPACTION_FAILED）

- **症状**（真机事故）：用户在群聊输入框切模型 ⇒ 三个群聊**每轮** `MODEL_SWITCH_COMPACTION_FAILED`、用户消息被丢。修复形态=输入框对 Bot/群聊会话隐藏模型与思考档 chip
- **根因**（两层）：① 契约层——桌面端 ds-bot 原话 "a Bot's model lives in its details panel"（`src/client/index.js:916` 附近把 `conversation.input.model` 置 `Nothing`）：Bot/room 会话本不该有模型选择，手机端此前没实现这条语义；② 机制层——切模型走 session control，群聊/Bot 会话是 ds-bot adopted engines，模型切换的压缩检查没豁免对应钩子（`compactRegion`/`summarize` 未包 `agent/request`）⇒ 压缩失败每轮报错。错误码出自 ds-bot host steward（`ds-bot/ds-bot/src/host/parts.js:183`）
- **怎么查**：网关日志看每轮错误码 → ds-bot 源码对契约（composer 的 model picker shadow 置空 + Bot 自己的 ModelChoiceDock）→ `BotDomainComposerTests` 用**真解码路径镜像**复现判定（不走成员逐一 init，fields 与网关下发一致）
- **修法**：iOS `ec00496`（Bot 域会话隐藏 chip + 三条反向单测）＋ Android `b901f8e` 同源。后续演进：84 三态判定（`db4bea2`，修「mirror nil 一刀切」）、86 收敛为**只防群聊**（`2576ce6`，用户口径：Bot 会话放开、群聊不行）
- **判据**：`BotDomainComposerTests` 三条反向——普通会话 chip 在、群聊 room chip 不在（11-04 防线，改口径时也保留）；**模拟器真跑 8/8 TEST SUCCEEDED**（11-06 12:53）
- **踩过的弯路**：
  - 事故防线（隐藏 chip）与「没拿到名册也隐藏」的 fail-closed 混在同一个布尔里——11-06 用户报「私聊也没了」才发现 fail-closed 把未配对用户全吞了；教训：**防线要精确到它要防的那条路**，别顺手扩大范围
  - 口径三轮收敛（全隐藏 → 三态 → 只防群聊），每次动口径前都先答「会不会复现 11-04 卡死」；86 那轮差点把 Bot 会话也一刀切放开

### 2026-10-31 · build 63 · Bot 页 rows 永远空（名册缓存与 resume 存储脱节）

- **症状**：装机后 Bot 页（团队实时状态）列表一直空白
- **根因**（机制级）：vc63 把名册缓存版本 1→2 使旧缓存作废，但 `TeamFeedResumeStore`（落盘 `{instanceId, seq}`）是**独立存储没跟着作废** → 重连带 resume 参数命中网关 caught-up（`afterSeq===head`）→ `team-feed.mjs:648` 返回 `frames:[]`、`server.mjs:571` 只发 `sync.done` **不发快照** → 客户端 rows 永远空
- **怎么查**：网关侧 team-feed 帧分析 + 探针覆盖层验证；顺带排掉 `degraded()` 语义坑（「无订阅者时自然为真」，不是故障信号）
- **修法**：双管——网关 caught-up 补快照（`5d0c180`，防御）＋ 客户端**本地无缓存时不带 resume**（根治，iOSDev；客户端提交 sha 未记录）
- **判据**：真机 Bot 页 rows 出数据；探针覆盖层读数「零帧是预期不是回归」（`97d6984` 专门更正过验证方法）
- **踩过的弯路**：
  - 「两个独立存储只作废了一个」——版本 bump 只动了名册缓存，没盘点与之耦合的 resume 存储；教训：**作废缓存时必须枚举所有依赖它的存储**
  - 「零帧」一度被误判为回归，实际是 caught-up 的正确行为——验证方法本身也要落档，防止下次重查一遍

### 2026-11-07 · build 88 出包前 · workspaceFileLog 重复声明（f2af2ab 编辑事故）

- **症状**：88 出包闸门红在「编 App 测试模块」站：`AppStore.swift:524:24: error: invalid redeclaration of 'workspaceFileLog'`（GATE_TYPECHECK_EXIT=1，02:20:44）
- **根因**：`f2af2ab`（占位文案分流，+6 行）编辑时把 `private static let workspaceFileLog = Logger(...)` 粘了两遍，:523/:524 逐字节重复——同名声明同类型作用域，App 任何编译形态都该红
- **怎么查**：红点出现在测试模块而四路「没报」⇒ 起疑 → 实读四路的 mktemp 日志（`$TMPDIR/tmp.*` grep "invalid redeclaration"）⇒ **App 双路日志各含 2 条 error:**，四路其实都抓到了；只是四路计数裁决在脚本末尾 `local-typecheck.sh:813` 统一做，测试模块站先撞同一错误提前 exit 1——红的位置有迷惑性，闸门本身没漏（GATE_TYPECHECK_EXIT=1 为真）
- **修法**：删 `:524` 一行（全仓 grep 唯一声明 + :2966 唯一使用点 `Self.workspaceFileLog`，零副作用），提交 `c4dae25`
- **判据**：grep `private static let workspaceFileLog` 计数=1；闸门复跑**全绿**（`06fa676`，02:28:20–02:34:57）：GATE_TYPECHECK_EXIT=0（四路 0/0/0/0 + 测试模块过 + Tests 22 文件 0 错 + UITests 2 文件 0 错 + 工程收录 103/103 + i18n 0 新增 + 版本三处 1.0.0 (88)）、GATE_ANDROID_EXIT=0
- **逐路日志复查（规矩首跑）**：TMPDIR 里 grep 到 6 份含 `error:` 的 tmp 全为**历史运行残留**（4 份 01:1x–01:3x 的 89① 中间态 `LocalPathImageProvider.swift:18`、2 份 02:17–02:19 本次事故运行），mtime 全部早于本次起跑 02:28:20（02:35 stat 实读）；本次四路日志 0 条 `error:`。教训：逐路复查必须按 mtime 限定「本次运行」，否则 TMPDIR 里跨运行残留会制造假红
- **踩过的弯路 / 规矩**：
  - 跑闸门**不能只看脚本末尾统一计数**，要逐路看自己日志里有没有 `error:`（Chief 2026-11-07 定为规矩）
  - 编辑粘贴代码后，提交前先 grep 一遍新增声明是否与既有撞名——一行粘重，耽误一轮出包

### 2026-11-07 · build 88 出包 · LocalPathImageProvider pbxproj 组挂错（archive 级闸门盲区）

- **症状**：88 archive 两次 `EXIT=65`（第一次另叠加启动脚本缺 `JAVA_HOME`，ReleaseBot 域已焊进脚本）：`Build input file cannot be found: …/Core/LocalPathImageProvider.swift`（日志 :2071/:2076）
- **根因**：`f2af2ab` 把文件入库在 `Components/`，但 29c4bda 注册 pbxproj 时 fileRef 挂在 **Core 组**（照 GatewayRtcLink.swift 模板插位）——`sourceTree=<group>` 逐层解析成 `Core/…`，磁盘不存在；archive 按 pbxproj 真值编译即红。**闸门为什么绿**：typecheck 按 `find` 磁盘枚举、收录检查按 **basename** 对表——同名文件挪目录，两边都看不见
- **怎么查**：ReleaseBot 从 archive 日志定位 → `git ls-tree` 证明树里在 `Components/` → pbxproj `:374` 在 Core 组 children ⇒「文件位置」与「工程声明位置」分叉
- **修法**：① Chief 把 fileRef 挪 Components 组（`dec46a2`，plutil OK + 解析 103/103）② iOSCoder 给 `check-project-membership.py` 加**第四类检查**：按祖先组 path 逐层解析每个 .swift fileRef 完整路径，磁盘缺失判红
- **判据**（08:0x 直跑取 EXIT，无管道）：正向 `✅ pbxproj 反向解析：103 个 .swift 引用…缺失 0` EXIT=0；**差分反向测试**：同名文件临时挪 `Views/`（旧三类应沉默）——新检查精确点名 `LocalPathImageProvider.swift → DeepSeekHarnessMobile/Components/…` EXIT=1，还原后 EXIT=0
- **踩过的弯路 / 规矩**：
  - 新检查第一版误报 2 个「无组链」——不是解析缺结构：objects 切块按「下一个 24 位 ID 头」，连续单行对象被吞进前一个多行对象 body（`A93F1F09`=TsnetgoFramework **target** 的 body 吞进了两个测试 fileRef 文本），按子串认类型就把 target 误当 fileRef ⇒ **类型判定必须认对象自己的第一个 isa**
  - 判红取 EXIT 不能过管道（`python3 x.py | tail` 拿到的是 tail 的退出码）——判据命令必须直跑再 `echo EXIT=$?`
  - 总纲：**「闸门绿」只覆盖闸门走过的真值链**——typecheck 走磁盘、archive 走 pbxproj，两条链必须各有闸门；第三类「引用悬空」按 basename 对表，查不出「同名不同位」

### 2026-11-07 · build 89 · 输入框两颗模型胶囊（modelControl 内部双渲染）

- **症状**（用户视角，原话）：「输入框里还是有两个模型选择的胶囊，一个是我们的设计，是带有模型搜索的，另一个我不知道是哪里来的，删掉」——注意用户先说「管理员」是指他的模型名，别被字面带偏
- **根因**：`ConversationView.modelControl` else 分支把模型胶囊画了两遍——`Button { showsModelPicker = true }`（label=ConversationModelMenuLabel，点开带搜索的 `ModelPickerPanel`，我们的设计）后面又渲染了 `ConversationModelMenu`（原生 `Menu`，`:3186` label 里**再画一次同名 label**）；88 批删的 `composerControls` 是上游整组重复控件，这个是我们自己代码内部的重复，两回事
- **怎么查**：用户截图 → Chief 逐个控件对号（先误判成「管理员」权限/模式两胶囊，用户纠正后转向模型胶囊）→ grep 渲染点：`modelControl` 唯一（:1456）→ 读 else 分支体发现双渲染
- **修法**：删 `ConversationModelMenu(...)` 调用（:2244）+ `ConversationModelMenu` 定义（:3167）+ 其私有子件 `ConversationModelMenuContent`（仅它引用，:3194-3273，含 option/submenuTitle）；保留 `ConversationModelMenuLabel`（Button 路径还在用）与 `ModelPickerPanel` 链；build 88→89
- **判据**：`grep ConversationModelMenu` 仅剩 Label 的 1 用 1 定义；diff −118 行 0 新增；闸门四路绿（跑完补 EXIT）
- **踩过的弯路**：用户说「两个管理员」时按字面去查权限/模式胶囊——**用户界面上显示的文案是他的模型名（叫「管理员」），不是控件语义**；先问「你点开哪个有搜索」能少绕一圈

### 2026-11-07 · build 89 · 401 后静默死亡（截图不显示+发出的消息在手机上消失的共同根因）

- **症状**（用户视角）：「我发出去的截图我手机聊天页面上还是不显示」「信息已经发出，你们也已经收到了，但是手机端上消失了」——消息网关全收（`mobile message accepted` 齐全），手机端却空白
- **根因**（机制级，三层叠加）：
  1. 宿主重启后手机存档的 access token 与服务端记录失配 → `/mobile/v1/state|conversations` 401（08:55、09:10 各一次，`verifyAccess` 的 hash 比对不过）
  2. 客户端 401 分支 `handleRefreshFailure` 里 `refreshToken()` 的**载荷守卫**：存档 JSON 没有 `refreshToken`（旧配对形态）→ `tokenRefreshRejected("unauthorized")` → KMP 令牌机判 permanent → **清会话+Keychain**，全程不发任何刷新请求（网关日志 `auth/refresh` = 0 次的由来；`/mobile/v1/auth/refresh` 服务端在 `mobile-v1/auth.mjs:152`，一直存在）
  3. 会话清空后 `refreshNow()` 的 `guard currentAccessToken() else return` 每 8s 空转 ⇒ **0 请求、0 重试、0 提示**；UI 横幅由 `connectionIssue` 驱动，而 401 分支提前 return 从不设置它——用户只见空白
- **怎么查**：网关日志 401 时间线（Chief）→ refresh=0 次 ⇒ 刷新从未发起 → 读客户端链路：`BridgeTransport.requestURL` 确认线上路径=`/mobile/v1/auth/refresh` ✓ → `BridgeTokenMachine.kt` 通读（permanent=清会话、transient=保留）→ `GatewayBridgeSession` 结构体**零引用**（U5 配对帧 session 没接线，疑点存档）→ UI 横幅缺口在 401 分支早退
- **修法**：① `BridgeConnectionIssue` 加 `.credentialsInvalid`（headline「凭证已失效」/detail「…请重新配对恢复同步」）② 401 分支刷新失败后按 `currentAccessToken()==nil`（=永久拒已被令牌机清会话）判别，挂横幅 + `refreshFailed("unauthorized")`；暂时性失败（超时/网络）会话还在，不误报「重新配对」、走既有退避
- **判据**（iOSTester §8.26 口径）：修复后装机——a) 401 后网关日志出现 `auth/refresh` 请求（存档带 refreshToken 时）随后 `/mobile/v1/*` 恢复 2xx；b) 存档无 refreshToken 的场景不再静默：出现「凭证已失效…请重新配对」横幅；c) WS 重连成功**不**作为本条证据
- **修复边界（2026-11-07 Chief 定，防后人误读）**：本修只保证「不再默默空着」。**老设备**（配对存档无 refreshToken，早期配对流程遗留）401 后没有刷新可用，**必须手动重配对一次**；**新设备**（配对响应带 refreshToken——服务端 `issueSession` 返回含 refreshToken 已实读核实）401 自动走 `/mobile/v1/auth/refresh` 自愈。验收「新设备能自愈」的唯一方法：重配对后人为制造 401（重启宿主），看日志里 `auth/refresh` 出现且随后 2xx（iOSTester §8.27 补条）。**别把这条当成「401 问题全修好了」**
- **踩过的弯路 / 规矩**：
  - 我的非递归 grep（`lib/*.mjs`）漏了 `lib/mobile-v1/` 子目录，差点误报「服务端没有 refresh 路由」——**grep 目录要递归**，下结论前先 `-r`
  - `lastSeenAt` 更新≠认证成功（401 的请求也会碰设备记录）——不能拿它当「手机还连着」的证据
  - 用户「截图不显示」与「消息消失」一个根因两副面孔；而历史 projcache 里 turn 3312/4855 的同款抱怨是**旧形态**（当时是渲染层），这次是数据层 401——同症状不同根因，别拿旧结论套新事故
- **后续（同日 09:2x）**：`.credentialsInvalid` 入枚举时漏改 `handleRefreshFailure` 的 message 映射 switch（1185:9 switch must be exhaustive）——测试模块站编译红拦下，四路日志同含错但裁决在后（老规矩再现）。另：**版本闸门本就拦 plist↔pbxproj 双源不一致**（verify-version-consistency.py 的 app_build≠pbx_build 判定 + `VERSION_GATE_ROOT` 反向夹具实测：88-vs-89 红 EXIT=1、89/89 绿 EXIT=0）——09:22 那轮只是没跑到版本站，不是闸门漏
- **后续（同日 13:4x，观察期巡检）**：自愈**实际在工作**——重配对后 5 组 401（10:03–12:06 本地，每组 access 到期）全部 200ms 内自动恢复拉取；但网关 `auth/refresh` 计数=0，一度推断「没自愈」——真因是 **refresh 成功侧不打日志**（server.mjs 只记拒绝）。修：网关 `4d38f5f` 给 refresh 路由补 `refresh ok device=<id>` 一行（暂不同步运行副本，随用户下次重启生效）。教训：**「日志无记录」≠「没发生」——成功路径也可能静默**，观测结论前先确认该事件类别有没有日志
- **后续（同日 16:3x，§8.27① 正式收口）**：用户 14:21 重启（运行副本含 `4d38f5f`），**14:32:58 首条 `refresh ok device=b93b8191-…`**（与 token TTL 吻合），至 16:2x 共 7 次全部成功（Chief 亲自复核）。换票瞬间偶发 3 条 401（14:32:59-14:33:01）=在途请求与票轮换的竞态，无复发。数据层三步闭环：401 → 日志 `auth/refresh` → `/mobile/v1/*` 回 2xx。**凭证自愈验证完毕，用户零手动操作**

---

## 安卓侧修复（AndroidCoder）

### 2026-11-05 · vc63 · 群聊合流终序错乱（前一条消息出现在后面）

- **症状**（用户视角，原话）：「进对话页聊天记录顺序有些不对，前一条信息出现在后面了，这个问题当时 iOS 那边遇到过的」
- **根因**（机制级）：`shared/domain/RoomChatMergePlan.kt` 的 `interleaveByTime` 只按毫秒时间交错、全程无 seq 参与——同毫秒的多条（reply:0/reply:1 同 seq 拆分、快速连发）排错；且交错条件用 `<=`，同毫秒的插入项**全部插到 existing 条目之前**，乱序送达时输出就是插入序
- **怎么查**：用户报现象 → 对照 iOS 04b84c0（同源同 bug）→ 读 Kotlin 侧 `RoomChatMergePlan`：`mergeRoomChat` 只去重不排序、`interleaveByTime` 只比毫秒；先怀疑 shared 的 `SessionListReducer.sortedBy(seq)`（那层是对的），再落到合流插入层
- **修法**：`07a5dd4`——`interleaveByTime` 加 `seqOf: (T) -> Long?` 参数（默认 `{ null }` = 恒等，旧调用点零影响）；交错结果再做终序重排：(seq 升序, 原相对顺序) 下标装饰稳定排序（不赌 sortedWith 稳定性），无 seq 殿后组内原序；调用方 `RoomChatMergeUi.interleavePeerEntries` 传 `timelineSeqOrNull()`（PeerMessage.seq / item.seq / Process 组取首条非空 seq）
- **判据**：红→绿实证——红阶段（旧签名旧代码）`mergeOrderFollowsSeqNotMillis` FAILED，实际输出 `[s102, s101, s100]`；绿后同用例过。②无 seq 殿后组内原序 ③同 seq 保原相对序（装饰排序）④既有用例全绿；组合树 1008 例 0 失败
- **踩过的弯路**：① 红用例第一版用 `cat >>` 追加到了**类的闭合括号之外**——JUnit4 不发现类外 @Test，24 例全绿、BUILD SUCCESSFUL，红证据是假的（同「自证式假绿」家族：形式对、没测到）；② 断言三参写错参数序（JUnit4 message 必须在前），编译红掩盖了运行红；③ 最先怀疑 shared 的 `SessionListReducer.sortedBy`，白查一层——先对照 iOS 同源修过的位置再下手会快一步

### 2026-11-05 · vc63 · Bot 头像眨眼幅度太小（几乎看不见）

- **症状**（用户视角）：「头像会眨眼了但是幅度很小，不明显」
- **根因**（机制级）：`BlinkCadence.kt` 的 IDLE/WORKING 步骤压到 **0.80**（20%），iOS 压到 **0.03**（97%）——差 26 倍；且 iOS 一周期眨**两次**（空闲 9 档 5s、干活 5 档 2.4s），安卓只眨一次
- **怎么查**：Chief 对照 iOS `TeamAvatarView.blinkSteps` 给出逐档数值 → 核对 `BlinkCadence.kt` 现表 → 确认眨眼接线本身没断（BotTeamScreen 已传 `eyeSquash = blinkSquash.value`）
- **修法**：`2007de4`——glyph 分支（`TeamGlyphCanvas` 独立眼睛层，vc62 已接 `eyeSquash`）**逐值采用 iOS 节奏**：空闲 9 档 5s 眨两次（1.48/0.03/1.06/1/1.71/0.03/1.06/1/1.27 秒），干活 5 档 2.4s；`blinkStepsFor`/`blinkPeriodMillis` 加 `glyphBranch` 维度
- **判据**：BlinkCadenceTest 新增 3 条——glyph 空闲 9 档/周期恰 5000ms/min squash 0.03/过冲 1.06 保留；glyph 干活 5 档/2400ms/0.03；Icon 分支全部 ≥0.5（护栏）；360 例 0 失败
- **踩过的弯路**：`BlinkCadence.kt` 注释自称「Android 画的是 20 个描边符号、没有独立眼睛层，压的是符号本身」——**这段注释在 vc62 glyph 分支落地后就过时了**，但没人回头改；Icon 分支（真没眼睛层）压整只符号到 0.03 会把符号压成一条线，故 Icon 分支保留 0.80 轻压、登记为**有意差异**（Chief 批）。教训：**注释里的前提随代码演进会失效，改架构时要把过时前提一起清**

### 2026-11-06 · 网关 0.11.3 · 上下文/token 统计恒 0（根因=网关用错投影接口）

- **症状**：手机端会话的上下文压力/token 用量统计恒为 0/空；用户试装**合并前的旧包也没有** ⇒ 排除客户端合并丢码，锁定网关/宿主侧
- **根因**：`tokenUsage`/`contextPressure` 是 token-meter 注册的**全量投影**，只在 `api.sessions.projections` 的 SessionProjectionMap 里返回；网关的 context-usage/session-stats 却走 `api.sessions.history`——history 的 `projections.values` 只含 inbox/modelSelection/actionCards/proposals 等，取 `values.tokenUsage` **恒 null**
- **怎么查**：① 最初 grep DSH 包 0 命中差点得出「宿主删了投影」——**错在直接 grep app.asar 单文件二进制**；asar 必须解包再搜，解包后 typert.host.js 仍定义这两个投影 schema、`dsh-token-meter` 包就是专门的计量服务（ Chief 复核并纠正）② 实读 `~/.dsh/storages/session_projcache/`：抽样 200/200 全含 tokenUsage，最新会话 pressureTokens=457992 非空 ⇒ 数据一直在，是网关读的接口不对
- **修法**：`lib/index.mjs` 新增 `querySessionProjectionMap()`（走 `api.sessions.projections`，错误处理照 ：1525 既有形态），context-usage/session-stats 两分支改走它；带回退——`api.sessions.projections` 缺失/失败时落回旧 history 路径，保住既有错误契约（SESSION_QUERY_PERSISTENCE_FAILED）与测试桩
- **判据**：projcache 实值非空（输入侧 543 万 uncached / 输出 62.6 万）＋ gateway.test.mjs 150 项全过；真机判据=手机端统计出现真实数字（% 与 token 数）
- **踩过的弯路**：
  - grep asar 单文件 0 命中 → 误判「宿主删了功能」；教训：**asar/Electron 打包产物必须解包后再搜**
  - 「合并前的旧包也没有」这条用户观察是破案关键——它把问题从「客户端合并丢码」正确地推到宿主/网关侧

### 2026-11-06 · 网关 0.11.3 · attachment 合同统一（上游扫描 × 我方记忆双路合一）

- **症状**：合并 0.11.3 后 gateway.test.mjs 三个 attachment 用例失败（session/attachment-invalid: Image is not referenced by this session）
- **根因**：合同错位——我方 5933b90 的合同是 handleQuery 发**已嵌套**引用（api.sessions.attachment=宿主直通、要求嵌套）；上游 0.11.3 改为 handleQuery 发**扁平** {sessionId, attachmentId}、由 host adapter（api 即 adapter）**无状态扫描宿主历史**后自行嵌套化。合并后 api=adapter：我方分支发嵌套 → adapter 读 `payload.attachmentId` 得 undefined → 扫描必然找不到。live 图（att-live）只存在于网关侧记忆、宿主历史里没有 ⇒ 上游扫描必然失败
- **怎么查**：handleQuery/adapter/attachment-refs 三层插桩（DSH_ATTACHMENT_DEBUG）逐层看——记忆命中 ✓、扫描命中 ✓、错误依旧 ⇒ 定位到**合同层**而不是哪一层逻辑错
- **修法**：handleQuery 回归上游扁平合同；adapter.attachment **先查网关侧记忆**（attachment-refs.mjs——live 事件/历史页读过的引用都在）miss 再走上游无状态扫描（覆盖网关重启后记忆为空）；扫描不到时 handleQuery 把 session/attachment-invalid 映射回 attachment/unknown-ref（我方 5933b90 的显式报错语义保留，宿主内部错误码不外泄）
- **判据**：gateway.test.mjs **150 项全过**（GW_EXIT=0，含 live 嵌套转发/bytes/未知引用显式三条）；上游自家 dsh-022/tool-images 测试不破
- **踩过的弯路**：
  - 三轮怀疑错层（先怀疑记忆没写、再怀疑扫描没跑、再怀疑桩 api）——插桩日志显示每层都「对」，最后才意识到是**两层对同一请求的合同不同**
  - 教训：合并两套实现时，**同名接口的请求/响应合同必须逐字段对表**，不能只看「功能都在」

---

## 待补（历史条目，查得到的照填，查不到的写「未记录」）

> 用户 2026-11-03 已定过同类制度（安卓每版写 DELIVERABLES）。iOS 侧的历史修复按同样标准补：

- [ ] build 59 之前：贴底抽搐（已记在 `ui改版` 主题，可搬）
- [x] build 63：Bot 页变空（缓存版本 1→2 与 resume 存储脱节）
- [ ] build 64：网关卡片断订重订（对象引用当身份判断）
- [ ] build 71：群聊实时必须重开才看到
- [x] build 76：Bot 域会话隐藏模型 chip（11-04 群聊卡死事故）
- [ ] build 77：没装 Bot 插件时页内说明 / 群聊终态无限重连

**补录原则**：`git log` + 群记录能查到的照填，**查不到的写「未记录」**，不编。

---

## 维护规则

1. **每笔修复提交后**，修的人自己往本文件加一条（或在原条目上补后续），不等催。
2. **同一问题复发**：在旧条目上加一行「**YYYY-MM-DD 复发**：原因=…」，不新开条目——让「反复坏的东西」一眼看出来。
3. **判据写不清的**，说明那次其实没验清楚，要标 `判据待补`。
4. 本文件跟代码进仓库（`Docs/FIXLOG.md`），不写 `releases/`。

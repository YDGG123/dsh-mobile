# 「有红点、点进去空白」断点复盘（问答卡片两套通道）

> 状态：**已定位、未修复**。2026-10-27 由 iOSDev 排查，Chief 复核证据成立。
> 本文目的：**记录这条断点的事实与修法成本，别让下一个人重走一遍排查**。
> 协议权威见 [`mobile-contract-v1.md`](mobile-contract-v1.md) §4（卡片帧）/ §3.4（pending）。

---

## 1. 用户看到的现象

手机上团队页/Bot 行显示**「等你回答」红点**，点进去**没有卡片正文、无法作答**。
红点是真的（确实有提问在等），但正文拿不到。

## 2. 一句话结论

**红点计数走 bridge（通），卡片正文走旧协议（断）——两套通道各走一半。**

这不是「卡片没发出去」，卡片**发出来了**；是手机端**主动选择不用它的正文**。

---

## 3. 链路实测（两端都正常，全通）

用**真实模块**跑（不是复刻逻辑），逐步验证：

| 步骤 | 验证方式 | 结果 |
|---|---|---|
| ① ds-bot 生成卡片 | 真实 `install(rt)` + `rt.cardFeed.snapshot()` | ✅ `cards=1`、`kind=question`、正文与 4 个选项齐全 |
| ② 推给 `dsBotCards` | `ctx.provide('dsBotCards', feed)`；活体 `/info` `cardsDegraded:false` | ✅ 服务在位 |
| ③ 网关归一封帧 | 真实 `normalizeCard(raw)` | ✅ 通过，字段完整 |
| ④ 网关 `/team` WS 发 | `server.mjs:589-592` 订阅 + 发快照 | ✅ |
| ⑤ `GET /mobile/v1/cards` | curl | ✅ 存在（401 = 需鉴权，非 404） |

**服务端没有任何一步断。** 排查中一度怀疑 `card-feed` 的 `rt.chatOf(bot.id)` 循环会漏掉「未绑定 own chat 的 Bot」，实测**不成立**：
`chatOf = botOf(botId)?.sessionId ?? botId`，未绑定 Bot 的 `sessionId` 键不存在 → 回落到自己的 session id → 与 `state.questions` 的键一致，**能命中**。
（所以"改 `card-feed` 加兜底遍历"是**假修复**，不要做。）

## 4. 真正的断点（手机端一行注释）

`GatewayTeamFeed.swift:683`：
```swift
// 卡片帧暂时只用来点「等你回答」的红点：正文渲染仍走 bridge 的提问卡片通道。
```
`:690` 只做 `var counts: [String: Int] = [:]` —— **只数个数，正文丢弃**。

即：

| 用途 | 走哪条通道 | 状态 |
|---|---|---|
| 红点计数 | bridge 的 `card` / `card-snapshot` 帧 | ✅ 通 |
| 卡片正文 | 旧协议 `/ws/mobile` 的 `question-requested` 帧（`GatewayFrameRouter.swift:313`） | ❌ **断**（手机已全迁 bridge） |

**这就是历史遗留**：正文通道当初走旧协议，主连接迁到 bridge 后，正文通道没跟着迁。

## 5. 修法成本（关键约束：bridge 没有"回答"通道）

查 `lib/mobile-v1/actions.mjs` 的完整白名单：

```
models / efforts / update-bot / set-flags / usage /
memory-view / memory-forget / schedules / schedule-create / schedule-delete
```

**没有「回答提问」这个动作**（正确名字是 `answer-question`，见 §5.3——它必须与 ds-bot 的 dispatch key 一致）。
`adapter.mjs` 里 `questions: {}` 只是恒空占位，`server.mjs` 里 question 相关 0 命中。

所以「手机端直接用 card 正文渲染」**不够**——能显示，但**答了发不回去**。

| 方案 | 内容 | 成本 | 归属 |
| --- | --- | --- | --- |
| **A 完整** | 网关加 `answer-question` action（写回 `state.questions`，可复用 `channel.js` 的 `answer-question` 逻辑）+ 手机端 `applyCards` 保留正文、渲染 `HumanQuestionView`、答复走新 action | 网关 0.5–1 天 + iOS 1–1.5 天 | DSBotDev + iOSCoder |
| **B 止血** | 有 pending/card 但无正文时，**显示「有 1 个提问待回答，请在电脑端处理」**，不做成可点击卡片；红点保留 | iOS 半天 | iOSCoder |

**建议**：先做 B（立刻不再骗用户），再排 A（根治）。A 不能为它拖住止血。

### 5.1 答复被拒的语义：`bot/stale` 覆盖两种不同情况（别一律当已答）

`channel.js:449` 一处返回同时覆盖两件事：

```js
if (question === undefined || question.id !== body.questionId) return fail('This question is no longer open', 'bot/stale')
```

| 情况 | 含义 | 是否 bug |
| --- | --- | --- |
| `question === undefined` | 卡已答过 / 已被撤（`dismiss-question` 会 `delete state.questions[...]`） | 否，正常收尾 |
| `question.id !== body.questionId` | 客户端发上来的 id 与服务端正在等的那张卡不一致 | **是**（客户端 bug 或状态错位） |

两者共用 `bot/stale`，所以**手机端不能一律当「已答」静默吞掉**——那会把「id 拼错」这一整类 bug 永久掩盖，症状是用户点了没反应、日志里什么都没有。

**处置必须先判后处置**，判据是「卡片流里该 `id` 还在不在」：

- **该 `id` 还在卡片流里** → 客户端与服务端视图不一致（状态错位）→ **刷新卡片帧**，不重试
- **该 `id` 已不在** → 按已答收尾，不重试
- **两种情况都不重试**（理由见 §5.2）
- 补一条留痕：**「id 不在」但用户明确点了选项却收到 stale 时，记一条诊断日志**——卡还在屏幕上、id 也来自卡片本身，理论上不该发生；静默就等于把证据丢了

> `view-reply`（`show_view` 卡）与群卡补答是同一个 `bot/stale`，判法一致。

### 5.2 认领语义：先认领、失败回滚（`delete` 不是永删）

`answer-question`（`channel.js:449-460`）的顺序是：

```
校验 question 存在且 id 匹配
→ delete state.questions[sessionId]     ← 认领；读+校验+delete 在同一同步块，中间没有 await（原子）
→ await rt.ensureCapabilities / resolveAgent / followup / ctx.sessions.flush   ← 真正投递
→ catch: state.questions[sessionId] ??= question    ← 失败回滚
```

两条要点：

1. **`delete` 不是永删**：投递阶段抛错（能力未就绪、agent 解析失败等）时卡会**重新开放**，用户重答会成功，不会撞 409。
2. **回滚用 `??=`**：若期间另一条路径已认领这张卡，回滚**不会覆盖**对方的认领（不会出现两张卡同时等答案）。

**对客户端的推论**：收到 409 不重试是安全的——服务端确实认为卡不开放，不是网络抖动；而服务端内部失败已经回滚，用户重答依然能成。

群卡同一套语义：`answerRoomCard`（`channel.js:173-193`）先 `rt.dropRoomCard` 认领，投递失败 `rt.restoreRoomCard` 回滚，被别的连接抢先则回 `bot/stale`。

### 5.3 动作名是硬约束：`answer-question`（不是 `question-answer`）

网关的 `callTeam(name, payload)` 把 name **原样**当作 ds-bot `POST /api/bot` 的 `body.endpoint`（`adapter.mjs:34-45`），而 ds-bot 的 `switch` 里那个 case 写的是 `case 'answer-question'`（`channel.js:437`）。**名字对不上就是 `bot/not-found`**，不是可选的命名偏好。

- 端到端统一用 **`answer-question`**：`actions.mjs` 的 `fields` 键名也用它——`MOBILE_ACTIONS` 由 `fields` 的键派生，`/info` 的 `capabilities.actions` 自动带上，`adapter.mjs:22` 的 `allowed` 也自动放开，**鉴权白名单代码不用动**
- 若手机端坚持对外叫 `question-answer`，就得在网关加一层「对外名 → dispatch key」映射；多一层映射就多一处对不上的地方，不建议

入参形状也随之固定（`channel.js:438/449/450`）：`{ sessionId, questionId, answer }`，其中 `answer = { selected: [选项 label 原文], custom?: string }`（`parts.js:69-87`，**label 是答案的身份，不是 id**）。

实现时还有三件必做（等排期，不在本文范围内）：

1. **归属校验**：`sessionId` 必须属于可见团队里某个 bot/room（`adapter` 的 `ownedSession` 语义），否则 `TARGET_DENIED`——否则一台设备能答别人的题
2. **in-flight 守卫**：同一 `questionId` 并发只放一条（只优化连点，正确性靠 §5.2 的原子认领）
3. **反向自证要看对地方**：`/info` 的 actions 由 `adapter.actionCapabilities()` 出，前提是 `description.version === 1 && redactedText === true`；删白名单的变异与该前置条件是两回事，别混判

## 6. 排查中走的弯路（记下来省下次）

| 弯路 | 为什么错 |
| --- | --- |
| 怀疑 `card-feed` 漏卡 → 想改 `collect()` 遍历全部 `state.questions` | 实测 `chatOf` 回落已覆盖，**本来没坏**；改了是假修复 |
| 怀疑「3080 端口没监听」 | `3081` 就是配置的 `lanPort`（`cordis.patch.yml:157`），**不存在"手机该连 3080"**；下结论前要先查配置 |
| 怀疑网关没捕获 `user-questions/request` | 网关确实订阅了（`lib/index.mjs:3442`），但那是**旧协议**路径；`hasInteractionClient` 只认旧 `/ws/mobile` 客户端（`clients` 集合仅在 `:3086` 加），**bridge 不进去**，所以对手机端天然不通 |

## 7. 下次排查从哪里开始（顺序）

1. **先看 `GatewayTeamFeed.swift:683` 那条注释还在不在**——它就是断点标记
2. 不要在服务端纠结：用第 3 节那五步实测，通常 10 分钟内能确认服务端无辜

### 7.1 三态判据（只看注释会误判，必须三查）

改动可能只做了一半，**注释消失 ≠ 已根治**。按下表判：

| 状态 | 判据（三项全查） | 结论 |
| --- | --- | --- |
| **① 未修** | `:683` 那条「只用来点红点」注释**还在** | 红点会亮、点进去空白，用户被误导 |
| **② 只止血（方案 B）** | 注释**没了** 或已改写；有「请在电脑端处理」类文案；**但答复仍走旧协议 `/ws/mobile` 的 `question-requested`**（`GatewayFrameRouter.swift` 里那条 case 还在用） | 不再骗人，但用户仍得开电脑答 |
| **③ 已根治（方案 A）** | 注释没了；`HumanQuestionView` 拿到 `card` 帧的正文并渲染；**答复走 bridge 的新动作**（`/mobile/v1/actions` 里出现 `answer-question`，且 `actions.mjs` 白名单含它） | 手机上能看能答，闭环 |

**关键区分点**：② 和 ③ 都可能「注释没了」，差别在**答复走哪条通道**——
查 `lib/mobile-v1/actions.mjs` 的 `fields` 里有没有 `answer-question`：没有 = 只止血；有 = 已根治。

> 只看到「红点不显示了」就以为修好，是最容易误判的一种——那可能只是把红点也一起关了（错误修法）。

### 7.2 当前状态

> 当前状态（2026-10-27）：② 止血已落地（`ef11019`，build 38）；
> ③ 根治未开始（等网关 `answer-question` action）。

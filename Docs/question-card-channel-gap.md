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

**没有 `question-answer`。** `adapter.mjs` 里 `questions: {}` 只是恒空占位，`server.mjs` 里 question 相关 0 命中。

所以「手机端直接用 card 正文渲染」**不够**——能显示，但**答了发不回去**。

| 方案 | 内容 | 成本 | 归属 |
| --- | --- | --- | --- |
| **A 完整** | 网关加 `question-answer` action（写回 `state.questions`，可复用 `channel.js` 的 answer-question 逻辑）+ 手机端 `applyCards` 保留正文、渲染 `HumanQuestionView`、答复走新 action | 网关 0.5–1 天 + iOS 1–1.5 天 | DSBotDev + iOSCoder |
| **B 止血** | 有 pending/card 但无正文时，**显示「有 1 个提问待回答，请在电脑端处理」**，不做成可点击卡片；红点保留 | iOS 半天 | iOSCoder |

**建议**：先做 B（立刻不再骗用户），再排 A（根治）。A 不能为它拖住止血。

## 6. 排查中走的弯路（记下来省下次）

| 弯路 | 为什么错 |
| --- | --- |
| 怀疑 `card-feed` 漏卡 → 想改 `collect()` 遍历全部 `state.questions` | 实测 `chatOf` 回落已覆盖，**本来没坏**；改了是假修复 |
| 怀疑「3080 端口没监听」 | `3081` 就是配置的 `lanPort`（`cordis.patch.yml:157`），**不存在"手机该连 3080"**；下结论前要先查配置 |
| 怀疑网关没捕获 `user-questions/request` | 网关确实订阅了（`lib/index.mjs:3442`），但那是**旧协议**路径；`hasInteractionClient` 只认旧 `/ws/mobile` 客户端（`clients` 集合仅在 `:3086` 加），**bridge 不进去**，所以对手机端天然不通 |

## 7. 下次排查从哪里开始（顺序）

1. **先看 `GatewayTeamFeed.swift:683` 那条注释还在不在**——它就是断点标记
2. 若注释已消失，说明方案 A/B 已落地，重新评估
3. 不要在服务端纠结：用第 3 节那五步实测，通常 10 分钟内能确认服务端无辜

# 「实时状态暂不可用」长期挂着的根因（2026-10-31 帧级实测）

用户症状：**「还是经常不可用的问题一直存在」**——Bot 页顶部黄条「实时状态暂不可用：网关目前只拿到名册…」
时不时挂着，过一会儿自己好。此前修过两轮（order 帧漏读、degraded 不变式），都没根治。

本文档记录**帧级实测**得到的结论、挂 true 的窗口、翻回的触发帧，以及 A/B/C 三条修法。
复跑：`node scripts/team_feed_degraded_probe.mjs`（只读探针，不起 DSH、不开端口、不落盘）。

---

## 1. 判定标志位的两套语义（根因的土壤）

`degraded` 这个标志**两边语义不同**，混在一起用是历史 bug 的土壤：

| 侧 | 位置 | 语义 |
|---|---|---|
| 网关 | `team-feed.mjs` `const degraded = () => service == null` | 「网关**此刻**是否在降级供数」（源服务在不在位） |
| 客户端 | `GatewayTeamFeed.swift` `@Published private(set) var degraded` | 「我手上的名册**是否实时态**」——含「我读的是本地缓存」 |

客户端在**缓存水合**时置 `true` 是**诚实**的（`hydrateRosterCache`：缓存本来就不是实时态）。
问题不在"别置 true"，而在**有没有帧能把它翻回来**。

---

## 2. 帧级实测（时间轴）

探针直接驱动已安装的 `team-feed.mjs`（`pollMs`/`orderMs` 压到 30ms 加速），
源服务由 `resolveService` 可控地置于在位/缺席：

```
   +2ms  ── service=UP
   +2ms  帧 snapshot degraded=false seq=100      ← 正常快照
  +33ms  帧 order    degraded=false seq=100
 +104ms  ── service=DOWN
 +205ms  ▸ snapshot() ← 手机此刻重连取快照 → degraded=true seq=0
 +365ms  ── service=UP（名册/排序都没变）
 +392ms  帧 snapshot degraded=false              ← 靠 bind() 的 recovering 补发才翻回
```

### 结论 ①：`resume` caught-up 路径**零帧**（主根因）

```
history(7) → mode=ok frames=0        # afterSeq === head
其后 200ms 内订阅者收到 0 帧
```

链路（网关 `mobile-v1/server.mjs` 的连接处理）：
1. 客户端带 `resume:{instanceId, afterSeq}` 连上
2. 网关 `team.history(afterSeq)` 命中且 `afterSeq === head` → `{mode:'ok', frames: []}`
3. → **整段跳过 `team.snapshot()`**，只发一条 `sync.done{reason:'caught-up'}`
4. `sync.done` **不带 `degraded` 字段**

于是：**客户端「缓存水合置 true」在这一刻起没有任何帧能清掉它**。
`maybeOrder` 只在**排序签名变化**时发帧（`team-feed.mjs` `maybeOrder`），
名册与排序静止时它一句话都不说 → 黄条就一直挂着。

这正是用户「经常不可用、过会儿自己好」的形态——「过会儿好」= 恰好来了一个真实变化
（delta / 签名变了的 order / 重新取快照）。

### 结论 ②：源服务缺席期间，已连接的订阅者**收不到帧**（反向谎报，待办 C）

```
源服务消失后 500ms 内订阅者收到 0 帧
```

网关只在 `snapshot()` 被调用时才判断降级，**不会在源服务消失时主动推一帧
`degraded:true`**。已连接的手机因此继续显示**上一次实时帧的旧状态**
（干活的显示"干活"、空闲的显示"空闲"），比"暂不可用"更不诚实。
方向与用户症状相反，优先级靠后，记入待办 C。

### 结论 ③：恢复靠 `bind()` 的 recovering 补发

`team-feed.mjs` `bind()` 有 `recovering = (service == null)` 分支，恢复时**主动补发一帧快照**
（条件是 `subscribers.size > 0`）。实测恢复后 +392ms 收到 `snapshot degraded=false`。
这条路径是好的，别改坏。

### 翻回 false 的**触发帧**（只有这三种）

| 帧 | 何时发 | degraded 字段 |
|---|---|---|
| `snapshot` | 首次连接 / resume 未命中 / recovering 补发 | 顶层 bool |
| `delta` | 合并窗口冲刷、降级轮询发现名册变化 | 顶层 bool（每次都带） |
| `order` | **排序签名变化时**（静止期不发） | 顶层 bool |

`ready` 帧**每次都发**，但它带的是**嵌套** `degraded:{team, cards}` —— 客户端原先**丢弃**了它。

---

## 3. 根因定性

**主根因**：客户端「缓存水合置 true」+ 网关「resume caught-up 零帧」→ 没有任何帧能翻回。
触发条件很常见：**有缓存 + 网关自上次以来无新变化（静止期）**。

**次根因（接线缺口）**：网关 `ready` 帧**每次都带** `degraded:{team,cards}`
（`server.mjs`，语义正是「网关此刻是否降级供数」），客户端 `handleFrame` 的 `ready` 分支
**只读 instanceId、丢弃 degraded**。这与当年 order 帧漏读是同一类缺口。

---

## 4. 修法

| 单 | 内容 | 状态 |
|---|---|---|
| **A** | 客户端 `ready` 读 `degraded.team`（`applyReady` + `degradedAfterReady` 纯函数） | **已入库**（`ecf3dcf` 的 `GatewayTeamFeed.swift`），用例 `b23bf3e` |
| **B** | 网关 `sync.done` 带顶层 `degraded`（语义最精确：回放界标，到达时回放数据已全部应用）；客户端分支已留好接口（缺字段不动） | 网关侧派给 DSBotDev，随下次重启生效 |
| **C** | 源服务消失时主动推一帧 `degraded:true`（修结论 ② 的反向谎报） | 待办，单独排 |

**A 的风险评估**（为什么可以清 true）：`ready` 带 `degraded.team:false` 只出现在
网关实时供数的连接上；caught-up 路径本身表示**客户端 seq 已追平网关 head**，
即手上的名册就是当前名册 → 清 true 不构成谎报。其余路径随后到达的
`snapshot`/`delta` 会覆盖 `ready` 的判定，无竞争风险。

**不许做的事**：
- 不许把「缓存水合置 true」当成 bug 改掉——那是诚实的
- 不许用 `ready` 的缺字段当成「恢复」（缺字段 = 不动，与 `degradedAfter` 同一纪律）
- 不许把 `cards.degraded` 混进团队标志（卡片流有独立降级通道）
- 不许改坏 `bind()` 的 recovering 补发（结论 ③）

---

## 5. 复跑与反向测试

```bash
node scripts/team_feed_degraded_probe.mjs          # 期望：3/3 ✅ + 「all ok」+ EXIT=0
MOBILE_GATEWAY_LIB=/nope/team-feed.mjs node scripts/team_feed_degraded_probe.mjs   # 期望：EXIT=2（fail-closed）
```

反向（证明修复真的必要）：删掉 `applyReady` 里 `degraded = Self.degradedAfterReady(...)` 那行
→ `TeamReadyDegradedWiringTests.testReadyFrameClearsDegradedFlagOnResumeCaughtUp` 变红；
探针的结论 ① 也会说明「该路径零帧，无帧可清」。

**判读纪律**：看退出码 + 是否打印 `all ok`，不要用 `wc -l` 数输出行数（空行会把有输出的脚本统计成 0 行）。

---

## 6. 教训

1. **「无帧」是一种状态更新**：客户端标志位只能靠帧翻转时，「网关不发帧」的路径
   就是 bug 的藏身处。新增能力（resume caught-up）时要问一句
   「这条路径上客户端的状态标志还能被修正吗」。
2. **同一字段两边语义不同**时，缩窄它比纠正它安全：客户端读 `ready` 时**只取 `team`**，
   不碰 `cards`，也不把 `ready` 的缺字段当成恢复。
3. **帧型白名单要成对维护**：网关 `ready` 早就诚实地带了 `degraded`，客户端没读 ——
   新增帧型/字段时，两侧要成对检查（`GatewayTeamFeed` 里已有这条注释纪律）。
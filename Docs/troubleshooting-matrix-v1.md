# 手机端故障自愈矩阵 v1

> 状态：**正式版**，2026-10-26 由 iOSDev 整理，对应 commit ①分块 `2ca90cb` / ②续连 `afb64a2`（手机）与 `48adb20` / `719b360`（网关）。
> 用途：**真机出问题时照着这张表定位**，而不是重新读代码。
> 配套（本表只讲「怎么排查」，字段定义看契约）：
> - 协议权威 [`mobile-contract-v1.md`](mobile-contract-v1.md)：**§8** 分块传输 / **§9** 续连心跳
> - §9.4 [`sync.done` 六个 reason](mobile-contract-v1.md#94-syncdone-界标与六个-reason)
> - §9.5 [五个 `SYNC_*` 错误码](mobile-contract-v1.md#95-五个错误码)
> - §8.4 [超限/并发/超时行为](mobile-contract-v1.md#84-超限并发与超时行为)
> - §10 [已知缺口](mobile-contract-v1.md#10-已知缺口留给后续决策)
>
> **本文每条「日志标记」都是从代码里 grep 出来的真实字符串**，不是拟写。
> 日志子系统 `ai.dsh.mobile.ios`，category **`team-chunk`**（手机端）/ 网关侧前缀 **`mobile-v1`**、**`team-feed:`**。

---

## 0. 一句话先记住三条

1. **`reason=snapshot` 不是故障，是自愈标记**——它前面必有一帧 `SYNC_GAP` 或 `SYNC_UNKNOWN_INSTANCE`，紧跟整帧快照，看到就算通过。
2. **`SYNC_GAP` / `SYNC_UNKNOWN_INSTANCE` 不需要断开重连**——同一连接自愈（等紧跟的快照）。断开重连的是**分块失败 / 超时 / 心跳判死**这几类。
3. **六种 reason / 五种错误码，没有第七种、没有第六码**——出现别的值说明版本不匹配，直接查版本（详见 §3）。
   权威枚举表在契约 [§9.4](mobile-contract-v1.md#94-syncdone-界标与六个-reason) /
   [§9.5](mobile-contract-v1.md#95-五个错误码)，本表不重复维护（避免两处漂移）。

---

## 1. 故障自愈矩阵

| # | 故障类型 | 预期客户端行为 | 预期日志标记（真实字符串） | 自愈判定（用户看到什么算通过） | 需人工干预？ |
| --- | --- | --- | --- | --- | --- |
| 1 | **半截 chunk 流**（网关发一半停住） | 清扫任务按 `max(0.5, 超时/6)`=5 s 节奏推进、**不依赖新块**；累计 30 s 未收齐 → 丢弃该流 → 断连 → 重连取整帧快照 | 手机：`chunk stream timed out: ["<streamId>"] after 30s; resyncing`<br>（底层：`stream expired stream=<id> after=30.0s`） | 断连后自动重连，名册/团队页**重新出现完整内容**（不是缺一块）。日志里后续应有 `sync.done reason=snapshot` | 否（自动） |
| 2 | **断网 30 s 后恢复** | 重连带 `resume{instanceId, afterSeq, maxDeltas}`，命中留窗 → 只补增量，**不重拉全量** | 手机：`sync.done reason=resume seq=<N>`<br>（续连命中，`reason=sync.from` 是主动重试同一语义） | 断网期间的新消息**补齐且顺序正确**，团队页没有整页闪白/重载 | 否（自动） |
| 3 | **网关重启** | 网关 `seq` 空间重来 → 回 `SYNC_GAP` → **紧跟整帧快照** → `sync.done reason=snapshot`。手机**不断开** | 手机：`sync error SYNC_GAP: waiting for the snapshot that follows`<br>然后：`sync.done reason=snapshot seq=<N>`<br>网关：`team-feed: delta base <b> != local seq <s>, resyncing from snapshot` | 团队页内容重新完整加载一次；**没有反复重连**（重连日志不该出现多条） | 否（自动） |
| 4 | **ds-bot 重启** | `instanceId` 变化 → 回 `SYNC_UNKNOWN_INSTANCE` → 整帧快照自愈 | 手机：`sync error SYNC_UNKNOWN_INSTANCE: waiting for the snapshot that follows`<br>然后：`sync.done reason=snapshot seq=<N>` | 同上；另外**落盘的 resume 会按新 instanceId 重写**，后续断网仍能补增量 | 否（自动） |
| 5 | **保留窗挤出**（超 10,000 帧 / 15 分钟 / 8 MB） | 网关判 gap → 整帧快照；客户端清空本地增量期待 | 手机：`sync error SYNC_GAP: waiting for the snapshot that follows`<br>网关：`team-feed: delta base ... resyncing` | 同 #3：一次完整重载后恢复正常 | 否（自动） |
| 6 | **单帧 > 4 MiB 不切不发** | 网关**不切、不发**，只记日志（连接与其他帧不受影响）；手机端该流永远收不齐 → 30 s 超时 → 重连取快照 | 网关：`<label>: <kind> frame is <bytes> bytes; not sent (too-large, limits 524288/4194304)`<br>手机：`chunk stream timed out: ... after 30s; resyncing` | 重连后拿到可渲染的快照（**内容可能比桌面少**，因为超限帧被网关主动丢弃）。若反复出现，说明有单帧确实过大——要人工看 §5 | **是**（反复出现时） |
| 7 | **心跳判死**（75 s 无任何帧） | 手机端判死 → 断连 → 重连（退避 1 s 起，×2，封顶 30 s） | 手机：`heartbeat timeout silence=<N>s; reconnecting` | 重连成功后恢复；`__` 正常时应有 `heartbeat pong skew=<N>ms`（校时诊断） | 否（自动） |
| 8 | **控制帧限速**（`SYNC_RATE_LIMITED`，`sync.from` 5 s 内重复） | **忽略**，不重试、不清 resume、不报错弹窗 | 手机：`sync error SYNC_RATE_LIMITED: ignored` | 本轮忽略即可；平静下来后下次重连自然恢复。客户端不在 5 s 内重复发 `sync.from` 就不会撞到 | 否（自动） |
| 9 | **老客户端**（不声明 `chunked-frames-v1` / 不发 `resume`） | **行为完全不变**：网关 `state.chunked !== true` 直接发整帧；无 resume 就走整帧快照 | 无新增日志（这正是预期）。网关侧也不会出现分块相关行 | 功能与升级前一致，**不需要任何配置** | 否 |

### 1.1 补充：能自动好、但属于「异常路径」的几类

| 故障 | 客户端行为 | 日志标记 | 判定 | 需人工 |
| --- | --- | --- | --- | --- |
| 分块帧字段缺失 | 丢弃该流 + 断连重取快照 | `chunk frame malformed: ["<keys>"]` | 重连后恢复 | 否 |
| 重组失败（单块超限/重组超限/并发超限/中途变形） | 丢弃该流 + 断连重取快照 | `chunk reassembly failed: <error>; resyncing`<br>细分：`chunk too large stream=<> bytes=<> limit=<>` / `stream too large ...` / `too many streams limit=<> drop=<>` / `stream mismatch ...` | 重连后恢复 | 否 |
| 单块超公布值 | 判错丢弃（说明网关与宣传值不一致） | `chunk too large stream=<id> bytes=<n> limit=<L>` | 若频繁出现→网关公布值/实际值不一致，人工查 | **频繁时是** |
| resume 载荷非法 | 清落盘 resume，下次走整帧 | `sync error SYNC_BAD_REQUEST: cleared resume state` | 下一次重连退化成整帧快照，功能正常 | 否 |
| resume 能力不可用（老网关） | 清落盘 resume | `sync error SYNC_UNAVAILABLE: cleared resume state` | 同上 | 否 |
| 其它未知错误码 | 报界面 | 界面出现 `lastError` 文案（无 `sync error ... ignored` 这类日志） | 需要看具体文案 | **是** |

---

## 2. 日志速查表（按字符串反查故障）

手机端 category `team-chunk`，复制下面任一串到 Console.app 过滤即可：

| 日志片段 | 含义 | 对应矩阵行 |
| --- | --- | --- |
| `chunk stream timed out` | 半截流超时被丢弃，正在重连 | #1、#6 |
| `stream expired stream=` | 底层单条流被超时清扫 | #1 |
| `chunk reassembly failed` | 重组失败（含四类细分原因） | §1.1 |
| `chunk frame malformed` | 帧字段缺失/类型不对 | §1.1 |
| `chunk stream complete kind=` | DEBUG：某条流成功拼齐并回放 | 正常路径 |
| `sync.done reason=` | 续连/回放界标，**看 reason 值** | #2、#3、#4、#5 |
| `sync error <CODE>` | 网关返回 `SYNC_*` | #3、#4、#5、#8、§1.1 |
| `heartbeat timeout silence=` | 心跳判死 | #7 |
| `heartbeat pong skew=` | 正常：校时诊断 | 正常路径 |
| `reset clearing <n> stream(s)` | 断连时清半截流 | 正常路径 |

网关侧（`mobile-v1` / `team-feed:`）：

| 日志片段 | 含义 | 对应 |
| --- | --- | --- |
| `frame is <N> bytes; not sent (too-large, limits ...)` | 单帧超 4 MiB，主动丢弃 | #6 |
| `frame is <N> bytes; not sent (too-many-streams, ...)` | 并发流超限被拒（防御性，正常不触发） | §1.1 |
| `team-feed: delta base <b> != local seq <s>, resyncing from snapshot` | 网关自己发现错位并重取快照 | #3、#5 |
| `team-feed: snapshot <N>KB > 1000KB, deferring <n> avatars in <f> frame(s)` | 大快照走 `avatarDeferred` 分帧（正常优化） | 正常路径 |
| `team-feed: avatar of <id> is <N>KB (> 44KB), dropped image and kept shape/color` | 头像超 32 KiB 红线被裁（正常保护） | 正常路径 |

---

## 3. 照表排查三步法

### 第一步：捞日志（30 秒内完成）

手机连 Console.app → 过滤 `category == "team-chunk"`（或直接搜 `chunk` / `sync.done` / `heartbeat`）。
网关侧看 DSH 日志里的 `mobile-v1` 与 `team-feed:`。

**先看这三行，通常一步定位**：
- 有 `sync.done reason=` → 走**第二步 A（续连类）**
- 有 `chunk stream timed out` / `chunk reassembly failed` / `chunk frame malformed` → 走**第二步 B（分块类）**
- 有 `heartbeat timeout` → 走**第二步 C（连接类）**

### 第二步：归类

**A. 续连类（看 reason）** —— 六个值的权威定义见契约 [§9.4](mobile-contract-v1.md#94-syncdone-界标与六个-reason)

| reason | 结论 | 下一步 |
| --- | --- | --- |
| `resume` / `sync.from` | ✅ **最健康**：只补了增量 | 无需处理 |
| `caught-up` | ✅ 本来就没落后 | 无需处理 |
| `gap` / `unknown-instance` | ✅ **自愈成功**：走了整帧快照（分别对应网关重启 / ds-bot 重启） | 无需处理；若**每次都出现**且伴随反复重连 → 查网关是否频繁重启 |
| `snapshot` | ✅ **自愈完成标记**，不是故障 | 无需处理；确认它前面有一帧 `sync error SYNC_GAP/UNKNOWN_INSTANCE` 即闭环 |
| `-`（缺字段） | ⚠️ 网关版本不匹配 | 核对网关是否 ≥ `719b360` |

**B. 分块类**

| 现象 | 结论 | 下一步 |
| --- | --- | --- |
| `chunk stream timed out` 偶发 1 次 | ✅ 自愈（重连取快照） | 无需处理 |
| 同上**反复出现** | ⚠️ 可能真有超 4 MiB 单帧 | 去网关日志找 `not sent (too-large`，看是哪个 kind 的帧；按 §5 处理 |
| `chunk reassembly failed: chunkTooLarge` | ⚠️ 网关公布值与实际切分不一致 | **需人工**：核对 `chunked.mjs` 的 `MAX_FRAME_BYTES` 与 `/info` 公布值 |
| `chunk frame malformed` | ⚠️ 网关发了字段不全的 chunk | **需人工**：对齐两端版本 |

**C. 连接类**（错误码权威定义见契约 [§9.5](mobile-contract-v1.md#95-五个错误码)）

| 现象 | 结论 | 下一步 |
| --- | --- | --- |
| `heartbeat timeout` 偶发 | ✅ 自愈（重连） | 无需处理 |
| `heartbeat timeout` 规律性（如每 75 s 一次） | ⚠️ 网络中间设备在丢帧 / TCP 半开 | 换网络复测；确认网关 WS 层 ping（15 s）是否被中间设备拦 |
| 重连风暴（多条 `heartbeat timeout` + 退避到 30 s） | ⚠️ 网络不可达或凭证失效 | 看是否伴随 `4401` / `lastError` 界面提示 |

### 第三步：判定是否需要人工

**符合所有这些 = 纯自愈，不用管**：
- 每种异常日志**只出现一次或偶发**，且随后有 `sync.done reason=snapshot`/`resume`
- 团队页重新出现完整内容，无缺失、无反复闪白
- 没有界面 `lastError` 报错

**出现任一 = 需人工**：
- `chunk stream timed out` **反复**出现（尤其伴随网关 `not sent (too-large`）
- `SYNC_*` 出现**契约外的值**（不是那五个）→ 版本不匹配
- `reason` 出现**契约外的值**（不是那六个）→ 版本不匹配
- 界面有 `lastError` 文案（未知错误码走 `report` 分支）
- 重连退避一路涨到 30 s 仍连不上

---

## 4. 关键参数速查（改这些会改掉上表行为）

| 参数 | 值 | 位置 | 影响矩阵行 |
| --- | --- | --- | --- |
| 流超时 `streamTimeout` | 30 s（客户端内部，网关不公布） | `FrameReassembler.swift:37` | #1、#6 |
| 清扫节奏 | `max(0.5, 超时/6)` = 5 s | `FrameReassembler.swift:277` | #1 |
| 单块上限 `maxFrameBytes` | 512 KiB | 网关 `chunked.mjs:21` | #6、§1.1 |
| 重组上限 `maxReassembledBytes` | 4 MiB（超了不切不发） | 网关 `chunked.mjs:22` | #6 |
| 客户端硬帽 `absoluteReassembledCap` | 8 MiB（有意比网关宽松） | `FrameReassembler.swift:116` | 防御 |
| 并发流上限 | 2（网关串行发送，实际恒 1） | 网关 `chunked.mjs:23` | §1.1 |
| 心跳间隔 / 判死 | 25 s / 75 s（`/info` 公布） | 网关 `server.mjs:58-59` | #7 |
| 网关 WS 层 ping / terminate | 15 s / 75 s | 网关 `server.mjs:419` | #7 |
| `sync.from` 限速 | 1 次 / 5 s | 网关 `server.mjs:506` | #8 |
| 重连退避 | 1 s 起，×2，封顶 30 s | `GatewayTeamFeed.swift:119`、`:299-300` | #7、C 类 |
| 保留窗 | 10,000 帧 / 15 分钟 / 8 MB | 网关 `team-feed.mjs:119-121` | #5 |
| `maxDeltas` 默认/上限 | 200 / 1000 | 网关 `server.mjs:60-61` | #2 |

---

## 5. 单帧超 4 MiB 反复出现时怎么处理（唯一常见的人工场景）

1. 网关日志搜 `not sent (too-large`，记下 `kind`（`snapshot` / `cards` / `delta`）。
2. 若是 `snapshot` 且团队规模很大（如 20 Bot 且头像满配）→ 先确认是否走了 `avatarDeferred`（应出现 `team-feed: snapshot ... deferring N avatars`）；没走说明单帧仍超限，需要发布端压小静态字段。
3. 若是 `cards` 中的 `view` 卡正文过大 → 属发布端问题，缩短 `viewText`。
4. **不要**靠调大 `maxReassembledBytes` 解决——那是往上撞 8 MiB 客户端硬帽和内存，治标不治本。

---

## 6. 本文不覆盖的部分

| 项 | 去哪看 |
| --- | --- |
| 协议字段定义、类型、约束 | [`mobile-contract-v1.md`](mobile-contract-v1.md) §8 / §9（唯一权威；两份指针分册只做跳转） |
| 配对、鉴权、凭证 TTL | 同上 §2 |
| 出包与校验判据 | 同上 §7 |
| `/events` 流的大帧行为（当前**不会**收到 chunk） | 同上 §10 缺口 4 |

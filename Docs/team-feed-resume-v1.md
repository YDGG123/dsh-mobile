# 团队流续连与心跳契约 v1（`resume` / `sync.from` / 应用层 ping）

> 目的：断网/切网/后台回来时**只补增量**，不再整帧重拉；以及发现「TCP 半开、本端以为还连着」。
> 网关实现 `48adb20`（①）与 `719b360`（②），手机实现见 `GatewayTeamFeed.swift`。
> 分块传输另见 `chunked-frames-v1.md`，两份互不依赖。

## 1. 序号语义（关键：按 `base` 链判缺口，不按 +1）

- 每条业务帧带 `seq`：单调递增、**每 `instanceId` 独立**。
- `snapshot`：带 `instanceId` + `seq`（当前 head）。
- `delta`：带 `base`（合并窗口前的 head）+ `seq`（窗口末帧）。
- **网关会把 400ms 窗口内多条 delta 合并成一帧**，所以 `seq` 允许跳号（`7 → 9` 正常，8 被并进 9）。
  客户端规则只有一条：
  - `base == 本地 seq` → 应用，本地 seq 置为 `seq`；
  - 否则 → 丢弃并重取快照。
  - **禁止**用「`seq` 是否 +1」判缺口——合并窗口下会疯狂误报。
- `order` 帧不占 seq（它的 `seq` 只是参考值，不代表事件位置）。
- `avatar` 旁路帧与 `snapshot` 同 seq，不参与回放。
- 心跳 / 补增量**不占 seq、不触发快照**。

## 2. 零往返续连（首选）

连上后首帧直接带续连状态，命中留窗就只回增量：

```json
{ "type": "auth", "token": "…",
  "capabilities": ["chunked-frames-v1"],
  "resume": { "instanceId": "…", "afterSeq": 8271, "maxDeltas": 200 } }
```

- **命中留窗** → 网关直接回 delta（**不发整帧快照**）→ 最后 `sync.done`。
- **没命中** → `{type:'error'}`（`SYNC_GAP` / `SYNC_UNKNOWN_INSTANCE`）+ **紧跟一条新快照** + `sync.done`
  （同一连接自愈，客户端**不要断开重连**）。
- 没有可用状态（首次连接、状态被清）→ 不写 `resume`，退化成整帧快照。
- `maxDeltas` 客户端默认 200，网关上限 1000。

## 3. 连接中重试（`sync.from`）

```json
{ "type": "sync.from", "instanceId": "…", "afterSeq": 8271, "maxDeltas": 200 }
```

语义与 `resume` 相同，用于连接仍在但客户端想主动补一段。限速 **1 次 / 5s**（超了回 `SYNC_RATE_LIMITED`）。

## 4. `sync.done` 界标

```json
{ "type": "sync.done", "instanceId": "…", "seq": 8293, "reason": "resume|sync.from|caught-up|gap|unknown-instance|snapshot" }
```

reason 六个值以网关实际发出为准（没有第七种）：
`resume`（auth 里的续连命中）| `sync.from`（连接中重试）| `caught-up`（本来就没落后）|
`gap`（留窗有缺口）| `unknown-instance`（instanceId 变了）| `snapshot`（**尝试了 resume 但没命中**）。

客户端拿它当「这段增量结束了」的界标，用于日志与界面状态，不需要再猜「还有没有」。
注意 `reason=snapshot` **不是故障**，是自愈标记：前面必有一帧 `SYNC_GAP`/`SYNC_UNKNOWN_INSTANCE`，
紧跟整帧快照，日志里见到它算通过。未知 reason 值只记日志、不分派分支（网关加值不会让手机走错路）。

## 5. 错误码

| code | 客户端动作 |
| --- | --- |
| `SYNC_GAP` | 等紧跟的新快照（**不断开**） |
| `SYNC_UNKNOWN_INSTANCE` | 同上（`instanceId` 变了，说明 ds-bot 重启） |
| `SYNC_UNAVAILABLE` | 清掉落盘 resume，下次走整帧 |
| `SYNC_BAD_REQUEST` | 清掉落盘 resume（`afterSeq` 非法） |
| `SYNC_RATE_LIMITED` | 忽略（限速） |
| 其它 | 报给界面（`lastError`） |

## 6. 客户端只存两个值

`{instanceId, seq}`，按**服务器地址**分键存 `UserDefaults`。
- 网关重启不重置 `seq` → 留窗被清 → `GAP` → 快照；
- ds-bot 重启换 `instanceId` → `UNKNOWN_INSTANCE` → 快照。
所以不需要在手机上存事件缓冲。

## 7. 应用层心跳

- 客户端每 `heartbeatIntervalSeconds` 发 `{type:'ping'}` → 网关回 `{type:'pong', at}`（`at` = 网关时间戳，仅诊断/校时）。
- 超过 `heartbeatTimeoutSeconds` 没收到任何帧 → 判死 → 断开重连。
- 两个值由 `/info` 的 `capabilities` 公布：`heartbeatIntervalSeconds`（默认 25）、
  `heartbeatTimeoutSeconds`（默认 75）；未公布用默认值。
- 客户端夹取：间隔 ∈ [5, 300]s，判死 ∈ [2×间隔, 600]s。
- 与网关自带的 WS 层 ping（15s）/ terminate（75s）**互补**：那条是服务端清理僵死连接，
  这条是本端发现「TCP 半开」。

## 8. 保留窗与订阅模型

- 网关：常驻订阅 + 环形缓冲 **10,000 帧 / 15 分钟 / 8MB**（先到者为准）。
  这意味着即使没有手机连着，网关也保持上游订阅（断线期间事件不丢）——宿主侧多约 4MB 内存。
- 卡片流**不参与**补增量：重连照旧重取 `card-snapshot`（帧小，不值得单开回放通道）。

## 9. 兼容

- 老客户端不发 `resume`、不发 `ping` → 网关行为与旧契约完全一致。
- 认证后除 `ping` / `pong` / `sync.from` 外的帧仍按旧契约关闭 4400。
- 手机端用例：resume 载荷（含 `maxDeltas` 夹取）、`SYNC_*` → 动作映射、resume 落盘（含多服务器隔离、
  非法值不写）、心跳参数协商与夹取。

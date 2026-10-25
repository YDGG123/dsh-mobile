# 分块帧契约 v1（`chunked-frames-v1`）

> 目的：让超大快照/大响应不再撞上宿主网关的缓冲红线（历史事故：单帧 5.9MB 直接把手机踢下线）。
> 抬阈值治不了根，所以改成**切块发、按序拼**。本文件是手机端与网关端的共同契约；
> 网关实现在 `dsh-plugin-mobile-gateway`（`48adb20`），手机实现在 `FrameReassembler.swift`
> （`7218198`）。

## 1. 能力协商

- **声明**：WS 首帧 `{"type":"auth","token":…,"capabilities":["chunked-frames-v1"]}`。
  网关也接受 `features` / `caps` 字段名，只认字符串项。
- **不声明 = 永远收整帧**（老客户端零影响；新网关对老客户端行为完全不变）。
- 两个 WS 都支持：`/mobile/v1/team`、`/mobile/v1/events`。

## 2. `/info` 公布的上限

| 字段（网关名） | 含义 | 当前值 | 手机端映射 |
| --- | --- | --- | --- |
| `capabilities.maxFrameBytes` | 单块上限（UTF-8 字节） | 524288（512KiB） | `FrameReassembler.Limits.maxChunkBytes` |
| `capabilities.maxReassembledBytes` | 单条流重组上限 | 4194304（4MiB） | `maxReassembledBytes` |
| `capabilities.maxStreamsPerConnection` | 同连接并发流上限 | 2 | `maxStreams` |

- 一个字段都没公布 → 手机端**保持本地兜底**（512KiB / 4MiB / 2，与当前网关值一致），不猜。
- 只公布部分字段也认（其余保持兜底）。
- 字符串型数值（如 `"524288"`）不认，按“没给”处理。

## 3. chunk 帧

```json
{ "kind": "chunk", "streamId": "…", "index": 0, "total": 3,
  "finalKind": "snapshot", "part": "{\"kind\":\"snapshot\",…", "at": 1761400000000 }
```

- `part`：按 **UTF-8 字符边界**切开的原始 JSON 字符串（中文/emoji 不会被劈开）；
  `parts` 按 `index` 拼接后即为原帧全文。
- `index`：`0 … total-1` 连续；`streamId` 每条流唯一。
- `finalKind`：原帧的 `kind`（`snapshot` / `delta` / `cards` / `order` …），手机据此回放进原处理链。
- `at`：网关时间戳。**手机端忽略**（JSON 宽松解析）；只作诊断。

## 4. 顺序与并发

- 网关**串行**发流（一条发完再发下一条），实际并发恒为 1（≤ 公布的 2）。
- 手机端仍按 `index` 归位，并**容忍乱序与重复**（重复块幂等覆盖，不重复计数）——
  协议不保证顺序，客户端不能依赖发送顺序。

## 5. 上限与失败语义

| 情况 | 网关行为 | 手机行为 |
| --- | --- | --- |
| 单帧 > `maxFrameBytes` | 切成 chunk 发 | 收齐后回放 |
| 单帧 > 4MiB（重组上限） | **不切不发**，只记一行日志（`… not sent (too-large …)`），连接与其他帧不受影响 | 收不到该流 → 30s 超时 → 记日志 + 断开重连重取快照 |
| 单块 > `maxChunkBytes` | 不应发生（网关按自己公布的值切） | 判客户端错误、丢弃该流（说明公布值与实际不一致） |
| 累计 > `maxReassembledBytes` | — | 丢弃该流，不无限吃内存 |
| 并发流 > `maxStreams` | — | 拒绝新流，已在拼的流不受影响 |
| 字段缺失 / `total` 中途变化 / index 越界 | — | 丢弃该流 + 断开重连 |

**两个“非协议参数”的口径（不要混淆）**：

- `streamTimeout = 30s` 是**客户端内部资源保护**（半截流不能永远占内存），
  **网关不公布也不参与协商**。
- 手机端另有 **8MiB 硬天花板**（`FrameReassembler.absoluteReassembledCap`），
  **有意比网关的 4MiB 宽松**——防止一个坏网关公布超大值把手机内存吹爆。
  这是客户端防御，不是沿用网关值，也不回写进协议。

## 6. 与 `avatarDeferred` 的关系

团队快照 >1MB 时仍走既有 `avatarDeferred` 优化（主帧去 avatar + 若干 `{kind:"avatar"}` 旁路帧）。
两者不冲突：`avatarDeferred` 是**针对团队快照的定向优化**（手机能立刻拿到名册），
`chunk` 是**单帧出口的通用兜底**（任何 kind 的超大帧）。

## 7. 验证现状

- 网关：9 条新用例（中文边界、老客户端整帧、超上限拒绝、块连续）+ 全量 103/0（DSBotDev `48adb20`）。
- 手机：16 条用例（乱序+重复、单块退化、缺块超时后可重开、单块/重组/并发超限、
  index/kind 非法、`total` 中途变化、reset、limits 夹取、`/info` 四种公布形态、能力名契约）。
  XCTest 本机不可跑，用例只到静态编译；真机验证随出包进行。

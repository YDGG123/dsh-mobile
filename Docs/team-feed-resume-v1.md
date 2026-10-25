# 团队流续连与心跳契约 v1（`sync-resume-v1`）— 已并入主契约

> ⚠️ **本文件不再是权威**，内容已无损并入 [`mobile-contract-v1.md`](mobile-contract-v1.md) **§9**（续连与心跳）。
> 保留本文件只为历史链接不 404；**改契约请改主契约**，不要在这里改。
> 由 `scripts/verify-mobile-contract.py` 看守的是主契约（本文件不在闸门覆盖内）。

## 为什么合并

原先「主契约 + 两份分册」并行维护，同一事实有两处可写 —— 分册漂了没有任何红灯。
现在**一份权威**：`mobile-contract-v1.md`。两份分册降级为指针，从根上消除双写。

## 去哪找内容

| 你原本想查 | 现在看 |
| --- | --- |
| 序号语义（按 `base` 链判缺口，不按 +1；`order`/`avatar` 不占 seq） | [`§9.1`](mobile-contract-v1.md#91-序号语义按-base-链判缺口不按-1) |
| 零往返续连 `resume{instanceId, afterSeq, maxDeltas}` | [`§9.2`](mobile-contract-v1.md#92-零往返续连首选) |
| 连接中重试 `sync.from`（限速 1 次 / 5 s） | [`§9.3`](mobile-contract-v1.md#93-连接中重试syncfrom) |
| `sync.done` 六个 reason（没有第七种） | [`§9.4`](mobile-contract-v1.md#94-syncdone-界标与六个-reason) |
| 五个 `SYNC_*` 错误码与动作 | [`§9.5`](mobile-contract-v1.md#95-五个错误码) |
| 客户端只存 `{instanceId, seq}` | [`§9.6`](mobile-contract-v1.md#96-客户端只存两个值) |
| 应用层心跳（25 s / 75 s） | [`§9.7`](mobile-contract-v1.md#97-应用层心跳) |
| 保留窗 10,000 帧 / 15 分钟 / 8 MB | [`§9.8`](mobile-contract-v1.md#98-保留窗与订阅模型) |
| 兼容（老客户端、4400） | [`§9.9`](mobile-contract-v1.md#99-兼容) |

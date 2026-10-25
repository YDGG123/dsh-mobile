# 分块帧契约 v1（`chunked-frames-v1`）— 已并入主契约

> ⚠️ **本文件不再是权威**，内容已无损并入 [`mobile-contract-v1.md`](mobile-contract-v1.md) **§8**（分块传输）。
> 保留本文件只为历史链接不 404；**改契约请改主契约**，不要在这里改。
> 由 `scripts/verify-mobile-contract.py` 看守的是主契约（本文件不在闸门覆盖内）。

## 为什么合并

原先「主契约 + 两份分册」并行维护，同一事实有两处可写 —— 分册漂了没有任何红灯。
现在**一份权威**：`mobile-contract-v1.md`。两份分册降级为指针，从根上消除双写。

## 去哪找内容

| 你原本想查 | 现在看 |
| --- | --- |
| 能力协商（`chunked-frames-v1`、声明范围、`features`/`caps` 容错） | [`§8.1`](mobile-contract-v1.md#81-能力协商) |
| `/info` 三上限与手机端映射、解析容错 | [`§8.1`](mobile-contract-v1.md#81-能力协商) |
| `chunk` 帧七字段 | [`§8.2`](mobile-contract-v1.md#82-chunk-帧字段) |
| 切分/重组规则（UTF-8 边界、乱序、重复） | [`§8.3`](mobile-contract-v1.md#83-切分与重组规则) |
| 超限/并发/超时/夹取 | [`§8.4`](mobile-contract-v1.md#84-超限并发与超时行为) |
| 与 `avatarDeferred`、既有红线的关系 | [`§8.5`](mobile-contract-v1.md#85-与既有红线avatardeferred-的关系) |
| 验证现状（用例数、真机待验） | [`§8.6`](mobile-contract-v1.md#86-验证现状) |

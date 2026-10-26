# 真机验证手册：团队流续连 / 心跳（② 特性）

> 适用包：`1.7.10 (33)` 及以后（含 ② 增量续连 + 应用层心跳）。
> 前置条件（缺一不可，否则验不了）：
> 1. 手机上装的是含 ② 的包（设置 → 关于 → 当前版本应显示 `1.7.10 (33)` 或更高）
> 2. **电脑上的 DSH 宿主已重启**（新网关代码才会加载；不重启则 `resume`/`ping` 都不会被处理）
> 3. 手机与电脑网络通（局域网 / 隧道均可），团队页能看到 Bot 列表
>
> 本文只验 4 条**必须真网络**的路径；其余（分块超时、畸形帧、超限等）已有单测与闸门覆盖。

---

## 0. 先把日志接出来（只做一次）

**方式 A：Console.app（图形界面，推荐）**
1. Mac 上打开 `Console.app`
2. 左侧选中你的 iPhone
3. 右上搜索框输入 `team-chunk`，或点「Action → Include Info Messages / Include Debug Messages」
   （调试级日志默认不显示，`heartbeat pong` 那一类需要打开 Debug）

**方式 B：命令行（更好过滤）**
```bash
# 先确认设备名
xcrun devicectl list devices        # 或：instruments -s devices
# 实时看团队流日志（建议开 debug 级别）
log stream --device-name "你的 iPhone" --level debug \
  --predicate 'subsystem == "ai.dsh.mobile.ios" AND category == "team-chunk"'
```

**判读小抄**
- `category == "team-chunk"`：本文所有日志都在这
- `sync.done reason=...`：一段增量结束的界标
- `sync error SYNC_*`：网关侧判定（见下）
- `heartbeat timeout`：本端判死
- `chunk stream timed out` / `chunk reassembly failed`：分块侧异常

---

## 1. 断网 30 秒再恢复 → 只补增量

**步骤**
1. 打开团队页，确认列表正常显示（等 5 秒让它稳定）
2. iPhone 打开飞行模式，**保持 30 秒**
3. 关闭飞行模式，等回网（约 5–10 秒）

**通过标准**
- 日志出现 `sync.done reason=resume seq=<N>`（**这就是"只补增量"的证据**）
- 期间**没有**整帧快照落地（不应出现 `reason=snapshot`，也没有 `reason=gap`）
- 界面：团队页内容保持，不出现整页闪白/重新加载

**失败判据（需上报）**
- 恢复后只有 `snapshot` 相关行为而没有 `reason=resume` → resume 没命中（可能宿主未重启 / 网关留窗未生效）
- 反复出现 `heartbeat timeout silence=…s; reconnecting` 且一直连不回来 → 网络或 host 不可达，不是本特性问题，先查网络

---

## 2. 网关重启（留窗被清）→ `SYNC_GAP` 自愈

**步骤**
1. 团队页保持前台
2. 在电脑上**重启 DSH 宿主**（关闭再启动，让网关进程重启）
3. 等手机自动恢复（约 5–15 秒）

**通过标准**
- 日志顺序：`sync error SYNC_GAP: waiting for the snapshot that follows` → `sync.done reason=snapshot seq=<N>`
- 界面：一次完整重载后恢复正常，**不需要手动操作**
- **不应该**出现多次重连（团队流不应反复断开重连）

**失败判据**
- 只看到 `SYNC_GAP` 但**没有**紧跟快照 → 网关侧自愈帧没发（查网关日志）
- 反复 `SYNC_GAP` → 网关留窗与 seq 记录不一致，需要网关侧排查

---

## 3. ds-bot 重启（instanceId 变）→ `SYNC_UNKNOWN_INSTANCE` 自愈

**步骤**
1. 团队页保持前台
2. 重启 ds-bot（让 `instanceId` 变化，不要动 DSH 宿主）
3. 等手机自动恢复

**通过标准**
- 日志顺序：`sync error SYNC_UNKNOWN_INSTANCE: waiting for the snapshot that follows` → `sync.done reason=snapshot seq=<N>`
- 界面自愈
- 之后再走一次「第 1 条」的断网测试，仍应出现 `reason=resume`（说明落盘 resume 已按新 instanceId 重写）

**失败判据**
- 出现 `SYNC_UNKNOWN_INSTANCE` 但界面一直空/卡在「正在同步团队」→ 快照没跟上，上报
- 之后再断网无法 resume（一直 `reason=snapshot`）→ 落盘 resume 没更新，上报

---

## 4. 静置 2 分钟：心跳正常，不误判死

**步骤**
1. 团队页前台静置 2 分钟（不要切后台、不要锁屏）
2. 观察日志

**通过标准**
- **没有** `heartbeat timeout`（有就是误判死）
- 打开 Debug 级别时能看到 `heartbeat pong skew=<N>ms`（校时诊断，数值大小不影响通过）
- 界面上 Bot 的实时状态（干活中/等你回答）仍能随电脑端变化刷新

**失败判据**
- 静置期间出现 `heartbeat timeout silence=…s; reconnecting`，但网络其实是通的 → 心跳/`pong` 链路问题，上报（附这段日志与包版本）

---

## 5. 失败上报模板（复制使用）

```
包版本：1.7.10 (33)   ← 设置 → 关于 → 当前版本
宿主是否重启：是 / 否
测试项：#1 断网 / #2 网关重启 / #3 ds-bot 重启 / #4 静置
时间：2026-10-26 09:12 左右
现象：…
日志（team-chunk）：
  <粘贴相关 10–20 行，含 sync.done / sync error / heartbeat 行>
```

> 判定口诀：`reason=resume` 是"只补增量"；`reason=snapshot` / `reason=gap` 是**自愈**（算通过）；
> `heartbeat timeout` 是**本端判死**；`SYNC_GAP` / `SYNC_UNKNOWN_INSTANCE` **不需要断开重连**。

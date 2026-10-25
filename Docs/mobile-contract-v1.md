# 手机端契约 v1 · 现状篇

> 状态：**底稿（现状快照）**，2026-10-26 由 iOSDev 整理。等「分块传输 + 心跳/序号增量」落地后收口为 v1 正式版。
> 目的：把现在**散在三个仓库、四种载体**里的手机端协议收成一份可核对的表，避免「文档说 A、代码是 B」。
> 每个字段都带证据源（`文件:行号`），行号是本次整理时的真实位置；改动代码后行号会漂，以字段名为准重新定位。
> **行号是快照，不是契约**：网关 ①（分块传输）与手机端 ① 正在并行开发，`lib/mobile-v1/server.mjs` 会持续位移
> （整理期间就已漂过一轮：8MB/2MB 两处从 :362/:311 移到 :412/:353）。所以契约的正确性由
> `scripts/verify-mobile-contract.py` 的**内容探针**担保，而不是行号。

三处仓库与简称：

| 简称 | 路径 | 角色 |
| --- | --- | --- |
| **mobile** | `dsh-mobile`（本仓库） | iOS / Android 客户端 |
| **gw** | `dsh-plugin-mobile-gateway` | 桌面端移动网关插件 |
| **bot** | `ds-bot` | 桌面端 Bot 运行时（团队名册 / 卡片的**发布端**） |

---

## 0. 一句话看清三条链路

| 链路 | 谁对谁 | 载体 | 管什么 |
| --- | --- | --- | --- |
| ① 旧网关协议 | 手机 ↔ gw | WS `/ws/mobile` | 会话列表、聊天流、提问/审批、工作区、附件 |
| ② bridge 协议 | 手机 ↔ gw | HTTP `/mobile/v1/*` + WS `/mobile/v1/team`、`/mobile/v1/events` | 团队名册、卡片、配对、动作白名单 |
| ③ 发布端契约 | bot → gw | 进程内 cordis 服务 `dsBotTeam` / `dsBotCards` | 名册帧、卡片帧的**上游形状** |

证据：三条链路共用一套进程与公网入口 — gw `docs/mobile-v1-integration.md:1-5`；`/ws/mobile` 端点 — gw `PROTOCOL.md:6-9`；`/mobile/v1` 前缀 — gw `docs/mobile-v1-integration.md:82`。

---

## 1. 端点清单（HTTP/WS）

前缀 `/mobile/v1`，同一组路由同时挂在宿主 webServer、LAN 监听器（3081）、隧道回环（3082），缺一即公网 404 — gw `docs/mobile-v1-integration.md:18-27`。

| 方法 | 路径 | 入参 | 出参/语义 | 鉴权 | 证据 |
| --- | --- | --- | --- | --- | --- |
| GET | `/mobile/v1/info` | — | `{protocolVersion, serverId, name, capabilities, compatibility}` | 免认证 | gw `docs/mobile-v1-integration.md:84`；实现 gw `lib/mobile-v1/server.mjs:190-197` |
| GET | `/mobile/v1/state` | — | TeamState（bots/rooms/models/mainBotIds/work…） | 需 token | gw `docs/mobile-v1-integration.md:88` |
| GET | `/mobile/v1/conversations` | — | 会话列表（bots + 群聊 + 普通会话行） | 需 token | gw `docs/mobile-v1-integration.md:89`；普通会话行字段 gw `docs/mobile-v1-integration.md:31-42` |
| GET | `/mobile/v1/conversations/{id}/messages` | `limit`、`cursor` | 历史分页；`limit` 限 1–100 | 需 token | gw `docs/mobile-v1-integration.md:90`；上限 gw `lib/mobile-v1/server.mjs:230` |
| GET | `/mobile/v1/conversations/{id}/target` | — | 会话目标与 `revision` | 需 token | gw `docs/mobile-v1-integration.md:91` |
| POST | `/mobile/v1/conversations/{id}/cancel` | — | 停止生成（能力位放行时） | 需 token | gw `docs/mobile-v1-integration.md:92` |
| POST | `/mobile/v1/pairings` | `{code, deviceName}` | `{claimToken, requestId, status}` | 免认证 | gw `docs/mobile-v1-integration.md:85` |
| POST | `/mobile/v1/pairings/complete` | `{claimToken}` | pending 或 Session（可含 `gateway` 字段） | claimToken | gw `docs/mobile-v1-integration.md:86` |
| POST | `/mobile/v1/auth/refresh` | `{refreshToken}` | 新 Session | refreshToken | gw `docs/mobile-v1-integration.md:87` |
| POST | `/mobile/v1/submissions` | `{conversationId, sessionId, requestId, text}` | 幂等发送 | 需 token | gw `docs/mobile-v1-integration.md:93` |
| GET | `/mobile/v1/submissions/{requestId}` | — | 回执恢复 | 需 token | gw `docs/mobile-v1-integration.md:94` |
| POST | `/mobile/v1/actions` | `{endpoint, payload}` | 动作白名单；未在白名单 → 403 `ACTION_NOT_ALLOWED`；载荷 > 32768 字节 → 非法 | 需 token | gw `docs/mobile-v1-integration.md:95`；白名单/上限 gw `lib/mobile-v1/server.mjs:246`、`lib/mobile-v1/actions.mjs:90` |
| GET | `/mobile/v1/devices` | — | 设备列表 + `currentDeviceId` | 需 token | gw `docs/mobile-v1-integration.md:96` |
| DELETE | `/mobile/v1/devices/{id}` | — | 撤销设备 | 需 token | gw `docs/mobile-v1-integration.md:97` |
| POST | `/mobile/v1/read-markers` | `{conversationId, seq}` | 按设备已读水位 | 需 token | gw `docs/mobile-v1-integration.md:98` |
| GET | `/mobile/v1/team` | — | 团队**快照**帧（与 WS 同源） | 需 token | gw `docs/mobile-v1-integration.md:99` |
| GET | `/mobile/v1/cards` | — | 卡片**快照**帧 | 需 token | gw `docs/mobile-v1-integration.md:100` |
| WS | `/mobile/v1/events` | 首帧 `{"type":"auth", token, conversationId, after?}` | 事件 `reset`/`delta`/`message`/`status`/`error`/`ready` | 首帧 auth | gw `docs/mobile-v1-integration.md:101` |
| WS | `/mobile/v1/team` | 首帧 `{"type":"auth","token"}`（**不需要** conversationId） | `ready` → 团队快照 + 卡片快照 → 增量 | 首帧 auth | gw `docs/mobile-v1-integration.md:102`；手机侧同款 gw 客户端见 mobile `Core/GatewayTeamFeed.swift:9-11`、`:170-171` |

管理面（**只在宿主 loopback**，手机端不得调用）：`/mgw/*` — gw `docs/mobile-v1-integration.md:18-27`；面板 `/mgw/mobile-v1/panel` — gw `docs/mobile-v1-integration.md:296-306`。

---

## 2. 配对与鉴权

| 项 | 值/形状 | 约束 | 证据 |
| --- | --- | --- | --- |
| 配对串编码 | **无 padding 的 Base64URL** | 只允许 `A-Z a-z 0-9 - _`；不接受 `=`、裸 JSON、普通 Base64 | gw `PROTOCOL.md:58-78` |
| 配对载荷 | `{version: 2, publicUrl, pairingCode, expiresAt}` | `version` 必须为 2；`expiresAt` 必须未过期 | gw `PROTOCOL.md:60-70` |
| 首次连接子协议 | `dsh-mobile-v1, dsh-pair.<pairingCode>` | 兼容写法 `?pairingCode=`（会进 URL 日志，不优先） | gw `PROTOCOL.md:79` |
| 设备标识 | 请求头 `X-DSH-Device-ID` | 安装级随机 UUID，Keychain 持久保存；缺了配对请求被拒 | gw `PROTOCOL.md:79` |
| 配对成功帧 | `{kind:"paired", …}` | **仅一次**，返回长期设备 token | gw `PROTOCOL.md:1138`；实现 gw `lib/index.mjs:3261` |
| 一次配对两套凭证 | `paired.bridgeSession`（可选） | 缺失只影响 Bot 页，不影响配对本身 | gw `docs/mobile-v1-integration.md:60-66` |
| 反向（bridge 先配对） | Session 里带可选 `gateway:{deviceId, token, wsPath}` | 签发失败只缺字段 | gw `docs/mobile-v1-integration.md:44-58` |
| token 轮换 | 同名设备重复配对**轮换** token，旧 session 立即失效 | 与网关设备轮换语义一致 | gw `docs/mobile-v1-integration.md:66` |
| 关闭码 | `4004` 网关被关闭、`4003` 重新开启鉴权、`4401` token 失效、`4408` 背压断开 | 客户端必须区分处理 | gw `PROTOCOL.md:54-55`；`4401` gw `lib/mobile-v1/server.mjs:395`；`4408` gw `lib/mobile-v1/server.mjs:311`、`:365` |
| 配对码时效 | 配对码 120 s、claim 300 s | — | gw `docs/mobile-v1-integration.md:285` |

---

## 3. 团队名册帧（`/mobile/v1/team`）

### 3.1 帧种类

| kind | 触发 | 关键字段 | 证据 |
| --- | --- | --- | --- |
| `snapshot` | 订阅成功 / 重连 / 降级重算 | `v, kind, instanceId, seq, at, mainBotId, mainBotIds, bots[], rooms[], pending[]` | gw `lib/mobile-v1/team-feed.mjs:263`；形状 gw `docs/mobile-v1-integration.md:145-153` |
| `delta` | 400 ms 合并窗口冲刷、结构变更、3 s 兜底 | `v, kind, instanceId, seq, base, at, ops[]` | gw `lib/mobile-v1/team-feed.mjs:357`；形状 gw `docs/mobile-v1-integration.md:155-157` |
| `order` | 会话 `updatedAt` 轮询变化 | `sessions: {sessionId → updatedAt}`；**不占契约 seq**，自带 `orderSeq` | gw `lib/mobile-v1/team-feed.mjs:187`；形状 gw `docs/mobile-v1-integration.md:159-161` |
| `avatar` | 大快照分帧后的头像旁路帧 | `items: [{id, avatar}]` | gw `lib/mobile-v1/team-feed.mjs:215` |
| `ready` | WS 首帧（auth 通过） | `{type:"ready", serverId, kind:"team", instanceId, seq, cards, degraded}` | gw `lib/mobile-v1/server.mjs:401-406` |

`ops` 取值：`bot.add|bot.update|bot.patch|bot.remove|room.add|room.update|room.patch|room.remove|main.set` — bot `docs/roster-publish-v1.md:91-98`。

### 3.2 Bot 字段

**静态**（只进 `snapshot` / `add` / `update`）— bot `docs/roster-publish-v1.md:112-130`：

| 字段 | 类型 | 约束 | 证据 |
| --- | --- | --- | --- |
| `id` | string | 桌面端 Session id | bot `roster-publish-v1.md:121` |
| `kind` | `"bot"` | 混排判别 | bot `:122` |
| `name` / `role` | string | — | bot `:123-124` |
| `color` | string | **发布端已解析**（调色板 key 或 hex）；手机只映射不推导 | bot `:125`；网关侧口径差异 gw `docs/mobile-v1-integration.md:313-317` |
| `avatar` | `{shape, image?}` | `shape` 必有；`image` 为 PNG/JPEG data URL，**解码后 ≤ 32KB**，只在快照出现一次 | bot `:126`；红线与分帧见 §6 |
| `glyph` | `{shape,color,size,path,layers,eyes}?` | 可选矢量；有 `avatar.image` 时**不发** | bot `:127` |
| `model` / `effort` / `workDir` | string? | 详情页展示 | bot `:128-130` |
| `instructions` | string | 详情页；静态 | bot `:131` |
| `main` / `pinned` / `hidden` | bool / bool / bool? | — | bot `:132-134` |
| `createdAt` | number | — | bot `:135` |
| `sessionId` | string | 当前 own-chat part | bot `:136` |
| `parts` | string[] | 历史分段；手机可不展示 | bot `:137` |

**易变**（快照带当前值，此后只进 `patch`）— bot `docs/roster-publish-v1.md:140-147`：

| 字段 | 类型 | 约束 | 证据 |
| --- | --- | --- | --- |
| `activity` | `idle\|thinking\|working\|waiting\|alert` | 派生值；`alert` = 有审批等待 | bot `:142` |
| `work` | `{roomId?, tool?, target?, said?, note?, since, switching?, speaking?}` | `note` = 工具自述，截 120 字 | bot `:143` |
| `approvals` | `[{sessionId, tool?, reason?, at, answerable}]` | `reason` 截 200 字；**`detail` 不下发** | bot `:144` |
| `pending` | `[{kind:"question"\|"view"\|"secret", sessionId, roomId?, at}]` | **只给类型与时间，不给正文** | bot `:145` |
| `waiting` | `{bots[], rooms[], for[]}` | 三键都传 | bot `:146` |
| `lastActivityAt` | number? | **best-effort，不可作排序权威** | bot `:147` |

### 3.3 Room 字段

静态 `id / kind:"room" / name / pinned / members[] / admin / mode("admin"|"everyone") / notice / createdAt / named?` — bot `docs/roster-publish-v1.md:151`；
易变 `work[{botId,tool?,target?,note?,since,speaking?,switching?}]`、`preview?`、`lastActivityAt?` — bot `:153-157`。

### 3.4 客户端必须守的同步规则

| 规则 | 说明 | 证据 |
| --- | --- | --- |
| `snapshot()` 不消耗 seq | 两台手机先后取快照拿到同一 head | bot `docs/roster-publish-v1.md:98-102`；gateway 自守同一条 gw `docs/mobile-v1-integration.md:178-181` |
| `delta.base ≠ 本地 seq` → 重取快照 | 不做事件回放 | bot `:100-104`；手机实现 mobile `Core/GatewayTeamFeed.swift:366-374` |
| `instanceId` 变更 → 重取快照 | host 重启后 seq 空间重来（含重传头像） | bot `:246-248`；mobile `Core/GatewayTeamFeed.swift:367-370` |
| 一帧内同 id 只出现一次 | 服务端已合并 | bot `:96` |
| `ops > 200` → 改发快照 | 防御超大帧 | gw `lib/mobile-v1/team-feed.mjs:30`、`:424` |
| 排序用 `order.sessions[]` | 置顶优先、再按 `updatedAt` 倒序；**不看 `lastActivityAt`** | gw `docs/mobile-v1-integration.md:182-183`；mobile `Core/GatewayTeamFeed.swift:12` |
| `degraded:true` 时显示「实时状态暂不可用」 | 不得显示成「全部空闲」 | gw `docs/mobile-v1-integration.md:215` |

### 3.5 `/info` 能力位（健康判读的口径）

| 字段 | 含义 | 证据 |
| --- | --- | --- |
| `capabilities.team` / `cards` | 该流路由是否存在 | gw `lib/mobile-v1/server.mjs:192`、`:195` |
| `capabilities.teamDegraded` / `cardsDegraded` | **源服务是否缺席**（活探，与订阅者无关）→ 健康判读看这个 | gw `lib/mobile-v1/server.mjs:193-196`；语义踩坑记录 gw `docs/mobile-v1-integration.md:132-137` |
| `capabilities.teamServing` / `cardsServing` | 此刻是否真在供实时帧（无订阅者时为 false，属正常） | 同上 |

---

## 4. 卡片帧（`/mobile/v1/cards`）

| 项 | 形状 | 约束 | 证据 |
| --- | --- | --- | --- |
| 快照帧 | `{v, kind:"card-snapshot", instanceId, seq, at, cards[]}` | 快照不增加 seq | bot `docs/card-projection-v1.md:15`；gw `docs/mobile-v1-integration.md:167-171` |
| 增量帧 | `{v, kind:"card", instanceId, seq, base, at, ops[]}` | op 只有 `card.shown` / `card.answered` / `card.cleared` | bot `:15`；gw `lib/mobile-v1/card-frames.mjs:26`、`:211` |
| `card.shown` | `id` + 完整 `card` | 正文一次给全，**无 update op**（内容变即新 id） | bot `:65`、`:197` |
| `card.answered` | `id` | 卡从队列移除 | bot `:66` |
| `card.cleared` | `id` | 无答案消失（跳过/群删/成员移除/Bot 删） | bot `:67` |

卡片字段（网关归一化后只保留契约字段，未知字段丢弃）— gw `lib/mobile-v1/card-frames.mjs:60-118`：

| 字段 | 类型 | 约束 | 证据 |
| --- | --- | --- | --- |
| `id` | string | 必填，空则整卡丢弃 | gw `card-frames.mjs:61` |
| `kind` | `"question"\|"view"\|"secret"` | 其它值一律按 `question` 处理 | bot `docs/card-projection-v1.md:83`；gw `:62` |
| `at` | number | 非法则填当前时间 | gw `:63` |
| `botId` / `sessionId` | string? | 空串丢弃 | gw `:64-65` |
| `roomId` + `group` | string? / true | 有 `roomId` 时自动带 `group:true` | gw `:66` |
| `placeholder` | true? | 源服务缺席时的占位卡，**只显示「有卡在等」，不可答复** | gw `:67`；口径 gw `docs/mobile-v1-integration.md:122`、`:187` |
| `questions[]` | array | 最多 20 项；`question` 非 string 的丢弃 | gw `:71-74` |
| `questions[].options[]` | array | 最多 50 项；`label` 是答复权威标识，**逐字保留不裁剪** | gw `:84-89` |
| `questions[].multiple` / `custom` | bool | — | gw `:76`、`:81` |
| `card.approval` | bool | 审批型提问 | gw `:95` |
| `view.{title,text,body}` | string? | 三者全空则丢卡 | gw `:97-104` |
| `secret.{name,description,save}` | string?/bool | **值永不下发**；`save` 缺省 true | bot `:19`；gw `:106-115` |

答复路径的安全默认值：`secret-set` 的 `scope` **必须显式传**（默认 `'bot'`；缺省会按 `'all'` 落库 = 全团队可读）— bot `docs/card-projection-v1.md:182-184`。

---

## 5. 状态机与体积红线（网关这一层）

| 项 | 值 | 含义 | 证据 |
| --- | --- | --- | --- |
| patch 合并窗口 | 400 ms | 窗口内同实体只留一份，绝对值语义 | gw `lib/mobile-v1/team-feed.mjs:129`；bot `docs/roster-publish-v1.md:196-200` |
| 低频 reconcile | 3 s（仅订阅者存在时） | 补发漏打点的派生字段 | bot `:204`；gw 同款 `docs/mobile-v1-integration.md:178` |
| 单头像上限 | 解码后 ≤ **32KB**（base64 字符串 ≤ 44KB） | 超限只裁 `image`，保留 `shape`/`color` | gw `lib/mobile-v1/team-feed.mjs:110-116`（常量 `maxAvatarBytes = 44 * 1024` 在 :116）；产品口径 gw `docs/mobile-v1-integration.md:190-200` |
| 头像格式白名单 | `data:image/png`、`data:image/jpeg` | **SVG 在 iOS 上解不了**，会静默回落成形状块 | gw `lib/mobile-v1/team-feed.mjs:233-239`；踩坑 gw `docs/mobile-v1-integration.md:202-205` |
| 大快照分帧 | 快照 > **1MB** 时先发去 image 主帧（`avatarDeferred:true`），再按 ≤ **256KB** 分批补 `avatar` 帧 | 手机先有内容、头像随后 | gw `lib/mobile-v1/team-feed.mjs:198-232`；明细 gw `docs/mobile-v1-integration.md:200` |
| 团队流 WS 背压 | **8MB**，超限 close `4408` | 与按会话的 `/events`（2MB）分开 | gw `lib/mobile-v1/server.mjs:412`（`TEAM_BUFFER_LIMIT`）；`/events` gw `:353` |
| 连接数 | 全局 100、单设备 5 | 超限 429 `CONNECTION_LIMIT` | gw `lib/mobile-v1/server.mjs:297`、`:329`、`:390` |

---

## 6. glyph 与头像（手机端绘制契约）

发布端字段 — bot `docs/roster-publish-v1.md:127`、`:189-191`；手机端解析 mobile `Core/TeamGlyph.swift:5-16`：

| 字段 | 类型 | 约束 | 证据 |
| --- | --- | --- | --- |
| `shape` | string | 角色 id；发布端已解析，手机不推导 | bot `:127` |
| `color` | string? | 绘制时**不单独使用**（主剪影色取 roster 的 `color`） | mobile `TeamGlyph.swift:39-41` |
| `size` | number | 坐标系边长；非法/缺失回落 100 | mobile `TeamGlyph.swift:50-53` |
| `path` | string | 主剪影 path data；缺失或不可解析 → 整份 glyph 忽略 | mobile `TeamGlyph.swift:55-62` |
| `layers` | array 或 SVG 片段字符串 | 推荐 `[{d, fill, opacity}]`；任一 `d` 不可解析则跳过该层；形态非法 → 整份 glyph 忽略 | mobile `TeamGlyph.swift:64-70`、`:100-112` |
| `eyes` | `[[cx, cy, w, h, tilt]]` | 按电脑端语义**从所有图层挖空**（露底色） | mobile `TeamGlyph.swift:15`、`:68` |

三层兜底（发布端保证顺序自洽）：`glyph`（矢量优先）→ `avatar.image`（用户位图）→ `shape + color` 符号；**有自定义位图时不发 glyph** — bot `docs/roster-publish-v1.md:189`。

---

## 7. 出包与校验协议

> ⚠️ 澄清一处命名：仓库里**没有**叫 `_manifest` 的产物。与「清单/判据」最接近的是下面三样，此处按实际文件名记录，不臆造。

### 7.1 iOS 出包闸门（verified-heads）

| 项 | 规则 | 证据 |
| --- | --- | --- |
| 只构建清单内 head | `scripts/ship-branch.sh` 只接受 `scripts/verified-heads.txt` 里列出的 SHA；不在清单 → **exit 5** | mobile `scripts/ship-branch.sh:61-98` |
| 清单格式 | 一行一个完整 SHA，`#` 后可写备注 | mobile `scripts/verified-heads.txt:1-13` |
| 逃生口 | `--allow-unverified`（会留痕，产物不得作正式交付） | mobile `scripts/ship-branch.sh:36-39`、`:90-96` |
| 并发纪律 | 同一时段只允许一个实例跑（force 更新同一 build 分支） | mobile `scripts/ship-branch.sh:20-23` |
| 提交方式 | 走 Git Data API（blobs→tree→commit→PATCH ref）+ `gh workflow run`，**不 `git push`** | mobile `scripts/ship-branch.sh:4-10` |

### 7.2 iOS 本地闸门

| 闸门 | 覆盖 | 证据 |
| --- | --- | --- |
| `scripts/local-typecheck.sh` | 四路 `swiftc -typecheck`：App / App `-D DEBUG` / Widget / Widget `-D DEBUG`，任一路红 exit 1 | mobile `scripts/local-typecheck.sh:1-10` |
| `scripts/check-project-membership.py` | 磁盘 `.swift` 是否按 **target 归属**正确挂进 pbxproj（防「find 收文件」式假绿） | mobile `scripts/check-project-membership.py:1-8` |
| `scripts/verify-localizations.py` | iOS 目录 + Android 双闸（含 Kotlin 硬编码扫描） | 本仓库 `scripts/verify-localizations.py` |
| `scripts/i18n-inventory.py` | **仅 Swift** 中文字面量盘点（0 处 Android 引用） | mobile `scripts/i18n-inventory.py` |
| `scripts/verify-mobile-contract.py` | **契约闸门**：本文件里的每个端点/帧/字段/红线，必须在网关、ds-bot、手机端实现里找到对应探针；文档改名或实现改名任一侧脱节即红（58 条探针） | mobile `scripts/verify-mobile-contract.py` |

### 7.3 Android 发布清单（latest.json）

| 字段 | 类型 | 约束 | 证据 |
| --- | --- | --- | --- |
| `schemaVersion` | number | 固定 1 | mobile `scripts/publish_r2_release.py:75` |
| `version` / `versionCode` | string / number | `versionCode` 单调不减 | `:75`；校验 `:84-91` |
| `packageName` | string | 取自 AAPT | `:76` |
| `downloadUrl` / `githubDownloadUrl` / `releasePageUrl` | string | R2 / GitHub Release 双通道 | `:77-79` |
| `size` / `sha256` | number / string | **与打包时 `.apk.sha256` 必须一致** | `:70-72`、`:80` |
| `releaseNotes` | string | 来自 `RELEASE_NOTES-v{version}.md` | `:74`、`:80` |
| 发布顺序纪律 | APK 先传并校验（ContentLength + 摘要）→ 再写 `latest.json` | 失败不更新清单 | `:93-118` |
| 回退保护 | R2 已有更高构建号 → 拒绝；同构建号不同摘要 → 拒绝 | — | `:84-91` |

### 7.4 ds-bot 装入判据（发布端存在性）

| 判据 | 期望 | 证据 |
| --- | --- | --- |
| `dsBotTeam` / `dsBotCards` 必须在 `src/host` | 装入判据沿用 install.sh 的存在性检查 | bot `docs/upstream-merge-freeze.md:20` |
| 实机核对 | `grep -c "dsBotCards" $P/node_modules/ds-bot/index.js` **> 0** | bot `docs/install-rollback-notes.md:146` |
| 回滚生效判据 | 同一 grep 结果为 **0** | bot `docs/install-rollback-notes.md:258` |
| 服务缺席的健康信号 | `/info` 的 `cardsDegraded:true`（不是看订阅数） | bot `docs/install-rollback-notes.md:203`、`:271` |
| 运行时导出而非清单声明 | `provide` 是运行时导出，不是包清单项 | bot `docs/install-rollback-notes.md:107` |

---

## 8. 将变：分块传输与心跳/增量（占位）

> 本节**待 ①② 两批落地后补写**，现在只钉住「会变哪些行」，避免正式版收口时漏项。

| 将变项 | 影响面 | 现状基线（证据） |
| --- | --- | --- |
| 分块传输 + 上限写进 `/info` | §5 体积红线整节、§1 的 actions 载荷上限 | 现状上限散在代码：头像 32KB（gw `team-feed.mjs:110-116`）、快照 1MB/256KB（`:198`）、团队流 8MB（`server.mjs:412`） |
| 心跳 + 序号增量补拉 | §3.4 同步规则（现在是「重连=重取快照、不复用增量」） | 现状：无回放缓冲，网关也没有按序号补拉的入参；`order` 帧不占 seq（`team-feed.mjs:187`） |

> 注：原先这里还列了「契约测试闸门」一项，已在 `6d207f1` 落地——`scripts/verify-mobile-contract.py`
> 就是那道闸门（登记在 §7.2），不再是「将变」。

---

## 9. 整理时发现的缺口（不臆造，留给正式版决策）

| # | 缺口 | 现状 |
| --- | --- | --- |
| 1 | 无统一契约测试 | 网关与 ds-bot 各测各的，字段改名不会有跨仓红灯 |
| 2 | `/mobile/v1/state` 与团队帧字段重叠但未声明从属 | `state` 的 bot 行带 `color`，网关侧 redaction 行为与 bridge 不一致（gw `docs/mobile-v1-integration.md:313-317`） |
| 3 | 卡片答复的 `scope` 默认值是安全陷阱 | 已在契约里写明必须显式传（bot `card-projection-v1.md:182-184`），但技术上仍是「不传=全团队」 |
| 4 | `_manifest` 命名不存在 | 本文件按 `verified-heads.txt` / `latest.json` 实际名记录（§7） |
| 5 | 体积红线探针只钉**数字原文**，不钉语义 | 「阈值语义变了但数值没变」（等同值换用）时闸门不红。已知弱点：`1_000_000` / `256 * 1024` / `8 * 1024 * 1024` / `2 * 1024 * 1024` / `32768` 都是纯数字探针。**防法**：红线尽量写成命名常量再做探针（`MAX_OPS = 200` 已是「名字+数值」双锚，最稳）；改常量名时同步改探针。iOSTester 复核认可这条局限为已知边界 |

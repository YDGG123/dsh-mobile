# 手机端契约 v1

> **状态：正式版（2026-10-26 收口）**。①分块传输（§8）与 ②续连/心跳（§9）均已落地出包。
> **本文档是手机端协议的唯二权威**（另一份是 §7.3 的 Android `latest.json`，那是发布清单不是协议）——
> `chunked-frames-v1.md`、`team-feed-resume-v1.md` 两份分册已**降级为指针**，内容全部并入本文件的 §8/§9，不要再改它们。
>
> **真机出问题时先看 [`troubleshooting-matrix-v1.md`](troubleshooting-matrix-v1.md)**（故障自愈矩阵 + 日志速查 + 排查三步法）；
> 本文档回答「协议是什么」，那份回答「坏了怎么看」。两者分工，不重叠。
>
> 目的：把**散在三个仓库、四种载体**里的手机端协议收成一份可核对的表，避免「文档说 A、代码是 B」。
> 每个字段都带证据源（`文件:行号`）。
>
> **行号是快照，不是契约**：代码改动会让行号持续漂移。契约的正确性由
> `scripts/verify-mobile-contract.py` 的**内容探针**担保——它把本文档的每条断言钉到真实实现上，
> 文档改名或实现改名任一侧脱节即红（当前 107 条探针 + 2 项枚举精确比对）。

**文档拓扑（一眼看清谁是权威）**：

| 文件 | 角色 | 是否被闸门看守 |
| --- | --- | --- |
| `Docs/mobile-contract-v1.md`（本文件） | **唯一权威**，协议全量 | ✅ 是 |
| `Docs/chunked-frames-v1.md` | 指针 → §8（历史链接用） | ❌ 否（无内容） |
| `Docs/team-feed-resume-v1.md` | 指针 → §9（历史链接用） | ❌ 否（无内容） |
| `Docs/troubleshooting-matrix-v1.md` | **真机排查第一入口**（故障→行为→日志→自愈判定） | ❌ 否（是实现行为记录，非协议） |

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

## 8. 分块传输（`chunked-frames-v1`）

> 权威源：网关 `48adb20`（`lib/mobile-v1/chunked.mjs` + `server.mjs:234-238` 已把三个上限接进 `/info`）。
> 手机端 ① 在 `Core/FrameReassembler.swift`（同期开发）。**未声明能力的客户端行为与旧版逐字节一致**，不存在灰度风险。

### 8.1 能力协商

| 项 | 值 | 约束 | 证据 |
| --- | --- | --- | --- |
| 能力名 | `chunked-frames-v1` | 客户端在握手帧里声明；**不声明 = 永远收整帧**（不掉帧、不改协议版本） | gw `lib/mobile-v1/chunked.mjs:24`、`:86` |
| `/info` 公布位置 | `capabilities.maxFrameBytes` / `maxReassembledBytes` / `maxStreamsPerConnection` | 单位：字节 / 条 | gw `lib/mobile-v1/server.mjs:234-238` |
| `maxFrameBytes` | **512 KiB** | 切分后**每块**的 UTF-8 字节上限 | gw `chunked.mjs:21` |
| `maxReassembledBytes` | **4 MiB** | 单条流重组后的上限；超过则不切不发 | gw `chunked.mjs:22`、`:70` |
| `maxStreamsPerConnection` | **2** | 同一连接同时存在的流数；网关按帧串行发送，实际并发恒为 1，该值是防御性约束 | gw `chunked.mjs:23`、`:71` |

**能力声明的适用范围（重要，别按文档以为两条 WS 都开了）**：

| WS | 网关是否接分块出口 | 手机端是否声明能力 | 结论 |
| --- | --- | --- | --- |
| `/mobile/v1/team` | 是（`emitFrame` 在 `server.mjs:451`） | **是**（auth 帧带 `capabilities`，`GatewayTeamFeed.swift:205-208`） | 团队大帧走分块 |
| `/mobile/v1/events` | 是（`emitFrame` 在 `server.mjs:374`） | **否**（auth 帧由 `encodeBridgeAuthFrame` 生成，`BridgeWsAuthFrame` 只有 type/token/conversationId/after，`BridgeDtos.kt:240-245`） | **今天不会收到 chunk** |

即：网关两条 WS 都能发 chunk，但手机端目前只在团队 WS 声明能力，`/events` 不声明 → 网关按「老客户端」处理、永远发整帧。
`/events` 的 2 MiB 背压红线（§5）仍是它唯一的超限保护，见 §9 缺口 6。

**命名映射（两名一概念，不是笔误）**：

| 概念 | 网关 `/info` 字段名 | 手机端 `Limits` 字段名 | 映射位置 |
| --- | --- | --- | --- |
| 单块上限 | `capabilities.maxFrameBytes` | `FrameReassembler.Limits.maxChunkBytes` | iOS `FrameReassembler.limits(fromInfo:)` |
| 重组上限 | `capabilities.maxReassembledBytes` | `maxReassembledBytes` | 同名 |
| 并发流上限 | `capabilities.maxStreamsPerConnection` | `maxStreams` | 同上 |

**解析容错**（手机端 `limits(fromInfo:)` 的行为，全部有用例守着）：

- 三个字段**一个都没公布** → 返回 nil，保持本地兜底（512 KiB / 4 MiB / 2，与网关当前值一致），**不猜**。
- **只公布部分**也认，其余保持兜底。
- **字符串型数值**（如 `"524288"`）不认，按「没给」处理。
- 握手能力名单容错：网关接受 `capabilities` / `features` / `caps` 三种数组写法，只认字符串项。

网关侧叫 `FrameBytes` 是因为它的出口视角是「一条帧切多大」；手机侧叫 `ChunkBytes` 是因为它只关心「收到的一块多大」。**同一个值的两个视角，映射在解析时一次做完**，不要在别处再换算。

### 8.2 `chunk` 帧字段

| 字段 | 类型 | 语义 | 手机端 | 证据 |
| --- | --- | --- | --- | --- |
| `kind` | `"chunk"` | 帧类型 | 必须识别 | gw `chunked.mjs:74` |
| `streamId` | string | 一条流的标识；同流各块共用 | 按它归组 | gw `chunked.mjs:74`；iOS `Core/FrameReassembler.swift:20` |
| `index` | int | 从 0 起，**必 < `total`**；乱序到达按它归位，重复块幂等覆盖 | 越界判错 | gw `chunked.mjs:74`；iOS `FrameReassembler.swift:21`、`:105` |
| `total` | int | 该流的块数，**≥ 1** | 收齐即重组 | gw `chunked.mjs:74`；iOS `:22` |
| `finalKind` | string | 重组后要走回的**原处理链**（`snapshot` / `delta` / `cards` / `order` …） | 拼完按它分派，不猜 | gw `chunked.mjs:74`；iOS `:24` |
| `part` | string | **按 UTF-8 字符边界**切开的原始 JSON 片段；**不做块级 JSON.parse** | 逐块拼接 | gw `chunked.mjs:12-14`、`:74` |
| `at` | number | 网关发送时刻（时间戳），**仅作诊断**（网关日志/抓包对时序用） | **手机端当前实现选择忽略**：宽松解析、不因该字段报错，也不消费它（`FrameReassembler.chunk(from:)` 不读 `at`） | gw `chunked.mjs:74`；iOS `FrameReassembler.swift:215-222` |

### 8.3 切分与重组规则

| 规则 | 说明 | 证据 |
| --- | --- | --- |
| 只切在字符边界 | 按码点迭代切分；按字节硬切会把中文/emoji 劈成两半，拼回来乱码且 `JSON.parse` 必挂 | gw `chunked.mjs:12-14`、`:29-53` |
| 每块 ≤ `maxFrameBytes` | 单个码点本身超上限（只会在上限 ≤3 字节时发生）单独成块 | gw `chunked.mjs:47-49` |
| 重组 = 拼回原文 | 手机把 `part` 顺序拼成完整 JSON 字符串，再走与整帧完全相同的处理链 | gw `chunked.mjs:4-6`；iOS `FrameReassembler.swift:16-28` |
| 乱序/重复安全 | 块带 `index`，乱序按 `index` 归位；重复块幂等覆盖 | iOS `FrameReassembler.swift:14` |

### 8.4 超限、并发与超时行为

| 场景 | 网关行为 | 手机行为 | 证据 |
| --- | --- | --- | --- |
| 整帧 ≤ `maxFrameBytes` | 直接发整帧（`mode: plain`），不分块 | 无需处理 | gw `chunked.mjs:69` |
| 整帧 > `maxReassembledBytes` | **不切、不发**（`mode: refused`，理由 `too-large`），记日志；宁可让手机保持上一份状态 | — | gw `chunked.mjs:70`、`:55-58` |
| 同时流数 > `maxStreams` | 拒绝（理由 `too-many-streams`），记日志 | — | gw `chunked.mjs:71` |
| 单块超 `maxChunkBytes` | — | 判错 `chunkTooLarge`（说明网关与宣传值不一致） | iOS `FrameReassembler.swift:31-32`、`:50` |
| 流 30 s 内没收齐 | — | 丢弃该流（`streamTimeout`）。**客户端实现细节，非协议字段，网关不公布**。实现上由**清扫任务**推进（间隔 = `max(0.5, 超时/6)`，30 s → 5 s），**不依赖新块到达**——网关发一半停住正是要防的故障形态；超时后与重组失败同路径（记 `team-chunk` 日志 + 断连重取快照） | iOS `FrameReassembler.swift:37`、`:277`；`GatewayTeamFeed.swift:333-348` |
| 客户端防护帽 8 MiB | 网关永远不会发 >4 MiB | 手机 `FrameReassembler.absoluteReassembledCap = 8 MiB`：**有意比服务端宽松**（弹性防护），只有网关不守规矩时才触及 | iOS `FrameReassembler.swift:116` |
| 网关公布离谱值 | — | 一律先经 `Limits.clamped` 夹取再生效：单块 ≤ 2 MiB、重组 ≤ 8 MiB、并发 ≤ 4、超时 ≤ 300 s。**公布值只会被夹小，不会被照搬** | iOS `FrameReassembler.swift:49-57` |

### 8.5 与既有红线、`avatarDeferred` 的关系

- §5 的既有上限**继续有效**（头像 32 KiB、快照 1 MiB 分帧 / 256 KiB 批、团队流 8 MiB、`/events` 2 MiB）——
  分块解决的是「单帧过大撞内存/背压」的根因，不是替代这些产品红线。
- 未声明 `chunked-frames-v1` 的旧客户端：收帧路径完全不变，`4408` 背压逻辑照旧。
- **与 `avatarDeferred` 不冲突、职责不同**：团队快照 >1 MiB 时仍走既有 `avatarDeferred`
  优化（主帧去 avatar + 若干 `{kind:"avatar"}` 旁路帧）——它是**针对团队快照的定向优化**
  （手机能立刻拿到名册）；`chunk` 是**单帧出口的通用兜底**（任何 kind 的超大帧都适用）。

### 8.6 验证现状

| 侧 | 用例 | 状态 |
| --- | --- | --- |
| 网关 | 9 条（中文边界、老客户端整帧、超上限拒绝、块连续有序、能力字段容错、`/info` 三值、拼回逐字节一致、参考重组器乱序/缺块/重复） | 已提交 `48adb20` |
| 手机 | 18 条（乱序+重复、单块退化、缺块超时、单块/重组/并发超限、index/kind 非法、`total` 中途变化、reset、`Limits.clamped` 夹取、`/info` 四形态、能力名契约） | `7218198` + `2ca90cb`（P1/P2 补生效） |
| 端到端 | 真长帧 walking | **仍待真机验证**，随出包进行；真机异常按 [`troubleshooting-matrix-v1.md`](troubleshooting-matrix-v1.md) #1/#6 排查 |

---

## 9. 续连与心跳（`sync-resume-v1`）

> 断网/切网/后台回来时**只补增量**，不再整帧重拉；以及发现「TCP 半开、本端以为还连着」。
> 网关实现 `48adb20`（①）与 `719b360`（②），手机实现 `afb64a2`。与 §8 分块**互相独立**：
> 分块管「一帧太大」（§8），续连管「断了怎么接上」（本节）。两份能力**互相独立**，可分别采用。

### 9.1 序号语义（按 `base` 链判缺口，不按 +1）

| 项 | 规则 | 证据 |
| --- | --- | --- |
| `seq` | 单调递增，**每 `instanceId` 独立** | gw `team-feed.mjs:630-651` |
| `snapshot` | 带 `instanceId` + `seq`（当前 head） | bot `roster-publish-v1.md:98-102` |
| `delta` | 带 `base`（合并窗口前的 head）+ `seq`（窗口末帧） | bot `:80-96` |
| **合并窗口会让 seq 跳号** | 400 ms 内多条 delta 并成一帧，`7 → 9` 正常（8 被并进 9） | gw `docs/mobile-v1-integration.md:176` |
| 客户端唯一规则 | `base == 本地 seq` → 应用并把本地 seq 置为 `seq`；否则丢弃并重取快照。**禁止**用「seq 是否 +1」判缺口 | mobile `Core/GatewayTeamFeed.swift:366-374` |
| `order` 帧 | 不占 seq（`seq` 只是参考值，不代表事件位置） | gw `team-feed.mjs:187` |
| `avatar` 旁路帧 | 与 `snapshot` 同 seq，**不参与回放** | gw `team-feed.mjs:198-232` |
| 心跳 / 补增量 | **不占 seq、不触发快照** | gw `server.mjs:463-510` |

### 9.2 零往返续连（首选）

```json
{ "type": "auth", "token": "…",
  "capabilities": ["chunked-frames-v1"],
  "resume": { "instanceId": "…", "afterSeq": 8271, "maxDeltas": 200 } }
```

| 情况 | 网关行为 | 客户端动作 | 证据 |
| --- | --- | --- | --- |
| 命中留窗 | 直接回 delta（**不发整帧快照**）→ 末尾 `sync.done` | 应用增量 | gw `server.mjs:557-568` |
| 未命中 | `{type:'error'}`（`SYNC_GAP` / `SYNC_UNKNOWN_INSTANCE`）+ **紧跟新快照** + `sync.done` | **不要断开重连**，同一连接自愈 | gw `server.mjs:565-586` |
| 无可用状态（首连/被清） | 不写 `resume`，退化成整帧快照 | — | mobile `GatewayTeamFeed.swift:356-358` |

**能力位**：`/info.capabilities.syncResume` 表示该网关是否支持补增量（`true` = 有 `history()` 可回放）；
`heartbeatIntervalSeconds` / `heartbeatTimeoutSeconds` 见 §9.7。老网关缺这些字段时，客户端按「不支持」处理，
退化成整帧快照 + 本地默认心跳（gw `server.mjs:248-251`）。

`maxDeltas` 客户端默认 200，网关夹取上限 1000（`SYNC_MAX_DELTAS` = 200 / `SYNC_MAX_DELTAS_LIMIT` = 1000，gw `server.mjs:60-61`、`:465`）。

### 9.3 连接中重试（`sync.from`）

```json
{ "type": "sync.from", "instanceId": "…", "afterSeq": 8271, "maxDeltas": 200 }
```

语义与 `resume` 相同，用于连接仍在但客户端想主动补一段。**限速 1 次 / 5 s**，超了回 `SYNC_RATE_LIMITED`（gw `server.mjs:504-508`）。

### 9.4 `sync.done` 界标与六个 reason

```json
{ "type": "sync.done", "instanceId": "…", "seq": 8293,
  "reason": "resume|sync.from|caught-up|gap|unknown-instance|snapshot" }
```

**恰好六个值，没有第七种**（由闸门集合精确比对守着，见 §10 缺口 1 与闸门脚本）：

| reason | 含义 | 证据 |
| --- | --- | --- |
| `resume` | auth 里的续连命中，有增量可补 | gw `server.mjs:571` |
| `sync.from` | 连接中重试命中 | gw `server.mjs:508` |
| `caught-up` | 本来就没落后（零帧） | gw `server.mjs:490` |
| `gap` | 留窗有缺口 | gw `server.mjs:486`、`:571` |
| `unknown-instance` | `instanceId` 变了（ds-bot 重启） | gw `server.mjs:480` |
| `snapshot` | **尝试了 resume 但没命中**，已改发整帧快照 | gw `server.mjs:586` |

客户端拿它当「这段增量结束了」的界标，用于日志与界面状态，不猜「还有没有」。
`reason=snapshot` **不是故障**，是自愈标记：前面必有一帧 `SYNC_GAP`/`SYNC_UNKNOWN_INSTANCE`。
未知 reason 值只记日志、不分派分支（网关加值不会让手机走错路）。

### 9.5 五个错误码

| code | 客户端动作 | 证据 |
| --- | --- | --- |
| `SYNC_GAP` | 等紧跟的新快照（**不断开**） | gw `server.mjs:485` |
| `SYNC_UNKNOWN_INSTANCE` | 同上（`instanceId` 变了） | gw `server.mjs:479` |
| `SYNC_UNAVAILABLE` | 清掉落盘 resume，下次走整帧 | gw `server.mjs:467`、`:476` |
| `SYNC_BAD_REQUEST` | 清掉落盘 resume（`afterSeq` 非法） | gw `server.mjs:477`、`:565` |
| `SYNC_RATE_LIMITED` | 忽略（限速） | gw `server.mjs:506` |

**恰好这五个**（同样由闸门集合比对守着）。

### 9.6 客户端只存两个值

`{instanceId, seq}`，按**服务器地址**分键存 `UserDefaults`（mobile `GatewayTeamFeed.swift:384-388`）。
- 网关重启不重置 `seq` → 留窗被清 → `GAP` → 快照；
- ds-bot 重启换 `instanceId` → `UNKNOWN_INSTANCE` → 快照。

所以**不需要在手机上存事件缓冲**。

### 9.7 应用层心跳

| 项 | 值 | 证据 |
| --- | --- | --- |
| 客户端发 | `{type:'ping'}` → 网关回 `{type:'pong', at}`（`at` = 网关时间戳，仅诊断/校时） | mobile `GatewayTeamFeed.swift:405`；gw `server.mjs:498-502` |
| `heartbeatIntervalSeconds` | **25**（默认；`/info` 公布） | gw `server.mjs:58`、`:249` |
| `heartbeatTimeoutSeconds` | **75**（默认；`/info` 公布） | gw `server.mjs:59`、`:250` |
| 判死条件 | 超过 timeout 没收到**任何帧** → 断开重连 | mobile `GatewayTeamFeed.swift:400-404` |
| 客户端夹取 | 间隔 ∈ [5, 300]s，判死 ∈ [2×间隔, 600]s | mobile `BridgeSessionTuning` |
| 与 WS 层 ping 的关系 | 网关自带 **15 s** ping / **75 s** terminate（`server.mjs:394-397`）**互补**：那条清僵死连接，这条发现「TCP 半开」 | gw `PROTOCOL.md`、`server.mjs:394-397` |
| ping 限速 | 网关丢弃 2 s 内的重复 ping（客户端正常 25 s 一条，不会撞到） | gw `server.mjs:498` |

### 9.8 保留窗与订阅模型

| 项 | 值 | 证据 |
| --- | --- | --- |
| 保留窗 | **10,000 帧 / 15 分钟 / 8 MB**（先到者为准） | gw `team-feed.mjs:119-121`、`:158-174` |
| 常驻订阅 | 即使没有手机连着也保持上游订阅（断线期间事件不丢），宿主侧多约 4 MB 内存 | gw `team-feed.mjs:145-146` |
| 只记 delta | 快照 / avatar / order **不参与回放** | gw `team-feed.mjs:176-180` |
| 卡片流 | **不参与**补增量：重连照旧重取 `card-snapshot`（帧小，不值得单开回放通道） | gw `card-frames.mjs` |

### 9.9 兼容

- 老客户端不发 `resume`、不发 `ping` → 网关行为与旧契约**完全一致**。
- 认证后除 `ping` / `pong` / `sync.from` 外的帧仍按旧契约关闭 **4400**（gw `server.mjs:389`）。
- **其它错误码**（不在五码内）→ 报给界面 `lastError`，不做静默处理。
- 手机端用例：resume 载荷（含 `maxDeltas` 夹取）、`SYNC_*` → 动作映射、resume 落盘（多服务器隔离、非法值不写）、心跳参数协商与夹取。
- 真机日志怎么认、异常怎么归类 → [`troubleshooting-matrix-v1.md`](troubleshooting-matrix-v1.md) #2～#5、#7、#8。

---

## 10. 已知缺口（留给后续决策）

| # | 缺口 | 现状 |
| --- | --- | --- |
| 1 | `/mobile/v1/state` 与团队帧字段重叠但未声明从属 | `state` 的 bot 行带 `color`，网关侧 redaction 行为与 bridge 不一致（gw `docs/mobile-v1-integration.md:313-317`） |
| 2 | 卡片答复的 `scope` 默认值是安全陷阱 | 已在契约里写明必须显式传（bot `card-projection-v1.md:182-184`），但技术上仍是「不传=全团队」 |
| 3 | 体积红线探针只钉**数值原文**，不钉语义 | 「阈值语义变了但数值没变」（同值换用）时闸门不红。已知弱点：`1_000_000` / `256 * 1024` / `8 * 1024 * 1024` / `2 * 1024 * 1024` / `32768` 是纯数字探针。**防法**：红线尽量写成命名常量（`MAX_OPS = 200` 已是「名字+数值」双锚，最稳）；改常量名时同步改探针。iOSTester 复核认可为已知边界 |
| 4 | `/events` 的大帧暴露面待评估 | 网关两条 WS 都接分块出口（`server.mjs:374` events / `:451` team），但手机端只在团队 WS 声明能力（`GatewayTeamFeed.swift:205`），`/events` 的 auth 帧不含 `capabilities`（`BridgeDtos.kt:240-245`），`BridgeChatEngine` 也没有 chunk 分支。**今天**因此不会收到 chunk、行为与旧版一致，安全；**但**一旦 `/events` 出现 >2 MiB 单帧，只剩 §5 的 2 MiB 背压红线（超限 `4408`）这一条路。待评估：给 `/events` 也接分块（需手机端补声明 + chunk 分支），还是把该流大帧限制在 2 MiB 以内 |
| 5 | `_manifest` 命名不存在 | 本文件按 `verified-heads.txt` / `latest.json` 实际名记录（§7.3） |

> 已收口、不再列为缺口的项：
> - ~~无统一契约测试~~ → 已由 `scripts/verify-mobile-contract.py` 落地（106 条探针 + 2 项枚举精确比对，双向核对：文档改名或实现改名任一侧脱节即红）。
> - ~~分块/续连契约散在多份文档~~ → 已并入本文件 §8/§9，两份分册降级为指针。
> - ~~无真机排查指引（故障怎么反应、日志怎么认）~~ → 已由
>   [`troubleshooting-matrix-v1.md`](troubleshooting-matrix-v1.md) 收口：九类故障 + §1.1 六类异常路径 +
>   日志速查（21 条真实字符串）+ 排查三步法 + 12 项关键参数。该文档为**真机反馈第一入口**。

# ds-bot-mobile-app 团队页移植设计（dsh-mobile）

状态：设计稿 v1.2（2026-10-21，Coder 产出，待评审；v1.1 修订 §5/§8-1，v1.2 修订 §2.9 错误码分族）
参考仓库：`/tmp/ds-bot-mobile-app`（clone 自 `https://github.com/MuQY1818/ds-bot-mobile-app`，若丢失：
`git clone --depth 1 https://gh-proxy.com/https://github.com/MuQY1818/ds-bot-mobile-app.git`）
本文只做设计，未改动任何 dsh-mobile 产品代码。协议解析可行性已用独立 spike 验证（见 §7）。

> **v1.1 修订记录（2026-10-21）**：按 Chief 评审与 iOSTester 对 bridge `config.schema.json` 的只读发现，§5 由「默认自签 SPKI pin」改为**双模式**——默认模式 B（公网受信、不 pin，走隧道/反代出口），模式 A（自签 + SPKI pin）仅在配对 JSON 带 `tlsPin` 时启用；§8-1 风险 1 同步改写。
>
> **v1.2 修订记录（2026-10-21）**：按 iOSTester 勘误，§2.9 改为两族分表——wire 错误码（服务端信封原样下发、大写）与 SDK 客户端自产码（小写）分开建模与文案映射；§2.7 注明 WS error 帧码处理细节；§6 P1 验收补错误码分族单测。

---

## 0. 结论摘要

| 决策点 | 结论 |
| --- | --- |
| 数据协议 | 直接采纳 `/mobile/v1` HTTP + WS 契约（protocolVersion = 1），字段清单见 §2 |
| bridge 客户端归属 | 协议解码、Token/会话状态机、归并算法、能力门控放 **KMP commonMain**；HTTP/WS 传输留在 Swift 平台层（effect） |
| 团队页状态 | 新增 `SharedTeamStore`（domain `team`），复用现有 `SharedMviEvent` 信封与 fail-closed 规则 |
| MVP 聊天 | 点 bot 进聊天**复用现有 Mobile Gateway 会话链路**（ConversationView / prepareConversation），团队页只做列表与入口；bridge 全量聊天（submissions/红action 流）放第二期 |
| 配对与 TLS | **双模式传输**：默认模式 B=公网受信 HTTPS（隧道/反代出口，不 pin，只绑定配对 origin）；仅当配对 JSON 带 `tlsPin` 才启用模式 A=自签 SPKI pin。统一走 URLSession delegate，ATS 不加例外；理由见 §5 |
| iOS 16 | 全部新 UI 走现有 compat 约定（禁 17+ API），清单见 §4.3 |

---

## 1. 参考实现盘点

`ds-bot-mobile-app` 是 React Native App，团队数据来自宿主上两个插件的组合：

| 组件 | 路径 | 职责 |
| --- | --- | --- |
| Mobile Bridge | `bridge/` | 宿主侧 HTTPS 插件：`/mobile/v1` 路由、设备认证、配对、动作白名单、WS 订阅（`bridge/src/server.js`、`adapter.mjs`、`actions.mjs`、`auth.js`） |
| Desktop companion | `companion/` | 桌面「手机访问 → App」UI：生成配对二维码、批准/拒绝设备（`companion/src/mobile-access-shell.mjs`） |
| mobile-sdk | `packages/mobile-sdk/` | 纯 JS 客户端：URL 校验、token 刷新、消息归并、WS 重连（`index.js` 587 行） |
| RN App | `apps/mobile/src/` | 界面层：`TeamScreen.tsx`（团队页）、`PairingScreen.tsx`、`useWorkspace.ts`（8 秒轮询）、`logic/mobile-access.ts`（能力门控） |
| 原生 TLS 模块 | `apps/mobile/modules/background-notifications/ios/LocalTLS.swift`、Android `LocalTLS.kt` | 自签证书 + SPKI pin 的原生传输 |

要点：bridge 本体不含团队写操作的全量能力（`create-bot`/`set-main` 等属上游宿主契约），bridge 用 `capabilities.actions` 白名单逐个放行；App 端按 `mobileCapabilities` 逐项门控 UI（`apps/mobile/src/logic/mobile-access.ts`）。移植时必须保持「能力驱动 UI」，不能假设端点一定存在。

---

## 2. 协议契约清单（照抄自 mobile-sdk 与 bridge 源码）

### 2.1 通用约定

- Base：`{origin}/mobile/v1`；origin 必须是无 path/query/凭据的根（`validateServerUrl`，`packages/mobile-sdk/index.js:13`）。HTTPS 强制，仅 `allowInsecure` 时允许 `http://localhost|127.0.0.1|[::1]`。
- 认证：`Authorization: Bearer {accessToken}`；token 永不出现在 URL（服务端见 `token|accessToken` 即 400 `TOKEN_IN_URL`）。
- 请求：`redirect: error`、`credentials: omit`；JSON 错误信封 `{ "error": { "code": string, "message": string } }`，HTTP 状态码保留语义。
- 401 处理：`POST /auth/refresh {refreshToken}` 换新 session，重试一次（`retry=false`）；刷新单飞（single-flight），serverId/deviceId 变化即判 `server_changed`。
- 限速（每 IP/每设备）：info 120/min、pair 10/min、complete 120/min、refresh 30/min、常规 600/min、WS upgrade 60/min；pending 配对 claim ≤ 20；设备 ≤ 100；每设备 WS ≤ 5；全局 WS ≤ 100；WS 帧上限 64 KiB；积压 > 2 MiB 主动 4408 踢线。

### 2.2 `GET /info`（免认证）

```jsonc
{
  "protocolVersion": 1,          // 客户端必须校验，不等即 incompatible_protocol
  "serverId": "…",               // 服务器身份；变更 ⇒ 必须重新配对
  "name": "DS Bot",
  "capabilities": { … },         // 对象；App 侧合并为 TeamState.mobileCapabilities
  "compatibility": { "protocolVersion": 1, "missing": ["…"], "message": "…" }
}
```

`capabilities` 关键键（bridge 实际产出，`bridge/src/adapter.mjs:193`）：
`discovery, pairing, conversations, history, stream, send, attachments, cancel, actions[], modelSelection, queue, groups, botProfileEditing, botEditableFields[], memory, schedules, usage, notifications, browser, settings, secrets, approvals, images, files, transcription…`
客户端规则（`logic/mobile-capabilities.ts`）：`actions` 数组存在时**只有列出的 endpoint 允许调用**；`{feature}: false` 一票否决；缺信息一律视为不支持（fail closed）。

### 2.3 `GET /state` → TeamState

服务端实际返回（`bridge/src/adapter.mjs:202`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `revision` | number | 团队状态版本号 |
| `readOnly` | string \| null | 非空 = 只读原因文案（App 以此锁写操作，而不是靠 capability） |
| `bots` | `ConversationRow[]`（`kind:"bot"`） | 每个 Bot 一条，含 `parts`（多 part 会话的其余 sessionId，排除隐藏成员） |
| `rooms` | `ConversationRow[]`（`kind:"room"`） | 群聊；`members` 已过滤为可见 bot id，`admin` 校验后为 bot id 或 null |
| `models` | `{ref,name,providerName?}[]` | 可选模型 |
| `defaultModel` | string \| null | |
| `mainBotIds` | string[] | 主 Bot 有序列表（`mainBotIds[0]` 为 primary） |
| `mainBotId` | string \| null | = `mainBotIds[0]`（兼容旧字段） |
| `questions` | object | 上游宿主填 Question；bridge 恒 `{}` |
| `activity` | object | 同上 |
| `work` | `TeamWork` | 工作态：`{ bots: {botId: WorkStatus}, rooms: {roomId: MemberWork[]}, approvals: [{id,botId,roomId?,sessionId?,answerable?}], waiting: {} }`；bridge 恒空，上游宿主填充 |
| `agents` | array | bridge 恒 `[]` |
| `capabilities` | array | bridge 恒 `[]`（注意：与 `/info.capabilities` 是两回事） |
| `memory` | boolean | memory-view 可用性 |
| `browser` | boolean | |

`ConversationRow` 字段（bridge 投影后）：`id, kind, name, sessionId, role?, model?, effort?, waiting?, pinned?, hidden?, parts[], members[]（仅 room）, admin?（仅 room）, mode?（"everyone"|"admin"）, avatar?{shape,color,image}`。
注意：**bridge 出于 redaction 不下发 `instructions`/`notice`/`color`**；bot 详情的资料编辑受 `botEditableFields: ["model","effort"]` 约束。

App 侧完整契约（`apps/mobile/src/types.ts` 的 `TeamState`，上游宿主才会填满，本设计全部建模为可空）：

```ts
TeamState {
  mobileCapabilities?: object      // ← 客户端从 /info.capabilities 合并进来（useWorkspace.ts:29）
  bots: Bot[]; rooms: Room[]; models: Model[]
  mainBotId?: string; mainBotIds?: string[]
  questions?: Record<id, Question>; activity?: object
  memory?: boolean; readOnly?: string | null; workspaceId?: string
  prefs?: object; colors?: string[]; defaultModel?: string; modelsKey?: string
  work?: TeamWork; permissions?: { defaultPreset?: string; options: {value,name?,description?}[] }
  agents?: object[]; connectors?: object[]
  memoryNews?: object; memoryReviews?: object
  capabilities?: Capability[]; capabilityStatuses?: Record<string, Record<string, CapabilityStatus>>
}
Bot       { id, name, role?, color?, instructions?, model?, sessionId?, waiting?,
            avatar?{shape?,image?,color?}, appliedModel?, effort?, permission?, workDir?,
            pinned?, hidden?, capabilities?[] }
Room      { id, name, members[], admin?, notice?, mode?, named?, pinned?, hidden?, workDir?, permission? }
Model     { ref, name, providerName?, input?[], provider?, contextWindow?, reasoning? }
WorkStatus{ tool?, said?, note?, target?, switching?, since? }   MemberWork = WorkStatus + { botId, speaking? }
```

团队状态**没有 WS 推送**：App 用 8 秒定时器 + 前台切换触发 `Promise.all([state(), conversations(), info()])` 全量刷新，动作成功后立即 `refresh()`（`apps/mobile/src/hooks/useWorkspace.ts`）。

### 2.4 `GET /conversations`

`{ conversations: Conversation[] }`；与 `/state` 的 bots+rooms 同源（`actions.mjs:43` 会把 bots/rooms 拼回 conversations 视图）。`Conversation` 额外可含 `notice?, createdAt?, updatedAt?`。

### 2.5 `POST /actions`：`{ endpoint, payload }` 全集

合法 endpoint 以 `/info.capabilities.actions` 数组为准；bridge 白名单及 payload 字段（`bridge/src/actions.mjs:1`，未知键 = 400 `INVALID_ACTION`，body ≤ 32 KiB）：

| endpoint | payload | 返回 | 说明 |
| --- | --- | --- | --- |
| `models` | `{}` | `{models:[{ref,name,providerName}], defaultModel}` | 模型选择面板 |
| `efforts` | `{ref}` | `{efforts:[{id,name,description?}], default?}` | 指定模型的思考档位 |
| `update-bot` | `{id, model?, effort?}`（model 可为 null=用默认） | `{id, changed:true}` | 仅模型/档位；改前校验 ref 与 effort 合法性 |
| `set-flags` | `{id, pinned?, hidden?}`（布尔） | `{id, pinned?, hidden?}` | 置顶/隐藏；目标可为 bot 或 room |
| `usage` | `{timeZone?}` | `{owners[], models[], kinds[], rows[][], now, scanning}` | 用量统计（需宿主 redactor） |
| `memory-view` | `{botId}` | `{botId, entries:[{scope,topic,text}], sizes, limits, summary, fresh, summarizing}` | 仅 scope=own 条目 |
| `memory-forget` | `{botId, scope:"own", topic?, text}` | 同上 | |
| `schedules` | `{}` | `{available, tasks[]}` | 计划任务列表（≤1000） |
| `schedule-create` | `{target, title, prompt, when}` | `{available, tasks[]}` | `when` 四态：`{kind:"at",at}` / `{kind:"daily",time,timeZone}` / `{kind:"weekly",time,timeZone,weekdays[1..7]}` / `{kind:"every",minutes}` |
| `schedule-delete` | `{sessionId, id}` | 同上 | |
| `queue` | `{conversationId}` | `{conversationId, sessionId, items:[{id,target,requestId?,text}]}` | 排队消息（target = next-turn/next-step） |
| `queue-remove` | `{conversationId, sessionId, itemId}` | `{removed:true,…}` | 409 `QUEUE_CHANGED` 表示已不在队 |

上游宿主契约中 App 还会按能力调用（本 bridge 未放行，设计需保留位）：`set-main {id, main, primary?}`、`create-bot {name, role?, instructions?, color?, avatar?, model?}`、`delete-bot/duplicate-bot`、`create-room {name, members[], admin, greet?}`、`update-room {id, name?/mode?/members?/admin?/notice?…}`、`delete-room`、`set-prefs`、`add-agent`、`approvals`/`approval-resolve {id, decision}`、`answer-question/dismiss-question` 等（`apps/mobile/src/logic/mobile-access.ts:5`）。

写动作的通用约束：`readOnly` 非空 ⇒ 409 `READ_ONLY`；目标不在可见团队 ⇒ 404 `TARGET_DENIED`；sessionId 过期 ⇒ 409 `TARGET_CHANGED`（客户端应刷新 /state 后重试）。

### 2.6 聊天（第二期才接，先留契约）

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/conversations/{id}/messages?cursor&limit(1..100,默认50)` | GET | `{sessionId, messages: Message[], cursor}` |
| `/conversations/{id}/target` | GET | `{conversationId, sessionId, revision, kind}` |
| `/conversations/{id}/cancel` | POST | 停止生成 |
| `/submissions` | POST | `{conversationId, sessionId, requestId, text}`（requestId 全程不变，幂等）；回执 `{accepted, status:"accepted", requestId, conversationId, sessionId, createdAt}`；宿主 4xx ⇒ `status:"rejected"` 持久化 |
| `/submissions/{requestId}` | GET | 回执恢复（重发判定） |
| `/read-markers` | POST | `{conversationId, seq}`（按设备记忆已读） |
| `/actions parts/search/context`、`/uploads`、`/transcriptions`、`/attachments/{id}` | — | 能力位 `parts/search/images/files/transcription` 控制 |

- `Message`：`id, sessionId, seq, role, text, createdAt(number|string), reasoning?, interrupted?, turn?, step?, requestId?, localSubmission?, delivery?("sending"|"unconfirmed"|"rejected"), updatedSeq?, systemEvent?{kind:"working"|"approval"|"resumed"|"cut"|"stopped"|"retry"}, tool?{callId,name,state:"running"|"completed"|"error",durationMs?,input?,output?,isError?,truncated?}, attachments?[{id,type?,name,mediaType?,size?}], pending?, attemptId?`。
- 历史 cursor：`base64url(JSON {v:1, conversationId, sessionId, throughSeq, beforeSeq?})`。
- 归并算法 `mergeMessages` / `applyConversationEvent`（`index.js:76,134`）：按 `id` 去重、`updatedSeq ?? seq` 判新旧、`localSubmission` 与确认回执按 `(sessionId, requestId)` 对账、`stream:{sessionId}:{attemptId}` 作为 pending 行、`replaceAttemptId` 替换、tool 卡片按 `updatedSeq` 原位更新。**这套算法必须整体搬进 KMP，不得在 Swift 重写**（见 §3.1）。

### 2.7 WebSocket：`wss {origin}/mobile/v1/events`

每会话一条连接（与 dsh-mobile 现有 gateway「每会话订阅」模型一致）：

1. 客户端连上后 5 秒内发唯一一帧 `{"type":"auth","token":…,"conversationId":…,"after"?:eventSeq}`；多帧/重复认证 ⇒ 4400 关闭。
2. 服务端回 `{"type":"ready","conversationId":…,"serverId":…}`（握手成功，重连计数清零），随后按 `after` 游标回放。
3. 事件类型（`MobileEvent`，未知 type 必须丢弃）：

| type | 关键字段 | 语义 |
| --- | --- | --- |
| `reset` | `messages[], eventSeq, cursor` | 重连基线快照（不承诺 exactly-once） |
| `delta` | `attemptId, text, reasoning?, index` | 助手流式增量；`index` 回退则丢弃；行 id=`stream:{sessionId}:{attemptId}` |
| `message` | `message, replaceAttemptId?` | 终稿消息；按 `replaceAttemptId` 替换 pending 流 |
| `status` | `busy, phase, clearAttempt?, attemptId?, eventSeq` | turn/start→`busy:true,phase:"running"`；turn/end→`busy:false,phase:"idle"` |
| `error` | `error{code,message}` | SDK 收帧后对 code `toLowerCase()` 再比对 `unauthorized/token_expired/invalid_token` ⇒ 置 refreshNext 断开重连（`index.js:541`）；`token_expired/invalid_token` 是 SDK 防御项，bridge 0.0.6 快照不产生 |
| `ready` | `conversationId, serverId` | 握手确认 |

4. 心跳/寿命：服务端 15s ping，token 每 15s 复验，过期 ⇒ 4401。关闭码：4401/4403=认证（刷新后重连）、4400=坏帧、4408=积压（退避重连）、1012=「重连以继续追流」。断线指数退避 750ms→15s。
5. `eventSeq` 出现即推进客户端游标 `after`，重连带上；事件 `conversationId` 不匹配必须丢弃。

### 2.8 配对流程（pair）

```text
桌面（companion）                         手机
────────────────                        ─────────────────────────────────────
手机访问→App：pairing.create
  ⇒ {url, code, serverId, expiresAt}     ① 扫码/粘贴 JSON（url+code+serverId[+tlsPin]）
     code 120s 有效，一次一码
                                         ② GET /info 校验 protocolVersion 与 serverId
                                         ③ POST /pairings {code, deviceName(≤100)}
                                            ⇒ {claimToken, requestId, status:"pending"}
  桌面显示待批请求（requestId, deviceName）
  pairing.approve / pairing.deny
                                         ④ 每 1.5s 轮询 POST /pairings/complete {claimToken}
                                            pending ⇒ 继续；denied ⇒ 403
                                            approved ⇒ Session{accessToken, refreshToken,
                                              deviceId, serverId, expiresAt}
                                         ⑤ Session 落 Keychain，绑定 serverUrl(+tlsPin)
                                            （serverId/serverUrl/tlsPin 变更 ⇒ server_changed 强制重配）
```

有效期：code 120s、claim 300s、领取后的 session 60s 内可重复领取。设备管理：`GET /devices`（含 `currentDeviceId`）、`DELETE /devices/{id}` 撤销。

### 2.9 错误码（两族分表建模，v1.2 按 iOSTester 勘误改写）

错误码有**两个来源、两种大小写**，KMP 侧建模为两张表，UI 文案映射也按来源分开：

**族 1：wire 错误码（服务端 JSON 错误信封 `error.code`，大写，原样透传）**
SDK `index.js:303` 把 `value.error.code` **原样透传**（不做大小写归一），信封缺 code 时兜底 `request_failed`。bridge 0.0.6 实际会下发的全集（`server.js`/`auth.js`/`actions.mjs`/`adapter.mjs` 汇总）：

| 分组 | 码 |
| --- | --- |
| 认证/会话/传输 | `UNAUTHORIZED` `TOKEN_IN_URL` `SESSION_DENIED` `DISPOSED`(503) `CONNECTION_UNAVAILABLE` `RATE_LIMITED` `ORIGIN_DENIED` `CONNECTION_LIMIT` |
| 配对/设备 | `INVALID_PAIRING_CODE` `PAIRING_DENIED` `PAIRING_NOT_FOUND` `PAIRING_LIMIT` `INVALID_DEVICE_NAME` `DEVICE_LIMIT` `DEVICE_NOT_FOUND` |
| 动作/写状态 | `ACTION_NOT_ALLOWED` `INVALID_ACTION` `READ_ONLY` `TARGET_DENIED` `TARGET_CHANGED` `QUEUE_CHANGED` `TASK_DENIED` `APPROVAL_UNAVAILABLE` |
| 会话/消息/历史 | `CONVERSATION_NOT_FOUND` `SUBMISSION_NOT_FOUND` `REQUEST_ID_CONFLICT` `INVALID_CURSOR` `INVALID_LIMIT` `INVALID_SEQUENCE` `HISTORY_UNAVAILABLE` `INVALID_CONTENT` `INVALID_ATTACHMENTS` `UPLOAD_EXPIRED` `BODY_TOO_LARGE` `VIEW_TOO_LARGE` |
| 投影/redaction | `REDACTION_REQUIRED` `REDACTED_PROJECTION_REQUIRED` `REDACTION_FAILED` `REDACTION_UNAVAILABLE` `STABLE_SUBMIT_REQUIRED` |
| 团队投影 | `TEAM_REFUSED`（上游宿主错误缺 code 时兜底） `TEAM_UNAVAILABLE` |
| 请求/其他 | `INVALID_JSON` `INVALID_REQUEST` `INVALID_PATH` `NOT_FOUND` `UNSUPPORTED` `INTERNAL_ERROR` `REQUEST_ABORTED` `REQUEST_FAILED` |

**族 2：SDK 客户端自产码（状态机/校验抛出，小写，`index.js` 各 throw 点）**
`invalid_url` `insecure_url` `invalid_tls_pin` `missing_pinned_transport` `invalid_response` `unauthorized`(无 session/401 态) `server_changed` `session_changed` `invalid_session` `invalid_receipt` `incompatible_protocol` `invalid_sequence`(读指定 seq 参数校验) `cancelled` `disposed` `timeout` `network_error` `request_failed`（信封缺 code 的兜底，归本族）。
构造期参数校验类（`invalid_input`/`invalid_listener`/`invalid_store`/`missing_fetch`/`missing_websocket`）属编程错误，不进 UI 映射表。

**KMP 建模规则**
- 两个枚举：`BridgeWireErrorCode`（保存原始大写 raw，未知值进 `UNRECOGNIZED(raw)`）与 `BridgeClientErrorCode`；wire 码**不做大小写归一**，未知码 fail-closed 显示原文 + 通用文案；
- `TeamError` 记录来源（wire/client）与 raw code，文案映射两张表各自维护；
- 旧版混排清单里的 `invalid_pairing_code/pairing_denied/device_limit/invalid_cursor/view_too_large/redaction_required` 等实为族 1 大写码（见上表），`token_expired/invalid_token` 仅存在于 WS error 帧的 SDK 防御比对（见 §2.7），不单独建模。

---

## 3. dsh-mobile 集成方案

### 3.1 决策：bridge 客户端核心放 KMP commonMain

在「KMP shared 为业务状态唯一源、Swift 只做镜像与 effect」的既有原则下：

**放 commonMain 的部分**
- 全部 DTO 与 wire 编解码（TeamState/ConversationRow/StreamEvent/ActionEnvelope…）；
- Token/会话状态机（刷新单飞、serverId/tlsPin 绑定校验、401→refresh→retry）；
- `mergeMessages` / `applyConversationEvent` 归并与流式状态机；
- 能力门控（`supportsMobileAction` 语义：actions 白名单 + feature 布尔 + fail-closed）；
- `SharedTeamStore` 业务状态与 Reducer。

**留 Swift 平台层的部分**
- HTTP/WS 传输执行器（URLSession / URLSessionWebSocketTask + 双模式 trust delegate，§5）——与现有 `GatewayClient.swift` 同级，属于「平台能力不进 commonMain」的既定边界；
- Keychain 存取、配对扫码（AVFoundation）、LAN 本地网络权限弹窗。

**理由**
1. 归并算法与刷新状态机是纯逻辑，且是正确性关键（对账/幂等/游标），必须在 commonTest 里测；Swift 复写一份必然漂移（ARCHITECTURE.md 明令禁止平行 Reducer）。
2. Android 第二端直接复用；reference 仓库的 JS 实现（`index.js`）已经证明该层可平台无关。
3. 传输层留在 Swift 恰好符合本项目「KMP 不做 I/O」的边界：KMP 产出**结构化 effect descriptor**（`BridgeHttpRequest` / `BridgeWsOpen`），Swift 执行后把响应/帧作为 Intent 喂回 KMP——与现有 SessionControl 的 request/response Intent 对完全同构。
4. spike（§7）已验证这些形状在 kotlinx.serialization + `ignoreUnknownKeys` 下无需反射与平台 API，commonMain 可承载。

反方案（放 Swift Core）被否：会把协议知识分裂到两端，TeamState 归并/能力门控在 Android 侧缺失，且违背项目第 2 节核心原则。

### 3.2 新增文件落点

```text
shared/src/commonMain/kotlin/com/clarklevis/dsh/shared/
├── protocol/BridgeDtos.kt            # TeamState/ConversationRow/StreamEvent/ActionEnvelope…（§2 全部形状）
├── protocol/BridgeWireDecoder.kt     # 严格解码 + 未知键忽略 + 错误信封解析（复用 protocol/JsonValue 风格）
├── domain/TeamReducer.kt             # 团队状态机（§3.4）
├── domain/TeamMerge.kt               # mergeMessages / applyConversationEvent 的 Kotlin 移植（第二期接入）
└── facade/SharedTeamStore.kt         # domain "team" 的 MVI Store（订阅/Intent/snapshot）

DeepSeekHarnessMobile/Core/
├── BridgeTransport.swift             # effect 执行器：URLSession + 双模式 trust delegate（§5）
├── BridgeTokenStore.swift            # Keychain 存 Session（accessToken/refreshToken/deviceId/serverId/expiresAt/serverUrl/tlsPin）
└── BridgePairingScanner.swift        # 复用现有 GatewayQRScannerView 的相机链路

DeepSeekHarnessMobile/Views/TeamView.swift  # §4 UI
```

spike 的 DTO（`Spikes/mobile-bridge-protocol/protocol/src/commonMain/kotlin/ProtocolSpike.kt`）即 `BridgeDtos.kt` 的底稿，迁入产品时逐字段对照 §2 表格。

### 3.3 SharedTeamStore 设计草案（domain = "team"）

沿用现有 facade 惯例（`subscribe(observer)` 订阅、`snapshot()` 拉初值、Intent 方法返回 `SharedMviDispatchResult`、事件走 `SharedMviEvent{schema,sequence,transactionId,domain,kind,statePayloadJson,effectsJson,…}`）：

```kotlin
// State（KMP 唯一业务状态；Swift 只镜像）
@Serializable data class TeamUiState(
    val phase: TeamPhase = TeamPhase.Idle,          // Idle/Loading/Ready/Offline/Paired-need
    val revision: Long = -1,
    val bots: List<BotRow> = emptyList(),           // 已排序：main 在前，pinned 次之
    val rooms: List<RoomRow> = emptyList(),
    val mainBotIds: List<String> = emptyList(),
    val defaultModel: String? = null,
    val models: List<ModelRow> = emptyList(),
    val readOnlyReason: String? = null,
    val work: TeamWork = TeamWork(),
    val caps: TeamCapabilities = TeamCapabilities(), // 从 /info.capabilities 归一的门控结果
    val lastError: TeamError? = null
)

// Intent（Swift → KMP，全部返回 DispatchResult）
fun refreshTeam(infoJson: String, stateJson: String, conversationsJson: String) // 三响应合流，revision 单调
fun refreshFailed(code: String, message: String)
fun openTeamConversation(conversationId: String)      // effect: bridge-target + 本地路由；MVP 仅做路由
fun setFlags(conversationId: String, pinned: Boolean?, hidden: Boolean?)   // 第二期示例
// Wire 回程（Swift 执行 effect 后喂回）
fun acceptBridgeResponse(effectId: String, status: Int, bodyJson: String)
fun acceptBridgeFrame(frameJson: String)             // WS 事件（第二期）

// Effect（KMP → Swift，effectsJson 数组元素）
@Serializable data class BridgeHttpRequest(
    val effectId: String, val method: String, val path: String,   // 如 "/state"、"/actions"
    val bodyJson: String? = null, val requiresAuth: Boolean = true,
    val timeoutMs: Long = 45_000
)
```

关键不变量（写进 Store，测试覆盖）：
- `revision` 回退的 `/state` 响应丢弃（防止乱序轮询覆盖新状态）；
- 动作成功后由 Swift 端触发一次 `refreshTeam`（等价 RN 的 `await refresh()`）；
- `caps.actions` 未列出的 endpoint，对应 Intent 直接 fail-closed 返回 `action_not_allowed`，不发 effect；
- `readOnlyReason != null` 时所有写 Intent 同样拒绝；
- Event 带同一 `transactionId` 的 state patch + effect，Swift 必须先发布 state 再执行 effect（既有规则）。

### 3.4 TeamReducer 状态机（MVP 范围）

```text
Idle ──pairing ok──▶ Loading ──3 响应齐──▶ Ready(revision N)
                        │                    │ 8s 前台轮询（Swift 定时器驱动 refreshTeam）
                        └─失败─▶ Offline(reason) ◀──任一响应失败──┘
Ready ──openTeamConversation──▶ effect: bridge-target(校验会话可见) → 本地路由 Intent → 现有 ConversationView
任何态 ──server_changed/incompatible_protocol──▶ Paired-need（清 Token，回到配对）
```

第二期再扩展：`setFlags/setMain/updateBot` 等写动作（乐观更新 + 失败回滚 + 强制 refresh）、`work` 工作态订阅（bridge 无推送，仍轮询）、消息流（TeamMerge + per-conversation WS）。

### 3.5 与现有 Gateway 的关系（重要取舍）

dsh-mobile 已有 Mobile Gateway WebSocket（会话/审批/计划任务等）。团队页引入的是**第二协议面**：

- MVP：团队数据来自 bridge（`/mobile/v1`），聊天继续走现有 gateway 会话。点 bot = `openTeamConversation` → 用 bridge 的 bot `sessionId` 匹配 gateway 会话列表，命中则 `prepareConversation`，未命中给出「该 Bot 暂无移动会话」的降级提示。这避开第二期前的最大风险（bridge 红action 流依赖宿主 `bridge.redact`，DHost 侧未就绪）。
- 第二期：视宿主 bridge 能力推进全量聊天（submissions + WS + merge）。届时 `TeamMerge` 从 `Spikes` 底稿迁入，ConversationView 增加数据源开关。

两个协议面共用：配对入口 UI 形态（现有 GatewaySwitcherView 的多 profile 模型可扩展出 bridge profile）、Keychain、主题与导航栈。

### 3.6 错误与 fail-closed

沿用 KMPSharedAdapter 既有规则：Event 丢序/schema 不明/patch 与 effect 语义不一致 ⇒ 拒绝发布并上报 `protocol_notice`；bridge 响应 4xx/5xx 一律把 `error.code` 映射为 TeamError（§2.9 两族文案表，wire 码原样保留不归一），`timeout` 提示「请求可能已生效，先查状态再重试」（submissions 幂等语义在第二期同样适用）。

---

## 4. UI 信息架构（参照 `apps/mobile/src/screens/TeamScreen.tsx`）

### 4.1 页面流

RN 端 `TeamPage = list | bot | group | character | model | memory | schedules | primary | add-main`。MVP 取 `list / bot(只读详情) / group(只读详情) / primary`，其余排后续：

```text
TeamView（AppRoute.team，推入现有 NavigationStack）
├─ list
│   ├─ 分段：Bots | Group chats（RN 的 section tabs；群 Tab 受 caps.groups 门控）
│   ├─ Bots 段
│   │   ├─ Main Bot 卡片：primary 头像+名字 → primary 选择页；多余 mainBotIds 给「移到第一/移除」
│   │   ├─ Bot 列表行：头像(shape/color) + name + role 副标题 + [Main Bot][Hidden] 标签
│   │   │     点行 → bot 详情；行右侧工作态（work.bots[botId] 的 tool/said，第二期）
│   │   └─ New Bot（caps 无 create-bot 时整行隐藏，list 顶部显示 BRIDGE_UNAVAILABLE 提示）
│   └─ Groups 段：Room 列表行（members 名字串接为副标题）→ group 详情
├─ bot（只读 MVP）：头像、name/role、model 行（caps.modelSelection 才可点，第二期开编辑）
│     操作：发消息（进聊天）、置顶/隐藏（set-flags，第二期）、更多面板（memory/schedules，后续）
├─ group（只读 MVP）：成员列表、admin 标注、mode(everyone/admin)、notice（若宿主下发）
└─ primary：Bot 单选列表 → set-main（第二期；MVP 仅展示）
```

数据绑定全部走 `AppStore` 的 team 镜像（`@Published private(set) var team: TeamUiState`），View 只发语义 Intent（`store.refreshTeam()`、`store.openTeamConversation(id)`），与 WorkspaceView 现有模式一致。

### 4.2 组件复用

- 行/卡片样式复用 `SettingsView.swift` 的行组件与 `Theme.swift`；
- 头像：`shape+color` 渲染（bridge 只给形状与颜色，`image` 后续）；
- 入口挂点：`WorkspaceView` 工具栏加「团队」入口（或底部 Tab，评审定）；
- 配对：复用 `GatewayQRScannerView` 扫码 + 手动粘贴 JSON 双通道（RN `PairingScreen` 行为）。

### 4.3 iOS 16 兼容注意点（新代码红线）

| 场景 | 禁用（17+） | 用什么 |
| --- | --- | --- |
| 空态视图 | `ContentUnavailableView` | 项目内自建 compat 视图（`Glass.swift` 同款做法） |
| 状态监听 | `.onChange(of:) { old,new in }` | 单参旧 API / `onChangeCompat` |
| 可观察模型 | `@Observable` 宏 | 继续用 `ObservableObject + @Published` |
| 分段控件动画 | `.symbolEffect` / `sensoryFeedback` | 不加，或 `UIImpactFeedbackGenerator` |
| 列表滚动 | `.scrollTargetBehavior` / `containerRelativeFrame` | 普通 `ScrollView`+`LazyVStack`（现状） |
| Sheet | `presentationCompactAdaptation` | `presentationCompactAdaptationCompat`（已有） |
| 触觉/新手势 | TipKit、SwiftData | 不引入 |
| 其余 | `defaultScrollAnchor`、`visualEffect` | 按改造说明逐条走既定降级 |

另：`NavigationStack`、`.searchable`、`ShareLink`、`presentationDetents` 均 ≥16.0 可用；`CryptoKit.SHA256`（pin 计算）与 `SecTrustEvaluateWithError` 均 16.0 可用。局域网权限（`NSLocalNetworkUsageDescription`，发现服务再加 `NSBonjourServices`）仅模式 A 直连场景需要；默认模式 B 走隧道/反代不触发本地网络弹窗。

---

## 5. 配对与 HTTPS（双模式：公网受信为默认，自签 SPKI pin 为可选）

### 5.0 背景：bridge 不自签证书（事实约束，v1.1 修订依据）

bridge 0.0.6 的 `config.schema.json` 与 iOSTester 的只读核查（[bridge-env-acceptance-plan.md](./bridge-env-acceptance-plan.md) §4）确认：

- `publicUrl` **必填**（直连 TLS 模式除外）：必须是「已存在的 HTTPS 来源」，bridge 只在它后面挂服务，**自身不生成任何证书**；
- `certPath`/`keyPath` 是可选项，只有运维自备证书文件时才生效（两者必须成对），插件没有「自签并把 pin 写进配对 JSON」的逻辑；
- `host` 默认 `127.0.0.1`、`port` 默认 `0`（自动挑空闲口）→ 本机监听默认只在 loopback，手机的 HTTPS 入口天然来自隧道/反代；
- `devAllowInsecure` 只放行显式的 loopback 开发模式。

本机现状：已有 cloudflared 二进制与 tunnel 配置可复用；隧道出口的叶子证书是**公网 CA 签发且会轮换**，对叶子做 SPKI pin 会随轮换立即失效。因此默认路径定为「公网受信、不 pin」（模式 B），自签 + SPKI pin（模式 A）降级为显式选择项。

### 5.1 双模式传输契约

模式由**配对 JSON 是否携带 `tlsPin`** 决定（与 mobile-sdk 语义严格一致：pin 缺省 = 允许系统默认传输；pin 存在 = 必须使用自带的 pinned transport，否则 SDK 直接抛 `missing_pinned_transport`；两种模式都强制 HTTPS，仅 loopback 调试模式允许 http）：

| | 模式 B（默认）：公网受信 | 模式 A：自签 + SPKI pin |
| --- | --- | --- |
| 启用条件 | 配对 JSON 无 `tlsPin` | 配对 JSON 带 `tlsPin`（`sha256:<64hex>`） |
| 典型宿主入口 | cloudflared 隧道 / 反向代理（公网 CA 证书，会轮换） | 宿主自备 `certPath/keyPath` 自签证书（额外工作项，见 §8-1） |
| 证书校验 | 系统默认证书链校验（`performDefaultHandling`），ATS 保持默认严格 | delegate 自行校验「单张叶子证书 + 自签名有效 + 未过期 + SPKI hash == pin」 |
| 额外绑定 | 只允许访问配对 origin 的 scheme+host+port，禁跟随重定向 | 同左，另加 host/port 与 origin 一致性校验 |
| 证书轮换/换机 | CA 正常轮换不受影响 | pin 不匹配即 `server_changed`，强制重配 |

pin 语义（仅模式 A，与 SDK 一致）：`tlsPin = "sha256:" + hex(SHA256(证书 DER 的 SPKI 段))`；客户端校验格式 `^sha256:[a-f0-9]{64}$`，session 绑定 pin，不匹配即 `server_changed`。模式 B 下不计算、不存储 pin。

两模式共同红线（防「证书受信但去错地方」）：
- 传输实例只服务绑定的 origin：发起请求前校验 URL == 配对 origin（无 path/query/凭据，`validateServerUrl` 语义）；任何重定向直接 `completionHandler(nil)`（等价 SDK `redirect: error`）；
- Session 绑定 `serverId/serverUrl`（模式 A 另绑 `tlsPin`），任一变更 ⇒ `server_changed` 强制重配；
- 无论哪个模式，Info.plist 的 ATS 全局/域名例外一律不加（`NSAllowsArbitraryLoads` 是审核敏感项；模式 B 的公网 CA 链在默认 ATS 下即可通过）。

### 5.2 iOS 实现要点（单 delegate，双分支）

```swift
final class BridgeTrustDelegate: NSObject, URLSessionTaskDelegate {
    // 通用（两模式）：willPerformHTTPRedirection → completionHandler(nil)   // 禁重定向
    // serverTrust 挑战：
    //   模式 A（有 pin）：SecTrustSetAnchorCertificates([leaf]) + only ⇒ 校验 SPKI/自签名/未过期 ⇒ evaluate
    //   模式 B（无 pin）：performDefaultHandling（系统校验公网证书链）
}
// HTTP: URLSession(configuration:.ephemeral, delegate:…)
// WS  : URLSessionWebSocketTask 同一 delegate；认证首帧 auth 由 KMP effect 描述，Swift 只发送
// 超时: 45s 请求 / 20s WS 握手 / 指数退避 750ms→15s（对齐 SDK）
// 模式只由配对 JSON 驱动：无 pin ⇒ 模式 B，宿主侧零配合即可联调
```

移植参照：模式 A 的校验逻辑照抄 reference 的 `LocalTLS.swift`（含 DER 手工解析 SPKI、自签名 ECDSA 校验、单证书链约束）；模式 B 无额外密码学逻辑，新增代码只有「origin 绑定 + 禁重定向」。原 v1 的「URLSession pinning vs ATS 例外白名单」对比结论继续成立：ATS 永不加例外，区别只是模式 A 由 delegate 自行信任、模式 B 交给系统。

---

## 6. MVP 切片与验收标准

| 期 | 范围 | 验收标准 |
| --- | --- | --- |
| **P1（MVP）** | 配对（扫码/粘贴 + 桌面批准）、`SharedTeamStore` + `/info /state /conversations` 拉取与 8s 前台轮询、TeamView 列表（Bots/Groups 分段、Main Bot 卡、Bot/群行）、点 Bot → 复用 gateway 会话进入聊天；双模式传输（默认模式 B 公网受信，模式 A 按 pin 启用） | ① 与 reference bridge 对接：配对成功且设备出现在桌面已批准列表；② 团队列表与桌面端 bots/rooms 一致（含 main/pinned/hidden 标注）；③ 断网/超时显示 Offline 与可重试；④ revision 回退不覆盖新状态（单测）；⑤ `caps.actions` 缺失的动作 UI 隐藏（单测 fail-closed）；⑥ iOS 16 模拟器全量类型检查 0 错误；⑦ commonTest 覆盖 DTO 解码（spike 11 例迁入）与 TeamReducer 全部转移；⑧ 错误码两族单测（wire 大写透传不归一、未知 wire 码 fail-closed 进 `UNRECOGNIZED(raw)`、客户端族文案表分离） |
| **P2** | `set-flags` 置顶/隐藏、`update-bot` 模型/档位（models+efforts）、set-main、设备管理（列表/撤销） | 动作后 1s 内 UI 反映；`TARGET_CHANGED/READ_ONLY` 有正确文案与自动 refresh 重试；Keychain session 刷新单飞单测 |
| **P3** | bridge 全量聊天：submissions 幂等发送、WS 订阅、mergeMessages 流、read-markers、历史分页 | 断线重连经 reset 基线无重复消息（mergeMessages 性质单测）；回执 rejected 状态可见；token 过期自动续 |
| **P4** | bot 编辑（instructions 等上游字段）、群管理（create/update-room、成员增删）、memory/schedules/usage 面板、工作态（work/approvals） | 面板仅当对应 capability 全组就绪才渲染（`mobilePanelAllowed` 语义）；缺一即整体隐藏 |

P1 明确不做：任何写操作、消息发送、通知、多 gateway 并存下的 bridge profile 切换细节（先按单 bridge 设计）。

---

## 7. Spike 结果（已完成）

位置：`Spikes/mobile-bridge-protocol/`（独立 Gradle 工程，未加入根 `settings.gradle.kts`，不参与产品构建；已加入 git 忽略评估范围之外，交付后可整体删除或保留作 P1 底稿）。

- `protocol/src/commonMain/kotlin/ProtocolSpike.kt` — TeamState/ConversationRow/StreamEvent/ActionEnvelope/WsAuthFrame 等 DTO + 严格 Json 配置 + `validateTlsPin`/`validateServerOrigin` 镜像实现；
- `protocol/src/commonTest/kotlin/ProtocolSpikeTest.kt` — 11 个用例：info/capabilities、TeamState 全字段（bots/rooms/models/work/approvals/mainBotIds）、未知键前向兼容、6 种 WS 事件类型与未知 type 丢弃、createdAt 双形态（epoch/ISO）、action 信封编码、auth 帧编码（`after` 缺省省略、`type` 恒在）、history/conversations 页、配对三态、pin/origin 校验。

运行：`./gradlew :protocol:jvmTest` → **11 tests, 0 failures**（BUILD SUCCESSFUL）。

验证结论：协议全部形状可用 kotlinx.serialization 建模（无需反射/多态自定义序列化器/平台 API），可安全落 commonMain；两处编码细节被测试钉死——`encodeDefaults=false` 时 auth 帧必须用单独的 `Json{encodeDefaults=true, explicitNulls=false}` 序列化，否则 `type:"auth"` 会被吞掉、可选 `after` 必须缺省而非 `null`。

---

## 8. 风险与开放问题

1. **宿主侧 bridge 未就绪**：dsh 宿主需要有 `/mobile/v1` 兼容插件。HTTPS 入口**默认假设走隧道/反代**（本机已有 cloudflared 二进制与 tunnel 配置可复用；出口为公网 CA 证书且会轮换，故模式 B 不 pin），宿主侧零证书工作。「自签证书 + pin 下发」降级为**备选工作项**：仅当隧道/反代方案不可用时才排期，且启用前提是宿主补「自签证书生成 + pin 写入配对 JSON」，P1 不依赖它。联调兜底：模拟器可用 `devAllowInsecure` 走 loopback http，真机必须有受信 HTTPS origin。宿主侧执行/验收/回滚步骤见 [bridge-env-acceptance-plan.md](./bridge-env-acceptance-plan.md)。P1 前需与宿主排期确认契约兼容 reference bridge 0.0.6 快照。
2. `work` 工作态与 `instructions` 等字段依赖上游宿主的 team 投影/redaction 扩展（`bridge.describe v1 + redactedText`），bridge 快照恒空；P1 UI 必须对空值有体面降级。
3. 双协议面（gateway + bridge）并存时 Keychain 里两套凭据的 profile 管理，需与 `MultiGatewayStore` 的多宿主模型对齐（开放问题，P1 评审时定）。
4. bridge 团队状态无推送，8s 轮询在弱网/后台的耗电需要用 scenePhase 门控（已纳入 P1 验收 ③）。

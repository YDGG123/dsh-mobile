# mobile-gateway 集成 bridge 后的切换日预案（v1）

状态：草案（2026-10-22，iOSTester）｜触发：fork `feat/mobile-v1-bridge` 集成验收通过后由 Chief 派单
前置事实来自只读侦察（§1），**本文件不代表已执行**。

> ⚠️ **历史存档**：原状态「草案·未执行」已于 **2026-10-26 失效** —— bridge 切换早已完成并出包。
> 本文保留作当时预案的存档，**不要按它的步骤再执行一次**。协议权威见 [`mobile-contract-v1.md`](mobile-contract-v1.md)。

---

## 1. 侦察结论（本轮，只读）

### 1.1 旧插件能否不依赖补丁 ds-bot 直读团队数据？——**能**

| 需求 | 来源 | 结论 |
| --- | --- | --- |
| bots/rooms/models/mainBotIds | 原版 ds-bot 的 `/api/bot` 路由 `state` 端点 | ✅ 存在（`.ds-bot.pre-bridge` 0.2.0 `channel.js:114`） |
| 传输方式 | `connection.createSharedFetchHandler('/api')` + owner peer，POST `/api/bot` `{endpoint,payload}` | ✅ bridge 现用同款；旧插件可直接复用（`ROUTE='/api/bot'`，`constants.js:22`） |
| App 用到的 MOBILE_ACTIONS | 原版端点表含 `models/efforts/update-bot/set-flags/usage/memory-view/memory-forget/schedules/schedule-create/schedule-delete` | ✅ 全在 |
| `bridge.describe` | 静态描述对象（`{version:1,stableRequestId:true,redactedText:true,…}`） | ✅ 插件自己产出即可 |
| `bridge.redact` | `rt.redactSecrets` = `redactor([...secrets])` 通用替换（`secrets.js:37-62`，约 30 行） | ✅ 可复刻；需读 ds-bot `secrets.json` + `$DSH_SECRET_*`（**双份密钥来源，需文档化**） |
| `bridge.submit` | 核心是宿主 API：`ctx.sessionController.prompt({requestId,sessionId,mode:'queue',content})`（`channel.js:324`） | ✅ 旧插件已 inject `connection`/可加 `sessionController`，能自己发；外围的身份/stale 校验需按 `state` 重实现 |
| `bridge.submit/redact` 的 pin 校验 | 校验 conversationId↔sessionId↔room members | ⚠️ 需在插件内重写（bridge 里这部分约 40 行） |

**两个真实缺口（必须在集成里补或明确不做）**：
1. **`queue` / `queue-remove`**：bridge 的 `/info.actions` 宣告了这两个动作，但**两个版本的 ds-bot 都没有对应端点**（codex.1 与原版 0.2.0 均无 `case 'queue'/'queue-remove'`）。集成后应**从 actions 白名单移除**或补实现，否则 App 调用会吃 501。当前 App 未做 queue UI，影响面为零。
2. **红action 来源重复**：插件复刻 redact 会与 ds-bot 各持一份密钥来源（同一 `DSH_HOME/bot/secrets.json` + 环境变量），需在 README 声明唯一来源，避免两套判断。

### 1.2 桌面「手机访问 → App」批准 UI 归属——**companion 提供，切换日必须处理**

- `ds-bot-mobile-app/client.js` 命中「手机访问」5 处；fork 的 `client.js` **0 处**
- `ds-bot-mobile-app/cordis.patch.yml` 用 `insert` 注入 web UI
- **结论**：卸载 companion 前，fork 插件必须自带等价批准 UI（把 companion 的这块搬进 `client.js`），否则切换后**配对批准入口消失**，用户重配即锁死。这是切换日的**硬前置**。

### 1.3 当前环境实态（切换前的基线，2026-10-22 12:0x）

| 项 | 现值 |
| --- | --- |
| desktop profile ds-bot | `file:…/ds-bot-0.1.7-codex.1.tgz`（补丁版） |
| bridge / companion | `file:…/ds-bot-mobile-bridge-0.0.6.tgz` / `file:…/ds-bot-mobile-app-0.2.0.tgz` |
| bridge 配置 | `port 50227`、`publicUrl https://matters-lead-victor-minolta.trycloudflare.com`、`home ~/.dsb-mobile-bridge` |
| 隧道 | launchd 常驻 `~/Library/LaunchAgents/com.dsh.bridge-tunnel.plist` → 50227（日志 `/tmp/cf-50227.log`）；mobile-gateway 自己的隧道 → 3082 |
| 回滚资产 | `~/.dsh/profiles/desktop/{package.json,pnpm-lock.yaml}.pre-bridge`、`.ds-bot.pre-bridge`（原版 **0.2.0**）、`~/.dsh/bot/state.json.pre-bridge`、SHA256 manifest 在 `.bridge-artifacts/env-snapshot-manifest.txt` |
| 三件套 tgz | `.bridge-artifacts/`（bridge 0.0.6 / companion 0.2.0 / ds-bot 0.1.7-codex.1） |

---

## 2. 切换前置清单（全绿才开工）

1. [ ] fork 集成分支自测全绿；App 端 **零改动** 能连 `/mobile/v1`（协议逐字节兼容）
2. [ ] fork 自带配对批准 UI（§1.2）
3. [ ] fork 处理 `queue/queue-remove` 白名单（§1.1 缺口 1）
4. [ ] 用**原版 ds-bot 0.2.0** 起一次宿主，确认 `/api/bot` `state` 能返回团队数据（§1.1 第一行）——这一步必须在切换前单独验证，否则 Q2 不成立
5. [ ] 新快照：`cp package.json{,.pre-switch}`、`pnpm-lock.yaml{,.pre-switch}`、`bot/state.json{,.pre-switch}`

## 3. 切换步骤（顺序敏感，每步留证）

1. **停**：bridge/companion 在 profile 中的 bundle 顺序调整前先记录当前 `cordis.patch.yml`
2. **卸** bridge + companion：`pnpm remove ds-bot-mobile-bridge ds-bot-mobile-app`，并从 `dsh.profile.bundles` 移除两项；删除 `cordis.patch.yml` 里的 `ds-bot-mobile-bridge` 配置块
3. **还原 ds-bot**：`pnpm add ./ds-bot-<原版>.tgz`（原版 0.2.0，从 npm 或备份目录；**不用** codex.1）
4. **装 fork 插件**：`pnpm add link:<fork 路径>` 或 tgz，加入 bundles
5. **隧道交割**：卸载 launchd `com.dsh.bridge-tunnel`（`launchctl bootout gui/$UID/com.dsh.bridge-tunnel`）——改由 fork 插件自己的 cloudflare 管理（3082）
6. **重启宿主一次**
7. **验收**（§4）

## 4. 验收清单（切换日）

| # | 用例 | 端点/操作 | 期望 |
| --- | --- | --- | --- |
| S1 | 旧网关协议未回归 | App 旧 gateway 链路（备用主机） | 完整会话/工作区列表、收发、审批全可用 |
| S2 | `/mobile/v1` 存活 | `GET http://127.0.0.1:3082/mobile/v1/info` | 200，`protocolVersion:1`，`missing:[]` |
| S3 | 团队投影 | 配对后 `/state` | bots/rooms/models 齐全且与桌面一致 |
| S4 | 发送 | App 发一条消息 | 经 `sessionController.prompt` 落地，ds-bot 会话收到 |
| S5 | 历史 + 分页 | 拉 `messages?cursor` | 文本已脱敏；分页不重不漏 |
| S6 | 动作 | 置顶/隐藏、改模型档位 | 生效且回执正确 |
| S7 | 配对批准 UI | 桌面「手机访问 → App」 | 生成码、待批准列表、撤销均在（§1.2） |
| S8 | 隧道 | fork 自带 cloudflare 管理 | 重启宿主后地址不变或可自助更换 |

## 5. 回滚（任一步失败，立即执行）

```sh
launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.dsh.bridge-tunnel.plist   # 需要时恢复隧道
cd ~/.dsh/profiles/desktop
cp package.json.pre-switch package.json && cp pnpm-lock.yaml.pre-switch pnpm-lock.yaml
# 或退回更早基线：
cp package.json.pre-bridge package.json && cp pnpm-lock.yaml.pre-bridge pnpm-lock.yaml
rm -rf node_modules/ds-bot && cp -R .ds-bot.pre-bridge node_modules/ds-bot
pnpm install   # 用 Electron 内置 node 跑运行时自带 pnpm
# 重启宿主，复跑 S2/S3
```
配套：`pnpm` 需 `ELECTRON_RUN_AS_NODE=1 "/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness" <runtime>/pnpm/bin/pnpm.cjs`（系统 node v20 跑不动 pnpm 11）。
**DSH_HOME 与团队目录永不清空**；bridge 的配对状态在 `~/.dsb-mobile-bridge`，回滚保留。

## 6. 风险

- fork 自带隧道（3082）与当前 launchd 隧道（50227）**并存期端口/域名混乱** → 切换日必须显式卸载 launchd 项，否则用户不知道该填哪个地址
- 原版 ds-bot 无 `bridge.submit` 的 requestId 幂等语义 → 插件自实现的发送必须自带 requestId，否则重连重发可能重复入队
- 卸载 companion 后，若 fork 的批准 UI 未就位，**用户将无法重新配对**（等同锁死）→ 前置清单 2 是硬门

---

## 7. 执行记录（2026-10-22，**已完成**）

切换已执行完毕，S1–S8 全部通过（S4 真投递按裁决留给真机；真机三项含**群聊发送**由用户实测通过），收尾清理同批完成。

### 终态

| 项 | 终态 |
| --- | --- |
| 插件 | 单一插件 `dsh-plugin-mobile-gateway`（fork，`file:` 本地目录，副本与源码逐文件一致）；bridge / companion 已卸载 |
| ds-bot | 原版 **0.2.0**（无 `bridge.*` RPC，fork 走本地 host-service 兜底） |
| 服务端口 | 宿主 webServer（每次启动分配）+ LAN `3081` + 隧道回环后端 `3082`；三者同挂 `/mobile/v1` |
| 公网入口 | fork 自管 Cloudflare **quick** 隧道 → `3082`；`publicReady: true`、`health: reachable` |
| 生产 home | `~/.dsh/mobile-v1`（serverId `6002f4c5-385e-4e1c-b70a-7a0561a3c832`） |
| 管理面 | 仅 loopback：`/mgw/*`、面板 `/mgw/mobile-v1/panel`；公网访问均 404 |
| 旧协议 | `/ws/mobile` 保留，未认证 upgrade → `401 missing or invalid device credential` |

### 已归档（可回滚，未硬删）

`~/.dsh/archive-switch-20261022/`：`com.dsh.bridge-tunnel.plist`（launchd 旧隧道，已 `bootout`）、`cf-50227.log.last`、`.dsb-mobile-bridge/`（bridge 旧 home）、`.dsh-mobile-v1-test/`（共存期测试 home）。

### 过程中发现并修复的缺陷（均已验证）

1. `mobileV1Enabled` 随包默认开启 → 改为默认关闭、开关权归部署侧
2. 同步返回 handle 被当 Promise 用（`.then` TypeError）→ 中断 apply、连带打死旧协议 LAN/upgrade
3. `sessionController` 未 inject 且 apply 阶段固化 null → history 500（改为按调用解析 + 503 语义 + 5xx 落栈）
4. `createMobileV1Admin` 漏 import → 面板 404
5. 配对 url 为 `wss://…/ws/mobile` 形态（App 校验拒绝）→ 归一化为 https 根 origin
6. mobile-v1 只挂宿主 loopback → 公网 404（改为挂 LAN + 隧道后端三处）
7. 原版 ds-bot `rooms` 无 `sessionId` → 群聊发送恒 `409 TARGET_CHANGED`（取 `room.sessionId ?? room.id`）
8. quick 隧道 DNS 健康检查只跑一次 → `publicReady` 永久红色（改为可重试失败自动复检）

### 遗留

- **quick 隧道域名每次重启都会变** → 用户需在 App「重置服务器地址」换一次地址；长期方案为命名隧道（需 Cloudflare 账号托管域名）
- 设备面存有 2 台 iPhone 记录，其中 `b93b8191…`（重配前的旧配对）已不活跃，建议在面板撤销（未擅自操作，避免误撤用户在用设备）
- App 侧两项兜底修复（`9234445`：revision 门控按 serverId 分段、聊天发送接 TARGET_CHANGED 自动纠偏）未推，随 U4 App 侧一起出包

### 回滚

同 §5。注意：`.pre-switch` 快照对应**切换前**（bridge 共存）状态，回滚会把 Q2 成果一并回退，非必要不用。

# 宿主前提核查报告（ds-bot-mobile-bridge 移植前置）

日期：2026-10-21 ｜ 执行：iOSTester ｜ 方式：**只读**核查，未改动任何环境、未安装/升级任何包

核查对象：`ds-bot-mobile-app`（MuQY1818）bridge 0.0.6 的 [`bridge/COMPATIBILITY.md`](https://github.com/MuQY1818/ds-bot-mobile-app/blob/main/bridge/COMPATIBILITY.md) 与 `docs/installation.md` 提出的两个硬前提。

---

## 1. 宿主版本清单

| 项 | 实测值 | 证据来源 |
| --- | --- | --- |
| DeepSeek Harness.app（官方壳） | `0.2.1-rc.1` | `Contents/Info.plist`：CFBundleShortVersionString / CFBundleVersion；`dshBuildCommit` = `ed75efeffee65f9fe93625bbd76a68cb219aaaf9` |
| 内置 dsh runtime | `@deepseek-ai/dsh-desktop-runtime` **0.2.1-rc.1** | `app.asar!/dsh/package.json`、`app.asar!/dsh/desktop-runtime.json`（另有 `hostProtocolVersion: 4`、node 24.21.0、pnpm 11.28.5） |
| 关键宿主包 | `@deepseek-ai/dsh-api-gateway` / `dsh-typert-protocol` / `dsh-typert-registry` 均 **0.2.1-rc.1** | `app.asar` 内各包 package.json |
| DSH Desktop.app（社区壳，另一实例） | 壳 `dsh-desktop 0.12.0`；asar 内**不含** dsh runtime（bin 只有 node/pnpm 转发脚本） | `Contents/Info.plist`、`app.asar` 文件表 |
| 本会话所属 DSH_HOME | `~/.dsh` | 当前群消息正文出现在 `~/.dsh/bot/state.json` |
| 第二个 DSH_HOME | `~/Library/Application Support/dsh-desktop/harness` | 该 home 下有 `profiles/web`、`.desktop-install-state.json`（firstSeenVersion 0.12.0） |

**要求**（bridge 0.0.6）：公开 DSH **≥ 0.2.1-rc.2**
**判定：不满足** —— 实测低 1 个 rc。磁盘上未找到任何 rc.2 的宿主 runtime / 安装包（`~/Downloads`、`~/.dsh`、`Application Support` 均无）。

---

## 2. bridge 契约（`bridge.describe` / `bridge.submit` / `bridge.redact`）判定

生效的 DS Bot：`~/.dsh/profiles/desktop/node_modules/ds-bot`（0.1.7，位于该 profile 的 `dsh.profile.bundles` 中）

```sh
P="$HOME/.dsh/profiles/desktop/node_modules/ds-bot"
grep -rnoE "bridge\.(describe|submit|redact)" "$P"      # → 0 命中
grep -rno  "bridge" "$P/index.js" "$P/src"               # → 0 命中
```

- `index.js` 的 `inject` 列表为 `['llm','tools','systemPrompt','sessionController','sessions','sessionTitle','workspaceRegistry','connection']`，**没有** bridge / RPC 注册面。
- 全文仅有的 `describe` / `redact` 命中在 `src/host/usage.js`（模型 describe）、`src/host/secrets.js`、`src/host/diagnostics.js`（脱敏函数），与 bridge 契约无关。
- 另一个 home `Application Support/dsh-desktop/harness/profiles/web` 同样 pin 原版 `ds-bot ^0.1.7`。

**判定：不满足**，与 bridge README「原版 npm DS Bot 0.1.7 不满足」一致。

---

## 3. 三个必需 tgz 是否已安装

| 包 | 状态 |
| --- | --- |
| `ds-bot-mobile-bridge-0.0.6.tgz`（bridge） | 未安装（`find ~ -maxdepth 6 -iname "*mobile-bridge*"` 无结果） |
| `ds-bot-mobile-app-0.2.0.tgz`（companion / 配对界面） | 未安装 |
| `ds-bot-0.1.7-codex.1.tgz`（兼容 DS Bot） | 未安装 |

→ 补齐前提 2 需要安装这三个包，属于改环境，须用户同意后由用户或 Chief 执行。

---

## 4. 一条需要澄清的旁证（不构成前提 1 满足）

本机已装 `dsh-plugin-mobile-gateway` **0.10.3**（`~/.dsh` 的 web/desktop 两个 profile 都有）。它的 `lib/dsh-host-adapter.mjs` 明确要求 DSH Connection Peer：

```js
export const DSH_VERSION = '0.2.1-rc.2'
throw new Error('mobile-gateway requires a DSH Connection Peer (DSH 0.2.1-rc.2 or later)')
```

而该网关**此刻正在本机运行**：

```sh
curl -i -H "Connection: Upgrade" -H "Upgrade: websocket" \
  http://127.0.0.1:3082/ws/mobile
# HTTP/1.1 401 Unauthorized
# missing or invalid device credential      ← 网关自身鉴权错误，不是 404
```

`~/.dsh/mobile-gateway-devices.json` 中有一台今天仍活跃的 iPhone 设备记录（`lastSeenAt` 为今日）。

**含义**：该检查是特性判定（`peer.signal instanceof AbortSignal`）而非版本号判定，说明 rc.2 门槛涉及的 **Connection Peer 特性面在本机 rc.1 上很可能已经具备**。
**边界**：这只能证明 mobile-gateway（另一个项目）所需的能力面，**不能**证明 bridge 0.0.6 依赖的其余 rc.2 面已具备；bridge 的公开要求仍写死 0.2.1-rc.2，companion 配对界面也未验证。因此不把它当作前提 1 的通过依据。

---

## 5. 结论与建议

| 前提 | 结论 | 差距 |
| --- | --- | --- |
| 1. 宿主 dsh ≥ 0.2.1-rc.2 | **不满足** | 实测全链路（壳 / 内置 runtime / api-gateway 等包）均为 0.2.1-rc.1，低 1 个 rc；升级通道为 `app-update.yml` 的 nightly |
| 2. DS Bot 提供三个 bridge RPC | **不满足** | 生效的是原版 0.1.7，零 bridge 契约；需换 `ds-bot-0.1.7-codex.1.tgz`，并另装 bridge 0.0.6 + companion 0.2.0 |

- 两项都属「改宿主环境」，按群规须用户同意，Tester 不擅自执行。
- 纯设计工作（协议契约清单、KMP/Swift 取舍、UI 信息架构、MVP 切片）不依赖宿主前提，可以照常推进。
- 依赖前提的联调 / 真机验收，在两个前提补齐前无法开展；若要先验证，建议在**不动现有 profile** 的前提下用独立 DSH_HOME 试跑，并保留实际报错作为证据。

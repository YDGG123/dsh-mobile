# Bridge 环境补齐：执行与验收清单（A 方案）

状态：**待命**（用户已定 A 方案，联调前一次性执行）｜作者：iOSTester｜日期：2026-10-21
依据：`/tmp/ds-bot-mobile-app` 参考仓库 + 本机只读核查（见 [host-prereq-check.md](./host-prereq-check.md)）

> 执行前提：**须 Chief 派单 + 用户同意**。执行会重启宿主与现有 Bot 团队。本文件只记录步骤，未执行任何一条。

---

## 1. 执行前预检（只读，先做）

| # | 检查 | 通过标准 |
| --- | --- | --- |
| P1 | 宿主版本 | `Info.plist` / `app.asar!/dsh/desktop-runtime.json` ≥ `0.2.1-rc.2`（A 方案含升级，须先确认升级完成并重启） |
| P2 | 生效 ds-bot | `~/.dsh/profiles/desktop/node_modules/ds-bot/package.json` 版本与 `bridge.describe` 存在 |
| P3 | 三个 tgz | 已下载且校验来源为 `MuQY1818/ds-bot-mobile-app` release（bridge 单独走 `v0.0.6`） |
| P4 | 端口占用 | 现有监听（mobile-gateway 用 `127.0.0.1:3082/ws/mobile`）与 bridge 配置端口不冲突 |
| P5 | 专用 home | bridge `home` 必须是独立绝对目录，**不得**复用 `~/.dsh` 或 DSH_HOME |
| P6 | HTTPS 入口 | `publicUrl` 已有可信 HTTPS 来源（见第 4 节，bridge 不自签） |

## 2. 执行步骤

1. **升宿主**：走 app 更新通道（`app-update.yml` = nightly）升到 rc.2+，重启；复跑 P1。
2. **换 DS Bot**：用 `ds-bot-0.1.7-codex.1.tgz` 覆盖 desktop profile 的原版 0.1.7（保留团队目录）；复跑 P2。
3. **装插件**：`ds-bot-mobile-bridge-0.0.6.tgz` + `ds-bot-mobile-app-0.2.0.tgz`，按 `cordis.patch.example.yml` 配 `enabled` / `home` / loopback `port` / `publicUrl`。
4. 重启宿主，确认插件加载无报错。

## 3. 验收用例（通过标准）

| # | 用例 | 命令 / 操作 | 期望 |
| --- | --- | --- | --- |
| T1 | Bridge 探活 | `GET {publicUrl}/mobile/v1/ready` | 200，服务已就绪 |
| T2 | 未授权拒绝 | 无凭证 `GET /mobile/v1/state` | 401，不泄露团队数据 |
| T3 | 原版 0.1.7 拒绝 | 换回原版 ds-bot 起一次 | bridge 明确报缺 `bridge.describe/submit/redact`（对应用例见 bridge `test/`） |
| T4 | 配对流程 | companion「手机访问 → App」生成二维码 → 批准设备 | 只扫码不批准不得授权；配对码 2 分钟过期 |
| T5 | `/state` 字段 | 授权后 GET | bots / rooms(groups) / models / work 齐全，与设计文档字段表逐项对齐 |
| T6 | `/actions` | 至少 1 个只读安全动作 | 写入生效并返回回执 |
| T7 | WS 订阅 | 订阅会话，宿主流产生成 | 事件类型与设计文档第 1 节清单一致 |
| T8 | 升级保数据 | 重启宿主后复跑 T4/T5 | 设备授权与回执保留 |

另：bridge 自带 `bridge/test/*.test.mjs`（fixture 驱动），可作为 T3/T5 的基线，执行前先在临时目录跑通。

## 4. 端口与证书预判（本次只读发现）

- `bridge/config.schema.json`：`host` 默认 `127.0.0.1`，`port` 默认 **0**（自动选空闲口）→ **与 mobile-gateway 的 3082 不冲突**，除非人为指定成同端口。
- `publicUrl` 是**必填**（除显式 loopback 开发模式），文档要求「已存在的 HTTPS 来源」，`certPath`/`keyPath` 为可选自带证书；`devAllowInsecure` 仅用于明确的本机开发。
  → **bridge 自身不提供自签证书**。手机上要连的 HTTPS 入口需要反代或隧道（本机已有 cloudflared 二进制与 tunnel 配置可复用）。
- 因此 App 侧 TLS pinning（设计文档第 4 节）要面对的是「隧道 / 反代证书」而不是 bridge 自签证书；pinning 策略需要与 `publicUrl` 的证书来源一起定，否则真机验收会卡在证书信任上。

## 5. 回滚预案

执行前快照：

```sh
cp ~/.dsh/profiles/desktop/package.json  ~/.dsh/profiles/desktop/package.json.pre-bridge
cp ~/.dsh/profiles/desktop/pnpm-lock.yaml ~/.dsh/profiles/desktop/pnpm-lock.yaml.pre-bridge
cp ~/.dsh/bot/state.json                 ~/.dsh/bot/state.json.pre-bridge
cp -R ~/.dsh/profiles/desktop/node_modules/ds-bot ~/.dsh/profiles/desktop/.ds-bot.pre-bridge
```

回滚：停用 bridge → 还原上述快照 → 恢复原版 ds-bot 依赖 → 重启宿主 → 复跑 P1/P2。
注意：bridge 文档要求「先停用旧实例再迁移，避免同端口或同目录争用」；回滚同样先停用再还原。DSH_HOME 与团队目录 **不清空**。

## 6. 未决项

- 宿主升级到 nightly 后拿到的具体版本号未知（可能高于 rc.2）→ 与 bridge 验证过的组合不一定完全一致，验收时须记录实测版本。
- iOS 端集成（KMP/Swift 侧）的验收用例要等设计文档落地后再补，本清单只覆盖宿主侧。

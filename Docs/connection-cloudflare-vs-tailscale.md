# 手机连接网关的两条路（Cloudflare 默认 / Tailscale 备选）

> 用户 2026-11-06 裁定：**Cloudflare 命名隧道是默认入口；Tailscale 只作备选**，但两条都要写清楚。
> 本文写给：① 我们自己以后维护 ② 开源版用户（他们要知道为什么装完还要配东西）。

---

## 一句话结论

| 入口 | 定位 | 要用户做什么 | 速度 | 依赖公网 |
|---|---|---|---|---|
| **Cloudflare 命名隧道** | **默认**，零配置首选 | 什么都不用做（已内置 launchd 常驻） | 一般（受 Cloudflare 边缘影响） | 是 |
| **Tailscale** | **备选**，追求内网直连 | 电脑装 Tailscale 登录 + 手机 App 内登录 | 快（点对点） | 否 |

**客户端行为**：按候选列表顺序尝试，**先 Cloudflare，失败才轮到 Tailscale**，用户无感。

---

## 一、默认路：Cloudflare 命名隧道

### 现在怎么跑的
- 本机 `cloudflared` 命名隧道 `dsh-mobile`，launchd 常驻（`com.dsh.named-tunnel`）
- 公网域名固定：`https://ydgg.dpdns.org`
- 转发链：公网 → 隧道 → `127.0.0.1:3082`（网关的 Cloudflare 入口）→ 手机
- 网关配置：`publicUrl: wss://ydgg.dpdns.org/ws/mobile`

### 优点 / 代价
- **优点**：用户零配置；地址固定，重启不变；不需要路由端口转发。
- **代价**：走公网绕一圈，延迟和稳定性受 Cloudflare 边缘影响（本机实测有时冷启动 20 秒）；依赖 Cloudflare 可用。

---

## 二、备选路：Tailscale（点对点内网直连）

### 为什么手机不用装 Tailscale
**App 里内嵌了一个 Tailscale 节点**（`TsnetNodeClient`：Go/tsnet 经 gomobile 绑定，跑在 App 进程里）。
- 不建 TUN、不申请系统 VPN 槽位
- 登录方式：设置页「内嵌隧道」→「打开登录链接」→ 浏览器授权一次

安卓侧同理（用 `startProxy`，比 iOS 16 更顺）。

### 电脑端必须装（无法省）
Tailscale 是点对点组网，**两端都要在同一个 tailnet 里**才能互相找到。手机那端由 App 内嵌，电脑这端只能真装。

电脑装好登录后拿到的地址形如 `100.x.x.x`（本机实测：`100.105.47.105`，设备名 `imac`）。

### 网关侧要做的两件事

**① 放行 Tailscale 网段 `100.64.0.0/10`**

原 `isPrivateNetworkHostname` 只放行 `10/8`、`172.16/12`、`192.168/16`、`169.254/16`，
**不含 100.64/10**。手机从 tailnet 拨入时源地址是 `100.x`，会被：

```
403 LAN listener accepts private-network clients only
```

修法（`lib/index.mjs`，`isPrivateNetworkHostname` 里加一行）：

```js
(octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127)
```

边界必须记牢：`100.63.x` 和 `100.128.x` 要**仍然拒绝**，公网地址照拒——意图不变，仍是「只收私网」。

**② 手工登记 tailnet 地址**

Tailscale 地址挂在 `utun4` 上，而网关的 `privateLanAddresses()` 把 `utun*` 判成虚拟网卡，
**不会自动广播**这个地址。所以要在 profile 配置里手工加：

```yaml
endpoints:
  - ws://100.105.47.105:3081/ws/mobile
```

### 为什么是 `ws://` 而不是 `wss://`
手机走内嵌 tsnet 节点时，iOS 只能经 `forwardTo`（127.0.0.1 本地转发）——**只能承载明文 ws**。
`wss` 的 TLS 主机名对不上（连的是 127.0.0.1），iOS 16 又没有 `URLSession` 代理 API
（`proxyConfigurations` 是 17+），所以这条明确不做。见 `TsnetTunnel.swift:25-29`。

### 默认顺序怎么保证
客户端 `GatewayIdentity.endpoints()` 的组装是 `[payload.publicUrl] + payload.endpoints`，
所以 `publicUrl`（Cloudflare）**永远排第一**，`endpoints` 里的 tailnet 地址只是候选。
改配置不会把默认入口改掉。

---

## 三、开源版用户要注意什么（重要，别让人以为是坏包）

**别人装了我们的网关，不是「装完就能用」，还需要配一次。** 必须写进 README，否则会被当成 bug。

| 用户情况 | 要做什么 |
|---|---|
| **只想要能用** | 用 Cloudflare 命名隧道（默认路），按面板生成二维码配对即可，**零额外配置** |
| **想要更快/不想走公网** | 自己注册 Tailscale（免费）→ 电脑装并登录 → 手机 App 内登录同一个账号 → 网关配置里加 `endpoints: ws://<内网IP>:3081/ws/mobile` |

**开源版必须说明的三点**：

1. **Tailscale 不是开箱即用**，它需要用户有自己的 Tailscale 账号（免费版够个人用）。
2. **两端必须同一个 tailnet**，这是 Tailscale 的机制，不是我们的限制。
3. **默认仍然走 Cloudflare**——所以不用 Tailscale 的人也完全能用，不会因为没配这条而坏掉。

**版本差异**（都支持，但体验不同）：

| 端 | 支持情况 |
|---|---|
| iOS 17+ | 有系统级代理 API，甚至能支持 `wss`，比 16 更顺 |
| iOS 16 | 只能明文 `ws`（本文档描述的路径） |
| Android | 用 `startProxy`，有现成代理机制，比 iOS 16 好做 |

---

## 四、验证判据

1. **网关放行生效**：`POST /mgw/pair` 带 `publicUrl: ws://100.x.x.x:3081/ws/mobile`
   - 旧代码：400 `publicUrl must use wss:// outside localhost or a private LAN`
   - 新代码：200，返回配对载荷
2. **默认顺序没被改**：客户端候选里 `wss://<Cloudflare 域名>` 仍排第一
3. **真机连通**：手机开内嵌隧道 → 发一条消息 → 电脑端收到；日志出现
   `TunnelDiagnostics.transport("route=node target=…")` 才是真的走了节点
4. **回落正常**：关掉隧道 ≤10s 回到 Cloudflare，消息通路不断
5. **红线没破**：公网地址（如 `8.8.8.8`）依然 403；`100.63.x` / `100.128.x` 依然拒绝

---

## 五、踩过的坑（别再重犯）

1. **`lanEnabled` 不是 Tailscale 的阻塞点**——它早就开着（desktop profile 里 `lanEnabled: true`）。
   曾误判成根因，还往 `web` profile（**不是运行中的那个**）加了一次配置，白做。
   → **改配置前先用 `ps` 确认宿主跑的是哪个 profile 路径。**
2. **上游 README 推荐的其实是 Tailscale Serve**（`tailscale serve --bg 3081` + `wss://<设备>.<tailnet>.ts.net`），
   那条要求手机也装 Tailscale。因为用户不装手机端，才走「App 内嵌节点 + 明文 ws」这条。
   → 两条路别混，问清楚用户愿不愿意在手机上装 App。
3. **`compiler(>=6.3)` 那类伪门槛教训同样适用于此**：判断「能不能用」要问清是
   **环境缺件**、**配置没开**、还是**代码缺陷**——这次三者全占了，一度只看到其中一个。

---

*建立 2026-11-06。对应 iOS build 87 / 网关 0.11.1。*
*相关：`upstream-merge-pitfalls-20261106.md`、`Docs/FIXLOG.md`。*
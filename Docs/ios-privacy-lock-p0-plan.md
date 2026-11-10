# iOS 隐私锁 + 后台快照遮盖 实现调研（P0-AT-05 / AT-13）

- 状态：方案，未落业务代码（Chief 指令：先给方案不改业务）
- 日期：2026-11-11（iOSDev）
- 依据：Docs/orcarness-product-requirements-p0.md §4.7（L193-198）、威胁表 SEC-05 / SEC-11、验收条目 P0-AT-13、P0-AT-05
- 关联：本批已落地的导出授权（Core/LocalAppLog.swift `LogExportGrant.standardRequest`，LAContext `.deviceOwnerAuthentication`）就是本方案的第一个消费者

## 1. 现状与差距

| 项 | 现状 | 差距（对照规格） |
| --- | --- | --- |
| App 隐私锁 | 无。冷启动直接进会话页 | 冷启动/超时后应进入锁定态（SEC-11「冷启动和超时后锁定」） |
| 任务切换器快照 | 无遮盖。聊天正文、附件缩略图直接入快照 | P0-AT-13：快照不得含聊天正文/完整命令/token/附件 |
| 解锁前读凭证 | BridgeTransport / MultiGatewayStore 启动即读 Keychain 并自动重连 | L198：锁定后不能继续读取需解锁的凭证；重连在授权解锁后恢复 |
| Keychain 保护档位 | `AfterFirstUnlockThisDeviceOnly`（SEC-05 已列） | 需评估升档到 `WhenUnlockedThisDeviceOnly`（有代价，见 §5） |
| 通知/Live Activity | 本地通知已有；正文级别未按最小信息收敛 | L194-195、L197（通知收敛与隐私锁同属 P0-AT-13，但本方案只做锁与快照） |

## 2. 总体设计：一个锁状态机 + 三个挂点

```
AppPrivacyLock（新，Core/）
  state: .locked / .unlocked
  lockReason: .coldStart / .backgrounded(timeout) / .manual
  lockAt: Date                     // 进入后台的时刻
  timeout: Duration                // 默认 60s，设置页可调（关闭=0 需二次确认）
  unlock(reason:) async throws     // LAContext .deviceOwnerAuthentication
  lockNow()                        // 手动锁 + 切后台立即锁（不受 timeout 影响）
```

三个挂点（顺序即落地顺序）：

1. **快照遮盖**（视图层，风险最低，见 §3）
2. **UI 门禁**（锁定态渲染锁页；解锁入口走 LAContext）
3. **凭证读门禁**（Keychain/会话 token 读取经过锁；锁定时重连暂停，见 §4）

设计原则：

- **不发明新秘密**：只用系统生物识别 + 设备密码（`.deviceOwnerAuthentication`，允许密码回退）。自设 PIN 就要自管一枚新秘密和找回流程，P0 不做。
- **锁是 App 级语义，与设备锁独立**：设备已解锁 ≠ App 已解锁；App 锁定 ≠ 设备锁定。
- **fail-closed**：任何异常路径（解锁报错、context 失效）一律停在锁定态。
- 遮盖视觉用系统材质（`.ultraThinMaterial` / 纯色），具体样式等 WorkBuddy 出设计稿后套用，本方案不定视觉。

## 3. 挂点一：任务切换器快照遮盖（最关键的技术点）

SwiftUI 的 `scenePhase` 在切后台时**不一定把视图更新渲染进快照**——后台态下 SwiftUI 事务不提交，`if scenePhase == .background { cover }` 经常来不及上屏，快照还是旧帧（聊天正文）。这是本功能最常见的翻车点。

可行做法（两层都做）：

1. **UIKit 直接操作窗口**（保底，保证生效）：在 SceneDelegate/AppDelegate 持有窗口引用，
   - `sceneWillResignActive` / `sceneDidEnterBackground`：立即把一张遮盖视图（`UIVisualEffectView` 或纯色 `UIView`）`addSubview` 到 window 最上层，**同步主线程执行**；
   - `sceneWillEnterForeground`：移除遮盖，再走解锁流程。
   - 快照是系统对 window 图层的截取，直接改图层不依赖 SwiftUI 渲染管线，切后台瞬间必中。
2. **SwiftUI 侧配合**：`scenePhase != .active` 时渲染遮盖层（覆盖聊天页），处理应用内下拉再回前台（inactive）这类 UIKit 回调覆盖不全的场景。

验收判据（阶段一）：

- 切后台后立刻 `xcrun simctl io <UDID> screenshot`，图中无任何会话正文/附件/token；
- 打开任务切换器（模拟器：Cmd+Shift+H 双击）人工核对快照；
- 冷启动到锁页之间不存在「先露出会话一帧再遮盖」的空窗（遮盖在首帧就位，见阶段二的冷启动锁定）。

## 4. 挂点三：解锁前不读凭证（改行为的部分，最后做）

现状链路：启动 → MultiGatewayStore 读 Keychain → GatewayClient 自动重连。锁定态要求：

- 会话 token 的读取收敛到一个门禁点（建议 `BridgeKeychainSessionStore` 读取路径或其上层的 `UnlockGate` actor）：锁定时读不到（抛 `locked` / 返回 nil），并停掉自动重连循环；
- 解锁成功后恢复重连（L198 原文语义）；
- 切后台 → `lockNow()` → 已在途请求不取消已发出的，但**刷新 token / 重新认证不启动**（对应 P0-AT-07 的「refresh 后不得沿用旧 Authorization」语义，两件事共用同一门禁点）；
- 审批等本地动作天然要求进 App + 解锁后才能执行（L197 已有），与门禁点复用同一授权结果。

与 P0-AT-07（iOSCoder 在做）的关系：他改「会话按作用域隔离」，本方案改「读之前必须解锁」。都落在 session store 读路径附近，**实施前必须先合他的批**，避免同文件冲突。顺序：AT-02 先行，隐私锁阶段三随后。

## 5. Keychain accessibility 升档评估（SEC-05）

| 档位 | 锁屏后后台任务可读 | 风险 | 结论 |
| --- | --- | --- | --- |
| `AfterFirstUnlockThisDeviceOnly`（现状） | 可读 | SEC-05 点名 | 保底档 |
| `WhenUnlockedThisDeviceOnly` | 不可读 | 设备锁屏时后台重连/刷新必须失败——但规格本来要求锁定后不读凭证、重连等解锁（L198），所以语义一致 | **目标档**，但必须在阶段三（读路径门禁）之后升，否则只是把崩溃从「读不到」变成「keychain 报错」 |

升档还要过 P0-AT-06 备份检查（备份中不出现长期 token）——`ThisDeviceOnly` 系列本就不进 iCloud 备份，iTunes 加密备份除外，验收时用真机备份抽查。

## 6. 分阶段落地计划（每阶段独立可验收）

| 阶段 | 内容 | 触碰文件域 | 验收判据 |
| --- | --- | --- | --- |
| 1 | 快照遮盖（UIKit window 层 + SwiftUI 配合） | App 入口/SceneDelegate、RootView 加载点 | §3 的三条判据，截图留档 |
| 2 | 锁状态机 + 锁页 + LAContext 解锁 + 冷启动锁定 + 超时重锁 | Core/AppPrivacyLock.swift（新）、RootView、SettingsView（超时设置项） | 冷启动首帧=锁页；后台超 timeout 后回前台=锁页；解锁成功进原页；导出日志的 `LogExportGrant.standardRequest` 复用同一 LAContext 体验 |
| 3 | 凭证读门禁 + 重连暂停/恢复 | session store 读路径、GatewayClient 重连循环（依赖 AT-02 合入） | 锁定期间日志零重连外联（联调日志 + 抓包）；解锁后重连成功 |
| 4 | Keychain 升档 `WhenUnlockedThisDeviceOnly` | BridgeKeychainSessionStore | 升档后全功能回归 + 备份检查（P0-AT-06） |

每阶段交付遵守现行纪律：本地 typecheck 四路全绿 + 单测过 + iOSTester 复核后才算数；UI 视觉（锁页长相）留给 WorkBuddy，阶段 2 只做行为。

## 7. Chief 裁定（2026-11-11）

1. **默认 60 秒超时锁定**；进入后台立即遮盖，不等超时。手动锁定立即生效。这样既满足「超时后锁定」，也满足「后台切换立即遮盖」。
2. **锁页不放审批人、会话名、正文或附件信息**；只保留品牌与通用解锁入口，避免锁页本身成为泄漏面。
3. **通知 / Live Activity / 快照统一归 P0-AT-13，单独开批**；iOS 由 iOSDev 接续，Android 由 AndroidCoder 接续，各自交 QA，不混入日志 AT-11/12。当前日志批先收口。
4. **阶段 1、2 可在当前日志批后推进；阶段 3 必须等 AT-07 动态验收转绿并合入后再做；阶段 4 最后。** 不为了并行而抢改 session store。

编号同时按规格 §7 纠正：日志秘密样本=AT-11，导出授权=AT-12，锁屏取证/隐私锁=AT-05，通知与快照=AT-13；AT-14 是插件更新，不是隐私锁。

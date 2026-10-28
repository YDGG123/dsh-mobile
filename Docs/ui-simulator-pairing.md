# 给模拟器造「已配对」状态的正确姿势（2026-10-29 实测）

> 背景：UI 改造验收要求截图与设计稿逐屏比对，但模拟器没有配对凭证，首页只能截到
> 未配对态。本文记录三种造「已配对态」的路子、各自的坑，以及**唯一可靠的流程**。
> 配对码来源：网关管理接口 `POST http://127.0.0.1:<webServer端口>/mgw/pair`
> （loopback-only、无鉴权，返回 `qrPayload` = App 手动配对框要的 Base64URL 载荷）。

## 三种路子的实测结论

| 路子 | 结果 | 坑 |
| --- | --- | --- |
| ① UI 测试里真配对（XCUITest 点「手动输入」→ typeText 载荷 → 连接） | 配对本身**成功**（弹「完成」、首页进 normal 态），**但凭证不持久** | 测试环境的 Keychain 凭证落盘有坑：`GatewayCredentialVault.store` 后上层 `persist()` 未生效，测试结束 App 被 SIGKILL，profile 丢失。重启仍是未配对态 |
| ② 直接改容器 plist（`Library/Preferences/com.example.dshmobile.plist` 写 `gateway.profiles.v1`） | **白改** | App 的 UserDefaults 读写走 **cfprefsd 缓存**；直接改磁盘文件对 App 不可见（且可能被旧缓存覆盖回去）。文件明明改了，App 读到的还是旧值 |
| ③ **临时单测经 `UserDefaults.standard` 写**（跑在 App 宿主进程里） | ✅ **可靠** | 单测进程就是 App 进程，写 `.standard` = 走 cfprefsd 正常路径，普通启动能读到 |

## 正确流程（路子 ③，逐步）

```bash
# 1. 生成新鲜配对载荷（拿 qrPayload；code 约 2 分钟有效，单次有效）
curl -s -X POST http://127.0.0.1:51942/mgw/pair \
  -H 'Content-Type: application/json' \
  -d '{"publicUrl":"https://ydgg.dpdns.org","name":"Simulator UI 验证"}'
```

2. 在 `DeepSeekHarnessMobileTests` 加临时单测（跑在 App 宿主进程里）：

```swift
final class InjectProfileForScreenshotTests: XCTestCase {
    func testZZInjectSyntheticGatewayProfile() {
        let profile = GatewayProfile(
            gatewayId: "<管理接口返回的 serverId>",   // 必须是合法 UUID v4
            gatewayName: "iMac.local",
            alias: "桌面端",
            endpoints: ["wss://ydgg.dpdns.org/ws/mobile"],
            preferredEndpoint: "wss://ydgg.dpdns.org/ws/mobile",
            deviceKind: "desktopcomputer")
        UserDefaults.standard.set(try! JSONEncoder().encode([profile]),
                                  forKey: "gateway.profiles.v1")   // MultiGatewayStore.profilesKey
    }
}
```

3. `xcodebuild test -only-testing:…/InjectProfileForScreenshotTests`
4. `simctl terminate` → `simctl launch` → App 直接进 normal 态 → 深浅截图。

## 两个必须注意的点

1. **载荷里的 `publicUrl` 必须是 `wss://` 形态**（`PairingPayloadParser` 校验 ws/wss
   scheme；`GatewayIdentity.endpoint` 会把空路径补成 `/ws/mobile`）。管理接口给的
   `https://` 公网地址要手动翻成 `wss://`。
2. **想截「有会话数据」的行（模式·轮次·步数、状态胶囊、运行卡）注入档案不够**——
   会话列表来自网关的历史/团队帧，需要真连接。注入态能验证：工作区卡、网关行
   （断网分支）、空态、tab bar。**有数据的行只能上真机（已配对）验证。**

## 已知副作用

- 注入的 profile 会连一个没有凭证的网关 → 网关行显示「未连接 · 重连中」（断网分支），
  这正好覆盖 21-offline 的结构验证。
- 验证完想清掉：删掉 App 重装（`simctl uninstall`），或在设置里移除该主机。

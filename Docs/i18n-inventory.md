# i18n 中文清单（盘点）

> 由 `scripts/i18n-inventory.py --write` 生成，**不要手改**：重跑即更新。
> 口径、四桶定义、以及不确定性说明都在脚本头部注释里，以那里为准。

## ⚠️ 先读：不确定性

- 带字符串插值的条目靠**前缀匹配目录键**判定：硬编码桶约 **101 条**、漏登记桶约 **5 条**，
  **边缘计数有 ±10 的误差**，不要当精确值引用；不带插值的条目是确定判定。
- `Localizable.xcstrings` 的键会被 Swift 提取成 `%@` / `%lld` 形式，脚本用前缀匹配近似。

## 计数

| 桶 | 条数 | 含义 |
| --- | --- | --- |
| `registered` | 712 | 键就是中文，已登记（重启后即可跟随语言） |
| `localized_default` | 135 | `String(localized:defaultValue:)` 的中文兜底（已本地化） |
| `unregistered` (c) | 5 | **漏登记**：本地化调用里但目录无键 → 补目录（第 ① 步） |
| `hardcoded` (a) | 527 | **真硬编码**：重启/免重启都救不了（第 ③ 步） |
| **合计** | **1379** | 源码含中文字面量（50 个 Swift 文件） |

- 能跟随语言（重启后）：**847**（61.4%）
- 重启也不会变：**532**（38.6%）= 硬编码 527 + 漏登记 5，其中 Widget/LiveActivity 占 109
- 目录自身缺 `en`：**0** 条；`en` 未翻译：**0** 条
- `String(localized:)` 的键不在目录：**5** 处（多为插值键，见不确定性）
- 结构：`AgentLiveActivityWidget` target 是否带 `Localizable.xcstrings`：**否**（否 → 该扩展的文案无论怎么切语言都不变）

## (c) 漏登记：本地化调用里、目录无键

### `DeepSeekHarnessMobile/Views/ConversationView.swift` （2）

- `:1407` `[Text]` \(…) 个附件
- `:2226` `[Text]` \(…)，用时 \(…) 秒…

### `DeepSeekHarnessMobile/Views/RootView.swift` （2）

- `:1136` `[accessibilityLabel]` \(…) 已下载，点击重新下载
- `:1434` `[Text]` \(…) 行 · UTF-8

### `DeepSeekHarnessMobile/Views/PluginsView.swift` （1）

- `:130` `[accessibilityLabel]` \(…)开关

## (a) 真硬编码：不在本地化调用里

### 按文件

-  158  `DeepSeekHarnessMobile/Core/KMPSharedAdapter.swift`
-   78  `DeepSeekHarnessMobile/Core/AppStore.swift`
-   60  `AgentLiveActivityWidget/AgentLiveActivityWidget.swift`
-   50  `DeepSeekHarnessMobile/Views/ConversationView.swift`
-   49  `DeepSeekHarnessMobile/LiveActivity/AgentLiveActivityManager.swift`
-   40  `DeepSeekHarnessMobile/Core/BridgeTeamAdapter.swift`
-   18  `DeepSeekHarnessMobile/Core/GatewayTeamFeed.swift`
-   13  `DeepSeekHarnessMobile/Core/GatewayModels.swift`
-    8  `DeepSeekHarnessMobile/Views/TeamView.swift`
-    7  `DeepSeekHarnessMobile/Core/BridgeTransport.swift`
-    7  `DeepSeekHarnessMobile/Core/MultiGatewayStore.swift`
-    6  `DeepSeekHarnessMobile/Views/SettingsView.swift`
-    4  `DeepSeekHarnessMobile/Core/GatewayClient.swift`
-    4  `DeepSeekHarnessMobile/Views/PluginsView.swift`
-    4  `DeepSeekHarnessMobile/Views/WorkspaceView.swift`
-    3  `DeepSeekHarnessMobile/Core/GatewayProfile.swift`
-    3  `DeepSeekHarnessMobile/Core/VoiceInputAdapter.swift`
-    3  `DeepSeekHarnessMobile/Views/BridgeConversationView.swift`
-    3  `DeepSeekHarnessMobile/Views/HumanQuestionView.swift`
-    3  `DeepSeekHarnessMobile/Views/RootView.swift`
-    2  `DeepSeekHarnessMobile/Core/AgentBackgroundKeepAliveManager.swift`
-    2  `DeepSeekHarnessMobile/Core/BridgeChatEngine.swift`
-    1  `DeepSeekHarnessMobile/Components/ConversationViewport.swift`
-    1  `DeepSeekHarnessMobile/Views/ApprovalRequestView.swift`

### App 侧 `Views/` 的用户可见条目

- `DeepSeekHarnessMobile/Views/ApprovalRequestView.swift:179` `[String]` \(…) 请求执行需要审批的操作
- `DeepSeekHarnessMobile/Views/BridgeConversationView.swift:70` `[banner]` 正在连接…
- `DeepSeekHarnessMobile/Views/BridgeConversationView.swift:72` `[banner]` 连接断开，正在重连（第 \(…) 次）
- `DeepSeekHarnessMobile/Views/BridgeConversationView.swift:74` `[banner]` 连接异常：\(…)
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1414` `[queueActionButton]` 编辑排队消息
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1415` `[queueActionButton]` 删除排队消息
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1416` `[queueActionButton]` 立即插话
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1598` `[(无调用)]` 读取模式…
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1598` `[(无调用)]` 重试模式
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1666` `[(无调用)]` 完整工具，适合日常开发
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1667` `[(无调用)]` 用代码组合多步工具操作
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1668` `[(无调用)]` 仅用 Shell，轻量直接
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1669` `[(无调用)]` 创建和调试自定义 Agent
- `DeepSeekHarnessMobile/Views/ConversationView.swift:1670` `[(无调用)]` 自定义 Agent 模式
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2164` `[(无调用)]` 梳理脉络中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2164` `[(无调用)]` 推敲细节中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2164` `[(无调用)]` 酝酿下一步中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2164` `[(无调用)]` 沿着思路前行中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2165` `[(无调用)]` 翻阅已有线索中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2165` `[(无调用)]` 收拢相关信息中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2165` `[(无调用)]` 寻找切入点中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2165` `[(无调用)]` 铺开问题脉络中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2166` `[(无调用)]` 向外寻找线索中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2166` `[(无调用)]` 打捞新线索中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2166` `[(无调用)]` 沿途探查中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2166` `[(无调用)]` 搜集更多拼图中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2167` `[(无调用)]` 对照新发现中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2167` `[(无调用)]` 拼合新线索中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2167` `[(无调用)]` 检视所得中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2167` `[(无调用)]` 把发现串起来中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2168` `[(无调用)]` 回看已有线索中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2168` `[(无调用)]` 重新校准方向中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2168` `[(无调用)]` 再推敲一遍中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2168` `[(无调用)]` 换个角度审视中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2169` `[(无调用)]` 组织回应中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2169` `[(无调用)]` 整理表达中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2169` `[(无调用)]` 将思路落成文字中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2169` `[(无调用)]` 勾勒答案中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2170` `[(无调用)]` 继续推进中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2170` `[(无调用)]` 稳步探索中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2170` `[(无调用)]` 梳理当前进展中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2170` `[(无调用)]` 逐步展开中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2193` `[(无调用)]` 梳理脉络中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2368` `[goalActionButton]` 暂停目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2368` `[goalActionButton]` 继续目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2372` `[goalActionButton]` 删除目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2386` `[(无调用)]` \(…) 已完成
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2387` `[(无调用)]` \(…) 进行中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2388` `[(无调用)]` \(…) 待处理
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2435` `[(无调用)]` 进行中的目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2436` `[(无调用)]` 已暂停的目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2437` `[(无调用)]` 受阻的目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2438` `[(无调用)]` 当前目标
- `DeepSeekHarnessMobile/Views/ConversationView.swift:3953` `[joined]` 、
- `DeepSeekHarnessMobile/Views/HumanQuestionView.swift:439` `[(无调用)]` （recommended）
- `DeepSeekHarnessMobile/Views/HumanQuestionView.swift:439` `[(无调用)]`  (推荐)
- `DeepSeekHarnessMobile/Views/HumanQuestionView.swift:439` `[(无调用)]` （推荐）
- `DeepSeekHarnessMobile/Views/PluginsView.swift:274` `[componentField]` 组件 ID
- `DeepSeekHarnessMobile/Views/PluginsView.swift:275` `[componentField]` 模块
- `DeepSeekHarnessMobile/Views/PluginsView.swift:276` `[componentField]` 运行条目 ID
- `DeepSeekHarnessMobile/Views/PluginsView.swift:277` `[componentField]` 不可修改原因
- `DeepSeekHarnessMobile/Views/RootView.swift:1136` `[(无调用)]` 下载 \(…)
- `DeepSeekHarnessMobile/Views/RootView.swift:1401` `[(无调用)]` 文件超过 2 MB，请使用系统应用打开
- `DeepSeekHarnessMobile/Views/RootView.swift:1404` `[(无调用)]` 读取代码失败：\(…)
- `DeepSeekHarnessMobile/Views/SettingsView.swift:297` `[releaseRow]` 当前版本
- `DeepSeekHarnessMobile/Views/SettingsView.swift:298` `[releaseRow]` 要求网关版本
- `DeepSeekHarnessMobile/Views/SettingsView.swift:300` `[releaseRow]` 已连接网关版本
- `DeepSeekHarnessMobile/Views/SettingsView.swift:301` `[releaseRow]` 读取中…
- `DeepSeekHarnessMobile/Views/SettingsView.swift:340` `[Markdown]` 本版本发布说明暂未提供，请查看 GitHub 发布页。
- `DeepSeekHarnessMobile/Views/SettingsView.swift:413` `[(无调用)]`  · 需重启后生效
- `DeepSeekHarnessMobile/Views/TeamView.swift:291` `[(无调用)]` 这条对话还没同步到会话列表，稍后重试或下拉刷新团队页。
- `DeepSeekHarnessMobile/Views/TeamView.swift:359` `[emptyRow]` 暂无其他会话
- `DeepSeekHarnessMobile/Views/TeamView.swift:422` `[emptyRow]` 暂无群聊
- `DeepSeekHarnessMobile/Views/TeamView.swift:494` `[joined]` 、
- `DeepSeekHarnessMobile/Views/TeamView.swift:719` `[joined]` 、
- `DeepSeekHarnessMobile/Views/TeamView.swift:725` `[labeledRow]` 管理员主导
- `DeepSeekHarnessMobile/Views/TeamView.swift:825` `[labeledRow]` 工作中
- `DeepSeekHarnessMobile/Views/TeamView.swift:975` `[(无调用)]` 尚未完成配对或会话不可用，请先配对桌面端。
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:554` `[(无调用)]` 这条对话还没同步到会话列表，稍后重试。
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:656` `[joined]` 、
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:667` `[(无调用)]` 尚未完成配对或会话不可用，请先在团队页完成配对。
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:716` `[drawerItem]` 备用主机


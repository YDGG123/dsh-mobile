# i18n 中文清单（盘点）

> 由 `scripts/i18n-inventory.py --write` 生成，**不要手改**：重跑即更新。
> 口径、四桶定义、以及不确定性说明都在脚本头部注释里，以那里为准。

## ⚠️ 先读：不确定性

- 带字符串插值的条目靠**前缀匹配目录键**判定：硬编码桶约 **102 条**、漏登记桶约 **13 条**，
  **边缘计数有 ±10 的误差**，不要当精确值引用；不带插值的条目是确定判定。
- `Localizable.xcstrings` 的键会被 Swift 提取成 `%@` / `%lld` 形式，脚本用前缀匹配近似。

## 计数

| 桶 | 条数 | 含义 |
| --- | --- | --- |
| `registered` | 598 | 键就是中文，已登记（重启后即可跟随语言） |
| `localized_default` | 134 | `String(localized:defaultValue:)` 的中文兜底（已本地化） |
| `unregistered` (c) | 108 | **漏登记**：本地化调用里但目录无键 → 补目录（第 ① 步） |
| `hardcoded` (a) | 545 | **真硬编码**：重启/免重启都救不了（第 ③ 步） |
| **合计** | **1385** | 源码含中文字面量（50 个 Swift 文件） |

- 能跟随语言（重启后）：**732**（52.9%）
- 重启也不会变：**653**（47.1%）= 硬编码 545 + 漏登记 108，其中 Widget/LiveActivity 占 120
- 目录自身缺 `en`：**0** 条；`en` 未翻译：**0** 条
- `String(localized:)` 的键不在目录：**5** 处（多为插值键，见不确定性）
- 结构：`AgentLiveActivityWidget` target 是否带 `Localizable.xcstrings`：**否**（否 → 该扩展的文案无论怎么切语言都不变）

## (c) 漏登记：本地化调用里、目录无键

### `DeepSeekHarnessMobile/Views/TeamView.swift` （54）

- `:52` `[navigationTitle]` 团队
- `:64` `[accessibilityLabel]` 设备管理
- `:71` `[accessibilityLabel]` 刷新团队
- `:135` `[Picker]` 分段
- `:147` `[Text]` 实时状态暂不可用：网关目前只拿到名册（名字/角色/成员）。干活中、等你回答、等你审批要等桌面端发布团队服务后才显示。
- `:156` `[Text]` 团队连接中断：\(reason)
- `:182` `[badge]` 主
- `:231` `[accessibilityLabel]` 详情
- `:240` `[Label]` 查看详情
- `:299` `[Picker]` 分段
- `:339` `[Section]` 主 Bot
- `:420` `[Section]` 群聊
- `:444` `[Text]` 主
- `:555` `[Text]` 尚未连接桌面端
- `:557` `[Text]` 回主页点右上角钥匙图标 →「扫描二维码」或「手动输入配对信息」。\n一次配对会同时带上 Bot 团队。
- `:574` `[Label]` 回主页配对
- `:588` `[Text]` 正在同步团队…
- `:599` `[Text]` 离线：显示最后一次同步的数据
- `:618` `[Section]` 基本信息
- `:619` `[LabeledContent]` 名称
- `:621` `[LabeledContent]` 角色
- `:623` `[LabeledContent]` 类型
- `:623` `[LabeledContent]` 群聊
- `:624` `[LabeledContent]` 身份
- `:624` `[LabeledContent]` 主 Bot
- `:625` `[LabeledContent]` 置顶
- `:625` `[LabeledContent]` 是
- `:627` `[LabeledContent]` 成员
- `:629` `[LabeledContent]` 发言模式
- `:629` `[LabeledContent]` 所有人轮流
- `:629` `[LabeledContent]` 管理员指派
- `:633` `[Section]` 实时状态
- `:637` `[Text]` 实时状态暂不可用（网关降级：只拿到名册）
- `:641` `[Text]` 当前没有进行中的动作
- `:644` `[LabeledContent]` 在做
- `:647` `[LabeledContent]` 开始于
- `:653` `[LabeledContent]` 最近活动
- `:661` `[Label]` 进入对话
- `:684` `[navigationTitle]` 群聊
- `:693` `[alert]` 无法进入聊天
- `:732` `[Label]` 发消息
- `:759` `[Text]` 主
- `:770` `[Section]` 显示
- `:771` `[Toggle]` 置顶
- `:783` `[Section]` 模型与档位
- `:793` `[Picker]` 档位
- `:808` `[Label]` 设为主 Bot
- `:817` `[Label]` 发消息
- `:889` `[Section]` 已配对设备
- `:892` `[Text]` 加载中…
- `:892` `[Text]` 暂无设备
- `:925` `[badge]` 本机
- `:928` `[Text]` 最近活跃 \(formatted(lastSeen))
- `:940` `[Text]` 撤销

### `DeepSeekHarnessMobile/Views/WorkspaceView.swift` （20）

- `:312` `[accessibilityLabel]` 首页分组
- `:348` `[Text]` 连接已断开
- `:351` `[Text]` 正在自动重连…
- `:358` `[Text]` 立即重试
- `:402` `[Text]` 实时状态暂不可用：网关目前只拿到名册（名字/角色/成员）。干活中、等你回答、等你审批要等桌面端发布团队服务后才显示。
- `:411` `[Text]` 团队连接中断：\(reason)
- `:421` `[Text]` 暂无 Bot 或群聊。桌面端有 Bot 团队后这里会出现。
- `:454` `[Text]` 主
- `:607` `[Text]` 暂无会话。在桌面端与 Bot 对话后，这里会出现会话。
- `:642` `[Text]` 置顶
- `:984` `[Text]` 连接桌面端
- `:986` `[Text]` 扫码或在手机上输入桌面端显示的配对码；配对成功后会自动带上 Bot 团队。
- `:994` `[Label]` 扫码配对
- `:1021` `[Text]` Bot 团队尚未就绪
- `:1023` `[Text]` phase：\(info.phase) · 配对态：\(info.pairedState)
- `:1025` `[Text]` 本机会话：\(info.hasStoredSession ? 
- `:1025` `[Text]` ) · serverUrl：\(info.serverUrl ?? 
- `:1028` `[Text]` 原因：\(error)
- `:1037` `[Label]` 扫码重配
- `:1060` `[Text]` 暂无已知会话。点这里连接桌面端后创建第一个任务。

### `DeepSeekHarnessMobile/Views/SettingsView.swift` （11）

- `:237` `[LabeledContent]` 未提供
- `:311` `[Label]` 推入空白测试页
- `:315` `[Text]` 诊断
- `:317` `[Text]` 点进去只有一个文本，不发请求、不做布局。若这一页也卡死，说明问题在「推入」本身；若正常，说明原来的问题在那些设置子页的内
- `:390` `[alert]` 语言已切换
- `:391` `[Button]` 退出 App
- `:392` `[Button]` 稍后重启
- `:394` `[Text]` 界面语言只在 App 启动时读取系统语言设置，因此需要重启一次才会切换：请从后台把 App 彻底划掉再打开。\n\n如果
- `:470` `[Text]` 调整后会应用到所有页面，包括对话、按钮和代码。
- `:1044` `[Text]` 空白测试页
- `:1047` `[navigationTitle]` 空白测试页

### `AgentLiveActivityWidget/AgentLiveActivityWidget.swift` （8）

- `:190` `[Text]` 详情
- `:304` `[Text]` 查看详情
- `:309` `[Label]` 检查结果
- `:400` `[Text]` 详情
- `:412` `[Text]` 等待操作详情
- `:479` `[Text]` 正在等待主机确认
- `:491` `[Text]` 查看详情
- `:496` `[Label]` 检查结果

### `DeepSeekHarnessMobile/Views/ConversationView.swift` （5）

- `:866` `[Text]` Host 无法读取旧格式会话，请修复或升级 Host 后重试。原始记录未修改。
- `:1407` `[Text]` \(item.attachmentCount) 个附件
- `:1597` `[Text]` 切换中…
- `:1637` `[Text]` 模式不可用
- `:2226` `[Text]` \(phrase)，用时 \(elapsed) 秒…

### `DeepSeekHarnessMobile/Views/RootView.swift` （4）

- `:726` `[Text]` )规则和时区 \(zone.identifier)。
- `:1035` `[Text]` 工作区根目录
- `:1136` `[accessibilityLabel]` \(item.name) 已下载，点击重新下载
- `:1434` `[Text]` \(document.lineCount) 行 · UTF-8

### `DeepSeekHarnessMobile/Views/BridgeConversationView.swift` （3）

- `:26` `[Text]` 加载更早的消息
- `:115` `[Text]` 发送失败
- `:125` `[TextField]` 发消息…

### `DeepSeekHarnessMobile/Views/PluginsView.swift` （2）

- `:130` `[accessibilityLabel]` \(bundle.title)开关
- `:189` `[Text]`  }.count) 运行中

### `DeepSeekHarnessMobile/Views/GatewaySwitcherView.swift` （1）

- `:25` `[accessibilityLabel]` ))，切换主机

## (a) 真硬编码：不在本地化调用里

### 按文件

-  158  `DeepSeekHarnessMobile/Core/KMPSharedAdapter.swift`
-   80  `DeepSeekHarnessMobile/Core/AppStore.swift`
-   62  `AgentLiveActivityWidget/AgentLiveActivityWidget.swift`
-   50  `DeepSeekHarnessMobile/LiveActivity/AgentLiveActivityManager.swift`
-   50  `DeepSeekHarnessMobile/Views/ConversationView.swift`
-   41  `DeepSeekHarnessMobile/Core/BridgeTeamAdapter.swift`
-   19  `DeepSeekHarnessMobile/Core/GatewayTeamFeed.swift`
-   14  `DeepSeekHarnessMobile/Core/GatewayModels.swift`
-   14  `DeepSeekHarnessMobile/Views/TeamView.swift`
-    8  `DeepSeekHarnessMobile/Views/SettingsView.swift`
-    7  `DeepSeekHarnessMobile/Core/BridgeTransport.swift`
-    7  `DeepSeekHarnessMobile/Core/MultiGatewayStore.swift`
-    5  `DeepSeekHarnessMobile/Views/WorkspaceView.swift`
-    4  `DeepSeekHarnessMobile/Core/GatewayClient.swift`
-    4  `DeepSeekHarnessMobile/Views/PluginsView.swift`
-    4  `DeepSeekHarnessMobile/Views/RootView.swift`
-    3  `DeepSeekHarnessMobile/Core/GatewayProfile.swift`
-    3  `DeepSeekHarnessMobile/Core/VoiceInputAdapter.swift`
-    3  `DeepSeekHarnessMobile/Views/BridgeConversationView.swift`
-    3  `DeepSeekHarnessMobile/Views/HumanQuestionView.swift`
-    2  `DeepSeekHarnessMobile/Core/AgentBackgroundKeepAliveManager.swift`
-    2  `DeepSeekHarnessMobile/Core/BridgeChatEngine.swift`
-    1  `DeepSeekHarnessMobile/Components/ConversationViewport.swift`
-    1  `DeepSeekHarnessMobile/Views/ApprovalRequestView.swift`

### App 侧 `Views/` 的用户可见条目

- `DeepSeekHarnessMobile/Views/ApprovalRequestView.swift:179` `[String]` \(request.toolName) 请求执行需要审批的操作
- `DeepSeekHarnessMobile/Views/BridgeConversationView.swift:70` `[banner]` 正在连接…
- `DeepSeekHarnessMobile/Views/BridgeConversationView.swift:72` `[banner]` 连接断开，正在重连（第 \(attempt) 次）
- `DeepSeekHarnessMobile/Views/BridgeConversationView.swift:74` `[banner]` 连接异常：\(message)
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
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2386` `[(无调用)]` \(completedCount) 已完成
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2387` `[(无调用)]` \(activeCount) 进行中
- `DeepSeekHarnessMobile/Views/ConversationView.swift:2388` `[(无调用)]` \(pendingCount) 待处理
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
- `DeepSeekHarnessMobile/Views/RootView.swift:816` `[String]` ]?.doubleValue ?? 0)))执行一次
- `DeepSeekHarnessMobile/Views/RootView.swift:1136` `[(无调用)]` 下载 \(item.name)
- `DeepSeekHarnessMobile/Views/RootView.swift:1401` `[(无调用)]` 文件超过 2 MB，请使用系统应用打开
- `DeepSeekHarnessMobile/Views/RootView.swift:1404` `[(无调用)]` 读取代码失败：\(error.localizedDescription)
- `DeepSeekHarnessMobile/Views/SettingsView.swift:295` `[releaseRow]` 当前版本
- `DeepSeekHarnessMobile/Views/SettingsView.swift:296` `[releaseRow]` 要求网关版本
- `DeepSeekHarnessMobile/Views/SettingsView.swift:296` `[releaseRow]` 未提供
- `DeepSeekHarnessMobile/Views/SettingsView.swift:298` `[releaseRow]` 已连接网关版本
- `DeepSeekHarnessMobile/Views/SettingsView.swift:299` `[releaseRow]` 读取中…
- `DeepSeekHarnessMobile/Views/SettingsView.swift:299` `[releaseRow]` 未提供
- `DeepSeekHarnessMobile/Views/SettingsView.swift:338` `[Markdown]` 本版本发布说明暂未提供，请查看 GitHub 发布页。
- `DeepSeekHarnessMobile/Views/SettingsView.swift:401` `[(无调用)]`  · 需重启后生效
- `DeepSeekHarnessMobile/Views/TeamView.swift:25` `[(无调用)]` 团队
- `DeepSeekHarnessMobile/Views/TeamView.swift:162` `[emptyRow]` 暂无 Bot 或群聊
- `DeepSeekHarnessMobile/Views/TeamView.swift:291` `[(无调用)]` 这条对话还没同步到会话列表，稍后重试或下拉刷新团队页。
- `DeepSeekHarnessMobile/Views/TeamView.swift:345` `[emptyRow]` 暂无 Bot
- `DeepSeekHarnessMobile/Views/TeamView.swift:359` `[emptyRow]` 暂无其他会话
- `DeepSeekHarnessMobile/Views/TeamView.swift:422` `[emptyRow]` 暂无群聊
- `DeepSeekHarnessMobile/Views/TeamView.swift:494` `[joined]` 、
- `DeepSeekHarnessMobile/Views/TeamView.swift:719` `[labeledRow]` 成员
- `DeepSeekHarnessMobile/Views/TeamView.swift:719` `[joined]` 、
- `DeepSeekHarnessMobile/Views/TeamView.swift:722` `[labeledRow]` 管理员
- `DeepSeekHarnessMobile/Views/TeamView.swift:725` `[labeledRow]` 发言模式
- `DeepSeekHarnessMobile/Views/TeamView.swift:725` `[labeledRow]` 管理员主导
- `DeepSeekHarnessMobile/Views/TeamView.swift:825` `[labeledRow]` 工作中
- `DeepSeekHarnessMobile/Views/TeamView.swift:975` `[(无调用)]` 尚未完成配对或会话不可用，请先配对桌面端。
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:553` `[(无调用)]` 这条对话还没同步到会话列表，稍后重试。
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:655` `[joined]` 、
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:656` `[(无调用)]` 群聊
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:666` `[(无调用)]` 尚未完成配对或会话不可用，请先在团队页完成配对。
- `DeepSeekHarnessMobile/Views/WorkspaceView.swift:715` `[drawerItem]` 备用主机


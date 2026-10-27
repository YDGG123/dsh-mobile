# build 40 测试计划（分段历史 + 卡片按 id + 降级诚实 + 订阅超时）

> 作者：iOSTester（QA）· 2026-10-27 · 供 build 40 验收执行
> 依据：网关契约 `dsh-plugin-mobile-gateway/docs/history-segments.md` §8（`debc076`）、
> 已落码 `b4a5e23`/`f8ed278`（①纯逻辑）/`bb3ec0e`+`a783c56`（②③④）、本机环境实测（§5）。
> 本文件是 QA 的执行计划，不随代码提交；40 验收后归档。

---

## 0. 主用例（最重的一条）：单段 Bot 行为不变

**这是全体既有用户的路径，改坏了就是全体回归，优先级高于一切新功能用例。**

| 层 | 用例 | 判据 | 方式 |
|---|---|---|---|
| 自动 | 单段 Bot 的翻页决策与旧实现逐字段一致 | `hasMore=true` → 只用 `nextBeforeSeq` 翻本段；`hasMore=false` 且无 `previousSessionId` → 停止；**任何路径都不得凭空产生换段行为** | `f8ed278` 的纯函数已覆盖（page-turn 决策三态），跑通即绿 |
| 自动 | 单段聚合退化为"本段原样" | `mergedHistory(current, segments=[本段])` 输出 = 本段 items 原顺序（`b4a5e23` 已带） | 同上 |
| 真机 | 单段 Bot（无 `parts` 的 Bot）上滑翻页 | 翻到头即停、无重复、无缺页、无换段跳变 | 用户/真机 |
| 反向 | 把换段判断改成"看页内 sessionId ≠ 当前段"（`b4a5e23` 之前的错误思路） | `register(pageSessionIds:)` 用例红（第一页必然把本段自己登记成旧段） | 已在用例覆盖，验收时确认在 |

---

## 1. 分段历史（① 主体）

**契约**：网关 `history-segments.md` §8——`nextBeforeSeq` 与 `previousSessionId` **互斥**；
换段请求**不带 `beforeSeq`**；两个都没有 = 真到头。

### 自动（可跑，视 §5 环境解锁进度）

| # | 用例 | 判据 |
|---|---|---|
| 1.1 | 换段入口字段互斥 | 响应 `hasMore=true` 时**绝不**出现 `previousSessionId`；出现即客户端视为协议错乱并停止翻页（防御性） |
| 1.2 | 换段请求不带游标 | 收到 `previousSessionId` 后发出的 `history` 帧**没有** `beforeSeq` 键（带了 = "旧段缺最后几条"，不报错只少内容，最难发现） |
| 1.3 | 跨段聚合排序 | 旧段之间按 `date` 稳定合并、段内保持 seq 序、**当前段整块最后**（`b4a5e23.mergedHistory` 已带） |
| 1.4 | 旧段登记按发现顺序 | `register` 不按 id/时间戳排序；重复登记去重；页内 id == 当前段时忽略 |
| 1.5 | 跨段去重 | 同 `item.id` 只出现一次（两段边界重复时保留先到者） |
| 1.6 | 逐会话归属 | 隐藏 Bot 的段请求 → 网关 `SESSION_DENIED`（网关侧已测，手机侧只需不崩并把错误呈现为"不可见"） |

### 人工（需用户配合——多段真实数据只在用户账号上）

| # | 场景 | 判据 |
|---|---|---|
| 1.M1 | 多段 Bot 主聊天窗**上滑翻过段边界** | 旧段消息无缝接上、时间顺序正确、当前段仍在新消息到达时正常追加 |
| 1.M2 | 翻到最旧一段的尽头 | 停止翻页、无死循环请求（观察网关日志无重复 history 帧） |
| 1.M3 | 多段 Bot 收新消息 | 新消息仍落在当前段末尾，不插进历史中间 |

> 用户已明确不接受"测不到就漏了"——1.M1/M2 **必须**用户配合执行，计划里如实标注。

### 反向设计（每件都做"改回去必红"）

| 反向操作 | 预期红 |
|---|---|
| `mergedHistory` 删掉"当前段整块最后"（并入按 date 排序） | 1.3 用例红（当前段消息被插进历史中间） |
| 换段请求带上 `beforeSeq`（模拟最容易接错的形态） | 1.2 用例红 |
| `register` 改成按 id 排序 | 1.4 用例红 |
| 聚合去掉 `seenIds` 去重 | 1.5 用例红 |

---

## 2. 卡片状态按 id 维护（②，已在 39 落码 `5cefeff`+`bb3ec0e` 手机侧）

### 自动

| # | 用例 | 判据 |
|---|---|---|
| 2.1 | `applyingCardOps` 三 op 语义 | `card.shown` 增/覆盖；`card.answered`/`card.cleared` **按 id 删**（它们不带 `card` 字段——旧实现整帧跳过，红点永远消不掉的根因） |
| 2.2 | 归属键 | `cardOwner`: `roomId` 优先、其次 `botId`、均空返回 nil（该卡丢弃） |
| 2.3 | `cardState` 投影 | live map → 各 owner 的活卡集合与计数 |

### 人工

| # | 场景 | 判据 |
|---|---|---|
| 2.M1 | Bot 提问 → 用户在桌面端回答 | 手机红点**立即**消失（不等快照） |
| 2.M2 | 卡片跳过/群删 | 同上即时生效 |

### 反向

| 反向操作 | 预期红 |
|---|---|
| `applyingCardOps` 删掉 `card.answered/cleared` 分支（退回只捞 `card` 的旧实现） | 2.1 红（红点永不消失） |
| `cardOwner` 改成只认 `botId` | 2.2 红（群聊卡归属错） |

---

## 3. 降级态不谎报（③，`bb3ec0e` + `a783c56` 已落码）

### 自动

| # | 用例 | 判据 |
|---|---|---|
| 3.1 | `inheritsVolatileState(degraded:true)` = false | 降级帧**不许沿用** `activity/work/approvals/pending/waiting`（真机「空闲显示正在干活」的根因；网关降级帧故意不填 activity，写 idle 就是谎报） |
| 3.2 | `inheritsVolatileState(degraded:false)` = true | 实时帧照常沿用，无回归 |
| 3.3 | `degradedAfter`：**带字段才改** | delta 带 `degraded:true` → true；带 `false` → false；**缺字段 → 保持 current**（缺字段 ≠ 恢复，那是另一种谎报） |
| 3.4 | delta 路径同步 degraded | `applyDelta` 必须调用 `degradedAfter`（`a783c56` 补的漏路径——降级主要靠 delta 维持，原实现在这里空转） |
| 3.5 | `placeholderCards` 不受降级影响 | 卡片计数来自卡片帧，与降级无关，继续沿用 |

### 人工

| # | 场景 | 判据 |
|---|---|---|
| 3.M1 | 停掉桌面端 ds-bot → Bot 页 | 状态显示「实时状态暂不可用」，**不是**上一次的 activity（「空闲显示正在干活」的真机反馈） |
| 3.M2 | 恢复 ds-bot | 状态回到实时，无残留旧值 |

### 反向

| 反向操作 | 预期红 |
|---|---|
| `inheritsVolatileState` 改回恒 `true` | 3.1 红 |
| 删 `applyDelta` 里的 `degradedAfter` 调用（退回 a783c56 前） | 3.4 红（delta 路径 degraded 永远 false） |
| `degradedAfter` 把缺字段当 false | 3.3 红（缺字段 = 恢复的谎报形态） |

---

## 4. 订阅超时不再弹窗（④，`bb3ec0e` 已落码）

### 自动

| # | 用例 | 判据 |
|---|---|---|
| 4.1 | subscribe 确认帧**先停计时清 pending**，无论 sessionId 是否匹配 | 匹配不上只打 `subscribe-ack-stale` trace，不再把 15 秒计时留到爆炸 |
| 4.2 | 超时路径不再发 `subscription-timeout` 错误帧 | `GatewayClient` 无 `onFrame(error code:"subscription-timeout")`——超时只留痕 + 静默重订阅一次 |
| 4.3 | 重订阅 | 超时后对非空 target 恰好重发一次 `subscribe` |

### 人工

| # | 场景 | 判据 |
|---|---|---|
| 4.M1 | 停在成员会话页制造订阅超时条件 | **无模态弹窗**打断；页面行为与"还没订阅上"一致 |

### 反向

| 反向操作 | 预期红 |
|---|---|
| 恢复 `onFrame(subscription-timeout)` 旧实现 | 4.2 红（有断言盯着"不再发该错误帧"） |
| 删"先停计时"的提前返回 | 4.1 红（stale 确认重新触发爆炸路径） |

---

## 5. 环境限制（2026-10-27 实测量化，替代"跑不了"的模糊说法）

| 通道 | 状态 | 证据 |
|---|---|---|
| **KMP `jvmTest`** | ✅ **可用**（515/0 实证过；KMP 共享层逻辑的自动化主通道） | `./gradlew :shared:jvmTest` |
| **Swift XCTest（模拟器）** | ⚠️ **被两层已定位的问题挡死，非根本不可跑** | 见下 |
| **macOS 直执行通道** | ✅ **可用**（纯 Foundation 逻辑编译为 macOS 可执行真跑；`TeamRosterCache` 四条失败路径已实证） | 本计划 §1/§2 的纯函数可用同法 |
| **typecheck 五路 + 收录** | ✅ 可用（73/73） | `local-typecheck.sh` |

**XCTest 两层障碍（都已有解法，修复属构建负责人/iOSCoder）**：
   > 本节"可跑/能跑"均为"**解锁后可真跑**"的缩写。当前真实可执行的证据（2026-10-27 实测）：`./gradlew :shared:jvmTest` → exit 0，**515 tests / 0 failures / 0 skipped**；macOS 直执行通道 → `TeamRosterCache` 四条失败路径真跑 exit 0（9 项断言全过）；五路 typecheck exit 0。**注意**：① 分段历史的纯函数（`BotHistorySegments`/`mergedHistory`/翻页决策）在 `AppStore.swift`（UIKit 依赖文件）里，**不能**走 macOS 直执行通道，它们的用例在 XCTest 里 → 属于"解锁后可真跑"，不是"现在就能跑"。

1. **KLIB duplicate unique_name**（ReleaseBot 报的"缺 iosX64 klib 缓存"的真相）：`~/.konan` 与 `tools/konan` **两个 konan 目录并存**，增量编译缓存把两边绝对路径都记住了 → KLIB 解析器 deny。
   **统一到哪个、谁改、要不要清缓存（QA 建议，构建负责人拍板）**：
   - **统一到 `tools/konan`**（构建系统的既有默认：pbxproj 构建脚本、ship 脚本全部指向它）——改 `~/.konan` 一侧的使用方（主要是 QA 的 typecheck/手工构建习惯），**不动构建系统**；
   - **清缓存必须**：统一后把 `shared/build/`（至少 KMP 增量缓存）清掉重建一次——两边的绝对路径都已被增量缓存记住，不清就会继续互相污染；
   - **本 QA 的探针只证明了"分叉会红、收敛后这一次会绿"**——它是在 `KONAN_DATA_DIR=$HOME/.konan` 会话里做的，不代表统一到 `tools/konan` 后天然绿；根治以"清理重建后的第一根绿色构建"为准。
2. **UITests 目标真代码错误**：`RealSessionScrollPerformanceTests.swift` 两处 `app.open(url)` 是 **iOS 16.4+ API**，UITests 部署目标 16.0 且无 `#available` 守卫 → `build-for-testing` 整体失败（`-only-testing` 不缩小构建范围）。修法：两处加守卫，或 UITests 目标部署目标升 16.4（该文件本来就是 build 34 批的，归 iOSCoder）。
3. **措辞纪律 → 已升级为实测结论（2026-10-27）**：runtime 已装 + 两层修复落地后，本 QA 实测全链打通——`build-for-testing` **exit 0** 产出 xctestrun；`test-without-building` 在 iPhone 16e (iOS 26.3.1) 上**实跑 88 条单元用例全过、0 失败**（项目历史上首次 XCTest 实跑）。**前提与边界**：(a) 必须 `KONAN_DATA_DIR=$HOME/.konan`——默认 `tools/konan` 仍会 KLIB duplicate 红 176 处，待构建方统一 konan 目录；(b) UITests 守卫已提交（`203059a`，`openDeepLink` 统一入口）；(c) 88 条为单元测试层，UITests 层的收尾结果见运行日志留档。

**量化结论**：修复这两层后，XCTest 可在模拟器（iPhone 16e，iOS 26.3.1，x86_64）真跑——届时 §1–§4 的全部自动用例都能上 XCTest；修复前，自动验收 = jvmTest + macOS 直执行 + typecheck 三通道，覆盖所有**纯函数**用例（§1.3–1.6、§2.1–2.3、§3.1–3.5、§4.1–4.3 全部是纯函数，**都能自动**），WS 接线层用例需要 XCTest 恢复或真机。

---

## 6. 已知未修（不在 40 范围，验收时**不要**误判为已解决）

**卡片「点进去没有可点卡片」**（用户当前最痛，优先级高于分段历史）：
会话页的可点卡片 `HumanQuestionView` 只吃**旧协议** `question-requested` 通道，而手机已全迁 bridge，该通道已死 → 列表红点看得到、点进去没有可交互卡片。
- 状态：**未修**（40 不含此项）
- 因此 §2 的自动用例全绿 ≠ "卡片问题清零"——它们只覆盖 `card.shown/answered/cleared` 的**计数与红点**层
- 40 验收结论里必须写明"点进去无可点卡片 = 已知未修，另行派单"

## 7. 建议执行顺序

0. **卡片体验项**（用户当前最痛，若 40 前有 hotfix 批则按 §6 处理；否则排 40 后第一优先）
1. **纯函数自动层**：`bb3ec0e`/`a783c56`/`99f8acc` 已带的用例**现在就能跑**（jvmTest 515/0 + TeamRosterCache macOS 直执行已实证）；**① 的纯函数用例不在其中**（在 AppStore.swift/XCTest 里，待两层解锁）——解锁后先跑它们 + 反向四组（改回去必红的四刀）
2. **环境解锁**（iOSCoder/构建负责人，两处）：konan 目录统一 + UITests 两处 `#available`
3. **XCTest 真跑**：解锁后全量跑一遍（这是本机首次 XCTest 实跑，结果要留档）
4. **① 的 WS 接线增量复核**（iOSCoder 交活后）：`previousSessionId` 接线 diff + 1.2/1.4 反向
5. **真机验收**（需用户配合）：1.M1/M2/M3 + 2.M1/M2 + 3.M1/M2 + 4.M1 + 单段 Bot 主用例
6. **出包指纹核验**（QA，照常四步 + 版本双 40）

---

## 8. 风险与如实声明

- 多段真实数据只在用户账号 → 1.M1/M2 **需用户配合**，不用模拟数据冒充
- 单段 Bot 主用例的真机回归需要一台"只有单段 Bot"的环境——用户账号若已全是多段，1.M 需构造（新建一个无历史 Bot 即可）
- 模拟器跑不了的感觉是过时的：runtime 已装（iOS 26.3.1），真正挡路的是上面两个工程问题；模拟器执行在两层修复前一律写"**解锁后可真跑**"

# 上游合并差量报告（Clarklevis1995/dsh-mobile → `ec916e1`）

> 状态：**只做差量分析，不动手合并**（Chief 要求：报告批完再派执行）。
> 生成时间：2026-10-25 · 基线：本地 `ios16-support`（`72abf13`）+ 自研分支 `feat/u6-rework`（`762383d`）· 上游目标：`ec916e1`
> 注意：本报告生成时，工作区**正处于一次未完成的上游合并中**（`integrate/upstream-ec916e1`，12 处冲突未解）。见 §6。

---

## 1. 规模

| 区域 | 文件 | +/− |
|---|---|---|
| iOS App（`DeepSeekHarnessMobile/`） | 317 | +11,644 / −795 |
| Android（`androidApp/`） | 247 | +12,034 / −2,718 |
| KMP shared（`shared/`） | 45 | +7,245 / −94 |
| iOS Tests | 5 | +6,569 / −120 |
| iOS UITests | 2 | +302 / −0 |
| `project.pbxproj` | 1 | +36 / −4 |
| scripts / gradle / Vendor / website / docs / 产物目录 | 49 | 其余 |
| **合计** | **666** | **+43,944 / −3,843** |

上游 5 个提交：

| 提交 | 内容 |
|---|---|
| `a1a1c22` | Android 架构与体验改进 |
| `d388197` | 会话管理、模型选择、聊天体验改进 |
| `48d5403` | DeepSeek 用户体验群申请流程（website 侧为主） |
| `c062106` | Android 界面与导航改进 |
| `1652235` | 移动端功能更新 + 排除依赖缓存 |
| `ec916e1` | 可配置的 App 用量统计（配置统计功能） |

## 2. 上游新功能（要全合）

1. **会话管理 / 模型选择 / 聊天改进**（`d388197`，iOS 为主，317 文件）：会话侧重构、模型选择 UI、消息渲染。
2. **可配置用量统计**（`ec916e1`）：App 内用量/配置统计页与数据层（new：`AppConfigStats*` 一类文件）。
3. **移动端功能更新**（`1652235`）：一批移动端体验与依赖缓存排除（`.gitignore`、Vendor）。
4. **Android 三笔**（`a1a1c22`/`c062106`/`48d5403`）：KMP 共享层接口与 Compose 界面、导航。
5. **测试补强**：iOS Tests +6,569 行（5 个文件）、UITests +302 行 —— 上游在补测试，应整体接受。

## 3. 必须保住的自研功能（一个不许丢）

| # | 功能 | 关键文件/符号 | 判定方式 |
|---|---|---|---|
| 1 | **iOS 16 适配** | 全仓 `#available` 双分支、`IPHONEOS_DEPLOYMENT_TARGET`、`scripts/local-typecheck.sh`、Widget 16.2 | 合并后 grep 双分支数量不减少；部署目标不变 |
| 2 | **四入口非推入化**（设置） | `SettingsView` 的 `CompactSheet` 系列（预设/模型/权限）+ `FontSizePanel` | 设置里不应再出现行内 `NavigationLink`（除空白测试页） |
| 3 | **头像二期（方案 C）** | `TeamGlyph.swift`、`TeamAvatarView.swift`、`GatewayTeamFeed.glyph`（**bot 顶层**）、`RosterLookParityTests` | glyph 三层兜底顺序 image>glyph>symbol；pbxproj 收录 |
| 4 | **i18n 第①期** | `Localizable.xcstrings`（754 键）、`InfoPlist.xcstrings`、`scripts/i18n-inventory.py`、`verify-localizations.py` | 双闸门 exit 0 |
| 5 | **语言需重启提示** | `AppLanguage.effective`、`SettingsView` 的 alert + 行内「需重启」 | 切换后立刻弹提示 |
| 6 | **团队帧/卡片帧** | `GatewayTeamFeed.swift`、`TeamFeed` 解析、`workspaceTeamSection` | 名册混排、degraded 显式、头像旁路帧 |
| 7 | **主题色板 / 浅色对比度** | `DSHPalette`（`ink/veil/surface/stroke`）、`DSHColor.*AA` 动态色 | 浅色五处可读、无 `palette.ink` 当填充 |
| 8 | **出包闸门** | `scripts/ship-branch.sh` + `scripts/verified-heads.txt` | 未验证 head 拒绝出包（exit 5） |
| 9 | **诊断逃逸口** | `SettingsDiagnostics`（键保留、无 UI）+ 空白推入测试页 | 设置里只有一处诊断入口 |
| 10 | **KMP facade 扩展** | `shared/.../facade/SharedTeamStore.kt` 我方增补（移动端 team 投影） | 与上游同名文件冲突，需人工并集 |

## 4. 12 处冲突与处置建议

| 文件 | 类型 | 冲突块 | 处置建议 |
|---|---|---|---|
| `DeepSeekHarnessMobile.xcodeproj/project.pbxproj` | UU（有 stage1/2/3，工作副本无标记） | — | **结构化并集**：以上游为基底（含 36 行新增文件登记），再补我方 4 个文件登记（TeamGlyph/TeamAvatarView/TeamGlyphTests/RosterLookParityTests）。合并后必须过「工程收录闸门」 |
| `Resources/Localizable.xcstrings` | UU（同上） | — | **JSON 并集**：以我方 754 键为基底，补齐上游新增键（缺 `en` 会立刻被闸门红）。别整份覆盖 |
| `App/DeepSeekHarnessMobileApp.swift` | UU | 1 | 并集：保留我方 `RootIdentityBinding` + 上游启动逻辑（`AppFontScope` 不能丢） |
| `Core/AppStore.swift` | UU | 1 | 并集：上游统计字段/动作 + 我方语言/主题持久化、bridge 会话状态。文件很大，逐块取 |
| `Core/MultiGatewayStore.swift` | UU | 1 | 并集：上游多网关改动 + 我方 `interfaceStyle` 复制、bridge 团队单例 |
| `Components/Glass.swift` | UU | 1 | 并集：保留 `lightFill`/`themedGlassSurface`（浅色实色面），叠加上游视觉改动 |
| `Components/ConversationViewport.swift` | UU | 1 | 并集：上游会话视口改进 + 我方浅色/滚动相关修补 |
| `Views/ConversationView.swift` | UU | 2 | 并集：上游聊天改进 + 我方 i18n 键、玻璃面、图形状态色 |
| `Views/SettingsView.swift` | UU | 1 | **以我方结构为准**（四入口弹层/字体面板/诊断逃逸口），把上游新增设置项插进去 |
| `Views/WorkspaceView.swift` | UU | 4 | 并集：上游首页改进 + 我方 tab 自绘控件、团队区、`WorkspaceCardFill.sunken`、头像渲染 |
| `shared/.../facade/SharedTeamStore.kt` | **AA（双方各自新增同名文件）** | 1 | **语义冲突**：需对比两套 API，建议以上游为骨架、把我方移动端投影能力并入（可能需要改调用点） |
| `shared/.../SharedTeamStoreTest.kt` | **AA** | 1 | 同上，测试跟随实现合并 |

> `AA` 两处是本次**唯一的语义冲突**（同名文件两边各自实现）；其余都是文本并集。

## 5. 验收清单（合并后必须全绿）

1. `./scripts/local-typecheck.sh` → App 0 / Widget 0 **且** 工程收录闸门通过（每个 `.swift` 都挂在 Sources phase）。
2. `./gradlew :shared:jvmTest` → 全绿（上游新增 KMP 测试一并跑）。
3. `python3 scripts/i18n-inventory.py --check` → exit 0；`python3 scripts/verify-localizations.py` → exit 0。
4. 自研功能冒烟（按 §3 十项逐条勾）：设置四入口、头像 glyph 三层兜底、语言重启提示、团队帧 degraded 文案、浅色五处可读。
5. **产物级验证只能靠云端**：本机 Xcode 26.3 **未安装 iOS 26.2 平台**（`xcodebuild` 报 `iOS 26.2 is not installed`、无可用 destination），
   所以「真实 xcodebuild 编译」这一步在本机做不了，必须走一次云端构建（`scripts/ship-branch.sh`，闸门需要登 head）。

## 6. 当前工作区状态（必须先处理）

- 当前分支 `integrate/upstream-ec916e1`，`.git/MERGE_HEAD = ec916e1`，**12 处冲突未解**，其中 2 处是 `AA`。
- 这次合并**不是本报告作者发起的**（发现时已在冲突中）。按 Chief「先出报告、批了再合」的口径，建议：
  **先 `git merge --abort` 回到干净分支**（自动合并结果可随时重做，不会有手工成果损失），
  再按 §7 的分批流程正式执行；若合并方正在手工解冲突，则以合并方为准，本报告作为解冲突的对照表。

## 7. 建议的合并顺序（分 3 批，每批都可独立验证）

1. **KMP shared 先行**：`shared/` + `androidApp/` → 跑 `:shared:jvmTest`。这一层与 iOS UI 无关，先把接口定下来。
2. **iOS 数据层**：`AppStore` / `MultiGatewayStore` / `App` / `ConversationViewport` / `Glass` → typecheck + 工程闸门。
3. **iOS 视图层**：`WorkspaceView` / `SettingsView` / `ConversationView` / `xcstrings` / `pbxproj` → typecheck + i18n 双闸门 + 自研冒烟。

每批结束都记录 head；合并完成后统一登 `verified-heads.txt` 再出包（纪律不变：一次构建）。

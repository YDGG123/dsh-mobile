# 上游合并踩坑与修复手册（iOS 1.7.15 合并实录）

> **用途**：安卓版做同样的上游合并时，照着这份逐条自查。每一条都是**真机上暴露过的**，不是理论风险。
> **范围**：iOS 侧合并 `3bf35c1`（上游 1.7.15）后，从 build 82 到 87 的全部修复（2026-11-06 一天内）。
> **核心教训一句话**：**合并冲突解错了，编译能过、闸门能绿、包能出，但界面是坏的。** 类型检查抓不到"内容被掏空"，只有真机看得见。

---

## 0. 一句话总结：合并为什么会坏

这次合并方向是 **「上游改动合进我们的 App」**（`3befc01` 第一父 = 我方 `2007de4`，第二父 = 上游 `3bf35b7`）。冲突按「我方语义优先」裁决，但有三类漏网：

| 类型 | 机制 | 为什么闸门抓不到 |
|---|---|---|
| **A. 内容被掏空** | 解冲突时把整段 body 换成了 `EmptyView()`，但辅助属性/分组都还在 | 语法合法、类型合法、编译通过 |
| **B. 我们的逻辑变死代码** | 上游写法替换了 body，我方取数属性还在但零调用 | 死代码不报错，Swift 不警告未使用的 private 计算属性 |
| **C. 伪版本门槛** | 合并引入 `#available(iOS 26.0, *)` / `compiler(>=6.3)` 这类判据，在低版本/新 SDK 上行为反转 | 编译通过，运行时静默走错分支 |

**安卓对应风险**：Kotlin/Compose 同理——`@Composable` 里换成空 `Box()` 一样能编；未被调用的 private 属性一样不报错；`Build.VERSION.SDK_INT` 判断写错一样静默走错分支。

---

## 1. 设置页整页空白（类型 A：内容被掏空）

### 症状
点进设置页，**整页空白**，什么都没有。

### 根因
`SettingsView.swift` 的 `settingsBody` 被解冲突时换成：
```swift
private var settingsBody: some View {
    ScrollView {
        VStack(alignment: .leading, spacing: DSHSpacing.xl) {
            EmptyView()          // ← 只有这个
        }
    }
}
```
但**七个分组的内容全都还在文件里**（`appearanceGroup`/`newSessionGroup`/`extensionsGroup`/`connectionGroup`/`hostGroup`/`aboutGroup`/`moreGroup`），只是**没人引用**。

同时丢掉的还有整套 body 修饰符：`.background` / `.navigationTitle` / `.toolbar` / `.task` / `.onReceive` / **7 个 `.sheet`** / 1 个 `.alert`。对应的 `@State` 变量全都还在，全成了死变量——所以就算把内容补回来，**点每一项也是没反应的**。

### 怎么查（安卓版照做）
```bash
# ① 找"body 里只有 EmptyView"的 composable / View
# ② 找"private 成员引用计数 = 1"（只有定义、零调用）
grep -c "appearanceGroup" SettingsView.swift    # 返回 1 = 只有定义 = 被掏空
```
**通用判据**：`grep -c` 某个私有属性名，**等于 1 就是死代码**（定义 1 次 + 引用 0 次）。正常应该是 ≥2。

### 修法
`7855295`：照合并前基线 `git show 2007de4:...` 逐段还原七分组 + 全部修饰符 + 10 个 sheet。11 个 `@State` 全部恢复引用。

### 附带坑：还原了内容，又漏了内边距
`af7e52f`：我重建 body 时**只补了 `.padding(.bottom)`，漏了 `.padding(.horizontal, DSHSpacing.lg)` 和 `.padding(.top, DSHSpacing.xs)`** → 用户真机反馈「设置页铺得太宽、卡片贴到屏幕边缘」。

**教训**：还原一段 UI，要**逐行 diff 基线的修饰符块**，不是"看起来对了就行"。修饰符链也是内容的一部分。

---

## 2. 浅色主题下列表看不见字（类型 B + 类型 C 叠加）

### 症状
浅色主题下，工作区与 Bot 区列表**底色仍是深色、文字看不见**。设置页却正常（因为它是新补的，不走那条路）。

### 根因（三层，逐层剥）

**第一层：底色写死深色，文字按主题算**
```swift
// WorkspaceView.swift:82-83
.background(DSHColor.navy.ignoresSafeArea())   // 写死深海军蓝 #06172B，不随主题
.foregroundStyle(palette.ink(1))               // 浅色下 = 墨黑 #0E131A
```
深蓝底 + 墨黑字，**对比度约 1.2:1 = 看不见**。

**第二层：真正的好版本在更早的提交里，被分支历史覆盖了**
`git blame` 显示这行来自 10-20 的 `a1a1c22`。而 **10-24 的 `641382d`**（B6「浅色下字看不见」那次修）曾经修对过：
```swift
.background((colorScheme == .dark ? DSHColor.navy : Color(red: 0.96, green: 0.98, blue: 1.0)).ignoresSafeArea())
func ink(_ opacity: Double) -> Color {
    isLight ? DSHColor.ink.opacity(min(1, 0.55 + opacity * 0.45)) : Color.white.opacity(opacity)
}
```
后来被 `d388197`（10-20「Add conversation management」）把文件旧版覆盖回来，之后的提交一直在旧版上叠。

**第三层：动画背景组件的浅色分支被整个裁掉**
`HarnessAnimatedBackground` 合并时被换成上游鲸鱼方案，`isLight` 引用 **22 处 → 0 处**。79 版浅色之所以正常，是因为**动画底的浅色分支盖住了 WorkspaceView 那层 navy**（navy 在动画底下看不见）。合并后动画底永远 navy，浅色下 navy 露出来了。

### 怎么查（关键方法：翻历史找好版本，别重新推导）
```bash
# 找"这行在哪个提交里是对的"
git log -S "某个特征字符串" --oneline -- <file>
# 逐个提交验证该行的实际内容
for c in <候选提交>; do git show $c:<file> | grep -n "<关键行>"; done
# 看某行到底是谁写的
git blame -L <行号>,<行号> <某个提交> -- <file>
```
**用户当时的原话点破了要害**：「为什么不从合并前的版本里看之前是怎么做的」。**回退类问题的第一动作必须是 `git log` 找历史好版本照抄，不是重新推导。**

### 修法
`db4bea2`：底色改走 `palette.pageBackground`（与设置页同一条路）；动画底照 `2007de4` 参数还原浅色分支。
`5a0e1bb`：`ink()` 加地板（浅 0.62 / 深 0.55），**只托底不动高档**（≥0.42 档位逐位不变）。

### 对比度判据（安卓版必须给数值，不许"看着好了"）
| 档 | 浅色（底 #F4F6F9） | 深色（底 #06172B） |
|---|---|---|
| 正文 ink(0.92) | 15.33:1 | 15.29:1 |
| 最淡档（加地板后） | 5.08:1 ✓ | 6.06:1 ✓ |
| **修复前最淡档** | **3.98:1 ✗** | **4.44:1 ✗** |

判据：**两档都要 ≥4.5:1（WCAG AA 正文级）**，且修浅色不许把深色搞坏。

---

## 3. 鲸鱼动效不动了（类型 C：伪版本门槛）

### 症状
浅色主题回来了，但**背景鲸鱼粒子不动**。用户手机 iOS 16.1。

### 根因
```swift
// HarnessAnimatedBackground.swift
private var usesStaticBackground: Bool {
    if #available(iOS 26.0, *) { return reduceMotion }
    return true        // ← iOS 26 以下恒 true ⇒ 永远走静态分支
}
```
合并新加的门槛，把 iOS 16 用户的动效整条关掉。基线 `2007de4` 里**根本没有这个 iOS 26 门槛**：
```swift
private var pausesAnimation: Bool {
    reduceMotion || scenePhase != .active || pause.shouldPause || ...
}
var body: some View {
    TimelineView(.animation(minimumInterval: isLight ? 1.0/24.0 : 1.0/30.0, paused: pausesAnimation)) { ... }
}
```

### 怎么查
**"门槛是不是伪门槛"的判据**：问「这个 API 是真的只在 26+ 存在，还是我们的代码用 26+ 的某个特性才加的限制？」
- **真 SDK 差异**（要保留）：`HarnessFluidLayer` 的 `#available(iOS 17.0, *)` —— Metal shader 真的只有 17+ 有
- **伪门槛**（要删）：`usesStaticBackground` 的 `#available(iOS 26.0, *)` —— 只是合并时顺手加的保守判断

### 修法
`2576ce6`：删掉伪门槛，无条件 `TimelineView`，`reduceMotion` 才暂停+静帧，深色档逐位不变，帧率保持浅 24fps / 深 30fps。

---

## 4. 主机行被换成上游设计（类型 B：我们的逻辑变死代码）

### 症状
用户指着工作区「最近会话」上面那一行说：**「主机显示不是我们的设计」**。

### 根因
合并把 `GatewaySwitcherBar` 的 body 整段换成了上游写法：

**我们的设计（基线 `2007de4`）**：
```swift
HStack(spacing: DSHSpacing.sm) {
    DSHStatus(kind: connected ? .online : .failed, text: gateway.state.label)   // ● 已连接
    if let hostLabel {
        Text("·"); Text(hostLabel).appFont(DSHType.mono)                        // · 家里Mac（等宽）
    }
    Spacer(minLength: DSHSpacing.sm)
    Text(trailingText).appFont(DSHType.meta)                                    // 3 分钟前
    Image(systemName: "chevron.forward")
}
```
**上游换成的**：
```swift
HStack(spacing: 8) {
    TailscaleTunnelIndicator()
    Image(systemName: hosts.activeProfile?.deviceKind ?? "desktopcomputer")
    Text(hosts.activeProfile?.displayName ?? "选择主机")
    Image(systemName: "chevron.forward")
}
```

### 铁证：死代码
我们那三个取数属性 `trailingText` / `hostLabel` / `activeEndpoint` **全都还在文件里，但引用计数全是 1（只剩定义、零调用）**。

> **这是"UI 被上游替换"的通用信号**：功能还在、逻辑还在、就是没人调用 → 说明调用它的那层被换掉了。

### 怎么查（系统性扫，别等用户一个一个挑）
```bash
# 对合并改动过的每个视图文件，找"private 成员引用计数=1"的死代码
for f in <合并改动的视图文件>; do
  for sym in $(grep -oE "private (var|func) [a-zA-Z]+" "$f" | awk '{print $3}'); do
    n=$(grep -c "$sym" "$f")
    [ "$n" -eq 1 ] && echo "$f: $sym 死代码"
  done
done
```
**但要注意**：不是所有 `=1` 都是误伤——**必须和基线 `2007de4` 比对**。基线里也是 1 的（比如有意保留的）不算。

### 修法
`c06dffe`：照基线还原四段主结构；上游 `TailscaleTunnelIndicator`（新功能，回答"隧道开着没"）**降为行尾次要标记，不顶掉主结构**。
**原则**：上游新功能不能丢，但也不能让它取代我们已验收的设计——降级为次要元素。

---

## 5. 相册选图白选（类型 A 变体：调用点被吃）

### 症状
用户没报，是扫死代码时发现的。**相册选完图没有任何消费者 = 选了白选。**

### 根因
合并把消费链吃掉了：
```swift
// 基线 2007de4:1558 有这一句
.onChangeCompat(of: selectedPhotoItems) { _, items in
    Task { await importPhotos(items, targetSessionID: target) }
}
// 合并后：importPhotos 全文件只剩定义，零调用
```
`selectedPhotoItems` 还在被 `photosPicker` 写入，但**没有任何人读它**。

### 怎么查
**比"死代码"更隐蔽**：函数有定义、`@State` 有写入、就是中间那一环断了。
判据：**找"有写入无读取"的状态**。`grep -c "selectedPhotoItems"` 若全是写入点（`= []`、`$selectedPhotoItems`），没有 `onChange`/`onReceive` 消费者，就是断链。

### 修法
`c06dffe`：照基线接回 `.onChangeCompat(of: selectedPhotoItems)`。

---

## 6. 新消息到达"抽一下"（抖动）——不是合并直接引起，但合并放大了

### 症状
对话页偶发上下抖动抽搐。用户：「不确定触发条件，也不确定是自己做了什么」。反馈很多次没解决。

### 根因（三层历史积累）
**第一层（已修，10-30）**：贴底判定单阈值 28pt 无迟滞 → offset 穿越容差线时 pinned 反复翻转 → 箭头闪灭 → ZStack 推拉 composer。修法=20pt 迟滞带（进 28 / 出 8）。

**第二层（已修，11-03）**：`keepTail` 把思考行文案变化当结构变化触发滚回。

**第三层（本次根治，87）**：
```swift
// 改前
if gap > 0.5, (isTailFollowSuppressionActive() || gap > 24) { 逐帧追平 }
else { 一帧硬跳 setContentOffset(animated: false) }
```
**`tailFollowHardJumpLimit = 24`**：差距 ≤24pt 直接硬跳。而新消息通常只长十几到几十 pt，**大多数落在这档**。自尺寸 cell 量高要 ~200ms，硬跳发生在落位前、落位后再纠正一次 = 抽一下。

抑制窗救不了：上限 1.2s，只在"刚进会话"有效。

### 修法（方案 2：逐帧化根治）
```swift
// 改后：任何可见位移都走逐帧，目标每帧重算
if gap > 2 { startTailFollowCatchUp(from: currentOffset); return }
// ≤2pt 单帧微补齐（低于视觉阈值，省掉十几帧 display link）
```
**为什么逐帧能根治**：`stepTailFollowCatchUp` 每帧重新算 `bottomOffset`，内容还在长也能吸收——这是它比硬跳强的根本。

**副作用处理（关键）**：damping 0.25 按剩余距离比例收敛，小距离会不会收敛太慢？
实算：**指数逼近帧数只随距离对数增长**——1pt→3 帧、3pt→7 帧、10pt→11 帧、24pt→14 帧（60fps）。所以"小距离收敛慢"不成立，微跳阈值敢设 2pt 就是这个依据。

### 判据（安卓版照做）
1. 源码级：给出改前改后判定表达式对比
2. **单测必须真跑**（不是类型检查）：`TailFollowMath` 纯函数抽出，4 用例在模拟器实跑
   - 收敛帧数对数增长
   - **脉冲增长**（真实形态）：无任何一帧跳完总量
   - **匀速无限增长**（数学上界）：稳态差距有界不发散（`r* = g(1−d)/d`），如实断言这种极端下数学上不可能归零，但表现为落后一小段继续收，不是抽动
   - 无过冲
3. 红线：**上滑阅读不被拉回**（`followStreamingTail` 只在贴底路径调用）

---

## 7. 合并把我们的版本号体系冲掉了（流程坑）

### 症状
用户：「版本号不是说过了要从 1.0.0 开始了吗，怎么还是 1.7.X」

### 根因
用户 2026-11-04 定过：**Orcarness 独立开源后不跟上游版本号，从 1.0.0 起号、逐版递增**。合并时 `MARKETING_VERSION` 跟着上游走了（1.7.15），把这条裁定冲掉了。

### 修法
`db4bea2`：`MARKETING_VERSION` 1.7.15 → 1.0.0（pbxproj 两处 + `Resources/Info.plist` 的 `CFBundleShortVersionString`）。`CFBundleVersion` 继续递增（build 号）。

**要一并检查的**：闸门脚本是否硬编码了 1.7.x 期望值（有就一起改，否则改完闸门反而红）；`release-info.json` 的「本版本发布说明」比对逻辑（`appVersionRaw` vs `releaseInfo.version`）会不会因此一直显示"暂未提供"。

**流程教训**：**版本号体系是产品决策，不是合并能动的**。合并前应该先确认"哪些字段是上游的、哪些是我们的"。

---

## 8. 合并后必做的自查清单（安卓版直接照抄）

### 8.1 死代码扫描（抓类型 A/B）
```bash
# 对合并改动过的每个 UI 文件：
# ① private 成员引用计数 = 1 → 死代码（但先和基线比对，基线也是 1 的不算）
# ② body 里只有空容器（EmptyView / Box{}）→ 被掏空
# ③ 有写入无读取的 @State → 断链
```

### 8.2 修饰符链逐行 diff（抓"还原了内容又漏了样式"）
```bash
git show <基线>:<file> | sed -n '<body范围>p' | grep -E "^        \.[a-z]"
# 与现在的逐条对比，一条不能少
```

### 8.3 伪版本门槛排查（抓类型 C）
对每个 `#available` / `Build.VERSION.SDK_INT` / `compiler(>=x)` 判断问一句：
**「这个 API 是真的只在 N+ 存在，还是我们的代码用了 N+ 的某个特性才加的保守判断？」**
- 真的 SDK 差异 → 保留（如 iOS 17 Metal shader）
- 保守判断 → 删（如 iOS 26 恒静态）

### 8.4 对比度数值校验（抓配色回归）
每处"底色 × 文字"组合，浅深两档都要 ≥4.5:1。**给数值，不许"看着好了"。**

### 8.5 编译器版本 ≠ SDK 版本（云端构建专用）
```swift
#if compiler(>=6.3)    // ← 错：云端 Xcode 26.6 Swift 6.3+ 但 SDK 26.5 没有 iOS 27.1 符号
#if compiler(>=6.4)    // ← 对：Xcode 27 才带 SDK 27
```
**判据**：`compiler(>=x)` 是**编译器**版本，`#available(iOS y)` 是**运行时**版本，**SDK 版本**是第三个东西——三者不能混用。云端 runner 的 Xcode 可能比本机新，本地绿、云端红就是这个原因。

### 8.6 判据必须"真跑"，不能只"类型检查"
本次反复踩：闸门第五路只做**类型检查**（`-parse`），不真编译测试目标 → `GatewayProtocolTests` 漏 17+ API 保护，闸门绿但真机部署 16 编不过。
**做法**：改了单测判据就必须 `xcodebuild test` 真跑一遍，并注意闸门用的部署目标（本机 `TEST_TARGET_OS="17.0"`，与 App 的 16.1 不同）。

---

## 9. 这次合并最终暴露的问题清单（共 6 处）

| # | 问题 | 类型 | 修复提交 | 谁发现的 |
|---|---|---|---|---|
| 1 | 设置页整页空白 + 7 sheet 全丢 | A | `7855295` | 用户真机 |
| 2 | 浅色下列表看不见字（底色写死 + 动画底浅色分支丢失） | B+C | `db4bea2` | 用户真机 |
| 3 | 淡字对比度不足（3.98/4.44） | 数值 | `5a0e1bb` | iOSTester 判红 |
| 4 | 鲸鱼动效不动（iOS 26 伪门槛） | C | `2576ce6` | 用户真机 |
| 5 | 主机行被上游设计替换（三属性变死代码） | B | `c06dffe` | 用户真机 |
| 6 | 相册选图白选（调用点被吃） | A变体 | `c06dffe` | 死代码扫描顺带 |
| 7 | 设置页漏水平/顶部内边距 | A变体 | `af7e52f` | 用户真机 |
| 8 | 版本号体系被冲掉（1.7.15 vs 1.0.0） | 流程 | `db4bea2` | 用户 |

> 注：**7 处里有 6 处是用户真机发现的**，闸门一处都没抓到。这是最重要的结论——**闸门只能证明"能编译"，证明不了"是对的"**。

---

## 10. 给安卓版的三条硬建议

1. **合并后必须做一次"死代码扫描"**（8.1），这是抓 A/B 类漏网最有效的手段，成本极低。
2. **任何"我们已验收过的 UI"，合并后要逐行 diff 基线的修饰符链**（8.2），别信"看起来差不多"。
3. **闸门绿 ≠ 界面对**。要么加视觉回归（截图对比），要么接受"必须真机过一遍"这个成本——本次的代价是一天修 6 个 bug。

---

*文档建立：2026-11-06。对应 iOS build 82→87 的合并修复全过程。*
*相关文档：`upstream-merge-20261105.md`（功能来源）、`ios-merge-acceptance-20261106.md`（验收预案）、`ios-27.1-api-pending.md`（SDK 门槛）。*

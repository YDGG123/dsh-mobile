# 「设置子页推入卡死」代码级定位与结论

状态：分析稿（2026-10-24，本批只做代码级定位，不作本批通过条件）
现象基线（用户实测）：从「设置」点进子页 / 打开权限菜单 → 整页卡死，**左上角返回也点不动，只能杀 App**（= 真死锁形态，排除"加载慢"）。
本批之前的对照事实：sheet 呈现正常；`Menu` 也中招（存疑，可能误报）；开「减弱动态效果」仍卡；关背景图层 / 关离屏合成与阴影 / 关根 identity 绑定**都无效**。

---

## 1. 结论（短版）

代码里**没有一个必然导致死锁的 API 调用**可以直接指认；但可以把范围收敛成一句话：

> 死锁发生在「**推入转场进行中，源视图或根视图又被重新求值/重排**」这件事上。
> 三个已知来源都在这条路径上：**根级 `dynamicTypeSize` 因 `@AppStorage` 写入被重设**、
> **`AppStore` 的 `@Published` 变更让源 `Form` 在转场中重排**、
> **`onAppear` 里发布 `BackgroundAnimationPause` 让全屏背景在转场中重绘**。

置信度：中等（可在空白页结果出来后再校正）。
**本批的处置不是"修死锁"，而是"把死锁面拆掉"**：四个入口全部离开推入路径（三个紧凑弹层 + 底部面板），设置里只剩一个**空白推入页**作为判别刀。因此即便根因尚未唯一定位，用户也已经不会再撞上它。

---

## 2. 已排除（都有实测或代码证据）

| 候选 | 证据 | 结论 |
| --- | --- | --- |
| 背景逐帧动画（Metal/Canvas/鲸鱼） | 用户开「减弱动态效果」后仍死锁；`pausesAnimation` 会把三层都停掉 | **排除** |
| 工作区离屏合成 + 双层实时 shadow（`WorkspaceCardComposition`） | 诊断开关关掉后仍死锁 | **排除** |
| 根视图 `.id(ObjectIdentifier(activeStore))`（`RootIdentityBinding`） | 诊断开关关掉后仍死锁；且 `activate()` 只在切换/解绑网关时执行，转场期间无调用路径 | **排除** |
| 内层嵌套 `ScrollView` / Slider 背景里的 `GeometryReader{Canvas}` | 已删除；删后仍死锁（用户手上 1441 已含清理） | **排除** |
| 网络请求本身 | `AgentPresetSelectionView.task` / `DefaultModelSelectionView.task` 都是 async，不阻塞主线程 | **排除"请求阻塞"**（但它引发的状态发布仍在候选里，见 M2） |

---

## 3. 仍在的候选（按可疑度）

### M1｜根级 `.dynamicTypeSize` 在转场中被重设（原「字体大小」页）
- `Core/Theme.swift:90` `AppFontScope`：读 `@AppStorage(AppFontScale.key)`，把整棵树 `.dynamicTypeSize(closest)`。
- 原 `FontSizeSettingsView.onAppear` 会把越界值写回该键（`SettingsView.swift` 旧版 514-519）。写入 ⇒ 根环境值变化 ⇒ **整棵树在推入转场中重排**。
- 与现象吻合：这是四个入口里唯一"改根环境"的，最容易造成"转场卡住且返回无响应"（UIKit 在转场中拿到互相冲突的布局请求时，通常表现为导航栏不响应）。
- 处置：本批已改为底部面板，且**面板内不做任何回写校正**（只在用户拖动时写）。

### M2｜`AppStore` 的 `@Published` 让源 `Form` 在转场中重排（原「Agent 预设」「默认模型」）
- `SettingsView` 是 `Form`（iOS 16 上就是 UICollectionView-backed List），并持有 `@EnvironmentObject store: AppStore`；两个子页 `.task` 会调 `store.refreshDefaultConfiguration()` / `ensureModelCatalogForDefaults()`。
- 请求返回 ⇒ `AppStore.objectWillChange` ⇒ **源页 `SettingsView` 在推入转场中重新求值**（目标页在栈顶，源页仍是转场的一方）。
- `RootView` 侧已有 `RootNavigationHost` 的 `.equatable()` 保护（注释写明"不要让 session/history 发布使 NavigationStack 失效"），**但保护只覆盖了导航容器，没有覆盖被推入的源页自身**。
- 处置：本批三个入口改成弹层，弹层里拿数据不再影响被覆盖的 `Form` 的转场。

### M3｜`onAppear` 里发布 `BackgroundAnimationPause`（所有被推入页）
- `Components/BackgroundAnimationPause.swift`：`enter()` 修改 `@Published coveredDepth`，发生在**转场进行中的 `onAppear`**；`HarnessAnimatedBackground` 观察它并重绘全屏背景。
- 「减弱动态效果」只停逐帧动画，**不影响这次发布引起的重绘**，所以该候选**没有被 Reduce Motion 排除**（这也是它与"背景已排除"不矛盾的地方）。
- 处置：本批空白页**故意不加** `pausesBackgroundAnimation()`，作为隔离变量；四个入口不再经推入，自然也不触发。

### M4｜「权限」`Menu` 也卡（存疑）
- `Menu` 不推入任何页面。若它真的死锁，则共同条件不是"推入"，而是"**在这套 `Form` 上发起任何 UIKit 呈现**"——那 M2（源视图在呈现中重排）就更像主嫌，因为它在两种呈现方式下都成立。
- 本批权限改成紧凑弹层：如果这版仍卡，说明 M4 成立且与推入无关，需要单独查 `Form` + 呈现；如果正常，则原报告很可能是把"进设置后随便点哪都卡"记成了"权限卡"。

---

## 4. 空白推入页的判据

入口：设置 → 诊断 → 推入空白测试页（`NavigationLink` → `BlankPushTestView`）。
页内只有一句 `Text`：**无请求、无 `.task`、无滚动容器、无 `pausesBackgroundAnimation()`、无 `@AppStorage` 写入**。

| 结果 | 解释 | 下一步 |
| --- | --- | --- |
| 空白页**也卡** | 「推入」这条路径本身在这套根视图上不可用（M3/M2 仍可能，因为设置页自身挂了 `.pausesBackgroundAnimation()`） | 把设置页自身的 `pausesBackgroundAnimation()` 一并去掉再试；若仍卡，则只剩 SwiftUI `Form`+`NavigationStack` 的组合问题，考虑把设置整体改成非推入（本批已把可交互内容移出，仅剩诊断页） |
| 空白页**正常** | 推入机制没问题，问题在**被推入页的内容/生命周期**（M1/M2 的原始形态） | 维持本批形态（入口已全部不推入），把空白页保留为回归探针 |

---

## 5. 本批实际改动与死锁面

- 四个入口全部离开推入：Agent 预设 / 默认模型 / 权限 → `CompactSheet` 选项弹层；字体大小 → `FontSizePanel` 底部面板（`presentationDetents([.height(392)])`）。
- 删除 4 个诊断开关，只留「推入空白测试页」。
- 删除已不可达的 `AgentPresetSelectionView` / `DefaultModelSelectionView` / `AgentPresetCard` / `DefaultModelCard`（约 14 KB 死代码）。
- 结论：**用户交互面上已不存在"从设置推入子页"这条路径**；死锁面从 4 条收敛到 1 条只读探针。

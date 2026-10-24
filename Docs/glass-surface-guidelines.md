# 玻璃面（Glass Surface）口径与规范

状态：2026-10-24 定稿（随 `2d084fd` 后的收口批次落地）
适用：`Components/Glass.swift` 的 `glassSurface` / `themedGlassSurface`，以及全部调用点

---

## 1. 两套机制与平台分支

| 平台 | 深色档 | 浅色档 |
| --- | --- | --- |
| iOS 16–25 | `ultraThinMaterial.opacity(...)` + tint（`dark: true`）或 `thinMaterial` + tint（`dark: false`） | **实色浅面** `lightFill`（默认 `secondarySystemBackground` = #F2F2F7），不叠 tint |
| iOS 26 | `.glassEffect(.regular.tint(tint))`（仍带 tint） | `.glassEffect(.regular)`，**不带 tint、也不是实色面** |

**iOS 26 的浅/深不对称是有意的**：26 上走系统原生玻璃观感，`glassEffect` 分支**完全忽略 `lightFill`**（见 `GlassSurface` 字段注释）。读代码时不要当"漏改"；也不要为了对称去在 `glassEffect` 里伪造实色填充。

入口约定：**新增调用点一律用 `themedGlassSurface(radius:tint:lightFill:darkMaterial:shadowOpacity:lightShadowOpacity:)`**，不要再手写 `glassSurface(dark: colorScheme == .dark, …)` 三元组——那是漂移的来源。目前全部调用点（含「新建会话」按钮）都已收进统一入口。

参数语义：
- `tint` —— **仅深色档**的 tint；浅色一律不叠（否则白底又变灰）
- `lightFill` —— 浅色实色面，默认 `secondarySystemBackground`
- `darkMaterial` —— 深色是否走 `dark: true` 的 `ultraThinMaterial` 形态；历史 7 处不传（保持深色逐字不变），「新建会话」传 `true`
- `lightShadowOpacity` —— 浅色阴影上限，默认 `0.06`（实色面不需要重阴影）

## 2. 边界线归属（踩过的坑）

**边界由最后描边的一方负责，`ThemedGlassSurface` 内部不画线。**

原因：7 个调用点都在修饰符之后自己描边（`glassEdge` / `panelGlassEdge` / 问题卡的 white）。曾在 `ThemedGlassSurface` 里补过一圈 `ink(0.06)`，但**同半径**、被外层 0.6–0.7pt 的线整条压住，浅色下等于没画。

规则：
- **浅色**：边界用墨色 `DSHColor.ink.opacity(0.10)`（实色面上白色高光几乎不可见）
- **深色**：边界用白色高光（`white.opacity(0.13~0.22)`），维持原样
- 新增玻璃面若自带描边 → 自己按主题选色；若不描边 → 也不要在修饰符里补，统一在调用点画

## 3. 真实收益（别把方向说反）

这 7 处改动的收益是：

1. **半透明 → 不透明**（不再有 thinMaterial 的模糊透光）
2. **阴影 0.18 → 0.06**（实色面不需要重阴影）
3. **补上可见边界**（浅色墨色）

**不是"变浅"**：`secondarySystemBackground` = **#F2F2F7** 本身就是系统浅灰，与实色面同色。若 tint 与目标底同色，"叠加后会变浅"这个直觉是错的。

实测数值（浅色，`secondarySystemBackground` 底为参照）：

| 项 | 值 |
| --- | --- |
| 新底 | **#F2F2F7**（= `secondarySystemBackground`） |
| 旧底（thinMaterial ≈#F2F2F7 + `veil(0.08)` = ink@0.072） | ≈ **#E2E2E7**，L\*≈90.0 |
| 新底 L\* | ≈ **99.8**（压在 #F5FAFF 工作区底上 ≈#FEFFFF） |
| **ΔL\*** | **≈ 9.9（约 10）** |

「新建会话」按钮（`surface(0.12)` = white@0.912）：新底 **#FEFFFF**，文字 `ink(1)` = **#0E131A**，对比度 **18.55:1**。

## 4. 关于"亮度比 1.09–1.15"

**仓库里没有这个口径，不要引用它。** 全仓 `1.09` / `1.15` 只出现在无关的鲸鱼粒子尺寸（`HarnessAnimatedBackground.swift`）。历史提交信息里出现过这个词，但从未落地成可复算的定义，已作废。

需要比较前后差异时，用这两个之一（都能复算）：
- **ΔL\***（CIELAB 明度差）
- **对比度比**（WCAG，正文 4.5:1 / 图形 3:1）

## 5. 系统语义色裁定（记录，不改代码）

`.secondary` 3.30:1 / `.tertiary` 1.71:1 在 #F2F2F7 底上**不按 4.5 门槛处理**：

- 它们是系统语义色的本值（白底上 `.secondary` 也只有 3.44:1）
- 本批只把底从 #F8F8F9 变到 #F2F2F7，影响 −0.06
- 结论：**保持系统语义、不硬调**；将来若用户点名某处浅灰文字看不清，再单独议

## 6. 待办（下一批，不在本批）

同类「浅色 material + tint 双叠」的历史残留已全部收口；若以后用户再报某处"灰底偏深"，先按本文件第 1、3 节核对是否走了 `themedGlassSurface`，不要当新 bug 查。

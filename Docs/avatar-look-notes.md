# 头像与颜色：两端一致性说明（任务 2 收口）

状态：2026-10-24 定稿，对应 ds-bot `80212bc`（roster 解析）+ App `177f10f` 起的收口批次
相关：`Docs/glass-surface-guidelines.md`（玻璃面口径）、`docs/roster-publish-v1.md`（ds-bot 侧契约）

---

## 1. 单一事实源

- **host 解析、手机端只渲染**：ds-bot 在发布 roster 前调用电脑端同一个 `lookOf()`
  （`src/client/characters.js`），`avatar.shape` 与 `color` **必定有值**。
- 缺省推导规则（只存在于 ds-bot）：`defaultShape(id)` = FNV-1a + murmur3 fmix32 → 7 个形状之一；
  `defaultColor(id)` = FNV-1a + mulberry32 → 12 个非灰阶色之一。
- App **不许**再按名字/首字母/随机数推导师形状或颜色：旧实现这么干过，结果同一个 Bot
  两端显示不同（用户报的「不一样」）。旧宿主缺字段时落**中性块 / 中性圆点**。

## 2. 颜色表（19 个 ink，深浅两档）

- 电脑端：浅色 `INKS[k][0]`；深色 `INKS[k][1]`（`styles.js:23-24`）。
- App：`GatewayTeamFeed.inkPairs` 同时存两档，按 `colorScheme` 取；
  自定义 hex 两档同色。
- 对拍：`DeepSeekHarnessMobileTests/RosterLookParityTests.swift` 断言 19 × 2 逐值相等，
  夹具由 `ds-bot/scripts/export-roster-look-fixture.mjs` 生成。

## 3. 符号墨色（白 / 近黑二选一）

电脑端 `themes.js:36` 的 `accentInk()`：

```
(L(accent) + 0.05)² > 1.05 × (L(#141414) + 0.05)  ⇒  #141414，否则 #FFFFFF
```

App 逐字复刻（`GatewayTeamFeed.symbolInkHex(forAccentHex:)`）。
**为什么必须这样**：白符号压在真彩底上，15/19 低于 4.5:1、10/19 低于 3:1
（yellow 1.84 / green 2.19 / cyan 2.21 / orange 2.64）。改用该规则后：

| ink | 白符号 | 近黑符号 |
| --- | --- | --- |
| yellow #FFAF38 | 1.84 | **10.04** |
| green #00C972 | 2.19 | **8.42** |
| cyan #1CC3B0 | 2.21 | **8.32** |
| orange #FF781C | 2.64 | **6.98** |
| deepseek #4D6BFE（浅） | **4.33** | 4.25（规则选白） |

浅色 19 色分布：5 个用白（deepseek/charcoal/graphite/cobalt/black），14 个用近黑。

## 4. 形状映射（SF Symbols 近似）

电脑端是矢量角色；手机端用同语义符号 + 同色底。`whale→fish.fill`、`star→star.fill`、
`gumdrop→drop.fill`、`peanut→circle.hexagongrid.fill`、`bunny→hare.fill`、`kitty→pawprint.fill`、
`acorn→leaf.fill`…
**`zed` 在电脑端是 Bull**，SF Symbols 没有牛，取 `tortoise.fill`（只求不与 bunny 撞符号）。
未知形状 → `circle.fill`（中性，不猜）。

## 5. 已知差异 / 待办

| 项 | 说明 | 状态 |
| --- | --- | --- |
| `aurora` 渐变 | 电脑端 `GRADIENTS.aurora` 是四段渐变；手机端只能显示单色 `#00B8D4`（ink 值）。`DEFAULT_COLORS` 不含渐变，只有用户**显式选 aurora** 才可见 | **待办（后续小项）**：手机端渲染 LinearGradient 即可对齐 |
| 符号形状 | SF Symbols 与电脑端矢量角色是「同语义近似」，不是像素级一致 | 接受 |

## 6. 提交信息勘误

- `3ab1901` 的提交信息里写「`ThemedGlassSurface` 的浅色边界统一走 `veil(0.06)`」——
  **仓库里不存在 `veil(0.06)`**。实际状态：`eb79331` 已删除内部的 ink 边界，
  边界归调用点，浅色用 `DSHColor.ink.opacity(0.10)`、`panelGlassEdge` 用 `black.opacity(0.13)`。
  该说法作废，以本文件与 `Docs/glass-surface-guidelines.md` 为准。

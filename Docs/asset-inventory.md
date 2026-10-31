# 素材总盘点（开源改名前置）

> 只盘点，未改任何代码/素材。生成日期 2026-10-31。
> 范围：**dsh-mobile**（`b23bf3e`，778 个素材）+ **dsh-plugin-mobile-gateway**（`f50e5c1`，3 个）。
> 口径：只统计 **git 已跟踪**的素材（`git ls-files`），扩展名
> `png/jpg/jpeg/svg/pdf/webp/gif/ico/mp3/wav/ttf/otf/m4a/aiff`。
> 作者 = 该文件**最早的新增记录**（`git log --reverse --diff-filter=A`），即"谁把它引入仓库"。

## 0. 总览

| | 数量 | 大小 |
| --- | --- | --- |
| dsh-mobile | **778** | **102.87 MB** |
| dsh-plugin-mobile-gateway | **3** | **3.71 MB** |
| **合计** | **781** | **106.58 MB** |

**关键分界线**：dsh-mobile 的 `a1a1c22`（2026-10-20，作者 Clarklevis1995）是**我们的 fork 起点**——
凡引入提交为 `a1a1c22` 或更早上游提交的 = **上游素材**；之后的 `iOSDev`/`YDGG123`/`iOSCoder` 提交 = **自有**。

---

## 1. 主表（按目录前缀，互不重叠）

| 路径前缀 | 数量 | 大小 | 引入提交 / 作者 | 归属 | 开源处置 |
| --- | --- | --- | --- | --- | --- |
| `DeepSeekHarnessMobile/Resources/Assets.xcassets/` | 241 | 0.52 MB | 多提交（`1652235`/`a1a1c22` + `5e7175f` YDGG123） | **混合** | 见 §2 跨目录清单 |
| `androidApp/src/` | 154 | 2.22 MB | `1652235`/`a1a1c22` Clarklevis1995 | **混合** | 见 §2 |
| `Design/` | 93 | 22.52 MB | `a1a1c22` Clarklevis1995 | **混合** | 大部分**直接删** |
| `Docs/` | 145 | 41.57 MB | **`iOSDev`×89**、Clarklevis1995×50、YDGG123×5、1 重命名 | **混合** | `test-evidence` 挑带、`images` 删 |
| `Artifacts/` | 29 | 11.51 MB | `a1a1c22` Clarklevis1995 | **上游** | **直接删** |
| `Vendor/` | 78 | 3.86 MB | vendored by Clarklevis1995 | **第三方** | **可带**（保留其 LICENSE） |
| `website/` | 24 | 1.53 MB | `a1a1c22` Clarklevis1995 | **上游** | **直接删**（或随新站重建） |
| `output/` | 11 | 7.69 MB | `a1a1c22` Clarklevis1995 | **上游** | **直接删** |
| `artwork/` | 1 | 10.48 MB | `a1a1c22` Clarklevis1995 | **上游** | **直接删** |
| 其他（`.assets/`、`AgentLiveActivityWidget/`） | 2 | 0.97 MB | 混合 | 混合 | 逐个判 |
| **合计** | **778** | **102.87 MB** | | | |

---

## 2. 跨目录的品牌资产清单（分布在多个目录，单独列出避免漏）

### 2.1 上游品牌「whale / whaleness」—— **15 个 / 12.51 MB → 必须换或删**

| 路径 | 大小 |
| --- | --- |
| `Design/whale-girl-ios-app-promo.png` | 2.78 MB |
| `Design/whale-girl-ios-app-promo-16x9-v2.png` | 2.81 MB |
| `Design/whale-girl-ios-app-promo-16x9-v3.png` | 2.71 MB |
| `Design/whaleness-iphone-mockups/`（6 张：home/conversation/plugins/presets/tasks/trajectory） | 5.55 MB |
| `Design/whaleness-orca-mark/whaleness-orca-{dark,light}.svg` | 17 KB |
| `DeepSeekHarnessMobile/Resources/Assets.xcassets/HeroWhaleMask.imageset/` | — |
| `DeepSeekHarnessMobile/Resources/Assets.xcassets/RunningWhaleTailSprite.imageset/` | — |
| `androidApp/src/main/res/drawable-nodpi/running_whale_tail_sprite.png` | 13 KB |

> 全部 `a1a1c22` / Clarklevis1995。「whale-girl」是上游的角色品牌形象，`whaleness` 是上游品牌词。

### 2.2 上游 `Dsh*` 功能图标 —— 7 个 / 0.01 MB → **建议换名或重画**

`Assets.xcassets/` 下：`DshAgentPreset`、`DshBash`、`DshContextInjection`、`DshPluginPinwheel`、`DshRead`、`DshSearch`、`DshThink`（均 `a1a1c22`）。
`Dsh` = DeepSeek Harness 前缀，属上游品牌命名。

### 2.3 **已有 Orcarness 自有资产 —— 6 个 / 0.71 MB → 可带 ✅**

| 路径 | 说明 |
| --- | --- |
| `…/Assets.xcassets/OrcarnessHarnessWordmark.imageset/orcarness-harness.svg` | 品牌字标 |
| `…/Assets.xcassets/OrcarnessLoadingPoster.imageset/poster.png` | 加载页 |
| `…/Assets.xcassets/OrcarnessOrcaBlack.imageset/orcarness-orca-black.svg` | orca 标记（黑） |
| `…/Assets.xcassets/orcarness-orca-blue.svg` | orca 标记（蓝） |
| `androidApp/src/main/res/drawable-nodpi/orcarness_loading_poster.png` | Android 加载页 |
| `androidApp/src/main/res/raw/orcarness_loading.png` | Android 加载图 |

> **重要**：这 6 个文件**没有"新增"记录**——它们是**重命名/移动**而来（大概率由 whale 资产改造），
> 说明品牌迁移**已经在进行**。这印证了新品牌 `Orcarness`（orca）已部分就位。

### 2.4 待换的 AgentAvatar —— **288 个 / 0.12 MB**（不是 144）

| 路径 | 数量 | 引入提交 |
| --- | --- | --- |
| `…/Assets.xcassets/AgentAvatar000-143.imageset/avatar.svg` | 144 | `1652235` Clarklevis1995 |
| `androidApp/src/main/res/raw/agent_avatar_000-143.svg` | 144 | `1652235` Clarklevis1995 |
| **合计** | **288** | 同一批几何的双端副本 |

> **补全你的数字**：你说 144，实际是 **iOS 144 + Android 144 = 288 个文件**，同一批几何。
> 任务 2 生成时必须**双端同步替换**，且 Android 侧文件名是 `agent_avatar_NNN.svg`（下划线，非驼峰）。

---

## 3. 三个附加问题的答案

### 3.1 总大小

**106.58 MB**（dsh-mobile 102.87 MB + 网关 3.71 MB）。
**带进开源仓库约 7 MB**——见 §4 的预算。

### 3.2 `Design/file-icons/svg` 那 72 个是什么来源？—— **第三方 Untitled UI，MIT，可带**

`Design/file-icons/README.md` 与 `manifest.json` 写得非常清楚（**上游自己留的来源声明**）：

- 来源：`https://www.untitledui.com/resources/file-icons`
- npm 包：`@untitledui/file-icons`，版本 `0.0.9`，**`manifest.json` 声明 `"license": "MIT (upstream package.json)"`**
- 构成：**27 个直接复用 Untitled UI + 45 个沿同一几何补充**
- 实测：72 个 SVG **全部**含 `<g id="Untitled UI / Solid / …">` 标记（我 grep 过：72/72 命中）
- 双端：`Design/file-icons/svg`（源，72）与 `…/Assets.xcassets/FileIcon_*.imageset`（iOS，72）**内容逐字节相同**（抽查 7z/apk 的 sha256 一致）
- Android 用 Android SDK `Svg2Vector` 转 VectorDrawable

**结论**：这是**第三方 MIT 素材**（不是上游品牌），**可以带进开源仓库**，只需保留来源与许可声明。
**⚠️ 但注意**：README 提到的重生成脚本是 `scripts/generate_file_icon_assets.py` —— **依赖 python3，本机 python3 不可用**（Xcode 工具链损坏）。以后要重生成双端图标会卡在这里。

### 3.3 Android 那 145 个 `res/raw` 是什么？—— **就是 AgentAvatar 的 Android 版**

145 = **144 个 `agent_avatar_NNN.svg`**（§2.4）+ **1 个 `orcarness_loading.png`**（自有品牌，可带）。
**没有音效/动画素材**——`res/raw` 在这里被当作"原样打包的 SVG"用。

---

## 4. 开源仓库体积预算

| 处置 | 内容 | 数量 | 大小 |
| --- | --- | --- | --- |
| **直接删** | `Docs/images`（App 截图）、`Artifacts/`、`output/`、`artwork/`、`website/`、`Design/` 里含 DeepSeek Harness 品牌与宣传的图 | ~190 | **~69.8 MB** |
| **必须换** | AgentAvatar 288（任务 2 生成，体积 <0.1 MB）+ whale/whaleness 15（删或重画）+ 网关 3（README 首屏） | 306 | **~16.3 MB** |
| **可带** | `Vendor/`（第三方 3.86）+ file-icons 双份（第三方 0.35）+ Orcarness 自有（0.71）+ `test-evidence` 挑带 | ~110 | **~7 MB** |
| **小计（带进开源仓库）** | | | **≈ 7 MB**（替换头像后仍 <7.1 MB） |

**结论**：106.58 MB 里 **约 86 MB 可以删/换掉**，开源仓库只需带 **约 7 MB 素材**。

---

## 5. 与"上游/第三方"的边界（避免误删）

| 类别 | 判据 | 例子 | 处置 |
| --- | --- | --- | --- |
| **第三方（可带，标 LICENSE）** | 随库 vendored / 公开 MIT 图标集 | `Vendor/swift-markdown-ui`（78）、`Design/file-icons` + `FileIcon_*`（144，Untitled UI MIT） | 可带 |
| **上游（必须换/删）** | 引入提交 ≤ `a1a1c22` 或 `1652235`，作者 Clarklevis1995 | AgentAvatar 288、whale 15、`Docs/images` 43、`Artifacts` 29、`Design` 宣传图 | 换或删 |
| **自有（可带）** | 引入者 `iOSDev`/`YDGG123`/`iOSCoder`，或 Orcarness 品牌 | Orcarness 6、`test-evidence` 89（iOSDev）、`AppIcon`（YDGG123 `5e7175f`）、用户反馈截图 5 | 可带 |
| **不明/待确认** | 无「新增」记录（重命名而来）或组内混合 | §2.3 的 6 个（已确认是 Orcarness 改造）、`Assets.xcassets` 里其余 15 个功能图标（`CopyMessage`/`Table*`/`PluginArtwork*`） | 逐个判 |

---

## 6. 我另外发现的三件事（建议一并处理）

1. **文件图标的重生成脚本依赖 python3**（`scripts/generate_file_icon_assets.py`）——本机不可用。改名后若要保持双端图标一致，需要先解决 python3，或把脚本改写成 node。
2. **`Design/file-icons/manifest.json` 记录了每个图标的 SHA-256** —— 如果我们替换/删除图标，这份清单也要同步更新，否则校验会失配。
3. **上游品牌残留在代码里也有**（不在素材盘点范围，但同批改名会遇到）：`iOSApp` 名称、`Info.plist` 的 `CFBundleDisplayName`、`whaleness` 字样等。**需要单独扫一遍代码层的品牌串**（我这轮只盘了素材）。

---

## 7. 待确认项（我没有猜，留给你/用户决定）

1. `Assets.xcassets` 里 **15 个非品牌功能图标**（`CopyMessage`、`TableCopy/Download/Expand/Rotate`、`PluginArtwork*` 5、`AppIcon`）：**`AppIcon` 是用户（YDGG123 `5e7175f`）做的 → 自有**；其余是上游的通用功能图标（复制/表格操作/插件配图），**可保留（非品牌）还是重画，需你定**。
2. `Design/conversation-dark-mode.png`、`Design/trajectory-dark-mode.png`、`Design/loading-logo/poster.png`、`Design/composer-icons/`（2 个小 SVG）、`Design/webui-agent-preset/`：上游引入，是**界面示意/功能图标**而非品牌图 → 建议**删**（宣传用途）或**保留**（如果新仓库文档要复用），需你定。
3. `Artifacts/LiveActivityValidation`（29 个，11.51 MB）是**灵动岛/实况活动的验收截图**（含真实界面）→ 我建议删，但若开源仓库需要展示功能，可挑 2-3 张。
4. 网关 `docs/assets/mobile-device-management.png`（900 KB）与 `public-access-ui.png`（213 KB）是**面板 UI 截图**（不含 whale 角色）→ **可保留**（换成新名后的截图更好），需你定。

---

## 附：盘点命令（可复跑）

```sh
# 素材清单（仅已跟踪）
git -c core.quotePath=false ls-files | grep -iE '\.(png|jpe?g|svg|pdf|webp|gif|ico|mp3|wav|ttf|otf|m4a|aiff)$'
# 引入者（最早的新增记录）
git -c core.quotePath=false log --reverse --diff-filter=A --format='@@%h|%an' --name-only
# 单体查询
git log --follow --diff-filter=A --format='%an|%h|%ad' --date=short -1 -- <file>
```
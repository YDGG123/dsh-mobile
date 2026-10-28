# 分段历史（多段 Bot）设计与勘误记录

## 权威收尾路径（2026-10-27 勘误，取代 8b83ffd 提交信息里的错误描述）

**成功路径上没有显式 `finish`。** `applyHistoryRebased` 成功消费一页后，
由 **KMP 推送 `outcome`**（`AppStore.handleHistoryChange`，约 :5213 附近）收尾：

```
switch change.outcome {
case "stopped": historySyncEngine.finish(sessionID: change.sessionID)
case "failed":  historySyncEngine.finish(sessionID: change.sessionID)
}
```

`applyHistoryRebased` 里仅有的 `finish` 在 **catch 块**（约 :4247，
`processingStarted` 失败 → finish + `kmpHistoryStore.cancel`）。
8b83ffd 提交信息写「成功/失败两条路径都有 finish（:4212/:4245）」是错的：
`:4212` 在错误分支、`:4245` 是 catch 第一行，二者都不在成功路径。
**结论不受影响**：旧段引擎经 KMP `outcome:"stopped"` 正常收尾，:4184 的门不会对旧段常开。

## 品牌替换清单的教训（三个实例）

1. build 37：顶栏/抽屉/空态换了，**背景剪影（HeroWhaleMask）、会话尾巴（RunningWhaleTailSprite）漏了**
2. build 42：加载动画（OrcarnessLoading）漏了——**生成脚本的数据源也漏了**（读旧 SVG）
3. 通用教训：**品牌替换清单 = 全仓图形资源扫描 + 脚本生成产物重跑 + 资产级渲染 intent 清查**
   （`template-rendering-intent` 藏在 Contents.json 里，代码 grep 不到，会自动压扁任何新用法）

## 渲染规则（多色品牌图）

`.renderingMode(.template)` 只取 alpha：**多色品牌图用它 = 毁掉细节**（黑鲸+白眼斑 → 灰色剪影）。
模板只适合单色图标。深色模式用浅色圆底解决"黑上黑"，不用模板填色。

## 渲染 B（进行中）

聚合入口 `AppStore.mergedHistory`（跨段 createdAt、同段 seq、当前段末尾）+ 缓存键两维
（segmentVersion + 各桶末位 seq）。等 ④ 自检（旧段桶真有 events）后提交。

## UI 改造勘误（feat/ui-redesign-tokens，2026-10-28）

1. **主题三选不照搬设计稿的 `DSHThemeMode`/`.dshTheme`** —— 工程已有 `InterfaceStyle`
   （system/light/dark，AppStore 持久化 + 根视图 preferredColorScheme），两套并存会互踩。
   设置页第二刀直接读写 `store.interfaceStyle`。
2. **`DSHType` 全走 `AppFont` 是白赚的一层**：设计稿的绝对字号（11/13/15/18/28）如果写死，
   用户调大系统字号会顶破布局；走 AppFont 把「设计档位」与「用户可调」解开。
   这条建议写回 handoff 文档（设计侧不知道工程有用户字号倍率）。

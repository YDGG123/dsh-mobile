# 预期红名单（build 40 树 `0035e97` · 首次全量 XCTest 实跑 · 2026-10-27）

> **用途**：全量 XCTest 重跑后的自动比对基线——名单内的失败是**预期红**（已裁决挂起或已修复待重验），名单外的失败才是真变化。
> **维护规则**：名单外的失败出现 → 先查是否为新回归，再对照 `qa-build40-test-plan.md` §6.5/§6.6 的修复进度更新本名单。
> **格式**：`类名.用例名`，一行一条；脚本可按行 grep 比对。
> 总数：40 条（环境挂起 27 + 需真机 2 + 已修复待重验确认 6 + 本文件建立时已修测试期望 5 —— 口径见下方分类）。

---

## A. 环境挂起（27 条）—— app-host 无 UIWindowScene / 模拟器渲染限制，**不是产品缺陷**

### A1. `UIWindowScene` 缺失（25 条）—— 测试基建需要真实 window scene（裁决：不修，挂起）
HistoryViewportControlledPresentationTests 19 条：
```
HistoryViewportControlledPresentationTests.testActualSnapshotsInsertAtMostOneEntryIncludingChromeCacheHitsAndHeavyMarkdown
HistoryViewportControlledPresentationTests.testCacheHitAndLayoutDrainCannotCommitOrBuildWhileDraggingThenOnlyLatestIsShown
HistoryViewportControlledPresentationTests.testEmptySessionWaitsForContentAndSwitchCancelsPreviousFade
HistoryViewportControlledPresentationTests.testHistoryInsertionNeverExposesOldOffscreenScreenshotBeforeFinalization
HistoryViewportControlledPresentationTests.testHistoryRevealsOnlyInsertedBodyAfterOperationAndStableLayout
HistoryViewportControlledPresentationTests.testHistoryScrollIndicatorRecoversAfterFailureEmptyAndDetachWithoutStaleFinish
HistoryViewportControlledPresentationTests.testHistoryScrollIndicatorStaysHiddenUntilAllBatchesAndStableGeometry
HistoryViewportControlledPresentationTests.testHistoryScrollIndicatorWaitsForDeferredFinalizationAfterAnotherDrag
HistoryViewportControlledPresentationTests.testInFlightCompletionDefersFinalizationAndKeepsNewReadingPositionAfterAnotherDrag
HistoryViewportControlledPresentationTests.testInitialContentFadesOnlyAfterCommitAndPositionAreStable
HistoryViewportControlledPresentationTests.testInitialStreamingUpdatesStayImmediateAndRevealWhileReplyContinues
HistoryViewportControlledPresentationTests.testNativeOffsetLeaseSurvivesInFlightDetachButCannotLeakIntoReplacementSession
HistoryViewportControlledPresentationTests.testNewDragRevealsPendingRowsAndStopsFadeWithoutWaitingForNetwork
HistoryViewportControlledPresentationTests.testPartialHistoryStopsOnNewDragAndAcknowledgesOnlyCompleteLatestEpoch
HistoryViewportControlledPresentationTests.testReduceMotionAndSessionSwitchCannotLeaveStaleOpacity
HistoryViewportControlledPresentationTests.testSameHistoryEpochLiveUpdatesDoNotStarveFinalHistoryAcknowledgment
HistoryViewportControlledPresentationTests.testSessionSwitchDiscardsPausedTargetAndOldAcknowledgment
HistoryViewportControlledPresentationTests.testShortContentHistoryAndReplacementKeepOriginalOffsetPolicy
HistoryViewportControlledPresentationTests.testWidthChangeAndNewEmptyEpochDiscardUnfinishedPreparedHistory
```
InlineImageTaskIsolationTests 6 条（**与上 19 条同根**：同一 scene unwrap 失败 → 视图/任务未运行 → expectations unwaited；已排除 MainActor 数据竞争）：
```
InlineImageTaskIsolationTests.testCancellationAndFailureFallBackThenNextInputCanLoad
InlineImageTaskIsolationTests.testDirectSameSourceDifferentAltRejectsLateAAfterB
InlineImageTaskIsolationTests.testLateImageAAfterNoImageThenBDoesNotReviveA
InlineImageTaskIsolationTests.testLinkedImageKeepsOriginalLoaderAndRendererCapability
InlineImageTaskIsolationTests.testPlainAndCodeLiteralHaveNoImageRequestsOrDelayedHeightChange
InlineImageTaskIsolationTests.testRelativeURLAltAndSameInputKeepOriginalTaskIdentity
```

### A2. 模拟器渲染/动态色差异（2 条）
```
MarkdownRenderProductTests.testJSONKeyWhitespaceEscapesAndUnicodeKeepOriginalAttributes
PluginIconRenderingTests.testSVGColorSchemeIsPartOfTheCacheKey
```

### A3. 环境-带升级钩子（1 条）—— 触发条件见 §升级
```
GatewayForegroundRecoveryTests.testForegroundProbesStaleConnectionBeforeNextHeartbeat
```

### A4. 模拟器行为差异——UITests 真机向（2 条，裁决：挂起等真机）
```
WorkspaceDrawerUITests.testFontSizePreviewUpdatesAndPersistsAfterRelaunch
WorkspaceDrawerUITests.testMarkdownLongPressOpensFullScreenSelectionAndRestoresReadingPosition
```
失败消息：字体 125% ≠ 150%（模拟器持久化窗口差异）；长按全屏断言失败。

---

## B. 已修复待重验确认（6 条）—— **重验时若再红 = 回归，必须查（不是环境噪声）**
> 修复提交在 `0035e97` 之后（blink `36402b6`、cache helper + fixture `c4db4ef`）。
> 下次全量（更新的树）这 6 条**应当转绿**；若仍红 → 先确认修复提交是否在树上，在树上仍红 = 回归，逐条查。

### B1. 测试期望值错（1 条）—— "共算共错"失效模式（开发手算 + QA 复核沿用同一处算错）
```
TeamGlyphTests.testBlinkPhaseSkipsWholeStepsAndKeepsRemainder
```
失败原因：`blinkResume(steps, phase: 3.61)` 断言 (index 4, hold 0.06)；正确 = **(index 6, hold 0.06)**（9 档逐段表：相位 3.61 落在第二次闭眼后的 1.06 过冲档 3.59–3.67 内；跳过前 6 档共 3.59s）。生产逻辑输出 6 正确。修复提交 `36402b6`。

### B2. 测试夹具错（5 条）
**TeamRosterCacheTests helper 漏 wrapper**（4 条）—— helper 直接回传裸快照 JSON，缺 `cacheVersion/savedAt/snapshot`，`load` 正确拒绝：
```
TeamRosterCacheTests.testExpiredCacheReturnsNil
TeamRosterCacheTests.testCacheSurvivesInstanceIdChange
TeamRosterCacheTests.testHydratedRowsKeepColorAndOtherSnapshotFields
TeamRosterCacheTests.testTrimmedSnapshotKeepsIdentityFieldsAndCardCounts
```
修复：helper 改走生产 `TeamRosterCache.encode`（wrapper 齐全）。

**FrameReassemblerTests fixture 尺寸被 clamped 下限穿透**（2 条，`0035e97` 夹取的连带）：
```
FrameReassemblerTests.testChunkLargerThanPublishedLimitIsRejected
FrameReassemblerTests.testStreamLargerThanReassemblyLimitIsRejected
```
修复：fixture 调大到 2000B / 90KB（超过夹取下限 1024B / 64KiB），语义不变。

---

## C. 设计性跳过（8 条）—— 非失败， tally 时与失败分开算
```
ControlledHistoryPresentationPerformanceTests.testControlledPresentationPipelineIsDeterministic（XCTSkip）
ConversationWarmHostTransferTests.testWarmHostTransferAcrossReallocation（×3 条相关）
DisplayLinkIdleCalibrationTests.testDisplayLinkIdleCalibration（XCTSkip）
RealSessionDirectoryLinkUITests.testDirectoryLinkOpensExistingWorkspaceBrowserAtTargetDirectory（openDeepLink 16.4 守卫）
RealSessionScrollPerformanceTests.testScrollTowardOlderHistoryInExistingSession（同上）
TabletAdaptiveLayoutUITests.testAdaptiveLayout（同上）
```

---

## 升级触发条件（GatewayForegroundRecovery那条的"升级钩子"具体化）

测试验证的是「回前台立即探测陈旧连接，不等 30 秒心跳」（`0b84115` 新代码区域）。模拟器失败 = 探测 expectation 1s 未满足。

**升级为真 bug 的触发条件（满足任一）**：
1. 真机：用户切回 App 后 **30 秒内**网关侧无任何该设备的探测/心跳请求（网关 access log 可查），且伴随"回前台后数据迟迟不刷新"
2. 真机日志：`category=bridge` 出现心跳超时循环（`heartbeat timeout silence=…; reconnecting`）在**每次**切前台后出现
3. 桌面端网关日志：App 前台切换后出现 4401/重连风暴（连续 3 次以上）

三条都不满足 + 真机上切回正常 → 维持"环境挂起"（模拟器探测时序与真机不同）。

---

## 与 40 总账的对账
A(27) + B(6) + A4 已计入 A(2 条在 A 内) + C(8) + 其余通过用例 = 600 全量对上，**无"消失但没人管"的条目**。

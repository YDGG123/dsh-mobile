import XCTest
@testable import DeepSeekHarnessMobile

/// 覆盖折叠屏适配涉及的纯逻辑：
/// - `AdaptiveLayout.workspaceUsesColumns`：首页 / 会话列表何时分栏
/// - `HarnessBackgroundStyle.resolve`：展开后复用平板背景
/// - `SessionGroups.group`：会话页侧栏的工作区分组
final class AdaptiveLayoutTests: XCTestCase {

    // MARK: - workspaceUsesColumns

    /// Duo 折叠 466×678 与展开横拿 890×626 是真机量到的；
    /// 932×430（iPhone 横屏）、1024×768（iPad）、1200×400 是参考尺寸。
    func testWorkspaceUsesColumnsMatchesDeviceShapes() {
        let cases: [(name: String, width: CGFloat, height: CGFloat, expected: Bool)] = [
            ("iPhone Duo 折叠 466×678", 466, 678, false),
            ("iPhone Duo 展开横拿 890×626", 890, 626, true),
            ("iPhone Duo 展开竖拿 626×890", 626, 890, false),
            ("iPhone 16 Pro 横屏 932×430", 932, 430, false),
            ("iPhone 16 Pro 竖屏 430×932", 430, 932, false),
            ("iPad 横屏 1024×768", 1024, 768, true),
            ("iPad 竖屏 768×1024", 768, 1024, false),
            ("宽屏但极矮 1200×400", 1200, 400, false),
        ]

        for item in cases {
            let actual = AdaptiveLayout.workspaceUsesColumns(
                size: CGSize(width: item.width, height: item.height)
            )
            XCTAssertEqual(actual, item.expected, "\(item.name) 分栏判断不符预期")
        }
    }

    /// 900 是作者原本的边界，横屏规则不能顺手把它降低。
    /// 关键是下面「竖着拿、宽度 899 / 860 都不分栏」那两条：如果谁把 900 改回 860，
    /// 这两条会立刻失败 —— 只验「该分栏的分了」是钉不住边界的。
    func testOriginalNineHundredBoundaryIsNotLowered() {
        XCTAssertTrue(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 900, height: 700)))
        XCTAssertTrue(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 1100, height: 800)))
        // 890×626 只有走横屏规则才分栏，竖着拿同样的宽度不分栏。
        XCTAssertTrue(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 890, height: 626)))
        XCTAssertFalse(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 626, height: 890)))
        // 竖着拿时，899 和 860 都必须单栏（860 是 #58 提过又被撤回的值）。
        XCTAssertFalse(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 899, height: 1000)))
        XCTAssertFalse(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 860, height: 1000)))
    }

    /// 无障碍大字号时不分栏，保持作者原来的行为。
    func testAccessibilityTextAlwaysUsesSingleColumn() {
        XCTAssertFalse(
            AdaptiveLayout.workspaceUsesColumns(
                size: CGSize(width: 1024, height: 768),
                accessibilityText: true
            )
        )
    }

    // MARK: - 首页背景

    func testBackgroundStyleFollowsUnfoldedSizeInBothOrientations() {
        let cases: [(size: CGSize, expected: HarnessBackgroundStyle)] = [
            (CGSize(width: 466, height: 678), .phone),
            (CGSize(width: 678, height: 466), .phone),
            (CGSize(width: 890, height: 626), .pad),
            (CGSize(width: 626, height: 890), .pad),
            (CGSize(width: 932, height: 430), .phone),
            (CGSize(width: 430, height: 932), .phone),
            (CGSize(width: 890, height: 599), .phone),
            (CGSize(width: 600, height: 890), .pad),
        ]

        for item in cases {
            XCTAssertEqual(
                HarnessBackgroundStyle.resolve(size: item.size, isPad: false),
                item.expected,
                "背景风格不符预期：\(item.size)"
            )
        }
    }

    func testPadBackgroundStyleIsPreservedForNarrowWindows() {
        for size in [CGSize(width: 744, height: 1133), CGSize(width: 1133, height: 744),
                     CGSize(width: 375, height: 1024)] {
            XCTAssertEqual(HarnessBackgroundStyle.resolve(size: size, isPad: true), .pad)
        }
    }

    // MARK: - SessionGroups

    private func session(_ id: String, hasConversation: Bool? = true) -> SessionSummary {
        SessionSummary(
            id: id,
            title: id,
            lastActivity: .distantPast,
            isRunning: false,
            hasUnread: false,
            hasConversation: hasConversation
        )
    }

    private func workspace(_ id: String, sessions: [String]) -> GatewayWorkspace {
        GatewayWorkspace(
            workspaceId: id,
            path: "/tmp/\(id)",
            title: id,
            sessionIds: sessions,
            createdAt: "",
            updatedAt: ""
        )
    }

    func testSessionBelongingToWorkspaceIsListedUnderIt() {
        let groups = SessionGroups.group(
            sessions: [session("a")],
            workspaces: [workspace("w1", sessions: ["a"])]
        )
        XCTAssertEqual(groups.byWorkspace["w1"]?.map(\.id), ["a"])
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }

    func testSessionWithoutWorkspaceFallsIntoUngrouped() {
        let groups = SessionGroups.group(
            sessions: [session("a"), session("b")],
            workspaces: [workspace("w1", sessions: ["a"])]
        )
        XCTAssertEqual(groups.byWorkspace["w1"]?.map(\.id), ["a"])
        XCTAssertEqual(groups.ungrouped.map(\.id), ["b"])
    }

    /// 同一个会话在两个工作区里时，两边各列一份（与网页版侧栏一致）。
    func testSessionInMultipleWorkspacesAppearsInEach() {
        let groups = SessionGroups.group(
            sessions: [session("a")],
            workspaces: [workspace("w1", sessions: ["a"]), workspace("w2", sessions: ["a"])]
        )
        XCTAssertEqual(groups.byWorkspace["w1"]?.map(\.id), ["a"])
        XCTAssertEqual(groups.byWorkspace["w2"]?.map(\.id), ["a"])
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }

    /// 工作区里登记了不存在、或没有会话内容的历史会话时，不产生空条目。
    func testSessionsWithoutConversationAreExcluded() {
        let groups = SessionGroups.group(
            sessions: [session("a", hasConversation: false)],
            workspaces: [workspace("w1", sessions: ["a"]), workspace("w2", sessions: ["missing"])]
        )
        XCTAssertNil(groups.byWorkspace["w1"])
        XCTAssertNil(groups.byWorkspace["w2"])
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }

    func testEmptyInputProducesEmptyGroups() {
        let groups = SessionGroups.group(sessions: [], workspaces: [])
        XCTAssertTrue(groups.byWorkspace.isEmpty)
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }
}

/// 会话页「贴底」迟滞决策的用例（2026-11-02 用户报「滑一点箭头就闪」）。
///
/// 判定抽成了纯函数 `ConversationViewportController.pinnedAfter`，所以这里能直接钉住语义：
/// **已贴底时离底 ≤28pt 仍算贴底；未贴底时回到 ≤8pt 才算贴底**（enter < exit）。
/// 两个阈值接反（这是当时的真实 bug）时，8–28pt 之间每次求值都翻转 ⇒ 负对照必须变红。
final class PinnedHysteresisTests: XCTestCase {

    private let enter: CGFloat = 8
    private let exit: CGFloat = 28

    private func pinned(_ distance: CGFloat, currentlyPinned: Bool) -> Bool {
        ConversationViewportController.pinnedAfter(distance: distance, currentlyPinned: currentlyPinned,
                                                  enter: enter, exit: exit)
    }

    /// ① 迟滞带内连续求值 5 次不得翻转（两个起始状态都测）。
    func testHysteresisDoesNotFlipInsideBand() {
        for start in [true, false] {
            var pinned = start
            for step in 0..<5 {
                let next = self.pinned(18, currentlyPinned: pinned)
                XCTAssertEqual(next, pinned, "8–28pt 区间内第 \(step + 1) 次求值翻转了（start=\(start)）")
                pinned = next
            }
        }
    }

    /// ② 阈值本身：28 算贴底、28.1 算离开；8 才算重新贴底、8.1 不算。
    func testThresholdsAreExactAndOrdered() {
        XCTAssertTrue(pinned(28, currentlyPinned: true))
        XCTAssertFalse(pinned(28.1, currentlyPinned: true))
        XCTAssertTrue(pinned(8, currentlyPinned: false))
        XCTAssertFalse(pinned(8.1, currentlyPinned: false))
        XCTAssertLessThan(enter, exit, "enter 必须小于 exit，否则迟滞失效")
    }

    /// ③ 负对照（红-绿对照）：把两个阈值接反 ⇒ 区间内**必然翻转**。
    /// 这条存在的意义就是「用例能抓住这个 bug」，只跑绿不算证明。
    func testSwappedThresholdsFlipInsideBandNegativeControl() {
        let flipped = ConversationViewportController.pinnedAfter(distance: 18, currentlyPinned: true,
                                                                 enter: exit, exit: enter)
        XCTAssertNotEqual(flipped, true, "阈值接反时必须翻转——否则上面那条用例是假绿")
    }
}

/// 背景动画相位时钟（上游 `4582f50` 引入）的用例：**暂停冻结相位、恢复从原相位继续**。
/// 判据（装机侧）：滚动开始背景停、结束背景从原相位接着动——不跳帧。
/// 寄放在本文件是为了省一个新测试文件 + pbxproj 四处登记；类名自证归属。
final class HarnessBackgroundAnimationClockTests: XCTestCase {

    @MainActor
    func testPauseFreezesPhaseAndResumeContinuesFromIt() {
        let clock = HarnessBackgroundAnimationClock()
        let t0 = Date(timeIntervalSinceReferenceDate: 1_000_000)
        XCTAssertEqual(clock.anchor(at: t0), t0, "从未暂停：锚点与墙钟一致（改动前行为零变化）")

        clock.setPaused(true, at: t0)
        XCTAssertEqual(clock.anchor(at: t0.addingTimeInterval(5)), t0, "暂停期间相位必须冻结在 t0")

        clock.setPaused(false, at: t0.addingTimeInterval(5))
        XCTAssertEqual(clock.anchor(at: t0.addingTimeInterval(5)), t0, "恢复那一刻不得跳帧")
        XCTAssertEqual(clock.anchor(at: t0.addingTimeInterval(15)),
                       t0.addingTimeInterval(10), "恢复后从暂停点继续走")
    }

    @MainActor
    func testRepeatedPausesAccumulatePausedDuration() {
        let clock = HarnessBackgroundAnimationClock()
        let t0 = Date(timeIntervalSinceReferenceDate: 2_000_000)
        clock.setPaused(true, at: t0)
        clock.setPaused(false, at: t0.addingTimeInterval(2))
        clock.setPaused(true, at: t0.addingTimeInterval(4))
        clock.setPaused(false, at: t0.addingTimeInterval(9))
        XCTAssertEqual(clock.anchor(at: t0.addingTimeInterval(12)),
                       t0.addingTimeInterval(5), "累计暂停 = 2 + 5 = 7s ⇒ 相位差 12 − 7 = 5")
    }
}

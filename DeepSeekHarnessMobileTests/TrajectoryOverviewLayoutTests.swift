import XCTest
@testable import DeepSeekHarnessMobile

final class TrajectoryOverviewLayoutTests: XCTestCase {
    func testLongToolDoesNotCompressOtherOperationsByDefault() {
        let nodes = [
            node("model", kind: .assistant, start: 1, end: 1.1),
            node("long-tool", kind: .tool, start: 1.1, end: 141.1),
            node("next-tool", kind: .tool, start: 141.1, end: 141.3)
        ]
        let sequence = TrajectoryOverviewLayout(nodes: nodes)
        for entry in sequence.entries {
            XCTAssertEqual(entry.barFrame(width: 300).width, 98, accuracy: 0.001)
        }
        let tools = sequence.entries.dropFirst().map { $0.barFrame(width: 300) }
        XCTAssertEqual(tools[1].minX - tools[0].maxX, 2, accuracy: 0.001)
        XCTAssertLessThanOrEqual(tools[1].maxX, 300)

        let duration = TrajectoryOverviewLayout(nodes: nodes, usesActualDuration: true)
        let longTool = duration.entries[1]
        XCTAssertGreaterThan(longTool.endFraction - longTool.startFraction, 0.98)
    }

    func testContextAndSubtoolRemainSelectableInTheirVisibleLanes() {
        let nodes = [
            node("context", kind: .context, start: 1, end: 1),
            node("model", kind: .assistant, start: 1, end: 2),
            node("subtool", kind: .subtool, start: 2, end: 4)
        ]
        let layout = TrajectoryOverviewLayout(nodes: nodes)
        XCTAssertEqual(layout.nearestNode(x: 10, width: 300, lane: .input)?.id, "context")
        XCTAssertEqual(layout.nearestNode(x: 10, width: 300, lane: .tool)?.id, "subtool")
        XCTAssertEqual(layout.entries[0].barFrame(width: 300).midY, 6)
        XCTAssertEqual(layout.entries[2].barFrame(width: 300).midY, 44)
    }

    func testEmptyOverviewAndZeroWidthHaveNoSelection() {
        XCTAssertTrue(TrajectoryOverviewLayout(nodes: []).entries.isEmpty)
        let layout = TrajectoryOverviewLayout(nodes: [node("tool", kind: .tool, start: 1, end: 1)])
        XCTAssertNil(layout.nearestNode(x: 0, width: 0, lane: .tool))
        XCTAssertEqual(layout.entries[0].barFrame(width: 0).width, 0)
    }

    private func node(_ id: String, kind: TrajectoryNode.Kind, start: TimeInterval, end: TimeInterval) -> TrajectoryNode {
        TrajectoryNode(
            id: id, kind: kind, title: id, subtitle: "", startSeq: 0, endSeq: 0,
            start: Date(timeIntervalSince1970: start), end: Date(timeIntervalSince1970: end), records: []
        )
    }
}

/// **轨迹页倒序**（2026-11-03 用户要求：最新的在最上面；74 件③）。
/// 三条反向判据（iOSDev 补的两条 + 轮次头那条）：
/// ① 展示顺序翻转（最新的在最上），但**序号逐字不变**（只翻展示层，不翻底层）；
/// ② 轮次头（startsTurn）挂在**倒序里每组的第一行**（= 该轮在时间序里的最后一行），
///    直接沿用正序的 startsTurn 会让轮次头挂到每组末行——负对照就卡这里；
/// ③ `visibleNodes`/overview 画布**仍按时间正序**（画布消费方不受倒序影响）。
@MainActor
final class TrajectoryReverseOrderTests: XCTestCase {

    /// 三轮、每轮两行：seq = 10/11（第 1 轮）、20/21（第 2 轮）、30/31（第 3 轮）。
    private func makeNodes() -> [TrajectoryNode] {
        var nodes: [TrajectoryNode] = []
        var seq = 0
        for turn in 1...3 {
            for step in 1...2 {
                seq += 10
                let id = "t\(turn)-r\(step)"
                nodes.append(TrajectoryNode(
                    id: id, kind: .assistant, title: id, subtitle: "",
                    startSeq: seq, endSeq: seq,
                    start: Date(timeIntervalSince1970: TimeInterval(seq)),
                    end: Date(timeIntervalSince1970: TimeInterval(seq)),
                    records: [SessionEvent(sessionId: "s1", seq: seq, time: Double(seq),
                                           event: GatewayEvent(type: "assistant/message", turn: turn, text: id))]
                ))
            }
        }
        return nodes
    }

    private func makePresentation(_ nodes: [TrajectoryNode]) -> TrajectoryPresentation {
        TrajectoryPresentation(nodes: nodes)
    }

    /// ① 倒序 + ② 序号逐字不变（集合相同、顺序正好是时间序的倒排）。
    func testRowsAreReversedNewestFirstWithUnchangedSeqs() {
        let nodes = makeNodes()
        let presentation = makePresentation(nodes)
        XCTAssertEqual(presentation.rows.map(\.node.id),
                       ["t3-r2", "t3-r1", "t2-r2", "t2-r1", "t1-r2", "t1-r1"])
        XCTAssertEqual(presentation.rows.map(\.node.startSeq),
                       [60, 50, 40, 30, 20, 10], "序号必须逐字不变（只翻展示层）")
        XCTAssertEqual(Set(presentation.rows.map(\.node.startSeq)),
                       Set(nodes.map(\.startSeq)), "序号集合不得增删")
    }

    /// ③ 轮次头挂在倒序里每组的第一行：t3-r2 / t2-r2 / t1-r2 三处为 true，其余 false。
    /// 直接沿用正序 startsTurn 的实现会让 t1-r1（现在的最后一行）带 true ⇒ 这里必红。
    func testTurnHeaderHangsOnFirstRowOfEachGroupInDisplayOrder() {
        let presentation = makePresentation(makeNodes())
        let starts = presentation.rows.map(\.startsTurn)
        XCTAssertEqual(starts, [true, false, true, false, true, false])
        XCTAssertEqual(presentation.rows[0].turn, 3)
        XCTAssertEqual(presentation.rows[2].turn, 2)
        XCTAssertEqual(presentation.rows[4].turn, 1)
    }

    /// ④ overview 画布与可见节点**仍按时间正序**（画布消费方不受倒序影响）。
    func testVisibleNodesStayChronological() {
        let nodes = makeNodes()
        let presentation = makePresentation(nodes)
        XCTAssertEqual(presentation.visibleNodes.map(\.startSeq),
                       [10, 20, 30, 40, 50, 60], "可见节点保持时间正序（overview 用）")
        XCTAssertEqual(presentation.turnCount, 3)
    }
}

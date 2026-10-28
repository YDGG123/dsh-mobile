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

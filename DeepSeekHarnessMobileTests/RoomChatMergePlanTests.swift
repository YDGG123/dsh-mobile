import XCTest
@testable import DeepSeekHarnessMobile

/// 群聊合流规则（③）的**可执行定义** —— 与 Android 侧 `RoomChatMergePlanTest.kt` 同用例、同期望。
///
/// 为什么必须有这份仓库内测试：规则漂移时**两端都要红**。此前这些用例只存在于临时探针里，
/// 仓库内没有可执行定义（AndroidTester 2026-11-02 复查时指出：Android 的用例在 `androidApp/src/test`，
/// iOS 的只在该探针里 ⇒ “两端同源、都可执行”只对了一半）。现在与 Android 目录对等。
///
/// 规则口径见 `RoomChatMergePlan` 的文档注释：跨源精确键 `(sessionId, seq)`；取 seq 三级优先；
/// 拿不到 seq 的条目不参与去重（调用方必须记日志，不许静默）。
final class RoomChatMergePlanTests: XCTestCase {

    private typealias Bubble = RoomChatMergePlan.Bubble

    private func bubble(_ id: String, _ role: String, _ text: String, _ seq: Int?,
                        _ millis: Int64?, _ author: String? = nil) -> Bubble {
        Bubble(id: id, role: role, text: text, seq: seq, dateMillis: millis, authorName: author)
    }

    // MARK: - 核心 9 条（与 Android 侧一一对应）

    func testSameSeqIsDeduplicatedKeepingGatewaySide() {
        let gateway = [bubble("s-100", "user", "我说的话", 100, 1000)]
        let bridge = [bubble("bridge:room:100", "user", "我说的话", 100, 1000)]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "s")
        XCTAssertEqual(result.merged.count, 1)
        XCTAssertEqual(result.dropped, ["bridge:room:100"])
        XCTAssertEqual(result.merged.first?.id, "s-100")
    }

    func testSeqCanBeParsedFromGatewayIDWhenFieldIsNil() {
        let gateway = [bubble("s-42", "assistant", "卡片条目", nil, 2000)]
        let bridge = [bubble("bridge:room:42", "assistant", "卡片条目", 42, 2000, "Chief")]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "s")
        XCTAssertEqual(result.merged.count, 1)
        XCTAssertEqual(result.seqFromID, ["s-42"])
        XCTAssertEqual(result.dropped.count, 1)
    }

    func testEntryWithoutSeqIsReportedNotSilentlySkipped() {
        let gateway = [bubble("s-user-abc123", "user", "hello", nil, 3000)]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: [], sessionId: "s")
        XCTAssertEqual(result.withoutSeq, ["s-user-abc123"])
        XCTAssertEqual(result.merged.count, 1, "不参与去重 ≠ 不显示")
    }

    func testUserMarkedIDIsNeverSplit() {
        XCTAssertNil(RoomChatMergePlan.seqFromGatewayID("s-user-abc123"))
    }

    func testBridgeOnlyEntryIsKept() {
        let gateway = [bubble("s-10", "assistant", "网关卡片", 10, 1000)]
        let bridge = [bubble("bridge:room:11", "assistant", "别人说的话", 11, 1100, "AndroidTester")]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "s")
        XCTAssertEqual(result.merged.count, 2)
        XCTAssertTrue(result.dropped.isEmpty)
    }

    func testTimedInsertGoesToTheMiddleNotAppended() {
        // 78 契约变更：seq 是真序（Chief 2026-11-04 定），dateMillis 只做插入提示。
        // 旧行为按 dateMillis 把 seq=9 的 b 插到 seq=2 的 c 之前；新契约下 seq 决定终序
        // ⇒ b(seq 9) 排在 c(seq 2) 之后。真实数据里 seq 与时间极少倒挂；倒挂时以桌面端
        // 一致的 seq 序为准（桌面上 b 就在 c 后面）。
        let gateway = [bubble("s-1", "assistant", "a", 1, 1000), bubble("s-2", "assistant", "c", 2, 3000)]
        let bridge = [bubble("bridge:room:9", "assistant", "b", 9, 2000, "Chief")]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "s")
        XCTAssertEqual(result.merged.map(\.text), ["a", "c", "b"], "seq 决定终序（旧 dateMillis 期望已随契约更新）")
    }

    func testEntryWithoutTimeIsAppendedAtTheEnd() {
        let gateway = [bubble("s-1", "assistant", "a", 1, 1000), bubble("s-2", "assistant", "c", 2, 3000)]
        let bridge = [bubble("bridge:room:stream:1", "assistant", "流式中", nil, nil)]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "s")
        XCTAssertEqual(result.merged.map(\.text), ["a", "c", "流式中"])
    }

    func testEmptyInputIsSafe() {
        let result = RoomChatMergePlan.merge(gateway: [], bridge: [], sessionId: "s")
        XCTAssertTrue(result.merged.isEmpty)
        XCTAssertTrue(result.dropped.isEmpty)
    }

    func testOtherSessionSameSeqIsNotSwallowed() {
        // 本函数按单会话调用；隔离由调用方按 sessionId 分组保证（网关侧没有该 seq ⇒ bridge 条目一律保留）
        let bridge = [bubble("bridge:other:100", "user", "别的群", 100, 1000)]
        let result = RoomChatMergePlan.merge(gateway: [], bridge: bridge, sessionId: "roomA")
        XCTAssertEqual(result.merged.count, 1)
        XCTAssertTrue(result.dropped.isEmpty)
    }

    // MARK: - 输入归一化（Kotlin 侧因“宽侧比较”不适用，iOS 侧必须覆盖）

    func testExtremeLongDoesNotCrashAndRoundTrips() {
        XCTAssertEqual(RoomChatMergePlan.normalizedSeq(Int64.max), Int(Int64.max))
    }

    func testIntIs64BitOnThisPlatform() {
        // 记录平台事实：64 位平台上不存在“超范围 Int64”，Int64→Int 不会 trap
        XCTAssertEqual(Int.bitWidth, 64)
    }

    func testNormalLongConverts() {
        XCTAssertEqual(RoomChatMergePlan.normalizedSeq(42), 42)
    }

    func testNilLongStaysNil() {
        XCTAssertNil(RoomChatMergePlan.normalizedSeq(nil))
    }

    func testEpochSecondsConvertToMillis() {
        XCTAssertEqual(RoomChatMergePlan.millis(fromEpochSeconds: 1_762_000_000.0), 1_762_000_000_000)
    }

    func testNonFiniteOrNonPositiveEpochIsTreatedAsNoTime() {
        XCTAssertNil(RoomChatMergePlan.millis(fromEpochSeconds: .nan))
        XCTAssertNil(RoomChatMergePlan.millis(fromEpochSeconds: -1))
        XCTAssertNil(RoomChatMergePlan.millis(fromEpochSeconds: nil))
    }

    // MARK: - 合成 id 边界（iOSTester 抓到的洞：末段恰好是数字时会被误拆）

    func testSyntheticKeyWithNonNumericTailIsNotSplit() {
        XCTAssertNil(RoomChatMergePlan.seqFromGatewayID("stream-text-9f3a-uuid"))
    }

    func testSyntheticKeyEntryFallsIntoThirdTier() {
        let gateway = [bubble("stream-text-9f3a-uuid", "assistant", "流式文本", nil, 1000)]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: [], sessionId: "s")
        XCTAssertEqual(result.withoutSeq, ["stream-text-9f3a-uuid"], "第三档是常态，不是异常路径")
    }

    func testSyntheticKeyWithNumericTailIsNotSplit() {
        XCTAssertNil(RoomChatMergePlan.seqFromGatewayID("stream-text-42"),
                     "末段是纯数字也不行 —— 合成 id 没有 seq 语义")
    }

    func testSyntheticToolKeyWithNumericTailIsNotSplit() {
        XCTAssertNil(RoomChatMergePlan.seqFromGatewayID("stream-tool-7"))
    }

    func testSyntheticKeyWithDateLikeTailIsNotSplit() {
        XCTAssertNil(RoomChatMergePlan.seqFromGatewayID("stream-text-20261102"))
    }

    func testLegalEventIDIsStillSplit() {
        XCTAssertEqual(RoomChatMergePlan.seqFromGatewayID("sess-1234"), 1234, "不许误伤正常事件 id")
    }

    // MARK: - 桥行 id 的冒号形态（2026-11-02 AndroidTester 指出的跨端真差异）

    /// iOS 的桥行 id 是 `bridge:<conversationId>:<sessionId>:<seq>`（**不是** Android 的 `<sessionId>:<seq>`）。
    /// 这条用例存在的理由：照抄安卓形态写过一版（判前缀 `"\(sessionId):"`），在 iOS 上**恒不命中**——
    /// 解析函数再对也没用，因为 id 以 `bridge:` 开头。用错 id 形态的用例还会"绿着没用"。
    func testSeqFromBridgeIDUsesIOSOwnIDShape() {
        XCTAssertEqual(RoomChatMergePlan.seqFromBridgeID("bridge:room-1:sess-1:7", sessionId: "sess-1"), 7)
        XCTAssertNil(RoomChatMergePlan.seqFromBridgeID("sess-1:7", sessionId: "sess-1"),
                     "安卓形态在 iOS 上不存在，不该命中（否则是死代码")
        XCTAssertNil(RoomChatMergePlan.seqFromBridgeID("bridge:room-1:other-sess:7", sessionId: "sess-1"),
                     "第 3 段 sessionId 必须精确相等")
        XCTAssertNil(RoomChatMergePlan.seqFromBridgeID("bridge:room-1:sess-1:7:9", sessionId: "sess-1"),
                     "多一段 ⇒ 非精确形态，不拆（防假 seq 吞消息）")
        // conversationId 若自带冒号（Chief 提的前提）：从右取仍然对，「恰 4 段」会恒假
        XCTAssertEqual(RoomChatMergePlan.seqFromBridgeID("bridge:room:1:sess-1:7", sessionId: "sess-1"), 7)
        XCTAssertNil(RoomChatMergePlan.seqFromBridgeID("bridge:room-1:sess-1:٣", sessionId: "sess-1"),
                     "只认 ASCII 数字：Kotlin 判 `'0'..'9'`，Swift 的 isNumber 还认其他 Unicode 数字 ⇒ 两端规则必须同形")
    }

    /// **生产路径**：桥气泡**缺 `seq` 字段**时靠 id 形态去重（AndroidTester 指出两端合并层此前都 0 覆盖）。
    /// 关键：桥侧 `seq = nil` —— 给它 seq 就是在测字段路径，压根走不到 id 解析分支。
    func testBridgeColonFormDedupesWhenBridgeSeqIsMissing() {
        let gateway = [bubble("sess-1-7", "assistant", "同一句话", 7, 1000)]
        let bridge = [bubble("bridge:room-1:sess-1:7", "assistant", "同一句话", nil, 1000, "Chief")]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "sess-1")

        XCTAssertEqual(result.merged.count, 1, "同一行不能出现两份")
        XCTAssertEqual(result.dropped, ["bridge:room-1:sess-1:7"], "桥那份必须被去重掉")
        // 桥侧 :: 不许"落 withoutSeq 仍显示"蒙过去
        XCTAssertTrue(result.withoutSeq.isEmpty, "去重生效 ⇒ 不该有 withoutSeq")
    }

    // MARK: - 78 顺序修复（2026-11-04 用户报「消息同步完顺序对不上，后发的跑到前面」）

    /// 判据 1（先红后绿）+ 判据 3：extra 里 seq 乱序、dateMillis 相同的项插入后
    /// 必须按 **seq 升序** 输出（旧实现只按 dateMillis 插入，同毫秒内顺序=插入顺序 ⇒ 旧实现红）。
    /// 判据 3 同时钉住：同 seq（reply:0/reply:1）保持原相对顺序。
    func testExtraInsertsWithSameDateAreFinallyOrderedBySeq() {
        let gateway = [
            bubble("s-100", "assistant", "早", 100, 1000),
            bubble("s-300", "assistant", "晚", 300, 3000),
        ]
        // extra：seq 320 先于 310 到达（78 快照恢复路径的真实形态），dateMillis 全相同
        let bridge = [
            bubble("bridge:r:320", "assistant", "三百二", 320, 2000, "iOSDev"),
            bubble("bridge:r:310", "assistant", "三百一", 310, 2000, "Chief"),
        ]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "r")
        XCTAssertEqual(result.merged.map(\.seq), [100, 300, 310, 320],
            "合流输出必须按 seq 升序（旧实现输出 [100,300,320,310] 必红）")
        XCTAssertEqual(result.merged.last?.id, "bridge:r:320", "seq 大者必须最后（用户看到的「后发的跑到前面」回归位）")
    }

    /// 判据 3：同 seq 的 reply:0/reply:1 保持原相对顺序，不许交换。
    func testEqualSeqKeepsOriginalRelativeOrder() {
        let gateway = [bubble("s-1", "assistant", "锚", 10, 1000)]
        let bridge = [
            bubble("bridge:r:20:reply:0", "assistant", "第一条", 20, 2000, "A"),
            bubble("bridge:r:20:reply:1", "assistant", "第二条", 20, 2000, "B"),
        ]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "r")
        XCTAssertEqual(result.merged.map(\.id), ["s-1", "bridge:r:20:reply:0", "bridge:r:20:reply:1"],
            "同 seq 必须稳定（reply:0 在 reply:1 前）")
    }

    /// 判据 4：无 seq 的项殿后，组内保持相对顺序；withoutSeq 口径不变。
    func testSeqLessItemsGoLastKeepingRelativeOrder() {
        let gateway = [
            bubble("s-5", "assistant", "有seq", 5, 1000),
            // id 不能带数字尾巴：seqFromGatewayID 会从 id 末段拆出 seq（"no-seq-1" ⇒ 1）
            bubble("no-seq-anchor", "assistant", "无seq一", nil, 3000),
        ]
        let bridge = [bubble("bridge:r:30", "assistant", "三十", 30, 2000, "B")]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: bridge, sessionId: "r")
        XCTAssertEqual(result.merged.map(\.id), ["s-5", "bridge:r:30", "no-seq-anchor"],
            "有 seq 按 seq；无 seq 殿后且相对顺序稳定")
        XCTAssertEqual(result.withoutSeq, ["no-seq-anchor"], "withoutSeq 日志口径不变")
    }

    /// 判据 2（反向对照）：extra 为空时输出必须逐字节等于 gateway（既有 guard 行为不许变）。
    func testEmptyExtraReturnsGatewayVerbatim() {
        let gateway = [
            bubble("s-9", "assistant", "九", 9, 900),
            bubble("s-2", "assistant", "二", 2, 200),
        ]
        let result = RoomChatMergePlan.merge(gateway: gateway, bridge: [], sessionId: "r")
        XCTAssertEqual(result.merged.map(\.id), gateway.map(\.id),
            "extra 为空 ⇒ 原样返回（guard 行为不变，不做排序）")
    }
}
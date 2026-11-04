import XCTest
@testable import DeepSeekHarnessMobile

/// 「没装 Bot 插件」三件事的反向测试（2026-11-04 派活单，网关契约 `d174044`）。
///
/// 契约：`/info` `capabilities.botPlugin ∈ {full, legacy, missing}`；
/// `nil`（旧网关没下发/未知值）⇒ **一切照旧**——「不知道」绝不能当「没装」。
/// 反向判据三条：missing ⇒ 横幅在 + 聊天页终态不重连；legacy/full ⇒ 横幅不在、行为不变。
/// 纯函数级验证（引擎/视图的接线在设备级走手测，本机无模拟器跑不了 XCTest）。
final class BotPluginMissingTests: XCTestCase {

    // MARK: - ① 解析（BridgeTeamAdapter.botPlugin(fromInfoJSON:)）

    private func assertParsed(_ raw: String, expected: BotPluginAvailability?, line: UInt = #line) {
        XCTAssertEqual(BridgeTeamAdapter.botPlugin(fromInfoJSON: raw), expected, line: line)
    }

    func testBotPluginThreeValuesParse() {
        assertParsed(#"{"capabilities":{"botPlugin":"full"}}"#, expected: .full)
        assertParsed(#"{"capabilities":{"botPlugin":"legacy"}}"#, expected: .legacy)
        assertParsed(#"{"capabilities":{"botPlugin":"missing"}}"#, expected: .missing)
    }

    func testAbsentOrUnknownBotPluginMeansUnknownNotMissing() {
        // 旧网关根本不下发这个字段 ⇒ nil（照旧），绝不能误判成 missing
        assertParsed(#"{"capabilities":{"actions":[]}}"#, expected: nil)
        assertParsed(#"{}"#, expected: nil)
        assertParsed(#"不是 JSON"#, expected: nil)
        // 未来新增的未知值域同样 ⇒ nil（fail-open 到既有行为）
        assertParsed(#"{"capabilities":{"botPlugin":"partial"}}"#, expected: nil)
        // 大小写宽容（网关小写下发，防御性接受任意大小写）
        assertParsed(#"{"capabilities":{"botPlugin":"MISSING"}}"#, expected: .missing)
    }

    func testLegacyAndFullDoNotTriggerMissingBehaviors() {
        for json in [#"{"capabilities":{"botPlugin":"legacy"}}"#, #"{"capabilities":{"botPlugin":"full"}}"#,
                     #"{"capabilities":{}}"#] {
            let parsed = BridgeTeamAdapter.botPlugin(fromInfoJSON: json)
            XCTAssertNotEqual(parsed, .missing, "legacy/full/未知都不得触发 missing 态表现")
        }
    }

    // MARK: - ② 聊天页终态（BridgeChatEngine.pluginMissingFailure）

    func testRedactedProjectionRequiredIsTerminalWithChineseMessage() {
        let message = BridgeChatEngine.pluginMissingFailure(forServerCode: "REDACTED_PROJECTION_REQUIRED")
        XCTAssertEqual(message, String(localized: "未安装 Bot 插件，无法发送消息"))
        // 小写形态等价（handleEvent 里已 lowercased，这里守住函数自身的大小写无关性）
        XCTAssertEqual(BridgeChatEngine.pluginMissingFailure(forServerCode: "redacted_projection_required"),
                       String(localized: "未安装 Bot 插件，无法发送消息"))
    }

    func testTransientRedactionFailuresAreNotTerminal() {
        // 反向防误伤：这两个是临时故障，短路成终态会把可恢复的会话杀死
        XCTAssertNil(BridgeChatEngine.pluginMissingFailure(forServerCode: "REDACTION_FAILED"))
        XCTAssertNil(BridgeChatEngine.pluginMissingFailure(forServerCode: "REDACTION_UNAVAILABLE"))
        // 401 族绝不能进终态（会破坏「刷新重发」既有逻辑）
        XCTAssertNil(BridgeChatEngine.pluginMissingFailure(forServerCode: "unauthorized"))
        XCTAssertNil(BridgeChatEngine.pluginMissingFailure(forServerCode: "token_expired"))
    }

    // MARK: - ③ HTTP 501 的 code → 中文（BridgeChatEngine.humanMessage(forStatusBody:)）

    func testStatusEnvelopeMapsByCodeNotByMessageText() {
        let redacted = #"{"error":{"code":"REDACTED_PROJECTION_REQUIRED","message":"A DS Bot-owned redacted transcript is required."}}"#
        XCTAssertEqual(BridgeChatEngine.humanMessage(forStatusBody: redacted),
                       String(localized: "未安装 Bot 插件，无法发送消息"))
        // message 文本是英文原话：映射只认 code，换文案不影响判定
        let stable = #"{"error":{"code":"STABLE_SUBMIT_REQUIRED","message":"DS Bot 0.1.7 needs the reviewed bridge submit extension for reliable sending."}}"#
        XCTAssertEqual(BridgeChatEngine.humanMessage(forStatusBody: stable),
                       String(localized: "网关缺少可靠发送扩展，暂时无法发送消息"))
        // 未映射的 code ⇒ nil（调用方回落原样，行为不变）
        XCTAssertNil(BridgeChatEngine.humanMessage(forStatusBody: #"{"error":{"code":"NOT_FOUND","message":"x"}}"#))
        XCTAssertNil(BridgeChatEngine.humanMessage(forStatusBody: "不是 JSON"))
        XCTAssertNil(BridgeChatEngine.humanMessage(forStatusBody: #"{"error":{}}"#))
    }

    func testStableSubmitCopyDoesNotBlameMissingPlugin() {
        // legacy 老插件也会缺稳定提交扩展（adapter.mjs:287）⇒ 文案不得说「未安装 Bot 插件」
        let message = BridgeChatEngine.humanMessage(forStatusBody:
            #"{"error":{"code":"STABLE_SUBMIT_REQUIRED","message":"x"}}"#)
        XCTAssertFalse(message?.contains("未安装") == true,
                       "STABLE_SUBMIT_REQUIRED 的文案不能归因为「未安装」")
    }
}

import XCTest
@testable import DeepSeekHarnessMobile

/// 77②「群聊隐藏模型/思考档选择」的反向测试（2026-11-04 事故防线）。
///
/// 事故：手机端群聊输入框出现了模型选择，用户在群聊切模型 ⇒ 三个群聊每轮
/// `MODEL_SWITCH_COMPACTION_FAILED`、用户消息被丢。
///
/// 2026-11-06 用户两轮裁定收敛后的最终口径：**只有群聊（rooms）隐藏 chip**——
/// Bot 会话要能切模型（Bot 模型独立于会话），普通会话/私聊/未配对全显示。
/// 判定入口 = `BridgeTeamMirror.modelControlsHidden`（true = 隐藏）：
/// READY/OFFLINE 按 rooms 命中；nil/IDLE/NEEDS_PAIRING 显示；LOADING 短暂 fail-closed。
final class BotDomainComposerTests: XCTestCase {

    /// 走**真解码路径**构造镜像（不用成员逐一 init）：fields 形态与网关实际下发一致，
    /// Row 只需 id/kind/name/sessionId 四个必填键。
    private func mirror(_ json: String) throws -> BridgeTeamMirror {
        try JSONDecoder().decode(BridgeTeamMirror.self, from: Data(json.utf8))
    }

    private let readyMirror = """
    {"phase":"READY",
     "bots":[{"id":"b1","kind":"bot","name":"ReleaseBot","sessionId":"session-bot-1"}],
     "rooms":[{"id":"r1","kind":"room","name":"开发组","sessionId":"session-room-1"}],
     "sessions":[{"id":"s1","kind":"session","name":"随手记","sessionId":"session-plain-1"}]}
    """

    // ---- 三条反向判据（2026-11-06 口径） ----

    func testBotSessionKeepsModelControls() throws {
        // 2026-11-06 用户改口径：Bot 会话**放开**切模型（旧判据「Bot 也隐藏」作废）。
        // Bot 的模型独立于会话，桌面端 ds-bot 有自己的 ModelChoiceDock；失败走
        // session control → lastError 全局弹窗，不静默。
        XCTAssertFalse(BridgeTeamMirror.modelControlsHidden(sessionId: "session-bot-1", mirror: try mirror(readyMirror)),
                       "Bot 会话必须显示模型/思考档选择——用户 2026-11-06 原话「bot 单独是要能在输入框切换模型和思考的」")
    }

    func testRoomSessionHidesModelControls() throws {
        XCTAssertTrue(BridgeTeamMirror.modelControlsHidden(sessionId: "session-room-1", mirror: try mirror(readyMirror)),
                      "群聊 room 必须隐藏——11-04 卡死事故的正是这条路，防线不复发")
    }

    func testPlainSessionKeepsModelControls() throws {
        XCTAssertFalse(BridgeTeamMirror.modelControlsHidden(sessionId: "session-plain-1", mirror: try mirror(readyMirror)),
                       "普通会话不受影响，chip 必须照常显示")
    }

    // ---- fail-closed 语义（三态判定，2026-11-06 改版） ----

    func testMirrorNotArrivedKeepsModelControls() {
        // 2026-11-06 用户裁定：bridge 未启动/未配对时 mirror 为 nil——此时不可能有
        // Bot 域会话，普通会话/私聊的 chip 必须显示。旧判据「nil ⇒ 隐藏」把
        // 没配对 bridge 的用户全部吞了（用户报的「输入框里模型选择没了」就是它）。
        XCTAssertFalse(BridgeTeamMirror.modelControlsHidden(sessionId: "session-plain-1", mirror: nil),
                       "bridge 未启动 ⇒ 不可能有 Bot 域会话，chip 必须显示")
    }

    func testUnpairedMirrorKeepsModelControls() throws {
        // 未配对（IDLE）：bots/rooms 为空 ⇒ 普通会话照常显示，不许因「接了 bridge」而全端隐藏
        let idle = try mirror("""
        {"phase":"IDLE","bots":[],"rooms":[],
         "sessions":[{"id":"s1","kind":"session","name":"随手记","sessionId":"session-plain-1"}]}
        """)
        XCTAssertFalse(BridgeTeamMirror.modelControlsHidden(sessionId: "session-plain-1", mirror: idle))
        let needsPairing = try mirror("""
        {"phase":"NEEDS_PAIRING","bots":[],"rooms":[],"sessions":[]}
        """)
        XCTAssertFalse(BridgeTeamMirror.modelControlsHidden(sessionId: "session-plain-1", mirror: needsPairing))
    }

    func testLoadingMirrorFailsClosed() throws {
        // 反向（事故防线保留）：LOADING 时名册在路上，此刻确实可能是群聊——
        // 短暂隐藏，方向仍是「先隐藏后显示」，不会反向闪。名册 READY 后按 rooms 命中揭示。
        let loading = try mirror("""
        {"phase":"LOADING","bots":[],"rooms":[],"sessions":[]}
        """)
        XCTAssertTrue(BridgeTeamMirror.modelControlsHidden(sessionId: "session-plain-1", mirror: loading),
                      "LOADING 短暂 fail-closed：防止用户在名册未到时于群聊切模型（11-04 事故路径）")
    }

    // ---- 判定的边界 ----

    func testNilOrEmptySessionIdNeverCountsAsBotDomain() throws {
        let ready = try mirror(readyMirror)
        XCTAssertFalse(BridgeTeamMirror.modelControlsHidden(sessionId: nil, mirror: ready))
        XCTAssertFalse(ready.isBotDomainSession(""))
    }

    func testSessionsRowIsNotBotDomainEvenWithSamePrefix() throws {
        // 反向防误伤：sessionId 前缀相同的不同会话不得命中（判定是整串相等，不是包含）
        let m = try mirror("""
        {"phase":"READY",
         "bots":[{"id":"b1","kind":"bot","name":"B","sessionId":"session-bot-1"}],
         "rooms":[],"sessions":[]}
        """)
        XCTAssertFalse(m.isBotDomainSession("session-bot-1-extra"),
                       "包含关系不等于命中：判定必须整串相等")
        XCTAssertTrue(m.isBotDomainSession("session-bot-1"))
    }
}

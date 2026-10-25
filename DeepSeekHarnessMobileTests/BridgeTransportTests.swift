import DeepSeekHarnessShared
import XCTest
@testable import DeepSeekHarnessMobile

/// P1 传输层纯逻辑用例（无网络）：origin 绑定、退避、pin 格式、镜像解码。
final class BridgeTransportTests: XCTestCase {

    func testOriginValidationAcceptsPlainHTTPSAndStripsBridgePath() {
        XCTAssertEqual(
            BridgeOriginValidation.origin(from: "https://host.ts.net")?.absoluteString,
            "https://host.ts.net"
        )
        XCTAssertEqual(
            BridgeOriginValidation.origin(from: "https://host.ts.net/mobile/v1/")?.absoluteString,
            "https://host.ts.net"
        )
        XCTAssertEqual(
            BridgeOriginValidation.origin(from: "https://192.168.1.5:7443")?.absoluteString,
            "https://192.168.1.5:7443"
        )
    }

    func testOriginValidationRejectsInsecureAndDirtyValues() {
        XCTAssertNil(BridgeOriginValidation.origin(from: "http://192.168.1.5:7443"))
        XCTAssertNil(BridgeOriginValidation.origin(from: "https://user:pass@host"))
        XCTAssertNil(BridgeOriginValidation.origin(from: "https://host/path/other"))
        XCTAssertNil(BridgeOriginValidation.origin(from: "https://host?query=1"))
        // loopback 显式开发模式放行。
        XCTAssertNotNil(
            BridgeOriginValidation.origin(from: "http://127.0.0.1:7443", allowInsecureLoopback: true)
        )
    }

    func testRequestBindingRequiresSameOriginAndBridgePrefix() {
        let origin = URL(string: "https://host.ts.net")!
        XCTAssertTrue(
            BridgeOriginValidation.meetsBinding(
                URL(string: "https://host.ts.net/mobile/v1/state")!, origin: origin
            )
        )
        XCTAssertFalse(
            BridgeOriginValidation.meetsBinding(
                URL(string: "https://other.ts.net/mobile/v1/state")!, origin: origin
            )
        )
        XCTAssertFalse(
            BridgeOriginValidation.meetsBinding(
                URL(string: "https://host.ts.net:8443/mobile/v1/state")!, origin: origin
            )
        )
        // 表外路径（含刷新跳转）一律拒绝。
        XCTAssertFalse(
            BridgeOriginValidation.meetsBinding(
                URL(string: "https://host.ts.net/other/state")!, origin: origin
            )
        )
    }

    func testPinFormatMatchesSDK() {
        XCTAssertTrue(BridgeOriginValidation.isValidPin("sha256:" + String(repeating: "a", count: 64)))
        XCTAssertFalse(BridgeOriginValidation.isValidPin("sha256:" + String(repeating: "A", count: 64)))
        XCTAssertFalse(BridgeOriginValidation.isValidPin("sha256:short"))
    }

    func testTransportConfigRejectsInvalidPinAndDetectsMode() {
        let pinned = BridgeTransportConfig(
            originString: "https://host.ts.net",
            tlsPin: "sha256:" + String(repeating: "b", count: 64)
        )
        XCTAssertNotNil(pinned)
        XCTAssertTrue(pinned?.isPinned == true)

        // 无 pin ⇒ 模式 B（公网受信，默认路径）。
        let publicTrust = BridgeTransportConfig(originString: "https://host.ts.net", tlsPin: nil)
        XCTAssertNotNil(publicTrust)
        XCTAssertFalse(publicTrust?.isPinned == true)

        XCTAssertNil(
            BridgeTransportConfig(originString: "https://host.ts.net", tlsPin: "sha256:nope")
        )
    }

    func testBackoffCapsAtFifteenSeconds() {
        XCTAssertEqual(BridgeBackoff.delay(attempt: 0), 0.75, accuracy: 0.001)
        XCTAssertEqual(BridgeBackoff.delay(attempt: 1), 1.5, accuracy: 0.001)
        XCTAssertEqual(BridgeBackoff.delay(attempt: 2), 3, accuracy: 0.001)
        XCTAssertEqual(BridgeBackoff.delay(attempt: 3), 6, accuracy: 0.001)
        XCTAssertEqual(BridgeBackoff.delay(attempt: 4), 12, accuracy: 0.001)
        XCTAssertEqual(BridgeBackoff.delay(attempt: 5), 15, accuracy: 0.001)
        XCTAssertEqual(BridgeBackoff.delay(attempt: 20), 15, accuracy: 0.001)
    }

    func testMirrorDecodesKmpSnapshotShape() throws {
        // 与 KMP SharedTeamStore 序列化的 TeamUiState 同形（encodeDefaults=true）。
        let json = """
        {"phase":"READY","revision":7,"serverId":"computer-1","serverName":"DS Bot",
         "capabilities":{"actionsWhitelist":["models","set-flags"],
           "features":{"groups":true,"queue":false}},
         "bots":[
           {"id":"chief","kind":"bot","name":"Chief","sessionId":"s-chief","role":"Main",
            "pinned":true,"hidden":false,"parts":[],"members":[],
            "avatar":{"shape":"circle","color":"#123456"}},
           {"id":"coder","kind":"bot","name":"Coder","sessionId":"s-coder",
            "pinned":false,"hidden":false,"parts":[],"members":[]}],
         "rooms":[],"models":[],"mainBotIds":["chief"],
         "lastError":null}
        """
        let mirror = try JSONDecoder().decode(BridgeTeamMirror.self, from: Data(json.utf8))
        XCTAssertTrue(mirror.isReady)
        XCTAssertEqual(mirror.revision, 7)
        XCTAssertEqual(mirror.bots.map(\.id), ["chief", "coder"])
        XCTAssertEqual(mirror.mainBotIds, ["chief"])
        XCTAssertTrue(mirror.capabilities?.groupsAllowed == true)
        XCTAssertTrue(mirror.capabilities?.actionsWhitelist?.contains("set-flags") == true)
        XCTAssertEqual(mirror.bots.first?.avatar?.color, "#123456")
    }

    func testMirrorDecodesIdentityFailureAndEffect() throws {
        let snapshot = """
        {"phase":"NEEDS_PAIRING","bots":[],"rooms":[],"models":[],
         "capabilities":{"actionsWhitelist":null,"features":{}},
         "lastError":{"source":"client","code":"server_changed",
           "knownClientCode":"SERVER_CHANGED","message":"服务器身份已变化"}}
        """
        let mirror = try JSONDecoder().decode(BridgeTeamMirror.self, from: Data(snapshot.utf8))
        XCTAssertTrue(mirror.needsPairing)
        XCTAssertEqual(mirror.lastError?.code, "server_changed")
        XCTAssertEqual(mirror.lastError?.displayText, "服务器身份已变化")

        let effect = """
        [{"kind":"open-conversation","sessionId":"s-chief","effectId":"chief"}]
        """
        let effects = try JSONDecoder().decode(
            [BridgeTeamEffectMirror].self,
            from: Data(effect.utf8)
        )
        XCTAssertEqual(effects.single?.kind, "open-conversation")
        XCTAssertEqual(effects.single?.sessionId, "s-chief")
    }

    func testBridgeHttpEffectAndPendingMirrorDecode() throws {
        // P2：写操作 effect 与待写状态同形（KMP SharedTeamStore 序列化）。
        let snapshot = """
        {"phase":"READY","bots":[],"rooms":[],"models":[{"ref":"mock/model","name":"Mock"}],
         "capabilities":{"actionsWhitelist":["set-flags","update-bot","models","efforts"]},
         "pendingActions":{"chief":{"action":"set-flags","effectId":"team-effect:1"}},
         "devices":[{"id":"dev-1","name":"我的 iPhone","createdAt":1730000000000,
            "lastSeenAt":1730000001000},{"id":"dev-2"}],
         "currentDeviceId":"dev-1","devicesLoading":false,
         "effortsByModel":{"mock/model":{"efforts":[{"id":"low","name":"Low"}],"default":"low"}}}
        """
        let mirror = try JSONDecoder().decode(BridgeTeamMirror.self, from: Data(snapshot.utf8))
        XCTAssertTrue(mirror.isPending("chief"))
        XCTAssertFalse(mirror.isPending("coder"))
        XCTAssertTrue(mirror.supportsAction("set-flags"))
        XCTAssertFalse(mirror.supportsAction("set-main"))
        XCTAssertEqual(mirror.devices?.count, 2)
        XCTAssertEqual(mirror.currentDeviceId, "dev-1")
        XCTAssertEqual(mirror.effortsByModel?["mock/model"]?.efforts.single?.id, "low")
        XCTAssertEqual(mirror.devices?.first?.lastSeenAt, 1730000001000)

        let effect = """
        [{"kind":"bridge-http","method":"POST","path":"/actions",
          "bodyJson":"{\\"endpoint\\":\\"set-flags\\",\\"payload\\":{\\"id\\":\\"chief\\",\\"pinned\\":true}}",
          "requiresAuth":true,"effectId":"team-effect:1"}]
        """
        let effects = try JSONDecoder().decode(
            [BridgeTeamEffectMirror].self,
            from: Data(effect.utf8)
        )
        XCTAssertEqual(effects.single?.kind, "bridge-http")
        XCTAssertEqual(effects.single?.method, "POST")
        XCTAssertEqual(effects.single?.path, "/actions")
        XCTAssertNotNil(effects.single?.bodyJson)
        XCTAssertEqual(effects.single?.requiresAuth, true)
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}

/// 配对资料解析与 complete 三态：走 KMP（commonMain）实现，Swift 侧只做桥接。
final class BridgePairingPayloadTests: XCTestCase {
    private var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func testParsesCompanionPairingPayload() {
        let expires = nowMillis + 120_000
        let json = """
        {"url":"https://pushing-cricket-ensure-execute.trycloudflare.com",
         "code":"abc123","serverId":"computer-1","expiresAt":\(expires)}
        """
        let parsed = BridgePairingKt.parseBridgePairingPayload(raw: json, nowMillis: nowMillis)
        XCTAssertTrue(parsed.isValid)
        XCTAssertEqual(parsed.origin, "https://pushing-cricket-ensure-execute.trycloudflare.com")
        XCTAssertEqual(parsed.code, "abc123")
        XCTAssertEqual(parsed.serverId, "computer-1")
    }

    func testRejectsMalformedPairingPayloads() {
        XCTAssertEqual(
            BridgePairingKt.parseBridgePairingPayload(raw: "not-json", nowMillis: nowMillis).status,
            "invalid_json"
        )
        XCTAssertEqual(
            BridgePairingKt.parseBridgePairingPayload(
                raw: #"{"url":"http://192.168.1.5:7443","code":"abc"}"#,
                nowMillis: nowMillis
            ).status,
            "insecure_url"
        )
        XCTAssertEqual(
            BridgePairingKt.parseBridgePairingPayload(
                raw: #"{"url":"https://host.example","code":""}"#,
                nowMillis: nowMillis
            ).status,
            "invalid_code"
        )
    }

    func testCompleteThreeStatesThroughKMP() {
        let pending = BridgePairingKt.classifyBridgePairingComplete(
            raw: #"{"status":"pending","requestId":"req-1"}"#,
            origin: "https://host.example",
            tlsPin: nil
        )
        XCTAssertTrue(pending.isPending)

        let approved = BridgePairingKt.classifyBridgePairingComplete(
            raw: """
            {"accessToken":"acc","refreshToken":"ref","deviceId":"dev-1",
             "serverId":"computer-1","expiresAt":\(nowMillis + 900_000)}
            """,
            origin: "https://host.example",
            tlsPin: nil
        )
        XCTAssertTrue(approved.isApproved)
        XCTAssertNotNil(approved.sessionJson)

        let invalid = BridgePairingKt.classifyBridgePairingComplete(
            raw: "nope",
            origin: "https://host.example",
            tlsPin: nil
        )
        XCTAssertEqual(invalid.status, "invalid")
    }
}

/// 离线原因分类：区分「地址失效/网络不通/服务端异常」给用户不同指引。
final class BridgeConnectionIssueTests: XCTestCase {
    func testAddressFailuresSuggestChangingServer() {
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(URLError(.cannotFindHost)),
            .addressInvalid
        )
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(URLError(.dnsLookupFailed)),
            .addressInvalid
        )
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(URLError(.cannotConnectToHost)),
            .addressInvalid
        )
    }

    func testNetworkFailuresSuggestCheckingNetwork() {
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(URLError(.notConnectedToInternet)),
            .network
        )
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(URLError(.timedOut)),
            .network
        )
    }

    func testServerFailuresFlagServerIssue() {
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(BridgeResponseStatus(status: 502, body: "")),
            .server
        )
        XCTAssertEqual(
            BridgeTeamAdapter.classifyTransportFailure(BridgeResponseStatus(status: 409, body: "")),
            .unknown
        )
    }

    // MARK: - 主动换凭证（用户真机「反复断连又连接」的根因）

    /// 网关访问凭证 15 分钟过期。主动刷新必须**早于**这个窗口，否则又回到「被踢才刷」；
    /// 也不能太短（频繁换证对网关是负担）。这条常量是断连频率的唯一旋钮。
    @MainActor
    func testProactiveTokenRefreshHappensBeforeCredentialExpiry() {
        let interval = BridgeTeamAdapter.proactiveRefreshInterval
        XCTAssertEqual(interval, 14 * 60, "按派单口径：14 分钟主动换新")
        XCTAssertLessThan(interval, 15 * 60, "必须早于 15 分钟凭证过期，否则又变成每 15 分钟被踢一次")
        XCTAssertGreaterThanOrEqual(interval, 60, "间隔不能退化成高频轮询")
    }

    // MARK: - 凭证 TTL 协商（网关 /info 公布 → 手机按 90% 排主动刷新）

    /// 有字段：按 90% 换算（900s → 810s = 13.5 分钟，比硬编码的 14 分钟更早）。
    func testTokenTTLFromGatewayInfo() throws {
        let info = """
        {"protocolVersion":1,"serverId":"pc-1","name":"DS Bot",
         "capabilities":{"team":true,"accessTokenTtlSeconds":900}}
        """
        let interval = try XCTUnwrap(BridgeTokenTTL.proactiveInterval(infoJSON: info))
        XCTAssertEqual(interval, 810, accuracy: 0.001, "900s × 90% = 810s（13.5 分钟，比硬编码 14 分钟更早）")
    }

    /// 字段缺失 / capabilities 缺失 / 类型不对 / 非正数 → 一律当作"没给"，
    /// 由调用方回落到 14 分钟兜底（老网关必须能连）。
    func testTokenTTLFallsBackWhenGatewayDoesNotPublishIt() {
        XCTAssertNil(BridgeTokenTTL.proactiveInterval(infoJSON: #"{"protocolVersion":1,"serverId":"pc-1"}"#))
        XCTAssertNil(BridgeTokenTTL.proactiveInterval(infoJSON: #"{"capabilities":{"team":true}}"#))
        XCTAssertNil(BridgeTokenTTL.proactiveInterval(infoJSON: #"{"capabilities":{"accessTokenTtlSeconds":"900"}}"#),
                     "字符串不猜，按没给处理")
        XCTAssertNil(BridgeTokenTTL.proactiveInterval(infoJSON: #"{"capabilities":{"accessTokenTtlSeconds":0}}"#))
        XCTAssertNil(BridgeTokenTTL.proactiveInterval(infoJSON: "not-json"))
    }

    /// 上下限：太短不狂刷、太长不逼近过期。
    func testTokenTTLIntervalIsClamped() throws {
        XCTAssertEqual(try XCTUnwrap(BridgeTokenTTL.proactiveInterval(ttlSeconds: 30)), 60, accuracy: 0.001,
                       "太短的下限 60s")
        XCTAssertEqual(try XCTUnwrap(BridgeTokenTTL.proactiveInterval(ttlSeconds: 6 * 3600)), 3600, accuracy: 0.001,
                       "太长的上限 60min")
        XCTAssertNil(BridgeTokenTTL.proactiveInterval(ttlSeconds: -5), "非正数当作没给")
    }
}

// MARK: - ① 分块传输：/info 上限解析与共享上限持有者

final class ChunkLimitNegotiationTests: XCTestCase {

    /// 网关字段名以网关为准：maxFrameBytes / maxReassembledBytes / maxStreamsPerConnection。
    func testLimitsParsedFromGatewayInfo() throws {
        let info = """
        {"protocolVersion":1,"serverId":"pc-1",
         "capabilities":{"maxFrameBytes":524288,"maxReassembledBytes":4194304,"maxStreamsPerConnection":2}}
        """
        let limits = try XCTUnwrap(FrameReassembler.limits(fromInfo: info))
        XCTAssertEqual(limits.maxChunkBytes, 512 * 1024)
        XCTAssertEqual(limits.maxReassembledBytes, 4 * 1024 * 1024)
        XCTAssertEqual(limits.maxStreams, 2)
        XCTAssertEqual(limits.streamTimeout, FrameReassembler.Limits.default.streamTimeout,
                       "超时是客户端内部资源保护，网关不公布，必须保持默认 30s")
    }

    /// 老网关一个字段都不公布 → nil（调用方保持本地兜底，不猜）。
    func testLimitsMissingFromOldGatewayReturnsNil() {
        XCTAssertNil(FrameReassembler.limits(fromInfo: #"{"protocolVersion":1,"serverId":"pc-1"}"#))
        XCTAssertNil(FrameReassembler.limits(fromInfo: #"{"capabilities":{"team":true}}"#))
        XCTAssertNil(FrameReassembler.limits(fromInfo: #"{"capabilities":{"maxFrameBytes":"524288"}}"#),
                     "字符串不猜")
        XCTAssertNil(FrameReassembler.limits(fromInfo: "not-json"))
    }

    /// 部分字段也认（比如只公布了单块上限）。
    func testLimitsAcceptPartialPublication() throws {
        let limits = try XCTUnwrap(FrameReassembler.limits(fromInfo:
            #"{"capabilities":{"maxFrameBytes":131072}}"#))
        XCTAssertEqual(limits.maxChunkBytes, 128 * 1024)
        XCTAssertEqual(limits.maxReassembledBytes, FrameReassembler.Limits.default.maxReassembledBytes)
    }

    /// 能力名是与网关约定死的字符串，改了网关就不发 chunk。
    func testCapabilityNameIsContract() {
        XCTAssertEqual(FrameReassembler.capabilityName, "chunked-frames-v1")
    }

    /// 共享持有者：网关不公布时保持原值（不乱覆盖），公布后按网关值更新。
    @MainActor
    func testSharedLimitsUpdateOnlyWhenPublished() {
        BridgeChunkLimits.update(fromInfo: #"{"capabilities":{}}"#)
        XCTAssertEqual(BridgeChunkLimits.shared.maxChunkBytes,
                       FrameReassembler.Limits.default.maxChunkBytes,
                       "没公布就保持默认，不能瞎改")
        BridgeChunkLimits.update(fromInfo: #"{"capabilities":{"maxFrameBytes":262144}}"#)
        XCTAssertEqual(BridgeChunkLimits.shared.maxChunkBytes, 256 * 1024)
        BridgeChunkLimits.update(fromInfo: #"{"capabilities":{"maxFrameBytes":262144}}"#)
        XCTAssertEqual(BridgeChunkLimits.shared.maxChunkBytes, 256 * 1024, "幂等")
    }
}

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
}

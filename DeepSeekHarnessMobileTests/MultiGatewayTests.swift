import SwiftUI
import XCTest
@testable import DeepSeekHarnessMobile

@MainActor
private final class MigrationTestCredentials: GatewayCredentialStoring {
    func loadToken(for endpoint: URL) -> String? { nil }
    func saveToken(_ token: String, for endpoint: URL) {}
    func deleteToken(for endpoint: URL) {}
    func loadOrCreateDeviceID() -> String { "migration-test-device" }
}

private actor ColdLaunchProfileCredentials: GatewayCredentialStoring {
    let profileID: String
    private(set) var tokenReads = 0
    init(profileID: String) { self.profileID = profileID }
    func loadToken(for endpoint: URL) -> String? {
        tokenReads += 1
        return endpoint.absoluteString == "https://gateway-credential.invalid/\(profileID)" ? "saved-device-token" : nil
    }
    func saveToken(_ token: String, for endpoint: URL) {}
    func deleteToken(for endpoint: URL) {}
    func loadOrCreateDeviceID() async throws -> String {
        // 停在凭据准备阶段，验证启动连接意图而不打开真实网络连接。
        try await Task.sleep(for: .seconds(30))
        return "cold-launch-device"
    }
}

final class MultiGatewayTests: XCTestCase {
    private let gatewayID = "d56a1098-8519-43a1-9dce-fb99863bf5bb"

    @MainActor
    func testRestoredExternalGatewayAutoConnectsWithoutDependingOnRootViewTask() async throws {
        let suite = "external-cold-launch-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let external = "wss://external.example/ws/mobile"
        let profile = GatewayProfile(gatewayId: gatewayID, gatewayName: "外网主机",
                                     endpoints: ["ws://192.168.1.94:3081/ws/mobile", external], preferredEndpoint: external)
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        defaults.set(profile.id, forKey: "gateway.activeID")
        let credentials = ColdLaunchProfileCredentials(profileID: profile.id)
        let hosts = MultiGatewayStore(defaults: defaults, credentials: credentials)
        await hosts.waitUntilReady()
        defer { hosts.activeStore.deactivateGateway() }
        let deadline = ProcessInfo.processInfo.systemUptime + 1
        while !hosts.activeStore.gateway.recoveryStateForTesting.wantsConnection,
              ProcessInfo.processInfo.systemUptime < deadline { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertEqual(hosts.activeStore.endpoint, external)
        XCTAssertEqual(hosts.activeStore.gateway.state, .connecting)
        XCTAssertTrue(hosts.activeStore.gateway.recoveryStateForTesting.wantsConnection)
        let reads = await credentials.tokenReads
        XCTAssertGreaterThan(reads, 0)
    }

    @MainActor
    func testRestoredManuallyDisconnectedGatewayDoesNotAutoConnect() async throws {
        let suite = "manual-cold-launch-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let profile = GatewayProfile(gatewayName: "手动断开的主机", endpoints: ["wss://external.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: profile.id).automaticallyConnect = false
        let credentials = ColdLaunchProfileCredentials(profileID: profile.id)
        let hosts = MultiGatewayStore(defaults: defaults, credentials: credentials)
        await hosts.waitUntilReady()
        defer { hosts.activeStore.deactivateGateway() }
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(hosts.activeStore.gateway.state, .disconnected)
        XCTAssertFalse(hosts.activeStore.gateway.recoveryStateForTesting.wantsConnection)
        let reads = await credentials.tokenReads
        XCTAssertEqual(reads, 0)
    }

    func testIdentityCannotDowngradeOrChange() throws {
        try GatewayIdentity.validate(expected: nil, received: nil)
        try GatewayIdentity.validate(expected: gatewayID, received: gatewayID.uppercased())
        XCTAssertThrowsError(try GatewayIdentity.validate(expected: gatewayID, received: nil))
        XCTAssertThrowsError(try GatewayIdentity.validate(expected: gatewayID, received: "a56a1098-8519-43a1-9dce-fb99863bf5bb"))
        XCTAssertThrowsError(try GatewayIdentity.validate(expected: nil, received: "host-name"))
    }

    func testPairingDecodesNewFieldsAndRetainsLegacyCompatibility() throws {
        let payload = GatewayPairingPayload(version: 2, publicUrl: "wss://gateway.example/ws/mobile", pairingCode: "once", expiresAt: 4_102_444_800_000, gatewayId: gatewayID, gatewayName: "我的电脑", endpoints: ["ws://192.168.1.10:3081/ws/mobile"])
        let encoded = try JSONEncoder().encode(payload).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let result = try PairingPayloadParser.parse(encoded)
        XCTAssertEqual(result.gatewayId, gatewayID)
        XCTAssertEqual(try GatewayIdentity.endpoints(result).count, 2)
    }

    func testAddressesRejectCredentialLeakAndUnboundedCandidates() throws {
        for endpoint in ["ws://10.bad.0.0.1/ws/mobile", "ws://example.com/ws/mobile", "wss://user:pass@example.com/ws/mobile", "wss://example.com/ws/mobile?a=b", "wss://0.0.0.0/ws/mobile"] {
            XCTAssertThrowsError(try GatewayIdentity.endpoint(endpoint))
        }
        let payload = GatewayPairingPayload(version: 2, publicUrl: "wss://gateway.example/ws/mobile", pairingCode: "once", expiresAt: 4_102_444_800_000, endpoints: (0..<16).map { "wss://host\($0).example/ws/mobile" })
        XCTAssertThrowsError(try GatewayIdentity.endpoints(payload))
    }

    func testTailscaleIPv4RangeAllowsPlainWebSocketWithoutAllowingPublicNeighbors() throws {
        for host in ["100.64.0.0", "100.100.1.19", "100.127.255.255"] {
            let endpoint = "ws://\(host):3081/ws/mobile"
            XCTAssertEqual(try GatewayIdentity.endpoint(endpoint), endpoint)
        }
        for host in ["100.63.255.255", "100.128.0.0", "100.255.255.255", "100.64.256.1"] {
            XCTAssertThrowsError(try GatewayIdentity.endpoint("ws://\(host):3081/ws/mobile"))
        }
        for suffix in ["?token=secret", "#fragment"] {
            XCTAssertThrowsError(try GatewayIdentity.endpoint("ws://100.100.1.19:3081/ws/mobile\(suffix)"))
        }
        XCTAssertThrowsError(try GatewayIdentity.endpoint("ws://user:pass@100.100.1.19:3081/ws/mobile"))
    }

    func testPairingAcceptsTailscaleAsPrimaryOrCandidateEndpoint() throws {
        let lan = "ws://192.168.1.19:3081/ws/mobile"
        let tailscale = "ws://100.100.1.19:3081/ws/mobile"
        for (primary, candidate) in [(lan, tailscale), (tailscale, lan)] {
            let payload = GatewayPairingPayload(
                version: 2, publicUrl: primary, pairingCode: "once", expiresAt: 4_102_444_800_000,
                gatewayId: gatewayID, endpoints: [candidate]
            )
            let encoded = try JSONEncoder().encode(payload).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
            let parsed = try PairingPayloadParser.parse(encoded)
            XCTAssertEqual(try GatewayIdentity.endpoints(parsed), [primary, candidate])
        }
    }

    func testPreferencesIsolateSameResourceIDs() throws {
        let defaults = UserDefaults(suiteName: "multi-test-\(UUID())")!
        let first = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "A")
        let second = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "B")
        first.selectedWorkspaceID = "same-id"
        first.endpoint = "wss://a.example/ws/mobile"
        XCTAssertNil(second.selectedWorkspaceID)
        XCTAssertNotEqual(first.endpoint, second.endpoint)
        second.selectedWorkspaceID = "other"
        XCTAssertEqual(first.selectedWorkspaceID, "same-id")
    }

    @MainActor
    func testLegacyMigrationIsIdempotentAndPreservesWorkspace() async throws {
        let defaults = UserDefaults(suiteName: "multi-migrate-\(UUID())")!
        defaults.set("wss://legacy.example/ws/mobile", forKey: "gateway.endpoint")
        defaults.set("workspace-a", forKey: "gateway.selectedWorkspaceId")
        let first = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
        await first.waitUntilReady()
        let second = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
        await second.waitUntilReady()
        XCTAssertEqual(first.profiles.count, 1)
        XCTAssertEqual(first.profiles, second.profiles)
        XCTAssertEqual(first.activeStore.selectedWorkspaceId, "workspace-a")
        XCTAssertEqual(first.activeStore.gatewayLocalID, first.activeProfile?.id)
    }

    @MainActor
    func testSelectingDisconnectedCurrentGatewayKeepsBusinessContainerAndHomeState() async throws {
        let suite = "same-gateway-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let profile = GatewayProfile(gatewayName: "离线电脑", endpoints: ["wss://a.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        let hosts = MultiGatewayStore(defaults: defaults)
        await hosts.waitUntilReady()
        let current = hosts.activeStore
        current.selectedWorkspaceId = "workspace-kept"
        hosts.select(profile, connect: false)
        XCTAssertTrue(current === hosts.activeStore)
        hosts.select(profile)
        XCTAssertTrue(current === hosts.activeStore)
        XCTAssertEqual(current.selectedWorkspaceId, "workspace-kept")
        current.disconnect()
        current.deactivateGateway()
    }

    @MainActor
    func testSwitchReplacesBusinessContainerAndRejectsOldCallbacks() async throws {
        let defaults = UserDefaults(suiteName: "multi-switch-\(UUID())")!
        let first = GatewayProfile(gatewayName: "同名电脑", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "同名电脑", endpoints: ["wss://b.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([first, second]), forKey: "gateway.profiles.v1")
        let hosts = MultiGatewayStore(defaults: defaults)
        await hosts.waitUntilReady()
        let old = hosts.activeStore
        let delayed = old.gateway.onFrame
        old.selectedWorkspaceId = "same-id"
        hosts.select(second, connect: false)
        XCTAssertFalse(old === hosts.activeStore)
        XCTAssertNil(old.gateway.onFrame)
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
        delayed?(GatewayFrame(kind: "pong"))
        old.selectedWorkspaceId = "late-a"
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
        hosts.activeStore.selectedWorkspaceId = "workspace-b"
        hosts.select(first, connect: false)
        XCTAssertEqual(hosts.activeStore.selectedWorkspaceId, "late-a")
        XCTAssertNotEqual(hosts.activeStore.gatewayLocalID, second.id)
    }

    @MainActor
    func testRemovingCurrentHostClearsStateAndDoesNotRemigrateLegacy() async throws {
        let defaults = UserDefaults(suiteName: "multi-remove-\(UUID())")!
        defaults.set("wss://legacy.example/ws/mobile", forKey: "gateway.endpoint")
        let hosts = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
        await hosts.waitUntilReady()
        let profile = try XCTUnwrap(hosts.activeProfile)
        let old = hosts.activeStore
        old.selectedWorkspaceId = "old-workspace"
        hosts.remove(profile)
        XCTAssertTrue(hosts.profiles.isEmpty)
        XCTAssertNil(hosts.activeID)
        XCTAssertFalse(old === hosts.activeStore)
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
        XCTAssertNil(old.gateway.onFrame)
        let restored = MultiGatewayStore(defaults: defaults)
        await restored.waitUntilReady()
        XCTAssertTrue(restored.profiles.isEmpty)
        XCTAssertNil(restored.activeStore.selectedWorkspaceId)
        hosts.select(profile, connect: false)
        XCTAssertNil(hosts.activeID)
    }

    @MainActor
    func testBatchRemovingHostsAlsoClearsActiveContainer() async throws {
        let defaults = UserDefaults(suiteName: "multi-batch-remove-\(UUID())")!
        let first = GatewayProfile(gatewayName: "A", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "B", endpoints: ["wss://b.example/ws/mobile"])
        let retained = GatewayProfile(gatewayName: "C", endpoints: ["wss://c.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([first, second, retained]), forKey: "gateway.profiles.v1")
        defaults.set(first.id, forKey: "gateway.activeID")
        let hosts = MultiGatewayStore(defaults: defaults)
        await hosts.waitUntilReady()
        hosts.activeStore.selectedWorkspaceId = "workspace-a"

        hosts.remove(ids: Set([first.id, second.id]))

        XCTAssertEqual(hosts.profiles, [retained])
        XCTAssertNil(hosts.activeID)
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
    }

    func testPreferredEndpointCannotAddTrust() {
        let profile = GatewayProfile(gatewayName: "电脑", endpoints: ["wss://trusted.example/ws/mobile"], preferredEndpoint: "wss://evil.example/ws/mobile")
        XCTAssertEqual(profile.connectionEndpoints, profile.endpoints)
    }

    @MainActor
    func testSwitcherSheetRendersWithoutEnvironmentObject() async throws {
        let first = GatewayProfile(gatewayName: "电脑 A", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "电脑 B", endpoints: ["wss://b.example/ws/mobile"])
        for profiles in [[GatewayProfile](), [first, second]] {
            let suite = "switcher-sheet-\(UUID())"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            defaults.set(try JSONEncoder().encode(profiles), forKey: "gateway.profiles.v1")
            defaults.set(second.id, forKey: "gateway.activeID")
            let hosts = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
            await hosts.waitUntilReady()
            defer { hosts.stopProbes(); hosts.activeStore.deactivateGateway() }

            // 独立承载弹窗，不注入环境对象，覆盖呈现边界丢失依赖的崩溃。
            let controller = UIHostingController(rootView:
                GatewaySwitcherSheet(hosts: hosts).environment(\.scenePhase, .background)
            )
            let size = controller.sizeThatFits(in: CGSize(width: 390, height: 640))

            XCTAssertTrue(size.width.isFinite && size.height.isFinite)
            XCTAssertGreaterThan(size.width, 0)
            XCTAssertGreaterThan(size.height, 0)
        }
    }
}

/// P0 修复（2026-11-11）回归：bridge 会话采纳门——paired 强制采纳、hello/bridge-session 缺会话才采纳。
/// 覆盖 Chief 派单两条：旧会话在、重新 paired 必须换新；旧会话在、普通 hello 不得触发替换。
@MainActor
final class BridgeSessionAdoptionGateTests: XCTestCase {
    private func session(serverId: String = "gw-1") -> GatewayBridgeSession {
        GatewayBridgeSession(
            accessToken: "tok", refreshToken: "ref", deviceId: "dev", serverId: serverId,
            serverUrl: "https://gw.invalid", tlsPin: nil, expiresAt: nil
        )
    }

    func testPairedForcesAdoption_EvenWhenOldSessionStored() {
        XCTAssertTrue(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "paired", bridgeSession: session(serverId: "gw-new"), hasStoredSession: true
        ), "旧会话在也必须采纳 paired 的新会话（本次真机事故根因）")
    }

    func testPaired_WithoutSession_NeverAdopts() {
        XCTAssertFalse(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "paired", bridgeSession: nil, hasStoredSession: false
        ))
    }

    func testPlainHello_WithStoredSession_NeverRotates() {
        XCTAssertFalse(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "hello", bridgeSession: session(), hasStoredSession: true
        ), "普通 hello 不得轮换已存会话")
    }

    func testPlainHello_MissingSession_Adopts() {
        XCTAssertTrue(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "hello", bridgeSession: session(), hasStoredSession: false
        ))
    }

    func testBridgeSessionReissue_WithStoredSession_NeverRotates() {
        XCTAssertFalse(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "bridge-session", bridgeSession: session(), hasStoredSession: true
        ))
    }

    func testBridgeSessionReissue_MissingSession_Adopts() {
        XCTAssertTrue(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "bridge-session", bridgeSession: session(), hasStoredSession: false
        ))
    }

    func testUnknownKind_NeverAdopts() {
        XCTAssertFalse(MultiGatewayStore.shouldAdoptBridgeSession(
            frameKind: "history", bridgeSession: session(), hasStoredSession: false
        ))
    }

    // MARK: - 落位决策（planBridgeSessionAdoption）——2026-11-11 审查修正的回归

    func testPairedOnPendingStore_StagesInsteadOfWritingOldScopeSlot() {
        // 真实调用顺序：handleFrame 内 receive → acceptIdentity（对 paired 提前 return，不提升
        // 不切作用域）→ 采纳决策 ⇒ 此刻作用域还是旧主机。直接 adopt 会把新主机刚签发的会话
        // 写进**旧主机槽**（跨槽污染，switchScope 回旧主机时会restore出别家的会话）⇒ 必须只暂存。
        for stored in [true, false] {
            XCTAssertEqual(MultiGatewayStore.planBridgeSessionAdoption(
                frameKind: "paired", bridgeSession: session(serverId: "gw-new"), hasStoredSession: stored, storeIsPending: true
            ), .stageForPendingPromotion, line: UInt(stored ? 2 : 1))
        }
    }

    func testPairedOnActiveStore_AdoptsDirectly() {
        // 同主机重配对（store 已 active）：槽就是该主机的，直接落位正确；
        // 且实现里落位即清 pending —— 不给「残留到下次提升」留尾巴。
        XCTAssertEqual(MultiGatewayStore.planBridgeSessionAdoption(
            frameKind: "paired", bridgeSession: session(), hasStoredSession: true, storeIsPending: false
        ), .adoptDirectly)
    }

    func testPlainHello_PlansFollowExistingGate() {
        XCTAssertNil(MultiGatewayStore.planBridgeSessionAdoption(
            frameKind: "hello", bridgeSession: session(), hasStoredSession: true, storeIsPending: false
        ), "有会话的 hello 不采纳（既有闸门语义，落位决策不得放宽）")
        XCTAssertEqual(MultiGatewayStore.planBridgeSessionAdoption(
            frameKind: "hello", bridgeSession: session(), hasStoredSession: false, storeIsPending: false
        ), .adoptDirectly, "缺会话的 hello 兼容路径仍要接")
    }

    func testPairedWithoutSession_NeitherWritesSlotNorStages() {
        // 修前缺陷素材：promoting 主机的 paired 帧若**不带** session，旧 pending（上一台主机的）
        // 会被重放进新主机槽。修后：这种帧既不落槽也不暂存——pending 只由「本店带会话的 paired
        // 帧」产生，而 acceptIdentity 只对同一个 pending 店提升 ⇒ 暂存与提升必然同主机，
        // 「A 店暂存、C 店提升」的组合在决策层不存在。
        XCTAssertNil(MultiGatewayStore.planBridgeSessionAdoption(
            frameKind: "paired", bridgeSession: nil, hasStoredSession: false, storeIsPending: true
        ))
    }

    func testBridgeSessionResponseOnPromotedStore_AdoptsDirectly() {
        // 拉取式自愈的应答（bridge-session）到达时提升已完成（pairingStore 已清 nil）⇒
        // storeIsPending=false，直接落进新作用域，不会写回旧主机槽。
        XCTAssertEqual(MultiGatewayStore.planBridgeSessionAdoption(
            frameKind: "bridge-session", bridgeSession: session(), hasStoredSession: false, storeIsPending: false
        ), .adoptDirectly)
    }

    func testPairedSemantics_IndependentOfStoredState() {
        // paired 的采纳只取决于「帧里有没有会话」，与本地是否已有会话无关——两条回归合一条性质
        for stored in [true, false] {
            XCTAssertTrue(MultiGatewayStore.shouldAdoptBridgeSession(
                frameKind: "paired", bridgeSession: session(), hasStoredSession: stored
            ))
        }
    }
}

/// P0 修复（2026-11-11）回归：paired 重采纳的「替换并重建」端到端证据。
/// 上面的门测试只证明**决策**放行；这里用真 BridgeTeamAdapter + 真 Keychain 槽（模拟器沙盒）
/// 证明**执行**：旧会话已在槽时再采 paired——槽里必须换成新会话、transport 强制重建必须成功
/// （adoptSession 的重建守卫失败会转 .failed，回到 .paired 即守卫通过）、connectionIssue 清空
/// （「连接已断开」横幅常驻的另一半根因）。
/// 反向（reissue/hello 不得轮换）在门层已覆盖：适配器是被动执行者，轮换与否由门决定，
/// 适配器层再造一个「拒绝替换」是重复实现，不做。
@MainActor
final class BridgePairedReplaceRebuildTests: XCTestCase {
    private var scope = ""
    private var previousScopeID: String?

    override func setUpWithError() throws {
        scope = "test-paired-rebuild-" + UUID().uuidString
        BridgeKeychainSessionStore.forgetScope(scope)
        clearLegacy()
        previousScopeID = BridgeKeychainSessionStore.currentScopeID
    }

    override func tearDownWithError() throws {
        BridgeKeychainSessionStore(scopeID: scope).clear()
        BridgeKeychainSessionStore.forgetScope(scope)
        if let previous = previousScopeID { BridgeKeychainSessionStore.currentScopeID = previous }
        clearLegacy()
    }

    /// 与 BridgeKeychainSessionStoreTests 同款清场：legacy 全局槽没有公开删除口，按 service+account 原生删除。
    private func clearLegacy() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: BridgeKeychainSessionStore.service,
            kSecAttrAccount as String: BridgeKeychainSessionStore.legacyAccount
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func sessionJSON(serverId: String, token: String) -> String {
        """
        {"accessToken":"\(token)","refreshToken":"r-\(token)","deviceId":"dev","serverId":"\(serverId)","serverUrl":"https://\(serverId).invalid","tlsPin":null,"expiresAt":9999999999999}
        """
    }

    func testPairedWithOldSessionStored_ReplacesAndRebuilds() {
        let adapter = BridgeTeamAdapter()
        adapter.switchScope(profileID: scope, gatewayId: nil, remoteDeviceId: nil)
        // 旧会话（模拟「上次配对留下的旧 token/旧地址」）
        adapter.adoptSession(json: sessionJSON(serverId: "gateway-old", token: "tok-old"))
        XCTAssertTrue(adapter.hasStoredSession, "前置：旧会话已在槽")

        // paired 重签（新 token、新 serverId）——真机事故里这一步被旧门整帧丢弃
        adapter.adoptSession(json: sessionJSON(serverId: "gateway-new", token: "tok-new"))

        // ① 替换：槽里是新会话，旧 token 零残留
        let stored = BridgeKeychainSessionStore(scopeID: scope).loadJson() ?? ""
        XCTAssertTrue(stored.contains("tok-new"), "paired 新会话必须替换旧会话")
        XCTAssertFalse(stored.contains("tok-old"), "旧 token 不得残留")
        // ② 重建：adoptSession 强制 transport=nil 再重建；重建失败会转 .failed，
        //    回到 .paired 即「transport != nil」守卫通过
        XCTAssertEqual(adapter.pairingState, .paired)
        XCTAssertNil(adapter.connectionIssue, "重配对必须清连接问题")
    }
}

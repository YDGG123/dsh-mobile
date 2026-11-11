import XCTest
@testable import DeepSeekHarnessMobile

/// P0-AT-07 双主机隔离：Keychain 会话槽按作用域分键（SEC-01 收口的平台侧回归）。
/// 全部用真 Keychain（模拟器沙盒），每个用例用独立 UUID 作用域互不污染。
final class BridgeKeychainSessionStoreTests: XCTestCase {
    private var scopeA = ""
    private var scopeB = ""

    override func setUpWithError() throws {
        scopeA = "test-profile-A-" + UUID().uuidString
        scopeB = "test-profile-B-" + UUID().uuidString
        // 清本用例可能撞上的残留 + 旧全局槽，保证从零开始
        BridgeKeychainSessionStore.forgetScope(scopeA)
        BridgeKeychainSessionStore.forgetScope(scopeB)
        BridgeKeychainSessionStore.forgetScope("__legacy-cleanup__")
        clearLegacy()
    }

    override func tearDownWithError() throws {
        BridgeKeychainSessionStore(scopeID: scopeA).clear()
        BridgeKeychainSessionStore(scopeID: scopeB).clear()
        clearLegacy()
    }

    private func clearLegacy() {
        // 显式清场：legacy 槽没有公开删除口（迁移成功才自动删），测试里按 service+account 原生删除。
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: BridgeKeychainSessionStore.service,
            kSecAttrAccount as String: BridgeKeychainSessionStore.legacyAccount
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func sessionJSON(serverId: String, deviceId: String, token: String = "tok") -> String {
        """
        {"accessToken":"\(token)","refreshToken":"r-\(token)","deviceId":"\(deviceId)","serverId":"\(serverId)","serverUrl":"https://\(serverId).invalid","tlsPin":null,"expiresAt":9999999999999}
        """
    }

    // MARK: 作用域隔离

    func testScopeIsolation_AandBDoNotSeeEachOther() throws {
        let a = BridgeKeychainSessionStore(scopeID: scopeA)
        let b = BridgeKeychainSessionStore(scopeID: scopeB)
        let jsonA = sessionJSON(serverId: "gateway-A", deviceId: "dev-A", token: "token-A")
        let jsonB = sessionJSON(serverId: "gateway-B", deviceId: "dev-B", token: "token-B")

        let stA = BridgeKeychainSessionStore.writeJSON(account: "device-session@\(scopeA)", json: jsonA)
        XCTAssertEqual(stA, errSecSuccess, "SecItem 写 A 失败: \(stA)")
        let stB = BridgeKeychainSessionStore.writeJSON(account: "device-session@\(scopeB)", json: jsonB)
        XCTAssertEqual(stB, errSecSuccess, "SecItem 写 B 失败: \(stB)")
        a.saveJson(json: jsonA)
        b.saveJson(json: jsonB)

        XCTAssertEqual(a.loadJson(), jsonA, "A 槽必须只读到 A 的会话")
        XCTAssertEqual(b.loadJson(), jsonB, "B 槽必须只读到 B 的会话")
        XCTAssertTrue(a.exists())
        XCTAssertTrue(b.exists())
        XCTAssertEqual(try XCTUnwrap(BridgeKeychainSessionStore.parseIdentity(try XCTUnwrap(a.loadJson(), "A 槽读回 nil（写链诊断见上方 status 断言）"))).serverId, "gateway-A")
    }

    func testClearOnlyAffectsOwnScope() throws {
        let a = BridgeKeychainSessionStore(scopeID: scopeA)
        let b = BridgeKeychainSessionStore(scopeID: scopeB)
        a.saveJson(json: sessionJSON(serverId: "gateway-A", deviceId: "dev-A"))
        b.saveJson(json: sessionJSON(serverId: "gateway-B", deviceId: "dev-B"))

        a.clear()

        XCTAssertNil(a.loadJson(), "A 清除后 A 槽必须为空")
        XCTAssertFalse(a.exists())
        XCTAssertNotNil(b.loadJson(), "A 清除不得波及 B 的槽（P0-AT-07 核心）")
        XCTAssertTrue(b.exists())
    }

    // MARK: 旧全局槽迁移（规格 6.1：验证归属，不能猜）

    private func writeLegacy(serverId: String, deviceId: String) {
        let json = sessionJSON(serverId: serverId, deviceId: deviceId, token: "legacy-token")
        // 借迁移成功路径之外的写入口不存在（legacy 槽只读），测试里直接用原生 Keychain 写：
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: BridgeKeychainSessionStore.service,
            kSecAttrAccount as String: BridgeKeychainSessionStore.legacyAccount
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: Data(json.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add.merge(attributes) { _, new in new }
            XCTAssertEqual(SecItemAdd(add as CFDictionary, nil), errSecSuccess)
        }
    }

    private func legacyJSON() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: BridgeKeychainSessionStore.service,
            kSecAttrAccount as String: BridgeKeychainSessionStore.legacyAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func testMigration_MatchingGateway_AdoptsAndRemovesLegacy() throws {
        writeLegacy(serverId: "gateway-x", deviceId: "dev-x")

        let migrated = BridgeKeychainSessionStore.migrateLegacySession(
            scopeID: scopeA, expectedGatewayId: "gateway-x", expectedDeviceId: "dev-x"
        )

        XCTAssertTrue(migrated, "归属匹配必须迁移成功")
        let scoped = BridgeKeychainSessionStore(scopeID: scopeA)
        XCTAssertNotNil(scoped.loadJson(), "迁移后本作用域必须能读到会话")
        XCTAssertEqual(try XCTUnwrap(BridgeKeychainSessionStore.parseIdentity(try XCTUnwrap(scoped.loadJson(), "迁移后作用域读回 nil"))).serverId, "gateway-x")
        XCTAssertNil(legacyJSON(), "迁移成功后旧全局槽必须删除（SEC-01 收口）")
    }

    func testMigration_WrongGateway_KeepsLegacyForItsOwner() throws {
        writeLegacy(serverId: "gateway-B", deviceId: "dev-B")

        let migrated = BridgeKeychainSessionStore.migrateLegacySession(
            scopeID: scopeA, expectedGatewayId: "gateway-A", expectedDeviceId: "dev-A"
        )

        XCTAssertFalse(migrated, "归属不符不得认领（规格 6.1：不能猜归属）")
        XCTAssertNil(BridgeKeychainSessionStore(scopeID: scopeA).loadJson(), "A 作用域必须保持无会话（重新配对路径）")
        XCTAssertNotNil(legacyJSON(), "全局槽保留——它可能属于 B，等 B 的作用域来认领")

        // B 来认领必须成功：同一份遗留会话不被 A 的失败迁移破坏
        let migratedB = BridgeKeychainSessionStore.migrateLegacySession(
            scopeID: scopeB, expectedGatewayId: "gateway-B", expectedDeviceId: "dev-B"
        )
        XCTAssertTrue(migratedB, "正确主机的作用域必须能认领")
        XCTAssertEqual(try XCTUnwrap(BridgeKeychainSessionStore.parseIdentity(try XCTUnwrap(BridgeKeychainSessionStore(scopeID: scopeB).loadJson(), "B 认领后读回 nil"))).serverId, "gateway-B")
        XCTAssertNil(legacyJSON())
    }

    func testMigration_WithoutExpectedGateway_NeverGuesses() throws {
        writeLegacy(serverId: "gateway-x", deviceId: "dev-x")

        let migrated = BridgeKeychainSessionStore.migrateLegacySession(scopeID: scopeA, expectedGatewayId: nil, expectedDeviceId: nil)

        XCTAssertFalse(migrated, "无法验证归属时不得迁移（规格 6.1）")
        XCTAssertNotNil(legacyJSON(), "未验证的遗留会话原样保留")
        XCTAssertNil(BridgeKeychainSessionStore(scopeID: scopeA).loadJson())
    }

    func testMigration_DeviceIdMismatch_Rejects() throws {
        writeLegacy(serverId: "gateway-x", deviceId: "dev-other")

        let migrated = BridgeKeychainSessionStore.migrateLegacySession(
            scopeID: scopeA, expectedGatewayId: "gateway-x", expectedDeviceId: "dev-x"
        )

        XCTAssertFalse(migrated, "deviceId 与资料绑定不符 = 新设备，要求重新配对")
        XCTAssertNotNil(legacyJSON())
    }

    func testMigration_NoLegacy_NoSideEffects() throws {
        let migrated = BridgeKeychainSessionStore.migrateLegacySession(
            scopeID: scopeA, expectedGatewayId: "gateway-x", expectedDeviceId: "dev-x"
        )

        XCTAssertFalse(migrated)
        XCTAssertNil(BridgeKeychainSessionStore(scopeID: scopeA).loadJson())
        XCTAssertNil(legacyJSON())
    }

    // MARK: 身份解析

    func testParseIdentity_RejectsMissingOrEmptyServerId() {
        XCTAssertNil(BridgeKeychainSessionStore.parseIdentity("{\"deviceId\":\"d\"}"))
        XCTAssertNil(BridgeKeychainSessionStore.parseIdentity("{\"serverId\":\"\",\"deviceId\":\"d\"}"))
        XCTAssertNil(BridgeKeychainSessionStore.parseIdentity("not-json"))
        XCTAssertEqual(BridgeKeychainSessionStore.parseIdentity("{\"serverId\":\"s\"}")?.serverId, "s")
    }
}

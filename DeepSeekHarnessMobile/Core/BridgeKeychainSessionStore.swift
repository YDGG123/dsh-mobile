import DeepSeekHarnessShared
import Foundation
import Security

/// Bridge 会话的 Keychain 存储——**按主机作用域分槽**（P0-AT-07 双主机隔离 / SEC-01）。
///
/// 作用域 = 本地资料 ID（`GatewayProfile.id`，MultiGatewayStore 的「持久化、凭证和缓存的
/// 共同命名空间」），其上已绑定 gatewayId + remoteDeviceId。每个作用域一个 Keychain 槽
/// （account = `device-session@<scopeID>`），A/B 主机互不覆盖、互不残留。
///
/// 旧全局槽（account = `device-session`）只作一次性迁移源：迁移前必须验证
/// `session.serverId == 期望 gatewayId`（规格 6.1——「不能猜归属」）；验证不过**不迁移也不删除**
/// （可能属于另一台主机，等它自己的作用域来认领），本作用域按无会话处理（重新配对）。
/// 迁移成功 = 写入本作用域槽 + 删除全局槽（全局槽永不写回）。
final class BridgeKeychainSessionStore: NSObject, BridgeSessionStore {
    static let service = "com.ydgg123.orcarness.bridge.session"
    /// 旧全局槽：SEC-01 的串号源头，只作迁移源。
    static let legacyAccount = "device-session"

    /// 当前活动作用域（由 `BridgeTeamAdapter.switchScope` 维护）。
    /// 无参 init 的调用方（GatewayTeamFeed 等「读当前会话」的工具位）一律落到这里，
    /// 保证它们读到的永远是**当前主机**的槽——读侧也不能读错槽。
    private static let scopeLock = NSLock()
    private static var _currentScopeID = "unpaired"
    static var currentScopeID: String {
        get { scopeLock.lock(); defer { scopeLock.unlock() }; return _currentScopeID }
        set { scopeLock.lock(); defer { scopeLock.unlock() }; _currentScopeID = newValue }
    }

    /// 迁移判定所需的最小身份；解析失败 = 无法归属 = 不迁移。
    struct SessionIdentity: Equatable {
        let serverId: String
        let deviceId: String?
    }

    let scopeID: String
    private var account: String { "device-session@\(scopeID)" }
    private var baseQuery: [String: Any] { Self.query(account: account) }

    /// 指定作用域的存储实例。adapter 在主机切换时以新作用域重建本类。
    init(scopeID: String) {
        self.scopeID = scopeID
        super.init()
    }

    /// 兼容旧调用方：无参 = **当前活动作用域**（不再是无作用域的全局槽）。
    override convenience init() {
        self.init(scopeID: BridgeKeychainSessionStore.currentScopeID)
    }

    // MARK: BridgeSessionStore（KMP 协议；只做 JSON 存取，解析与校验留在 KMP）

    func loadJson() -> String? { Self.readJSON(account: account) }

    /// Keychain 里**有没有**这条记录（不管能否读出内容）。语义与旧实现一致：
    /// 区分「会话丢了」（允许自愈补发）与「会话在、读不出」（不得自动补发）。
    func exists() -> Bool { Self.itemExists(account: account) }

    func saveJson(json: String) { Self.writeJSON(account: account, json: json) }

    /// 只清**本作用域**的槽；其它主机的槽不受影响。
    func clear() { Self.deleteItem(account: account) }

    // MARK: 一次性迁移（旧全局槽 → 本作用域槽）

    /// 迁移旧全局槽到本作用域。expectedGatewayId 缺失 = 无法验证归属 = 不动全局槽。
    /// - 返回 true：归属验证通过，旧会话已归入本作用域（全局槽已删）。
    /// - 返回 false：无可迁移或归属不符；归属不符时全局槽**保留**给真正的主机。
    static func migrateLegacySession(scopeID: String, expectedGatewayId: String?, expectedDeviceId: String?) -> Bool {
        guard let json = readJSON(account: legacyAccount) else { return false }
        guard let expectedGatewayId, !expectedGatewayId.isEmpty,
              let identity = parseIdentity(json),
              identity.serverId.lowercased() == expectedGatewayId.lowercased() else {
            return false   // 无法验证归属：不猜（规格 6.1），本作用域按无会话走重新配对
        }
        if let expectedDeviceId, !expectedDeviceId.isEmpty, identity.deviceId != expectedDeviceId {
            return false
        }
        writeJSON(account: "device-session@\(scopeID)", json: json)
        deleteItem(account: legacyAccount)
        return true
    }

    /// 清除指定作用域的槽（移除主机/解配时，P0-AT-17 的作用域口径）。
    static func forgetScope(_ scopeID: String) {
        deleteItem(account: "device-session@\(scopeID)")
    }

    // MARK: Keychain 原语（按 account 参数化，作用域槽与迁移共用）

    private static func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    private static func readJSON(account: String) -> String? {
        var q = query(account: account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func itemExists(account: String) -> Bool {
        var q = query(account: account)
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        return SecItemCopyMatching(q as CFDictionary, nil) == errSecSuccess
    }

    /// 写入并返回最终 OSStatus（诊断/测试用：saveJson 里的静默失败在这条链上会变成「读回 nil」）。
    @discardableResult
    static func writeJSON(account: String, json: String) -> OSStatus {
        guard let data = json.data(using: .utf8) else { return errSecParam }
        var q = query(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemUpdate(q as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            q.merge(attributes) { _, new in new }
            return SecItemAdd(q as CFDictionary, nil)
        }
        return status
    }

    private static func deleteItem(account: String) {
        SecItemDelete(query(account: account) as CFDictionary)
    }

    /// 从会话 JSON 提取 serverId/deviceId；只认字段存在且非空，解析失败返回 nil。
    static func parseIdentity(_ json: String) -> SessionIdentity? {
        guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let serverId = object["serverId"] as? String, !serverId.isEmpty else { return nil }
        let deviceId = (object["deviceId"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return SessionIdentity(serverId: serverId, deviceId: deviceId)
    }
}

import DeepSeekHarnessShared
import Foundation
import Security
import UIKit

// MARK: - 镜像模型（解码 KMP SharedTeamStore 的 snapshot JSON）

struct BridgeTeamMirror: Decodable {
    let phase: String
    let revision: Int64?
    let serverId: String?
    let serverName: String?
    let readOnlyReason: String?
    let mainBotIds: [String]?
    let defaultModel: String?
    let capabilities: BridgeTeamCapabilitiesMirror?
    let bots: [BridgeTeamRow]
    let rooms: [BridgeTeamRow]
    /// U4：普通会话（kind == "session"）。
    let sessions: [BridgeTeamRow]?
    let models: [BridgeTeamModelRow]?
    let lastError: BridgeTeamErrorMirror?
    let pendingActions: [String: BridgeTeamPendingMirror]?
    let effortsByModel: [String: BridgeTeamEffortsMirror]?
    let devices: [BridgeTeamDeviceRow]?
    let currentDeviceId: String?
    let devicesLoading: Bool?
    let devicesLoadError: BridgeTeamErrorMirror?

    var isReady: Bool { phase == "READY" }
    var needsPairing: Bool { phase == "NEEDS_PAIRING" || phase == "IDLE" }
    var isLoading: Bool { phase == "LOADING" }
    var isOffline: Bool { phase == "OFFLINE" }

    func isPending(_ id: String) -> Bool { pendingActions?[id] != nil }
    func models(forRowId: String) -> [BridgeTeamModelRow]? { models }

    /// 能力门控（fail-closed）：actions 白名单里没有 ⇒ 对应写操作 UI 直接隐藏。
    func supportsAction(_ action: String) -> Bool {
        capabilities?.actionsWhitelist?.contains(action) == true
    }
}

struct BridgeTeamModelRow: Decodable, Hashable, Identifiable {
    let ref: String
    let name: String
    let providerName: String?

    var id: String { ref }
}

struct BridgeTeamEffortRow: Decodable, Hashable, Identifiable {
    let id: String
    let name: String
    let description: String?
}

struct BridgeTeamEffortsMirror: Decodable {
    let efforts: [BridgeTeamEffortRow]
    let `default`: String?
}

struct BridgeTeamPendingMirror: Decodable {
    let action: String
    let effectId: String
}

struct BridgeTeamDeviceRow: Decodable, Identifiable, Hashable {
    let id: String
    let name: String?
    let createdAt: Int64?
    let lastSeenAt: Int64?
    let revokedAt: Int64?
}

struct BridgeTeamCapabilitiesMirror: Decodable {
    let actionsWhitelist: [String]?
    let features: [String: Bool]?

    /// 群聊 Tab 仅在 bridge 声明 groups 后出现（fail-closed）。
    var groupsAllowed: Bool { features?["groups"] == true }

    /// U4「会话」分段：bridge 宣告 sessions 才出现（fail-closed）。
    var sessionsAllowed: Bool { features?["sessions"] == true }
}

struct BridgeAvatarMirror: Decodable, Hashable {
    let shape: String?
    let color: String?
    let image: String?
}

struct BridgeTeamRow: Decodable, Identifiable, Hashable {
    let id: String
    let kind: String
    let name: String
    let sessionId: String
    let role: String?
    let model: String?
    let effort: String?
    let pinned: Bool?
    let hidden: Bool?
    let waiting: Bool?
    let members: [String]?
    let admin: String?
    let mode: String?
    let avatar: BridgeAvatarMirror?
    /// U4：普通会话的最近活跃时间（ms）。
    let lastActiveAt: Int64?
    /// U4：工作区名（服务端只下发末段）。
    let workspace: String?
    /// U4：会话正在运行。
    let busy: Bool?

    var isRoom: Bool { kind == "room" }
    var isSession: Bool { kind == "session" }
}

struct BridgeTeamErrorMirror: Decodable {
    let source: String
    let code: String
    let knownWireCode: String?
    let knownClientCode: String?
    let message: String?

    /// fail-closed：未知 wire 码显示原文 + 通用文案（§2.9）。
    var displayText: String {
        if let message, !message.isEmpty { return message }
        if source == "wire", knownWireCode == nil { return "服务器错误（\(code)）" }
        return "操作失败（\(code)）"
    }
}

struct BridgeTeamEffectMirror: Decodable {
    let kind: String
    let sessionId: String?
    let reason: String?
    let effectId: String?
    let method: String?
    let path: String?
    let bodyJson: String?
    let requiresAuth: Bool?
}

private final class BridgeMviObserver: NSObject, SharedMviEventObserver {
    private let handler: (SharedMviEvent) -> Void

    init(handler: @escaping (SharedMviEvent) -> Void) {
        self.handler = handler
    }

    func onEvent(event: SharedMviEvent) {
        handler(event)
    }
}

// MARK: - Keychain 会话存储（实现 KMP BridgeSessionStore）

/// 只做 JSON 字符串存取；解析与校验全部留在 KMP（fail-closed）。
final class BridgeKeychainSessionStore: NSObject, BridgeSessionStore {
    private let service = "com.clarklevis.dsh.bridge.session"
    private let account = "device-session"

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    func loadJson() -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveJson(json: String) {
        guard let data = json.data(using: .utf8) else { return }
        var query = baseQuery
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            query.merge(attributes) { _, new in new }
            SecItemAdd(query as CFDictionary, nil)
        }
    }

    func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}

// MARK: - 团队页适配器：KMP 唯一状态源 + Swift 镜像与 effect

/// 连接问题的可读分类：地址失效 / 网络不通 / 服务端异常 / 其他。
enum BridgeConnectionIssue: Equatable {
    case addressInvalid
    case network
    case server
    case unknown

    var headline: String {
        switch self {
        case .addressInvalid: return "服务器入口已失效"
        case .network: return "网络不通"
        case .server: return "服务器异常"
        case .unknown: return "连接已断开"
        }
    }

    var detail: String {
        switch self {
        case .addressInvalid: return "隧道域名可能已更换，请更换服务器地址后重新连接。"
        case .network: return "正在自动重连，请检查手机网络或隧道是否在线。"
        case .server: return "服务器暂时不可用，正在自动重试。"
        case .unknown: return "正在自动重连。"
        }
    }
}

/// 配对流程状态（§2.8）。
enum BridgePairingState: Equatable {
    case idle
    case connecting
    case awaitingApproval(requestId: String)
    case paired
    case failed(String)
}

/// P1 团队页控制器（§3.5）：
/// - 配对：KMP 解析配对资料 → /info 校验 → /pairings 申请 → 1.5s 轮询 /pairings/complete；
/// - 拉取 /info /state /conversations 喂回 SharedTeamStore（8s 前台轮询，scenePhase 门控）；
/// - 订阅 SharedMviEvent，只做镜像发布与 effect 执行（open-conversation → 现有 gateway 会话链路）；
/// - HTTP 执行在 BridgeTransport（双模式 TLS），KMP 不碰 I/O。
@MainActor
final class BridgeTeamAdapter: ObservableObject {
    @Published private(set) var mirror: BridgeTeamMirror?
    @Published private(set) var isRefreshing = false
    @Published private(set) var lastTransportError: String?
    @Published private(set) var pairingState: BridgePairingState = .idle
    /// 最近一次失败的可读分类（离线态 UI 用来给出「换地址」还是「查网络」）。
    @Published private(set) var connectionIssue: BridgeConnectionIssue?

    /// effect：进入聊天（sessionId 来自配对端 /state 的 bot/room 行）。
    var onOpenConversation: ((String) -> Void)?

    private let sessionStore = BridgeKeychainSessionStore()
    /// U5：配对成功且服务端下发了网关凭证时回调（由 MultiGatewayStore 接管落盘/建 profile）。
    /// 参数：凭证、bridge 的 https 根 origin。缺失时不会调用。
    var onGatewayCredential: ((BridgeGatewayCredential, String) -> Void)?
    private let store: SharedTeamStore
    private var observer: BridgeMviObserver?
    private var subscription: SharedMviSubscription?
    private var transport: BridgeTransport?
    private var pollingTask: Task<Void, Never>?
    private var pairingTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var started = false
    private var failureAttempt = 0
    private var lastSequence: Int64 = -1

    init() {
        let sessionStore = self.sessionStore
        self.store = SharedTeamStore(sessionStore: sessionStore)
        let observer = BridgeMviObserver { [weak self] event in
            Task { @MainActor [weak self] in
                self?.handle(event: event)
            }
        }
        self.observer = observer
        self.subscription = store.subscribe(observer: observer)
    }

    deinit {
        subscription?.cancel()
        pollingTask?.cancel()
        pairingTask?.cancel()
        watchdogTask?.cancel()
    }

    /// 启动时恢复密钥串里的会话；成功后从会话重建 transport 并开始轮询。
    /// U 单根因修复：transport 此前只存在于配对流程的内存里，App 重建即丢，
    /// 导致 refreshNow 静默空转、团队页永久「正在同步团队」。
    func bootstrap() {
        ensureStarted()
    }

    /// App 启动时调用（幂等）：恢复会话 + 从 Keychain 重建 transport + 开始常驻轮询。
    /// 轮询不再依赖团队页生命周期——这是「离线后不会自动回连」的根因修复。
    func ensureStarted() {
        guard !started else { return }
        started = true
        restoreTransportFromStoredSession()
        let outcome = store.restoreSession()
        if outcome.isSuccess, store.currentAccessToken() != nil {
            startPolling()
            armWatchdog()
        } else {
            pairingState = .idle
        }
    }

    /// 从 Keychain 会话（serverUrl + tlsPin）重建传输层；配对流程之外唯一的 transport 来源。
    private func restoreTransportFromStoredSession() {
        guard transport == nil,
              let raw = sessionStore.loadJson(),
              let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let serverUrl = object["serverUrl"] as? String, !serverUrl.isEmpty else { return }
        let tlsPin = object["tlsPin"] as? String
        guard let config = BridgeTransportConfig(
            originString: serverUrl,
            tlsPin: tlsPin,
            allowInsecureLoopback: false
        ) else {
            lastTransportError = "已保存的服务器地址无效，请重新配对"
            return
        }
        transport = BridgeTransport(config: config)
    }

    /// LOADING 看门狗：45s 内没有进入 READY 则转 OFFLINE（可重试），杜绝静默卡死。
    private func armWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 45_000_000_000)
            guard !Task.isCancelled, let self else { return }
            guard self.store.currentAccessToken() != nil else { return }
            let phase = self.mirror?.phase
            if phase == nil || phase == "LOADING" {
                self.store.refreshFailed(code: "timeout", message: "同步超时，请检查网络后重试")
            }
        }
    }

    private func disarmWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = nil
    }

    // MARK: - bridge 聊天（一次配对 = 聊天 + 团队）

    /// 为某个 bot/群创建聊天引擎；transport 缺失时先从 Keychain 会话重建。
    func chatEngine(conversationId: String, sessionId: String) -> BridgeChatEngine? {
        restoreTransportFromStoredSession()
        guard let transport, store.currentAccessToken() != nil else { return nil }
        return BridgeChatEngine(
            transport: transport,
            conversationId: conversationId,
            sessionId: sessionId,
            tokenProvider: { [weak self] in self?.store.currentAccessToken() },
            refreshToken: { [weak self] in
                guard let self else { return false }
                return await self.refreshAccessTokenOnce()
            },
            // 409 TARGET_CHANGED 纠偏：拉一次全量团队，再用最新 sessionId 重发一次。
            refreshTeam: { [weak self] in
                guard let self else { return }
                await self.refreshNow()
            },
            resolveSessionId: { [weak self] id in
                guard let mirror = self?.mirror else { return nil }
                return (mirror.bots.first { $0.id == id } ?? mirror.rooms.first { $0.id == id })?.sessionId
            }
        )
    }

    /// 401 时的一次 token 刷新；单飞与永久失效判定都在 KMP。
    func refreshAccessTokenOnce() async -> Bool {
        restoreTransportFromStoredSession()
        guard let transport else { return false }
        guard store.beginTokenRefresh() else { return false }
        guard let raw = sessionStore.loadJson(),
              let data = raw.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let refreshToken = payload["refreshToken"] as? String else {
            store.tokenRefreshRejected(code: "unauthorized")
            return false
        }
        do {
            let body = try await transport.refreshSession(refreshToken: refreshToken)
            guard var next = try? JSONSerialization.jsonObject(
                with: body.data(using: .utf8) ?? Data()
            ) as? [String: Any] else {
                store.tokenRefreshRejected(code: "invalid_response")
                return false
            }
            next["serverUrl"] = payload["serverUrl"]
            next["tlsPin"] = payload["tlsPin"]
            if let merged = try? JSONSerialization.data(withJSONObject: next),
               let text = String(data: merged, encoding: .utf8) {
                store.tokenRefreshSucceeded(sessionJson: text)
                return true
            }
            store.tokenRefreshRejected(code: "invalid_response")
            return false
        } catch let status as BridgeResponseStatus {
            let envelope = BridgeErrorsKt.decodeBridgeErrorEnvelope(
                status: Int32(status.status),
                body: status.body
            )
            store.tokenRefreshRejected(code: envelope.code)
            return false
        } catch {
            store.tokenRefreshRejected(code: "network_error")
            return false
        }
    }

    // MARK: - 配对（§2.8：KMP 解析校验 → /info 校验 → /pairings → 轮询 /pairings/complete）

    /// 粘贴桌面端「复制配对资料」的 JSON（QR 同形）：解析与校验走 KMP。
    ///
    /// **B3 起已无 UI 入口**（粘贴 JSON 那条配对路径退役）：bridge 凭证改由网关配对成功后的
    /// `paired` 帧 `bridgeSession` 字段下发，用户只需要走网关的扫码 / 手输地址 + 配对码。
    /// 这里保留实现是给「网关降级、只能手工塞一份 bridge 会话」的应急排查用，
    /// 不要在界面上重新接回来——那会让用户面对两条看起来都能配对的路径。
    func pairPastedPayload(_ raw: String) {
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1000)
        let parsed = BridgePairingKt.parseBridgePairingPayload(raw: raw, nowMillis: nowMillis)
        guard parsed.isValid, let url = parsed.url, let code = parsed.code else {
            pairingState = .failed(Self.parseFailureMessage(for: parsed.status))
            return
        }
        pair(
            url: url,
            code: code,
            expectedServerId: parsed.serverId,
            tlsPin: parsed.tlsPin
        )
    }

    /// 手动填写：服务器地址 + 配对码。
    func pair(url: String, code: String, expectedServerId: String? = nil, tlsPin: String? = nil) {
        pairingTask?.cancel()
        pairingState = .connecting
        let deviceName = Self.deviceName()
        pairingTask = Task { @MainActor [weak self] in
            await self?.runPairing(
                url: url,
                code: code,
                expectedServerId: expectedServerId,
                tlsPin: tlsPin,
                deviceName: deviceName
            )
        }
    }

    func cancelPairing() {
        pairingTask?.cancel()
        pairingTask = nil
        pairingState = .idle
    }

    func failPairingWithInvalidQr() {
        pairingState = .failed("二维码不是有效的配对资料，请用桌面端「手机访问 → App」生成的码")
    }

    func setPairingFailure(_ message: String) {
        pairingState = .failed(message)
    }

    private func runPairing(
        url: String,
        code: String,
        expectedServerId: String?,
        tlsPin: String?,
        deviceName: String
    ) async {
        guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            pairingState = .failed("请填写配对码")
            return
        }
        guard let config = BridgeTransportConfig(
            originString: url,
            tlsPin: tlsPin,
            allowInsecureLoopback: false
        ) else {
            pairingState = .failed("服务器地址无效：需要 https:// 开头的根地址（不要带路径）")
            return
        }
        let transport = BridgeTransport(config: config)
        do {
            // 1. /info：校验协议版本与服务器身份（KMP 解码）。
            let infoBody = try await transport.fetchInfo()
            let info = try BridgeDtosKt.parseBridgeInfo(raw: infoBody)
            guard info.protocolVersion == 1 else {
                pairingState = .failed("桌面端 bridge 协议版本不兼容（需要 protocolVersion 1），请升级桌面插件")
                return
            }
            if let expectedServerId, expectedServerId != info.serverId {
                pairingState = .failed("配对码与服务器身份不匹配，请重新生成配对码")
                return
            }

            // 2. /pairings：申请配对（KMP 解码 claim）。
            let beginBody = try await transport.post(
                path: "/pairings",
                token: nil,
                jsonBody: ["code": code, "deviceName": deviceName]
            )
            let claim = try BridgeDtosKt.parseBridgePairing(raw: beginBody)
            pairingState = .awaitingApproval(requestId: claim.requestId)

            // 3. 轮询 complete：claim 300s，每 1.5s 一次；三态判定在 KMP。
            let deadline = Date().addingTimeInterval(300)
            while Date() < deadline {
                try Task.checkCancellation()
                try await Task.sleep(nanoseconds: 1_500_000_000)
                do {
                    let completeBody = try await transport.post(
                        path: "/pairings/complete",
                        token: nil,
                        jsonBody: ["claimToken": claim.claimToken]
                    )
                    let complete = BridgePairingKt.classifyBridgePairingComplete(
                        raw: completeBody,
                        origin: config.origin.absoluteString,
                        tlsPin: config.tlsPin
                    )
                    if complete.isApproved, let sessionJson = complete.sessionJson {
                        self.transport = transport
                        store.sessionPaired(sessionJson: sessionJson)
                        // U5：一次配对顺带下发的网关凭证——交给上层落 Keychain + 建 profile。
                        if let session = try? BridgeDtosKt.parseBridgeSession(raw: sessionJson),
                           let gateway = session.gateway {
                            onGatewayCredential?(gateway, config.origin.absoluteString)
                        }
                        pairingState = .paired
                        failureAttempt = 0
                        connectionIssue = nil
                        lastTransportError = nil
                        startPolling()
                        return
                    }
                    if !complete.isPending {
                        pairingState = .failed("桌面端返回的配对结果无法解析，请重试")
                        return
                    }
                    // pending：继续等桌面批准。
                } catch let status as BridgeResponseStatus {
                    pairingState = .failed(Self.pairingMessage(for: status))
                    return
                }
            }
            pairingState = .failed("等待桌面批准超时（5 分钟），请重新生成配对码")
        } catch is CancellationError {
            pairingState = .idle
        } catch let status as BridgeResponseStatus {
            pairingState = .failed(Self.pairingMessage(for: status))
        } catch {
            pairingState = .failed(
                "无法连接服务器：\(error.localizedDescription)（检查地址与网络）"
            )
        }
    }

    static func parseFailureMessage(for status: String) -> String {
        switch status {
        case "invalid_json": return "配对资料格式不对，请整段复制桌面端的配对 JSON"
        case "insecure_url": return "配对资料里的服务器地址无效（需要 https:// 根地址）"
        case "invalid_code": return "配对资料里没有有效的配对码"
        case "expired": return "配对码已过期（生成后 2 分钟内有效），请在桌面端重新生成"
        case "invalid_pin": return "配对资料里的设备指纹格式无效"
        default: return "配对资料无法解析，请重新从桌面端复制"
        }
    }

    /// 传输失败分类（供离线 UI 与用例使用）。
    nonisolated static func classifyTransportFailure(_ error: Error) -> BridgeConnectionIssue {
        if let status = error as? BridgeResponseStatus {
            return status.status >= 500 ? .server : .unknown
        }
        guard let urlError = error as? URLError else { return .unknown }
        switch urlError.code {
        case .cannotFindHost, .dnsLookupFailed, .cannotConnectToHost:
            return .addressInvalid
        case .notConnectedToInternet, .networkConnectionLost, .timedOut, .dataNotAllowed:
            return .network
        default:
            return .unknown
        }
    }

    private static func pairingMessage(for status: BridgeResponseStatus) -> String {
        let envelope = BridgeErrorsKt.decodeBridgeErrorEnvelope(
            status: Int32(status.status),
            body: status.body
        )
        switch envelope.knownWireCode {
        case "INVALID_PAIRING_CODE": return "配对码无效或已过期（桌面端生成后 2 分钟内有效）"
        case "PAIRING_DENIED": return "桌面端拒绝了这次配对"
        case "PAIRING_NOT_FOUND": return "配对请求已过期，请重新生成配对码"
        case "PAIRING_LIMIT": return "待批的配对请求过多，稍后再试"
        case "DEVICE_LIMIT": return "设备数已达上限，先在桌面端撤销不用的设备"
        case "INVALID_DEVICE_NAME": return "设备名称不合法，请改名后重试"
        default:
            // fail-closed：未知 wire 码显示原文。
            return envelope.message ?? "配对失败（\(envelope.code)）"
        }
    }

    private static func deviceName() -> String {
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = name.isEmpty ? "iPhone" : name
        return String(fallback.prefix(100))
    }

    /// U5 对称配对：网关自己的配对流程成功后，插件会把 mobile-v1 session 放在 paired 帧里。
    /// 这里直接采纳（等同一次 bridge 配对），随后轮询拉团队。
    ///
    /// 反向签发的 session **没有 serverUrl**（只有 accessToken/refreshToken/deviceId/serverId），
    /// 而 transport 只能从「已保存 session 的 serverUrl」重建——不补这一步就会出现
    /// 「token 有了、团队永远是空」。所以这里把当前连接端点的 https origin 注入后再落库。
    /// 从网关端点（wss://host/ws/mobile）折出 bridge 需要的 https origin。
    /// 注意：origin 必须**不带路径**——BridgeOriginValidation 只接受空路径或 /mobile/v1。
    static func bridgeOrigin(fromEndpoint endpoint: String?) -> String? {
        guard var raw = endpoint?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        if raw.hasPrefix("wss://") { raw = "https://" + raw.dropFirst("wss://".count) }
        else if raw.hasPrefix("ws://") { raw = "http://" + raw.dropFirst("ws://".count) }
        guard var components = URLComponents(string: raw) else { return nil }
        components.path = ""
        components.query = nil
        components.fragment = nil
        components.user = nil
        components.password = nil
        guard let candidate = components.string,
              let origin = BridgeOriginValidation.origin(from: candidate) else { return nil }
        return origin.absoluteString
    }

    func adoptSession(json: String, endpoint: String? = nil) {
        var payload = json
        if let origin = Self.bridgeOrigin(fromEndpoint: endpoint),
           var object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] {
            if (object["serverUrl"] as? String)?.isEmpty != false { object["serverUrl"] = origin }
            if let data = try? JSONSerialization.data(withJSONObject: object),
               let merged = String(data: data, encoding: .utf8) {
                payload = merged
            }
        }
        // 落库前先确认有可用的 serverUrl：否则存下去也建不了连接，还会顶掉现有可用会话。
        guard let serverUrl = Self.sessionServerUrl(payload),
              BridgeOriginValidation.origin(from: serverUrl) != nil else {
            let message = "配对资料缺少可用的服务器地址（需要 https 根地址），请重新配对"
            pairingState = .failed(message)
            lastTransportError = message
            return
        }
        let outcome = store.sessionPaired(sessionJson: payload)
        guard outcome.isSuccess else {
            let message = "配对资料保存失败：" + (outcome.errorMessage ?? outcome.errorCode ?? "未知原因")
            pairingState = .failed(message)
            lastTransportError = message
            return
        }
        // 关键：adopt 时**强制重建**。旧 transport 可能指向失效 origin（或来自上一条会话），
        // 而 restoreTransportFromStoredSession() 有 guard transport == nil —— 不复位就等于沿用一个死连接。
        transport = nil
        restoreTransportFromStoredSession()
        guard transport != nil else {
            let message = "已保存会话但无法建立连接（serverUrl=" + serverUrl + "），请重新配对"
            pairingState = .failed(message)
            lastTransportError = message
            return
        }
        pairingState = .paired
        lastTransportError = nil
        failureAttempt = 0
        connectionIssue = nil
        startPolling()
        armWatchdog()
    }

    /// 从待落库的会话 JSON 里取 serverUrl（用于落库前校验）。
    static func sessionServerUrl(_ json: String) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let value = object["serverUrl"] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 诊断摘要：Bot tab 空态直接显示，省掉「猜为什么没数据」。
    /// 只暴露非敏感信息（不含 token）。
    struct Diagnostics {
        let phase: String
        let pairedState: String
        let hasStoredSession: Bool
        let serverUrl: String?
        let error: String?
    }

    func diagnostics() -> Diagnostics {
        let raw = sessionStore.loadJson()
        return Diagnostics(
            phase: mirror?.phase ?? "nil",
            pairedState: {
                switch pairingState {
                case .idle: return "idle"
                case .connecting: return "connecting"
                case .awaitingApproval: return "awaitingApproval"
                case .paired: return "paired"
                case .failed(let message): return "failed((message))"
                }
            }(),
            hasStoredSession: raw != nil,
            serverUrl: raw.flatMap { Self.sessionServerUrl($0) },
            error: lastTransportError ?? connectionIssue?.detail
        )
    }

    func restoreSession() {
        store.restoreSession()
    }

    func sessionPaired(_ sessionJson: String) {
        store.sessionPaired(sessionJson: sessionJson)
        startPolling()
        armWatchdog()
    }

    func open(botId: String) {
        store.openTeamConversation(conversationId: botId)
    }

    // MARK: - P2 写操作（全部走 KMP 门控 + effect，平台只执行）

    func setPinned(_ botId: String, _ pinned: Bool) {
        store.setFlags(id: botId, pinned: KotlinBoolean(bool: pinned), hidden: nil)
    }

    func setHidden(_ botId: String, _ hidden: Bool) {
        store.setFlags(id: botId, pinned: nil, hidden: KotlinBoolean(bool: hidden))
    }

    /// `model == nil + includeModel` 表示「使用默认模型」。
    func updateBot(
        _ botId: String,
        includeModel: Bool,
        model: String?,
        includeEffort: Bool,
        effort: String?
    ) {
        store.updateBot(
            id: botId,
            includeModel: includeModel,
            model: model,
            includeEffort: includeEffort,
            effort: effort
        )
    }

    func setMain(_ botId: String) {
        store.setMain(id: botId)
    }

    func loadModels() {
        store.loadModels()
    }

    func loadEfforts(ref: String) {
        store.loadEfforts(ref: ref)
    }

    func loadDevices() {
        store.loadDevices()
    }

    func revokeDevice(_ deviceId: String) {
        store.revokeDevice(id: deviceId)
    }

    func startPolling() {
        guard pollingTask == nil else { return }
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshNow()
                guard let self else { return }
                // 在线：8s 轮询；离线：指数退避 750ms→15s 自动回连（U3）。
                let delay: TimeInterval = self.mirror?.isOffline == true
                    ? BridgeBackoff.delay(attempt: self.failureAttempt)
                    : 8
                try? await Task.sleep(nanoseconds: UInt64(max(delay, 1) * 1_000_000_000))
            }
        }
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    /// 一次全量刷新：三响应齐才提交（revision 单调由 KMP 保证）。
    func refreshNow() async {
        guard let transport else { return }
        guard let token = store.currentAccessToken() else { return }
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        do {
            async let info = transport.fetchInfo()
            async let state = transport.fetchState(token: token)
            async let conversations = transport.fetchConversations(token: token)
            let (infoBody, stateBody, conversationsBody) = try await (info, state, conversations)
            lastTransportError = nil
            failureAttempt = 0
            connectionIssue = nil
            store.refreshTeam(
                infoJson: infoBody,
                stateJson: stateBody,
                conversationsJson: conversationsBody
            )
        } catch {
            await handleRefreshFailure(error, transport: transport)
        }
    }

    private func handleRefreshFailure(_ error: Error, transport: BridgeTransport) async {
        if let status = error as? BridgeResponseStatus {
            if status.status == 401 {
                await refreshToken(transport: transport)
                return
            }
            let envelope = BridgeErrorsKt.decodeBridgeErrorEnvelope(
                status: Int32(status.status),
                body: status.body
            )
            lastTransportError = envelope.message ?? error.localizedDescription
            store.refreshFailed(code: envelope.code, message: envelope.message)
            return
        }
        let code: String
        if let urlError = error as? URLError, urlError.code == .timedOut {
            code = "timeout"
        } else {
            code = "network_error"
        }
        failureAttempt += 1
        connectionIssue = Self.classifyTransportFailure(error)
        let message: String
        switch connectionIssue {
        case .addressInvalid:
            message = "服务器入口不可达：隧道域名可能已更换，请更换服务器地址"
        case .network:
            message = "网络不通，正在自动重连"
        case .server:
            message = "服务器异常，正在自动重试"
        case .unknown, .none:
            message = error.localizedDescription
        }
        lastTransportError = message
        store.refreshFailed(code: code, message: message)
    }

    /// 401 单飞刷新：成功喂回 KMP（保留 serverUrl/tlsPin 绑定），失败按 SDK 语义分永久/临时。
    private func refreshToken(transport: BridgeTransport) async {
        guard store.beginTokenRefresh() else { return }
        guard let raw = sessionStore.loadJson(),
              let data = raw.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let refreshToken = payload["refreshToken"] as? String else {
            store.tokenRefreshRejected(code: "unauthorized")
            return
        }
        do {
            let body = try await transport.refreshSession(refreshToken: refreshToken)
            guard var next = try? JSONSerialization.jsonObject(with: body.data(using: .utf8) ?? Data()) as? [String: Any] else {
                store.tokenRefreshRejected(code: "invalid_response")
                return
            }
            // 与 SDK index.js:242 一致：刷新响应不含 serverUrl/tlsPin，需由客户端合并。
            next["serverUrl"] = payload["serverUrl"]
            next["tlsPin"] = payload["tlsPin"]
            if let merged = try? JSONSerialization.data(withJSONObject: next),
               let mergedText = String(data: merged, encoding: .utf8) {
                store.tokenRefreshSucceeded(sessionJson: mergedText)
            } else {
                store.tokenRefreshRejected(code: "invalid_response")
            }
        } catch let status as BridgeResponseStatus {
            let envelope = BridgeErrorsKt.decodeBridgeErrorEnvelope(
                status: Int32(status.status),
                body: status.body
            )
            store.tokenRefreshRejected(code: envelope.code)
        } catch {
            store.tokenRefreshRejected(
                code: (error as? URLError)?.code == .timedOut ? "timeout" : "network_error"
            )
        }
    }

    private func handle(event: SharedMviEvent) {
        // 只接受递进的 sequence；乱序/重复事件丢弃（KMP 已保证有序，这里防御平台边界）。
        guard event.sequence > lastSequence else { return }
        lastSequence = event.sequence
        if let payload = event.statePayloadJson, let data = payload.data(using: .utf8) {
            if let decoded = try? JSONDecoder().decode(BridgeTeamMirror.self, from: data) {
                mirror = decoded
                if decoded.phase != "LOADING" { disarmWatchdog() }
            }
        }
        guard !event.effectsJson.isEmpty, event.effectsJson != "[]",
              let data = event.effectsJson.data(using: .utf8),
              let effects = try? JSONDecoder().decode([BridgeTeamEffectMirror].self, from: data) else {
            return
        }
        for effect in effects {
            switch effect.kind {
            case "open-conversation":
                if let sessionId = effect.sessionId { onOpenConversation?(sessionId) }
            case "session-clear":
                // KMP 已清 Keychain；轮询下一拍会因无 token 自然停摆。
                stopPolling()
            case "bridge-http":
                Task { [weak self] in await self?.execute(effect: effect) }
            case "refresh-team":
                // TARGET_CHANGED / READ_ONLY 的自动纠偏：立即全量刷新。
                Task { [weak self] in await self?.refreshNow() }
            default:
                break
            }
        }
    }

    /// 执行 KMP 描述的 bridge-http effect 并把回执喂回（成功/失败都由 KMP 决策）。
    private func execute(effect: BridgeTeamEffectMirror) async {
        guard let transport,
              let method = effect.method,
              let path = effect.path,
              let effectId = effect.effectId else { return }
        guard let token = store.currentAccessToken() else { return }
        do {
            let body = try await transport.send(
                method: method,
                path: path,
                bodyJson: effect.bodyJson,
                token: token
            )
            store.acceptBridgeResponse(effectId: effectId, status: 200, bodyJson: body)
        } catch let status as BridgeResponseStatus {
            store.acceptBridgeResponse(
                effectId: effectId,
                status: Int32(status.status),
                bodyJson: status.body
            )
        } catch {
            let code = (error as? URLError)?.code == .timedOut ? "timeout" : "network_error"
            store.acceptBridgeTransportFailure(effectId: effectId, code: code)
        }
    }
}

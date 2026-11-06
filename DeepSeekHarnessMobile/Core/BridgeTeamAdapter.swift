import DeepSeekHarnessShared
import Foundation
import os
import Security
import UIKit

// MARK: - 镜像模型（解码 KMP SharedBridgeTeamStore 的 snapshot JSON）

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

    /// 能力门控（fail-closed）：actions 白名单里没有 ⇒ 对应写操作 UI 直接隐藏。    /// 能力门控（fail-closed）：actions 白名单里没有 ⇒ 对应写操作 UI 直接隐藏。
    func supportsAction(_ action: String) -> Bool {
        capabilities?.actionsWhitelist?.contains(action) == true
    }

    /// **Bot 域会话判定**（纯函数，可单测）：`sessionId` 命中 **bots 或 rooms 任一行** ⇒ Bot 域。
    ///
    /// 依据 = 桌面端同语义（ds-bot `src/client/index.js:916`：Bot 会话输入框
    /// `conversation.input.model = Nothing`，"a Bot's model lives in its details panel"）。
    /// 2026-11-04 真实事故：手机端群聊漏了这条，用户在群聊切模型 ⇒ 每轮
    /// `MODEL_SWITCH_COMPACTION_FAILED`、用户消息被丢。
    ///
    /// 两条边界都在 `modelControlsHidden` 里按三态处理（2026-11-06 用户裁定改版）：
    /// - READY/OFFLINE：按命中判定（名册在手或曾是）；
    /// - IDLE/NEEDS_PAIRING / `mirror == nil`（bridge 未启动）：不可能有 Bot 域会话 ⇒ 显示；
    /// - LOADING：短暂 fail-closed（名册在路上，此刻可能是 Bot 域）。
    func isBotDomainSession(_ sessionId: String?) -> Bool {
        guard let sessionId, !sessionId.isEmpty else { return false }
        return bots.contains { $0.sessionId == sessionId } || rooms.contains { $0.sessionId == sessionId }
    }

    /// 视图层入口（可单测）。**三态判定**（2026-11-06 用户裁定，废除旧的 `guard let mirror
    /// else { return true }` 一刀切——那把「没配对 bridge」的普通会话/私聊也吞了，
    /// 就是用户报的「输入框里模型/思考档选择没了」）。返回 true = 隐藏 chip。
    /// - READY / OFFLINE：命中 bots/rooms ⇒ 隐藏（11-04 群聊卡死事故防线，必须保住）；
    ///   未命中 ⇒ 显示。
    /// - IDLE / NEEDS_PAIRING / `nil`：bridge 没在跑，不可能有 Bot 域会话 ⇒ 显示。
    /// - LOADING：短暂 fail-closed——镜像正在拉名册，此刻确实可能是 Bot 域会话；
    ///   名册到了立即按命中揭示（方向仍是「先隐藏后显示」，不会反向闪）。
    static func modelControlsHidden(sessionId: String?, mirror: BridgeTeamMirror?) -> Bool {
        guard let mirror else { return false }
        if mirror.isReady || mirror.isOffline {
            return mirror.isBotDomainSession(sessionId)
        }
        if mirror.needsPairing { return false }
        return true
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
    /// 行级墨色：**ink 名**（`"deepseek"`）或 `#rrggbb`。
    /// 2026-11-01 真实抓包定位：线上 `avatar` 只有 `shape`（`avatar.color` 命中 0），
    /// 颜色一直在这个字段上，此前两端都读错了位置、恒落中性兜底色。
    let color: String?
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
    ///
    /// **已登记的 code 一律走人话**（`BridgeErrorText`，与 Android 的 `humanMessageFor` 逐条对齐）；
    /// 只有「网关新增、还没登记」的 wire 码才保留原文——那是有意留的诊断，它提示我们回来登记。
    var displayText: String {
        if let message, !message.isEmpty { return message }
        if source == "wire", knownWireCode == nil { return "服务器错误（\(code)）" }
        return BridgeErrorText.registeredMessage(forCode: code) ?? "操作失败（\(code)）"
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
    private let service = "com.ydgg123.orcarness.bridge.session"
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

    /// Keychain 里**有没有**这条记录（不管能否读出内容）。
    /// 用途：区分「会话丢了」（允许自愈补发）与「会话在、但读不出来」（**不得**自动补发）——
    /// 后者是安全边界（iOSTester 2026-11-02 提）：不能把一次瞬时的读取失败
    /// 静默升级成「向网关换一份新凭证」，那等于把授权范围从「配对码 ⇒ bridge 会话」
    /// 扩成「网关 token ⇒ 可铸造 bridge 会话」。
    func exists() -> Bool {
        var query = baseQuery
        query[kSecReturnData as String] = false
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        return SecItemCopyMatching(query as CFDictionary, nil) == errSecSuccess
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

/// 连接问题的可读分类：地址失效 / 网络不通 / 服务端异常 / 中断 / 其他。
enum BridgeConnectionIssue: Equatable {
    case addressInvalid
    case network
    case server
    /// **本次刷新被中断**（请求被取消：切后台、换主机、停轮询、或同批请求里有一路失败引起的取消）。
    /// 它**不是**断线——哪怕是真断线，也会由 network/server/addressInvalid 那几类说清。
    case interrupted
    case unknown

    var headline: String {
        switch self {
        case .addressInvalid: return "服务器入口已失效"
        case .network: return "网络不通"
        case .server: return "服务器异常"
        case .interrupted: return "刷新被中断"
        case .unknown: return "连接已断开"
        }
    }

    var detail: String {
        switch self {
        case .addressInvalid: return "隧道域名可能已更换，请更换服务器地址后重新连接。"
        case .network: return "正在自动重连，请检查手机网络或隧道是否在线。"
        case .server: return "服务器暂时不可用，正在自动重试。"
        case .interrupted: return "本次刷新被中断，不算断线；下一个周期会自动重试。"
        case .unknown: return "正在自动重连。"
        }
    }
}

/// `/info` `capabilities.botPlugin` 的三态（网关契约 `d174044`，值域 full/legacy/missing）。
///
/// `nil` = 网关没下发（旧网关）或值不在已知值域 ⇒ **一切照旧**：
/// 「不知道」绝不能当「没装」——那会给所有老网关用户误挂「未安装 Bot 插件」横幅。
enum BotPluginAvailability: String, Equatable {
    case full
    case legacy
    case missing
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
/// - 拉取 /info /state /conversations 喂回 SharedBridgeTeamStore（8s 前台轮询，scenePhase 门控）；
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
    /// `/info` `capabilities.botPlugin`（网关 `d174044`）。`nil` = 网关未下发/值未知 ⇒ 照旧，
    /// **只有 `.missing` 才触发「未安装 Bot 插件」的横幅与聊天页终态**（2026-11-04 派活单）。
    @Published private(set) var botPlugin: BotPluginAvailability?

    /// effect：进入聊天（sessionId 来自配对端 /state 的 bot/room 行）。
    var onOpenConversation: ((String) -> Void)?

    // MARK: 78 room 快照（2026-11-03 用户报「退出对话页再进，又重新加载一遍最新消息」）
    //
    // 根因：room 会话在客户端零持久化 + chatEngine 每次新建（chatEngine() 每次 new），
    // messagesJson 随实例丢弃 ⇒ 再进必空 ⇒ 无条件拉整页 50 条。
    // 修法：adapter（App 级单例 MultiGatewayStore.bridgeTeam，App 存活期唯一，
    // 前置读数 19:51）持有 per-conversation 快照；engine 退场交回、新 engine 先吃
    // 快照立刻渲染，再走 limit=1 探针增量（探针那 1KB 必发——「有快照 ≠ 零请求」，
    // 否则桌面端发的新消息必漏）。
    struct RoomChatSnapshot {
        var messagesJson: String
        var maxSeq: Int64?
        var wasAtBottom: Bool
        /// 退出时的阅读锚（气泡 id + 视口内偏移），用于非贴底退出的原位恢复。
        var anchorBubbleID: String?
        var anchorOffsetInViewport: CGFloat?
        var byteCount: Int { messagesJson.utf8.count }
    }

    /// 只留最近 3 个会话（Chief 定）：内存有界，超了丢最旧。
    private static let roomSnapshotConversationLimit = 3
    /// 字节预算：单快照超过直接拒收（异常大的会话不缓存，行为退回现状）。
    private static let roomSnapshotByteBudget = 512 * 1024

    private var roomSnapshots: [String: RoomChatSnapshot] = [:]
    /// LRU 序（队首 = 最近使用）。
    private var roomSnapshotOrder: [String] = []

    /// 记录/刷新一个会话的快照（engine 每次消息变更或视图退场时调用）。
    func storeRoomSnapshot(conversationId: String, _ snapshot: RoomChatSnapshot) {
        guard snapshot.byteCount <= Self.roomSnapshotByteBudget else { return }
        roomSnapshots[conversationId] = snapshot
        roomSnapshotOrder.removeAll { $0 == conversationId }
        roomSnapshotOrder.insert(conversationId, at: 0)
        while roomSnapshotOrder.count > Self.roomSnapshotConversationLimit {
            let evicted = roomSnapshotOrder.removeLast()
            roomSnapshots[evicted] = nil
        }
    }

    /// 取快照（命中则升到 LRU 队首）。
    func roomSnapshot(conversationId: String) -> RoomChatSnapshot? {
        guard var snapshot = roomSnapshots[conversationId] else { return nil }
        roomSnapshotOrder.removeAll { $0 == conversationId }
        roomSnapshotOrder.insert(conversationId, at: 0)
        return snapshot
    }

    /// 只更新贴底标记（视图在退场/滚动时上报，消息正文不动）。
    func updateRoomSnapshotWasAtBottom(conversationId: String, wasAtBottom: Bool) {
        guard var snapshot = roomSnapshots[conversationId] else { return }
        snapshot.wasAtBottom = wasAtBottom
        roomSnapshots[conversationId] = snapshot
    }

    /// 只更新阅读锚（视图在退场时上报）。
    func updateRoomSnapshotAnchor(conversationId: String, bubbleID: String?, offsetInViewport: CGFloat?) {
        guard var snapshot = roomSnapshots[conversationId] else { return }
        snapshot.anchorBubbleID = bubbleID
        snapshot.anchorOffsetInViewport = offsetInViewport
        roomSnapshots[conversationId] = snapshot
    }

    /// 测试/诊断用。
    var roomSnapshotCountForTesting: Int { roomSnapshots.count }

    private let sessionStore = BridgeKeychainSessionStore()

    /// 本地是否已有可用的 bridge 会话。
    /// 用途：**自愈**——网关只在配对那一刻下发 `bridgeSession`（重连与 `hello` 都不带），
    /// 丢了就永不自愈。补发的接法见 `MultiGatewayStore`：`hello` 上**只在没有会话时**接一次，
    /// 有会话就不接，避免每次重连都走一遍 `adoptSession`（它会强制重建 transport + 重开轮询）。
    var hasStoredSession: Bool { sessionStore.loadJson() != nil }

    /// 本地**存在**会话记录、但读不出内容（= 密文在、解不开）。
    /// 自愈补发只在「确实没有记录」时才做；解不开时**不补发**（iOSTester 的安全边界）——
    /// 否则一次瞬时的读取失败会被静默升级成「向网关换一份新凭证」，
    /// 授权范围从「配对码 ⇒ bridge 会话」扩成「网关 token ⇒ 可铸造 bridge 会话」。
    var storedSessionPresentButUnusable: Bool {
        sessionStore.exists() && sessionStore.loadJson() == nil
    }
    /// U5：配对成功且服务端下发了网关凭证时回调（由 MultiGatewayStore 接管落盘/建 profile）。
    /// 参数：凭证、bridge 的 https 根 origin。缺失时不会调用。
    var onGatewayCredential: ((BridgeGatewayCredential, String) -> Void)?
    private let store: SharedBridgeTeamStore
    private var observer: BridgeMviObserver?
    private var subscription: SharedMviSubscription?
    private var transport: BridgeTransport?
    private var pollingTask: Task<Void, Never>?
    private var pairingTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    /// 主动换凭证的定时任务（见 `startTokenRefreshLoop`）。
    private var tokenRefreshTask: Task<Void, Never>?
    private var started = false
    private var failureAttempt = 0
    private var lastSequence: Int64 = -1

    /// 主动换凭证的**兜底**节奏：老网关不公布 TTL 时按 14 分钟（凭证 15 分钟过期，提前 1 分钟）。
    /// 新网关会在 `/info` 的 `capabilities.accessTokenTtlSeconds` 里公布真实 TTL，
    /// 手机按 TTL 的 90% 排（见 `BridgeTokenTTL`），网关改 TTL 不用发版。
    static let proactiveRefreshInterval: TimeInterval = 14 * 60
    /// 当前生效的主动刷新间隔（`/info` 公布 TTL 后会被覆盖）。
    private var proactiveInterval: TimeInterval = BridgeTeamAdapter.proactiveRefreshInterval
    /// 主动刷新的诊断（非静默）：失败原因要能在设备日志里看到。
    private static let refreshLog = Logger(subsystem: "ai.dsh.mobile.ios", category: "bridge-token")
    /// 上次主动/被动刷新成功的时间（诊断与单测用；不参与鉴权判定）。
    private(set) var lastTokenRefreshAt: Date?

    init() {
        let sessionStore = self.sessionStore
        self.store = SharedBridgeTeamStore(sessionStore: sessionStore)
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
        tokenRefreshTask?.cancel()
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
    /// bridge `answer-question`（POST /mobile/v1/actions，网关 `09d66ad`）。
    /// **HTTP 200 只代表已受理**：卡片最终以卡片流 `card.answered` 为准，本地卡不在此处删。
    /// 409 `bot/stale` / `QUESTION_IN_FLIGHT` 原样抛给调用方按文档处置（不重试）。
    func answerCardQuestion(sessionId: String, questionId: String,
                            selected: [String], custom: String?) async throws {
        restoreTransportFromStoredSession()
        guard let transport, let token = store.currentAccessToken() else {
            throw BridgeAnswerError.notPaired
        }
        var answer: [String: Any] = ["selected": selected]   // 逐字原样：不 trim、不去重
        if let custom, !custom.isEmpty { answer["custom"] = custom }
        // path 必须是**相对**路径：`BridgeTransport.request()` 自己会拼
        // `config.origin + "mobile/v1" + path`（BridgeTransport.swift:310）。
        // 这里曾写 "/mobile/v1/actions" → 实际请求 /mobile/v1/mobile/v1/actions
        // → 网关 404 NOT_FOUND。这是全项目唯一一处传完整路径的调用。
        _ = try await transport.post(path: "/actions", token: token, jsonBody: [
            "endpoint": "answer-question",
            "payload": ["sessionId": sessionId, "questionId": questionId, "answer": answer],
        ])
    }

    enum BridgeAnswerError: LocalizedError {
        case notPaired
        var errorDescription: String? { "尚未完成配对，无法提交回答。" }
    }


    func chatEngine(conversationId: String, sessionId: String) -> BridgeChatEngine? {
        restoreTransportFromStoredSession()
        guard let transport, store.currentAccessToken() != nil else { return nil }
        let engine = BridgeChatEngine(
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
        // 78：先吃快照立刻渲染（不许白屏等网络），start() 再走 limit=1 探针增量
        // （探针那 1KB 必发——“有快照 ≠ 零请求”，否则桌面端发的新消息必漏）。
        if let snapshot = roomSnapshot(conversationId: conversationId) {
            engine.applyResumedSnapshot(messagesJson: snapshot.messagesJson, wasAtBottom: snapshot.wasAtBottom)
        }
        engine.onMessagesChanged = { [weak self, weak engine] json in
            guard let self, let engine else { return }
            let previous = self.roomSnapshot(conversationId: conversationId)
            self.storeRoomSnapshot(conversationId: conversationId, BridgeTeamAdapter.RoomChatSnapshot(
                messagesJson: json,
                maxSeq: engine.latestKnownSeq,
                wasAtBottom: previous?.wasAtBottom ?? true,
                anchorBubbleID: previous?.anchorBubbleID,
                anchorOffsetInViewport: previous?.anchorOffsetInViewport
            ))
        }
        return engine
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
                lastTokenRefreshAt = Date()
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
            botPlugin = Self.botPlugin(fromInfoJSON: infoBody)
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
        if isCancellation(error) { return .interrupted }
        if let status = error as? BridgeResponseStatus {
            return status.status >= 500 ? .server : .unknown
        }
        guard let urlError = error as? URLError else { return .unknown }
        switch urlError.code {
        case .cancelled:
            return .interrupted
        case .cannotFindHost, .dnsLookupFailed, .cannotConnectToHost:
            return .addressInvalid
        case .notConnectedToInternet, .networkConnectionLost, .timedOut, .dataNotAllowed:
            return .network
        default:
            return .unknown
        }
    }

    /// **取消不是断线**：切后台、换主机、停轮询，以及同一批 `async let` 里某一路失败引起的兄弟取消，
    /// 都会让在途请求以 `CancellationError` / `URLError.cancelled` 结束。
    /// 2026-11-02 用户报「明明已连接却一直挂着『连接已断开』」就是这条被当成连接失败。
    nonisolated static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let urlError = error as? URLError, urlError.code == .cancelled { return true }
        return false
    }

    /// 失败/中断时打一行（**成功不打**：8 秒一条会把日志刷爆）。
    /// 固定标记 `bridgeteam-refresh-failed`（24 字节 ASCII）也可作为产物树标记。
    @MainActor
    static func logRefreshFailure(_ error: Error, attempt: Int) {
        let status = (error as? BridgeResponseStatus).map { String($0.status) } ?? "nil"
        let urlError = (error as? URLError).map { String($0.errorCode) } ?? "nil"
        refreshFailureLog.notice("bridgeteam-refresh-failed status=\(status, privacy: .public) urlerror=\(urlError, privacy: .public) cancelled=\(isCancellation(error), privacy: .public) attempt=\(attempt, privacy: .public)")
    }

    private static let refreshFailureLog = Logger(subsystem: "ai.dsh.mobile.ios", category: "bridge-refresh")

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
            // 兜底绝不能把内部 code 摆到用户面前（曾出现 `no-device-session` / `bridge-session-unavailable`）。
            let detail = outcome.errorMessage
                ?? outcome.errorCode.map { BridgeErrorText.humanMessage(forCode: $0) }
                ?? "未知原因"
            let message = "配对资料保存失败：" + detail
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
        // 轮询一起来就排上主动换凭证；停轮询（断开/进后台）时一并取消。
        startTokenRefreshLoop()
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
        stopTokenRefreshLoop()
    }

    // MARK: - 主动换凭证（不等 401 被踢）

    /// 连上就排一次 `proactiveRefreshInterval` 后的主动刷新；成功后按同一节奏续排。
    /// 断开/后台由 `stopPolling` 取消；并发安全由 KMP 的 `beginTokenRefresh` 单飞保证，
    /// 这里不做第二套并发控制。
    func startTokenRefreshLoop() {
        guard tokenRefreshTask == nil else { return }
        guard store.currentAccessToken() != nil else { return }
        tokenRefreshTask = Task { @MainActor [weak self] in
            // 无论怎么退出（成功/失败/无 token/被取消）都清句柄，保证还能重新排上。
            defer { self?.tokenRefreshTask = nil }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(proactiveInterval * 1_000_000_000))
                guard !Task.isCancelled, let self else { return }
                guard self.store.currentAccessToken() != nil else { return }
                let refreshed = await self.refreshAccessTokenOnce()
                guard refreshed else {
                    // 主动刷新失败：不自己重试，退化成「被踢再刷」的老行为；
                    // 之后任何一次 401 刷新成功、或下一次轮询成功都会把主动刷新重新排上。
                    Self.refreshLog.error("proactive token refresh failed; falling back to 401-driven refresh")
                    return
                }
                Self.refreshLog.notice("proactive token refresh ok")
            }
        }
    }

    func stopTokenRefreshLoop() {
        tokenRefreshTask?.cancel()
        tokenRefreshTask = nil
    }

    /// 401 路径刷新成功后重新排主动刷新（重置 14 分钟窗口）。
    private func restartTokenRefreshLoop() {
        stopTokenRefreshLoop()
        startTokenRefreshLoop()
    }

    /// 从 `/info` 原始 JSON 读 `capabilities.botPlugin`（纯函数，可单测）。
    /// 没有该字段（旧网关）/ 不是字符串 / 值不在已知值域 ⇒ **nil（一切照旧，不当「没装」）**。
    /// 为什么不进 KMP：`TeamCapabilities.fromInfo` 只提取 actions 白名单与布尔 features，
    /// 字符串形态的 botPlugin 在状态投影里会被丢（2026-11-04 实读 TeamCapabilities.kt:34-46），
    /// 所以在这里直读 `/info` 原始体——与 `BridgeChunkLimits.update(fromInfo:)` 同款先例。
    /// `nonisolated`：纯解析无状态，测试（非主线程）也要能直接调。
    nonisolated static func botPlugin(fromInfoJSON infoJSON: String) -> BotPluginAvailability? {
        guard let data = infoJSON.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let capabilities = root["capabilities"] as? [String: Any],
              let raw = capabilities["botPlugin"] as? String else { return nil }
        return BotPluginAvailability(rawValue: raw.lowercased())
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
            // 分块上限同样由 /info 公布（capabilities.maxFrameBytes / maxReassembledBytes /
            // maxStreamsPerConnection）；团队 WS 下次建连时读 BridgeChunkLimits.shared。
            BridgeChunkLimits.update(fromInfo: infoBody)
            // Bot 插件可用性（网关 d174044）：未下发/未知值 ⇒ 保持 nil（照旧）。
            botPlugin = Self.botPlugin(fromInfoJSON: infoBody)
            // ② 应用层心跳参数同样来自 /info（不公布则用 25/75 兜底）。
            BridgeSessionTuning.update(fromInfo: infoBody)
            // 凭证 TTL 由网关在 /info 里公布（capabilities.accessTokenTtlSeconds），
            // 手机按 90% 排主动刷新；老网关没有这个字段 → 保持 14 分钟兜底。
            if let interval = BridgeTokenTTL.proactiveInterval(infoJSON: infoBody),
               interval != proactiveInterval {
                proactiveInterval = interval
                Self.refreshLog.notice("token ttl from /info → proactive interval \(Int(interval), privacy: .public)s")
                // 间隔变了，按新节奏重排（不必等旧窗口走完）。
                restartTokenRefreshLoop()
            }
            store.refreshTeam(
                infoJson: infoBody,
                stateJson: stateBody,
                conversationsJson: conversationsBody
            )
            // 连接正常时确保主动换凭证在跑（幂等）：主动刷新失败过一次后，
            // 只要后续轮询恢复，就重新排上，不必等到下次前台。
            startTokenRefreshLoop()
        } catch {
            // **取消不设 connectionIssue、不 bump failureAttempt**：被中断的刷新不是断线。
            if Self.isCancellation(error) {
                Self.logRefreshFailure(error, attempt: failureAttempt)
                return
            }
            await handleRefreshFailure(error, transport: transport)
        }
    }

    private func handleRefreshFailure(_ error: Error, transport: BridgeTransport) async {
        Self.logRefreshFailure(error, attempt: failureAttempt + 1)
        if let status = error as? BridgeResponseStatus {
            if status.status == 401 {
                // 被踢才有机会刷：成功就顺手把主动刷新重新排上（重置 14 分钟窗口）。
                if await refreshToken(transport: transport) { restartTokenRefreshLoop() }
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
        case .interrupted:
            message = "本次刷新被中断，不算断线；下一个周期会自动重试"
        case .unknown, .none:
            message = error.localizedDescription
        }
        lastTransportError = message
        store.refreshFailed(code: code, message: message)
    }

    /// 401 单飞刷新：成功喂回 KMP（保留 serverUrl/tlsPin 绑定），失败按 SDK 语义分永久/临时。
    /// 返回是否刷新成功——调用方据此决定要不要把主动刷新重新排上。
    @discardableResult
    private func refreshToken(transport: BridgeTransport) async -> Bool {
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
            guard var next = try? JSONSerialization.jsonObject(with: body.data(using: .utf8) ?? Data()) as? [String: Any] else {
                store.tokenRefreshRejected(code: "invalid_response")
                return false
            }
            // 与 SDK index.js:242 一致：刷新响应不含 serverUrl/tlsPin，需由客户端合并。
            next["serverUrl"] = payload["serverUrl"]
            next["tlsPin"] = payload["tlsPin"]
            if let merged = try? JSONSerialization.data(withJSONObject: next),
               let mergedText = String(data: merged, encoding: .utf8) {
                store.tokenRefreshSucceeded(sessionJson: mergedText)
                lastTokenRefreshAt = Date()
                return true
            } else {
                store.tokenRefreshRejected(code: "invalid_response")
                return false
            }
        } catch let status as BridgeResponseStatus {
            let envelope = BridgeErrorsKt.decodeBridgeErrorEnvelope(
                status: Int32(status.status),
                body: status.body
            )
            store.tokenRefreshRejected(code: envelope.code)
            return false
        } catch {
            store.tokenRefreshRejected(
                code: (error as? URLError)?.code == .timedOut ? "timeout" : "network_error"
            )
            return false
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

// MARK: - 凭证 TTL 协商（网关公布，手机按比例排主动刷新）

/// 应用层心跳参数（② 心跳 + 增量）。网关在 `/info` 的 `capabilities` 里公布：
/// `heartbeatIntervalSeconds`（默认 25）、`heartbeatTimeoutSeconds`（默认 75）。
/// 未公布就用默认值——与凭证 TTL / 分块上限同一套"公布则用、不公布则兜底"的协商风格。
@MainActor
enum BridgeSessionTuning {
    struct Tuning: Equatable {
        var heartbeatInterval: TimeInterval = 25
        var heartbeatTimeout: TimeInterval = 75
    }

    private(set) static var shared = Tuning()
    static let minimumInterval: TimeInterval = 5
    static let maximumInterval: TimeInterval = 300

    /// 只在**公布值可用**时更新；不公布保持默认，不猜。
    static func update(fromInfo infoJSON: String) {
        guard let data = infoJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let caps = object["capabilities"] as? [String: Any] else { return }
        var next = Tuning()
        var found = false
        if let interval = (caps["heartbeatIntervalSeconds"] as? NSNumber)?.doubleValue, interval > 0 {
            next.heartbeatInterval = interval; found = true
        }
        if let timeout = (caps["heartbeatTimeoutSeconds"] as? NSNumber)?.doubleValue, timeout > 0 {
            next.heartbeatTimeout = timeout; found = true
        }
        guard found else { return }
        next.heartbeatInterval = min(max(next.heartbeatInterval, minimumInterval), maximumInterval)
        next.heartbeatTimeout = min(max(next.heartbeatTimeout, next.heartbeatInterval * 2), maximumInterval * 2)
        if next != shared { shared = next }
    }
}

/// 网关可以在 `/info` 的 `capabilities` 里公布访问凭证的有效期（秒）：
/// `{"capabilities": {"accessTokenTtlSeconds": 900, ...}}`。
/// 手机按 TTL 的 90% 排主动刷新，所以网关以后把 15 分钟改成 30 分钟 / 5 分钟都不用发版。
///
/// 前向兼容红线：**字段缺失、类型不对、非正数一律当作"没给"**，调用方回落到
/// `BridgeTeamAdapter.proactiveRefreshInterval`（14 分钟）——老网关不发这个字段也得能连。
enum BridgeTokenTTL {
    /// 占 TTL 的比例：留 10% 余量吸收网络抖动与请求耗时。
    static let refreshRatio: Double = 0.9
    /// 间隔下限/上限（秒）：太小会频繁换证（网关侧也是负担），太大就逼近过期。
    static let minimumInterval: TimeInterval = 60
    static let maximumInterval: TimeInterval = 60 * 60

    /// 从 `/info` 的原始 JSON 里取 TTL 并换算成刷新间隔；拿不到返回 nil。
    static func proactiveInterval(infoJSON: String) -> TimeInterval? {
        guard let data = infoJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let capabilities = object["capabilities"] as? [String: Any],
              let raw = capabilities["accessTokenTtlSeconds"] as? NSNumber else { return nil }
        return proactiveInterval(ttlSeconds: raw.doubleValue)
    }

    /// 纯换算（便于单测）：非有限/非正 → nil；否则 90% 并夹在 [60s, 60min]。
    static func proactiveInterval(ttlSeconds: Double) -> TimeInterval? {
        guard ttlSeconds.isFinite, ttlSeconds > 0 else { return nil }
        return min(max(ttlSeconds * refreshRatio, minimumInterval), maximumInterval)
    }
}

/// bridge 错误码 → 中文人话。**与 Android 的 `AndroidBridgeTeamAdapter.humanMessageFor` 逐条对齐**
/// （映射各自放在平台的适配层、不在 KMP；但文案必须一致——见 `docs/spec/parity-defect-registry.md` #2）。
///
/// 为什么必须有：网关/传输层只给 `code` 不给 `message` 时，App 曾把 code 原文直接摆到用户面前
/// （截图实测 `no-device-session`；`bridge-session-unavailable` 在 iOS 全仓 0 命中 ⇒ 用户只会看到兜底）。
/// **判据（机械可执行）**：已登记的 code 在 UI 文案里 0 命中；未登记的 code 走通用文案、同样不外露。
enum BridgeErrorText {
    /// 未登记 code 的兜底（**绝不外露 code**）。
    static let genericFailure = "连接出问题了，稍后会自动重试"
    static let notPaired = "尚未配对桥接设备：请先与网关配对"
    static let networkRetry = "网络不通，稍后会自动重试"

    /// 已登记 code → 人话；**未登记返回 nil**，让调用方自行兜底
    /// （`BridgeTeamErrorMirror` 要保留「未登记 wire 码显示原文」的 fail-closed 诊断，§2.9）。
    static func registeredMessage(forCode code: String?) -> String? {
        guard let code = code?.trimmingCharacters(in: .whitespacesAndNewlines), !code.isEmpty else { return nil }
        switch code {
        case "no-bridge-origin":
            return "尚未连接桌面端：请先在设置里配对服务器"
        case "no-device-session", "unauthorized", "invalid_session", "session_clear", "blocked_url":
            return notPaired
        case "network_error", "transport_failed":
            return networkRetry
        // 网关自愈分支的三种 error（mobile-gateway `lib/index.mjs`）：Android 早前已登记，iOS 此前 0 命中。
        // **网关侧每新增一个 code，都要在这里登记。**
        case "authentication-required":
            return "桌面端连接尚未认证，正在重连"
        case "rate-limited":
            return "请求太频繁，稍后自动重试"
        case "bridge-session-unavailable":
            return "桌面端暂时无法签发桥接凭证，请稍后重试或重新扫码配对"
        default:
            return nil
        }
    }

    /// 未登记也回落到人话（**绝不显示裸 code**）。
    static func humanMessage(forCode code: String?) -> String {
        registeredMessage(forCode: code) ?? genericFailure
    }
}

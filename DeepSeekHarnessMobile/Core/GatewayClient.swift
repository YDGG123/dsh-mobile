import Foundation
import UIKit
import Security
import OSLog
import Network
import DeepSeekHarnessShared

@MainActor
final class GatewayClient: ObservableObject {

    /// 81 重连退避（纯函数，单测直接钉产品本体）：`immediateReconnect` 当次豁免置 0
    /// （become-active 快速线 giveUp 后不退避）；常规链 2/4/8/…s，封顶 30s。
    /// `attempt` 从 1 起（`fail()` 里先递增再取值）。
    static func reconnectDelaySeconds(attempt: Int, immediateReconnect: Bool) -> Int {
        guard !immediateReconnect else { return 0 }
        return min(30, 2 * (1 << min(attempt - 1, 4)))
    }
    private static let presetLogger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "agent-presets")
    private static let lifecycleLogger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "gateway-lifecycle")
    private var backgroundEnteredAt: Date?

    // 仅记录传输元数据，禁止写入地址、凭据、消息正文和服务端原始关闭原因。
    private func traceLifecycle(_ event: String) {
        let elapsed = backgroundEnteredAt.map { Date().timeIntervalSince($0) } ?? -1
        Self.lifecycleLogger.notice("[GatewayLifecycle] channel=\(self.channel) event=\(event) background=\(self.isApplicationInBackground) backgroundSeconds=\(elapsed) connected=\(self.state.isConnected) wantsConnection=\(self.wantsConnection) taskId=\(self.socket?.taskIdentifier ?? -1)")
    }

    private let channel: String
    var requestDecorator: ((String) -> String?)?
    var conversationTarget: ((String) -> MobileConversationTarget?)?
    private var subscriptionPending = false
    private var pendingSubscriptionID: String?
    private var desiredSubscriptionID: String?
    private var subscriptionDirty = false
    private var subscriptionTimeout: Task<Void, Never>?
    private var subscriptionGeneration = UUID()
    private var subscriptionRecoveryAttempts = 0
    private static let maximumSubscriptionRecoveryAttempts = 2

    var subscriptionRecoveryExhausted: Bool {
        subscriptionRecoveryAttempts >= Self.maximumSubscriptionRecoveryAttempts
    }

    func cancelSubscriptionRecovery() {
        subscriptionRecoveryAttempts = 0
        desiredSubscriptionID = nil
        resetSubscriptionRequests()
    }

    /// subscribed 没有 requestId；换新连接隔离旧确认，只恢复当前订阅，不重放写操作。
    @discardableResult
    func recoverSubscription(sessionID: String) -> Bool {
        guard channel == "control", wantsConnection, state.isConnected,
              !probeOnly, !isManualPairingAttempt, !isApplicationInBackground,
              desiredSubscriptionID == sessionID, !subscriptionRecoveryExhausted,
              let endpoint else { return false }
        subscriptionRecoveryAttempts += 1
        traceLifecycle("subscription-recovery attempt=\(subscriptionRecoveryAttempts)")
        forceNextWebsocket = true
        beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false)
        return true
    }

    func resetSubscriptionRequests() {
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        subscriptionGeneration = UUID()
        subscriptionPending = false
        pendingSubscriptionID = nil
        subscriptionDirty = false
    }

    /// subscribed 无 requestId，必须单飞并合并快速切换意图。
    func acceptSubscriptionAck(sessionID: String?) -> Bool {
        // 兼容未经过前台选择入口的历史订阅与旧网关；托管切换仍严格单飞。
        guard subscriptionPending else { return true }
        // 收到确认就**先停计时、清 pending**，无论 sessionId 是否等于等待值：
        // 网关收到 subscribe 会立刻回 `subscribed`（`lib/index.mjs:3182`），对不上只可能是
        // 旧响应/快速切换的错位；早期的"严格相等才停表"会让一次错位把 15 秒计时留到爆炸，
        // 表现为用户正看页面时弹「订阅超时」（真机频繁打断的根因）。
        guard pendingSubscriptionID == sessionID else { return false }
        traceLifecycle("subscription-ack dirty=\(subscriptionDirty)")
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        subscriptionPending = false
        if pendingSubscriptionID != sessionID {
            gatewayApprovalTrace("subscribe-ack-stale got=\(sessionID ?? "nil") waiting=\(pendingSubscriptionID ?? "nil")")
        }
        if subscriptionDirty {
            subscriptionDirty = false
            subscribe(sessionId: desiredSubscriptionID)
            return false
        }
        subscriptionRecoveryAttempts = 0
        return true
    }

    /// 仅供已通过 AssistantStreamState 校验的快照调用，不接受任意历史页作为确认。
    @discardableResult
    func acceptSubscriptionSnapshot(sessionID: String) -> Bool {
        guard subscriptionPending, pendingSubscriptionID == sessionID else { return false }
        return acceptSubscriptionAck(sessionID: sessionID)
    }

    var credentialID: String?
    var expectedGatewayID: String?
    var trustedEndpoints: [String] = []
    var onIdentity: ((GatewayFrame, String) throws -> Void)?
    var probeOnly = false
    private var reconnectAttempt = 0
    private var networkPathMonitor: NWPathMonitor?
    private lazy var networkRecovery = GatewayNetworkRecovery(
        canRecover: { [weak self] in
            guard let self else { return false }
            return self.wantsConnection && self.channel == "control" && !self.probeOnly &&
                !self.isManualPairingAttempt && !self.isApplicationInBackground
        },
        recover: { [weak self] in self?.recoverNetworkConnection() }
    )
    private var pendingConversationPayloads: [String] = []
    private var pendingConversationBytes = 0
    private var outboundTask: Task<Void, Never>?
    private let transportSession = URLSession(configuration: .ephemeral, delegate: GatewayRedirectBlocker(), delegateQueue: nil)

    static func forgetCredential(for profileID: String) {
        let url = URL(string: "https://gateway-credential.invalid/\(profileID)")!
        Task { await SystemGatewayCredentialStore.shared.deleteToken(for: url) }
    }

    private func credentialURL(_ endpoint: URL) -> URL {
        credentialID.map { URL(string: "https://gateway-credential.invalid/\($0)")! } ?? endpoint
    }

    func migrateCredential(from endpoint: URL) async throws {
        guard credentialID != nil, try await credentials.loadToken(for: credentialURL(endpoint)) == nil,
              let token = try await credentials.loadToken(for: endpoint) else { return }
        try await credentials.saveToken(token, for: credentialURL(endpoint))
    }

    /// 最近一次收到**任何**帧的时间（含语音帧）。判活不能只看 pong：
    /// 通道里明明有数据在流，一次迟到的 pong 不该拆掉健康连接
    /// （2026-10-26 真机「一直在断连重连」的断连风暴就是这条缺失导致的误判）。
    private var lastInboundFrameAt = Date()
    /// 连续「判活失败」次数。一次失败就拆 → 手机忙时每 ~30s 一轮重连；必须连续两次。
    private var consecutiveProbeMisses = 0
    /// 判死门限（连续失败次数）。提到类常量，方便和单测对齐。
    static let probeMissLimit = 2
    /// 日常 30s 心跳那条探测线的超时——2026-10-26 断连风暴防线的地盘，**不改**。
    static let heartbeatProbeTimeout: Duration = .seconds(10)
    /// 握手看门狗：蜂窝对被拦域名的静默丢包会让 WS 握手挂满 URLSession 默认 60s。
    /// 不用 request.timeoutInterval——它管空闲读超时，25-30s 心跳间隔会被短值误杀。
    static let handshakeWatchdogTimeout: Duration = .seconds(10)
    /// 「回到前台」这一次探测的短超时（81）：用户判据是回前台 ≤3s 连上，
    /// 10s×2 的旧预算到不了；短超时**只用于这条路径**，两次 miss 才拆的红线不动。
    static let foregroundReturnProbeTimeout: Duration = .seconds(1)

    /// 旧链路最坏预算（政策常数，供单测钉死）：probe1 + 等下一次心跳 + probe2 + 重连退避。
    /// 端点归一化键（host 小写 + 有效端口）。轮转与「上次失败」判定都用它，
    /// 不再依赖 URL 字符串逐字相等——端点串漂移会把轮转锚点打歪（2026-11-11 真机「离开家连不上」）。
    static func endpointKey(_ url: URL) -> String {
        let host = url.host?.lowercased() ?? ""
        let defaultPort = url.scheme?.lowercased() == "wss" ? 443 : 80
        return "\(host):\(url.port ?? defaultPort)"
    }

    /// 从 current 出发在 trusted 列表里轮转到**下一个不同键**的端点；
    /// 当前串漂移（键不在列表里）时退而取第一个不同键的候选；列表只有当前键时返回 nil（原地不动）。
    static func rotateAway(from current: URL, trusted: [String]) -> URL? {
        let candidates = trusted.compactMap { URL(string: $0) }
        let currentKey = endpointKey(current)
        if let index = candidates.firstIndex(where: { endpointKey($0) == currentKey }) {
            return candidates[(index + 1) % candidates.count]
        }
        return candidates.first { endpointKey($0) != currentKey }
    }

    static func legacyForegroundRecoveryBudgetSeconds(
        probeTimeout: Double = 10, heartbeatInterval: Double = 30, backoff: Double = 2
    ) -> Double { probeTimeout + heartbeatInterval + probeTimeout + backoff }

    /// 新链路预算：前台恢复探测用 1s 短超时、miss 立即重探、giveUp 后跳过退避直接重连。
    static func quickForegroundRecoveryBudgetSeconds(quickTimeout: Double = 1, backoff: Double = 0) -> Double {
        quickTimeout + quickTimeout + backoff
    }

    /// 掉线重连目标决策（纯函数，可单测）：
    /// attempt 1（本轮首次退避）优先回「最后成功的地址」——它刚被证明可用，不按序绕走
    /// （2026-11-12 真机事故：蜂窝下第一跳绕到被拦的旧域名，握手挂满超时 ⇒ 重连慢）；
    /// 该地址再失败（attempt ≥ 2）才按 trustedEndpoints 顺序 rotate，避免钉死单一地址。
    /// lastSuccessfulEndpoint 缺席（从未连上过）时保持原 rotate 行为。
    static func reconnectTarget(current: URL, lastSuccessful: URL?, attempt: Int, trusted: [String]) -> URL {
        // attempt 1 无条件回 lastSuccessful：preferred==current 时也要**原地重试**而不是轮换走人
        //（回归用例首跳曾抓出此 bug：加了 != 守卫会掉进 rotate，正好复刻要修的事故）
        if attempt <= 1, let preferred = lastSuccessful {
            return preferred
        }
        if let rotated = Self.rotateAway(from: current, trusted: trusted),
           Self.endpointKey(rotated) != Self.endpointKey(current) {
            return rotated
        }
        return current
    }

    /// 判活结论（纯函数，单测直接钉）。放在这里而不是写死进 Task：
    /// 这类「多久算死」的决策出过一次错，必须可测。
    enum ProbeVerdict: Equatable {
        /// 超时窗内有数据帧进来 → 活着（pong 只是迟了）
        case aliveByInboundFrames
        /// 记为一次失败，还不够拆（下个心跳再试）
        case miss(Int)
        /// 连续失败到门限 → 拆连接重连
        case giveUp(Int)
    }

    static func probeVerdict(inboundSinceProbe: Bool, consecutiveMisses: Int,
                             limit: Int = probeMissLimit) -> ProbeVerdict {
        if inboundSinceProbe { return .aliveByInboundFrames }
        let next = consecutiveMisses + 1
        return next >= limit ? .giveUp(next) : .miss(next)
    }

    private var conversationClient: GatewayClient?
    private var onTransportFailure: ((String, Bool) -> Void)?
    private let credentials: any GatewayCredentialStoring
    private let heartbeatInterval: Duration
    private let probeTimeout: Duration
    /// 「回到前台」探测的短超时（81）：只服务 become-active 那一条路径。
    private let foregroundProbeTimeout: Duration
    private let foregroundRtcTimeout: Duration
    private let subscriptionTimeoutInterval: Duration
    private let rtcPolicy: (String) -> Bool
    private let prepareTunnel: (String) async -> Bool
    private let tunnelForwarder: (String) async -> TsnetNodeClient.Forward?

    init(
        channel: String = "control",
        credentials: (any GatewayCredentialStoring)? = nil,
        heartbeatInterval: Duration = .seconds(30),
        probeTimeout: Duration = GatewayClient.heartbeatProbeTimeout,
        foregroundProbeTimeout: Duration = GatewayClient.foregroundReturnProbeTimeout,
        foregroundRtcTimeout: Duration = .seconds(3),
        subscriptionTimeoutInterval: Duration = .seconds(15),
        rtcPolicy: @escaping (String) -> Bool = { GatewayTransportPolicy.shared.allowsRtc(endpoint: $0) },
        prepareTunnel: @escaping (String) async -> Bool = { await TailscaleTunnelController.shared.prepareForConnection(to: $0) },
        tunnelForwarder: @escaping (String) async -> TsnetNodeClient.Forward? = { await TailscaleTunnelController.shared.forwarder(for: $0) }
    ) {
        self.channel = channel
        self.credentials = credentials ?? SystemGatewayCredentialStore.shared
        self.heartbeatInterval = heartbeatInterval
        self.probeTimeout = probeTimeout
        self.foregroundProbeTimeout = foregroundProbeTimeout
        self.foregroundRtcTimeout = foregroundRtcTimeout
        self.subscriptionTimeoutInterval = subscriptionTimeoutInterval
        self.rtcPolicy = rtcPolicy
        self.prepareTunnel = prepareTunnel
        self.tunnelForwarder = tunnelForwarder
    }
#if DEBUG
    private var testRequestTransport: ((String) -> Void)?
    var receivedFrameBarrierForTesting: ((Data) async -> Void)?
    var incomingFrameDecoderForTesting: ((Data) async throws -> (GatewayFrame?, String?))?
    var recoveryStateForTesting: (wantsConnection: Bool, reconnectAttempt: Int, isManualPairing: Bool) {
        (wantsConnection, reconnectAttempt, isManualPairingAttempt)
    }

    /// 测试使用明确的已连接传输，避免仅投递 hello 却仍处于断线状态的伪在线 fixture。
    func installConnectedTransportForTesting(_ send: @escaping (String) -> Void = { _ in }) {
        testRequestTransport = send
        state = .connected
    }

    func installRtcConnectionForTesting(_ connection: any GatewayRtcConnection) {
        rtcLink = connection
        connectionMethod = "WebRTC"
        lastSuccessfulConnectionWasRtc = true
    }

    func recoverNetworkConnectionForTesting() { recoverNetworkConnection() }
#endif

    /// History responses contain raw trajectory events (including request
    /// context) and can exceed URLSessionWebSocketTask's 1 MiB default.
    /// Gateway v0.1.12 normally keeps history pages below 4 MiB. Retain a much
    /// larger transport ceiling for the documented case where one indivisible
    /// event is itself larger than the page budget.
    private static let maximumIncomingMessageSize = 64 * 1024 * 1024


    @Published private(set) var state: ConnectionState = .disconnected
    private struct NetworkDiagnosticsObserver {
        let pong: (String) -> Void
        let traffic: (Int, Int) -> Void
    }
    private var networkObservers: [UUID: NetworkDiagnosticsObserver] = [:]
    private var diagnosticsCapabilities = Set<String>()
    private var forwardNetworkTraffic: ((Int, Int) -> Void)?
    private var networkTrafficObserved: (() -> Bool)?
    var networkDiagnosticsConnectionKey: String { connectionGeneration.uuidString }
    var networkDiagnosticsSupported: Bool { diagnosticsCapabilities.contains("network-diagnostics-v1") }
#if DEBUG
    var networkDiagnosticsObserverCountForTesting: Int { networkObservers.count }
#endif

    func addNetworkDiagnosticsObserver(id: UUID, pong: @escaping (String) -> Void, traffic: @escaping (Int, Int) -> Void) {
        networkObservers[id] = NetworkDiagnosticsObserver(pong: pong, traffic: traffic)
    }
    func removeNetworkDiagnosticsObserver(id: UUID) { networkObservers.removeValue(forKey: id) }

    private func recordNetworkTraffic(sent: Int = 0, received: Int = 0) {
        for observer in networkObservers.values { observer.traffic(sent, received) }
        if networkTrafficObserved?() == true { forwardNetworkTraffic?(sent, received) }
    }
    private func recordNetworkSent(_ text: String) {
        if !networkObservers.isEmpty || networkTrafficObserved?() == true { recordNetworkTraffic(sent: text.utf8.count) }
    }

    func sendNetworkDiagnosticsProbe(_ requestID: String) async throws {
        guard state.isConnected, networkDiagnosticsSupported, let socket else { throw URLError(.notConnectedToInternet) }
        let data = try JSONSerialization.data(withJSONObject: ["type": "ping", "requestId": requestID])
        let payload = String(decoding: data, as: UTF8.self)
        let generation = connectionGeneration
        if let rtcLink, rtcLink.isRtc { try await rtcLink.send(payload, channel: "control") }
        else { try await socket.send(.string(payload)) }
        guard connectionGeneration == generation, self.socket === socket else { throw CancellationError() }
        recordNetworkSent(payload)
    }

    @Published private(set) var connectionMethod = ""
    @Published private(set) var connectionPaths: [String: RtcSelectedPath] = [:]
    private var rtcLink: (any GatewayRtcConnection)?
    private var forceNextWebsocket = false
    private var upgradeAfterPairing = false

    @Published private(set) var serverPort: Int?
    @Published private(set) var clientCount: Int?

    var onFrame: ((GatewayFrame) -> Void)?
    /// 仅命令菜单需要原始 JSON；对话流不能触发它的解析和 UI 发布。
    var onCommandFrame: ((String) -> Void)?
    var onSpeechFrame: ((String) -> Void)?
    var onConnectionFailure: ((String) -> Void)?
    /// 传输生命周期与错误弹窗分离；静默恢复也必须废弃旧请求。
    var onConnectionInvalidated: (() -> Void)?
    var onConnectionCheckStarted: (() -> Void)?
    var onConnectionReady: (() -> Void)?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var connectionPreparationTask: Task<Void, Never>?
    private var connectionGeneration = UUID()
    private var reconnectTask: Task<Void, Never>?
    private var handshakeWatchdogTask: Task<Void, Never>?
    private var connectionTimeoutTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var foregroundProbeTask: Task<Void, Never>?
    private var foregroundProbeSequence = 0
    private var sessionCreationContinuations: [String: CheckedContinuation<String, Error>] = [:]

    func createSession(workspaceId: String?) async throws -> String {
        try Task.checkCancellation()
        guard state.isConnected else { throw URLError(.notConnectedToInternet) }
        let requestId = UUID().uuidString
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                sessionCreationContinuations[requestId] = continuation
                var payload: [String: Any] = ["type": "session-create", "requestId": requestId]
                if let workspaceId { payload["workspaceId"] = workspaceId }
                send(payload)
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(15))
                    self?.sessionCreationContinuations.removeValue(forKey: requestId)?
                        .resume(throwing: URLError(.timedOut))
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.sessionCreationContinuations.removeValue(forKey: requestId)?
                    .resume(throwing: CancellationError())
            }
        }
    }

    private func failSessionCreations() {
        let pending = sessionCreationContinuations
        sessionCreationContinuations.removeAll()
        for continuation in pending.values {
            continuation.resume(throwing: URLError(.networkConnectionLost))
        }
    }

    /// 迟到的创建响应也在此消费，避免修改已经离开的页面。
    func acceptSessionCreationFrame(_ frame: GatewayFrame) -> Bool {
        guard frame.kind == "session-created" ||
                (frame.kind == "error" && frame.requestType == "session-create") else { return false }
        guard let requestId = frame.requestId,
              let continuation = sessionCreationContinuations.removeValue(forKey: requestId) else { return true }
        if frame.kind == "session-created", let sessionId = frame.sessionId, !sessionId.isEmpty {
            continuation.resume(returning: sessionId)
        } else {
            continuation.resume(throwing: NSError(
                domain: "GatewaySessionCreation", code: 1,
                userInfo: [NSLocalizedDescriptionKey: frame.message ?? "创建会话失败"]
            ))
        }
        return true
    }

    var connectedEndpoint: String? { endpoint?.absoluteString }
    private var endpoint: URL?
    private var lastSuccessfulEndpoint: URL?
    /// 上一次连接失败的端点键（host:port 归一化）；手动重连据此轮开，避免反复撞同一堵墙（2026-11-11 真机案例）。
    private var lastFailedEndpointKey: String?
    private var lastSuccessfulConnectionWasRtc = false
    private var backgroundConnectionWasRtc = false
    private var foregroundFastRetryAvailable = false
    private var foregroundRtcRetryAvailable = false
    private var foregroundRtcTimeoutTask: Task<Void, Never>?
    private var foregroundRecoveryStartedAt: TimeInterval?
    private var wantsConnection = false
    private var pairingCode: String?
    /// 手动配对码是一次性的，失败后不能拿旧码在后台重复尝试。
    private var isManualPairingAttempt = false
    private var lastReportedFailure: String?
    /// iOS may suspend and tear down a normal WebSocket after the app moves
    /// into the background. Keep this lifecycle state separate from protocol
    /// failures so an expected transport interruption doesn't become a modal
    /// error when the app returns to the foreground.
    private var isApplicationInBackground = false
    private var isRecoveringFromBackground = false

    deinit {
        subscriptionTimeout?.cancel()
        networkPathMonitor?.cancel()
        outboundTask?.cancel()
        receiveTask?.cancel()
        connectionPreparationTask?.cancel()
        reconnectTask?.cancel()
        connectionTimeoutTask?.cancel()
        handshakeWatchdogTask?.cancel()
        foregroundProbeTask?.cancel()
        foregroundRtcTimeoutTask?.cancel()
        heartbeatTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        transportSession.invalidateAndCancel()
    }

    func connect(to rawEndpoint: String) {
        cancelSubscriptionRecovery()
        lastSuccessfulEndpoint = nil
        lastSuccessfulConnectionWasRtc = false
        backgroundConnectionWasRtc = false
        foregroundFastRetryAvailable = false
        foregroundRtcRetryAvailable = false
        foregroundRecoveryStartedAt = nil
        forceNextWebsocket = false
        isRecoveringFromBackground = false
        isManualPairingAttempt = false
        var target = rawEndpoint
        if let failed = lastFailedEndpointKey,
           let url = URL(string: target), Self.endpointKey(url) == failed,
           let rotated = Self.rotateAway(from: url, trusted: trustedEndpoints),
           Self.endpointKey(rotated) != failed {
            target = rotated.absoluteString
            traceLifecycle("manual-reconnect rotate away from last failure: \(url.host ?? "?") → \(rotated.host ?? "?")")
        }
        beginConnection(to: target, pairingCode: nil, resetReportedFailure: true)
    }

    /// Cold launch should only restore a connection for a device that has
    /// already completed pairing. Opening an unauthenticated socket merely to
    /// discover that pairing is required produces a misleading failure alert.
    func hasStoredCredential(for rawEndpoint: String) async -> Bool {
        guard let url = URL(string: rawEndpoint),
              ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            return false
        }
        return (try? await credentials.loadToken(for: credentialURL(url)))?.isEmpty == false
    }

    func connectForPairing(_ payload: GatewayPairingPayload) {
        cancelSubscriptionRecovery()
        lastSuccessfulEndpoint = nil
        lastSuccessfulConnectionWasRtc = false
        backgroundConnectionWasRtc = false
        foregroundFastRetryAvailable = false
        foregroundRtcRetryAvailable = false
        foregroundRecoveryStartedAt = nil
        isRecoveringFromBackground = false
        isManualPairingAttempt = true
        beginConnection(to: payload.publicUrl, pairingCode: payload.pairingCode, resetReportedFailure: true)
    }

    /// 只记录场景状态。连接的生命周期由用户的连接意图与真实传输故障决定；
    /// 进入后台本身不再主动关闭 WebSocket。
    func applicationDidEnterBackground() {
        networkRecovery.cancel()
        if !isApplicationInBackground { backgroundConnectionWasRtc = lastSuccessfulConnectionWasRtc }
        foregroundRtcTimeoutTask?.cancel()
        foregroundRtcTimeoutTask = nil
        // 保留在途订阅和最后一次选择，仅取消前台等待期限。
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        foregroundProbeTask?.cancel()
        foregroundProbeTask = nil
        foregroundProbeSequence += 1
        backgroundEnteredAt = Date()
        isApplicationInBackground = true
        traceLifecycle("enter-background")
        isRecoveringFromBackground = true
        conversationClient?.applicationDidEnterBackground()
    }

    /// Restores the transport immediately instead of waiting for the delayed
    /// reconnect loop. A successful `hello` clears recovery mode.
    func applicationDidBecomeActive() {
        traceLifecycle("become-active")
        let wasInBackground = isApplicationInBackground
        backgroundEnteredAt = nil
        isApplicationInBackground = false
        if wasInBackground { startSubscriptionTimeout() }
        guard wantsConnection, !isManualPairingAttempt, !probeOnly else { return }
        // 首次 active 通知可能晚于冷启动连接任务；不能打断正在进行的握手。
        guard wasInBackground || state != .connecting else { return }
        if wasInBackground {
            foregroundFastRetryAvailable = lastSuccessfulEndpoint != nil
            foregroundRtcRetryAvailable = backgroundConnectionWasRtc
            backgroundConnectionWasRtc = false
            foregroundRecoveryStartedAt = ProcessInfo.processInfo.systemUptime
        }
        reconnectAttempt = 0
        if state.isConnected {
            // 后台可能已经先恢复了 WS；保留进入后台时的 RTC 偏好。
            if wasInBackground, foregroundRtcRetryAvailable, rtcLink?.isRtc != true,
               let endpoint = lastSuccessfulEndpoint ?? endpoint, rtcPolicy(endpoint.absoluteString),
               expectedGatewayID != nil, channel == "control" {
                reconnectForegroundTransport(to: endpoint)
                return
            }
            conversationClient?.applicationDidBecomeActive()
            // 81：回到前台走快速探测线（1s 短超时 + miss 立即重探 + giveUp 跳退避）。
            // 两条通道各自独立收口：conversation 子客户端的 applicationDidBecomeActive
            // 会走到同一条快速线，控制通道 pong 成功不再掩盖 conversation 通道已死。
            probeForegroundConnection(quickTimeout: foregroundProbeTimeout)
            return
        }
        guard let endpoint = lastSuccessfulEndpoint ?? endpoint else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        if wasInBackground, lastSuccessfulEndpoint != nil {
            reconnectForegroundTransport(to: endpoint)
            return
        }
        beginConnection(
            to: endpoint.absoluteString,
            pairingCode: pairingCode,
            resetReportedFailure: false
        )
    }

    /// 前台恢复最多尝试一次新的 RTC；限时失败后立即改用 WS，避免循环协商。
    private func reconnectForegroundTransport(to endpoint: URL) {
        let retryRtc = foregroundRtcRetryAvailable && rtcPolicy(endpoint.absoluteString) &&
            expectedGatewayID != nil && channel == "control"
        foregroundRtcRetryAvailable = false
        forceNextWebsocket = !retryRtc
        if retryRtc {
            traceLifecycle("foreground-fast-reconnect transport=webrtc")
        } else {
            foregroundFastRetryAvailable = false
            traceLifecycle("foreground-fast-reconnect transport=websocket")
        }
        beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false,
                        rtcRecoveryTimeout: retryRtc ? foregroundRtcTimeout : nil)
    }

    private func beginConnection(to rawEndpoint: String, pairingCode: String?, resetReportedFailure: Bool,
                                 rtcRecoveryTimeout: Duration? = nil) {
        traceLifecycle("begin-connection")
        disconnectTransport(reconnect: false)
        guard let url = URL(string: rawEndpoint), ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            fail(String(localized: "二维码中的 publicUrl 不是有效的 ws:// 或 wss:// 地址"), shouldReconnect: false)
            return
        }
        if !trustedEndpoints.isEmpty && !trustedEndpoints.contains(url.absoluteString) {
            fail("地址未经此主机确认，请重新扫码添加地址。", shouldReconnect: false)
            return
        }
        wantsConnection = true
        endpoint = url
        self.pairingCode = pairingCode
        isManualPairingAttempt = pairingCode != nil
        startMonitoringNetworkChanges()
        if resetReportedFailure { lastReportedFailure = nil }
        state = .connecting
        var request = URLRequest(url: url)
        request.setValue(channel, forHTTPHeaderField: "X-DSH-Channel")
        request.setValue(Data(UIDevice.current.name.utf8).base64EncodedString(), forHTTPHeaderField: "X-DSH-Device-Name")
        let generation = connectionGeneration
        let credentialEndpoint = credentialURL(url)
        connectionPreparationTask = Task { [weak self, credentials] in
            let deviceID: String
            do {
                deviceID = try await credentials.loadOrCreateDeviceID()
            } catch {
                guard let self, !Task.isCancelled, self.connectionGeneration == generation else { return }
                self.fail(String(localized: "gateway.device-id.unavailable", defaultValue: "无法读取或创建设备唯一标识：\(error.localizedDescription)"),
                          shouldReconnect: self.canRetryCredentialAccess(error))
                return
            }
            guard !Task.isCancelled else { return }
            let token: String?
            do {
                token = pairingCode == nil ? try await credentials.loadToken(for: credentialEndpoint) : nil
            } catch {
                // 临时读取失败不是 token 缺失，不能发起匿名握手或删除有效凭据。
                guard let self, !Task.isCancelled, self.connectionGeneration == generation else { return }
                self.fail(error.localizedDescription, shouldReconnect: self.canRetryCredentialAccess(error))
                return
            }
            guard let self, !Task.isCancelled, self.connectionGeneration == generation,
                  self.wantsConnection else { return }
            request.setValue(deviceID, forHTTPHeaderField: "X-DSH-Device-ID")
            if let pairingCode {
                request.setValue("dsh-mobile-v1, dsh-pair.\(pairingCode)", forHTTPHeaderField: "Sec-WebSocket-Protocol")
            } else {
                if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
                request.setValue("dsh-mobile-v1", forHTTPHeaderField: "Sec-WebSocket-Protocol")
            }
            // 经内嵌节点的那条路：只把「连接端点」换成本地转发器（127.0.0.1:随机端口原样转发到目标）。
            // 逻辑端点不动——凭据、档案标识、日志里的 endpoint 全部还是用户填的那个；
            // 换掉的只是 socket 连去哪儿。WS 的握手与帧仍由 URLSession 做，我们不碰协议。
            let tunnelReady = await self.prepareTunnel(url.absoluteString)
            guard !Task.isCancelled, self.connectionGeneration == generation, self.wantsConnection else { return }
            guard tunnelReady else {
                self.fail(String(localized: "正在建立连接"), shouldReconnect: true, reportFailure: false)
                return
            }
            let forward = await self.tunnelForwarder(url.absoluteString)
            guard !Task.isCancelled, self.connectionGeneration == generation, self.wantsConnection else { return }
            if let forward, let local = NodeForwarding.connectURL(for: url, localPort: forward.localPort) {
                request.url = local
                TunnelDiagnostics.transport("route=node target=\(forward.target) local=\(forward.localPort)")
            }
            // 经节点时不试 RTC：WebRTC 用的是自己的 socket，绕过这个入口（安卓实测过它不会走节点）。
            let rtcAllowed = self.rtcPolicy(url.absoluteString) && forward == nil
            let negotiate = self.channel == "control" && !self.probeOnly && token?.isEmpty == false &&
                pairingCode == nil && self.expectedGatewayID != nil && !self.forceNextWebsocket && rtcAllowed
            if self.channel == "control", !self.probeOnly, !negotiate {
                let reason: String
                if forward != nil { reason = "node-websocket" }
                else if !rtcAllowed { reason = "lan-websocket" }
                else if pairingCode != nil { reason = "pairing" }
                else if token?.isEmpty != false { reason = "no-credential" }
                else if self.expectedGatewayID == nil { reason = "no-gateway-identity" }
                else { reason = "websocket-recovery" }
                GatewayRtcLog.write(RtcDiagnostics.shared.message(event: "upgrade-skipped", reason: reason,
                    phase: "connecting", channel: "control", mode: "websocket", elapsedMs: 0))
            }
            self.forceNextWebsocket = false
            if negotiate { request.setValue("negotiate-v1", forHTTPHeaderField: "X-DSH-Transport-Intent") }
            self.connectionPreparationTask = nil
            let socket = self.transportSession.webSocketTask(with: request)
            socket.maximumMessageSize = Self.maximumIncomingMessageSize
                self.socket = socket
            self.startHandshakeWatchdog(generation: generation, socket: socket)
            self.lastInboundFrameAt = Date()
            self.consecutiveProbeMisses = 0
            self.socket = socket
            if negotiate, let gatewayID = self.expectedGatewayID {
                self.rtcLink = GatewayRtcLink(expectedGatewayID: gatewayID, wsSend: { [weak self, weak socket] text in
                    guard let self, let socket, self.socket === socket else { throw URLError(.cancelled) }
                    try await socket.send(.string(text))
                }, deliver: { [weak self, weak socket] channel, data in
                    guard let self, let socket, self.socket === socket else { return }
                    if channel == "control" { await self.receiveBusinessData(data, socket: socket) }
                    else { await self.receiveRtcConversation(data, socket: socket) }
                }, selected: { [weak self, weak socket] method in
                    guard let self, let socket, self.socket === socket else { return }
                    self.connectionMethod = method
                }, pathChanged: { [weak self, weak socket] paths in
                    guard let self, let socket, self.socket === socket else { return }
                    self.connectionPaths = paths
                }, failure: { [weak self, weak socket] recoverable in
                    guard let self, let socket, self.socket === socket else { return }
                    self.forceNextWebsocket = recoverable
                    self.fail(recoverable ? "RTC 连接已中断，正在使用 WebSocket 重新连接。" : "RTC 身份或传输校验失败，请重新连接。",
                              shouldReconnect: recoverable)
                })
            }
            socket.resume()
            self.startConnectionTimeout(for: socket)
            if negotiate, let rtcRecoveryTimeout {
                self.foregroundRtcTimeoutTask = Task { [weak self, weak socket] in
                    do { try await Task.sleep(for: rtcRecoveryTimeout) } catch { return }
                    guard let self, let socket, self.socket === socket,
                          self.connectionGeneration == generation, !self.state.isConnected,
                          !self.isApplicationInBackground else { return }
                    self.traceLifecycle("foreground-rtc-recovery-timeout fallback=websocket")
                    self.fail("RTC 恢复超时，正在使用 WebSocket 恢复连接。", shouldReconnect: true, reportFailure: false)
                }
            }
            self.receiveTask = Task { [weak self] in await self?.receiveLoop(socket) }
        }
    }

    func disconnect(reconnect: Bool = false) {
        cancelSubscriptionRecovery()
        backgroundConnectionWasRtc = false
        foregroundRtcRetryAvailable = false
        foregroundFastRetryAvailable = false
        disconnectTransport(reconnect: reconnect)
    }

    private func disconnectTransport(reconnect: Bool) {
        foregroundRtcTimeoutTask?.cancel()
        foregroundRtcTimeoutTask = nil
        connectionGeneration = UUID()
        rtcLink?.close()
        rtcLink = nil
        connectionMethod = ""
        connectionPaths = [:]
        diagnosticsCapabilities.removeAll()
        upgradeAfterPairing = false
        connectionPreparationTask?.cancel()
        connectionPreparationTask = nil
        onConnectionInvalidated?()
        networkRecovery.cancel()
        resetSubscriptionRequests()
        traceLifecycle("explicit-disconnect reconnect=\(reconnect)")
        outboundTask?.cancel()
        outboundTask = nil
        pendingConversationPayloads.removeAll()
        pendingConversationBytes = 0
        conversationClient?.disconnect()
        conversationClient = nil
        failSessionCreations()
        wantsConnection = reconnect
        // 81 取证：显式断开若不带重连意图，同样关掉自动重连——留痕到事件级。
        if !reconnect { traceLifecycle("wants-connection-false source=explicit-disconnect") }
        reconnectTask?.cancel()
        reconnectTask = nil
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        handshakeWatchdogTask?.cancel()
        handshakeWatchdogTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        foregroundProbeTask?.cancel()
        foregroundProbeTask = nil
        foregroundProbeSequence += 1
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
        pairingCode = nil
        isManualPairingAttempt = false
        serverPort = nil
        clientCount = nil
        state = .disconnected
    }

    func ping() { send(["type": "ping"]) }

    func requestWorkspaces() { send(["type": "workspaces"]) }
    func requestSessions() { send(["type": "sessions"]) }
    func requestHost() { send(["type": "host"]) }
    func searchSessions(_ query: String) { send(["type": "search", "query": query]) }
    func requestDirectories(path: String? = nil) {
        var payload: [String: Any] = ["type": "directories"]
        if let path, !path.isEmpty { payload["path"] = path }
        send(payload)
    }
    func createDirectory(path: String, name: String) {
        send(["type": "directory-create", "path": path, "name": name])
    }
    func createWorkspace(path: String) { send(["type": "workspace-create", "path": path]) }
    func requestModels(sessionId: String? = nil) {
        var payload: [String: Any] = ["type": "models"]
        if let sessionId, !sessionId.isEmpty { payload["sessionId"] = sessionId }
        send(payload)
    }
    func requestProviders() {
        send(["type": "providers"])
    }
    func selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?) {
        var payload: [String: Any] = [
            "type": "select-model",
            "sessionId": sessionId,
            "provider": provider,
            "model": model
        ]
        if let reasoningEffort, !reasoningEffort.isEmpty { payload["reasoningEffort"] = reasoningEffort }
        send(payload)
    }
    func requestPermissionOptions(sessionId: String?) {
        var payload: [String: Any] = ["type": "permission-options"]
        if let sessionId, !sessionId.isEmpty { payload["sessionId"] = sessionId }
        send(payload)
    }
    func setPermission(sessionId: String, name: String) {
        send(["type": "permission", "sessionId": sessionId, "name": name])
    }
    func requestContextUsage(sessionId: String) {
        send(["type": "context-usage", "sessionId": sessionId])
    }
    func requestSessionStats(sessionId: String) {
        send(["type": "session-stats", "sessionId": sessionId])
    }
    func requestTasks(sessionId: String) {
        send(["type": "tasks", "sessionId": sessionId])
    }
    func requestScheduleCatalog() {
        send(["type": "schedule-catalog"])
    }
    func requestScheduleList(sessionId: String) {
        send(["type": "schedule-list", "sessionId": sessionId])
    }
    func requestScheduleHistory(sessionId: String, id: String, limit: Int, before: String? = nil) {
        var payload: [String: Any] = ["type": "schedule-history", "sessionId": sessionId, "id": id, "limit": limit]
        if let before { payload["before"] = before }
        send(payload)
    }
    func updateSchedule(
        sessionId: String, id: String, expected: JSONValue,
        title: String? = nil, prompt: String? = nil, change: JSONValue? = nil,
        requestId: String? = nil
    ) {
        guard let data = try? JSONEncoder().encode(expected),
              let expectedObject = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        var payload: [String: Any] = ["type": "schedule-update", "sessionId": sessionId, "id": id, "expected": expectedObject]
        if let title { payload["title"] = title }
        if let prompt { payload["prompt"] = prompt }
        if let change {
            guard let data = try? JSONEncoder().encode(change),
                  let changeObject = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            payload["change"] = changeObject
        }
        if let requestId { payload["requestId"] = requestId }
        send(payload)
    }
    func deleteSchedule(sessionId: String, id: String, requestId: String? = nil) {
        var payload: [String: Any] = ["type": "schedule-delete", "sessionId": sessionId, "id": id]
        if let requestId { payload["requestId"] = requestId }
        send(payload)
    }
    func requestGoal(sessionId: String) {
        send(["type": "goal", "sessionId": sessionId])
    }
    func editGoal(sessionId: String, ref: GatewayGoalReference, objective: String) {
        send([
            "type": "goal-edit",
            "sessionId": sessionId,
            "ref": ["id": ref.id, "revision": ref.revision],
            "objective": objective
        ])
    }
    func pauseGoal(sessionId: String, ref: GatewayGoalReference) {
        sendGoalAction("goal-pause", sessionId: sessionId, ref: ref)
    }
    func resumeGoal(sessionId: String, ref: GatewayGoalReference) {
        sendGoalAction("goal-resume", sessionId: sessionId, ref: ref)
    }
    func clearGoal(sessionId: String, ref: GatewayGoalReference) {
        sendGoalAction("goal-clear", sessionId: sessionId, ref: ref)
    }

    private func sendGoalAction(_ type: String, sessionId: String, ref: GatewayGoalReference) {
        send([
            "type": type,
            "sessionId": sessionId,
            "ref": ["id": ref.id, "revision": ref.revision]
        ])
    }
    func requestAgentPresets() {
        send(["type": "agent-presets"])
    }
    func requestDefaults() {
        send(["type": "defaults"])
    }
    func requestDefaultModel() {
        send(["type": "default-model"])
    }
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        var payload: [String: Any] = ["type": "save-default-model", "provider": provider, "model": model]
        if let reasoningEffort, !reasoningEffort.isEmpty { payload["reasoningEffort"] = reasoningEffort }
        send(payload)
    }
    func setDefault(target: String, value: String) {
        send(["type": "set-default", "target": target, "value": value])
    }
    func requestHistory(
        sessionId: String,
        beforeSeq: Int? = nil,
        maxMessages: Int = 50,
        maxBytes: Int? = nil,
        view: String? = nil,
        historyFormatVersion: Int? = nil
    ) {
        var payload: [String: Any] = ["type": "history", "sessionId": sessionId, "maxMessages": maxMessages]
        if let beforeSeq, let historyFormatVersion {
            guard beforeSeq >= 0 else { return }
            payload["beforeSeq"] = beforeSeq
            payload["historyFormatVersion"] = historyFormatVersion
        }
        if let maxBytes { payload["maxBytes"] = maxBytes }
        if let view { payload["view"] = view }
        send(payload)
    }

    func requestAttachment(sessionId: String, attachmentId: String) {
        send([
            "type": "attachment",
            "sessionId": sessionId,
            "attachmentId": attachmentId
        ])
    }

    func subscribe(sessionId: String?) {
        if desiredSubscriptionID != sessionId { subscriptionRecoveryAttempts = 0 }
        desiredSubscriptionID = sessionId
        if subscriptionPending { subscriptionDirty = true; return }
        subscriptionPending = true
        pendingSubscriptionID = sessionId
        startSubscriptionTimeout()
        gatewayApprovalTrace("transport subscribe hasSession=\(sessionId?.isEmpty == false)")
        if let sessionId, !sessionId.isEmpty {
            send(["type": "subscribe", "sessionId": sessionId, "assistantStream": true])
        } else {
            send(["type": "unsubscribe"])
        }
    }

    private func startSubscriptionTimeout() {
        guard subscriptionPending, !isApplicationInBackground else { return }
        let generation = UUID()
        subscriptionGeneration = generation
        let timeout = subscriptionTimeoutInterval
        subscriptionTimeout?.cancel()
        subscriptionTimeout = Task { [weak self] in
            do { try await Task.sleep(for: timeout) } catch { return }
            guard let self, !Task.isCancelled, self.subscriptionPending,
                  self.subscriptionGeneration == generation else { return }
            let target = self.pendingSubscriptionID
            let superseded = self.subscriptionDirty && target != self.desiredSubscriptionID
            let latest = self.desiredSubscriptionID
            self.traceLifecycle("subscription-timeout superseded=\(superseded) hasSession=\(target?.isEmpty == false)")
            self.resetSubscriptionRequests()
            // **不再弹模态**：用户明确说这个"经常弹"、打断正在看的页面。
            // 超时只留痕 + 静默重订阅一次；真的没有回包时，界面行为与"还没订阅上"一致，
            // 比一个遮住屏幕的弹窗更接近真实（拿不到确认时降级成安静的真实）。
            gatewayApprovalTrace("subscribe-timeout-silent-retry target=\(target ?? "nil")")
            if let target, !target.isEmpty {
                self.subscribe(sessionId: target)
            }
        }
    }

    func archiveSession(sessionId: String) {
        send(["type": "session-archive", "sessionId": sessionId])
    }

    func renameSession(sessionId: String, title: String) {
        send(["type": "session-rename", "sessionId": sessionId, "title": title])
    }

    func cancelSession(sessionId: String) {
        send(["type": "session-cancel", "sessionId": sessionId])
    }

    /// 在 UI 清空草稿前决定承载。给 JSON 转义和协议元数据保留上界，避免编码大型 Base64 阻塞主线程。
    func prepareMessageTransport(text: String, images: [GatewayOutgoingImage]) -> Bool {
        guard let rtcLink, rtcLink.isRtc else { return true }
        var upperBound = 65536
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0...31: upperBound += 6
            case 34, 47, 92: upperBound += 2
            default: upperBound += scalar.utf8.count
            }
        }
        for image in images { upperBound += ((image.data.count + 2) / 3) * 8 + (image.name?.utf8.count ?? 0) * 6 }
        guard upperBound > rtcLink.maximumMessageBytes else { return true }
        GatewayRtcLog.write(RtcDiagnostics.shared.message(event: "fallback-requested", reason: "message-too-large",
            phase: "active", channel: "group", mode: "webrtc", elapsedMs: rtcLink.elapsedMilliseconds))
        // 确认尚未发送，换新 owner；由用户保留草稿并重新提交，不重放任何写操作。
        forceNextWebsocket = true
        if let endpoint { beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false) }
        return false
    }

    func sendMessage(
        text: String,
        images: [GatewayOutgoingImage] = [],
        receipts: [String] = [],
        sessionId: String?,
        workspaceId: String? = nil,
        mode: String = "queue",
        requestId: String? = nil,
        textParts: [GatewayTextPart]? = nil
    ) {
        let address = sessionId.flatMap { conversationTarget?($0) }
        let request = GatewayMessageRequest(
            sessionId: sessionId?.isEmpty == false ? sessionId : nil,
            parentSessionId: address?.parentSessionId,
            subagentMode: address?.subagentMode,
            text: textParts == nil ? text : nil,
            textParts: textParts,
            images: images.map {
                GatewayMessageRequest.Image(
                    mediaType: $0.mediaType,
                    data: $0.data,
                    name: $0.name
                )
            },
            files: receipts.isEmpty ? nil : receipts.map { GatewayMessageRequest.File(receiptId: $0) },
            workspaceId: sessionId == nil && workspaceId?.isEmpty == false ? workspaceId : nil,
            clientTimeZone: TimeZone.current.identifier,
            mode: mode,
            requestId: requestId
        )
        let generation = connectionGeneration
        Task { [weak self] in
            do {
                let payload = try await Task.detached(priority: .userInitiated) {
                    try JSONEncoder().encode(request)
                }.value
                if let self, self.connectionGeneration == generation, self.state.isConnected {
                    // 图片 Base64 已在后台编码；直接使用固定地址，不在主线程重新解析整条消息。
                    let client = self.conversationClient ?? self
                    client.sendRequestPayload(String(decoding: payload, as: UTF8.self), decorate: false)
                }
            } catch {
                guard let self, !Task.isCancelled, self.connectionGeneration == generation else { return }
                await self.handleFailure(error)
            }
        }
    }

    func executeCommand(
        line: String,
        images: [GatewayOutgoingImage] = [],
        sessionId: String?
    ) {
        guard let sessionId, !sessionId.isEmpty else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
        let request = GatewayCommandExecuteRequest(
            sessionId: sessionId,
            line: line,
            images: images.map {
                GatewayMessageRequest.Image(mediaType: $0.mediaType, data: $0.data, name: $0.name)
            }
        )
        Task { [weak self] in
            guard let socket = self?.socket else { return }
            do {
                let payload = try await Task.detached(priority: .userInitiated) {
                    try JSONEncoder().encode(request)
                }.value
                guard self?.socket === socket else { return }
                self?.sendRequestPayload(String(decoding: payload, as: UTF8.self))
            } catch {
                await self?.handleFailure(error, socket: socket)
            }
        }
    }

    func answerQuestion(rpcId: String, sessionId: String, answers: [GatewayQuestionAnswer]) {
        let encodedAnswers: [[String: Any]] = answers.map { answer in
            var value: [String: Any] = [
                "id": answer.id,
                "selected": answer.selected
            ]
            if let custom = answer.custom { value["custom"] = custom }
            return value
        }
        send([
            "type": "question-answer",
            "rpcId": rpcId,
            "sessionId": sessionId,
            "answers": encodedAnswers
        ])
    }

    func cancelQuestion(rpcId: String, sessionId: String) {
        send([
            "type": "question-cancel",
            "rpcId": rpcId,
            "sessionId": sessionId
        ])
    }

    func respondToApproval(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: GatewayApprovalOutcome
    ) {
        send([
            "type": "approval-response",
            "rpcId": rpcId,
            "sessionId": sessionId,
            "approvalId": approvalId,
            "outcome": outcome.rawValue
        ])
    }

    /// KMP 文件状态机已经生成并校验过的协议请求。平台 transport 只负责发送。
    func sendRequestPayload(_ originalPayload: String, decorate: Bool = true) {
        let payload: String
        if decorate, let requestDecorator {
            guard let decorated = requestDecorator(originalPayload) else {
                // 文件预览不能静默丢弃，否则状态机会一直停在 0%。
                if let object = try? JSONSerialization.jsonObject(with: Data(originalPayload.utf8)) as? [String: Any],
                   let type = object["type"] as? String, type.hasPrefix("file-") {
                    onFrame?(GatewayFrame(kind: "error", sessionId: object["sessionId"] as? String,
                        code: "file-workspace-unavailable", message: "无法确定文件所属工作区，请重新打开会话后重试。",
                        requestType: type, requestId: object["requestId"] as? String,
                        transferId: object["transferId"] as? String))
                }
                return
            }
            payload = decorated
        } else { payload = originalPayload }

#if DEBUG
        if let testRequestTransport {
            if state.isConnected { testRequestTransport(payload) }
            return
        }
#endif
        if let rtcLink, rtcLink.isRtc {
            let type = (try? JSONDecoder().decode(RequestEnvelope.self, from: Data(payload.utf8)).type) ?? ""
            let target = Self.usesConversationChannel(type) ? "conversation" : "control"
            Task { [weak self, weak rtcLink] in
                guard let self, let rtcLink, self.rtcLink === rtcLink else { return }
                do {
                    try await rtcLink.send(payload, channel: target)
                    if self.rtcLink === rtcLink { self.recordNetworkSent(payload) }
                }
                catch GatewayMgwCodec.Failure.tooLarge {
                    guard !Task.isCancelled, self.rtcLink === rtcLink else { return }
                    let envelope = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
                    self.onFrame?(GatewayFrame(kind: "error", code: "transport/ws-required",
                        message: "消息超过 RTC 传输上限，请使用 WebSocket 连接后重新提交。", requestType: type,
                        requestId: envelope?["requestId"] as? String))
                } catch {
                    guard self.rtcLink === rtcLink else { return }
                    self.forceNextWebsocket = true
                    self.fail("RTC 发送中断，请确认操作状态后重试。", shouldReconnect: true)
                }
            }
            return
        }
        if let conversationClient,
           let type = try? JSONDecoder().decode(RequestEnvelope.self, from: Data(payload.utf8)).type,
           Self.usesConversationChannel(type) {
            conversationClient.sendRequestPayload(payload)
            return
        }
        // 凭据读取也属于会话通道的连接准备阶段，此时 socket 尚未创建。
        // 必须先入队，否则重连后的首个订阅会被静默丢弃，留下绿灯和订阅超时。
        if deferUntilConversationHello(payload) { return }
        guard let socket else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
        write(payload, to: socket)
    }

    private func send(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        sendRequestPayload(String(decoding: data, as: UTF8.self))
    }

    /// 每条通道按提交顺序发送；hello 前的缓冲不会被后来的写操作插队。
    private func write(_ payload: String, to socket: URLSessionWebSocketTask, logsPresets: Bool = false) {
        let previous = outboundTask
        outboundTask = Task { [weak self] in
            await previous?.value
            guard let self, !Task.isCancelled, self.socket === socket else { return }
            do {
                if logsPresets { Self.presetLogger.info("send started") }
                try await socket.send(.string(payload))
                if self.socket === socket { self.recordNetworkSent(payload) }
                if logsPresets { Self.presetLogger.info("send completed") }
            } catch { await self.handleFailure(error, socket: socket) }
        }
    }

    private func deferUntilConversationHello(_ payload: String) -> Bool {
        guard channel == "conversation", wantsConnection, state == .connecting else { return false }
        let bytes = payload.utf8.count
        guard pendingConversationPayloads.count < 64,
              pendingConversationBytes + bytes <= 16 * 1024 * 1024 else {
            fail("会话通道尚未就绪，请稍后重试。", shouldReconnect: false)
            return true
        }
        pendingConversationPayloads.append(payload)
        pendingConversationBytes += bytes
        traceLifecycle("conversation-request-buffered socketReady=\(socket != nil) count=\(pendingConversationPayloads.count)")
        return true
    }

    nonisolated static func usesConversationChannel(_ requestType: String) -> Bool {
        ["message", "history", "subscribe", "unsubscribe"].contains(requestType)
    }

    private struct RequestEnvelope: Decodable {
        let type: String
    }

    // 解复用只负责传输格式；ASR 协议字段与状态由公共层处理。
    nonisolated static func decodeIncomingFrame(_ data: Data) throws -> (GatewayFrame?, String?) {
        struct SpeechEnvelope: Decodable { let kind: String?; let requestType: String? }
        let header = try? JSONDecoder().decode(SpeechEnvelope.self, from: data)
        if header?.kind?.hasPrefix("speech-") == true ||
            (header?.kind == "error" && header?.requestType?.hasPrefix("speech-") == true) {
            return (nil, String(decoding: data, as: UTF8.self))
        }
        return (try GatewayWireDecoder.decode(data), nil)
    }

    private func receiveLoop(_ socket: URLSessionWebSocketTask) async {
        let generation = connectionGeneration
        do {
            while !Task.isCancelled {
                let message = try await socket.receive()
                // 收到任何一条消息就证明链路是活的：刷新时间戳并清零连续失败计数。
                lastInboundFrameAt = Date()
                consecutiveProbeMisses = 0
                let data: Data
                switch message {
                case .string(let text): data = Data(text.utf8)
                case .data(let payload): data = payload
                @unknown default: continue
                }
#if DEBUG
                await receivedFrameBarrierForTesting?(data)
#endif
                // receive 的等待可以跨越 WS/RTC 切换；旧帧不能交给新连接的状态机。
                guard !Task.isCancelled, self.socket === socket, connectionGeneration == generation else { return }
                if let rtcLink { try await rtcLink.webSocket(data) }
                else { await receiveBusinessData(data, socket: socket) }
            }
        } catch is CancellationError {
            return
        } catch {
            await handleFailure(error, socket: socket)
        }
    }

    private func decodeBusinessFrame(_ data: Data) async throws -> (GatewayFrame?, String?) {
#if DEBUG
        if let incomingFrameDecoderForTesting { return try await incomingFrameDecoderForTesting(data) }
#endif
        return try await Task.detached(priority: .userInitiated) { try Self.decodeIncomingFrame(data) }.value
    }

    private func receiveBusinessData(_ data: Data, socket: URLSessionWebSocketTask) async {
        guard self.socket === socket else { return }
        recordNetworkTraffic(received: data.count)
        do {
            // 语音帧使用独立公共解码器，所有 JSON 扫描仍在后台完成。
            let decoded = try await decodeBusinessFrame(data)
            guard !Task.isCancelled, self.socket === socket else { return }
            if let speech = decoded.1 {
                if state.isConnected { onSpeechFrame?(speech) }
                return
            }
            guard let frame = decoded.0 else { return }
            if frame.kind == "pong", let requestID = frame.requestId, requestID.hasPrefix("network-check:") {
                for observer in networkObservers.values { observer.pong(requestID) }
                return
            }
            if frame.kind.hasPrefix("approval") {
                gatewayApprovalTrace(
                    "transport received kind=\(frame.kind) hasRpc=\(frame.rpcId?.isEmpty == false) " +
                    "hasSession=\(frame.sessionId?.isEmpty == false) " +
                    "hasApprovalId=\(frame.approvalId?.isEmpty == false) " +
                    "hasTool=\(frame.toolName?.isEmpty == false) replay=\(frame.replay == true)"
                )
            }
            if frame.kind == "hello", isManualPairingAttempt, pairingCode != nil {
                fail("配对未返回长期凭据，请重新生成二维码。", shouldReconnect: false)
                return
            }
            if frame.kind == "paired" || frame.kind == "hello" {
                do {
                    try GatewayIdentity.validate(expected: expectedGatewayID, received: frame.gatewayId)
                    // 身份回调必须先校验冲突，再写入凭证或分发业务帧。
                    try onIdentity?(frame, endpoint?.absoluteString ?? "")
                    if let id = frame.gatewayId { expectedGatewayID = id.lowercased() }
                } catch {
                    fail(error.localizedDescription, shouldReconnect: false)
                    return
                }
            } else if !state.isConnected {
                return
            }
            if frame.kind == "paired" {
                guard let endpoint, let token = frame.token, !token.isEmpty else {
                    fail(String(localized: "pairing.response.missing-token", defaultValue: "配对响应缺少长期设备 token，未保存凭据"), shouldReconnect: false)
                    return
                }
                do {
                    try await credentials.saveToken(token, for: credentialURL(endpoint))
                    guard !Task.isCancelled, self.socket === socket else { return }
                    pairingCode = nil
                    upgradeAfterPairing = channel == "control" && !probeOnly && expectedGatewayID != nil && rtcPolicy(endpoint.absoluteString)
                    // 一次性配对已完成；hello 前断线也应使用长期 token 自动恢复。
                    isManualPairingAttempt = false
                    startMonitoringNetworkChanges()
                } catch {
                    guard !Task.isCancelled, self.socket === socket else { return }
                    fail(String(localized: "pairing.token.keychain-failed", defaultValue: "配对成功，但无法将设备 token 写入 Keychain：\(error.localizedDescription)"), shouldReconnect: false)
                    return
                }
            } else if frame.kind == "hello" {
                diagnosticsCapabilities = Set(frame.capabilities ?? [])
                if upgradeAfterPairing, let endpoint {
                    upgradeAfterPairing = false
                    beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false)
                    return
                }
                if channel == "control", !probeOnly, rtcLink?.isRtc != true, frame.capabilities?.contains("split-channels") == true {
                    openConversationChannel()
                }
                // `hello` is the protocol's authentication boundary.
                // Debug mode may explicitly return authenticated=false.
                connectionTimeoutTask?.cancel()
                connectionTimeoutTask = nil
                foregroundRtcTimeoutTask?.cancel()
                foregroundRtcTimeoutTask = nil
                isManualPairingAttempt = false
                startMonitoringNetworkChanges()
                reconnectAttempt = 0
                handshakeWatchdogTask?.cancel()
                handshakeWatchdogTask = nil
                lastSuccessfulEndpoint = endpoint
                lastFailedEndpointKey = nil
                lastSuccessfulConnectionWasRtc = rtcLink?.isRtc == true
                foregroundFastRetryAvailable = false
                foregroundRtcRetryAvailable = false
                if let started = foregroundRecoveryStartedAt {
                    let elapsed = Int((ProcessInfo.processInfo.systemUptime - started) * 1000)
                    traceLifecycle("foreground-recovered elapsedMs=\(elapsed)")
                    foregroundRecoveryStartedAt = nil
                }
                if connectionMethod.isEmpty { connectionMethod = "WebSocket" }
                state = .connected
                onConnectionReady?()
                traceLifecycle("hello-connected")
                startHeartbeat(for: socket)
                lastReportedFailure = nil
                isRecoveringFromBackground = false
                serverPort = frame.port
                clientCount = frame.clients
                let pending = pendingConversationPayloads
                pendingConversationPayloads.removeAll()
                pendingConversationBytes = 0
                for payload in pending { sendRequestPayload(payload) }
            }
            deliverApplicationFrame(frame, data: data)
        } catch {
            guard !Task.isCancelled, self.socket === socket else { return }
            // One future or malformed frame must not tear down an otherwise healthy socket.
            onFrame?(GatewayFrame(kind: "error", code: "decode-failed", message: GatewayWireDecoder.failureDescription(error)))
        }
    }


    private func receiveRtcConversation(_ data: Data, socket: URLSessionWebSocketTask) async {
        guard self.socket === socket else { return }
        recordNetworkTraffic(received: data.count)
        do {
            let decoded = try await decodeBusinessFrame(data)
            guard !Task.isCancelled, self.socket === socket, state.isConnected else { return }
            if let speech = decoded.1 { onSpeechFrame?(speech); return }
            guard let frame = decoded.0, frame.kind != "hello", frame.kind != "paired" else { return }
            deliverApplicationFrame(frame, data: data)
        } catch {
            guard !Task.isCancelled, self.socket === socket else { return }
            forceNextWebsocket = true
            fail("RTC 业务消息格式校验失败。", shouldReconnect: false)
        }
    }

    private func openConversationChannel() {
        guard conversationClient == nil, let endpoint else { return }
        let client = GatewayClient(channel: "conversation", credentials: credentials,
                                   heartbeatInterval: heartbeatInterval, probeTimeout: probeTimeout,
                                   prepareTunnel: prepareTunnel, tunnelForwarder: tunnelForwarder)
        client.credentialID = credentialID
        client.expectedGatewayID = expectedGatewayID
        client.trustedEndpoints = trustedEndpoints
        conversationClient = client
        client.networkTrafficObserved = { [weak self] in self?.networkObservers.isEmpty == false }
        client.forwardNetworkTraffic = { [weak self] sent, received in self?.recordNetworkTraffic(sent: sent, received: received) }
        client.onFrame = { [weak self, weak client] frame in
            guard let self, self.conversationClient === client,
                  frame.kind != "hello", frame.kind != "paired" else { return }
            self.onFrame?(frame)
        }
        client.onTransportFailure = { [weak self, weak client] detail, shouldReconnect in
            guard let self, let client, self.conversationClient === client else { return }
            // A transient failure on the split conversation socket must restore
            // both channels. Keep non-recoverable authentication failures terminal.
            self.fail(
                detail,
                shouldReconnect: shouldReconnect,
                reportFailure: !(self.isApplicationInBackground || self.isRecoveringFromBackground)
            )
        }
        // 长期凭据已在控制连接 paired 帧中保存，绝不重复使用一次性配对码。
        if isApplicationInBackground { client.applicationDidEnterBackground() }
        client.connect(to: endpoint.absoluteString)
    }

    func deliverApplicationFrame(_ frame: GatewayFrame, data: Data) {
        if frame.kind == "agent-presets" || frame.requestType == "agent-presets" {
            Self.presetLogger.info("received decoded response kind=\(frame.kind) bytes=\(data.count)")
        }
        switch frame.kind {
        case "commands", "command-options", "command-selected":
            onCommandFrame?(String(decoding: data, as: UTF8.self))
        case "error" where ["commands", "command-options", "command-select"].contains(frame.requestType):
            onCommandFrame?(String(decoding: data, as: UTF8.self))
        default:
            break
        }
        if !acceptSessionCreationFrame(frame) { onFrame?(frame) }
    }

    private func handleFailure(_ error: Error, socket: URLSessionWebSocketTask? = nil, immediateReconnect: Bool = false) async {
        guard wantsConnection else { return }
        let nsError = error as NSError
        let isCurrentSocket = socket == nil || socket === self.socket
        traceLifecycle("transport-failure domain=\(nsError.domain) code=\(nsError.code) closeCode=\(socket?.closeCode.rawValue ?? -1) httpStatus=\(Self.httpResponse(from: socket, error: nsError)?.statusCode ?? -1) currentSocket=\(isCurrentSocket) hasCloseReason=\(socket?.closeReason != nil)")
        // `beginConnection` deliberately cancels the previous task before it
        // installs the replacement. Its receive loop can finish one actor turn
        // later, after `wantsConnection` has become true again. Never let that
        // stale cancellation overwrite the new connection or surface as a
        // user-facing "连接失败" alert.
        if let socket, socket !== self.socket { return }
        // 旧 socket 已由上面的身份检查排除；当前 socket 的系统取消也属于真实断线。
        let statusCode = Self.httpResponse(from: socket, error: nsError)?.statusCode
        let closeCode = socket?.closeCode.rawValue
        let closeReason = socket?.closeReason.flatMap { String(data: $0, encoding: .utf8) }
        let detail: String
        let shouldReconnect: Bool
        let shouldReportFailure: Bool
        switch (statusCode, closeCode) {
        case (401, _):
            if pairingCode == nil, let endpoint,
               socket?.originalRequest?.value(forHTTPHeaderField: "Authorization") != nil {
                let generation = connectionGeneration
                await credentials.deleteToken(for: credentialURL(endpoint))
                guard generation == connectionGeneration, socket === self.socket else { return }
            }
            detail = String(localized: "auth.failed.401", defaultValue: "鉴权失败（HTTP 401）：设备 token 无效、已被吊销，或配对码已过期/使用过，请重新扫码配对。")
            shouldReconnect = false
            shouldReportFailure = true
        case (503, _):
            detail = String(localized: "gateway.503.disabled", defaultValue: "移动网关暂未开启（HTTP 503）。已保留设备凭据，开启网关后会自动重连。")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        case (_, 4003):
            detail = String(localized: "auth.reenabled.4003", defaultValue: "服务端已重新开启设备鉴权（WebSocket 4003），请使用已保存的设备凭据重连或重新扫码。")
            shouldReconnect = false
            shouldReportFailure = true
        case (_, 4004):
            detail = String(localized: "gateway.closed.4004", defaultValue: "移动网关已关闭（WebSocket 4004）。已保留设备凭据，重新开启后会自动重连。")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        default:
            let reasonSuffix = closeReason.flatMap { $0.isEmpty ? nil : String(localized: "close.server-reason.suffix", defaultValue: "；服务端原因：\($0)") } ?? ""
            let connectionFailure = String(
                localized: "websocket.connect-failed.detail",
                defaultValue: "WebSocket 连接失败：\(nsError.localizedDescription)（\(nsError.domain) \(nsError.code)）\(reasonSuffix)"
            )
            detail = connectionFailure + (Self.certificateTrustAdvice(for: nsError).map { "\n\n\($0)" } ?? "")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        }
        fail(
            detail,
            shouldReconnect: shouldReconnect && !isManualPairingAttempt,
            reportFailure: shouldReportFailure,
            immediateReconnect: immediateReconnect
        )
    }

    private func canRetryCredentialAccess(_ error: Error) -> Bool {
        (error as? GatewayKeychainError)?.isTemporarilyUnavailable == true &&
            !isManualPairingAttempt && !probeOnly
    }

    static func certificateTrustAdvice(for error: NSError) -> String? {
        guard error.domain == NSURLErrorDomain else { return nil }
        switch error.code {
        case NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted,
             NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid:
            return String(
                localized: "gateway.certificate-trust.advice",
                defaultValue: "如果确认这是安全的个人服务器，请在网关中更新公网配置，并确保其公网证书有效后重试。"
            )
        default:
            return nil
        }
    }

    /// - Parameter immediateReconnect: 81 前台恢复线专用：giveUp 后**跳过 2s 退避**直接重连。
    ///   只由「回到前台」的快速探测（两次 1s miss）触发；become-active 已把
    ///   `reconnectAttempt` 清 0，本就不是「反复失败」的语境。网络真断时后续失败
    ///   走常规退避链（2s→4s→8s…封顶 30s），不会形成紧密循环。
    private func fail(_ detail: String, shouldReconnect: Bool, reportFailure: Bool = true, immediateReconnect: Bool = false) {
        foregroundRtcTimeoutTask?.cancel()
        foregroundRtcTimeoutTask = nil
        if let rtcLink, rtcLink.isRtc {
            forceNextWebsocket = true
            GatewayRtcLog.write(RtcDiagnostics.shared.message(event: shouldReconnect ? "fallback-requested" : "blocked",
                reason: "connection-lost", phase: "active", channel: channel, mode: "webrtc", elapsedMs: rtcLink.elapsedMilliseconds))
        }
        connectionGeneration = UUID()
        rtcLink?.close()
        rtcLink = nil
        connectionMethod = ""
        connectionPaths = [:]
        diagnosticsCapabilities.removeAll()
        upgradeAfterPairing = false
        connectionPreparationTask?.cancel()
        connectionPreparationTask = nil
        traceLifecycle("failure-cleanup reconnect=\(shouldReconnect) report=\(reportFailure)")
        lastFailedEndpointKey = endpoint.map(Self.endpointKey)
        onConnectionInvalidated?()
        resetSubscriptionRequests()
        outboundTask?.cancel()
        outboundTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        foregroundProbeTask?.cancel()
        foregroundProbeTask = nil
        foregroundProbeSequence += 1
        pendingConversationPayloads.removeAll()
        pendingConversationBytes = 0
        conversationClient?.disconnect()
        conversationClient = nil
        failSessionCreations()
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        state = .failed(detail)
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        receiveTask?.cancel()
        receiveTask = nil
        if !shouldReconnect {
            // 81 取证：wantsConnection 被关掉的每个落点都要留痕（become-active 的
            // guard 会在此标志为 false 时直接 return、零动作——用户报「切回不自动重连」
            // 的首要嫌疑）。只记来源标记，不写 detail（可能是服务端原因的转述）。
            traceLifecycle("wants-connection-false source=fail")
            wantsConnection = false
        }
        onTransportFailure?(detail, shouldReconnect)
        if reportFailure, lastReportedFailure != detail {
            lastReportedFailure = detail
            onConnectionFailure?(detail)
        }
        reconnectTask?.cancel()
        guard shouldReconnect, channel == "control" else { return }
        guard !probeOnly else { wantsConnection = false; return }
        if foregroundFastRetryAvailable, !isApplicationInBackground, let endpoint = lastSuccessfulEndpoint ?? endpoint {
            reconnectTask = Task { [weak self] in
                guard let self, !Task.isCancelled, self.wantsConnection else { return }
                self.reconnectForegroundTransport(to: endpoint)
            }
            return
        }
        reconnectAttempt += 1
        let delay = Self.reconnectDelaySeconds(
            attempt: reconnectAttempt, immediateReconnect: immediateReconnect)
        traceLifecycle("schedule-reconnect attempt=\(reconnectAttempt) delaySeconds=\(delay)\(immediateReconnect ? " mode=quick" : "")")
        reconnectTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            guard let self, !Task.isCancelled, self.wantsConnection,
                  let current = self.endpoint else { return }
            // P0 重连慢修复（2026-11-12）：首个退避 attempt 先回最后成功地址，再失败才轮换
            let target = Self.reconnectTarget(
                current: current,
                lastSuccessful: self.lastSuccessfulEndpoint,
                attempt: reconnectAttempt,
                trusted: self.trustedEndpoints)
            if let fromHost = URL(string: current.absoluteString)?.host,
               let toHost = URL(string: target.absoluteString)?.host, fromHost != toHost {
                traceLifecycle("reconnect-rotate attempt=\(reconnectAttempt) from-host=\(fromHost) to-host=\(toHost)")
            }
            self.beginConnection(
                to: target.absoluteString,
                pairingCode: self.pairingCode,
                resetReportedFailure: false
            )
        }
    }

    /// 握手看门狗：10s 内没完成握手（state 仍 .connecting）就掐 socket，走正常 fail→重连
    /// （配合 reconnectTarget：preferred 失败后下一 attempt 正常轮换，不钉死）。
    private func startHandshakeWatchdog(generation: UUID, socket: URLSessionWebSocketTask) {
        handshakeWatchdogTask?.cancel()
        handshakeWatchdogTask = Task { [weak self, weak socket] in
            try? await Task.sleep(for: Self.handshakeWatchdogTimeout)
            guard !Task.isCancelled, let self, let socket,
                  self.connectionGeneration == generation, self.socket === socket,
                  self.state == .connecting else { return }
            traceLifecycle("handshake-watchdog-fire timeoutSeconds=\(Int(Self.handshakeWatchdogTimeout.components.seconds))")
            socket.cancel(with: .goingAway, reason: nil)
        }
    }

    private func startMonitoringNetworkChanges() {
        // 对话通道由控制通道统一恢复；临时探测和一次性配对不订阅网络变化。
        guard channel == "control", !probeOnly, !isManualPairingAttempt,
              networkPathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        networkPathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let snapshot = GatewayNetworkRecovery.Path(path)
            Task { @MainActor [weak self] in self?.networkRecovery.update(snapshot) }
        }
        monitor.start(queue: DispatchQueue(label: "ai.dsh.mobile.gateway-network"))
    }

    private func recoverNetworkConnection() {
        traceLifecycle("network-path-recovery")
        // RTC 协商本身会带来多次路径通知；正在连接时保留原有超时，避免不断推倒重来。
        guard state != .connecting else {
            traceLifecycle("network-path-recovery-deferred reason=handshake-in-progress")
            return
        }
        reconnectAttempt = 0
        if state.isConnected {
            // 两条 socket 都可能停留在旧网络，即使控制通道的 ping 已成功。
            probeForegroundConnection()
            conversationClient?.probeForegroundConnection()
        } else if let endpoint {
            reconnectTask?.cancel()
            reconnectTask = nil
            beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false)
        }
    }

    /// URLSession 可能尚未报告后台或网络切换断线；以 ping 验证连接，超时则自动恢复。
    ///
    /// 2026-10-26 断连风暴的三条防御都在这里（真机反馈「一直在断连重连」，网关侧 307 次断开）：
    /// 1. **判活也认数据**：超时窗内收到过任何帧就算活着——UI 忙时 pong 回调会被推后，
    ///    只看 pong 会把健康连接判死（这是风暴的主因）；
    /// 2. **连续两次失败才拆**：一次迟到不再等于死亡；
    /// 3. **期限**：日常心跳线 10s（原 5s）不变；「回到前台」线用 1s 短超时（81）。
    ///    结论都由纯函数 `probeVerdict` 给出，可单测。
    /// 观测点：日志里会看到 `probe-skipped-by-inbound-frame` / `probe-miss consecutive=N`
    /// / `foreground-probe-timeout consecutive=N`，真机上能确认防御是否真的生效。
    /// - Parameters:
    ///   - quickTimeout: 传值 = 「回到前台」快速线（81）：短超时、miss 立即重探、giveUp 跳过退避。
    ///     传 nil = 日常心跳线，行为与 2026-10-26 修复版完全一致（风暴防线所在，不许动）。
    ///   - immediateRetry: 快速线第二次探测标记；仅影响日志措辞与重连退避豁免。
    /// 供 AppStore 的在途请求活性探测复用（2026-11-12 第三补丁）：原 private，
    /// 按需快探是跨组件的受控入口（仅 quick 探测，不改心跳节奏）。
    func probeForegroundConnection(quickTimeout: Duration? = nil, immediateRetry: Bool = false) {
        guard let socket, state.isConnected, foregroundProbeTask == nil else { return }
        onConnectionCheckStarted?()
        foregroundProbeSequence += 1
        let sequence = foregroundProbeSequence
        let probeStartedAt = Date()
        let timeout = quickTimeout ?? probeTimeout
        traceLifecycle(immediateRetry ? "foreground-probe-ping mode=quick-retry" : "foreground-probe-ping")
        if let rtcLink, rtcLink.isRtc {
            traceLifecycle("foreground-rtc-probe-ping channels=control,conversation")
            foregroundProbeTask = Task { [weak self, weak socket] in
                do {
                    try await rtcLink.checkHealth(timeout: min(self?.probeTimeout ?? .seconds(2), .seconds(2)))
                    guard let self, let socket, self.socket === socket,
                          !self.isApplicationInBackground, self.foregroundProbeSequence == sequence else { return }
                    self.foregroundProbeTask = nil
                    self.foregroundFastRetryAvailable = false
                    self.foregroundRtcRetryAvailable = false
                    self.foregroundRecoveryStartedAt = nil
                    self.isRecoveringFromBackground = false
                    self.traceLifecycle("foreground-rtc-probe-pong channels=control,conversation")
                    self.onConnectionReady?()
                } catch {
                    guard !Task.isCancelled, let self, let socket, self.socket === socket,
                          !self.isApplicationInBackground, self.foregroundProbeSequence == sequence else { return }
                    self.foregroundProbeTask = nil
                    if case let GatewayRtcHealthProbe.Failure.timeout(channels) = error {
                        self.traceLifecycle("foreground-rtc-probe-failed reason=pong-timeout channels=\(channels.joined(separator: ","))")
                    } else {
                        self.traceLifecycle("foreground-rtc-probe-failed reason=business-channel-send-failed")
                    }
                    self.fail("RTC 业务通道未响应，正在恢复连接。", shouldReconnect: true, reportFailure: false)
                }
            }
            return
        }
        traceLifecycle("foreground-probe-ping")
        foregroundProbeTask = Task { [weak self, weak socket] in
            do { try await Task.sleep(for: timeout) } catch { return }
            guard let self, let socket, self.socket === socket,
                  !self.isApplicationInBackground, self.foregroundProbeSequence == sequence else { return }
            let inbound = self.lastInboundFrameAt > probeStartedAt
            switch Self.probeVerdict(inboundSinceProbe: inbound,
                                     consecutiveMisses: self.consecutiveProbeMisses) {
            case .aliveByInboundFrames:
                self.foregroundProbeTask = nil
                self.consecutiveProbeMisses = 0
                self.traceLifecycle("probe-skipped-by-inbound-frame")
                self.onConnectionReady?()
            case .miss(let count):
                self.foregroundProbeTask = nil
                self.consecutiveProbeMisses = count
                if quickTimeout != nil {
                    // 81：前台快速线 miss 后**立即**重探（不排延迟、不等 30s 心跳）。
                    // 判据是「这条线是快速线」（quickTimeout 非空），不是「这是第几次」——
                    // 首探 miss 同样要立即重探，否则回前台永远只探一次就干等心跳
                    // （行为级用例抓过：immediateRetry 只在重探时为 true ⇒ 首探漏排）。
                    // 两次门限不变：重探再 miss 就 giveUp（t≈2s）。
                    self.traceLifecycle("probe-miss consecutive=\(count) retry-immediate")
                    self.probeForegroundConnection(quickTimeout: quickTimeout, immediateRetry: true)
                } else {
                    self.traceLifecycle("probe-miss consecutive=\(count) retryOnNextHeartbeat")
                }
            case .giveUp(let count):
                self.traceLifecycle("foreground-probe-timeout consecutive=\(count)\(immediateRetry ? " mode=quick" : "")")
                await self.handleFailure(URLError(.timedOut), socket: socket, immediateReconnect: immediateRetry)
            }
        }
        socket.sendPing { [weak self, weak socket] error in
            Task { @MainActor [weak self, weak socket] in
                guard let self, let socket, self.socket === socket,
                      self.foregroundProbeSequence == sequence else { return }
                self.foregroundProbeTask?.cancel()
                self.foregroundProbeTask = nil
                if let error {
                    // 单次 ping 失败同样先记一次 miss，不直接拆（与超时走同一门限）。
                    switch Self.probeVerdict(inboundSinceProbe: false,
                                             consecutiveMisses: self.consecutiveProbeMisses) {
                    case .aliveByInboundFrames:
                        self.onConnectionReady?()
                    case .miss(let count):
                        self.consecutiveProbeMisses = count
                        if quickTimeout != nil {
                            self.traceLifecycle("probe-miss consecutive=\(count) pingError=\(error.localizedDescription) retry-immediate")
                            self.probeForegroundConnection(quickTimeout: quickTimeout, immediateRetry: true)
                        } else {
                            self.traceLifecycle("probe-miss consecutive=\(count) pingError=\(error.localizedDescription)")
                        }
                    case .giveUp(let count):
                        self.traceLifecycle("foreground-probe-error consecutive=\(count)\(immediateRetry ? " mode=quick" : "")")
                        await self.handleFailure(error, socket: socket, immediateReconnect: immediateRetry)
                    }
                } else {
                    self.consecutiveProbeMisses = 0
                    self.traceLifecycle("foreground-probe-pong")
                    self.foregroundFastRetryAvailable = false
                    self.foregroundRtcRetryAvailable = false
                    self.foregroundRecoveryStartedAt = nil
                    self.isRecoveringFromBackground = false
                    self.onConnectionReady?()
                }
            }
        }
    }

    /// Keep both control and conversation sockets active through idle tunnels.
    /// WebSocket ping frames do not add application-level requests or responses.
    private func startHeartbeat(for socket: URLSessionWebSocketTask) {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self, weak socket] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: self?.heartbeatInterval ?? .seconds(30)) } catch { return }
                guard let self, let socket, self.socket === socket, self.state.isConnected else { return }
                self.traceLifecycle("heartbeat-ping")
                if !self.isApplicationInBackground {
                    // 普通前台心跳也必须有截止时间，防止 TCP 黑洞一直显示在线。
                    self.probeForegroundConnection()
                    continue
                }
                socket.sendPing { [weak self, weak socket] error in
                    guard let error else { return }
                    Task { @MainActor [weak self, weak socket] in
                        await self?.handleFailure(error, socket: socket)
                    }
                }
            }
        }
    }

    private func startConnectionTimeout(for socket: URLSessionWebSocketTask) {
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = Task { [weak self, weak socket] in
            try? await Task.sleep(for: .seconds(15))
            guard let self,
                  let socket,
                  self.socket === socket,
                  self.wantsConnection,
                  !self.state.isConnected else { return }
            socket.cancel(with: .goingAway, reason: nil)
            self.fail(
                String(localized: "connection.timeout", defaultValue: "连接超时，请检查网络或配对信息后重试。"),
                shouldReconnect: !self.isManualPairingAttempt && !self.probeOnly
            )
        }
    }

    private static func httpResponse(from socket: URLSessionWebSocketTask?, error: NSError) -> HTTPURLResponse? {
        if let response = socket?.response as? HTTPURLResponse { return response }
        for key in ["NSErrorFailingURLResponseKey", "NSURLErrorFailingURLResponseErrorKey"] {
            if let response = error.userInfo[key] as? HTTPURLResponse { return response }
        }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError {
            return httpResponse(from: socket, error: underlying)
        }
        return nil
    }
}

/// 合并系统网络通知；初次通知只建立基线，断网会取消尚未执行的恢复。
@MainActor
final class GatewayNetworkRecovery {
    struct Path: Equatable, Sendable {
        let isSatisfied: Bool
        private let systemPath: NWPath?
        private let interfaces: [String]

        init(_ path: NWPath) {
            isSatisfied = path.status == .satisfied
            // 使用系统的路径等价判断，涵盖接口不变但路由变化的情况。
            systemPath = path
            interfaces = []
        }

#if DEBUG
        init(isSatisfied: Bool, interfaces: [String] = []) {
            self.isSatisfied = isSatisfied
            systemPath = nil
            self.interfaces = interfaces
        }
#endif
    }

    private let debounce: Duration
    private let canRecover: () -> Bool
    private let recover: () -> Void
    private var previousPath: Path?
    private var pendingTask: Task<Void, Never>?

    init(debounce: Duration = .seconds(1), canRecover: @escaping () -> Bool, recover: @escaping () -> Void) {
        self.debounce = debounce
        self.canRecover = canRecover
        self.recover = recover
    }

    deinit { pendingTask?.cancel() }

    func cancel() {
        pendingTask?.cancel()
        pendingTask = nil
    }

    func update(_ path: Path) {
        let previous = previousPath
        previousPath = path
        guard previous != path else { return }
        cancel()
        guard previous != nil, path.isSatisfied, canRecover() else { return }
        pendingTask = Task { [weak self, debounce] in
            do { try await Task.sleep(for: debounce) } catch { return }
            guard let self, !Task.isCancelled, self.canRecover() else { return }
            self.pendingTask = nil
            self.recover()
        }
    }
}

private struct GatewayMessageRequest: Encodable, Sendable {
    struct Image: Encodable, Sendable {
        var mediaType: String
        // JSONEncoder serializes Data as standard Base64 without a Data URL
        // prefix, exactly matching protocol 3.
        var data: Data
        var name: String?
    }

    struct File: Encodable, Sendable { let receiptId: String }
    let type = "message"
    var sessionId: String?
    var parentSessionId: String?
    var subagentMode: String?
    var text: String?
    var textParts: [GatewayTextPart]?
    var images: [Image]
    var files: [File]?
    var workspaceId: String?
    var clientTimeZone: String
    var mode: String
    var requestId: String?
}

private struct GatewayCommandExecuteRequest: Encodable, Sendable {
    let type = "command-execute"
    var sessionId: String
    var line: String
    var images: [GatewayMessageRequest.Image]
}

protocol GatewayCredentialStoring: Sendable {
    func loadToken(for endpoint: URL) async throws -> String?
    func saveToken(_ token: String, for endpoint: URL) async throws
    func deleteToken(for endpoint: URL) async
    func loadOrCreateDeviceID() async throws -> String
}

// 所有连接共享后台 actor，避免 Keychain IPC 占用主线程，并串行化设备 ID 创建与凭证读写。
private actor SystemGatewayCredentialStore: GatewayCredentialStoring {
    static let shared = SystemGatewayCredentialStore()
    func loadToken(for endpoint: URL) throws -> String? {
        try Task.checkCancellation()
        return try GatewayTokenStore.load(for: endpoint)
    }
    func saveToken(_ token: String, for endpoint: URL) throws {
        try Task.checkCancellation()
        try GatewayTokenStore.save(token, for: endpoint)
    }
    func deleteToken(for endpoint: URL) { GatewayTokenStore.delete(for: endpoint) }
    func loadOrCreateDeviceID() throws -> String {
        try Task.checkCancellation()
        return try GatewayDeviceIdentityStore.loadOrCreate()
    }
}

struct GatewayKeychainError: LocalizedError {
    let status: OSStatus

    var isTemporarilyUnavailable: Bool {
        status == errSecInteractionNotAllowed || status == errSecNotAvailable
    }

    var errorDescription: String? {
        (SecCopyErrorMessageString(status, nil) as String?) ??
            String(localized: "keychain.error.status", defaultValue: "Keychain 错误 \(status)")
    }
}

private enum GatewayDeviceIdentityStore {
    private static let service = "ai.dsh.mobile.ios.device-identity"
    private static let account = "installation"

    static func loadOrCreate() throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess,
           let data = result as? Data,
           let stored = String(data: data, encoding: .utf8),
           !stored.isEmpty {
            return stored
        }
        guard status == errSecItemNotFound else { throw GatewayKeychainError(status: status) }

        let value = UUID().uuidString.lowercased()
        var insertion = query
        insertion.removeValue(forKey: kSecReturnData as String)
        insertion.removeValue(forKey: kSecMatchLimit as String)
        insertion[kSecValueData as String] = Data(value.utf8)
        insertion[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(insertion as CFDictionary, nil)
        if addStatus == errSecDuplicateItem { return try loadOrCreate() }
        guard addStatus == errSecSuccess else { throw GatewayKeychainError(status: addStatus) }
        return value
    }
}

/// U5：一次配对顺带下发的网关凭证由上层（MultiGatewayStore）落盘，这里只开一个窄口子。
enum GatewayCredentialVault {
    static func store(_ token: String, for endpoint: URL) throws {
        try GatewayTokenStore.save(token, for: endpoint)
    }
    static func hasCredential(for endpoint: URL) -> Bool {
        // 上游（ec916e1）把 load 改成 throws，以便配对路径把 Keychain 错误传上去；
        // 这里只是 UI 用的布尔探针，读失败按"没有凭证"处理——后果是引导用户重新配对，
        // 比把错误吞掉又谎报"有凭证"安全。
        (try? GatewayTokenStore.load(for: endpoint))?.isEmpty == false
    }
}

private enum GatewayTokenStore {
    private static let service = "ai.dsh.mobile.ios.gateway-token"

    static func load(for endpoint: URL) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: endpoint),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw GatewayKeychainError(status: status) }
        guard let data = result as? Data, let token = String(data: data, encoding: .utf8),
              !token.isEmpty else { throw GatewayKeychainError(status: errSecDecode) }
        return token
    }

    static func save(_ token: String, for endpoint: URL) throws {
        let account = account(for: endpoint)
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: Data(token.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(base as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var insertion = base
            attributes.forEach { insertion[$0.key] = $0.value }
            let status = SecItemAdd(insertion as CFDictionary, nil)
            guard status == errSecSuccess else { throw GatewayKeychainError(status: status) }
        } else if updateStatus != errSecSuccess {
            throw GatewayKeychainError(status: updateStatus)
        }
    }

    static func delete(for endpoint: URL) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: endpoint)
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func account(for endpoint: URL) -> String {
        endpoint.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

}

/// 拒绝 WebSocket 握手重定向，避免向其他来源转发主机凭据。
private final class GatewayRedirectBlocker: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

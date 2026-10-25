import Foundation
import os
import SwiftUI
import UIKit

/// 网关团队帧客户端（手机端团队页的新数据源）。
///
/// 协议见 `dsh-plugin-mobile-gateway` 的 `docs/mobile-v1-integration.md`「团队投影帧与卡片帧」与
/// ds-bot `docs/roster-publish-v1.md`（v2）：
/// - WS `/mobile/v1/team`，首帧 `{"type":"auth","token":…}`（**不需要** conversationId）
/// - 服务端回 `ready` → `snapshot` → `delta`（带 `base`）/ `order` / `card*`
/// - `delta.base` 必须等于本地 `seq`（否则丢弃并重取快照）；`instanceId` 变更同样重取
/// - `order` 帧只带排序键（网关会话 `updatedAt`），**不占**契约 seq
/// - `degraded == true` 表示拿不到实时态（名册只有名字/角色）——界面必须显式标注，
///   不能渲染成「全部空闲」
///
/// iOS 16 红线：URLSessionWebSocketTask（iOS 13+）、不用 @Observable、不用双参 onChange。
@MainActor
final class GatewayTeamFeed: ObservableObject {
    enum Phase: Equatable {
        case idle
        case needsPairing      // Keychain 里没有可用会话
        case connecting
        case live
        case offline(String)
    }

    struct Work: Hashable {
        var roomId: String?
        var tool: String?
        var target: String?
        var note: String?
        var said: String?
        var since: Int64?
    }

    struct Approval: Hashable, Identifiable {
        var sessionId: String
        var tool: String?
        var reason: String?
        var at: Int64?
        var id: String { "\(sessionId):\(tool ?? ""):\(at ?? 0)" }
    }

    struct Pending: Hashable {
        var kind: String
        var sessionId: String
        var roomId: String?
        var at: Int64?
    }

    struct Waiting: Hashable {
        var bots: [String] = []
        var rooms: [String] = []
        var forWhom: [String] = []
    }

    struct Row: Identifiable, Hashable {
        enum Kind: String { case bot, room }
        var id: String
        var kind: Kind
        var name: String
        var role: String?
        var sessionId: String
        var main: Bool = false
        var pinned: Bool = false
        var members: [String] = []
        var admin: String?
        var mode: String?
        // 易变
        var activity: String?          // nil = 服务端没提供（降级态）
        var work: Work?
        var approvals: [Approval] = []
        var pending: [Pending] = []
        var waiting: Waiting?
        var preview: String?
        var updatedAt: Int64?
        /// 静态字段：电脑端 rosters 的 `avatar` 是 `{ shape, image }` 对象（不是单个 data URL）。
        /// shape 是角色形状 id（whale/star/…，缺省时电脑端按 bot id 推导），image 是可选图片。
        var avatarShape: String?
        var avatarImage: String?
        /// 方案 C：矢量定义（与电脑端 SHAPES 同源）。有它就用 Path 画，回落顺序见 TeamAvatarView。
        var glyph: TeamAvatarGlyph?
        /// ds-bot 调色板 key（契约明写「手机端需同名映射」），默认块颜色优先用它。
        var colorKey: String?
        var placeholderCards: Int = 0
    }

    @Published private(set) var phase: Phase = .idle
    /// 大帧重组（①分块传输）。网关只在握手声明 `chunked-frames-v1` 后才发 chunk 帧。
    private var reassembler = FrameReassembler()
    /// 非静默诊断：分块相关的异常必须能在设备日志里看到。
    private static let chunkLog = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-chunk")
    /// 分块超时清扫：网关发一半停住时没有任何新块，必须靠"只推进时钟"的清扫触发超时。
    private var chunkSweeper: ChunkStreamSweeper?
    // MARK: ② 增量续连与心跳（网关 719b360）
    /// `{instanceId, seq}` 落盘：重连时用零往返 `resume` 直接补增量，不用整帧快照。
    private static let resumeStore = TeamFeedResumeStore()
    private var heartbeatTask: Task<Void, Never>?
    /// 最近一次收到任何帧的时间（心跳超时判定的唯一依据）。
    private var lastFrameAt = Date()
    /// 本连接的 resume 存储键（建连时算一次，避免每帧读 Keychain）。
    private var resumeKey = ""
    @Published private(set) var rows: [Row] = []
    @Published private(set) var degraded = false
    @Published private(set) var lastError: String?
    @Published private(set) var seq: Int = 0
    @Published private(set) var hasMainBot: Bool = false

    private var socket: URLSessionWebSocketTask?
    private var session: URLSession?
    private var instanceId: String = ""
    private var mainBotIds: [String] = []
    private var orderKeys: [String: Int64] = [:]
    private var reconnectTask: Task<Void, Never>?
    private var reconnectDelay: TimeInterval = 1
    private var wantLive = false
    /// 每次连上都自增；旧连接的回调据此丢弃（避免重连后旧 socket 的 onClose 把状态打回 offline）。
    private var generation = 0
    private let log: (String) -> Void

    init(log: @escaping (String) -> Void = { _ in }) {
        self.log = log
        // 头像解码失败要能被看见：写进最近错误（界面会显示）+ 日志，但不含 data URL 原文。
        Self.onAvatarDiagnostic = { [weak self] message in
            self?.log(message)
            self?.lastError = message
        }
    }

    // MARK: - 生命周期

    func start() {
        wantLive = true
        guard sessionResource() != nil else {
            phase = .needsPairing
            return
        }
        connect()
    }

    func stop() {
        wantLive = false
        reconnectTask?.cancel()
        reconnectTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        phase = .idle
    }

    func reload() {
        guard wantLive else { return }
        connect()
    }

    // MARK: - 连接

    private struct Resource {
        var token: String
        var baseURL: URL
    }

    /// 复用配对时落进 Keychain 的 mobile-v1 会话（bridge 那条 token），不另存一份凭证。
    private func sessionResource() -> Resource? {
        let store = BridgeKeychainSessionStore()
        guard let json = store.loadJson(), let data = json.data(using: .utf8) else { return nil }
        struct Wire: Decodable {
            let accessToken: String
            let serverUrl: String?
            let expiresAt: Int64?
        }
        guard let wire = try? JSONDecoder().decode(Wire.self, from: data), !wire.accessToken.isEmpty else { return nil }
        if let expiresAt = wire.expiresAt, expiresAt > 0, expiresAt < Int64(Date().timeIntervalSince1970 * 1000) {
            return nil   // 过期就先按未配对处理，由 bridge 侧的刷新链路补
        }
        let raw = (wire.serverUrl?.isEmpty == false ? wire.serverUrl! : "https://127.0.0.1")
        guard let base = URL(string: raw) else { return nil }
        return Resource(token: wire.accessToken, baseURL: base)
    }

    private func teamURL(_ resource: Resource) -> URL? {
        var components = URLComponents(url: resource.baseURL, resolvingAgainstBaseURL: false)
        components?.scheme = resource.baseURL.scheme == "http" ? "ws" : "wss"
        // 配对资料里的 serverUrl 必须是根 origin（App 侧已校验），这里只补路径。
        let prefix = (components?.path ?? "").hasSuffix("/mobile/v1") ? (components?.path ?? "") : "/mobile/v1"
        components?.path = prefix + "/team"
        components?.query = nil
        components?.fragment = nil
        return components?.url
    }

    private func connect() {
        reconnectTask?.cancel()
        reconnectTask = nil
        guard let resource = sessionResource() else {
            phase = .needsPairing
            return
        }
        guard let url = teamURL(resource) else {
            phase = .offline("配对地址无效，无法建立团队连接。")
            return
        }
        generation += 1
        let mine = generation
        socket?.cancel(with: .goingAway, reason: nil)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        let session = URLSession(configuration: config)
        self.session = session
        let task = session.webSocketTask(with: url)
        socket = task
        phase = rows.isEmpty ? .connecting : phase
        task.resume()
        // ① 分块传输：只有声明了能力，网关才会把超大帧切成 chunk 发（不声明 = 永远收整帧）。
        resumeKey = Self.resumeKey(for: resource)
        var authFrame: [String: Any] = ["type": "auth", "token": resource.token,
                                        "capabilities": [FrameReassembler.capabilityName]]
        // ② 零往返续连：带上 {instanceId, afterSeq}，网关命中留窗就直接回 delta（不发整帧快照）。
        if let resume = Self.resumeStore.load(serverKey: resumeKey),
           let payload = Self.resumePayload(instanceId: resume.instanceId, seq: resume.seq) {
            authFrame["resume"] = payload
        }
        let authText = (try? JSONSerialization.data(withJSONObject: authFrame))
            .flatMap { String(data: $0, encoding: .utf8) }
            ?? "{\"type\":\"auth\",\"token\":\"\(resource.token)\"}"
        reassembler = FrameReassembler(limits: BridgeChunkLimits.shared)
        lastFrameAt = Date()
        startHeartbeat()
        task.send(.string(authText)) { [weak self] error in
            guard let self, let error else { return }
            Task { @MainActor in self.handleDisconnect(mine, reason: error.localizedDescription) }
        }
        receive(mine: mine)
    }

    private func receive(mine: Int) {
        guard let socket, mine == generation else { return }
        socket.receive { [weak self] result in
            guard let self else { return }
            Task { @MainActor in
                guard mine == self.generation else { return }
                switch result {
                case .failure(let error):
                    self.handleDisconnect(mine, reason: error.localizedDescription)
                case .success(let message):
                    self.lastFrameAt = Date()
                    switch message {
                    case .string(let text): self.handleFrame(text)
                    case .data(let data): self.handleFrame(String(data: data, encoding: .utf8) ?? "")
                    @unknown default: break
                    }
                    self.receive(mine: mine)
                }
            }
        }
    }

    private func handleDisconnect(_ mine: Int, reason: String) {
        guard mine == generation else { return }
        // 断开即放弃半截流：不让它跨连接存活（否则新连接会收到同 streamId 的块拼错）。
        stopHeartbeat()
        stopChunkSweeper()
        reassembler.reset()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        if case .needsPairing = phase { return }
        // 会话过期（4401）时先让 bridge 侧刷新一次 token，再重连。
        if reason.lowercased().contains("unauthorized") || reason.contains("4401") {
            lastError = "团队连接被拒（会话可能已过期），正在重新配对会话…"
        }
        phase = .offline(reason.isEmpty ? "连接中断" : reason)
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard wantLive else { return }
        reconnectTask?.cancel()
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, 30)
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            await MainActor.run { self.connect() }
        }
    }

    // MARK: - 帧

    private func handleFrame(_ text: String) {
        guard let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        if let type = object["type"] as? String {
            if type == "ready" {
                reconnectDelay = 1
                if let instance = object["instanceId"] as? String { instanceId = instance }
                phase = .live
                lastError = nil
                return
            }
            if type == "pong" {
                if let at = (object["at"] as? NSNumber)?.doubleValue {
                    // 网关时间戳只作校时诊断，不参与调度。
                    Self.chunkLog.debug("heartbeat pong skew=\(Int(Date().timeIntervalSince1970 * 1000 - at), privacy: .public)ms")
                }
                return
            }
            if type == "sync.done" {
                // 回放界标：reason ∈ resume|sync.from|caught-up|gap|unknown-instance|snapshot。
                let reason = object["reason"] as? String ?? "-"
                let doneSeq = (object["seq"] as? NSNumber)?.intValue ?? -1
                Self.chunkLog.notice("sync.done reason=\(reason, privacy: .public) seq=\(doneSeq, privacy: .public)")
                return
            }
            if type == "error" {
                let error = object["error"] as? [String: Any]
                let code = error?["code"] as? String
                let message = error?["message"] as? String ?? "团队流被拒绝"
                switch Self.syncErrorAction(code: code) {
                case .waitForSnapshot:
                    // GAP / UNKNOWN_INSTANCE：同连接通常已紧跟新快照，**不要断开重连**。
                    Self.chunkLog.notice("sync error \(code ?? "-", privacy: .public): waiting for the snapshot that follows")
                case .clearResume:
                    Self.resumeStore.clear(serverKey: resumeKey)
                    Self.chunkLog.notice("sync error \(code ?? "-", privacy: .public): cleared resume state")
                case .ignore:
                    Self.chunkLog.notice("sync error \(code ?? "-", privacy: .public): ignored")
                case .report:
                    lastError = message
                }
                return
            }
            return
        }
        guard let kind = object["kind"] as? String else { return }
        switch kind {
        case "snapshot":
            applySnapshot(object)
            persistResume()
        case "delta":
            applyDelta(object)
            persistResume()
        case "order": applyOrder(object)
        case "card", "card-snapshot": applyCards(object)
        case "avatar": applyAvatarBatches(object)
        case "chunk": handleChunkFrame(object)
        default: break
        }
        if phase == .live || phase == .connecting { phase = .live }
    }

    // MARK: - ② 增量续连与心跳

    /// `{instanceId, seq}` → 零往返 resume 载荷；没有可用状态时返回 nil（回落整帧快照）。
    nonisolated static func resumePayload(instanceId: String, seq: Int, maxDeltas: Int = 200) -> [String: Any]? {
        guard !instanceId.isEmpty, seq > 0 else { return nil }
        return ["instanceId": instanceId, "afterSeq": seq, "maxDeltas": min(max(maxDeltas, 1), 1000)]
    }

    /// 网关 SYNC_* 错误码 → 客户端动作。GAP/UNKNOWN 之后紧跟新快照，所以**不断开**。
    enum SyncErrorAction: Equatable {
        case waitForSnapshot   // gap / unknown-instance：同连接自愈
        case clearResume       // 状态不可用 / 请求非法：清掉落盘状态，下次走整帧
        case ignore            // 限速等可忽略
        case report            // 其它错误：报给界面
    }

    nonisolated static func syncErrorAction(code: String?) -> SyncErrorAction {
        switch code {
        case "SYNC_GAP", "SYNC_UNKNOWN_INSTANCE": return .waitForSnapshot
        case "SYNC_UNAVAILABLE", "SYNC_BAD_REQUEST": return .clearResume
        case "SYNC_RATE_LIMITED": return .ignore
        default: return .report
        }
    }

    /// resume 存储键：按服务器地址区分（一台手机可能连过多个宿主）。
    /// `private`：`Resource` 是类的私有嵌套类型。
    private static func resumeKey(for resource: Resource) -> String {
        "team-feed-resume." + resource.baseURL.absoluteString
    }

    /// 落盘 `{instanceId, seq}`；失败也不影响功能（下次退化成整帧快照）。
    private func persistResume() {
        guard !resumeKey.isEmpty, !instanceId.isEmpty, seq > 0 else { return }
        Self.resumeStore.save(serverKey: resumeKey, instanceId: instanceId, seq: seq)
    }

    /// 心跳：每 interval 发一次 `{type:"ping"}`；静默超过 timeout 判死并重连。
    /// 与网关自带的 WS 层 ping 互补——这条是**本端**发现「TCP 半开、以为还连着」。
    private func startHeartbeat() {
        stopHeartbeat()
        let tuning = BridgeSessionTuning.shared
        heartbeatTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(tuning.heartbeatInterval * 1_000_000_000))
                guard !Task.isCancelled, let self else { return }
                let silence = Date().timeIntervalSince(self.lastFrameAt)
                if silence > tuning.heartbeatTimeout {
                    Self.chunkLog.error("heartbeat timeout silence=\(Int(silence), privacy: .public)s; reconnecting")
                    self.handleDisconnect(self.generation, reason: "心跳超时，正在重连")
                    return
                }
                self.socket?.send(.string("{\"type\":\"ping\"}")) { _ in }
            }
        }
    }

    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    /// 分块帧：拼齐后把原文按 `finalKind` 回放进同一条处理链。
    /// 任何异常都**不静默**：记日志并断开重连（重连会拿到完整快照），绝不拼半个 JSON 交给上层。
    private func handleChunkFrame(_ object: [String: Any]) {
        // 收到新块顺带清一次（便宜），但**不能只靠它**：网关停发时没有新块可触发（P1）。
        _ = reassembler.expire()
        guard let chunk = FrameReassembler.chunk(from: object) else {
            Self.chunkLog.error("chunk frame malformed: \(object.keys.sorted(), privacy: .public)")
            stopChunkSweeper()
            handleDisconnect(generation, reason: "分块帧字段缺失")
            return
        }
        switch reassembler.accept(chunk) {
        case .success(nil):
            // 流还在拼：确保有清扫任务在跑（只在首块时会真正创建）。
            startChunkSweeperIfNeeded()
        case .success(.some(let payload)):
            if reassembler.activeStreamCount == 0 { stopChunkSweeper() }
            Self.chunkLog.debug("chunk stream complete kind=\(chunk.finalKind, privacy: .public) bytes=\(payload.utf8.count, privacy: .public)")
            // payload 是原帧全文：重新解析后按 kind 走原有分发（snapshot/delta/cards/order 都复用）。
            handleFrame(payload)
        case .failure(let error):
            stopChunkSweeper()
            Self.chunkLog.error("chunk reassembly failed: \(String(describing: error), privacy: .public); resyncing")
            handleDisconnect(generation, reason: "分块重组失败，正在重取快照")
        }
    }

    /// 流一开就挂清扫任务；`expire()` 由它按节奏推进，**与是否还有新块无关**。
    /// 超时后走与重组失败同一条恢复路径（记日志 + 断连重取快照）。
    private func startChunkSweeperIfNeeded() {
        guard chunkSweeper == nil else { return }
        let timeout = reassembler.limits.streamTimeout
        let sweeper = ChunkStreamSweeper(interval: ChunkStreamSweeper.interval(forTimeout: timeout)) { [weak self] in
            guard let self else { return }
            let dropped = self.reassembler.expire()
            guard !dropped.isEmpty else {
                if self.reassembler.activeStreamCount == 0 { self.stopChunkSweeper() }
                return
            }
            Self.chunkLog.error("chunk stream timed out: \(dropped, privacy: .public) after \(Int(timeout), privacy: .public)s; resyncing")
            self.stopChunkSweeper()
            self.handleDisconnect(self.generation, reason: "分块超时，正在重取快照")
        }
        chunkSweeper = sweeper
        sweeper.start()
    }

    private func stopChunkSweeper() {
        chunkSweeper?.stop()
        chunkSweeper = nil
    }

    /// 大快照分帧的补充帧：`{kind:"avatar", items:[{id, avatar:{shape,image}}]}`。
    /// 主帧带 `avatarDeferred: true` 时头像随后到；这里把 image 合回对应行。
    private func applyAvatarBatches(_ object: [String: Any]) {
        guard let items = object["items"] as? [[String: Any]], !items.isEmpty else { return }
        let index = Dictionary(uniqueKeysWithValues: rows.enumerated().map { ($1.id, $0) })
        for item in items {
            guard let id = item["id"] as? String, let position = index[id] else { continue }
            let avatar = (item["avatar"] as? [String: Any]) ?? [:]
            if avatar["shape"] != nil || avatar["image"] != nil {
                rows[position].avatarShape = (avatar["shape"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                rows[position].avatarImage = (avatar["image"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            }
            // 旁路帧的 glyph 也在**项顶层**（与 avatar 平级）：合并时不能丢，
            // 否则大快照延后头像的那批会退化成符号层。
            if let glyph = (item["glyph"] as? [String: Any]).flatMap(TeamAvatarGlyph.init(json:))
                ?? avatar["glyph"].flatMap({ $0 as? [String: Any] }).flatMap(TeamAvatarGlyph.init(json:)) {
                rows[position].glyph = glyph
            }
        }
        objectWillChange.send()
    }

    private func applySnapshot(_ object: [String: Any]) {
        let frameInstance = object["instanceId"] as? String ?? instanceId
        instanceId = frameInstance
        mainBotIds = (object["mainBotIds"] as? [String]) ?? []
        if mainBotIds.isEmpty, let single = object["mainBotId"] as? String { mainBotIds = [single] }
        degraded = object["degraded"] as? Bool == true
        seq = (object["seq"] as? NSNumber)?.intValue ?? 0
        var next: [Row] = []
        for raw in (object["bots"] as? [[String: Any]]) ?? [] {
            if let row = row(from: raw, kind: .bot) { next.append(row) }
        }
        for raw in (object["rooms"] as? [[String: Any]]) ?? [] {
            if let row = row(from: raw, kind: .room) { next.append(row) }
        }
        rows = next
        applySnapshotOrderFallback()
        phase = .live
        lastError = nil
    }

    /// snapshot 里已经带 updatedAt（网关补的），作为 order 帧到达前的排序依据。
    private func applySnapshotOrderFallback() {
        for index in rows.indices {
            if let stamp = rows[index].updatedAt { orderKeys[rows[index].sessionId] = stamp }
        }
    }

    private func applyOrder(_ object: [String: Any]) {
        guard let sessions = object["sessions"] as? [String: Any] else { return }
        var next: [String: Int64] = [:]
        for (key, value) in sessions {
            if let number = value as? NSNumber { next[key] = number.int64Value }
        }
        orderKeys.merge(next) { _, new in new }
        objectWillChange.send()
    }

    private func pendingKey(_ row: Row) -> String { row.id }

    private func applyCards(_ object: [String: Any]) {
        // 卡片帧暂时只用来点「等你回答」的红点：正文渲染仍走 bridge 的提问卡片通道。
        let cards: [[String: Any]]
        if let list = object["cards"] as? [[String: Any]] {
            cards = list
        } else {
            cards = ((object["ops"] as? [[String: Any]]) ?? []).compactMap { $0["card"] as? [String: Any] }
        }
        var counts: [String: Int] = [:]
        for card in cards {
            let owner = (card["roomId"] as? String) ?? (card["botId"] as? String) ?? ""
            if !owner.isEmpty { counts[owner, default: 0] += 1 }
        }
        for index in rows.indices {
            rows[index].placeholderCards = counts[pendingKey(rows[index])] ?? 0
        }
    }

    private func applyDelta(_ object: [String: Any]) {
        let frameInstance = object["instanceId"] as? String ?? instanceId
        if !instanceId.isEmpty, frameInstance != instanceId {
            // host 重启：seq 空间重来，必须重取快照（契约 §6）。
            requestResync(reason: "instanceChanged")
            return
        }
        let base = (object["base"] as? NSNumber)?.intValue
        if let base, base != seq {
            requestResync(reason: "baseMismatch(base=\(base),local=\(seq))")
            return
        }
        let newSeq = (object["seq"] as? NSNumber)?.intValue ?? seq
        guard let ops = object["ops"] as? [[String: Any]] else { return }
        var table = Dictionary(uniqueKeysWithValues: rows.map { ($0.id, $0) })
        var order = rows.map(\.id)
        for op in ops {
            guard let name = op["op"] as? String else { continue }
            let id = op["id"] as? String ?? ""
            switch name {
            case "bot.add", "room.add":
                guard let entity = op["entity"] as? [String: Any],
                      let row = row(from: entity, kind: name.hasPrefix("bot") ? .bot : .room) else { continue }
                if table[row.id] == nil { order.append(row.id) }
                table[row.id] = row
            case "bot.update", "room.update":
                guard let entity = op["entity"] as? [String: Any],
                      var row = row(from: entity, kind: name.hasPrefix("bot") ? .bot : .room) else { continue }
                // update 是完整对象；保留帧里没带的易变值，避免结构变更把实时态清空。
                if let existing = table[row.id] {
                    row.activity = row.activity ?? existing.activity
                    row.work = row.work ?? existing.work
                    if row.approvals.isEmpty { row.approvals = existing.approvals }
                    if row.pending.isEmpty { row.pending = existing.pending }
                    if row.waiting == nil { row.waiting = existing.waiting }
                    row.placeholderCards = existing.placeholderCards
                }
                if table[row.id] == nil { order.append(row.id) }
                table[row.id] = row
            case "bot.patch", "room.patch":
                guard var row = table[id], let set = op["set"] as? [String: Any] else { continue }
                merge(set, into: &row)
                table[id] = row
            case "bot.remove", "room.remove":
                table.removeValue(forKey: id)
                order.removeAll { $0 == id }
            case "main.set":
                // 主 Bot 影响排序（sortedRows 把 main 排最前），所以要把表里的行标记同步过来。
                mainBotIds = (op["mainBotIds"] as? [String]) ?? mainBotIds
                for (key, var value) in table {
                    value.main = mainBotIds.contains(key)
                    table[key] = value
                }
            default:
                continue
            }
        }
        rows = order.compactMap { table[$0] }
        seq = newSeq
        applySnapshotOrderFallback()
    }

    private func merge(_ set: [String: Any], into row: inout Row) {
        if set.keys.contains("activity") { row.activity = set["activity"] as? String }
        if let work = set["work"] { row.work = workValue(work) }
        if let approvals = set["approvals"] as? [[String: Any]] {
            row.approvals = approvals.compactMap { approval(from: $0) }
        }
        if let pending = set["pending"] as? [[String: Any]] {
            row.pending = pending.compactMap { pendingValue($0) }
        }
        if let waiting = set["waiting"] as? [String: Any] { row.waiting = waitingValue(waiting) }
        if let preview = set["preview"] as? String { row.preview = preview }
        if let stamp = (set["lastActivityAt"] as? NSNumber)?.int64Value { row.updatedAt = row.updatedAt ?? stamp }
        if set.keys.contains("name"), let name = set["name"] as? String { row.name = name }
        if set.keys.contains("pinned"), let pinned = set["pinned"] as? Bool { row.pinned = pinned }
    }

    private func requestResync(reason: String) {
        log("team feed: resync \(reason)")
        seq = 0
        connect()
    }

    // MARK: - 解码

    /// internal（非 private）仅为让 `TeamAvatarGlyphTests` 用真实发布形态构造行，逻辑不变。
    func row(from raw: [String: Any], kind: Row.Kind) -> Row? {
        guard let id = raw["id"] as? String, !id.isEmpty else { return nil }
        let name = raw["name"] as? String ?? id
        let sessionId = raw["sessionId"] as? String ?? id
        var row = Row(id: id, kind: kind, name: name, role: raw["role"] as? String, sessionId: sessionId)
        row.pinned = raw["pinned"] as? Bool ?? false
        row.main = (raw["main"] as? Bool ?? false) || mainBotIds.contains(id)
        row.members = (raw["members"] as? [String]) ?? []
        row.admin = raw["admin"] as? String
        row.mode = raw["mode"] as? String
        row.activity = raw["activity"] as? String
        if let work = raw["work"] { row.work = workValue(work) }
        row.approvals = ((raw["approvals"] as? [[String: Any]]) ?? []).compactMap { approval(from: $0) }
        row.pending = ((raw["pending"] as? [[String: Any]]) ?? []).compactMap { pendingValue($0) }
        if let waiting = raw["waiting"] as? [String: Any] { row.waiting = waitingValue(waiting) }
        row.preview = raw["preview"] as? String
        row.updatedAt = (raw["updatedAt"] as? NSNumber)?.int64Value
        // avatar 可能是 `{ shape, image }`（当前契约实际形态），也可能是旧式单个 data URL 字符串。
        if let avatar = raw["avatar"] as? [String: Any] {
            row.avatarShape = (avatar["shape"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            row.avatarImage = (avatar["image"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        } else if let single = raw["avatar"] as? String, !single.isEmpty {
            row.avatarShape = nil
            row.avatarImage = single
        }
        // ⚠️ glyph 在**bot 顶层**（与 avatar 平级），不是 avatar 里的字段——
        // ds-bot 发布端就是这么发的。之前写成 `avatar["glyph"]` 导致恒 nil、
        // 所有头像永远走符号层（方案 C 等于没做）。这里以顶层为准，同时兼容旧的嵌套形态。
        row.glyph = (raw["glyph"] as? [String: Any]).flatMap(TeamAvatarGlyph.init(json:))
            ?? (raw["avatar"] as? [String: Any])?["glyph"].flatMap { $0 as? [String: Any] }.flatMap(TeamAvatarGlyph.init(json:))
        row.colorKey = raw["color"] as? String
        return row
    }

    private func workValue(_ raw: Any) -> Work? {
        if let list = raw as? [[String: Any]] {
            // 群聊的 work 是数组（每个成员一条），取第一条作为行内摘要。
            guard let first = list.first else { return nil }
            return workValue(first)
        }
        guard let dict = raw as? [String: Any], !dict.isEmpty else { return nil }
        return Work(roomId: dict["roomId"] as? String, tool: dict["tool"] as? String,
                    target: dict["target"] as? String, note: dict["note"] as? String,
                    said: dict["said"] as? String, since: (dict["since"] as? NSNumber)?.int64Value)
    }

    private func approval(from raw: [String: Any]) -> Approval? {
        guard let sessionId = raw["sessionId"] as? String else { return nil }
        return Approval(sessionId: sessionId, tool: raw["tool"] as? String, reason: raw["reason"] as? String,
                        at: (raw["at"] as? NSNumber)?.int64Value)
    }

    private func pendingValue(_ raw: [String: Any]) -> Pending? {
        guard let kind = raw["kind"] as? String, let sessionId = raw["sessionId"] as? String else { return nil }
        return Pending(kind: kind, sessionId: sessionId, roomId: raw["roomId"] as? String,
                       at: (raw["at"] as? NSNumber)?.int64Value)
    }

    private func waitingValue(_ raw: [String: Any]) -> Waiting {
        Waiting(bots: (raw["bots"] as? [String]) ?? [], rooms: (raw["rooms"] as? [String]) ?? [],
                forWhom: (raw["for"] as? [String]) ?? [])
    }

    // MARK: - 展示

    /// 需求 1：主 Bot 最上 → 置顶 → 网关 `order`（会话 `updatedAt`）倒序 → 名字。
    var sortedRows: [Row] {
        rows.sorted { left, right in
            if left.main != right.main { return left.main }
            if left.pinned != right.pinned { return left.pinned }
            let leftStamp = orderKeys[left.sessionId] ?? left.updatedAt ?? 0
            let rightStamp = orderKeys[right.sessionId] ?? right.updatedAt ?? 0
            if leftStamp != rightStamp { return leftStamp > rightStamp }
            return left.name.localizedStandardCompare(right.name) == .orderedAscending
        }
    }

    var bots: [Row] { sortedRows.filter { $0.kind == .bot } }
    var rooms: [Row] { sortedRows.filter { $0.kind == .room } }

    /// 需求 4：名字下面那行实时状态。`nil` 表示这一行没有要显示的状态。
    func statusText(for row: Row, botNames: [String: String] = [:]) -> String? {
        if !row.pending.isEmpty || row.placeholderCards > 0 {
            let kind = row.pending.first?.kind ?? "question"
            return kind == "view" ? "等你确认卡片" : kind == "secret" ? "等你填密钥" : "等你回答"
        }
        if !row.approvals.isEmpty { return "等你审批\(row.approvals.first?.tool.map { "（\($0)）" } ?? "")" }
        if degraded, row.activity == nil { return "实时状态暂不可用（网关降级）" }
        guard let activity = row.activity else { return nil }
        switch activity {
        case "working", "thinking":
            if let work = row.work {
                let where_ = work.roomId.flatMap { botNames[$0] }.map { " · 在 \($0)" } ?? ""
                let what = work.note ?? work.target ?? work.tool ?? "干活中"
                return "正在干活：\(what)\(where_)"
            }
            return "正在干活"
        case "waiting": return "等待中"
        case "alert": return "需要你处理"
        case "idle": return "空闲"
        default: return activity
        }
    }

    /// 群聊行（需求 3）：成员 + 谁在发言 + 最后一条消息摘要。
    func roomSubtitle(for row: Row, botNames: [String: String]) -> String {
        let members = row.members.prefix(4).map { botNames[$0] ?? $0 }.joined(separator: "、")
        let extra = row.members.count > 4 ? " 等 \(row.members.count) 人" : ""
        return members.isEmpty ? "群聊" : "\(members)\(extra)"
    }

    // MARK: - 头像（静态字段，缺失时回落默认块）

    /// ds-bot 的 ink 调色板（`src/client/inks.js` 的 `INKS[k] = [light, dark]`）。
    /// **深浅两档都要抄**：电脑端 `styles.js` 深色走 `INKS[k][1]`，只抄浅色会让
    /// 深色下 19/19 与电脑端不同（QA 实测指出）。
    private static let inkPairs: [String: (light: String, dark: String)] = [
        "deepseek": ("#4D6BFE", "#6A84FF"), "charcoal": ("#141416", "#F1F2F4"),
        "graphite": ("#2D2D2D", "#D6D6DA"), "rose": ("#EC2F6B", "#FF5A7A"),
        "sky": ("#0AA8D6", "#2EE6E0"), "tangerine": ("#FF6900", "#FF8A2E"),
        "cobalt": ("#082DFF", "#5C78FF"), "black": ("#000000", "#FFFFFF"),
        "brown": ("#A27952", "#855C36"), "red": ("#FF3E51", "#E02135"),
        "orange": ("#FF781C", "#FF6700"), "yellow": ("#FFAF38", "#FF9800"),
        "green": ("#00C972", "#009957"), "cyan": ("#1CC3B0", "#00A592"),
        "blue": ("#2A92FE", "#0E74E0"), "violet": ("#A97EFE", "#804EE0"),
        "magenta": ("#FF5EB1", "#E02A88"), "gray": ("#959595", "#777777"),
        "aurora": ("#00B8D4", "#2EE6E0"),
    ]

    /// 电脑端符号墨色（`themes.js` 的 `DARK_INK`）。
    private static let accentDarkInk = "#141414"

    private static func rgb(_ hex: String) -> (Double, Double, Double)? {
        let body = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard body.count == 6, let value = UInt32(body, radix: 16) else { return nil }
        return (Double((value >> 16) & 0xFF) / 255,
                Double((value >> 8) & 0xFF) / 255,
                Double(value & 0xFF) / 255)
    }

    private static func color(hex: String) -> Color? {
        guard let (red, green, blue) = rgb(hex) else { return nil }
        return Color(red: red, green: green, blue: blue)
    }

    /// WCAG 相对亮度（与 `inks.js` 的 `luminance` 同式）。
    static func luminance(hex: String) -> Double {
        guard let (red, green, blue) = rgb(hex) else { return 0 }
        func linear(_ channel: Double) -> Double {
            channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /// 指定主题下 ink 的实际 hex（自定义 hex 两档同色）。
    static func inkHex(_ key: String?, scheme: ColorScheme) -> String? {
        guard let key, !key.isEmpty else { return nil }
        if key.hasPrefix("#") { return key.count == 7 ? key : nil }
        guard let pair = inkPairs[key.lowercased()] else { return nil }
        return scheme == .dark ? pair.dark : pair.light
    }

    /// 指定主题下的 ink 颜色（对拍测试用；视图用 `dynamicInk`）。
    static func inkColor(_ key: String?, scheme: ColorScheme) -> Color? {
        inkHex(key, scheme: scheme).flatMap(color(hex:))
    }

    /// 视图用：随主题自动解析的动态色（渲染层不用逐层加 env）。
    static func dynamicInk(_ key: String?) -> Color? {
        guard let key, !key.isEmpty else { return nil }
        if key.hasPrefix("#") { return color(hex: key) }
        guard inkPairs[key.lowercased()] != nil else { return nil }
        return Color(uiColor: UIColor { traits in
            let scheme: ColorScheme = traits.userInterfaceStyle == .dark ? .dark : .light
            return UIColor(inkColor(key, scheme: scheme) ?? neutralBlockColor)
        })
    }

    /// 电脑端 `accentInk()`：在白与近黑之间选对比度更高的那个。
    /// 规则逐字取自 `themes.js:35-36`，不要自己另发明一套。
    static func symbolInkHex(forAccentHex accent: String) -> String {
        let accentLum = luminance(hex: accent)
        let darkLum = luminance(hex: accentDarkInk)
        return pow(accentLum + 0.05, 2) > 1.05 * (darkLum + 0.05) ? accentDarkInk : "#FFFFFF"
    }

    /// 角色符号的墨色（压在真彩底上）：按当前主题的实际底色二选一。
    static func avatarInk(_ row: Row) -> Color {
        Color(uiColor: UIColor { traits in
            let scheme: ColorScheme = traits.userInterfaceStyle == .dark ? .dark : .light
            let resolved = inkHex(row.colorKey, scheme: scheme) ?? "#959595"
            return UIColor(color(hex: symbolInkHex(forAccentHex: resolved)) ?? .white)
        })
    }

    /// 角色形状 → 近似符号（电脑端是矢量角色；手机端用同一形状语义的符号 + 同色底，
    /// 不再一律首字母）。符号一律走系统可用名，iOS 16 全都有。
    static func shapeSymbol(_ shape: String?) -> String {
        switch (shape ?? "").lowercased() {
        case "whale": return "fish.fill"
        case "star": return "star.fill"
        case "moon": return "moon.fill"
        case "wave": return "water.waves"
        case "squircle": return "square.fill"
        // `zed` 在电脑端是 Bull（不是兔子）；SF Symbols 没有牛，取最接近且不撞符号的四足剪影。
        case "zed": return "tortoise.fill"
        case "hex": return "camera.macro"
        case "gumdrop": return "drop.fill"
        case "peanut": return "circle.hexagongrid.fill"
        case "saucer": return "airplane"
        case "bunny": return "hare.fill"
        case "kitty": return "pawprint.fill"
        case "beacon": return "lightbulb.fill"
        case "plus": return "plus"
        case "magnet": return "magnet"
        case "acorn": return "leaf.fill"
        case "pebbles": return "circle.grid.3x3.fill"
        case "cushion": return "square.stack.3d.up.fill"
        case "ring": return "circle.dashed"
        case "gem": return "diamond.fill"
        default: return "circle.fill"
        }
    }

    /// 旧宿主（未按 v2 契约解析）没有 `color` 时的**中性**色块。
    /// 不再按名字猜色：猜出来的值一定和电脑端不同，用户看到的就是「两边不一样」。
    static let neutralBlockColor = Color(red: 0.29, green: 0.31, blue: 0.33)

    /// room 没有 avatar 字段（契约静态列表里只有 members/admin/mode/notice）：
    /// 成员头像堆叠要用 members 去 join 各 bot 现拼，不给 room 加契约字段。
    func memberAvatars(for room: Row, limit: Int = 3) -> [Row] {
        let byId = Dictionary(uniqueKeysWithValues: rows.filter { $0.kind == .bot }.map { ($0.id, $0) })
        return room.members.prefix(limit).compactMap { byId[$0] }
    }

    /// 契约 v2 起 host 已在 roster 里解析好 `color`（ink key 或 hex），手机端**只映射**。
    /// 深浅两档都按 `colorScheme` 取（电脑端深色走 INKS[k][1]）；
    /// 真拿不到（旧宿主）时用中性色块，**不按名字猜**——猜测必然和电脑端不一致。
    static func avatarColor(_ row: Row) -> Color {
        dynamicInk(row.colorKey) ?? neutralBlockColor
    }

    /// 契约 v2 起 host 已解析好 `avatar.shape`（缺省按 bot id 推导），手机端**只映射符号**。
    /// 旧宿主缺 shape（或形状未知）时落到中性圆点 `circle.fill`，**不做名字/首字母推导**。
    static func avatarSymbol(_ row: Row) -> String {
        shapeSymbol(row.avatarShape)
    }

    /// data URL → UIImage。非 data URL（本地路径）这里不解，交给默认块。
    /// 解码结果缓存：SwiftUI 每次 body 求值都会问一次头像，base64 解码 + UIImage 构造
    /// 放在列表里是明显的每帧成本。键用 **data URL 的内容哈希**（同一 bot 换图会自动失效），
    /// 上限 64 张，够覆盖 20 个 Bot 的两份快照。
    /// 解码失败**不许静默**（上次「头像全是首字母」查了几轮就是因为静默）。
    /// 只报前缀与长度，绝不把 data URL 原文写进日志/界面（脱敏要求）。
    static var onAvatarDiagnostic: ((String) -> Void)?
    private static var reportedAvatarFailures = Set<String>()

    private static let avatarCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 64
        return cache
    }()

    static func avatarImage(_ row: Row) -> UIImage? {
        guard let raw = row.avatarImage, raw.hasPrefix("data:"),
              let comma = raw.firstIndex(of: ","),
              raw[raw.startIndex..<comma].contains("base64") else { return nil }
        // 32 字符的稳定哈希键（FNV-1a 双轮），比整串 data URL 当键省内存
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in raw.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        let key = NSString(string: String(hash, radix: 36))
        if let cached = avatarCache.object(forKey: key) { return cached }
        let base64 = String(raw[raw.index(after: comma)...])
        guard let data = Data(base64Encoded: base64), let image = UIImage(data: data) else {
            let prefix = String(raw.prefix(28))
            if !reportedAvatarFailures.contains(key as String) {
                reportedAvatarFailures.insert(key as String)
                // 例：avatar undecodable: prefix=data:image/svg+xml;base64, bytes=1234
                onAvatarDiagnostic?("avatar undecodable: prefix=\(prefix) bytes=\(raw.count)")
            }
            return nil
        }
        // 渲染尺寸压到 ≤256pt（头像最大 46pt），避免为小图解码整张原图占内存
        let scaled = image.preparingThumbnail(of: CGSize(width: 128, height: 128)) ?? image
        avatarCache.setObject(scaled, forKey: key)
        return scaled
    }

    func isLive(_ row: Row) -> Bool { row.activity == "working" || row.activity == "thinking" }
}

// MARK: - ② 续连状态落盘

/// `{instanceId, seq}` 的本地持久化：重连时零往返补增量。
///
/// 只存两个值（网关明确说过：网关重启不重置 seq，ds-bot 重启才换 instanceId），
/// 所以这里不需要存事件缓冲。`UserDefaults` 按服务器地址分键——一台手机可能连过多个宿主。
struct TeamFeedResumeStore {
    struct Resume: Equatable {
        let instanceId: String
        let seq: Int
    }

    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private func instanceKey(_ serverKey: String) -> String { serverKey + ".instanceId" }
    private func seqKey(_ serverKey: String) -> String { serverKey + ".seq" }

    func load(serverKey: String) -> Resume? {
        guard !serverKey.isEmpty else { return nil }
        let instanceId = defaults.string(forKey: instanceKey(serverKey)) ?? ""
        let seq = defaults.integer(forKey: seqKey(serverKey))
        guard !instanceId.isEmpty, seq > 0 else { return nil }
        return Resume(instanceId: instanceId, seq: seq)
    }

    func save(serverKey: String, instanceId: String, seq: Int) {
        guard !serverKey.isEmpty, !instanceId.isEmpty, seq > 0 else { return }
        defaults.set(instanceId, forKey: instanceKey(serverKey))
        defaults.set(seq, forKey: seqKey(serverKey))
    }

    func clear(serverKey: String) {
        guard !serverKey.isEmpty else { return }
        defaults.removeObject(forKey: instanceKey(serverKey))
        defaults.removeObject(forKey: seqKey(serverKey))
    }
}

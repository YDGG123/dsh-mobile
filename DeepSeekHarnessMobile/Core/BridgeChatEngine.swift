import DeepSeekHarnessShared
import Foundation
import SwiftUI
import os

/// ## 为什么不把主聊天窗整窗切到这条通道（2026-10-27 定论，别再重新评估一遍）
/// 这条通道**确实能跨段**：REST 的 cursor 由服务端顺着 `parts` 翻
/// （`adapter.mjs:200 cursorFor`，带归属校验 `:139 requireSession`），所以"多段 Bot 看不到
/// 旧历史"看起来可以直接复用这里。**但它只输出气泡文本**（`BridgeChatItem`：user/assistant
/// 文本 + 流式状态），没有工具卡、审批卡、推理段、附件预览——而主聊天窗的价值恰恰在这些
/// 结构化条目上，切过来等于降级。所以跨段能力要回到**主聊天窗自己的数据流**里做
/// （网关补 WS 跨段 + 客户端多桶聚合，见 `AppStore.BotHistorySegments`），
/// 这条引擎继续只服务 Bot/团队页的轻量聊天。
///
/// bridge 会话的实时聊天引擎（§2.6）：历史 + submissions + WS 事件。
/// 归并与对账全部在 KMP（chatApplyEvent / chatAppendLocalSubmission / chatApplySubmissionReceipt），
/// Swift 只做 HTTP/WS 执行、重连与状态发布。
@MainActor
final class BridgeChatEngine: ObservableObject {
    enum Status: Equatable {
        case idle
        case connecting
        case connected
        case reconnecting(attempt: Int)
        case failed(String)
    }

    @Published private(set) var messagesJson = "[]"
    @Published private(set) var status: Status = .idle
    @Published private(set) var isSending = false
    /// 78：恢复快照与实时回写。applyResumedSnapshot 在 start() 前由 adapter 注入；
    /// onMessagesChanged 每次 messagesJson 变化回调（adapter 据此实时刷新快照）。
    private(set) var resumedWithSnapshot = false
    var onMessagesChanged: ((String) -> Void)?
    /// 退出时的贴底标记（视图退场时上报，决定再进时贴底还是恢复原位）。
    var resumeWasAtBottom = true

    let conversationId: String
    /// 会话目标可能在服务端轮换（TARGET_CHANGED 后由团队镜像纠偏）。
    private(set) var sessionId: String

    private let transport: BridgeTransport
    private let tokenProvider: () -> String?
    private let refreshToken: () async -> Bool
    /// 409 TARGET_CHANGED 纠偏：先全量刷新团队，再从最新镜像取该会话的 sessionId。
    private let refreshTeam: () async -> Void
    private let resolveSessionId: (String) -> String?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var reconnectAttempt = 0
    private var lastEventSeq: Int64?
    private var stopped = false
    private var historyCursor: String?
    @Published private(set) var canLoadOlder = false
    @Published private(set) var isLoadingOlder = false

    init(
        transport: BridgeTransport,
        conversationId: String,
        sessionId: String,
        tokenProvider: @escaping () -> String?,
        refreshToken: @escaping () async -> Bool,
        refreshTeam: @escaping () async -> Void = {},
        resolveSessionId: @escaping (String) -> String? = { _ in nil }
    ) {
        self.transport = transport
        self.conversationId = conversationId
        self.sessionId = sessionId
        self.tokenProvider = tokenProvider
        self.refreshToken = refreshToken
        self.refreshTeam = refreshTeam
        self.resolveSessionId = resolveSessionId
    }

    deinit {
        receiveTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
    }

    var messages: [BridgeChatItem] {
        guard let data = messagesJson.data(using: .utf8),
              let snapshot = try? JSONDecoder().decode(BridgeChatSnapshotView.self, from: data) else {
            return []
        }
        return snapshot.messages
    }

    func start() {
        guard status == .idle else { return }
        stopped = false
        status = .connecting
        Task { [weak self] in
            guard let self else { return }
            if self.resumedWithSnapshot {
                // 78：已有快照渲染在先。整页拉取被 limit=1 探针取代——探针 seq 与
                // 快照 maxSeq 一致 ⇒ 无新消息，跳过 ~50KB 整页；不一致 ⇒ 拉整页合并。
                // 探针必发（1KB）：「有快照 ≠ 零请求」，桌面端新消息不能漏。
                await self.refreshLatest(limit: 50)
            } else {
                await self.loadHistory()
            }
            await self.connect()
        }
    }

    /// 78：adapter 在构造后注入跨进入快照（立刻可渲染，不等网络）。
    func applyResumedSnapshot(messagesJson: String, wasAtBottom: Bool) {
        guard messagesJson.count > 2, messagesJson != self.messagesJson else { return }
        self.messagesJson = messagesJson
        resumeWasAtBottom = wasAtBottom
        resumedWithSnapshot = true
    }

    func stop() {
        stopped = true
        receiveTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        status = .idle
    }

    /// 历史：GET /conversations/{id}/messages（最近 50 条）。
    private func loadHistory() async {
        guard let token = tokenProvider() else { return }
        do {
            let body = try await transport.fetch(
                path: "/conversations/\(conversationId)/messages?limit=50",
                token: token
            )
            messagesJson = BridgeChatMergeKt.chatMessagesFromHistory(historyJson: body)
            onMessagesChanged?(messagesJson)
            historyCursor = Self.cursor(from: body)
            canLoadOlder = historyCursor != nil
        } catch let status as BridgeResponseStatus where status.status == 401 {
            if await refreshToken(), let token = tokenProvider() {
                if let body = try? await transport.fetch(
                    path: "/conversations/\(conversationId)/messages?limit=50",
                    token: token
                ) {
                    messagesJson = BridgeChatMergeKt.chatMessagesFromHistory(historyJson: body)
                    onMessagesChanged?(messagesJson)
                    historyCursor = Self.cursor(from: body)
                    canLoadOlder = historyCursor != nil
                }
            }
        } catch {
            // 历史失败不阻塞 WS：WS reset 事件会补基线。
        }
    }

    private func connect() async {
        guard !stopped, let token = tokenProvider() else {
            self.status = .failed("会话不可用，请重新配对")
            return
        }
        do {
            let task = try transport.webSocketTask(path: "/events")
            socket = task
            task.resume()
            let after = lastEventSeq.map { KotlinLong(longLong: $0) }
            let frame = BridgeDtosKt.encodeBridgeAuthFrame(
                token: token,
                conversationId: conversationId,
                after: after
            )
            try await task.send(.string(frame))
            status = .connected
            listen(on: task)
        } catch {
            scheduleReconnect()
        }
    }

    private func listen(on task: URLSessionWebSocketTask) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    let message = try await task.receive()
                    guard let self else { return }
                    let text: String
                    switch message {
                    case .string(let value): text = value
                    case .data(let data): text = String(data: data, encoding: .utf8) ?? ""
                    @unknown default: continue
                    }
                    self.handleEvent(text)
                } catch {
                    guard let self else { return }
                    if !self.stopped {
                        self.scheduleReconnect()
                    }
                    return
                }
            }
        }
    }

    private func handleEvent(_ raw: String) {
        guard let event = try? JSONDecoder().decode(BridgeChatEventHead.self, from: Data(raw.utf8)) else { return }
        if let seq = event.eventSeq { lastEventSeq = seq }
        if event.type == "ready" {
            reconnectAttempt = 0
            status = .connected
            return
        }
        if event.type == "error", let code = event.error?.code?.lowercased(),
           ["unauthorized", "token_expired", "invalid_token"].contains(code) {
            Task { [weak self] in
                guard let self else { return }
                if await self.refreshToken() {
                    self.socket?.cancel(with: .goingAway, reason: nil)
                    self.scheduleReconnect()
                } else {
                    self.status = .failed("会话已失效，请重新配对")
                }
            }
            return
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        messagesJson = BridgeChatMergeKt.chatApplyEvent(
            messagesJson: messagesJson,
            eventJson: raw,
            nowMillis: now
        )
        onMessagesChanged?(messagesJson)
    }

    private func scheduleReconnect() {
        guard !stopped else { return }
        reconnectAttempt += 1
        status = .reconnecting(attempt: reconnectAttempt)
        let delay = BridgeBackoff.delay(attempt: reconnectAttempt - 1)
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !self.stopped else { return }
            await self.connect()
        }
    }

    /// 历史分页：按 cursor 加载更早一页并合并（§2.4）。
    func loadOlder() async {
        guard !isLoadingOlder, let cursor = historyCursor, let token = tokenProvider() else { return }
        isLoadingOlder = true
        defer { isLoadingOlder = false }
        let encoded = cursor.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? cursor
        do {
            let body = try await transport.fetch(
                path: "/conversations/\(conversationId)/messages?limit=50&cursor=\(encoded)",
                token: token
            )
            messagesJson = BridgeChatMergeKt.chatMergeHistoryPage(
                messagesJson: messagesJson,
                historyJson: body
            )
            historyCursor = Self.cursor(from: body)
            canLoadOlder = historyCursor != nil
        } catch {
            canLoadOlder = false
        }
    }

    /// **兜底轮询**：拉最新一页并与现有快照**合并**（与 Android 的 8s 轮询同一个可观察行为）。
    ///
    /// 为什么必须有：房间消息的实时只有 WS 一条路；那条一旦不通，用户就表现为
    /// 「必须重开才看到」（重开才走 REST 历史）。有了这条，最差退化成「≤8s 延迟」，
    /// 而不是「永远不出现」——两端可观察行为也就一致了（Android 本来就是 8s 轮询）。
    ///
    /// 合并走 KMP 的 `chatMergeHistoryPage`：按 id/seq 去重，**无新消息时返回原串**
    /// ⇒ 不产生多余的 `messagesJson` 变更（@Published 不空转，视图不白重算）。
    /// 失败不报错、不改状态，下个周期再来（与 Android 的静默重试一致）。
    func refreshLatest(limit: Int = 50) async {
        guard !stopped, let token = tokenProvider() else { return }
        let path = "/conversations/\(conversationId)/messages?limit=\(limit)"
        do {
            // ⑥ 探针（Chief 定，2026-11-03）：先拉 1 条（~1KB）比最新 seq，与本地一致 ⇒ 无新消息，
            // 跳过整页（~50KB）。活跃群每 8s 轮询的常驻流量从 50KB 降到 1KB；探针解析失败或
            // 比对不上时保守拉整页（行为退化为原样，绝不静默漏消息）。
            if let probe = try? await transport.fetch(path: "/conversations/\(conversationId)/messages?limit=1", token: token),
               let probeSeq = Self.latestSeq(from: probe), probeSeq == latestKnownSeq {
                return
            }
            applyLatestPage(try await transport.fetch(path: path, token: token))
        } catch let status as BridgeResponseStatus where status.status == 401 {
            guard await refreshToken(), let fresh = tokenProvider() else { return }
            if let body = try? await transport.fetch(path: path, token: fresh) {
                applyLatestPage(body)
            }
        } catch {
            // 静默：兜底轮询不是主路径，失败不该污染 status/lastError。
        }
    }

    /// 探针/分页共用的「最新 seq」读取：**取 seq 最大值而非末位**——不依赖服务端返回顺序
    /// （升降序存疑时 max 是唯一不猜的口径）；seq 缺失的条目不计。
    private static func latestSeq(from body: String) -> Int64? {
        guard let data = body.data(using: .utf8),
              let snapshot = try? JSONDecoder().decode(BridgeChatSnapshotView.self, from: data) else { return nil }
        return snapshot.messages.compactMap(\.seq).max()
    }

    var latestKnownSeq: Int64? { messages.compactMap(\.seq).max() }

    private func applyLatestPage(_ body: String) {
        let merged = BridgeChatMergeKt.chatMergeHistoryPage(messagesJson: messagesJson, historyJson: body)
        guard merged != messagesJson else { return }
        messagesJson = merged
        onMessagesChanged?(messagesJson)
        // 固定 ASCII 标记（≥16 字节）：既用于日志定位，也用作产物指纹（70 为 0、71 ≥1）。
        Self.pollLog.notice("roomchat-poll-latest-merged conversation=\(self.conversationId, privacy: .public)")
    }

    private static let pollLog = Logger(subsystem: "ai.dsh.mobile.ios", category: "roomchat-poll")

    private static func cursor(from historyJson: String) -> String? {
        guard let data = historyJson.data(using: .utf8),
              let page = try? JSONDecoder().decode(BridgeHistoryCursor.self, from: data) else { return nil }
        return page.cursor.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// 发送：本地 pending + POST /submissions + 回执对账（requestId 幂等）。
    func send(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isSending else { return }
        guard let token = tokenProvider() else { return }
        isSending = true
        defer { isSending = false }
        let requestId = UUID().uuidString
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        messagesJson = BridgeChatMergeKt.chatAppendLocalSubmission(
            messagesJson: messagesJson,
            sessionId: sessionId,
            requestId: requestId,
            text: trimmed,
            nowMillis: now
        )
        onMessagesChanged?(messagesJson)
        do {
            try await postSubmission(text: trimmed, requestId: requestId, token: token)
        } catch let status as BridgeResponseStatus where status.status == 401 {
            // 令牌过期：刷新一次后重发（同一个 requestId，服务端幂等）。
            if await refreshToken(), let fresh = tokenProvider() {
                if (try? await postSubmission(text: trimmed, requestId: requestId, token: fresh)) == nil {
                    self.status = .failed(status.localizedDescription)
                }
            }
        } catch let status as BridgeResponseStatus where status.status == 409 {
            // 契约：TARGET_CHANGED 的语义是「目标换了，刷新后重试」。
            // 群会话/会话 sessionId 会在宿主侧重排，这里拉一次全量团队并用新值重发一次。
            if await recoverTargetAndResend(text: trimmed, requestId: requestId) == false {
                self.status = .failed(status.localizedDescription)
            }
        } catch {
            // 上屏失败态：保留 pending 行，用户可重试。
            status = .failed(error.localizedDescription)
        }
    }

    /// 发送一次提交并按回执对账（KMP 归并）。
    @discardableResult
    private func postSubmission(text: String, requestId: String, token: String) async throws -> String {
        let body = try await transport.post(
            path: "/submissions",
            token: token,
            jsonBody: [
                "conversationId": conversationId,
                "sessionId": sessionId,
                "requestId": requestId,
                "text": text
            ]
        )
        messagesJson = BridgeChatMergeKt.chatApplySubmissionReceipt(
            messagesJson: messagesJson,
            conversationId: conversationId,
            sessionId: sessionId,
            requestId: requestId,
            text: text,
            receiptJson: body,
            nowMillis: Int64(Date().timeIntervalSince1970 * 1000)
        )
        onMessagesChanged?(messagesJson)
        return body
    }

    /// TARGET_CHANGED 纠偏：全量刷新 → 取最新 sessionId → 换值后用同一 requestId 重发一次。
    private func recoverTargetAndResend(text: String, requestId: String) async -> Bool {
        await refreshTeam()
        guard let fresh = resolveSessionId(conversationId), fresh != sessionId else { return false }
        sessionId = fresh
        guard let token = tokenProvider() else { return false }
        return (try? await postSubmission(text: text, requestId: requestId, token: token)) != nil
    }
}

// MARK: - 视图侧解码模型

struct BridgeChatSnapshotView: Decodable {
    let messages: [BridgeChatItem]

    private enum CodingKeys: String, CodingKey { case messages }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        messages = (try? container.decode([BridgeChatItem].self, forKey: .messages)) ?? []
    }
}

struct BridgeChatItem: Decodable, Identifiable, Hashable {
    let id: String
    let role: String
    let text: String
    let pending: Bool?
    let localSubmission: Bool?
    let delivery: String?

    /// 合流用（2026-11-02 新增，只加解码、不改现有渲染）。
    /// `seq` = **宿主事件 seq**（服务端 `group-projection.mjs:8` 的 `id = ${sessionId}:${event.seq}`），
    /// 与网关侧 `SessionEvent.seq` 同源 ⇒ 跨源精确去重的键是 `(sessionId, seq)`。
    /// ⚠️ 两边的 **id 字符串格式不同**（bridge 是 `sessionId:seq`、网关是 `sessionId-seq`），
    /// 所以去重**必须比 (sessionId, seq) 分量**，拿 id 直接比会恒不等且静默失效。
    let seq: Int64?
    /// `createdAt` 在 KMP 里是 `JsonPrimitive`（可能是数字、也可能是字符串）⇒ 宽松解码，
    /// 用强类型会让整份 snapshot 解码失败、把现有 bridge 视图一起带崩。
    let createdAtMillis: Int64?
    /// 群聊消息的发送者名（服务端只对 room 分支下发 `author`）。
    /// KMP 两级模型已声明并透传（commit `3ea7340`）⇒ **iOS 重新链接 shared 之后即可拿到值**；
    /// 在那之前、或旧 bridge 服务端不下发时仍为 nil ⇒ 渲染必须按 nil 兜底（不得当成"我"）。
    let authorName: String?
    /// 群回复的归组 id（服务端 `groupReplyId`）；同 `authorName`，KMP 已透传（`3ea7340`）。
    let groupReplyId: String?

    var isUser: Bool { role == "user" }
    var isStreaming: Bool { pending == true }
    var isDeliveryFailed: Bool { delivery == "rejected" }

    private enum CodingKeys: String, CodingKey {
        case id, role, text, pending, localSubmission, delivery, seq, createdAt, author, groupReplyId
    }
    private enum AuthorKeys: String, CodingKey { case id, name }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        role = try c.decode(String.self, forKey: .role)
        text = (try? c.decode(String.self, forKey: .text)) ?? ""
        pending = try? c.decodeIfPresent(Bool.self, forKey: .pending)
        localSubmission = try? c.decodeIfPresent(Bool.self, forKey: .localSubmission)
        delivery = try? c.decodeIfPresent(String.self, forKey: .delivery)
        seq = (try? c.decodeIfPresent(Int64.self, forKey: .seq)) ?? nil
        if let n = try? c.decodeIfPresent(Int64.self, forKey: .createdAt) {
            createdAtMillis = n
        } else if let d = try? c.decodeIfPresent(Double.self, forKey: .createdAt) {
            createdAtMillis = Int64(d)
        } else if let s = try? c.decodeIfPresent(String.self, forKey: .createdAt) {
            createdAtMillis = Self.parseCreatedAt(s)
        } else {
            createdAtMillis = nil
        }
        if let ac = try? c.nestedContainer(keyedBy: AuthorKeys.self, forKey: .author) {
            authorName = (try? ac.decodeIfPresent(String.self, forKey: .name)) ?? nil
        } else {
            authorName = nil
        }
        groupReplyId = (try? c.decodeIfPresent(String.self, forKey: .groupReplyId)) ?? nil
    }

    private static func parseCreatedAt(_ s: String) -> Int64? {
        if let n = Int64(s) { return n }
        let f = ISO8601DateFormatter()
        if let d = f.date(from: s) { return Int64(d.timeIntervalSince1970 * 1000) }
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return Int64(d.timeIntervalSince1970 * 1000) }
        return nil
    }

    /// ③ 群聊合流：bridge 气泡 → 合流行（`RoomChatMergePlan.Bubble`）。
    ///
    /// - `id` 必须加 `bridge:` 命名空间：bridge 的 id 是 `"<sessionId>:<seq>"`（冒号），
    ///   网关的是 `"<sessionId>-<seq>"`（连字符），拿裸 id 参与任何比较都会恒不等（静默失效）。
    ///   `conversationId` 只用于让同屏多会话的 bridge 行彼此不撞号。
    /// - **特意不过滤"自己刚发出的本地回声"**（`localSubmission == true`）：KMP 已按
    ///   `(sessionId, requestId)` 对账，服务端回显一到，该条就会从列表里消失
    ///   （`BridgeChatMerge.kt:94-97`）。在这里再过滤一次，反而会在"回执未到"的窗口里
    ///   把自己的话整条抹掉——那是比重复更糟的故障。
    func roomChatBubble(conversationId: String) -> RoomChatMergePlan.Bubble {
        RoomChatMergePlan.Bubble(
            id: "bridge:\(conversationId):\(id)",
            role: isUser ? "user" : "assistant",
            text: text,
            seq: RoomChatMergePlan.normalizedSeq(seq),
            dateMillis: createdAtMillis,
            authorName: authorName
        )
    }
}

struct BridgeChatEventHead: Decodable {
    let type: String
    let eventSeq: Int64?
    let error: BridgeChatEventError?

    private enum CodingKeys: String, CodingKey { case type, eventSeq, error }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = (try? container.decode(String.self, forKey: .type)) ?? ""
        eventSeq = try? container.decodeIfPresent(Int64.self, forKey: .eventSeq)
        error = try? container.decodeIfPresent(BridgeChatEventError.self, forKey: .error)
    }
}

struct BridgeChatEventError: Decodable {
    let code: String?
    let message: String?
}

/// 历史页 envelope：只取分页游标。
struct BridgeHistoryCursor: Decodable {
    let cursor: String?
}

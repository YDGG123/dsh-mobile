import DeepSeekHarnessShared
import Foundation
import SwiftUI

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

    let conversationId: String
    let sessionId: String

    private let transport: BridgeTransport
    private let tokenProvider: () -> String?
    private let refreshToken: () async -> Bool
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
        refreshToken: @escaping () async -> Bool
    ) {
        self.transport = transport
        self.conversationId = conversationId
        self.sessionId = sessionId
        self.tokenProvider = tokenProvider
        self.refreshToken = refreshToken
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
            await self?.loadHistory()
            await self?.connect()
        }
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
            historyCursor = Self.cursor(from: body)
            canLoadOlder = historyCursor != nil
        } catch let status as BridgeResponseStatus where status.status == 401 {
            if await refreshToken(), let token = tokenProvider() {
                if let body = try? await transport.fetch(
                    path: "/conversations/\(conversationId)/messages?limit=50",
                    token: token
                ) {
                    messagesJson = BridgeChatMergeKt.chatMessagesFromHistory(historyJson: body)
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
        do {
            let body = try await transport.post(
                path: "/submissions",
                token: token,
                jsonBody: [
                    "conversationId": conversationId,
                    "sessionId": sessionId,
                    "requestId": requestId,
                    "text": trimmed
                ]
            )
            messagesJson = BridgeChatMergeKt.chatApplySubmissionReceipt(
                messagesJson: messagesJson,
                conversationId: conversationId,
                sessionId: sessionId,
                requestId: requestId,
                text: trimmed,
                receiptJson: body,
                nowMillis: Int64(Date().timeIntervalSince1970 * 1000)
            )
        } catch let status as BridgeResponseStatus where status.status == 401 {
            if await refreshToken(), let token = tokenProvider() {
                if let body = try? await transport.post(
                    path: "/submissions",
                    token: token,
                    jsonBody: [
                        "conversationId": conversationId,
                        "sessionId": sessionId,
                        "requestId": requestId,
                        "text": trimmed
                    ]
                ) {
                    messagesJson = BridgeChatMergeKt.chatApplySubmissionReceipt(
                        messagesJson: messagesJson,
                        conversationId: conversationId,
                        sessionId: sessionId,
                        requestId: requestId,
                        text: trimmed,
                        receiptJson: body,
                        nowMillis: Int64(Date().timeIntervalSince1970 * 1000)
                    )
                }
            }
        } catch {
            // 上屏失败态：保留 pending 行，用户可重试。
            status = .failed(error.localizedDescription)
        }
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

    var isUser: Bool { role == "user" }
    var isStreaming: Bool { pending == true }
    var isDeliveryFailed: Bool { delivery == "rejected" }
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

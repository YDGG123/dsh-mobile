import Foundation
import DeepSeekHarnessShared

/// 所有权在调用线程同步交接，避免旧容器的 Task 比新容器更晚进入 actor。
private final class ChatCacheProtectionOwners: @unchecked Sendable {
    private let lock = NSLock()
    private var current: [String: (UUID, Bool)] = [:]

    func claim(_ gatewayID: String) -> ChatHistoryCache.ProtectionOwner {
        lock.withLock {
            let owner = ChatHistoryCache.ProtectionOwner(gatewayID: gatewayID, id: UUID())
            current[gatewayID] = (owner.id, true)
            return owner
        }
    }

    func release(_ owner: ChatHistoryCache.ProtectionOwner) -> Bool {
        lock.withLock {
            guard current[owner.gatewayID]?.0 == owner.id else { return false }
            current[owner.gatewayID] = (owner.id, false)
            return true
        }
    }

    func permits(_ owner: ChatHistoryCache.ProtectionOwner, releasing: Bool) -> Bool {
        lock.withLock {
            current[owner.gatewayID]?.0 == owner.id && current[owner.gatewayID]?.1 == !releasing
        }
    }
}

/// 平台仅提供路径与 Swift 并发适配，数据库和容量策略全部复用 Room KMP。
actor ChatHistoryCache {
    struct ProtectionOwner: Sendable {
        fileprivate let gatewayID: String
        fileprivate let id: UUID
    }
    static let shared = ChatHistoryCache()
    private let url: URL
    private let maximumBytes: Int64
    private let targetBytes: Int64
    private var service: SharedChatHistoryCache?
    private var closed = false
    private enum LifecycleError: Error { case closed }
    private nonisolated let protectionOwners = ChatCacheProtectionOwners()
    private var protectionTail: Task<Void, Never>?
    private let protectionCommitBarrier: (@Sendable () async -> Void)?

    init(url: URL? = nil, maximumBytes: Int64 = 96 * 1024 * 1024,
         targetBytes: Int64 = 72 * 1024 * 1024,
         protectionCommitBarrier: (@Sendable () async -> Void)? = nil) {
        self.url = url ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ChatHistory/chat-history-room.db")
        self.maximumBytes = maximumBytes
        self.targetBytes = targetBytes
        self.protectionCommitBarrier = protectionCommitBarrier
    }

    private func database() throws -> SharedChatHistoryCache {
        guard !closed else { throw LifecycleError.closed }
        if let service { return service }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let service = try IosChatHistoryCacheFactory().create(path: url.path, maximumBytes: maximumBytes, targetBytes: targetBytes)
            self.service = service
            return service
        } catch {
            ChatCacheDiagnostics.shared.databaseUnavailable()
            throw error
        }
    }

    nonisolated func makeProtectionOwner(gatewayID: String) -> ProtectionOwner {
        protectionOwners.claim(gatewayID)
    }

    @discardableResult
    nonisolated func releaseProtection(_ owner: ProtectionOwner) -> Task<Void, Never>? {
        guard protectionOwners.release(owner) else { return nil }
        return Task { await commitProtection(owner, sessionIDs: [], releasing: true) }
    }

    func protect(owner: ProtectionOwner, sessionIDs: Set<String>) async {
        await commitProtection(owner, sessionIDs: sessionIDs, releasing: false)
    }

    private func commitProtection(_ owner: ProtectionOwner, sessionIDs: Set<String>, releasing: Bool) async {
        let previous = protectionTail
        let operation = Task { [self] in
            await previous?.value
            guard protectionOwners.permits(owner, releasing: releasing),
                  !releasing || service != nil, let service = try? database() else { return }
            // 串行链跨越 Kotlin suspend；新 owner 的提交必定在旧提交完成之后。
            await protectionCommitBarrier?()
            try? await service.protect(gatewayId: owner.gatewayID, sessionIds: Array(sessionIDs))
        }
        protectionTail = operation
        await operation.value
    }

    func load(gatewayID: String, sessionID: String) async -> String? {
        guard let service = try? database() else { return nil }
        return try? await service.load(gatewayId: gatewayID, sessionId: sessionID)
    }

    @discardableResult
    func save(gatewayID: String, sessionID: String, json: String, generation: UInt64 = 0, epoch: Int64? = nil) async -> Bool {
        guard let service = try? database() else { return false }
        if let epoch {
            return (try? await service.saveAtEpoch(gatewayId: gatewayID, sessionId: sessionID,
                json: json, generation: Int64(clamping: generation), expectedEpoch: epoch))?.boolValue ?? false
        }
        return (try? await service.save(gatewayId: gatewayID, sessionId: sessionID, json: json,
                                       generation: Int64(clamping: generation)))?.boolValue ?? false
    }

    func recordSessionOpen(owner: ProtectionOwner, sessionID: String) async {
        guard protectionOwners.permits(owner, releasing: false), let service = try? database() else { return }
        try? await service.recordSessionOpen(gatewayId: owner.gatewayID, sessionId: sessionID)
    }

    func planPrefetch(owner: ProtectionOwner, candidates: [ChatSessionPrefetcher.Candidate], selected: String?) async -> [String] {
        guard protectionOwners.permits(owner, releasing: false), let service = try? database(),
              let encoded = try? JSONEncoder().encode(candidates) else { return [] }
        guard let json = try? await service.planPrefetch(gatewayId: owner.gatewayID,
            candidatesJson: String(decoding: encoded, as: UTF8.self), selectedSessionId: selected),
              protectionOwners.permits(owner, releasing: false) else { return [] }
        return (try? JSONDecoder().decode([String].self, from: Data(json.utf8))) ?? []
    }

    func prefetchTicket(owner: ProtectionOwner, sessionID: String) async -> String? {
        guard protectionOwners.permits(owner, releasing: false), let service = try? database() else { return nil }
        guard let token = try? await service.prefetchTicket(gatewayId: owner.gatewayID, sessionId: sessionID), !token.isEmpty else { return nil }
        return token
    }

    func acceptPrefetch(owner: ProtectionOwner, sessionID: String, json: String, ticket: String) async -> Bool {
        guard !Task.isCancelled, protectionOwners.permits(owner, releasing: false), let service = try? database() else { return false }
        return (try? await service.acceptPrefetchHistory(gatewayId: owner.gatewayID, sessionId: sessionID,
            rawJson: json, ticket: ticket))?.boolValue ?? false
    }

    func cancelPrefetch(owner: ProtectionOwner, sessionID: String, ticket: String) async {
        guard let service else { return }
        try? await service.cancelPrefetch(gatewayId: owner.gatewayID, sessionId: sessionID, ticket: ticket)
    }

    func remove(gatewayID: String, sessionID: String, generation: UInt64 = DispatchTime.now().uptimeNanoseconds) async {
        guard let service = try? database() else { return }
        try? await service.remove(gatewayId: gatewayID, sessionId: sessionID, generation: Int64(clamping: generation))
    }

    func loadTyped(gatewayID: String, sessionID: String, before: Int? = nil, limit: Int = 80) async -> CachedHistoryWindow? {
        guard let service = try? database() else { return nil }
        return try? await service.loadTypedPage(gatewayId: gatewayID, sessionId: sessionID,
            beforeSequence: before.map { KotlinInt(int: Int32(clamping: $0)) }, limit: Int32(clamping: limit))
    }

    func epoch() async -> Int64 { (try? await database().epoch())?.int64Value ?? 0 }

    func search(gatewayID: String, query: String) async -> String? {
        guard let service = try? database() else { return nil }
        return try? await service.search(gatewayId: gatewayID, query: query, sessionId: nil, limit: 100)
    }

    func loadOfflineCatalog(gatewayID: String) async -> String? {
        guard let service = try? database() else { return nil }
        return try? await service.loadOfflineCatalog(gatewayId: gatewayID)
    }

    func saveOfflineCatalog(owner: ProtectionOwner, json: String) async -> Bool {
        guard protectionOwners.permits(owner, releasing: false), let service = try? database(),
              let ticket = try? await service.offlineCatalogTicket(gatewayId: owner.gatewayID),
              protectionOwners.permits(owner, releasing: false) else { return false }
        return (try? await service.saveOfflineCatalog(gatewayId: owner.gatewayID,
            catalogJson: json, ticket: ticket))?.boolValue ?? false
    }

    func forget(gatewayID: String) async {
        guard let service = try? database() else { return }
        try? await service.clear(gatewayId: gatewayID)
        try? await service.forgetOfflineCatalog(gatewayId: gatewayID)
    }

    func statistics() async -> String? {
        guard let service = try? database() else { return nil }
        return try? await service.statistics()
    }

    func clear(gatewayID: String? = nil) async {
        guard let service = try? database() else { return }
        try? await service.clear(gatewayId: gatewayID)
    }

    func trim(to bytes: Int64) async {
        guard let service = try? database() else { return }
        _ = try? await service.trimToBytes(targetBytes: bytes)
    }

    func trimMemory() async {
        guard let service = try? database() else { return }
        try? await service.trimMemory()
    }

    func close() async {
        guard !closed else { return }
        // await 会允许 actor 重入，必须先关闭准入，迟到读取/维护不能重开数据库。
        closed = true
        await protectionTail?.value
        let closingService = service
        service = nil
        try? await closingService?.close()
    }
}

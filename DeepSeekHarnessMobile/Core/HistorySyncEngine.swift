import Foundation

/// 阅读批次与传输页分开计量；只累计新增正文，不把工具事件和 Markdown 分块当成消息。
struct HistoryReadingBatch: Sendable, Equatable {
    let operationID: UUID
    let originalIDs: Set<String>
    let viewportHeight: Double
    let contentWidth: Double
    let fontScale: Double
    let startedAt: TimeInterval
    var beforeSequence: Int
    var minimumEventSequence: Int64 = 0
    var rounds = 0
    var responseReady = false
    var failed = false

    struct Window: Sendable {
        var messages = 0
        var height = 0.0
        var characters = 0
    }

    nonisolated func window(in items: [ConversationItem]) -> Window {
        var result = Window()
        for item in items {
            if originalIDs.contains(item.id) { break }
            result.characters += item.text.utf16.count
            guard item.kind == .user || item.kind == .assistant else { continue }
            result.messages += 1
            let width = max(80, contentWidth - 32)
            let lines = item.text.split(separator: "\n", omittingEmptySubsequences: false).reduce(0.0) { count, line in
                let units = line.unicodeScalars.reduce(0.0) { $0 + ($1.value > 255 ? 16 : 8) }
                return count + max(1, ceil(units * fontScale / width))
            }
            result.height += lines * 24 * fontScale + 48
            if !item.images.isEmpty { result.height += 160 }
            result.height += Double(item.files.count) * 48
        }
        return result
    }

    nonisolated func stopReason(window: Window, earliestSequence: Int?, hasMore: Bool, now: TimeInterval) -> String? {
        if failed { return "error" }
        if !hasMore { return "end" }
        guard let earliestSequence, earliestSequence < beforeSequence else { return "no-progress" }
        if window.height >= viewportHeight * 2 { return "window-filled" }
        if window.characters >= 1_048_576 { return "content-budget" }
        if rounds >= 8 { return "round-budget" }
        if now - startedAt >= 2.5 { return "time-budget" }
        return nil
    }
}

struct HistorySyncConfiguration: Equatable {
    var pageMessageLimit = 60
    var pageByteBudget = 4 * 1024 * 1024
    var pagesPerBatch = 2

    static let `default` = HistorySyncConfiguration()
}

@MainActor
final class HistorySyncEngine {
    let configuration: HistorySyncConfiguration
    private let timeoutTracker = RequestTracker()
    private var generations: [String: UUID] = [:]

    init(configuration: HistorySyncConfiguration = .default) {
        self.configuration = configuration
    }

    func beginRequest(
        sessionID: String,
        timeout: Duration,
        onTimeout: @escaping @MainActor () -> Void
    ) {
        let generation = UUID()
        generations[sessionID] = generation
        timeoutTracker.begin(sessionID, timeout: timeout) { [weak self] in
            guard let self, self.generations[sessionID] == generation else { return }
            self.generations[sessionID] = nil
            onTimeout()
        }
    }

    func beginProcessing(sessionID: String) -> UUID {
        timeoutTracker.finish(sessionID)
        let generation = UUID()
        generations[sessionID] = generation
        return generation
    }

    func isCurrent(_ generation: UUID, sessionID: String) -> Bool {
        generations[sessionID] == generation
    }

    func isActive(sessionID: String) -> Bool {
        generations[sessionID] != nil
    }

    func finish(sessionID: String) {
        timeoutTracker.finish(sessionID)
        generations[sessionID] = nil
    }

    func cancelAll() {
        timeoutTracker.finishAll()
        generations.removeAll()
    }
}

import Foundation

@MainActor
final class RequestTracker {
    private var generations: [String: UUID] = [:]
    private var timeoutTasks: [String: Task<Void, Never>] = [:]
    private var pendingTimeouts: [String: (duration: Duration, action: @MainActor () -> Void)] = [:]
    private var isPaused = false

    var activeKeys: Set<String> { Set(generations.keys) }

    func begin(
        _ key: String,
        timeout: Duration,
        onTimeout: @escaping @MainActor () -> Void
    ) {
        finish(key)
        let generation = UUID()
        generations[key] = generation
        pendingTimeouts[key] = (timeout, onTimeout)
        if !isPaused { schedule(key, generation: generation) }
    }

    /// 后台与连接验证期间不计算业务超时；恢复后给予完整的响应等待窗口。
    func pause() {
        isPaused = true
        for task in timeoutTasks.values { task.cancel() }
        timeoutTasks.removeAll()
    }

    func resume() {
        guard isPaused else { return }
        isPaused = false
        for (key, generation) in generations { schedule(key, generation: generation) }
    }

    private func schedule(_ key: String, generation: UUID) {
        guard let pending = pendingTimeouts[key] else { return }
        timeoutTasks[key] = Task { [weak self] in
            do {
                try await Task.sleep(for: pending.duration)
            } catch {
                return
            }
            guard let self,
                  !Task.isCancelled,
                  !self.isPaused,
                  self.generations[key] == generation else { return }
            self.generations[key] = nil
            self.timeoutTasks[key] = nil
            self.pendingTimeouts[key] = nil
            pending.action()
        }
    }

    func finish(_ key: String) {
        generations[key] = nil
        pendingTimeouts[key] = nil
        timeoutTasks.removeValue(forKey: key)?.cancel()
    }

    func finishAll() {
        for task in timeoutTasks.values { task.cancel() }
        timeoutTasks.removeAll()
        generations.removeAll()
        pendingTimeouts.removeAll()
    }
}

import Foundation
import os

/// 大响应分块重组（协议中立、无网关依赖）。
///
/// 背景：宿主网关历史上有一条 2MB 缓冲红线，超大快照/大响应会把手机直接踢下线。
/// 抬阈值解决不了根因 —— 正确做法是把大 payload 切成固定大小的块发，手机按序号拼。
/// 网关侧通过 `/info` 公布三个上限（单块、重组、并发流），手机只在 **hello 里声明
/// `chunked-frames-v1` 能力**之后才会收到 `chunk` 帧。
///
/// 设计红线：
/// - **任何异常都不静默**：丢块、乱序、超体积、并发超限、超时，全部返回明确错误，
///   由调用方记日志并重拉快照 —— 宁可重来，也不拼半个 JSON 交给上层。
/// - **乱序/重复块安全**：块带 `index`，乱序到达按 `index` 归位；重复块按幂等覆盖处理。
/// - **体积先算后存**：累计 UTF-8 字节数超上限立即丢弃该流，不无限吃内存。
struct FrameReassembler {

    /// 一条 chunk 帧解出来的内容。
    struct Chunk: Equatable {
        let streamId: String
        let index: Int
        let total: Int
        /// 重组后要走回的原处理链（`snapshot` / `delta` / `cards` / `order` …）。
        let finalKind: String
        /// 已按 UTF-8 字符边界切好的原始 JSON 片段。
        let part: String
    }

    /// 网关公布（或本地兜底）的三个上限。
    struct Limits: Equatable {
        /// 单块上限（UTF-8 字节）。收到超限块直接判错 —— 说明网关和宣传值不一致。
        var maxChunkBytes: Int = 512 * 1024
        /// 单条流重组后的上限（UTF-8 字节）。
        var maxReassembledBytes: Int = 4 * 1024 * 1024
        /// 同一条连接上允许同时存在的流数。
        var maxStreams: Int = 2
        /// 首块到达后多久还没收齐就丢弃（客户端内部资源保护，网关不公布）。
        var streamTimeout: TimeInterval = 30

        static let `default` = Limits()

        /// 单块上限的绝对天花板：再大也不接受（防止网关公布一个离谱值把内存吃掉）。
        static let absoluteChunkCap = 2 * 1024 * 1024
        /// 重组总量下限：小于这个值连一条普通快照都装不下，说明公布值不可信。
        static let minimumReassembled = 64 * 1024

        /// **唯一的夹取入口**。任何来自外部的 Limits（`/info`、测试、将来的新字段）
        /// 都必须走这里，否则 8MiB 硬帽会被生产路径绕过（P2 的根因就是 `update` 直接赋值）。
        var clamped: Limits {
            var result = self
            result.maxChunkBytes = min(max(maxChunkBytes, 1024), Limits.absoluteChunkCap)
            result.maxReassembledBytes = min(max(maxReassembledBytes, Limits.minimumReassembled),
                                             FrameReassembler.absoluteReassembledCap)
            result.maxStreams = min(max(maxStreams, 1), 4)
            result.streamTimeout = min(max(streamTimeout, 1), 300)
            return result
        }
    }

    /// 握手能力名：手机在 WS auth 帧里声明它，网关才发 chunk 帧（不声明 = 永远收整帧）。
    static let capabilityName = "chunked-frames-v1"

    /// 从网关 `/info` 的 capabilities 解析上限（字段名以网关为准：
    /// `maxFrameBytes` / `maxReassembledBytes` / `maxStreamsPerConnection`）。
    /// **一个都没有 → nil**（老网关不公布，调用方保持本地兜底，不猜）。
    static func limits(fromInfo infoJSON: String) -> Limits? {
        guard let data = infoJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let caps = object["capabilities"] as? [String: Any] else { return nil }
        var found = false
        var limits = Limits.default
        if let value = (caps["maxFrameBytes"] as? NSNumber)?.doubleValue, value > 0 {
            limits.maxChunkBytes = Int(value); found = true
        }
        if let value = (caps["maxReassembledBytes"] as? NSNumber)?.doubleValue, value > 0 {
            limits.maxReassembledBytes = Int(value); found = true
        }
        if let value = (caps["maxStreamsPerConnection"] as? NSNumber)?.doubleValue, value > 0 {
            limits.maxStreams = Int(value); found = true
        }
        return found ? limits : nil
    }

    enum ReassemblyError: Error, Equatable {
        /// 单块字节数超过网关公布的上限（数据面/宣传值不一致）。
        case chunkTooLarge(bytes: Int, limit: Int)
        /// 该流累计超过重组上限。
        case streamTooLarge(bytes: Int, limit: Int)
        /// 同时存在的流超过上限。
        case tooManyStreams(limit: Int)
        /// index 越界（负值或 ≥ total）。
        case indexOutOfRange(index: Int, total: Int)
        /// total/part 非法（total ≤ 0、part 为空但 total > 1 等）。
        case malformedChunk(String)
        /// 该流已超时被丢弃，后续块只能重新拉快照。
        case streamExpired(streamId: String)
    }

    /// 单条流的累积状态。
    private struct Stream {
        var total: Int
        var finalKind: String
        var parts: [String?]
        var received = 0
        var bytes = 0
        var startedAt: Date
    }

    private(set) var limits: Limits
    private var streams: [String: Stream] = [:]
    private let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-chunk")
    private let now: () -> Date

    init(limits: Limits = .default, now: @escaping () -> Date = Date.init) {
        // 任何构造路径都夹取一遍：将来加新入口也绕不开。
        self.limits = limits.clamped
        self.now = now
    }

    /// 更新上限（来自网关 `/info`）。**必须经过 `Limits.clamped`**，别在别处再写一遍夹取。
    mutating func apply(limits new: Limits) {
        let merged = new.clamped
        let current = limits
        if merged != current {
            limits = merged
            log.notice("limits updated chunk=\(merged.maxChunkBytes, privacy: .public) reassembled=\(merged.maxReassembledBytes, privacy: .public) streams=\(merged.maxStreams, privacy: .public)")
        }
    }

    /// 重组的硬天花板：不管网关公布什么，都不超过 8MiB。
    static let absoluteReassembledCap = 8 * 1024 * 1024

    /// 喂一块。返回 `.success(payload)` 表示这条流已收齐（`payload` 是原始 JSON 字符串），
    /// `.success(nil)` 表示还差块；`failure` 一律意味着该流已被丢弃、调用方应重拉快照。
    mutating func accept(_ chunk: Chunk) -> Result<String?, ReassemblyError> {
        let partBytes = chunk.part.utf8.count
        // 日志里用局部变量而不是 self.xxx：mutating 方法里的 OSLog 插值会捕获 self。
        let chunkLimit = limits.maxChunkBytes
        if partBytes > chunkLimit {
            discard(chunk.streamId)
            log.error("chunk too large stream=\(chunk.streamId, privacy: .public) bytes=\(partBytes, privacy: .public) limit=\(chunkLimit, privacy: .public)")
            return .failure(.chunkTooLarge(bytes: partBytes, limit: chunkLimit))
        }
        guard chunk.total > 0, chunk.index >= 0, chunk.index < chunk.total, !chunk.finalKind.isEmpty else {
            discard(chunk.streamId)
            log.error("malformed chunk stream=\(chunk.streamId, privacy: .public) index=\(chunk.index, privacy: .public) total=\(chunk.total, privacy: .public)")
            return .failure(.indexOutOfRange(index: chunk.index, total: chunk.total))
        }

        if var stream = streams[chunk.streamId] {
            guard stream.total == chunk.total, stream.finalKind == chunk.finalKind else {
                // 同一 streamId 换了 total/类型：视为协议错乱，丢弃重来。
                discard(chunk.streamId)
                log.error("stream mismatch stream=\(chunk.streamId, privacy: .public) total \(stream.total, privacy: .public)→\(chunk.total, privacy: .public)")
                return .failure(.malformedChunk("total/finalKind changed mid-stream"))
            }
            if stream.parts[chunk.index] == nil {
                stream.parts[chunk.index] = chunk.part
                stream.received += 1
                stream.bytes += partBytes
                let reassemblyLimit = limits.maxReassembledBytes
                if stream.bytes > reassemblyLimit {
                    let total = stream.bytes
                    discard(chunk.streamId)
                    log.error("stream too large stream=\(chunk.streamId, privacy: .public) bytes=\(total, privacy: .public) limit=\(reassemblyLimit, privacy: .public)")
                    return .failure(.streamTooLarge(bytes: total, limit: reassemblyLimit))
                }
            } else {
                // 重复块：幂等覆盖（网关重发/网络重放都可能出现），不重复计入 received。
                stream.parts[chunk.index] = chunk.part
            }
            streams[chunk.streamId] = stream
        } else {
            let streamLimit = limits.maxStreams
            if streams.count >= streamLimit {
                log.error("too many streams limit=\(streamLimit, privacy: .public) drop=\(chunk.streamId, privacy: .public)")
                return .failure(.tooManyStreams(limit: streamLimit))
            }
            var parts = [String?](repeating: nil, count: chunk.total)
            parts[chunk.index] = chunk.part
            streams[chunk.streamId] = Stream(total: chunk.total, finalKind: chunk.finalKind,
                                            parts: parts, received: 1, bytes: partBytes, startedAt: now())
        }

        guard let stream = streams[chunk.streamId], stream.received == stream.total else { return .success(nil) }
        let payload = stream.parts.compactMap { $0 }.joined()
        discard(chunk.streamId)
        guard !payload.isEmpty else {
            log.error("empty payload stream=\(chunk.streamId, privacy: .public)")
            return .failure(.malformedChunk("reassembled payload is empty"))
        }
        log.debug("stream complete stream=\(chunk.streamId, privacy: .public) kind=\(stream.finalKind, privacy: .public) bytes=\(payload.utf8.count, privacy: .public)")
        return .success(payload)
    }

    /// 清掉超时的流，返回被丢弃的 streamId（调用方据此记日志/重拉快照）。
    mutating func expire(now current: Date? = nil) -> [String] {
        let instant = current ?? now()
        var dropped: [String] = []
        for (streamId, stream) in streams where instant.timeIntervalSince(stream.startedAt) > limits.streamTimeout {
            dropped.append(streamId)
        }
        let timeout = limits.streamTimeout
        for streamId in dropped {
            streams.removeValue(forKey: streamId)
            log.error("stream expired stream=\(streamId, privacy: .public) after=\(timeout, privacy: .public)s")
        }
        return dropped
    }

    /// 当前正在重组的流数（诊断/测试用）。
    var activeStreamCount: Int { streams.count }

    /// 重置（断开连接/重新配对时调用，避免半截流残留）。
    mutating func reset() {
        let count = streams.count
        if count > 0 {
            log.notice("reset clearing \(count, privacy: .public) stream(s)")
        }
        streams.removeAll()
    }

    private mutating func discard(_ streamId: String) {
        streams.removeValue(forKey: streamId)
    }

    // MARK: - 帧解析（把一条 `{"kind":"chunk",…}` 帧解成 Chunk）

    /// 解析 WS/HTTP 帧里的 chunk 字段；不是 chunk 帧或字段不全返回 nil（含明确的诊断）。
    static func chunk(from object: [String: Any]) -> Chunk? {
        guard (object["kind"] as? String) == "chunk" else { return nil }
        guard let streamId = object["streamId"] as? String, !streamId.isEmpty,
              let index = (object["index"] as? NSNumber)?.intValue,
              let total = (object["total"] as? NSNumber)?.intValue,
              let finalKind = object["finalKind"] as? String, !finalKind.isEmpty,
              let part = object["part"] as? String else { return nil }
        return Chunk(streamId: streamId, index: index, total: total, finalKind: finalKind, part: part)
    }
}

/// 网关公布的分块上限（由 `BridgeTeamAdapter` 在 `/info` 之后更新，团队 WS 建连时读取）。
/// 单例而非逐流传递：团队/卡片两个 feed 的生命周期比 adapter 短，建连时读一次最新值即可；
/// 断开重连会重新读，所以网关调参后下一次重连生效（不必重启 App）。
@MainActor
enum BridgeChunkLimits {
    private(set) static var shared: FrameReassembler.Limits = .default
    /// 更新上限。传 nil（网关不公布）时保持现状，不做"猜一个"。
    ///
    /// **必须走 `Limits.clamped`**：生产路径 `网关 /info → 这里 → 直接赋值` 曾绕过夹取，
    /// 网关公布 64MiB 手机就真吃 64MiB（P2）。现在夹取只在 `Limits.clamped` 一处，
    /// 将来新增字段只要走 `clamped` 就绕不开。
    static func update(fromInfo infoJSON: String) {
        guard let parsed = FrameReassembler.limits(fromInfo: infoJSON) else { return }
        // 保留本地兜底里的非协议项（streamTimeout 等），只覆盖网关公布的三项。
        var merged = FrameReassembler.Limits.default
        merged.maxChunkBytes = parsed.maxChunkBytes
        merged.maxReassembledBytes = parsed.maxReassembledBytes
        merged.maxStreams = parsed.maxStreams
        let clamped = merged.clamped
        if clamped != shared { shared = clamped }
    }
}

/// 分块流的超时清扫（P1 修复：`expire()` 不能只在"收到新块"时调用）。
///
/// 故障形态：网关发到一半停住 —— 恰恰是做分块要防的场景 —— 此时不会再有 chunk 到达，
/// 纯靠"收块时顺带 expire"就永远不触发，半截流会一直占内存。
/// 所以流一开就挂一个按固定节奏的清扫任务，**只推进时钟、不依赖新块**。
///
/// `sleep` 可注入：测试里换成"记账 + 立即返回"的假实现，就能同步驱动多轮清扫，
/// 不用真的等 30 秒。
@MainActor
final class ChunkStreamSweeper {
    /// 清扫节奏：取超时的 1/6（30s → 5s），既不会太迟也不会太频繁。
    /// `nonisolated`：纯计算，测试/非主线程都能直接调。
    nonisolated static func interval(forTimeout timeout: TimeInterval) -> TimeInterval {
        max(0.5, timeout / 6)
    }

    private let interval: TimeInterval
    private let sleep: @MainActor (TimeInterval) async -> Void
    private let onTick: @MainActor () -> Void
    private var task: Task<Void, Never>?
    private(set) var ticks = 0

    init(interval: TimeInterval,
         sleep: @escaping @MainActor (TimeInterval) async -> Void = { seconds in
             try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0.05) * 1_000_000_000))
         },
         onTick: @escaping @MainActor () -> Void) {
        self.interval = max(interval, 0.05)
        self.sleep = sleep
        self.onTick = onTick
    }

    var isRunning: Bool { task != nil }

    func start() {
        guard task == nil else { return }
        task = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.sleep(self.interval)
                guard !Task.isCancelled else { return }
                self.ticks += 1
                self.onTick()
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    deinit { task?.cancel() }
}

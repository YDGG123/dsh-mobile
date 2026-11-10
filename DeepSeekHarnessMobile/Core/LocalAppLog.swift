import Foundation
import LocalAuthentication

/// 日志与导出快照共用 200 MB 配额，所有磁盘操作在专用串行队列执行。
final class LocalAppLog: @unchecked Sendable {
    static let shared = LocalAppLog()
    private let queue = DispatchQueue(label: "ai.dsh.local-logs", qos: .utility)
    private let pending = DispatchSemaphore(value: 1024)
    private let directory: URL
    private let limit: Int64
    private let hour = DateFormatter()
    private let timestamp = DateFormatter()
    private let lineTime = ISO8601DateFormatter()
    private let files = FileManager.default

    init(directory: URL? = nil, limit: Int64 = 200_000_000) {
        precondition(limit > 0 && limit <= 200_000_000)
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DiagnosticLogs", isDirectory: true)
        self.limit = limit
        for formatter in [hour, timestamp] {
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
        }
        hour.dateFormat = "yyyyMMddHH'00'"
        timestamp.dateFormat = "yyyyMMddHHmm"
        queue.async { [self] in
            do {
                try files.createDirectory(at: self.directory, withIntermediateDirectories: true)
                var url = self.directory
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try url.setResourceValues(values)
                try? files.removeItem(at: exports)
                try trim(to: limit)
            } catch { NSLog("Local log initialization failed: %@", error.localizedDescription) }
        }
    }

    private var exports: URL { directory.appendingPathComponent("Exports", isDirectory: true) }

    /// 结构化白名单事件（规格 §4.6）：只有事件名、状态码、耗时、错误类别和可选
    /// detail 会落盘；消息正文、URL、未知对象在类型层面就进不来，detail 仍要过集中脱敏。
    func write(event: String, status: Int? = nil, durationMillis: Int64? = nil,
               category: String? = nil, detail: String? = nil, at date: Date = Date()) {
        var line = "EVENT \(LogRedactor.scrub(String(event.prefix(120))))"
        if let status { line += " status=\(status)" }
        if let durationMillis { line += " durationMs=\(durationMillis)" }
        if let category { line += " category=\(LogRedactor.scrub(String(category.prefix(80))))" }
        if let detail, !detail.isEmpty { line += " detail=\(LogRedactor.scrub(detail))" }
        write(line, at: date)
    }

    func write(_ message: String, at date: Date = Date()) {
        guard pending.wait(timeout: .now()) == .success else { return }
        // 写入前集中脱敏（规格 §4.6）：调用点不再依赖“记得别传 secret”。
        let text = String(LogRedactor.scrub(message).prefix(16_384))
        queue.async { [self] in
            defer { pending.signal() }
            do {
                let data = Data("\(lineTime.string(from: date)) \(text)\n".utf8)
                guard Int64(data.count) <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
                try trim(to: limit - Int64(data.count))
                let prefix = hour.string(from: date)
                let chunks = try logs().filter {
                    $0.deletingPathExtension().lastPathComponent == prefix || $0.lastPathComponent.hasPrefix("\(prefix)-part-")
                }
                var url = chunks.last ?? directory.appendingPathComponent("\(prefix).log")
                if ((try? size(url)) ?? 0) + Int64(data.count) > min(4_000_000, limit / 4) {
                    let next = (Int(url.deletingPathExtension().lastPathComponent.components(separatedBy: "-part-").last ?? "") ?? 0) + 1
                    let part = String(format: "%05d", next)
                    url = directory.appendingPathComponent("\(prefix)-part-\(part).log")
                }
                if !files.fileExists(atPath: url.path) { try Data().write(to: url) }
                let handle = try FileHandle(forWritingTo: url)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } catch { NSLog("Local log write failed: %@", error.localizedDescription) }
        }
    }

    private func logs() throws -> [URL] {
        try files.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
            .filter { $0.pathExtension == "log" }.sorted { $0.deletingPathExtension().lastPathComponent < $1.deletingPathExtension().lastPathComponent }
    }

    private func size(_ url: URL) throws -> Int64 {
        Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
    }

    private func directoryFiles(in root: URL? = nil) throws -> [URL] {
        let entries = try files.contentsOfDirectory(at: root ?? directory,
                                                    includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        var result: [URL] = []
        for url in entries {
            let values = try url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw CocoaError(.fileReadUnsupportedScheme) }
            if values.isDirectory == true { result += try directoryFiles(in: url) }
            else { result.append(url) }
        }
        return result
    }

    private func directoryBytes() throws -> Int64 {
        try directoryFiles().reduce(Int64(0)) { try $0 + size($1) }
    }

    private func trim(to budget: Int64) throws {
        guard budget >= 0 else { throw CocoaError(.fileWriteOutOfSpace) }
        // 递归计入旧版本、嵌套目录和中断导出的残留文件；优先回收最旧日志。
        let candidates = try directoryFiles().sorted {
            let leftExport = $0.deletingLastPathComponent() == exports
            let rightExport = $1.deletingLastPathComponent() == exports
            if leftExport != rightExport { return !leftExport }
            let leftDate = try $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast
            let rightDate = try $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast
            return leftDate == rightDate ? $0.path < $1.path : leftDate < rightDate
        }
        var total = try candidates.reduce(Int64(0)) { try $0 + size($1) }
        for url in candidates {
            if total <= budget { break }
            let bytes = try size(url)
            try files.removeItem(at: url)
            total -= bytes
        }
        guard try directoryBytes() <= budget else { throw CocoaError(.fileWriteOutOfSpace) }
    }

    struct Summary: Sendable {
        let fileCount: Int
        let bytes: Int64
        let earliest: Date?
    }

    /// 读取单文件并做导出侧二次脱敏（规格 §4.6：不能只依赖写入时那一遍——
    /// 盘上可能残留旧版本写入的明文，导出副本必须干净）。
    private func selected(_ source: URL, interval: DateInterval?) throws -> Data {
        let text = try String(contentsOf: source, encoding: .utf8)
        guard let interval else { return Data(LogRedactor.scrub(text).utf8) }
        var include = false
        var result = ""
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        for line in lines.dropLast(text.hasSuffix("\n") ? 1 : 0) {
            if let token = line.split(separator: " ", maxSplits: 1).first,
               let time = lineTime.date(from: String(token)) {
                include = time >= interval.start && time < interval.end
            }
            if include { result += LogRedactor.scrub(String(line)) + "\n" }
        }
        return Data(result.utf8)
    }

    func summary(in interval: DateInterval) async throws -> Summary {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    var count = 0
                    var bytes: Int64 = 0
                    var earliest: Date?
                    for url in try logs() {
                        let text = try String(contentsOf: url, encoding: .utf8)
                        if let token = text.split(separator: " ", maxSplits: 1).first,
                           let date = lineTime.date(from: String(token)) {
                            earliest = min(earliest ?? date, date)
                        }
                        let data = try selected(url, interval: interval)
                        if !data.isEmpty { count += 1; bytes += Int64(data.count) }
                    }
                    continuation.resume(returning: Summary(fileCount: count, bytes: bytes, earliest: earliest))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    /// 导出授权铸造器：默认走系统解锁（Face ID / 设备密码，规格 §4.6「导出前需要
    /// App 解锁」）；测试目标可注入直通实现。仅在并发使用前设置一次。
    nonisolated(unsafe) var exportAuthorizer: (String) async throws -> LogExportGrant = LogExportGrant.standardRequest

    /// ZIP 使用 STORE 编码，逐个小时分片写入，避免把全部日志装入内存。
    /// grant 为空壳授权凭证：只能在模块内经解锁流程铸造，用于把「先解锁再导出」
    /// 变成类型层面的强制顺序。
    func exportArchive(in interval: DateInterval, grant: LogExportGrant) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    let target = try writeLogArchive(in: interval)
                    continuation.resume(returning: target)
                } catch {
                    try? files.removeItem(at: exports)
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// 上游 1.7.15 的 ZIP 例程（原内嵌于双层闭包，本机 swiftc 26.3 类型检查超预算，
    /// 拆成独立方法；导出文件名随品牌改为 Orcarness-logs-*）。
    private func writeLogArchive(in interval: DateInterval) throws -> URL {
    try? files.removeItem(at: exports)
    try files.createDirectory(at: exports, withIntermediateDirectories: true)
    try trim(to: max(0, (limit - 1_000_000) / 2))
    let target = exports.appendingPathComponent("Orcarness-logs-\(timestamp.string(from: Date())).zip")
    try Data().write(to: target)
    let handle = try FileHandle(forWritingTo: target)
    defer { try? handle.close() }
    var central = Data()
    var offset: UInt32 = 0
    var count: UInt16 = 0
    let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date())
    let yearsSince1980 = max(1980, min(2107, parts.year ?? 1980)) - 1980
    let monthNumber = parts.month ?? 1
    let dayNumber = parts.day ?? 1
    let dosDate = UInt16((yearsSince1980 << 9) | (monthNumber << 5) | dayNumber)
    let hourNumber = parts.hour ?? 0
    let minuteNumber = parts.minute ?? 0
    let secondNumber = (parts.second ?? 0) / 2
    let dosTime = UInt16((hourNumber << 11) | (minuteNumber << 5) | secondNumber)
    let remaining = try limit - directoryBytes()
    func write(_ data: Data) throws {
        guard Int64(offset) + Int64(data.count) <= remaining else { throw CocoaError(.fileWriteOutOfSpace) }
        try handle.write(contentsOf: data)
        offset += UInt32(data.count)
    }
    for source in try logs() {
        let data = try selected(source, interval: interval)
        guard !data.isEmpty else { continue }
        let name = Data(source.lastPathComponent.utf8)
        let crc = LogZip.crc(data)
        let size = UInt32(data.count)
        let recordOffset = offset
        var header = Data()
        header.logLE(UInt32(0x04034b50)); header.logLE(UInt16(20))
        header.logLE(UInt16(0)); header.logLE(UInt16(0)); header.logLE(dosTime); header.logLE(dosDate)
        header.logLE(crc); header.logLE(size); header.logLE(size)
        header.logLE(UInt16(name.count)); header.logLE(UInt16(0)); header.append(name)
        try write(header); try write(data)
        central.logLE(UInt32(0x02014b50)); central.logLE(UInt16(20)); central.logLE(UInt16(20))
        central.logLE(UInt16(0)); central.logLE(UInt16(0)); central.logLE(dosTime); central.logLE(dosDate)
        central.logLE(crc); central.logLE(size); central.logLE(size)
        central.logLE(UInt16(name.count))
        for _ in 0..<4 { central.logLE(UInt16(0)) }
        central.logLE(UInt32(0)); central.logLE(recordOffset); central.append(name)
        count += 1
    }
    guard count > 0 else { throw CocoaError(.fileNoSuchFile) }
    let centralOffset = offset
    try write(central)
    var end = Data()
    end.logLE(UInt32(0x06054b50)); end.logLE(UInt16(0)); end.logLE(UInt16(0))
    end.logLE(count); end.logLE(count); end.logLE(UInt32(central.count)); end.logLE(centralOffset); end.logLE(UInt16(0))
    try write(end)
        return target
    }

    func export(grant: LogExportGrant) async throws -> [URL] {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    try? files.removeItem(at: exports)
                    try files.createDirectory(at: exports, withIntermediateDirectories: true)
                    // 为快照预留一半预算，避免导出瞬间突破容量上限。
                    try trim(to: limit / 2)
                    var result: [URL] = []
                    for source in try logs() {
                        let target = exports.appendingPathComponent(source.lastPathComponent)
                        // 导出副本走同一套二次脱敏，不原样拷贝盘上文件。
                        let scrubbed = try selected(source, interval: nil)
                        guard try directoryBytes() + Int64(scrubbed.count) <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
                        try scrubbed.write(to: target)
                        result.append(target)
                    }
                    guard try directoryBytes() <= limit else { throw CocoaError(.fileWriteOutOfSpace) }
                    guard !result.isEmpty else { throw CocoaError(.fileNoSuchFile) }
                    continuation.resume(returning: result)
                } catch {
                    try? files.removeItem(at: exports)
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

/// 与系统控制台同步输出，调用方只记录已有的诊断字段。
struct LocalLogger {
    let subsystem: String
    let category: String
    func notice(_ message: String) { emit("NOTICE", message) }
    func info(_ message: String) { emit("INFO", message) }
    func debug(_ message: String) { emit("DEBUG", message) }
    private func emit(_ level: String, _ message: String) {
        let line = "\(level) [\(category)] \(message)"
        NSLog("%@", line)
        LocalAppLog.shared.write(line)
    }
}

func appLog(_ format: String, _ arguments: CVarArg...) {
    let message = String(format: format, arguments: arguments)
    NSLog("%@", message)
    LocalAppLog.shared.write(message)
}

func appPrint(_ message: String) {
    print(message)
    LocalAppLog.shared.write(message)
}

private enum LogZip {
    static let table: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = crc & 1 == 0 ? crc >> 1 : (crc >> 1) ^ 0xedb88320 }
        return crc
    }
    static func crc(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8) }
        return crc ^ 0xffffffff
    }
}

private extension Data {
    mutating func logLE<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }
}

/// 导出授权解锁失败（无设备密码 / 用户取消 / 策略评估失败）。
enum LogExportError: LocalizedError {
    case locked(underlying: Error?)

    var errorDescription: String? {
        switch self {
        case .locked: return "诊断日志导出需要先通过设备解锁验证"
        }
    }
}

/// 导出授权凭证：init 私有，只能在模块内铸造，外部代码无法伪造。
/// 正规入口是 `standardRequest`（LAContext 系统解锁）；`mintForTests`
/// 仅供测试目标经 @testable 使用，App 代码不得调用。
struct LogExportGrant {
    private init() {}

    static func standardRequest(reason: String) async throws -> LogExportGrant {
        let context = LAContext()
        var policyError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) else {
            throw LogExportError.locked(underlying: policyError)
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, error in
                if success { continuation.resume() }
                else { continuation.resume(throwing: LogExportError.locked(underlying: error)) }
            }
        }
        return LogExportGrant()
    }

    static func mintForTests() -> LogExportGrant { LogExportGrant() }
}

/// 集中脱敏引擎（规格 §4.6：写入前、导出前各跑一遍，纵深防御）。
/// 只做结构化识别——已知凭证格式与已知敏感键名；不做高熵猜测，
/// 未识别的格式应从源头不记（结构化白名单），而不是靠正则碰运气。
enum LogRedactor {
    private static let masked = "[REDACTED]"

    private static func regex(_ pattern: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern)
    }

    // TLS 私钥 PEM 整块（跨行，先于其它规则执行）。
    private static let privateKeyBlock = regex(
        #"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"#)
    // 三段式 JWT（头段固定 eyJ 开头；签名段允许空）。
    private static let jwt = regex(
        #"eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*"#)
    // Bearer / Basic 方案凭证（长度下限压误伤）。
    private static let bearer = regex(#"Bearer\s+[A-Za-z0-9._~+/=-]{16,}"#)
    private static let basic = regex(#"Basic\s+[A-Za-z0-9+/=_-]{10,}"#)
    // Cookie / Authorization / Set-Cookie 头：值不可预测，整段打到行尾。
    private static let secretHeader = regex(#"(?i)\b(set-cookie|cookie|authorization)\s*:\s*.*"#)
    // URL query 键值对 / JSON 字符串字段 / Keychain 属性上下文。
    private static let queryPair = regex(#"([?&])([a-zA-Z0-9_.%-]{1,40})=([^&\s"]{0,256})"#)
    private static let jsonPair = regex(
        #""([a-zA-Z0-9_. -]{2,48})"\s*:\s*"([^"\\]{0,512})""#)
    private static let keychainAttr = regex(
        #"(?i)\b(acct|v_Data)\s*[=:]\s*("[^"]*"|[^\s,;)}"]+)"#)
    // 个人路径（home 目录用户名）。
    private static let homePath = regex(#"/Users/[A-Za-z0-9_.-]+"#)

    /// 敏感键名候选：归一化（小写、去分隔符）后做子串匹配——宁多遮不漏遮。
    private static let sensitiveKeys: Set<String> = [
        "token", "secret", "password", "passwd", "credential", "cookie",
        "authorization", "auth", "apikey", "accesskey", "secretkey",
        "privatekey", "sessionid", "sessionkey", "accesstoken",
        "refreshtoken", "refresh", "idtoken", "pairingcode", "pairing",
        "signature", "key",
    ]

    /// 幂等：遮完的标记本身不含可再命中的模式。
    static func scrub(_ text: String) -> String {
        guard !text.isEmpty else { return text }
        var result = text
        result = maskMatches(in: result, regex: privateKeyBlock) { _, _ in "[REDACTED:private-key-block]" }
        result = maskMatches(in: result, regex: jwt) { _, _ in "[REDACTED:jwt]" }
        result = maskMatches(in: result, regex: bearer) { _, _ in "Bearer \(masked)" }
        result = maskMatches(in: result, regex: basic) { _, _ in "Basic \(masked)" }
        result = maskMatches(in: result, regex: secretHeader) { match, text in
            let name = group(match, 1, in: text) ?? "header"
            return "\(name): \(masked)"
        }
        result = maskMatches(in: result, regex: queryPair) { match, text in
            guard let name = group(match, 2, in: text), isSensitive(name) else { return nil }
            let prefix = group(match, 1, in: text) ?? "&"
            return "\(prefix)\(name)=\(masked)"
        }
        result = maskMatches(in: result, regex: jsonPair) { match, text in
            guard let name = group(match, 1, in: text), isSensitive(name) else { return nil }
            return "\"\(name)\": \"\(masked)\""
        }
        result = maskMatches(in: result, regex: keychainAttr) { match, text in
            guard let name = group(match, 1, in: text) else { return nil }
            return "\(name)=\(masked)"
        }
        result = maskMatches(in: result, regex: homePath) { _, _ in "/Users/\(masked)" }
        return result
    }

    private static func isSensitive(_ name: String) -> Bool {
        let normalized = String(name.lowercased().filter { $0.isLetter || $0.isNumber })
        guard !normalized.isEmpty else { return false }
        return sensitiveKeys.contains { normalized.contains($0) }
    }

    private static func group(_ match: NSTextCheckingResult, _ index: Int, in text: String) -> String? {
        let range = match.range(at: index)
        let length = (text as NSString).length
        guard range.location != NSNotFound, range.location + range.length <= length else { return nil }
        return (text as NSString).substring(with: range)
    }

    /// 从后往前替换，跳过 template 返回 nil 的命中（保留非敏感键值）。
    private static func maskMatches(in text: String, regex: NSRegularExpression,
                                    template: (NSTextCheckingResult, String) -> String?) -> String {
        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return text }
        var result = text
        for match in matches.reversed() {
            guard let replacement = template(match, text) else { continue }
            result = (result as NSString).replacingCharacters(in: match.range, with: replacement)
        }
        return result
    }
}

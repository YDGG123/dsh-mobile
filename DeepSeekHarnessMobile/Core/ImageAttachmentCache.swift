import CryptoKit
import Foundation

/// 同步读取只访问全局有界内存；磁盘读取、写入与扫描均在后台执行。
final class ImageAttachmentCache: @unchecked Sendable {
    static let defaultTTL: TimeInterval = 7 * 24 * 60 * 60
    static let defaultMemoryCostLimit = 32 * 1024 * 1024
    private final class MemoryEntry: NSObject {
        let data: Data
        let expiresAt: Date
        init(data: Data, expiresAt: Date) { self.data = data; self.expiresAt = expiresAt }
    }
    private static let lock = NSRecursiveLock()
    private static let diskWriterLock = NSLock()
    private static var diskEpoch: UInt64 = 0
    private static var writing: Set<String> = []
    private static var reservations: [String: Int64] = [:]
    private static let memory: NSCache<NSString, MemoryEntry> = {
        let cache = NSCache<NSString, MemoryEntry>()
        cache.totalCostLimit = defaultMemoryCostLimit
        cache.countLimit = 64
        return cache
    }()
    private let fileManager: FileManager
    private let directoryURL: URL
    private let ttl: TimeInterval
    private let maximumDiskBytes: Int64
    private let now: () -> Date
    private let writeData: @Sendable (Data, URL) throws -> Void
    private let readData: @Sendable (URL) throws -> Data
    private var writes: [UUID: Task<Void, Never>] = [:]
    private var reading: Set<String> = []
    var onDataLoaded: (@Sendable (String) -> Void)?

    init(directoryURL: URL? = nil, ttl: TimeInterval = defaultTTL,
         memoryCostLimit: Int = defaultMemoryCostLimit, fileManager: FileManager = .default,
         maximumDiskBytes: Int64 = 32 * 1024 * 1024,
         now: @escaping () -> Date = Date.init,
         writeData: @escaping @Sendable (Data, URL) throws -> Void = { data, url in
             try data.write(to: url, options: [.completeFileProtectionUntilFirstUserAuthentication])
         }, readData: @escaping @Sendable (URL) throws -> Data = { url in
             try Data(contentsOf: url, options: .mappedIfSafe)
         }) {
        self.directoryURL = directoryURL ?? Self.defaultDirectory(fileManager: fileManager)
        self.ttl = ttl
        self.maximumDiskBytes = maximumDiskBytes
        self.fileManager = fileManager
        self.now = now
        self.writeData = writeData
        self.readData = readData
        Self.synchronized {
            if memoryCostLimit > 0 { Self.memory.totalCostLimit = min(Self.defaultMemoryCostLimit, memoryCostLimit) }
        }
    }
    private static func synchronized<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }; return try body()
    }
    /// 只有后台 writer 使用此锁；UI 的内存读取、租约和 clear 永远不等待文件复制。
    private static func withDiskWriter(_ body: () -> Void) {
        diskWriterLock.lock(); defer { diskWriterLock.unlock() }; body()
    }
    var epoch: UInt64 { Self.synchronized { Self.diskEpoch } }
    private func memoryKey(_ id: String) -> NSString { "\(directoryURL.path)|\(id)" as NSString }

    /// UI 路径不会等待磁盘；缺失时后台恢复，完成后通知所在 timeline。
    func data(for attachmentId: String) -> Data? {
        if let entry = Self.memory.object(forKey: memoryKey(attachmentId)), entry.expiresAt > now() { return entry.data }
        let start = Self.synchronized { reading.insert(attachmentId).inserted }
        if start {
            Task.detached(priority: .userInitiated) { [self] in
                let result = await loadData(for: attachmentId)
                Self.synchronized { _ = reading.remove(attachmentId) }
                if result != nil { onDataLoaded?(attachmentId) }
            }
        }
        return nil
    }

    func loadData(for attachmentId: String) async -> Data? {
        if let entry = Self.memory.object(forKey: memoryKey(attachmentId)), entry.expiresAt > now() { return entry.data }
        let ticket = epoch
        return await Task.detached(priority: .userInitiated) { [self] in
            let url = fileURL(for: attachmentId)
            guard isSafeManagedCacheDirectory(directoryURL), let attributes = try? fileManager.attributesOfItem(atPath: url.path),
                  attributes[.type] as? FileAttributeType == .typeRegular,
                  let modified = attributes[.modificationDate] as? Date,
                  modified.addingTimeInterval(ttl) > now(),
                  let size = (attributes[.size] as? NSNumber)?.intValue, size <= Self.defaultMemoryCostLimit,
                  let data = try? readData(url) else {
                Self.synchronized {
                    if ticket == Self.diskEpoch,
                       let attributes = try? fileManager.attributesOfItem(atPath: url.path),
                       let modified = attributes[.modificationDate] as? Date,
                       modified.addingTimeInterval(ttl) <= now() { try? fileManager.removeItem(at: url) }
                }
                return nil
            }
            return Self.synchronized {
                guard ticket == Self.diskEpoch else { return nil }
                Self.memory.setObject(MemoryEntry(data: data, expiresAt: modified.addingTimeInterval(ttl)),
                                      forKey: memoryKey(attachmentId), cost: data.count)
                return data
            }
        }.value
    }

    /// expectedEpoch 由发出 attachment 请求时捕获；清理前的迟到响应不能重建缓存。
    func store(_ data: Data, for attachmentId: String, expectedEpoch: UInt64? = nil) {
        guard data.count <= Self.defaultMemoryCostLimit else { return }
        let ticket = expectedEpoch ?? epoch
        let createdAt = now()
        let admitted = Self.synchronized {
            guard ticket == Self.diskEpoch else { return false }
            Self.memory.setObject(MemoryEntry(data: data, expiresAt: createdAt.addingTimeInterval(ttl)),
                                  forKey: memoryKey(attachmentId), cost: data.count)
            return true
        }
        guard admitted else { return }
        let id = UUID()
        Self.synchronized {
            writes[id] = Task.detached(priority: .utility) { [self] in
                Self.withDiskWriter {
                    defer { Self.synchronized { writes[id] = nil } }
                    guard isSafeManagedCacheDirectory(directoryURL) else { return }
                    let free = (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
                    guard free >= WorkspacePreviewCache.minimumFreeBytes + Int64(data.count) else { return }
                    let allocation = ((Int64(data.count) + 4095) / 4096) * 4096
                    Self.maintainDownloadedFiles(admittingBytes: allocation)
                    let physicalBytes = Self.downloadedBytes(additionalRoot: directoryURL)
                    let file = fileURL(for: attachmentId)
                    let staging = directoryURL.appendingPathComponent(".pending-\(UUID().uuidString)")
                    let mayWrite = Self.synchronized {
                        guard ticket == Self.diskEpoch,
                              physicalBytes + Self.reservations.values.reduce(0, +) + allocation <= maximumDiskBytes else { return false }
                        Self.writing.insert(staging.path)
                        Self.reservations[staging.path] = allocation
                        return true
                    }
                    guard mayWrite else { return }
                    defer {
                        Self.synchronized { Self.writing.remove(staging.path); Self.reservations[staging.path] = nil }
                        try? fileManager.removeItem(at: staging)
                        if (try? fileManager.contentsOfDirectory(atPath: directoryURL.path).isEmpty) == true {
                            try? fileManager.removeItem(at: directoryURL)
                        }
                    }
                    do {
                        try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
                        try writeData(data, staging)
                        try fileManager.setAttributes([.modificationDate: createdAt], ofItemAtPath: staging.path)
                        try Self.synchronized {
                            guard ticket == Self.diskEpoch, isSafeManagedCacheDirectory(directoryURL) else { return }
                            if fileManager.fileExists(atPath: file.path) { try fileManager.removeItem(at: file) }
                            try fileManager.moveItem(at: staging, to: file)
                        }
                        Self.maintainDownloadedFiles()
                    } catch { }
                }
            }
        }
    }

    func flushWrites() async {
        let pending = Self.synchronized { Array(writes.values) }
        for write in pending { await write.value }
    }
    func removeAllFromMemory() { Self.memory.removeAllObjects() }
    func removeExpiredFiles() { Task.detached(priority: .utility) { Self.maintainDownloadedFiles() } }

    /// 只管理服务端附件副本，不触碰待发图片、上传源或用户导出文件。
    static func maintainDownloadedFiles(clear: Bool = false, additionalRoot: URL? = nil, admittingBytes: Int64 = 0) {
        let ticket = synchronized {
            if clear { diskEpoch &+= 1; memory.removeAllObjects() }
            return diskEpoch
        }
        let manager = FileManager.default
        let base = manager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        var roots = [base.appendingPathComponent("GatewayAttachments"), base.appendingPathComponent("DshMobile/ImageAttachments")]
        if let additionalRoot { roots.append(additionalRoot) }
        var files: [URL] = []
        // 扫描与排序可能涉及数千个文件，不持有 UI 读取 epoch 所需的锁。
        for root in roots {
            guard isSafeManagedCacheDirectory(root), let enumerator = manager.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .totalFileAllocatedSizeKey, .fileSizeKey, .contentModificationDateKey]) else { continue }
            files += enumerator.compactMap { $0 as? URL }.filter {
                let values = try? $0.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                return values?.isRegularFile == true && values?.isSymbolicLink != true
            }
        }
        let cutoff = Date().addingTimeInterval(-defaultTTL)
        for file in files {
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            if clear || file.lastPathComponent.hasPrefix(".pending-") || modified < cutoff {
                synchronized {
                    if ticket == diskEpoch, !writing.contains(file.path) { try? manager.removeItem(at: file) }
                }
            }
        }
        files.removeAll { !manager.fileExists(atPath: $0.path) }
        var bytes = files.reduce(Int64(0)) { $0 + allocatedBytes($1) }
        let free = (try? base.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
        guard clear || bytes + admittingBytes > 32 * 1024 * 1024 || free < WorkspacePreviewCache.minimumFreeBytes else { return }
        let target: Int64 = clear ? 0 : min(max(0, 24 * 1024 * 1024 - admittingBytes), max(0, bytes - max(0, WorkspacePreviewCache.minimumFreeBytes - free)))
        files.sort {
            ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) <
            ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }
        for file in files where bytes > target {
            let count = allocatedBytes(file)
            synchronized {
                guard ticket == diskEpoch, !writing.contains(file.path) else { return }
                do { try manager.removeItem(at: file); bytes -= count } catch { }
            }
        }
    }
    func clear() async {
        await Task.detached(priority: .utility) { [self] in
            Self.maintainDownloadedFiles(clear: true, additionalRoot: directoryURL)
        }.value
    }
    static func forgetGateway(_ gatewayID: String) {
        synchronized { diskEpoch &+= 1; memory.removeAllObjects() }
        Task.detached(priority: .utility) {
            let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("GatewayAttachments", isDirectory: true).standardizedFileURL
            let directory = root.appendingPathComponent(gatewayID, isDirectory: true).standardizedFileURL
            guard isSafeManagedCacheDirectory(root), directory.deletingLastPathComponent() == root else { return }
            try? FileManager.default.removeItem(at: directory)
        }
    }

    private static func downloadedBytes(additionalRoot: URL) -> Int64 {
        let manager = FileManager.default
        let base = manager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        var roots = [base.appendingPathComponent("GatewayAttachments"), base.appendingPathComponent("DshMobile/ImageAttachments")]
        if !roots.contains(where: { additionalRoot.path == $0.path || additionalRoot.path.hasPrefix($0.path + "/") }) {
            roots.append(additionalRoot)
        }
        var bytes: Int64 = 0
        for root in roots where isSafeManagedCacheDirectory(root) {
            guard let files = manager.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]) else { continue }
            for case let file as URL in files {
                let values = try? file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                if values?.isRegularFile == true, values?.isSymbolicLink != true { bytes += allocatedBytes(file) }
            }
        }
        return bytes
    }

    private static func allocatedBytes(_ url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey, .fileSizeKey])
        return Int64(values?.totalFileAllocatedSize ?? values?.fileSize ?? 0)
    }
    private func fileURL(for id: String) -> URL {
        let key = SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return directoryURL.appendingPathComponent(key)
    }
    private static func defaultDirectory(fileManager: FileManager) -> URL {
        (fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fileManager.temporaryDirectory)
            .appendingPathComponent("DshMobile/ImageAttachments")
    }
}

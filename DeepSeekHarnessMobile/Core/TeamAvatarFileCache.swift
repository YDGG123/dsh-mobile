import CryptoKit
import Foundation
import os

/// 团队头像的**文件缓存**：用户上传的照片头像（data URL）落盘，启动时直接能画，断网也看得到。
///
/// 为什么单独一个目录、不塞进名册 JSON（`TeamRosterCache`）：
/// - 头像是数十 KB 级的二进制，塞进 `UserDefaults` 那份 JSON 会撑爆 512KB 上限，
///   而且 `UserDefaults` 本来就不适合存大块二进制；
/// - 文件名 = **内容 sha256 前 16 位**：内容没变就复用同一个文件、变了自然换新文件，
///   天然满足用户口径「电脑端有变更就换头像，没变就保持原头像」；
/// - 存**解码前的原始字节**（data URL 里 base64 解出来的 PNG/JPEG 字节，不是 UIImage 归档），
///   启动时按需解码成 UIImage，省一次 base64 解码也不占常驻内存。
///
/// 三条红线（与 `TeamRosterCache` 同口径）：
/// - 任何异常（目录建不出、文件损坏、哈希非法）一律**静默返回 nil**，绝不打断启动与渲染；
/// - 日志只报哈希前缀与字节数，**绝不把头像内容写进日志**（脱敏要求）；
/// - 目录不参与 iCloud 备份（`isExcludedFromBackup`），这是可重建的缓存。
final class TeamAvatarFileCache {

    /// 单张头像落盘体积上限（data URL base64 解出来的字节）。超过直接不缓存（宁可回符号层也不写坏文件）。
    static let maxEntryBytes = 512 * 1024
    /// 目录总量上限：超过就先清「名册里已经不存在」的文件，再按最久未用淘汰。
    static let maxTotalBytes = 20 * 1024 * 1024
    /// 文件扩展名固定 `.img`（内容其实是 PNG/JPEG 原始字节，靠 UIImage 嗅探解码）。
    static let fileExtension = "img"
    /// 哈希长度：sha256 前 16 位十六进制，够避碰且文件名短。
    static let hashLength = 16

    static let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-avatar-cache")

    static let shared = TeamAvatarFileCache()

    let directory: URL
    private let queue = DispatchQueue(label: "ai.dsh.mobile.ios.team-avatar-cache", qos: .utility)

    /// - Parameter directory: 测试可注入临时目录；生产走 Application Support/TeamAvatars。
    init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                     in: .userDomainMask,
                                                     appropriateFor: nil,
                                                     create: true))
                ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            self.directory = base.appendingPathComponent("TeamAvatars", isDirectory: true)
        }
    }

    // MARK: - 哈希

    /// 内容哈希（sha256 前 16 位十六进制）。纯函数，便于单测与「变了才换」判定。
    static func hash(of data: Data) -> String {
        let digest = SHA256.hash(data: data)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return String(hex.prefix(hashLength))
    }

    /// data URL → 原始字节（`data:image/png;base64,xxxx`）。不是 base64 data URL 返回 nil。
    static func imageData(fromDataURL raw: String) -> Data? {
        guard raw.hasPrefix("data:"), let comma = raw.firstIndex(of: ","),
              raw[raw.startIndex..<comma].contains("base64") else { return nil }
        return Data(base64Encoded: String(raw[raw.index(after: comma)...]))
    }

    /// data URL 的内容哈希（只算哈希、不落盘；实时帧上用来判「变了没有」）。
    static func hashOfDataURL(_ raw: String) -> String? {
        guard let data = imageData(fromDataURL: raw) else { return nil }
        return hash(of: data)
    }

    // MARK: - 写

    /// 把 data URL 落盘，返回内容哈希；失败返回 nil（调用方回符号层或只留内存，不报错）。
    @discardableResult
    func store(dataURL raw: String) -> String? {
        guard let data = Self.imageData(fromDataURL: raw) else { return nil }
        return store(imageData: data)
    }

    /// 把原始图片字节落盘，返回内容哈希。**同哈希已存在则直接复用**（不重写、不改修改时间）。
    @discardableResult
    func store(imageData data: Data) -> String? {
        guard !data.isEmpty, data.count <= Self.maxEntryBytes else {
            Self.log.notice("avatar store skipped: bytes=\(data.count, privacy: .public) exceeds \(Self.maxEntryBytes, privacy: .public)")
            return nil
        }
        let hash = Self.hash(of: data)
        let url = fileURL(for: hash)
        guard !FileManager.default.fileExists(atPath: url.path) else { return hash }
        return queue.sync {
            ensureDirectory()
            do {
                try data.write(to: url, options: .atomic)
                return hash
            } catch {
                Self.log.error("avatar store failed: hash=\(hash, privacy: .public) bytes=\(data.count, privacy: .public)")
                return nil
            }
        }
    }

    // MARK: - 读

    func fileURL(for hash: String) -> URL {
        directory.appendingPathComponent(hash).appendingPathExtension(Self.fileExtension)
    }

    /// 同步读原始字节（单张几十 KB，启动路径上直接读；解码由调用方做）。
    /// 哈希非法或文件不存在/读失败一律 nil。
    func data(for hash: String) -> Data? {
        guard Self.isValidHash(hash) else { return nil }
        let url = fileURL(for: hash)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard !data.isEmpty else { return nil }
            return data
        } catch {
            Self.log.notice("avatar read failed: hash=\(hash, privacy: .public)")
            return nil
        }
    }

    func contains(_ hash: String) -> Bool {
        guard Self.isValidHash(hash) else { return false }
        return FileManager.default.fileExists(atPath: fileURL(for: hash).path)
    }

    static func isValidHash(_ hash: String) -> Bool {
        guard hash.count == hashLength else { return false }
        return hash.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }

    // MARK: - 清理

    /// 只保留 `keeping` 里的哈希：
    /// 1) 名册里已经不存在（Bot 被删 / 换了新头像）的文件直接删；
    /// 2) 仍有总量上限 `maxTotalBytes`：淘汰后**只统计、不再删引用中的文件**——
    ///    删掉正在用的头像会让 Bot 页退化成符号层，宁可暂时超限并留一条日志。
    func prune(keeping hashes: Set<String>) {
        queue.sync {
            ensureDirectory()
            var removed = 0
            var keptBytes = 0
            var keptFiles = 0
            for url in cachedFileURLs() {
                let hash = url.deletingPathExtension().lastPathComponent
                guard hashes.contains(hash), Self.isValidHash(hash) else {
                    if (try? FileManager.default.removeItem(at: url)) != nil { removed += 1 }
                    continue
                }
                keptFiles += 1
                keptBytes += (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            }
            if keptBytes > Self.maxTotalBytes {
                Self.log.notice("avatar cache above cap: files=\(keptFiles, privacy: .public) bytes=\(keptBytes, privacy: .public)")
            }
            if removed > 0 {
                Self.log.notice("avatar cache pruned files=\(removed, privacy: .public) kept=\(keptFiles, privacy: .public) bytes=\(keptBytes, privacy: .public)")
            }
        }
    }

    /// 当前目录占用（字节）。缓存管理入口用得上。
    func totalBytes() -> Int {
        cachedFileURLs().reduce(0) { sum, url in
            sum + ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    func cachedFileCount() -> Int { cachedFileURLs().count }

    func removeAll() {
        queue.sync {
            for url in cachedFileURLs() { _ = try? FileManager.default.removeItem(at: url) }
        }
    }

    // MARK: - 内部

    private func ensureDirectory() {
        guard !FileManager.default.fileExists(atPath: directory.path) else { return }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var url = directory
            try? url.setResourceValues(values)
        } catch {
            Self.log.error("avatar directory create failed")
        }
    }

    private func cachedFileURLs() -> [URL] {
        let contents = (try? FileManager.default.contentsOfDirectory(at: directory,
                                                                   includingPropertiesForKeys: [.fileSizeKey],
                                                                   options: [.skipsHiddenFiles])) ?? []
        return contents.filter { $0.pathExtension == Self.fileExtension }
    }

}

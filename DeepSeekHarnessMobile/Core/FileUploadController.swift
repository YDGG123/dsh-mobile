import Foundation
import CryptoKit
import DeepSeekHarnessShared

/// 平台只负责固定的私有副本及后台 I/O，协议状态统一由 KMM 管理。
@MainActor
final class FileUploadController {
    private let shared = SharedFileUploadStore()
    private let gatewayID: String
    private let previewCacheRoot: URL?
    private var previewCache: WorkspacePreviewCache {
        WorkspacePreviewCache(gatewayID: gatewayID, root: previewCacheRoot,
                              namespace: "uploaded-attachment")
    }
    private var preservedDraftIDs: Set<String> = []
    private var previewWrites: [String: Task<Void, Never>] = [:]

    init(gatewayID: String = "legacy", previewCacheRoot: URL? = nil) {
        self.gatewayID = gatewayID
        self.previewCacheRoot = previewCacheRoot
    }

    /// 上传附件只从本机私有副本预览，绝不把 attachmentId 当作远端工作区路径。
    func previewURL(for file: PresentedFile, sessionID: String) async -> URL? {
        guard let entry = Self.previewEntry(file, sessionID: sessionID) else { return nil }
        await previewWrites[Self.previewKey(entry)]?.value
        let cache = previewCache
        return await Task.detached(priority: .userInitiated) { cache.find(entry) }.value
    }

    private static func previewEntry(_ file: PresentedFile, sessionID: String,
                                     sha256: String? = nil) -> WorkspacePreviewCacheEntry? {
        guard let id = file.attachmentId, !id.isEmpty,
              let name = file.name, !name.isEmpty, let size = file.bytes, size >= 0 else { return nil }
        return WorkspacePreviewCacheEntry(sessionID: sessionID, path: "\(id.utf8.count):\(id)\(name)", name: name,
                                          size: size, modifiedAt: 0, sha256: sha256)
    }

    private static func previewKey(_ entry: WorkspacePreviewCacheEntry) -> String {
        "\(entry.sessionID.utf8.count):\(entry.sessionID)\(entry.path)"
    }

    private func preserveReadyFiles() {
        guard let sessionID else { return }
        for draft in drafts where draft.status == "ready" && !preservedDraftIDs.contains(draft.id) {
            guard let file = draft.file, let source = localFiles[draft.id],
                  let entry = Self.previewEntry(PresentedFile(attachmentId: file.attachmentId,
                        name: file.name, bytes: file.bytes?.int64Value), sessionID: sessionID,
                        sha256: draft.sha256) else { continue }
            let key = Self.previewKey(entry)
            guard previewWrites[key] == nil else { continue }
            preservedDraftIDs.insert(draft.id)
            let cache = previewCache
            WorkspaceFileLifetime.shared.retain(source)
            previewWrites[key] = Task {
                await Task.detached(priority: .utility) {
                    defer { WorkspaceFileLifetime.shared.release(source) }
                    if let existing = cache.find(entry) {
                        WorkspaceFileLifetime.shared.release(existing)
                    } else if let saved = cache.store(source, entry: entry) {
                        WorkspaceFileLifetime.shared.release(saved)
                    }
                }.value
                previewWrites[key] = nil
            }
        }
    }

    private func discardLocalFile(_ url: URL) {
        Task.detached(priority: .utility) {
            WorkspaceFileLifetime.shared.synchronized {
                if WorkspaceFileLifetime.shared.isLeased(url) {
                    WorkspaceFileLifetime.shared.removeAfterLastLease(url)
                } else {
                    try? FileManager.default.removeItem(at: url)
                }
            }
        }
    }
    private var localFiles: [String: URL] = [:]
    private var timeouts: [String: Task<Void, Never>] = [:]
    private var connectionKey: String?
    private var context = UUID()
    private(set) var sessionID: String?
    private(set) var drafts: [SharedUploadDraft] = []
    private(set) var preparing = false
    private(set) var supported = false
    var changed: (() -> Void)?
    var send: ((String) -> Void)?
    var reportError: ((String) -> Void)?

    var canSend: Bool { !preparing && shared.canSend() }
    var receipts: [String] { shared.receipts() }

    func connected(sessionID: String?, supported: Bool) {
        context = UUID()
        preparing = false
        connectionKey = UUID().uuidString
        self.supported = supported
        apply(shared.bind(sessionId: sessionID, connectionKey: connectionKey, available: supported))
        self.sessionID = sessionID
    }

    func disconnected() {
        supported = false
        context = UUID()
        preparing = false
        connectionKey = nil
        timeouts.values.forEach { $0.cancel() }
        timeouts.removeAll()
        apply(shared.bind(sessionId: sessionID, connectionKey: nil, available: false))
    }

    func select(sessionID: String?, allowed: Bool) {
        if self.sessionID != sessionID {
            context = UUID()
            preparing = false
            clear()
            self.sessionID = sessionID
        }
        apply(shared.bind(sessionId: sessionID, connectionKey: connectionKey, available: supported && allowed))
    }

    func importFiles(_ urls: [URL]) async {
        guard supported, connectionKey != nil, sessionID != nil, !preparing else { return }
        let token = context
        preparing = true
        changed?()
        defer { if context == token { preparing = false; changed?() } }
        for url in urls.prefix(max(0, 20 - drafts.count)) {
            do {
                let id = UUID().uuidString
                let prepared = try await Task.detached(priority: .userInitiated) {
                    try Self.prepare(url, id: id)
                }.value
                guard token == context, !Task.isCancelled else {
                    try? FileManager.default.removeItem(at: prepared.url)
                    continue
                }
                localFiles[id] = prepared.url
                apply(shared.add(id: id, name: prepared.name, size: prepared.size, sha256: prepared.sha256))
            } catch {
                if token == context { reportError?(error.localizedDescription) }
            }
        }
    }

    func accept(_ frame: GatewayFrame) {
        guard frame.kind.hasPrefix("file-upload-") || frame.requestType?.hasPrefix("file-upload-") == true else { return }
        if let id = frame.requestId { timeouts.removeValue(forKey: id)?.cancel() }
        guard let json = try? JSONEncoder().encode(frame) else { return }
        apply(shared.acceptFrame(json: String(decoding: json, as: UTF8.self)))
    }

    func retry(_ id: String) { apply(shared.retry(id: id)) }
    func remove(_ id: String) {
        preservedDraftIDs.remove(id)
        apply(shared.remove(id: id))
        if let url = localFiles.removeValue(forKey: id) { discardLocalFile(url) }
    }
    func accepted(_ ids: [String]) { ids.forEach(remove) }
    func invalidReceipts() { apply(shared.invalidateReceipts()) }
    func submission(text: String, imageKey: String) -> String {
        shared.submission(text: text, imageKey: imageKey, proposedId: UUID().uuidString)
    }
    func clear() {
        context = UUID()
        preparing = false
        timeouts.values.forEach { $0.cancel() }; timeouts.removeAll()
        apply(shared.clear())
        localFiles.values.forEach(discardLocalFile)
        localFiles.removeAll()
        preservedDraftIDs.removeAll()
    }

    private func apply(_ transition: SharedUploadTransition) {
        drafts = transition.drafts
        preserveReadyFiles()
        changed?()
        for request in transition.requests {
            if request.requestType != "file-upload-cancel", let id = request.correlationId { watch(request, id: id) }
            send?(request.payload)
        }
        if let request = transition.watch, let id = request.correlationId { watch(request, id: id) }
        if let read = transition.read, let url = localFiles[read.id] {
            let token = read.token
            let offset = UInt64(read.offset)
            let length = Int(read.length)
            Task { [weak self] in
                do {
                    let encoded = try await Task.detached(priority: .userInitiated) {
                        let handle = try FileHandle(forReadingFrom: url)
                        defer { try? handle.close() }
                        try handle.seek(toOffset: offset)
                        let data = try handle.read(upToCount: length) ?? Data()
                        return (data.base64EncodedString(), data.count)
                    }.value
                    guard let self else { return }
                    self.apply(self.shared.chunkReady(token: token, data: encoded.0, bytes: Int32(encoded.1)))
                } catch {
                    guard let self else { return }
                    self.apply(self.shared.readFailed(token: token, message: error.localizedDescription))
                }
            }
        }
    }

    private func watch(_ request: GatewayRequest, id: String) {
        let seconds: Double = request.requestType == "file-upload-finish" ? 60 : 30
        timeouts[id]?.cancel()
        timeouts[id] = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(seconds)) } catch { return }
            guard let self else { return }
            self.timeouts.removeValue(forKey: id)
            self.apply(self.shared.timedOut(requestId: id))
        }
    }

    private struct Prepared: Sendable {
        let url: URL
        let name: String
        let size: Int64
        let sha256: String
    }
    nonisolated private static func prepare(_ source: URL, id: String) throws -> Prepared {
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        let name = source.lastPathComponent
        guard !name.isEmpty, name.utf16.count <= 255, name != ".", name != "..",
              !name.contains("/"), !name.contains("\\"), name.rangeOfCharacter(from: .controlCharacters) == nil else {
            throw UploadError.invalidName
        }
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw UploadError.invalidFile }
        guard Int64(values.fileSize ?? 0) <= 536_870_912 else { throw UploadError.tooLarge }
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("dsh-draft-\(id)")
        var succeeded = false
        defer { if !succeeded { try? FileManager.default.removeItem(at: destination) } }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else { throw UploadError.invalidFile }
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var digest = SHA256()
        var size: Int64 = 0
        while let data = try input.read(upToCount: 256 * 1024), !data.isEmpty {
            try Task.checkCancellation()
            size += Int64(data.count)
            guard size <= 536_870_912 else { throw UploadError.tooLarge }
            digest.update(data: data)
            try output.write(contentsOf: data)
        }
        succeeded = true
        return Prepared(url: destination, name: name, size: size,
            sha256: digest.finalize().map { String(format: "%02x", $0) }.joined())
    }
    private enum UploadError: LocalizedError {
        case invalidName, invalidFile, tooLarge
        var errorDescription: String? {
            switch self {
            case .invalidName: String(localized: "文件名无效")
            case .invalidFile: String(localized: "无法读取所选文件")
            case .tooLarge: String(localized: "文件不能超过 512 MiB")
            }
        }
    }
}

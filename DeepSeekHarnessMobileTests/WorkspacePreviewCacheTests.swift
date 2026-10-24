import XCTest
import CryptoKit
@testable import DeepSeekHarnessMobile

final class WorkspacePreviewCacheTests: XCTestCase {
    func testSeparatePreviewAndTemporaryCleanupPreservesOtherCategoryAndLeasedFiles() throws {
        try withDirectory { directory in
            let previews = directory.appendingPathComponent("previews")
            let temporary = directory.appendingPathComponent("temporary")
            let preview = previews.appendingPathComponent("cached/document.pdf")
            let temp = temporary.appendingPathComponent("download/document.pdf")
            let saved = directory.appendingPathComponent("saved.pdf")
            for file in [preview, temp, saved] {
                try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try Data("content".utf8).write(to: file)
            }
            let cache = WorkspacePreviewCache(gatewayID: "test", root: previews)
            XCTAssertGreaterThan(cache.statistics(temporaryRoot: temporary).temporaryBytes, 0)
            XCTAssertTrue(FileManager.default.fileExists(atPath: temp.path), "统计不应触发清理")
            WorkspaceFileLifetime.shared.retain(temp)
            cache.clearTemporaryFiles(temporaryRoot: temporary)
            XCTAssertTrue(FileManager.default.fileExists(atPath: preview.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: temp.path))
            WorkspaceFileLifetime.shared.release(temp)
            XCTAssertFalse(FileManager.default.fileExists(atPath: temp.path))
            try FileManager.default.createDirectory(at: temp.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("content".utf8).write(to: temp)
            cache.maintain(temporaryRoot: nil, clear: true)
            XCTAssertFalse(FileManager.default.fileExists(atPath: preview.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: temp.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: saved.path))
        }
    }

    private let fileDigest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    func testPersistsAcrossInstancesAndSeparatesVersionsGatewaysSessionsAndPaths() throws {
        try withDirectory { directory in
            let root = directory.appendingPathComponent("cache")
            let source = directory.appendingPathComponent("source.txt")
            try Data("hello".utf8).write(to: source)
            let entry = entry()
            XCTAssertNotNil(WorkspacePreviewCache(gatewayID: "g1", root: root).store(source, entry: entry))
            let cache = WorkspacePreviewCache(gatewayID: "g1", root: root)
            let file = try XCTUnwrap(cache.find(entry))
            XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "hello")
            XCTAssertNil(WorkspacePreviewCache(gatewayID: "g2", root: root).find(entry))
            XCTAssertNil(cache.find(self.entry(sessionID: "s2")))
            XCTAssertNil(cache.find(self.entry(path: "other/a.txt")))
            XCTAssertNil(cache.find(self.entry(size: 6)))
            XCTAssertNil(cache.find(self.entry(modifiedAt: 11)))
        }
    }

    func testCorruptOrDeletedCacheIsNeverReturned() throws {
        try withDirectory { directory in
            let source = directory.appendingPathComponent("source.txt")
            try Data("hello".utf8).write(to: source)
            let cache = WorkspacePreviewCache(gatewayID: "g1", root: directory.appendingPathComponent("cache"))
            let entry = entry()
            let file = try XCTUnwrap(cache.store(source, entry: entry))
            try Data("other".utf8).write(to: file)
            XCTAssertNil(cache.find(entry))
            WorkspaceFileLifetime.shared.release(file)
            let replacement = try XCTUnwrap(cache.store(source, entry: entry))
            try FileManager.default.removeItem(at: replacement)
            XCTAssertNil(cache.find(entry))
        }
    }

    func testCacheWriteFailurePreservesDownloadedFile() throws {
        try withDirectory { directory in
            let source = directory.appendingPathComponent("source.txt")
            let root = directory.appendingPathComponent("not-a-directory")
            try Data("hello".utf8).write(to: source)
            try Data().write(to: root)
            let cache = WorkspacePreviewCache(gatewayID: "g1", root: root)
            XCTAssertNil(cache.store(source, entry: entry()))
            XCTAssertEqual(try String(contentsOf: source, encoding: .utf8), "hello")
        }
    }

    func testOversizedAndLowSpaceFilesRemainTemporary() throws {
        try withDirectory { directory in
            let source = directory.appendingPathComponent("video.mp4")
            try Data("hello".utf8).write(to: source)
            let cache = WorkspacePreviewCache(gatewayID: "g", root: directory.appendingPathComponent("cache"),
                maximumBytes: 1024, targetBytes: 512)
            XCTAssertNil(cache.store(source, entry: entry(size: 512 * 1024 * 1024)))
            let lowSpace = WorkspacePreviewCache(gatewayID: "g", root: directory.appendingPathComponent("low"),
                availableBytes: { 100 })
            XCTAssertNil(lowSpace.store(source, entry: entry()))
            XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        }
    }

    func testMaintenanceRemovesOrphansButProtectsAllLiveLeases() throws {
        try withDirectory { directory in
            let root = directory.appendingPathComponent("cache")
            let temp = directory.appendingPathComponent("temp")
            let active = temp.appendingPathComponent("active/video.mp4")
            let orphan = temp.appendingPathComponent("orphan/video.mp4")
            let pending = root.appendingPathComponent(".pending-interrupted")
            for item in [active, orphan, pending.appendingPathComponent("video.mp4")] {
                try FileManager.default.createDirectory(at: item.deletingLastPathComponent(), withIntermediateDirectories: true)
                try Data("hello".utf8).write(to: item)
            }
            let empty = temp.appendingPathComponent("empty")
            try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
            WorkspaceFileLifetime.shared.retain(active)
            WorkspaceFileLifetime.shared.retain(active)
            let cache = WorkspacePreviewCache(gatewayID: "g", root: root)
            let statistics = cache.maintain(temporaryRoot: temp, clear: true)
            XCTAssertGreaterThan(statistics.protectedBytes, 0)
            XCTAssertTrue(FileManager.default.fileExists(atPath: active.path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: pending.path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: empty.path))
            WorkspaceFileLifetime.shared.release(active, deleteTemporary: true)
            XCTAssertTrue(FileManager.default.fileExists(atPath: active.path))
            WorkspaceFileLifetime.shared.release(active, deleteTemporary: true)
            XCTAssertFalse(FileManager.default.fileExists(atPath: active.deletingLastPathComponent().path))
        }
    }

    func testClearProtectsPreviewAndRejectsAlreadyScheduledStores() throws {
        try withDirectory { directory in
            let source = directory.appendingPathComponent("source")
            try Data("hello".utf8).write(to: source)
            let cache = WorkspacePreviewCache(gatewayID: "g", root: directory.appendingPathComponent("cache"))
            let file = try XCTUnwrap(cache.store(source, entry: entry()))
            let secondReader = try XCTUnwrap(cache.find(entry()))
            let remaining = cache.maintain(temporaryRoot: nil, clear: true)
            XCTAssertGreaterThan(remaining.previewBytes, 0)
            XCTAssertEqual(try Data(contentsOf: file), Data("hello".utf8))
            XCTAssertNil(cache.store(source, entry: entry(path: "late")), "清理前创建的异步操作不得重建缓存")
            WorkspaceFileLifetime.shared.release(file)
            WorkspaceFileLifetime.shared.release(secondReader)
            XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "用户清理保留的在用副本应在最后读者关闭后立即删除")
            XCTAssertEqual(cache.maintain(temporaryRoot: nil, clear: true).previewBytes, 0)
        }
    }

    func testTemporaryFallbackIsDeletedOnDismissAndExportRelease() throws {
        try withDirectory { directory in
            let parent = directory.appendingPathComponent("UUID")
            let file = parent.appendingPathComponent("video.mp4")
            try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
            try Data("hello".utf8).write(to: file)
            WorkspaceFileLifetime.shared.retain(file)
            WorkspaceFileLifetime.shared.release(file, deleteTemporary: true)
            XCTAssertFalse(FileManager.default.fileExists(atPath: parent.path))
        }
    }

    func testExpiredEntryAndSymlinkAreCleanedWithoutTouchingExternalFiles() throws {
        try withDirectory { directory in
            let root = directory.appendingPathComponent("cache")
            let outside = directory.appendingPathComponent("user-export.txt")
            try Data("用户导出".utf8).write(to: outside)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let expired = root.appendingPathComponent("old")
            try FileManager.default.createDirectory(at: expired, withIntermediateDirectories: true)
            try Data("old".utf8).write(to: expired.appendingPathComponent("file"))
            try FileManager.default.setAttributes([.modificationDate: Date.distantPast], ofItemAtPath: expired.path)
            let link = root.appendingPathComponent(".pending-link")
            try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)
            let cache = WorkspacePreviewCache(gatewayID: "g", root: root)
            _ = cache.maintain(temporaryRoot: nil)
            XCTAssertFalse(FileManager.default.fileExists(atPath: expired.path))
            XCTAssertEqual(try Data(contentsOf: outside), Data("用户导出".utf8))
        }
    }

    func testLowSpaceEvictsEvenWhenBelowNormalLowWatermark() throws {
        try withDirectory { directory in
            let root = directory.appendingPathComponent("cache")
            let source = directory.appendingPathComponent("source")
            try Data("hello".utf8).write(to: source)
            let cache = WorkspacePreviewCache(gatewayID: "g", root: root)
            let file = try XCTUnwrap(cache.store(source, entry: entry()))
            WorkspaceFileLifetime.shared.release(file)
            let lowSpace = WorkspacePreviewCache(gatewayID: "g", root: root, availableBytes: { 0 })
            XCTAssertEqual(lowSpace.maintain(temporaryRoot: nil).previewBytes, 0)
        }
    }

    func testClearDuringCopyRejectsCommitAndDoesNotBlockClosingAnotherPreview() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("source")
        try Data("hello".utf8).write(to: source)
        let started = DispatchSemaphore(value: 0)
        let proceed = DispatchSemaphore(value: 0)
        let cache = WorkspacePreviewCache(gatewayID: "g", root: directory.appendingPathComponent("cache"),
            copyFile: { source, target in
                started.signal()
                _ = proceed.wait(timeout: .now() + 5)
                try FileManager.default.copyItem(at: source, to: target)
            })
        let entry = entry()
        let task = Task.detached { cache.store(source, entry: entry) }
        let began = await Task.detached { started.wait(timeout: .now() + 5) == .success }.value
        XCTAssertTrue(began)
        let time = Date()
        WorkspaceFileLifetime.shared.retain(source)
        WorkspaceFileLifetime.shared.release(source)
        _ = cache.maintain(temporaryRoot: nil, clear: true)
        XCTAssertLessThan(Date().timeIntervalSince(time), 0.5, "复制期间取消和清理不能等待大文件 I/O")
        proceed.signal()
        let result = await task.value
        XCTAssertNil(result, "清理发生在复制提交之前，旧写入必须被 epoch 拒绝")
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        XCTAssertEqual(cache.maintain(temporaryRoot: nil).previewBytes, 0)
    }

    func testImageClearInvalidatesInflightDiskWriteAndLateNetworkResponse() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let began = DispatchSemaphore(value: 0)
        let proceed = DispatchSemaphore(value: 0)
        let cache = ImageAttachmentCache(directoryURL: directory, writeData: { data, url in
            began.signal()
            _ = proceed.wait(timeout: .now() + 5)
            try data.write(to: url)
        })
        let oldEpoch = cache.epoch
        cache.store(Data("old".utf8), for: "old", expectedEpoch: oldEpoch)
        let started = await Task.detached { began.wait(timeout: .now() + 5) == .success }.value
        XCTAssertTrue(started)
        let time = Date()
        await cache.clear()
        XCTAssertLessThan(Date().timeIntervalSince(time), 0.5)
        XCTAssertNil(cache.data(for: "old"))
        cache.store(Data("late".utf8), for: "late", expectedEpoch: oldEpoch)
        proceed.signal()
        await cache.flushWrites()
        let old = await cache.loadData(for: "old")
        let late = await cache.loadData(for: "late")
        XCTAssertNil(old)
        XCTAssertNil(late)
        XCTAssertTrue(((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).isEmpty)
    }

    func testImageDiskReadRunsOffMainAndMemoryMissReturnsImmediately() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = ImageAttachmentCache(directoryURL: directory, readData: { url in
            XCTAssertFalse(Thread.isMainThread)
            return try Data(contentsOf: url)
        })
        let payload = Data("image fixture".utf8)
        cache.store(payload, for: "image")
        await cache.flushWrites()
        cache.removeAllFromMemory()
        let start = Date()
        XCTAssertNil(cache.data(for: "image"))
        XCTAssertLessThan(Date().timeIntervalSince(start), 0.1)
        let loaded = await cache.loadData(for: "image")
        XCTAssertEqual(loaded, payload)
    }

    func testActiveTemporaryExceptionIsVisibleAsStoragePressure() {
        let resources = WorkspaceCacheStatistics(previewBytes: 0, temporaryBytes: 40 * 1024 * 1024,
            protectedBytes: 40 * 1024 * 1024)
        let stats = AppCacheStorageStatistics(resources: resources, databaseBytes: 0, imageBytes: 0,
            memoryBytes: 0, memoryHits: 0, diskHits: 0, misses: 0, freeBytes: Int64.max)
        XCTAssertTrue(stats.underPressure)
    }

    func testManagedPreviewAndTemporaryRootSymlinksNeverTraverseExternalDirectory() throws {
        try withDirectory { directory in
            let external = directory.appendingPathComponent("external")
            let pending = external.appendingPathComponent(".pending-user-file/file")
            let orphan = external.appendingPathComponent("child/user-file")
            for file in [pending, orphan] {
                try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try Data("用户文件".utf8).write(to: file)
            }
            let root = directory.appendingPathComponent("root-link")
            try FileManager.default.createSymbolicLink(at: root, withDestinationURL: external)
            let source = directory.appendingPathComponent("source")
            try Data("hello".utf8).write(to: source)
            for managed in [root, root.appendingPathComponent("child")] {
                let cache = WorkspacePreviewCache(gatewayID: "g", root: managed)
                XCTAssertNil(cache.find(entry()))
                XCTAssertNil(cache.store(source, entry: entry()))
                XCTAssertEqual(cache.maintain(temporaryRoot: managed, clear: true).totalBytes, 0)
            }
            XCTAssertEqual(try Data(contentsOf: pending), Data("用户文件".utf8))
            XCTAssertEqual(try Data(contentsOf: orphan), Data("用户文件".utf8))
        }
    }

    func testImageRootSymlinkRejectsReadWriteAndCleanupOutsideSandboxCategory() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let external = directory.appendingPathComponent("external")
        try FileManager.default.createDirectory(at: external, withIntermediateDirectories: true)
        let key = SHA256.hash(data: Data("external".utf8)).map { String(format: "%02x", $0) }.joined()
        let userFile = external.appendingPathComponent(key)
        try Data("外部图片".utf8).write(to: userFile)
        let root = directory.appendingPathComponent("image-link")
        try FileManager.default.createSymbolicLink(at: root, withDestinationURL: external)
        let cache = ImageAttachmentCache(directoryURL: root)
        let loaded = await cache.loadData(for: "external")
        XCTAssertNil(loaded)
        cache.store(Data("downloaded".utf8), for: "downloaded")
        await cache.flushWrites()
        await cache.clear()
        XCTAssertEqual(try Data(contentsOf: userFile), Data("外部图片".utf8))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: external.path), [key])
    }

    func testConcurrentCacheAdmissionsRespectHardQuota() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("source")
        try Data("hello".utf8).write(to: source)
        let cache = WorkspacePreviewCache(gatewayID: "g", root: directory.appendingPathComponent("cache"),
            maximumBytes: 16 * 1024, targetBytes: 16 * 1024)
        let entries = (0..<8).map { entry(path: "file-\($0)") }
        let results = await withTaskGroup(of: URL?.self) { group in
            for entry in entries { group.addTask { cache.store(source, entry: entry) } }
            var results: [URL] = []
            for await result in group { if let result { results.append(result) } }
            return results
        }
        XCTAssertFalse(results.isEmpty)
        XCTAssertLessThanOrEqual(results.count, 2)
        XCTAssertLessThanOrEqual(cache.maintain(temporaryRoot: nil).previewBytes, 16 * 1024)
        for result in results { WorkspaceFileLifetime.shared.release(result) }
        XCTAssertEqual(cache.maintain(temporaryRoot: nil, clear: true).previewBytes, 0)
    }

    func testConcurrentImageWritesRespectPhysicalQuota() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = ImageAttachmentCache(directoryURL: directory, maximumDiskBytes: 16 * 1024)
        await cache.clear()
        for index in 0..<8 { cache.store(Data("hello".utf8), for: "image-\(index)") }
        await cache.flushWrites()
        cache.removeAllFromMemory()
        var hits = 0
        for index in 0..<8 {
            if await cache.loadData(for: "image-\(index)") != nil { hits += 1 }
        }
        XCTAssertGreaterThan(hits, 0)
        XCTAssertLessThanOrEqual(hits, 4)
        let inspector = WorkspacePreviewCache(gatewayID: "inspection", root: directory)
        XCTAssertLessThanOrEqual(inspector.treeBytes(directory), 16 * 1024)
    }

    private func entry(sessionID: String = "s1", path: String = "build/a.txt",
                       size: Int64 = 5, modifiedAt: Double = 10.5) -> WorkspacePreviewCacheEntry {
        WorkspacePreviewCacheEntry(sessionID: sessionID, path: path, name: "a.txt",
                                   size: size, modifiedAt: modifiedAt, sha256: fileDigest)
    }

    private func withDirectory(_ block: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try block(directory)
    }
}

import XCTest
@testable import DeepSeekHarnessMobile

/// 团队头像文件缓存（2026-10-31 用户要求：打开 App 就能看到头像，电脑端变了才换）。
///
/// 覆盖：内容哈希去重 / 落盘读回 / 坏文件静默回空 / 清理只删无人引用 / 体积上限 /
/// 名册 v1 缓存作废 / `trimEntity` 只留哈希 / `makeRow` 能读回哈希。
final class TeamAvatarFileCacheTests: XCTestCase {

    private var directory: URL!
    private var cache: TeamAvatarFileCache!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("TeamAvatarFileCacheTests-\(UUID().uuidString)", isDirectory: true)
        cache = TeamAvatarFileCache(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        TeamAvatarFileCache.shared.removeAll()
    }

    // MARK: - 样本

    /// 一个最小的合法 PNG（1x1，透明）。够 UIImage 解码，也够压测哈希/落盘。
    private func samplePNG(_ tint: UInt8) -> Data {
        var bytes: [UInt8] = [
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // signature
        ]
        // 直接给一段可解码的最小 PNG（固定内容）+ 尾部附加区分字节（不同 tint → 不同哈希）。
        let base: [UInt8] = [
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
        ]
        bytes.append(contentsOf: base)
        bytes.append(tint) // 尾部附加字节：PNG 解码器会忽略，但内容哈希不同
        return Data(bytes)
    }

    private func dataURL(_ data: Data) -> String {
        "data:image/png;base64," + data.base64EncodedString()
    }

    // MARK: - 哈希与去重

    func testHashIsStableAndContentAddressed() {
        let a = samplePNG(1)
        let b = samplePNG(2)
        XCTAssertEqual(TeamAvatarFileCache.hash(of: a), TeamAvatarFileCache.hash(of: a))
        XCTAssertNotEqual(TeamAvatarFileCache.hash(of: a), TeamAvatarFileCache.hash(of: b))
        XCTAssertEqual(TeamAvatarFileCache.hash(of: a).count, TeamAvatarFileCache.hashLength)
        XCTAssertTrue(TeamAvatarFileCache.isValidHash(TeamAvatarFileCache.hash(of: a)))
        XCTAssertFalse(TeamAvatarFileCache.isValidHash("XYZ"))
        XCTAssertFalse(TeamAvatarFileCache.isValidHash("ABCDEF0123456789"))
    }

    func testStoreIsIdempotentForSameContent() throws {
        let data = samplePNG(3)
        let first = cache.store(imageData: data)
        let second = cache.store(imageData: data)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first, TeamAvatarFileCache.hash(of: data))
        XCTAssertEqual(cache.cachedFileCount(), 1, "同一内容只应有一个文件（内容没变就复用）")
        XCTAssertEqual(cache.data(for: try XCTUnwrap(first)), data)
    }

    func testDataURLRoundTrip() throws {
        let data = samplePNG(4)
        let hash = try XCTUnwrap(cache.store(dataURL: dataURL(data)))
        XCTAssertEqual(hash, TeamAvatarFileCache.hash(of: data))
        XCTAssertEqual(cache.data(for: hash), data)
        XCTAssertTrue(cache.contains(hash))
    }

    func testNonDataURLAndOversizeAreRejected() {
        XCTAssertNil(cache.store(dataURL: "file:///tmp/avatar.png"))
        XCTAssertNil(cache.store(dataURL: "data:image/svg+xml,%3Csvg%3E"))
        let oversize = Data(count: TeamAvatarFileCache.maxEntryBytes + 1)
        XCTAssertNil(cache.store(imageData: oversize))
        XCTAssertEqual(cache.cachedFileCount(), 0)
    }

    // MARK: - 坏数据静默回空

    func testCorruptFileReturnsNilWithoutCrash() throws {
        let hash = try XCTUnwrap(cache.store(imageData: samplePNG(5)))
        // 把文件内容改坏（模拟磁盘损坏 / 半截写入）
        try Data([0x00, 0x01, 0x02]).write(to: cache.fileURL(for: hash))
        XCTAssertNil(cache.data(for: hash).flatMap { UIImage(data: $0) }, "坏文件必须解不出图，而不是崩")
        // 非法哈希文件名不许越界读
        XCTAssertNil(cache.data(for: "../evil"))
        XCTAssertNil(cache.data(for: "0000000000000000"))
    }

    // MARK: - 清理

    func testPruneKeepsReferencedAndDeletesOthers() throws {
        let kept = try XCTUnwrap(cache.store(imageData: samplePNG(6)))
        let stale = try XCTUnwrap(cache.store(imageData: samplePNG(7)))
        XCTAssertEqual(cache.cachedFileCount(), 2)
        cache.prune(keeping: [kept])
        XCTAssertTrue(cache.contains(kept))
        XCTAssertFalse(cache.contains(stale), "名册里已经没人引用的头像文件必须被删")
        XCTAssertEqual(cache.cachedFileCount(), 1)
    }

    func testTotalBytesReportsDirectoryUsage() throws {
        _ = try XCTUnwrap(cache.store(imageData: samplePNG(8)))
        XCTAssertGreaterThan(cache.totalBytes(), 0)
        cache.removeAll()
        XCTAssertEqual(cache.totalBytes(), 0)
    }

    // MARK: - 名册缓存结构（v2）

    func testTrimEntityStoresImageAsHashAndDropsBytes() throws {
        let image = dataURL(samplePNG(9))
        let snapshot: [String: Any] = [
            "instanceId": "inst-1",
            "bots": [[
                "id": "bot-1", "name": "Alice",
                "avatar": ["shape": "whale", "image": image],
            ]],
        ]
        let data = try XCTUnwrap(TeamRosterCache.trimmedSnapshot(from: snapshot))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let bots = try XCTUnwrap(object["bots"] as? [[String: Any]])
        let avatar = try XCTUnwrap(bots.first?["avatar"] as? [String: Any])
        XCTAssertNil(avatar["image"], "照片字节不许进 UserDefaults 那份 JSON")
        XCTAssertEqual(avatar["hasImage"] as? Bool, true)
        let hash = try XCTUnwrap(avatar["imageHash"] as? String)
        XCTAssertEqual(hash, TeamAvatarFileCache.hash(of: samplePNG(9)))
        XCTAssertNotNil(TeamAvatarFileCache.shared.data(for: hash), "字节应落盘到文件缓存")
        XCTAssertEqual(TeamRosterCache.avatarImageHashes(in: object), [hash])
    }

    func testRosterRowsReadImageHashFromCache() throws {
        let hash = TeamAvatarFileCache.hash(of: samplePNG(10))
        let snapshot: [String: Any] = [
            "bots": [[
                "id": "bot-2", "name": "Bob",
                "avatar": ["shape": "star", "hasImage": true, "imageHash": hash],
            ]],
        ]
        let rows = GatewayTeamFeed.rosterRows(from: snapshot)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.avatarImageHash, hash)
        XCTAssertNil(rows.first?.avatarImage, "水合路径不应有 data URL")
        XCTAssertEqual(rows.first?.avatarShape, "star")
    }

    func testVersionOneCacheIsDiscardedSilently() throws {
        let legacy: [String: Any] = [
            "cacheVersion": 1,
            "savedAt": Date().timeIntervalSince1970 * 1000,
            "instanceId": "inst-1",
            "snapshot": ["instanceId": "inst-1", "bots": []],
        ]
        let data = try JSONSerialization.data(withJSONObject: legacy)
        XCTAssertNil(TeamRosterCache.load(from: data), "v1 旧缓存必须静默作废")
        // 当前版本正常读
        let current = try XCTUnwrap(TeamRosterCache.encode(snapshot: ["instanceId": "inst-1", "bots": []],
                                                           instanceId: "inst-1"))
        XCTAssertNotNil(TeamRosterCache.load(from: current))
    }

    // MARK: - 解码：冷启动只解一次

    @MainActor
    func testAvatarImageDecodesFromDiskAndCachesByHash() throws {
        let data = samplePNG(11)
        let hash = try XCTUnwrap(TeamAvatarFileCache.shared.store(imageData: data))
        var row = GatewayTeamFeed.Row(id: "bot-3", kind: .bot, name: "Carol", sessionId: "s3")
        row.avatarImageHash = hash
        XCTAssertNotNil(GatewayTeamFeed.avatarImage(row), "冷启动应按哈希从磁盘解出头像")
        XCTAssertNotNil(GatewayTeamFeed.avatarImage(row), "第二次应命中内存缓存")
    }

    @MainActor
    func testAvatarImageFallsBackWhenFileMissing() {
        var row = GatewayTeamFeed.Row(id: "bot-4", kind: .bot, name: "Dave", sessionId: "s4")
        row.avatarImageHash = "0123456789abcdef"
        XCTAssertNil(GatewayTeamFeed.avatarImage(row), "文件不在 → 回 glyph/符号层，不许崩")
    }

    // MARK: - 不重复解码 / 变了才换

    @MainActor
    func testSameHashReusesDecodedImageInstance() throws {
        let data = samplePNG(12)
        let hash = try XCTUnwrap(TeamAvatarFileCache.shared.store(imageData: data))
        var row = GatewayTeamFeed.Row(id: "bot-5", kind: .bot, name: "Eve", sessionId: "s5")
        row.avatarImageHash = hash
        let first = try XCTUnwrap(GatewayTeamFeed.avatarImage(row))
        let second = try XCTUnwrap(GatewayTeamFeed.avatarImage(row))
        XCTAssertTrue(first === second, "同一 imageHash 必须复用同一个 UIImage 实例（不重复解码）")
    }

    @MainActor
    func testChangedAvatarGetsNewHashAndNewFile() throws {
        let old = samplePNG(13)
        let new = samplePNG(14)
        var row = GatewayTeamFeed.Row(id: "bot-6", kind: .bot, name: "Frank", sessionId: "s6")
        row.avatarImageHash = TeamAvatarFileCache.hash(of: old)
        _ = TeamAvatarFileCache.shared.store(imageData: old)
        let oldImage = try XCTUnwrap(GatewayTeamFeed.avatarImage(row))

        // 电脑端换头像：新 data URL → 新哈希 → 新文件、新 UIImage
        row.avatarImage = "data:image/png;base64," + new.base64EncodedString()
        row.avatarImageHash = TeamAvatarFileCache.hashOfDataURL(row.avatarImage!)
        XCTAssertNotEqual(row.avatarImageHash, TeamAvatarFileCache.hash(of: old))
        let newImage = try XCTUnwrap(GatewayTeamFeed.avatarImage(row))
        XCTAssertFalse(oldImage === newImage, "换了头像必须重新解码")
        XCTAssertTrue(TeamAvatarFileCache.shared.contains(try XCTUnwrap(row.avatarImageHash)), "新头像应已落盘")
    }

    // MARK: - 名册保存顺带清理（端到端）

    @MainActor
    func testRosterSavePrunesAvatarOfRemovedBot() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "TeamAvatarPruneTests-\(UUID().uuidString)"))
        let store = TeamRosterCacheStore(defaults: defaults)
        let key = "team-feed-resume.tests"
        func snapshot(_ ids: [String]) -> [String: Any] {
            ["instanceId": "inst", "bots": ids.map { id in
                ["id": id, "name": id, "avatar": ["hasImage": true,
                                                  "imageHash": TeamAvatarFileCache.hash(of: samplePNG(15))]]
            }]
        }
        let hashA = TeamAvatarFileCache.hash(of: samplePNG(16))
        let hashB = TeamAvatarFileCache.hash(of: samplePNG(17))
        _ = TeamAvatarFileCache.shared.store(imageData: samplePNG(16))
        _ = TeamAvatarFileCache.shared.store(imageData: samplePNG(17))
        XCTAssertTrue(TeamAvatarFileCache.shared.contains(hashA))
        XCTAssertTrue(TeamAvatarFileCache.shared.contains(hashB))

        store.saveNow(serverKey: key, snapshot: snapshot(["a"]).merging(["bots": [
            ["id": "a", "name": "a", "avatar": ["hasImage": true, "imageHash": hashA]],
        ]]) { _, new in new }, instanceId: "inst")
        XCTAssertTrue(TeamAvatarFileCache.shared.contains(hashA))
        XCTAssertFalse(TeamAvatarFileCache.shared.contains(hashB), "名册里已经没有的 Bot，头像文件必须被清掉")
    }
}

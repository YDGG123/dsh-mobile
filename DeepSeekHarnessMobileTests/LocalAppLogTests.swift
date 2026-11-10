import XCTest
@testable import DeepSeekHarnessMobile

final class LocalAppLogTests: XCTestCase {
    private let grant = LogExportGrant.mintForTests()

    func testRangeSummaryAndArchiveExcludeOtherRecordsInSameHour() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalAppLog(directory: directory)
        let base = Date(timeIntervalSince1970: 1_800_000_000)
        store.write("before", at: base)
        store.write("inside\ncontinuation", at: base.addingTimeInterval(60))
        store.write("end-exclusive", at: base.addingTimeInterval(120))
        let interval = DateInterval(start: base.addingTimeInterval(60), duration: 60)
        let summary = try await store.summary(in: interval)
        XCTAssertEqual(summary.fileCount, 1)
        XCTAssertEqual(summary.earliest, base)
        let url = try await store.exportArchive(in: interval, grant: grant)
        let data = try Data(contentsOf: url)
        XCTAssertEqual(Array(data.prefix(4)), [0x50, 0x4b, 0x03, 0x04])
        XCTAssertNotNil(data.range(of: Data("inside\ncontinuation".utf8)))
        XCTAssertNil(data.range(of: Data("before".utf8)))
        XCTAssertNil(data.range(of: Data("end-exclusive".utf8)))
        let empty = try await store.summary(in: DateInterval(start: base.addingTimeInterval(180), duration: 60))
        XCTAssertEqual(empty.fileCount, 0)
        do {
            _ = try await store.exportArchive(in: DateInterval(start: base.addingTimeInterval(180), duration: 60), grant: grant)
            XCTFail("空时间段应拒绝导出")
        } catch {}
    }

    func testNestedResidualFilesCountAgainstProductionLimit() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let nested = directory.appendingPathComponent("abandoned/nested", isDirectory: true)
        try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
        let residual = nested.appendingPathComponent("export.partial")
        try Data().write(to: residual)
        let handle = try FileHandle(forWritingTo: residual)
        try handle.truncate(atOffset: 200_000_001)
        try handle.close()
        let store = LocalAppLog(directory: directory)
        store.write("latest")
        let snapshot = try await store.export(grant: grant)
        XCTAssertFalse(FileManager.default.fileExists(atPath: residual.path))
        let text = try snapshot.map { try String(contentsOf: $0, encoding: .utf8) }.joined()
        XCTAssertTrue(text.contains("latest"))
        let urls = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.fileSizeKey])!
            .compactMap { $0 as? URL }
        let bytes = try urls.reduce(0) { $0 + (try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        XCTAssertLessThanOrEqual(bytes, 200_000_000)
    }

    func testHourlyRotationAndBoundedExport() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalAppLog(directory: directory, limit: 2_000_000)
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        store.write("first", at: date)
        store.write("same-hour", at: date.addingTimeInterval(60))
        store.write("next-hour", at: date.addingTimeInterval(3600))
        let snapshot = try await store.export(grant: grant)
        XCTAssertEqual(snapshot.count, 2)
        XCTAssertTrue(snapshot.allSatisfy {
            $0.lastPathComponent.range(of: #"^\d{10}00\.log$"#, options: .regularExpression) != nil
        })
        let text = try snapshot.map { try String(contentsOf: $0, encoding: .utf8) }.joined()
        XCTAssertTrue(text.contains("same-hour"))
        XCTAssertTrue(text.contains("next-hour"))
        for index in 0..<160 {
            store.write("\(index) " + String(repeating: "x", count: 16_000),
                        at: date.addingTimeInterval(Double(index) * 3600))
        }
        _ = try await store.export(grant: grant)
        let urls = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.fileSizeKey])!
            .compactMap { $0 as? URL }
        let total = try urls.reduce(0) { $0 + (try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        XCTAssertLessThanOrEqual(total, 2_000_000)
    }

    // MARK: - 秘密样本（规格 §4.6 / P0-AT-06：注入后必须全部被遮）

    /// (用例名, 注入行, 必须消失的秘密, 必须保留的结构标记)
    private let secretSamples: [(name: String, line: String, secrets: [String], keep: String)] = [
        ("jwt", "notice auth received eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c ok",
         ["eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c"], "[REDACTED:jwt]"),
        ("bearer", "GET /ws Authorization: Bearer sk-live-abcdef1234567890ABCDEF",
         ["sk-live-abcdef1234567890ABCDEF"], "Authorization: [REDACTED]"),
        ("basic", "upstream replied to Basic dXNlcjpwYXNzd29yZDEyMzQ1Njc4",
         ["dXNlcjpwYXNzd29yZDEyMzQ1Njc4"], "Basic [REDACTED]"),
        ("url-query", "connect wss://gateway.example.com/ws/mobile?token=tok_abc123def456&refresh=rfr_987654321&page=2",
         ["tok_abc123def456", "rfr_987654321"], "page=2"),
        ("cookie", "Cookie: dsh-auth-abc=v1.eyJhbGciOiJub25lIn0.sig; theme=dark",
         ["dsh-auth-abc", "v1.eyJhbGciOiJub25lIn0.sig"], "Cookie: [REDACTED]"),
        ("json-fields", "payload {\"refreshToken\":\"rt-secret-123\",\"pairingCode\":\"demo-pairing-0001\",\"name\":\"gw\"}",
         ["rt-secret-123", "demo-pairing-0001"], "\"name\":\"gw\""),
        ("keychain", "keychain err=-25299 acct=bridge-token-42 svce=ai.dsh.bridge",
         ["bridge-token-42"], "svce=ai.dsh.bridge"),
        ("home-path", "crash dump at /Users/someone/Library/secrets.db",
         ["/Users/someone"], "/Users/[REDACTED]"),
    ]

    func testSecretSamplesRedactedAtWriteAndIdempotent() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalAppLog(directory: directory)
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        for sample in secretSamples {
            XCTAssertEqual(LogRedactor.scrub(LogRedactor.scrub(sample.line)), LogRedactor.scrub(sample.line),
                           "\(sample.name) 脱敏必须幂等")
            store.write(sample.line, at: date)
        }
        // summary 走同一串行队列，充当落盘完成屏障。
        _ = try await store.summary(in: DateInterval(start: date.addingTimeInterval(-3600), duration: 7200))
        let text = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "log" }
            .map { try String(contentsOf: $0, encoding: .utf8) }
            .joined()
        for sample in secretSamples {
            for secret in sample.secrets {
                XCTAssertFalse(text.contains(secret), "\(sample.name) 秘密必须被遮: \(secret)")
            }
            XCTAssertTrue(text.contains(sample.keep), "\(sample.name) 应保留结构标记 \(sample.keep)")
        }
    }

    func testExportSecondPassRedactsLegacyPlaintextOnDisk() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // 模拟旧版本写入的明文行：绕过 write API 直接落盘，导出必须二次脱敏。
        let stamp = ISO8601DateFormatter().string(from: Date())
        let legacy = "\(stamp) legacy Bearer sk-live-legacy-token-1234567890 jwt eyJhbGciOiJIUzI1NiJ9.eyJvbGQ9MX0.c2ln\n"
        try legacy.write(to: directory.appendingPathComponent("legacy-0000.log"), atomically: true, encoding: .utf8)
        let store = LocalAppLog(directory: directory)
        let interval = DateInterval(start: Date().addingTimeInterval(-60), duration: 120)
        let url = try await store.exportArchive(in: interval, grant: grant)
        let data = try Data(contentsOf: url)
        XCTAssertNil(data.range(of: Data("sk-live-legacy-token-1234567890".utf8)), "导出副本不得残留盘上明文 token")
        XCTAssertNil(data.range(of: Data("eyJhbGciOiJIUzI1NiJ9.eyJvbGQ9MX0.c2ln".utf8)), "导出副本不得残留盘上明文 jwt")
        XCTAssertNotNil(data.range(of: Data("Bearer [REDACTED]".utf8)))
        XCTAssertNotNil(data.range(of: Data("[REDACTED:jwt]".utf8)))
    }

    func testStructuredEventWhitelistOnlyEmitsListedFields() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalAppLog(directory: directory)
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        store.write(event: "gateway.connect", status: 401, durationMillis: 320,
                    category: "auth.failed", detail: "POST https://gw.example.com/api?token=abc123def456")
        _ = try await store.summary(in: DateInterval(start: date.addingTimeInterval(-3600), duration: 7200))
        let text = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "log" }
            .map { try String(contentsOf: $0, encoding: .utf8) }
            .joined()
        XCTAssertTrue(text.contains("EVENT gateway.connect status=401 durationMs=320 category=auth.failed"))
        XCTAssertTrue(text.contains("token=[REDACTED]"))
        XCTAssertFalse(text.contains("abc123def456"))
    }
}

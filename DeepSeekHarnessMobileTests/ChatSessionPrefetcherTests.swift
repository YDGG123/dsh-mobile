import XCTest
@testable import DeepSeekHarnessMobile

@MainActor
final class ChatSessionPrefetcherTests: XCTestCase {
    func testWaitsForHelloSessionsAndForegroundIdleThenUsesHeatOrder() async throws {
        let h = Harness()
        let candidates = [candidate("current"), candidate("cold"), candidate("hot")]
        h.prefetch.update(candidates: candidates, selected: "current", busy: false, sessionsReceived: true)
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertTrue(h.requests.isEmpty)
        h.prefetch.connectedHello()
        h.prefetch.update(candidates: candidates, selected: "current", busy: true, sessionsReceived: true)
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertTrue(h.requests.isEmpty)
        h.prefetch.update(candidates: candidates, selected: "current", busy: false)
        await waitFor { h.requests.count == 1 }
        XCTAssertEqual(h.requests.map(\.0), ["hot"])
        XCTAssertTrue(h.prefetch.consume(try frame("hot", requestID: h.requests[0].1)))
        await waitFor { h.requests.count == 2 }
        XCTAssertEqual(h.requests.map(\.0), ["hot", "cold"])
        XCTAssertEqual(h.accepted, ["hot"])
        h.prefetch.stop(reason: "test")
    }

    func testSingleConcurrentRequestAndSixPerConnectionWithoutSessionUpdateRepeats() async throws {
        let h = Harness()
        h.prefetch.connectedHello()
        let candidates = (0..<10).map { candidate("s\($0)") }
        h.prefetch.update(candidates: candidates, selected: nil, busy: false, sessionsReceived: true)
        for count in 1...6 {
            await waitFor { h.requests.count == count }
            try await Task.sleep(for: .milliseconds(3))
            XCTAssertEqual(h.requests.count, count, "未完成请求期间不能继续发下一页")
            let request = h.requests[count - 1]
            XCTAssertTrue(h.prefetch.consume(try frame(request.0, requestID: request.1)))
        }
        await waitFor { h.accepted.count == 6 }
        h.prefetch.update(candidates: candidates, selected: nil, busy: false, sessionsReceived: true)
        try await Task.sleep(for: .milliseconds(15))
        XCTAssertEqual(h.requests.count, 6)
        XCTAssertEqual(Set(h.requests.map(\.0)).count, 6)
        h.prefetch.stop(reason: "test")
    }

    func testUserOpeningInflightSessionAdoptsLatestPageWithoutDuplicateOrCacheWrite() async throws {
        let h = Harness()
        await h.begin([candidate("hot")])
        XCTAssertFalse(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: false))
        XCTAssertFalse(h.prefetch.consume(try frame("hot")), "接管后的响应必须交回正常前台 history 路由")
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertTrue(h.accepted.isEmpty)
        XCTAssertEqual(h.requests.count, 1)
        XCTAssertTrue(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: false))
        h.prefetch.stop(reason: "test")
    }

    func testForegroundHandoffErrorReleasesSlotAndAllowsRealRetry() async throws {
        for errorJSON in [
            #"{"kind":"error","requestType":"history","sessionId":"hot","message":"failed"}"#,
            #"{"kind":"error","requestType":"history","message":"failed"}"#
        ] {
            let h = Harness()
            await h.begin([candidate("hot")])
            XCTAssertFalse(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: false))
            let error = try GatewayWireDecoder.decode(Data(errorJSON.utf8))
            XCTAssertFalse(h.prefetch.consumeHistoryError(error, foregroundHistoryPending: true))
            XCTAssertTrue(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: false), "终止错误后必须实际发送重试，不能继续接管已结束请求")
            h.prefetch.stop(reason: "test")
        }
    }

    func testAssistantStreamSelectionDrainsRawPageWithoutEnteringForegroundRoute() async throws {
        let h = Harness()
        await h.begin([candidate("hot")])
        h.prefetch.prioritizeSelection(sessionID: "hot", assistantStream: true)
        XCTAssertTrue(h.prefetch.consume(try frame("hot")))
        XCTAssertTrue(h.accepted.isEmpty)
        XCTAssertTrue(h.foregroundReady.isEmpty)
        h.prefetch.stop(reason: "test")
    }

    func testCancelledLegacyRequestQuarantinesLateResponseBeforeDeferredForegroundSend() async throws {
        for reason in ["clear", "memory", "disconnect", "deactivate"] {
            let h = Harness()
            await h.begin([candidate("hot")])
            h.prefetch.stop(reason: reason)
            XCTAssertFalse(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: false))
            XCTAssertFalse(h.prefetch.consume(try frame("other")))
            XCTAssertTrue(h.prefetch.consume(try frame("hot")))
            XCTAssertEqual(h.foregroundReady, ["hot"])
            XCTAssertTrue(h.accepted.isEmpty)
            XCTAssertTrue(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: false))
        }
    }

    func testForegroundDemandRecoversDrainingSocketOnceWithoutWaitingForMissingReply() async throws {
        for startsDraining in [true, false] {
            let h = Harness()
            await h.begin([candidate("hot")])
            if startsDraining { h.prefetch.stop(reason: "clear") }
            XCTAssertTrue(h.reconnects.isEmpty, "纯后台取消不能主动重连")
            XCTAssertFalse(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: !startsDraining))
            XCTAssertEqual(h.reconnects, ["hot"])
            XCTAssertFalse(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: !startsDraining))
            XCTAssertEqual(h.reconnects.count, 1)
            // 不喂旧响应：新连接由 transport socket identity 隔离，hello 必须解除旧槽。
            h.prefetch.connectedHello()
            XCTAssertTrue(h.prefetch.shouldSendForeground(sessionID: "hot", isOlder: !startsDraining))
            h.prefetch.stop(reason: "test")
        }
    }

    func testTimeoutEndsRoundAndLateReplyCannotWriteOrStartAnotherRequest() async throws {
        let h = Harness(timeout: .milliseconds(10))
        await h.begin([candidate("hot"), candidate("cold")])
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertTrue(h.prefetch.consume(try frame("hot")))
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(h.requests.count, 1)
        XCTAssertTrue(h.accepted.isEmpty)
        h.prefetch.stop(reason: "test")
    }

    func testRequestIdentityAndMissingSessionErrorsDoNotConsumeForegroundHistory() async throws {
        let h = Harness()
        await h.begin([candidate("hot")])
        XCTAssertFalse(h.prefetch.consume(try frame("hot", requestID: "foreground")))
        let ambiguous = try GatewayWireDecoder.decode(Data(#"{"kind":"error","requestType":"history","message":"failed"}"#.utf8))
        XCTAssertFalse(h.prefetch.consumeHistoryError(ambiguous, foregroundHistoryPending: true))
        XCTAssertTrue(h.prefetch.consumeHistoryError(ambiguous, foregroundHistoryPending: false))
        XCTAssertTrue(h.accepted.isEmpty)
        h.prefetch.stop(reason: "test")
    }

    func testForegroundBusyPausesRemainingQueueAndSelectionIsExcludedAfterResume() async throws {
        let h = Harness()
        let all = [candidate("hot"), candidate("cold"), candidate("third")]
        await h.begin(all)
        h.prefetch.update(candidates: all, selected: "cold", busy: true)
        XCTAssertTrue(h.prefetch.consume(try frame("hot")))
        await waitFor { h.accepted.count == 1 }
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(h.requests.count, 1)
        h.prefetch.update(candidates: all, selected: "cold", busy: false)
        await waitFor { h.requests.count == 2 }
        XCTAssertEqual(h.requests[1].0, "third")
        h.prefetch.stop(reason: "test")
    }

    func testNewConnectionRejectsTaggedPreviousResponseAndRunsOnlyNewRound() async throws {
        let h = Harness()
        await h.begin([candidate("hot")])
        let previousID = h.requests[0].1
        h.prefetch.connectedHello()
        h.prefetch.update(candidates: [candidate("hot")], selected: nil, busy: false, sessionsReceived: true)
        await waitFor { h.requests.count == 2 }
        XCTAssertTrue(h.prefetch.consume(try frame("hot", requestID: previousID)), "旧预加载标识只能丢弃，不能完成当前请求")
        XCTAssertTrue(h.prefetch.consume(try frame("hot", requestID: h.requests[1].1)))
        await waitFor { h.accepted.count == 1 }
        h.prefetch.stop(reason: "test")
    }

    func testOversizedBackgroundPageIsDiscardedWithoutAffectingForeground() async throws {
        let h = Harness()
        await h.begin([candidate("hot")])
        XCTAssertTrue(h.prefetch.consume(try frame("hot", text: String(repeating: "x", count: 300 * 1024))))
        await waitFor { !h.invalidated.isEmpty }
        XCTAssertTrue(h.accepted.isEmpty)
        h.prefetch.stop(reason: "test")
    }

    func testActualRoomHeatPrefetchOpensOfflineWithoutChangingSessionMetadata() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("prefetch.db"))
        let owner = cache.makeProtectionOwner(gatewayID: "g")
        await cache.recordSessionOpen(owner: owner, sessionID: "hot")
        await cache.recordSessionOpen(owner: owner, sessionID: "hot")
        var requests: [(String, String)] = []
        var saved = false
        let coordinator = ChatSessionPrefetcher(idleDelay: .milliseconds(1),
            rank: { await cache.planPrefetch(owner: owner, candidates: $0, selected: $1) },
            ticket: { await cache.prefetchTicket(owner: owner, sessionID: $0) },
            accept: { id, json, ticket in
                saved = await cache.acceptPrefetch(owner: owner, sessionID: id, json: json, ticket: ticket)
                return saved
            },
            invalidate: { await cache.cancelPrefetch(owner: owner, sessionID: $0, ticket: $1) },
            request: { requests.append(($0, $1)) }, foregroundReady: { _ in XCTFail("后台预加载不能触发前台请求") })
        coordinator.connectedHello()
        coordinator.update(candidates: [candidate("hot", activity: 1), candidate("new", activity: Date().timeIntervalSince1970)],
            selected: nil, busy: false, sessionsReceived: true)
        await waitFor { requests.count == 1 }
        XCTAssertEqual(requests.first?.0, "hot")
        XCTAssertTrue(coordinator.consume(try frame("hot", requestID: requests[0].1, text: "热预加载正文")))
        await waitFor { saved }
        coordinator.stop(reason: "test")
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "hot", title: "缓存会话", lastActivity: Date(timeIntervalSince1970: 1), isRunning: false, hasUnread: true)
        preferences.saveSessions([session])
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        XCTAssertNil(store.selectedSessionId)
        XCTAssertTrue(store.selectedEvents.isEmpty)
        let prepared = await store.prepareConversation(for: session)
        XCTAssertTrue(prepared)
        await store.awaitConversationProjectionForTesting(sessionID: "hot", expectedText: "热预加载正文")
        XCTAssertEqual(store.selectedEvents.first?.event.text, "热预加载正文")
        XCTAssertEqual(store.sessions.first?.lastActivity, session.lastActivity)
        XCTAssertNil(store.lastError)
        store.deactivateGateway()
        await cache.close()
    }

    func testAppStorePrefetchesBackgroundRunningSessionWithoutPublishingHistoryOrUnreadChanges() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("app-prefetch.db"))
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let current = SessionSummary(id: "current", title: "当前生成", lastActivity: Date(timeIntervalSince1970: 1), isRunning: true, hasUnread: false)
        preferences.saveSessions([current])
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        let prepared = await store.prepareConversation(for: current)
        XCTAssertTrue(prepared)
        for json in [
            #"{"kind":"hello","historyFormatVersion":1,"capabilities":[]}"#,
            #"{"kind":"sessions","items":[{"sessionId":"current","title":"当前生成","updatedAt":100,"running":true,"blank":false},{"sessionId":"hot","title":"后台生成","updatedAt":100,"running":true,"blank":false}]}"#
        ] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
            await store.awaitPendingKMPEventDeliveriesForTesting()
        }
        store.gateway.onFrame?(try frame("current", text: "当前正文"))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        await store.awaitConversationProjectionForTesting(sessionID: "current", expectedText: "当前正文")
        try await Task.sleep(for: .milliseconds(800))
        XCTAssertEqual(store.chatPrefetchRequestCountForTesting, 0, "当前会话生成时必须暂停")
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"sessions","items":[{"sessionId":"current","title":"当前生成","updatedAt":100,"running":false,"blank":false},{"sessionId":"hot","title":"后台生成","updatedAt":100,"running":true,"blank":false}]}"#.utf8)))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        await waitFor { store.chatPrefetchRequestCountForTesting == 1 }
        let before = store.sessions
        store.gateway.onFrame?(try frame("hot", text: "独立缓存正文"))
        var cached: String?
        let deadline = Date().addingTimeInterval(3)
        repeat {
            cached = await cache.load(gatewayID: "g", sessionID: "hot")
            if cached == nil { try? await Task.sleep(for: .milliseconds(2)) }
        } while cached == nil && Date() < deadline
        XCTAssertTrue(cached?.contains("独立缓存正文") == true)
        XCTAssertEqual(store.selectedSessionId, "current")
        XCTAssertEqual(store.selectedEvents.first?.event.text, "当前正文")
        XCTAssertNil(store.events["hot"])
        XCTAssertTrue(store.historyLoadingSessionIds.isEmpty)
        XCTAssertEqual(store.sessions, before, "预加载不能改变未读、运行状态和会话活动时间")
        XCTAssertNil(store.lastError)
        store.deactivateGateway()
        await cache.close()
    }

    private func candidate(_ id: String, activity: Double = 1) -> ChatSessionPrefetcher.Candidate {
        .init(id: id, lastActivityEpochSeconds: activity, isRunning: false, hasUnread: false, hasConversation: true)
    }
    private func frame(_ id: String, requestID: String? = nil, text: String = "缓存正文") throws -> GatewayFrame {
        var object: [String: Any] = ["kind": "history", "sessionId": id, "view": "conversation", "historyFormatVersion": 1,
            "hasMore": false, "events": [["type": "user/message", "seq": 1, "time": 1, "data": ["content": [["type": "text", "text": text]]]]]]
        if let requestID { object["requestId"] = requestID }
        return try GatewayWireDecoder.decode(JSONSerialization.data(withJSONObject: object))
    }
    private func waitFor(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        let deadline = Date().addingTimeInterval(3)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(2)) }
        XCTAssertTrue(condition(), "等待异步缓存条件超时", file: file, line: line)
    }

    @MainActor
    private final class Harness {
        var requests: [(String, String)] = []
        var accepted: [String] = []
        var invalidated: [String] = []
        var foregroundReady: [String] = []
        var reconnects: [String] = []
        let timeout: Duration
        init(timeout: Duration = .seconds(5)) { self.timeout = timeout }
        lazy var prefetch = ChatSessionPrefetcher(idleDelay: .milliseconds(1), timeout: timeout,
            rank: { candidates, _ in candidates.sorted { lhs, rhs in
                if lhs.id == "hot" { return true }
                if rhs.id == "hot" { return false }
                return lhs.id < rhs.id
            }.map(\.id) }, ticket: { "ticket-" + $0 },
            accept: { [weak self] id, _, _ in self?.accepted.append(id); return true },
            invalidate: { [weak self] id, _ in self?.invalidated.append(id) },
            request: { [weak self] id, token in self?.requests.append((id, token)) },
            foregroundReady: { [weak self] id in self?.foregroundReady.append(id) },
            reconnectForeground: { [weak self] id in self?.reconnects.append(id) })
        func begin(_ candidates: [ChatSessionPrefetcher.Candidate]) async {
            prefetch.connectedHello()
            prefetch.update(candidates: candidates, selected: nil, busy: false, sessionsReceived: true)
            let deadline = Date().addingTimeInterval(3)
            while requests.isEmpty, Date() < deadline { try? await Task.sleep(for: .milliseconds(2)) }
            XCTAssertFalse(requests.isEmpty)
        }
    }
}

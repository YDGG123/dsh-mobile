import XCTest
import os.signpost
import QuartzCore

/// 只对用户已打开的真实会话做只读手势；常规测试不会自动运行此采样。
final class RealSessionScrollPerformanceTests: XCTestCase {
    func testScrollTowardOlderHistoryInExistingSession() throws {
        guard ProcessInfo.processInfo.environment["DSH_REAL_SESSION_SCROLL"] == "1" else {
            throw XCTSkip("需要已打开的真实长会话，显式启用后采样")
        }
#if targetEnvironment(simulator)
        guard ProcessInfo.processInfo.environment["DSH_SCROLL_ALLOW_SIMULATOR"] == "1" else {
            throw XCTSkip("模拟器性能采样需显式启用，并与真机结果区分")
        }
#endif
        continueAfterFailure = false
        let app = XCUIApplication()
        let environment = ProcessInfo.processInfo.environment
        if let rawURL = environment["DSH_SCROLL_EXISTING_SESSION_URL"], let url = URL(string: rawURL),
           url.scheme == "dshmobile", url.host == "session" {
            // 正常重启只清进程内缓存，保留用户数据库与配对；不提供 fake/reset 参数。
            app.launchEnvironment["DSH_HISTORY_SCROLL_METRICS"] = "1"
            app.launchEnvironment["DSH_HISTORY_SCROLL_METRICS_SECONDS"] = environment["DSH_SCROLL_SECONDS"] ?? "120"
            app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
            app.launch()
            XCTAssertTrue(app.staticTexts["已连接"].waitForExistence(timeout: 20), "需要真实网关连接")
            // 显式目标 App 的公开测试 API，避免系统 URL 确认框遮挡滚动。
            app.open(url)
        } else {
            XCTAssertNotEqual(app.state, .notRunning, "请先打开已存在的真实会话")
            app.activate()
        }
        let viewport = app.collectionViews["conversation-history-viewport"]
        XCTAssertTrue(viewport.waitForExistence(timeout: 10), "当前页面必须是会话详情")
        XCTAssertGreaterThan(viewport.cells.count, 0)
        // 两包都给首屏同样的固定稳定时间；首个用户手势才启动 DEBUG 采样。
        Thread.sleep(forTimeInterval: 2)

        let driverMode = environment["DSH_SCROLL_DRIVER"] ?? "query"
        XCTAssertTrue(["query", "coordinates"].contains(driverMode), "未知滚动驱动模式")
        // 公共 XCUICoordinate API。只在计时前查询一次视口与 App 几何；循环引用 App 根坐标。
        let cachedDrag: (() -> Void)?
        if driverMode == "coordinates" {
            let viewportFrame = viewport.frame
            let appFrame = app.frame
            XCTAssertGreaterThan(viewportFrame.height, 100)
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let x = viewportFrame.midX - appFrame.minX
            let start = origin.withOffset(CGVector(dx: x, dy: viewportFrame.minY + viewportFrame.height * 0.25 - appFrame.minY))
            let end = origin.withOffset(CGVector(dx: x, dy: viewportFrame.minY + viewportFrame.height * 0.80 - appFrame.minY))
            cachedDrag = {
                start.press(forDuration: 0, thenDragTo: end, withVelocity: .fast, thenHoldForDuration: 0)
            }
        } else {
            cachedDrag = nil
        }
        NSLog("DSH_SCROLL_DRIVER=%@", driverMode)
        let log = OSLog(subsystem: "com.dsh.scroll-measurement", category: .pointsOfInterest)
        let swipeCount = min(80, max(2, Int(environment["DSH_SCROLL_SWIPE_COUNT"] ?? "") ?? 30))
        let scroll = {
            os_signpost(.begin, log: log, name: "RealHistoryScroll")
            for _ in 0..<swipeCount {
                // 手指向下拖动，列表向较早的历史消息移动。
                if let cachedDrag { cachedDrag() }
                else { viewport.swipeDown(velocity: .fast) }
            }
            os_signpost(.end, log: log, name: "RealHistoryScroll")
        }
#if targetEnvironment(simulator)
        // Simulator 图形仪器不可用；此处只驱动手势，诊断来自独立 callback CSV。
        scroll()
#else
        let options = XCTMeasureOptions()
        options.iterationCount = 1
        measure(metrics: [XCTOSSignpostMetric.scrollDecelerationMetric], options: options, block: scroll)
#endif
        // 此断言只确认手势完成；性能及“真实分页已到达”必须由 trace 中的绘制与分页标记验收。
        XCTAssertTrue(viewport.exists)
        // 最后一次手势后继续观测惯性与一秒 settle，避免返回转场截断活跃尾部。
        Thread.sleep(forTimeInterval: 2)
        // 返回只结束阅读；触发 viewport 生命周期导出，不终止进程导致末尾样本丢失。
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.exists)
        back.tap()
        XCTAssertFalse(viewport.exists)
    }
}

/// 独立的新策略场景；这里只驱动公开手势，实际覆盖与体验结论必须来自 App 原始采样。
@MainActor
final class ControlledHistoryPresentationPerformanceTests: XCTestCase {
    private struct Parameters: Codable {
        let cycles: Int
        let endHoldSeconds: Double
        let briefIdleSeconds: Double
        let finalIdleSeconds: Double

        init(environment: [String: String]) throws {
            func number(_ key: String, default value: Double, range: ClosedRange<Double>) throws -> Double {
                guard let raw = environment[key] else { return value }
                let parsed = try XCTUnwrap(Double(raw), "参数 \(key) 必须是数字")
                return try XCTUnwrap(parsed.isFinite && range.contains(parsed) ? parsed : nil,
                                     "参数 \(key) 超出允许范围")
            }
            if let raw = environment["DSH_CONTROLLED_CYCLES"] {
                let parsed = try XCTUnwrap(Int(raw), "DSH_CONTROLLED_CYCLES 必须是整数")
                cycles = try XCTUnwrap((1...4).contains(parsed) ? parsed : nil,
                                       "DSH_CONTROLLED_CYCLES 必须在1至4之间")
            } else {
                cycles = 2
            }
            endHoldSeconds = try number("DSH_CONTROLLED_HOLD_SECONDS", default: 0.25, range: 0...1)
            briefIdleSeconds = try number("DSH_CONTROLLED_PAUSE_SECONDS", default: 0.20, range: 0.05...2)
            finalIdleSeconds = try number("DSH_CONTROLLED_FINAL_IDLE_SECONDS", default: 8, range: 3...15)
        }
    }

    private struct IntentPhase: Codable {
        let name: String
        let cycle: Int
        let beganMonotonicSeconds: Double
        var endedMonotonicSeconds: Double?
    }

    private struct IntentReport: Encodable {
        let schemaVersion = 1
        let driver = "public_xctest_controlled_coordinates"
        let parameters: Parameters
        let initialViewportFramePoints: [Double]
        let phases: [IntentPhase]
        let externalRawCoverageRequired = true
        let assertsPerformanceOrActualInterruption = false
        let measuresPhysicalTouchDeliveryLatency = false
        let notes = "命令时间仅代表驱动意图。XCTest可能等待App静止；固定pause不是实际idle或中断证据。必须用真实scroll/operation/batch/final-ack采样检验，未覆盖记insufficient。"
    }

    func testReadPauseAndAttemptInterruptionInExistingSession() throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["DSH_CONTROLLED_PRESENTATION_SCROLL"] == "1" else {
            throw XCTSkip("仅显式启用的真实会话受控呈现场景")
        }
#if targetEnvironment(simulator)
        guard environment["DSH_SCROLL_ALLOW_SIMULATOR"] == "1" else {
            throw XCTSkip("模拟器场景必须显式启用并单独标记")
        }
#endif
        continueAfterFailure = false
        let parameters = try Parameters(environment: environment)
        guard let rawURL = environment["DSH_SCROLL_EXISTING_SESSION_URL"],
              let url = URL(string: rawURL), url.scheme == "dshmobile", url.host == "session" else {
            XCTFail("需要本机私有配置中的已有真实会话deep link")
            return
        }
        let app = XCUIApplication()
        app.launchEnvironment["DSH_HISTORY_SCROLL_METRICS"] = "1"
        app.launchEnvironment["DSH_HISTORY_SCROLL_METRICS_SECONDS"] = environment["DSH_SCROLL_SECONDS"] ?? "120"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.staticTexts["已连接"].waitForExistence(timeout: 20), "需要真实网关连接")
        app.open(url)
        let viewport = app.collectionViews["conversation-history-viewport"]
        XCTAssertTrue(viewport.waitForExistence(timeout: 10), "必须打开既有会话详情")
        XCTAssertGreaterThan(viewport.cells.count, 0)
        Thread.sleep(forTimeInterval: 2)

        // 只在动作前显式读取一次几何。公开coordinate API自身仍可能执行AX同步。
        let viewportFrame = viewport.frame
        let appFrame = app.frame
        XCTAssertGreaterThan(viewportFrame.height, 100)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let x = viewportFrame.midX - appFrame.minX
        let start = origin.withOffset(CGVector(dx: x,
            dy: viewportFrame.minY + viewportFrame.height * 0.25 - appFrame.minY))
        let end = origin.withOffset(CGVector(dx: x,
            dy: viewportFrame.minY + viewportFrame.height * 0.82 - appFrame.minY))
        let interruptionEnd = origin.withOffset(CGVector(dx: x,
            dy: viewportFrame.minY + viewportFrame.height * 0.62 - appFrame.minY))
        var phases: [IntentPhase] = []
        defer {
            let report = IntentReport(parameters: parameters,
                initialViewportFramePoints: [Double(viewportFrame.minX), Double(viewportFrame.minY),
                                             Double(viewportFrame.width), Double(viewportFrame.height)],
                phases: phases)
            if let data = try? JSONEncoder().encode(report) {
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
                attachment.name = "controlled-presentation-driver-intent.json"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        func perform(_ name: String, cycle: Int, _ action: () -> Void) {
            let index = phases.count
            phases.append(IntentPhase(name: name, cycle: cycle,
                                      beganMonotonicSeconds: CACurrentMediaTime()))
            NSLog("DSH_CONTROLLED_PHASE begin=%@ cycle=%ld", name, cycle)
            action()
            phases[index].endedMonotonicSeconds = CACurrentMediaTime()
            NSLog("DSH_CONTROLLED_PHASE end=%@ cycle=%ld", name, cycle)
        }

        for cycle in 1...parameters.cycles {
            perform("slow_drag_and_end_hold", cycle: cycle) {
                start.press(forDuration: 0, thenDragTo: end, withVelocity: .slow,
                            thenHoldForDuration: parameters.endHoldSeconds)
            }
            perform("fast_release_for_deceleration", cycle: cycle) {
                start.press(forDuration: 0, thenDragTo: end, withVelocity: .fast, thenHoldForDuration: 0)
            }
            perform("brief_idle_observation", cycle: cycle) {
                Thread.sleep(forTimeInterval: parameters.briefIdleSeconds)
            }
            perform("new_drag_attempting_interruption", cycle: cycle) {
                start.press(forDuration: 0, thenDragTo: interruptionEnd,
                            withVelocity: .fast, thenHoldForDuration: 0)
            }
        }
        // 只提供完成机会，不把sleep视为owner已经释放；raw必须有terminal及最新target最终ack。
        perform("final_idle_observation", cycle: parameters.cycles) {
            Thread.sleep(forTimeInterval: parameters.finalIdleSeconds)
        }
        XCTAssertTrue(viewport.exists)
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.exists)
        back.tap()
        XCTAssertFalse(viewport.exists)
    }
}

/// 需要已配对的真实网关；通过环境变量显式启用，常规 CI 不访问用户会话。
final class RealSessionDirectoryLinkUITests: XCTestCase {
    func testDirectoryLinkOpensExistingWorkspaceBrowserAtTargetDirectory() throws {
        guard ProcessInfo.processInfo.environment["DSH_DIRECTORY_SMOKE"] == "1" else {
            throw XCTSkip("真实目录链接验收需要显式启用")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["已连接"].waitForExistence(timeout: 20))
        let session = app.staticTexts["制作产品宣传片"].firstMatch
        XCTAssertTrue(session.waitForExistence(timeout: 10))
        session.tap()
        let team = app.buttons["查看智能体团队"]
        XCTAssertTrue(team.waitForExistence(timeout: 10))
        team.tap()
        let member = app.staticTexts["footage-scout"].firstMatch
        XCTAssertTrue(member.waitForExistence(timeout: 5))
        member.tap()
        let viewport = app.collectionViews["conversation-history-viewport"]
        XCTAssertTrue(viewport.waitForExistence(timeout: 10))
        let link = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "stills/")).firstMatch
        for _ in 0..<6 {
            if link.exists && link.isHittable { break }
            viewport.swipeDown(velocity: .slow)
        }
        XCTAssertTrue(link.exists && link.isHittable, "会话中应有交付目录链接")
        link.tap()
        XCTAssertTrue(app.staticTexts["promo/analysis/stills"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["s04-scan-connected.png"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["完成"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "子 Agent 目录链接打开工作区文件"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}

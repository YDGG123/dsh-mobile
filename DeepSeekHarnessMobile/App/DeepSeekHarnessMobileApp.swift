import SwiftUI
import OSLog

private final class AgentNotificationAppDelegate: NSObject, UIApplicationDelegate {
    func applicationDidBecomeActive(_ application: UIApplication) {
        if #available(iOS 16.2, *) {
            AgentLiveActivityManager.shared.applicationDidBecomeActive()
        }
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
#if DEBUG
        AppExecutionHeartbeat.start()
#endif
        _ = AgentUserNotificationManager.shared
        return true
    }
}

@main
struct DeepSeekHarnessMobileApp: App {
    @UIApplicationDelegateAdaptor(AgentNotificationAppDelegate.self) private var notificationAppDelegate
    @StateObject private var hosts = MultiGatewayStore()

    init() {
        // U6：旧版本设置里有四个诊断 Toggle，用户做二分实验时可能把它们拨成「开」；
        // 新版本已无对应 UI，不清的话 App 会一直跑在降级形态（关离屏合成 / 关根 identity 等）。
        // 幂等：只在升级后首次执行，之后用 `defaults write` 设的逃逸值不会被抹掉。
        SettingsDiagnostics.resetLegacyTogglesIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // B4 候选 1：整棵树身份挂在 AppStore 实例指针上——任何路径在呈现中途替换
                // activeStore，SwiftUI 会拆掉正在呈现的 UIKit 层级（「退不出来只能杀 App」的形态）。
                // 诊断开关可关掉这个绑定（关掉后切网关不再重置整棵树，属可控副作用）。
                .modifier(RootIdentityBinding(store: hosts.activeStore))
                .environmentObject(hosts.activeStore)
                .environmentObject(hosts)
                .modifier(AppFontScope())
                .task {
                    hosts.bridgeTeam.ensureStarted()
                    await AgentUserNotificationManager.shared.requestAuthorizationIfNeeded()
                }
                .alert("主机连接", isPresented: Binding(get: { hosts.error != nil }, set: { if !$0 { hosts.error = nil } })) {
                    Button("好", role: .cancel) { hosts.error = nil; hosts.cancelPairing() }
                } message: { Text(hosts.error ?? "") }
        }
    }
}

#if DEBUG
/// 独立诊断进程是否仍在执行，不依赖任务、连接或主线程，也不申请后台保活额度。
@MainActor
private enum AppExecutionHeartbeat {
    private static var timer: DispatchSourceTimer?

    static func start() {
        guard timer == nil else { return }
        let logger = Logger(subsystem: "ai.dsh.mobile.ios", category: "app-execution-heartbeat")
        let processID = ProcessInfo.processInfo.processIdentifier
        let launchID = UUID().uuidString
        let startedAt = ProcessInfo.processInfo.systemUptime
        let queue = DispatchQueue(label: "ai.dsh.mobile.ios.execution-heartbeat", qos: .utility)
        let source = DispatchSource.makeTimerSource(queue: queue)
        source.schedule(deadline: .now(), repeating: .seconds(1), leeway: .milliseconds(50))
        source.setEventHandler {
            let uptime = ProcessInfo.processInfo.systemUptime
            let elapsed = uptime - startedAt
            logger.notice("[AppExecutionHeartbeat] pid=\(processID) launchID=\(launchID, privacy: .public) elapsedSeconds=\(elapsed) uptimeSeconds=\(uptime)")
        }
        // 持有 timer，使无任务、切后台和连接断开时仍继续尝试输出。
        timer = source
        source.resume()
    }
}
#endif

/// 诊断逃逸口：`dsh.diag.noRootIdentity` 可关掉根 identity 绑定。
/// **有意保留**（UI 已无开关，见 `SettingsDiagnostics` 注释）；默认 false = 正常形态。
private struct RootIdentityBinding: ViewModifier {
    let store: AppStore
    @AppStorage(SettingsDiagnostics.rootIdentityKey) private var disabled = false

    func body(content: Content) -> some View {
        if disabled {
            content
        } else {
            content.id(ObjectIdentifier(store))
        }
    }
}

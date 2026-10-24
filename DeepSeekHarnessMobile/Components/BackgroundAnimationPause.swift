import SwiftUI
import UIKit

/// B4：导航推入时暂停常驻背景动画。
///
/// **定位更正（2026-10-24，用户真机）**：空白推入页不卡 ⇒ 推入机制无罪，卡死根因在
/// 目标页的内容/生命周期，**与本组件无关**。结论见 `Docs/push-deadlock-analysis.md`。
/// 本组件保留的理由只是**性能**：整屏被覆盖时不必继续逐帧重绘。
///
/// 历史推断（**已被空白页实验推翻**，仅留作记录）：`HarnessAnimatedBackground` 用
/// `TimelineView(.animation)` 逐帧重建三层——iOS 17+ 的 Metal 流体着色器、Canvas 网格、
/// 鲸鱼粒子 Canvas。导航推入过渡期间，导航栈、转场快照与这三层逐帧绘制挤在同一个主线程帧里，
/// 过渡动画拿不到稳定帧率，表现出来就是「点进去卡住」。
///
/// 关键事实让修法变得简单：**推入后的子页会把背景完全盖住**，此时继续逐帧重绘
/// 没有任何视觉收益，纯粹在抢主线程。所以策略是「被子页覆盖时暂停」——
/// 不依赖「哪一层最贵」这个尚未实测的结论，三类图层一起停，弹回后自动恢复。
///
/// 覆盖关系用深度计数（支持多层推入）：子页 onAppear +1、onDisappear −1；
/// 计数归零后再保持一小段「结算窗口」，避免弹出过渡刚结束就立刻满帧重绘又卡一下。
@MainActor
final class BackgroundAnimationPause: ObservableObject {
    static let shared = BackgroundAnimationPause()

    @Published private(set) var coveredDepth = 0
    @Published private(set) var isSettling = false

    /// 结算窗口：弹出过渡（约 0.35s）期间继续暂停。
    private var settleTask: Task<Void, Never>?

    var shouldPause: Bool { coveredDepth > 0 || isSettling }

    func enter() {
        settleTask?.cancel()
        settleTask = nil
        isSettling = false
        coveredDepth += 1
    }

    func exit() {
        coveredDepth = max(0, coveredDepth - 1)
        guard coveredDepth == 0 else { return }
        settleTask?.cancel()
        isSettling = true
        settleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.isSettling = false
                self?.settleTask = nil
            }
        }
    }
}

/// 给「被推入的子页」加这个修饰符：在它覆盖背景期间暂停背景动画。
struct PausesBackgroundAnimation: ViewModifier {
    func body(content: Content) -> some View {
        content
            .onAppear { BackgroundAnimationPause.shared.enter() }
            .onDisappear { BackgroundAnimationPause.shared.exit() }
    }
}

extension View {
    /// 只在**会被推入/覆盖整屏**的页面根部使用（设置子页、插件详情等）。
    func pausesBackgroundAnimation() -> some View {
        modifier(PausesBackgroundAnimation())
    }
}

/// B4 的归因开关：只渲染指定图层，便于有设备时逐个排除。
///
/// 用法（Xcode scheme → Run → Arguments → Environment）：
/// - `DSH_BACKGROUND_LAYERS=whale`         只留鲸鱼
/// - `DSH_BACKGROUND_LAYERS=fluid,grid`    只留流体 + 网格
/// - `DSH_BACKGROUND_LAYERS=none`          全关（用来确认卡死是否与背景有关）
/// 不设该变量 = 三层全开（正常形态）。
enum HarnessBackgroundLayers {
    static let environmentKey = "DSH_BACKGROUND_LAYERS"

    static var enabled: Set<String> {
        let raw = ProcessInfo.processInfo.environment[environmentKey]
            ?? UserDefaults.standard.string(forKey: "dsh.background.layers")
            ?? ""
        let names = raw.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        guard !names.isEmpty else { return ["fluid", "grid", "whale"] }
        if names.contains("none") || names.contains("off") { return [] }
        return Set(names)
    }

    static func includes(_ name: String) -> Bool { enabled.contains(name) }
}

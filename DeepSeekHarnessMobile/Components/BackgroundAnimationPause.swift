import SwiftUI
import UIKit
import os

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

/// 背景动画相位时钟（上游 `4582f50` 的 `HarnessBackgroundAnimationClock`）：
/// 暂停期间把相位**冻结**在暂停那一刻，恢复时从原相位继续。
/// 为什么必须有它：`TimelineView(paused:)` 只是停帧，`timeline.date` 仍在走，
/// 恢复那一刻相位会直接跳到新的墙钟位置——肉眼可见的「背景跳一下」。
@MainActor
final class HarnessBackgroundAnimationClock: ObservableObject {
    static let shared = HarnessBackgroundAnimationClock()

    @Published private(set) var isPaused = false
    private var pausedAt: Date?
    private var pausedDuration: TimeInterval = 0

    func setPaused(_ paused: Bool, at now: Date = Date()) {
        guard paused != isPaused else { return }
        // `now` 是**注入的时间**（测试需要固定时钟），别再被本地 Date() 遮蔽——
        // 那会把真实墙钟间隔累进 pausedDuration，相位就错了（首轮用例红的原因）。
        if paused {
            pausedAt = now
        } else if let pausedAt {
            pausedDuration += now.timeIntervalSince(pausedAt)
            self.pausedAt = nil
        }
        isPaused = paused
        // 固定 ASCII 标记（≥16 字节）：iOSTester 的包级新增式指纹（72 包里为 0、73 ≥1）。
        // 只在状态真正切换时打一条（began/changed 不打，滚动中每帧一条会把日志刷爆）。
        Self.clockLog.notice("bganim-pause-resume paused=\(paused, privacy: .public)")
    }

    private static let clockLog = Logger(subsystem: "ai.dsh.mobile.ios", category: "background-anim")

    /// 相位锚点：暂停期间停在 `pausedAt`；其余时刻 = 当前时间 − 累计暂停时长。
    /// 从未暂停过时与墙钟完全一致（改动前行为零变化）。
    func anchor(at date: Date = Date()) -> Date {
        let reference = pausedAt ?? date
        return reference.addingTimeInterval(-pausedDuration)
    }
}

/// 滚动暂停的**触摸层挂载器**：找到最近的滚动容器，挂一个「不抢触摸、允许同时识别」的
/// 伴生 pan 手势——`began/changed` ⇒ 暂停，`ended/cancelled` ⇒ 恢复。
/// **不劫持 SwiftUI ScrollView 自己的 delegate**（iOS 16 上劫持会弄坏
/// `.scrollDismissesKeyboard` 等内建行为）。
///
/// ⚠️ 放置位置：必须作为**滚动容器内部**的视图（与 `DrawerPanGestureAttachment` 同层，
/// 例如 `.background { … }` 里）——放在 ScrollView 之外找不到祖先滚动视图。
struct ScrollPausesBackgroundAnimationProbe: UIViewRepresentable {
    final class ProbeView: UIView {
        var onHierarchyChange: (() -> Void)?
        override func didMoveToSuperview() { super.didMoveToSuperview(); onHierarchyChange?() }
        override func didMoveToWindow() { super.didMoveToWindow(); onHierarchyChange?() }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var scrollView: UIScrollView?
        private var pan: UIPanGestureRecognizer?

        func attach(from view: UIView) {
            var ancestor = view.superview
            while let current = ancestor, !(current is UIScrollView) {
                ancestor = current.superview
            }
            guard let scrollView = ancestor as? UIScrollView, self.scrollView !== scrollView else { return }
            detach()
            let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
            pan.delegate = self
            pan.cancelsTouchesInView = false
            scrollView.addGestureRecognizer(pan)
            self.scrollView = scrollView
            self.pan = pan
        }

        func detach() {
            if let pan { scrollView?.removeGestureRecognizer(pan) }
            pan = nil
            scrollView = nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
            switch pan.state {
            case .began, .changed:
                HarnessBackgroundAnimationClock.shared.setPaused(true)
            case .ended, .cancelled, .failed:
                HarnessBackgroundAnimationClock.shared.setPaused(false)
            default:
                break
            }
        }
    }

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView(frame: .zero)
        view.isUserInteractionEnabled = false   // 探针自身不参与命中测试
        let coordinator = context.coordinator
        // 与 DrawerPanGestureAttachment 同款：SwiftUI 层级未必当场就绪，异步一拍再找祖先。
        view.onHierarchyChange = { [weak view, weak coordinator] in
            DispatchQueue.main.async { [weak view, weak coordinator] in
                guard let view, let coordinator else { return }
                coordinator.attach(from: view)
            }
        }
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        context.coordinator.attach(from: view)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }
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

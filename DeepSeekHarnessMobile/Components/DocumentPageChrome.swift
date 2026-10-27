import SwiftUI

/// 表格预览和文字选择页共用的颜色、页头尺寸与关闭按钮。
enum DocumentPageAppearance {
    static let background = Color(uiColor: .systemBackground)
    static let foreground = Color(uiColor: .label)
    static let closeBackground = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark
        ? UIColor(red: 37/255, green: 37/255, blue: 40/255, alpha: 1)
        : UIColor(red: 245/255, green: 245/255, blue: 247/255, alpha: 1) })
    static let headerFill = adaptive(0xEFEFF0, 0x252527)
    static let alternateFill = adaptive(0xF8F8FA, 0x111113)
    static let border = adaptive(0xDADADD, 0x353538)
    static let toolbar = adaptive(0xF5F5F7, 0x1C1C1E)
    static let icon = adaptive(0x65656B, 0xAAAAB0)

    private static func adaptive(_ light: Int, _ dark: Int) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

struct DocumentPageHeader<Actions: View>: View {
    let title: LocalizedStringKey
    let closeIdentifier: String
    let onClose: () -> Void
    @ViewBuilder var actions: Actions

    var body: some View {
        ZStack {
            Text(title)
                .appFont(.system(size: 17, weight: .semibold))
                .lineLimit(1)
                .padding(.horizontal, 90)
                .accessibilityIdentifier("document-page-title")
            HStack(spacing: 0) {
                Button("关闭", action: onClose)
                    .buttonStyle(.plain)
                    .appFont(.system(size: 17))
                    .frame(width: 64, height: 44)
                    .background { Capsule().fill(DocumentPageAppearance.closeBackground) }
                    .accessibilityIdentifier(closeIdentifier)
                Spacer(minLength: 0)
                actions
            }
        }
        .foregroundStyle(DocumentPageAppearance.foreground)
        .padding(.horizontal, 20)
        .frame(height: 60)
        .background { Rectangle().fill(DocumentPageAppearance.background) }
    }
}

/// 只有表格预览期间放开手机横屏；离开后恢复聊天的竖屏策略。
@MainActor
enum DocumentRotationPolicy {
    private static var owners = Set<UUID>()
    static var tableIsOpen: Bool { !owners.isEmpty }
    static var orientations: UIInterfaceOrientationMask {
        if UIDevice.current.userInterfaceIdiom == .pad { return .all }
        return tableIsOpen ? .allButUpsideDown : .portrait
    }
    static func begin(_ owner: UUID, scene: UIWindowScene?) {
        owners.insert(owner)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        refresh(scene, restore: false)
    }
    static func end(_ owner: UUID, scene: UIWindowScene?) {
        owners.remove(owner)
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        refresh(scene, restore: !tableIsOpen && UIDevice.current.userInterfaceIdiom != .pad)
    }
    private static func refresh(_ scene: UIWindowScene?, restore: Bool) {
        scene?.windows.forEach { window in
            window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            window.rootViewController?.presentedViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        if restore { scene?.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) }
    }
}

struct DocumentRotationSupport: UIViewControllerRepresentable {
    var rotationRequest = 0

    final class Controller: UIViewController {
        let owner = UUID()
        var active = false
        var lastRotationRequest = 0
        weak var scene: UIWindowScene?
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !active else { return }
            active = true
            scene = view.window?.windowScene
            DocumentRotationPolicy.begin(owner, scene: scene)
        }

        func rotate() {
            guard let scene else { return }
            let orientation: UIInterfaceOrientationMask = scene.interfaceOrientation.isLandscape ? .portrait : .landscapeRight
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientation))
        }
    }
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {
        guard controller.lastRotationRequest != rotationRequest else { return }
        controller.lastRotationRequest = rotationRequest
        controller.rotate()
    }
    static func dismantleUIViewController(_ controller: Controller, coordinator: ()) {
        if controller.active { DocumentRotationPolicy.end(controller.owner, scene: controller.scene) }
    }
}

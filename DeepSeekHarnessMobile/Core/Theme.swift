import SwiftUI
import UIKit

enum AppFontScale {
    static let key = "appearance.fontScale"
    static let followsSystemKey = "appearance.fontFollowsSystem"
    static func effective(_ value: Double, followsSystem: Bool) -> Double {
        followsSystem ? 1 : normalized(value)
    }
    static func normalized(_ value: Double) -> Double {
        value.isFinite ? min(1.5, max(0.8, value)) : 1
    }
    static var current: CGFloat {
        let value = UserDefaults.standard.object(forKey: key) as? Double ?? 1
        return CGFloat(effective(value, followsSystem: UserDefaults.standard.bool(forKey: followsSystemKey)))
    }
}

// 统一描述字体，避免固定字号、语义字体和独立 UIKit 宿主使用不同的缩放规则。
struct AppFont {
    private var style: UIFont.TextStyle = .body
    private var size: CGFloat?
    private var fontWeight: Font.Weight?
    private var design: Font.Design = .default
    private var usesMonospacedDigits = false

    static let largeTitle = system(.largeTitle)
    static let title = system(.title)
    static let title2 = system(.title2)
    static let title3 = system(.title3)
    static let headline = system(.headline)
    static let subheadline = system(.subheadline)
    static let body = system(.body)
    static let callout = system(.callout)
    static let footnote = system(.footnote)
    static let caption = system(.caption)
    static let caption2 = system(.caption2)

    static func system(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> AppFont {
        AppFont(size: size, fontWeight: weight, design: design)
    }

    static func system(_ style: Font.TextStyle, design: Font.Design = .default) -> AppFont {
        let uiStyle: UIFont.TextStyle
        switch style {
        case .largeTitle: uiStyle = .largeTitle
        case .title: uiStyle = .title1
        case .title2: uiStyle = .title2
        case .title3: uiStyle = .title3
        case .headline: uiStyle = .headline
        case .subheadline: uiStyle = .subheadline
        case .callout: uiStyle = .callout
        case .footnote: uiStyle = .footnote
        case .caption: uiStyle = .caption1
        case .caption2: uiStyle = .caption2
        default: uiStyle = .body
        }
        return AppFont(style: uiStyle, design: design)
    }

    func weight(_ value: Font.Weight) -> AppFont {
        var copy = self; copy.fontWeight = value; return copy
    }
    func bold() -> AppFont { weight(.bold) }
    func monospaced() -> AppFont {
        var copy = self; copy.design = .monospaced; return copy
    }
    func monospacedDigit() -> AppFont {
        var copy = self; copy.usesMonospacedDigits = true; return copy
    }
    func resolved(scale: CGFloat) -> Font {
        let base = size.map { UIFontMetrics.default.scaledValue(for: $0) }
            ?? UIFont.preferredFont(forTextStyle: style).pointSize
        var font = Font.system(size: base * scale, weight: fontWeight ?? (style == .headline ? .semibold : .regular), design: design)
        if usesMonospacedDigits { font = font.monospacedDigit() }
        return font
    }
}

private struct AppFontModifier: ViewModifier {
    @AppStorage(AppFontScale.key) private var scale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystem = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let value: AppFont
    func body(content: Content) -> some View {
        content.font(value.resolved(scale: CGFloat(AppFontScale.effective(scale, followsSystem: followsSystem))))
    }
}

struct AppFontScope: ViewModifier {
    @AppStorage(AppFontScale.key) private var scale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystem = false
    @Environment(\.dynamicTypeSize) private var systemSize

    func body(content: Content) -> some View {
        // 系统控件也需要显式更新 Dynamic Type；应用自己的字体保留精确倍率。
        let sizes = DynamicTypeSize.allCases
        let target = UIFont.preferredFont(forTextStyle: .body).pointSize * AppFontScale.effective(scale, followsSystem: followsSystem)
        let closest = sizes.min { lhs, rhs in
            abs(bodySize(lhs) - target) < abs(bodySize(rhs) - target)
        } ?? systemSize
        content
            .font(AppFont.body.resolved(scale: CGFloat(AppFontScale.effective(scale, followsSystem: followsSystem))))
            .dynamicTypeSize(closest)
    }

    private func bodySize(_ size: DynamicTypeSize) -> CGFloat {
        let categories: [UIContentSizeCategory] = [.extraSmall, .small, .medium, .large, .extraLarge,
            .extraExtraLarge, .extraExtraExtraLarge, .accessibilityMedium, .accessibilityLarge,
            .accessibilityExtraLarge, .accessibilityExtraExtraLarge, .accessibilityExtraExtraExtraLarge]
        let index = DynamicTypeSize.allCases.firstIndex(of: size) ?? 3
        return UIFont.preferredFont(forTextStyle: .body,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: categories[index])).pointSize
    }
}

extension View {
    func appFont(_ value: AppFont) -> some View { modifier(AppFontModifier(value: value)) }
}

extension UIFont {
    static func appPreferredFont(forTextStyle style: UIFont.TextStyle) -> UIFont {
        let font = preferredFont(forTextStyle: style)
        return font.withSize(font.pointSize * AppFontScale.current)
    }
    static func appMonospacedFont(ofSize size: CGFloat, weight: UIFont.Weight) -> UIFont {
        monospacedSystemFont(ofSize: UIFontMetrics.default.scaledValue(for: size) * AppFontScale.current, weight: weight)
    }
}

enum DSHColor {
    static let navy = Color(red: 0.025, green: 0.09, blue: 0.17)
    static let navyRaised = Color(red: 0.05, green: 0.14, blue: 0.25)
    static let ocean = Color(red: 0.18, green: 0.42, blue: 0.9)
    static let mist = Color(red: 0.75, green: 0.84, blue: 1)
    static let ink = Color(red: 0.055, green: 0.075, blue: 0.1)
    static let paper = Color(red: 0.975, green: 0.98, blue: 0.99)
    static let purple = Color(red: 0.48, green: 0.33, blue: 0.78)
    static let orange = Color(red: 0.94, green: 0.49, blue: 0.08)
    static let amber = Color(red: 1.0, green: 0.68, blue: 0.12)
    static let success = Color(red: 0.18, green: 0.72, blue: 0.36)
}

/// 主题感知色板：主页所有前景/容器色都从它取，禁止再写死 `.white.opacity(…)`。
/// 深色沿用原来的白色叠加；浅色映射成墨色/白卡片，层次比例保持一致。
/// iOS 16 可用（只用 `@Environment(\.colorScheme)`，无 17+ API）。
struct DSHPalette {
    let scheme: ColorScheme

    var isLight: Bool { scheme == .light }

    /// 前景（文字/图标）：opacity 越大越强调。
    /// 浅色按「对比度优先」重算：0.92 的正文≈近全黑，0.55 的次要文字在纯白上仍有约 4.5:1，
    /// 不会再出现「浅底白字看不见」；深色沿用原来的白色叠加，观感不变。
    func ink(_ opacity: Double) -> Color {
        isLight
            ? DSHColor.ink.opacity(min(1, 0.55 + opacity * 0.45))
            : Color.white.opacity(opacity)
    }

    /// 卡片/输入框等容器填充。浅色不用低透明白（在白底上等于没有），改用高不透明白衬底，
    /// 层次交给 `stroke` 的墨色描边表达。
    func surface(_ opacity: Double) -> Color {
        isLight
            ? Color.white.opacity(min(1, 0.72 + opacity * 1.6))
            : Color.white.opacity(opacity)
    }

    /// 描边/分隔线。浅色需要真正可见的细线（≥0.06），否则卡片边界在浅底上消失。
    func stroke(_ opacity: Double) -> Color {
        isLight
            ? DSHColor.ink.opacity(min(0.3, 0.06 + opacity * 0.6))
            : Color.white.opacity(opacity)
    }

    // 状态色（文字/图标用）。深色沿用品牌色；浅色必须压暗，否则在浅底上达不到 4.5:1：
    // 绿 2.59:1 / 琥珀 1.86:1 / 橙 2.75:1（原值），压暗后 6.6 / 6.3 / 4.9。
    var success: Color { isLight ? Color(red: 0.07, green: 0.42, blue: 0.18) : DSHColor.success }
    var warning: Color { isLight ? Color(red: 0.55, green: 0.32, blue: 0.0) : DSHColor.amber }
    var caution: Color { isLight ? Color(red: 0.70, green: 0.34, blue: 0.0) : DSHColor.orange }
    var danger: Color { isLight ? Color(red: 0.72, green: 0.12, blue: 0.10) : Color.red.opacity(0.9) }

    static func resolve(_ scheme: ColorScheme) -> DSHPalette { DSHPalette(scheme: scheme) }
}

/// B7：浅色**不再是静态占位**。鲸鱼（吉祥物）在浅色下同样逐帧动，只换配色与帧率；
/// 具体形态由 `HarnessAnimatedBackground` 按 `colorScheme` 分支处理。
/// B4：被子页覆盖时（`BackgroundAnimationPause`）自动暂停逐帧重绘，避免推入卡死。
struct DeepOceanBackground: View {
    var body: some View {
        HarnessAnimatedBackground(style: UIDevice.current.userInterfaceIdiom == .pad ? .pad : .phone)
    }
}

/// 根据当前容器伸缩；不读取设备型号或 UIScreen，分屏和台前调度同样生效。
enum AdaptiveLayout {
    static let pageWidth: CGFloat = 1_040
    static let workspaceWidth: CGFloat = 1_440

    static func workspaceUsesColumns(size: CGSize, accessibilityText: Bool = false) -> Bool {
        size.width >= 900 && size.height >= 500 && !accessibilityText
    }

    static func horizontalInset(width: CGFloat, maximumWidth: CGFloat) -> CGFloat {
        max(0, (width - maximumWidth) / 2)
    }
}

extension View {
    /// 背景和滚动区域铺满窗口，正文保持合适的阅读宽度。
    func readableContentWidth(_ maximumWidth: CGFloat = AdaptiveLayout.pageWidth) -> some View {
        frame(maxWidth: maximumWidth)
            .frame(maxWidth: .infinity)
    }

    func adaptiveListWidth(_ maximumWidth: CGFloat = AdaptiveLayout.pageWidth) -> some View {
        modifier(AdaptiveListWidth(maximumWidth: maximumWidth))
    }
}

private struct AdaptiveListWidth: ViewModifier {
    let maximumWidth: CGFloat

    @ViewBuilder
    func body(content: Content) -> some View {
        GeometryReader { geometry in
            let inset = AdaptiveLayout.horizontalInset(width: geometry.size.width, maximumWidth: maximumWidth)
            // contentMargins(_:_:for:) 自 iOS 17 起可用；更早系统改用等价的
            // 水平内边距，列表仍保持同样的阅读宽度。
            if #available(iOS 17.0, *) {
                content.contentMargins(.horizontal, inset, for: .scrollContent)
            } else {
                content.padding(.horizontal, inset)
            }
        }
    }
}

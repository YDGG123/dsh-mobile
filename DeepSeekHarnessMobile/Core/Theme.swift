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

    // MARK: 主题自适应状态色（渲染层没有 colorScheme 时用这一组）
    // UIKit 动态色按 traitCollection 解析，SwiftUI 的 preferredColorScheme 会改到 window 的
    // overrideUserInterfaceStyle，所以这套色在任何视图里都能自动跟随主题，不必逐层加
    // `@Environment(\.colorScheme)`。深色档 = 原品牌色（观感不变），浅色档 = 压暗版（达标 AA/≥3:1）。
    private static func dynamic(dark: (CGFloat, CGFloat, CGFloat), light: (CGFloat, CGFloat, CGFloat)) -> Color {
        Color(uiColor: UIColor { traits in
            let (r, g, b) = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: r, green: g, blue: b, alpha: 1)
        })
    }

    static let successAA = dynamic(dark: (0.18, 0.72, 0.36), light: (0.07, 0.42, 0.18))
    static let warningAA = dynamic(dark: (1.0, 0.68, 0.12), light: (0.55, 0.32, 0.0))
    static let cautionAA = dynamic(dark: (0.94, 0.49, 0.08), light: (0.62, 0.30, 0.0))
    /// danger 深色档必须与原来的 `Color.red.opacity(0.9)` 等价（带 0.9 透明度的纯红），
    /// 所以这里单独构造带 alpha 的动态色，而不是套用 dynamic(...) 的不透明默认。
    static let dangerAA = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 1.0, green: 0.0, blue: 0.0, alpha: 0.9)
            : UIColor(red: 0.72, green: 0.12, blue: 0.10, alpha: 1)
    })
    static let success = Color(red: 0.18, green: 0.72, blue: 0.36)
}

/// 主题感知色板：主页所有前景/容器色都从它取，禁止再写死 `.white.opacity(…)`。
/// 深色沿用原来的白色叠加；浅色映射成墨色/白卡片，层次比例保持一致。
/// iOS 16 可用（只用 `@Environment(\.colorScheme)`，无 17+ API）。
struct DSHPalette {
    let scheme: ColorScheme

    var isLight: Bool { scheme == .light }

    /// 前景（文字/图标）：opacity 越大越强调。
    /// 浅色下按 `0.35 + o*0.65` 重算：
    ///   - 起点 0.55 → 0.35：让次要文字与元信息重新拉开层次
    ///     （原公式把所有档位挤在 0.68–0.96，用户实测「浅色透明度有问题」）
    ///   - 斜率 0.45 → 0.65：正文（0.92 档）几乎不变（15.44→14.82 对比度）
    ///   - 最淡档仍有 3.88:1，满足 WCAG 大字标准；0.40 往上全部过 AA
    /// 深色沿用原来的白色叠加，观感不变。
    func ink(_ opacity: Double) -> Color {
        isLight
            ? DSHColor.ink.opacity(min(1, 0.35 + opacity * 0.65))
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

    /// **填充/描边专用档**。不要拿 `ink()` 当底色：浅色档 `ink` 有 0.55 的透明度地板
    /// （那是为文字对比度设的），`ink(0.07)` 当填充会变成 58% 的近黑——用户看到的
    /// 「分段控件灰底比周围白卡片重」就是这个。veil 在浅色下最多 0.16，深色沿用白色叠加。
    func veil(_ opacity: Double) -> Color {
        isLight ? DSHColor.ink.opacity(min(0.16, opacity * 0.9)) : Color.white.opacity(opacity)
    }

    // 状态色（文字/图标用）。深色沿用品牌色；浅色必须压暗，否则在浅底上达不到 4.5:1：
    // 绿 2.59:1 / 琥珀 1.86:1 / 橙 2.75:1（原值），压暗后 6.6 / 6.3 / 4.9。
    // 单一来源：直接复用 DSHColor 的动态色（深色档与品牌色逐位一致、浅色档压暗），
    // 避免「palette.* 与 DSHColor.*AA 两套并存」以后分叉。
    var success: Color { DSHColor.successAA }
    var warning: Color { DSHColor.warningAA }
    // 橙单独再压一档：浅海渐变最深处（#E0F0FF）上 0.70/0.34/0 只有 4.25:1，不到 AA；
    // 0.62/0.30/0 在 #FFFFFF / #F5FAFF / #E0F0FF 上分别是 5.99 / 5.71 / 5.16。
    var caution: Color { DSHColor.cautionAA }
    var danger: Color { DSHColor.dangerAA }

    static func resolve(_ scheme: ColorScheme) -> DSHPalette { DSHPalette(scheme: scheme) }
}

/// B7：浅色**不再是静态占位**。鲸鱼（吉祥物）在浅色下同样逐帧动，只换配色与帧率；
/// 具体形态由 `HarnessAnimatedBackground` 按 `colorScheme` 分支处理。
/// B4：被子页覆盖时（`BackgroundAnimationPause`）自动暂停逐帧重绘，避免推入卡死。
struct DeepOceanBackground: View {
    /// iOS 27.1+ 竖向工具栏：顶部光弧让位给工具栏（上游新增，默认关闭）。
    var prefersOpenLightArcs = false

    var body: some View {
        GeometryReader { geometry in
            HarnessAnimatedBackground(
                style: HarnessBackgroundStyle.resolve(
                    size: geometry.size,
                    isPad: UIDevice.current.userInterfaceIdiom == .pad
                ),
                prefersOpenLightArcs: prefersOpenLightArcs
            )
        }
        .ignoresSafeArea()
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

/// `ContentUnavailableView` 的 iOS 16.1 替代实现，覆盖项目内用到的三种构造形式。
struct DshEmptyState: View {
    private let label: AnyView
    private let message: AnyView?
    private let actions: AnyView?

    init<L: View, D: View, A: View>(
        @ViewBuilder label: () -> L,
        @ViewBuilder description: () -> D,
        @ViewBuilder actions: () -> A
    ) {
        self.label = AnyView(label())
        self.message = AnyView(description())
        self.actions = AnyView(actions())
    }

    init<L: View, D: View>(
        @ViewBuilder label: () -> L,
        @ViewBuilder description: () -> D
    ) {
        self.label = AnyView(label())
        self.message = AnyView(description())
        self.actions = nil
    }

    init(_ title: String, systemImage: String, description: Text? = nil) {
        self.label = AnyView(
            VStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.system(size: 40, weight: .light))
                Text(title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
        )
        self.message = description.map { AnyView($0.font(.subheadline).multilineTextAlignment(.center)) }
        self.actions = nil
    }

    var body: some View {
        VStack(spacing: 12) {
            label
            if let message {
                message
            }
            if let actions {
                actions
                    .padding(.top, 4)
            }
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}

// MARK: ===== 设计系统 v1（来源：Design/handoff/DesignSystem.swift，唯一数值来源）=====
// 2026-10-28 第一刀：只并入 token 与组件；逐屏替换在后续提交。
// 与既有成员的关系：只增不改（DSHColor/AppFont/DSHPalette 原成员一律未动）。
// 主题三选：未照搬 DSHThemeMode/.dshTheme —— 工程已有 InterfaceStyle（system/light/dark，
// 持久化 + 根视图 preferredColorScheme），设置页直接读写 store.interfaceStyle。详见 /tmp/dropped-dshtheme.swift 备份。


// MARK: - 1. 尺寸档
//
// 只有这些值。新增之前先问：能不能用现有的？
// 历史上这个工程出现过 17 种圆角值（1、3、6、9…48），那是「看起来不精致」的根因。

enum DSHRadius {
    /// 小方块：徽标、状态胶囊的内层
    static let xs: CGFloat = 8
    /// 行内小元件：工具行、代码块内嵌
    static let sm: CGFloat = 12
    /// 分组卡片、工作区卡
    static let md: CGFloat = 16
    /// 大卡片：运行卡、摘要卡、空态图标底
    static let lg: CGFloat = 20
    /// 底部弹层、审批卡
    static let xl: CGFloat = 28
    /// 胶囊（开关、Tab、按钮）
    static let pill: CGFloat = 999
}

enum DSHSpacing {
    static let xxs: CGFloat = 2
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 28
}

// MARK: - 2. 字号档
//
// 只有五档 + 两个等宽档。全部走 AppFont，自动吃 Dynamic Type 和用户字号倍率。
// 不要直接写 .font(.system(size:))，那会绕过字号设置。

enum DSHType {
    /// 11pt · 元信息：时间、计数、状态字、标签
    static var meta: AppFont { .system(size: 11, weight: .medium) }
    /// 13pt · 次要文字：说明、副标题、行内辅助
    static var footnote: AppFont { .system(size: 13, weight: .regular) }
    /// 15pt · 正文与行标题
    static var body: AppFont { .system(size: 15, weight: .regular) }
    /// 18pt · 区块标题、卡片主数字
    static var title: AppFont { .system(size: 18, weight: .semibold) }
    /// 28pt · 摘要页的大数字
    static var display: AppFont { .system(size: 28, weight: .semibold) }

    /// 12pt 等宽：地址、路径、cron、代码
    static var mono: AppFont { .system(size: 12, weight: .regular, design: .monospaced) }
    /// 17pt 等宽：指标数字
    static var monoValue: AppFont { .system(size: 17, weight: .semibold, design: .monospaced) }
}

// MARK: - 3. 面语义（接进现有 DSHPalette 的三档）
//
// 现有三档的语义是「叠多少」：ink / surface / stroke / veil。
// 这里把它们组合成有名字的「面」，视图只引用名字，不引用数值。

extension DSHPalette {
    /// 页面底色。深色沿用品牌海军蓝，浅色用带一点蓝的灰白（纯白会让白卡片失去层次）。
    var pageBackground: Color {
        isLight ? Color(red: 0.957, green: 0.965, blue: 0.976) : DSHColor.navy
    }

    /// 阅读区底色（会话正文、文件预览）。深色比页面底色略亮一档，形成「纸」的层次。
    var contentBackground: Color {
        isLight ? .white : Color(red: 0.063, green: 0.106, blue: 0.169)
    }

    /// 卡片填充：分组卡片、工作区卡、任务卡
    var cardFill: Color {
        isLight ? .white : surface(0.06)
    }

    /// 卡片描边：1pt，浅色下必须可见，否则白卡在白底上消失
    var cardStroke: Color {
        isLight ? stroke(0.06) : stroke(0.12)
    }

    /// 卡片内部分隔线（分组行的 line）
    var rowDivider: Color {
        isLight ? stroke(0.05) : stroke(0.07)
    }

    /// 小控件底：chip、圆形图标按钮、搜索框
    var chipFill: Color {
        isLight ? veil(0.12) : veil(0.08)
    }

    /// 代码块底色：两种主题下都是深色（保证语法色一致）
    var codeBackground: Color {
        Color(red: 0.024, green: 0.055, blue: 0.09)
    }

    /// 品牌主色（按钮、选中态）。深色下用亮一档，保证在深底上的可点感。
    var brand: Color {
        isLight ? DSHColor.ocean : Color(red: 0.30, green: 0.55, blue: 1.0)
    }
}

// MARK: - 4. 主题三选
//
// 现在的工程只跟随系统。设计稿需要「自动 / 浅色 / 深色」三选，
// 因为有人在深色系统下也想要浅色会话页（长时间读代码）。
//
// MARK: - 5. 分组卡片
//
// 设置页、关于页、扩展页的标准容器：标题在卡片外，行在卡片内，
// 分隔线左缩进 14（对齐文字起点，不是贯穿整卡）。

struct DSHGroup<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    private let title: String?
    private let content: Content

    init(_ title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                Text(title)
                    .appFont(DSHType.meta)
                    .foregroundStyle(p.ink(0.45))
                    .padding(.leading, DSHSpacing.xs)
                    .padding(.bottom, DSHSpacing.sm)
            }
            VStack(spacing: 0) { content }
                .background(p.cardFill)
                .clipShape(RoundedRectangle(cornerRadius: DSHRadius.md, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DSHRadius.md, style: .continuous)
                        .strokeBorder(p.cardStroke, lineWidth: 1)
                )
        }
    }
}

// MARK: - 6. 设置行
//
// 分组卡片里的一行：标题（可带副标题）+ 右侧内容（值 / 开关 / 箭头 / 自定义视图）。
//
// 规则：
// - 有 action 才可点，可点必须有右侧视觉暗示（箭头或值），不能只有文字变化
// - showsDivider 默认 true，但组内最后一行必须传 false（否则卡片底部多一条线）

struct DSHRow<Trailing: View>: View {
    @Environment(\.colorScheme) private var scheme
    private let title: String
    private let subtitle: String?
    private let showsDivider: Bool
    private let action: (() -> Void)?
    private let trailing: Trailing

    init(_ title: String,
         subtitle: String? = nil,
         showsDivider: Bool = true,
         action: (() -> Void)? = nil,
         @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.showsDivider = showsDivider
        self.action = action
        self.trailing = trailing()
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        let row = HStack(spacing: DSHSpacing.md) {
            VStack(alignment: .leading, spacing: DSHSpacing.xxs) {
                Text(title)
                    .appFont(DSHType.body)
                    .foregroundStyle(p.ink(0.92))
                if let subtitle {
                    Text(subtitle)
                        .appFont(DSHType.footnote)
                        .foregroundStyle(p.ink(0.5))
                }
            }
            Spacer(minLength: DSHSpacing.sm)
            trailing
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 50)
        .contentShape(Rectangle())
        .overlay(alignment: .bottom) {
            if showsDivider {
                Rectangle()
                    .fill(p.rowDivider)
                    .frame(height: 1)
                    .padding(.leading, 14)
            }
        }

        if let action {
            Button(action: action) { row }
                .buttonStyle(.plain)
        } else {
            row
        }
    }
}

extension DSHRow where Trailing == EmptyView {
    init(_ title: String,
         subtitle: String? = nil,
         showsDivider: Bool = true,
         action: (() -> Void)? = nil) {
        self.init(title, subtitle: subtitle, showsDivider: showsDivider, action: action) {
            EmptyView()
        }
    }
}

/// 行右侧的「值 + 箭头」，最常用的一种 trailing
struct DSHRowValue: View {
    @Environment(\.colorScheme) private var scheme
    let text: String
    var showsChevron: Bool = true

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        HStack(spacing: DSHSpacing.xs) {
            Text(text)
                .appFont(DSHType.footnote)
                .foregroundStyle(p.ink(0.5))
                .lineLimit(1)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(p.ink(0.28))
            }
        }
    }
}

// MARK: - 7. 状态
//
// 语义固定，不可互换：
//   橙 = 等你动手（审批 / 回答）  → 实心胶囊 + 呼吸
//   蓝 = 进行中（运行 / 在线）    → 圆点 + 文字
//   绿 = 正常（已连接 / 在线）    → 圆点 + 文字
//   红 = 中断 / 失败              → 圆点 + 文字
//   灰 = 空闲 / 已完成            → 圆点 + 文字
//
// 判断依据只有一条：需不需要用户现在动手。需要的用胶囊，不需要的用圆点。

enum DSHStatusKind {
    case waiting     // 等你审批
    case question    // 等你回答
    case running     // 运行中
    case online      // 已连接 / 在线
    case failed      // 已中断
    case idle        // 空闲 / 已完成
}

struct DSHStatus: View {
    @Environment(\.colorScheme) private var scheme
    let kind: DSHStatusKind
    let text: String
    /// 只有需要用户动手的状态才允许开呼吸动画
    var pulsing: Bool = false

    private var isPill: Bool { kind == .waiting || kind == .question }

    private func color(_ p: DSHPalette) -> Color {
        switch kind {
        case .waiting:  return p.caution
        case .question: return p.brand
        case .running:  return p.brand
        case .online:   return p.success
        case .failed:   return p.danger
        case .idle:     return p.ink(0.45)
        }
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        if isPill {
            HStack(spacing: 5) {
                dot(color(p), pulsing: pulsing)
                Text(text)
                    .appFont(DSHType.meta.weight(.semibold))
                    .foregroundStyle(color(p))
                    .lineLimit(1)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color(p).opacity(0.16))
            .clipShape(RoundedRectangle(cornerRadius: DSHRadius.xs, style: .continuous))
        } else {
            HStack(spacing: 5) {
                dot(color(p), pulsing: false)
                Text(text)
                    .appFont(DSHType.meta.weight(.semibold))
                    .foregroundStyle(color(p))
                    .lineLimit(1)
            }
        }
    }

    @ViewBuilder
    private func dot(_ c: Color, pulsing: Bool) -> some View {
        if pulsing {
            Circle()
                .fill(c)
                .frame(width: 7, height: 7)
                .opacity(0.95)
                .modifier(BreathingOpacity())
        } else {
            Circle()
                .fill(c)
                .frame(width: 7, height: 7)
        }
    }
}

/// 只能用在「等你动手」的提示上。其他地方的呼吸会变成噪音。
private struct BreathingOpacity: ViewModifier {
    @State private var dim = false
    func body(content: Content) -> some View {
        content
            .opacity(dim ? 0.35 : 1)
            .animation(.easeInOut(duration: 1.9).repeatForever(autoreverses: true), value: dim)
            .onAppear { dim = true }
    }
}

// MARK: - 8. 空态（iOS 16 没有 ContentUnavailableView）
//
// 每个列表都要有空态。文案要具体，不要写「暂无数据」。

struct DSHEmptyState: View {
    @Environment(\.colorScheme) private var scheme
    let icon: String            // SF Symbol 名
    let title: String
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        VStack(spacing: DSHSpacing.md) {
            Image(systemName: icon)
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(p.ink(0.42))
                .frame(width: 56, height: 56)
                .background(p.chipFill)
                .clipShape(RoundedRectangle(cornerRadius: DSHRadius.lg, style: .continuous))

            Text(title)
                .appFont(DSHType.body.weight(.semibold))
                .foregroundStyle(p.ink(0.92))
                .multilineTextAlignment(.center)

            Text(message)
                .appFont(DSHType.footnote)
                .foregroundStyle(p.ink(0.55))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .appFont(DSHType.body.weight(.semibold))
                }
                .buttonStyle(DSHPrimaryButtonStyle())
                .padding(.top, DSHSpacing.xs)
            }
        }
        .padding(.horizontal, DSHSpacing.xl)
        .padding(.top, 52)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - 8b. 品牌空态（新会话页）
//
// 会话还没有任何消息时显示：黑鲸 + 标题 + 说明，在内容区**垂直居中**。
//
// 图标必须复用 `OrcarnessOrcaIcon`（Glass.swift）—— 它已经处理了「深色下黑鲸在深底上消失」
// 的问题（加一层浅灰圆底）。**不要用 SF Symbol 替代**，这只鲸是品牌资产。
//
// 浅色下没有圆底，白斑会和页面背景融合，鲸鱼看起来有点"缺" —— 这是现有实现的表现，
// 别自作主张改配色。

struct DSHBrandEmptyState: View {
    @Environment(\.colorScheme) private var scheme
    let title: String
    let message: String
    var iconSize: CGFloat = 52

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        VStack(spacing: 14) {
            OrcarnessOrcaIcon(size: iconSize)

            Text(title)
                .appFont(DSHType.title)
                .foregroundStyle(p.ink(0.92))
                .multilineTextAlignment(.center)

            Text(message)
                .appFont(DSHType.footnote)
                .foregroundStyle(p.ink(0.55))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 28)
    }
}

// MARK: - 9. 按钮
//
// 只有两种：主操作（填充）和次操作（描边）。不要出现第三种。

struct DSHPrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.white)
            .padding(.horizontal, DSHSpacing.xl)
            .frame(height: 38)
            .background(DSHColor.ocean)
            .clipShape(Capsule())
            .opacity(configuration.isPressed ? 0.86 : 1)
    }
}

struct DSHSecondaryButtonStyle: ButtonStyle {
    @Environment(\.colorScheme) private var scheme
    func makeBody(configuration: Configuration) -> some View {
        let p = DSHPalette(scheme: scheme)
        return configuration.label
            .appFont(DSHType.body.weight(.semibold))
            .foregroundStyle(p.ink(0.75))
            .padding(.horizontal, DSHSpacing.xl)
            .frame(height: 38)
            .background(p.cardFill)
            .overlay(Capsule().strokeBorder(p.cardStroke, lineWidth: 1))
            .clipShape(Capsule())
            .opacity(configuration.isPressed ? 0.86 : 1)
    }
}

// MARK: - 10. 页面骨架
//
// 所有二级页都长这样：底色 + 滚动容器 + 20 外边距 + 页面级 spacing。
// 直接照抄这段，不要每页自己写 padding。

struct DSHPage<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                content
            }
            .padding(.horizontal, DSHSpacing.xl)
            .padding(.top, DSHSpacing.md)
            .padding(.bottom, DSHSpacing.xxl)
        }
        .scrollIndicators(.hidden)
        .background(p.pageBackground)
    }
}

// MARK: - 10. 底部 tab bar（参考实现 ScreensShared · 按其要求上移进设计系统）
//
// 图标先用 SF Symbol 占位（参考实现同款）：工作区 = 方格、Bot = cpu、设置 = 滑杆。
// 与参考实现的差异：**选择用回调用而非 Binding**——本工程「设置」是 push 页
// （NavigationStack route），不是本容器内的内容切换，"设置选中"不存在持续状态；
// 选中态由调用方从自身状态推导。

/// 底部三 tab 的标识。rawValue 即界面文案（既有字符串：「Bot」「设置」沿用；
/// 「工作区」为设计稿 01 的 tab 名，属新增元素）。
enum DSHTab: String, CaseIterable {
    case workspace = "工作区"
    case bots = "Bot"
    case settings = "设置"

    var icon: String {
        switch self {
        case .workspace: return "square.grid.2x2"
        case .bots: return "cpu"
        case .settings: return "slider.horizontal.3"
        }
    }
}

struct DSHTabBar: View {
    @Environment(\.colorScheme) private var scheme
    let selection: DSHTab
    /// 角标规则（design-handoff.md §5）：按 tab 独立计数，当前 tab 不重复提示。
    /// 语义（待办数还是未读数）产品未定，先留空不显示——不编数字。
    var badges: [DSHTab: Int] = [:]
    var onTap: (DSHTab) -> Void

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        HStack(spacing: DSHSpacing.xs) {
            ForEach(DSHTab.allCases, id: \.self) { tab in
                let active = tab == selection
                Button { onTap(tab) } label: {
                    VStack(spacing: 3) {
                        Image(systemName: tab.icon)
                            .appFont(DSHType.body.weight(.medium))
                        Text(tab.rawValue).appFont(DSHType.meta)
                    }
                    .foregroundStyle(active ? Color.white : p.ink(0.55))
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(active ? AnyShapeStyle(p.brand) : AnyShapeStyle(.clear))
                    .clipShape(Capsule())
                    .overlay(alignment: .topTrailing) {
                        if let n = badges[tab], n > 0, !active {
                            Text("\(n)")
                                .appFont(DSHType.meta.weight(.semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1.5)
                                .background(DSHColor.cautionAA)
                                .clipShape(Capsule())
                                .offset(x: 6, y: -4)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DSHSpacing.xs)
        .background(p.cardFill)
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(p.cardStroke, lineWidth: 1))
        .padding(.horizontal, DSHSpacing.lg)
    }
}


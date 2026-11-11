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

/// 设置主页与子页共用的文字层级（上游 1.7.15 移植）；所有角色沿用应用字号与 Dynamic Type 缩放。
enum SettingsTypography {
    static let title = AppFont.body.weight(.semibold)
    static let item = AppFont.body
    static let description = AppFont.caption
    static let section = description
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

    /// 插件与设置开关共用的开启底色，分别适配浅色和深色外观。（上游 1.7.15 移植）
    static func switchTint(for scheme: ColorScheme) -> Color {
        scheme == .dark
            ? Color(red: 29.0 / 255, green: 68.0 / 255, blue: 127.0 / 255)
            : Color(red: 36.0 / 255, green: 90.0 / 255, blue: 196.0 / 255)
    }
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
    /// 2026-11-06（iOSTester §8.21.1 判红）：中间档两边都不过 AA 正文级——
    /// 浅色 ink(0.3)=3.98:1、深色 ink(0.45)=4.44:1。修法=**只托底不动高档**：
    /// 浅色地板 0.62、深色地板 0.55，0.42 以上档位数值逐位不变（层级顺序与既有观感不回归）。
    /// 实算（WCAG 相对亮度）：浅色最淡档 5.08:1 ✓（原 3.98）、深色最淡档 6.06:1 ✓（原 4.44）；
    /// 两端 ink(1.0) 浅 17.22 / 深 18.02 不变。
    func ink(_ opacity: Double) -> Color {
        isLight
            ? DSHColor.ink.opacity(min(1, max(0.62, 0.35 + opacity * 0.65)))
            : Color.white.opacity(min(1, max(0.55, opacity)))
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

    /// 卡片填充：分组卡片、工作区卡、任务卡。
    /// **浅色 0.55 白是用户真机反馈的定制值**：纯白会把背景鲸鱼粒子完全挡住
    /// （用户：深色下快透明、只有很淡灰底才对，浅色要跟上）。
    /// 注：`surface()` 浅色有 0.72 地板、`veil()` 是墨色压暗，都做不出「半透明白」，
    /// 所以这里直接写白色 + 不透明度（仍是白色，未引入新色）。
    var cardFill: Color {
        isLight ? Color.white.opacity(0.55) : surface(0.06)
    }

    /// 输入框外壳：**必须不透明**（半透明会把下方正文透出来，轻飘飘没质感）。
    /// 与 `cardFill` 分开的原因：`cardFill` 浅色 0.55 半透是给首页卡片「透鲸鱼」用的
    /// （用户已验收），不能共用 —— 共用会让输入框跟着变透明（2026-10-29 真机回归）。
    /// 深色这一支**不能跟 cardFill 学**：cardFill 深色是故意半透（white 6%）要透鲸鱼，
    /// 输入框跟了就会把正文透出来（用户第六轮反馈）。这里用不透明的 `navyRaised`，
    /// 比页面底色 `navy` 亮一档，看得出「浮在页面上」的层次，且没有任何 alpha。
    var composerFill: Color {
        isLight ? .white : DSHColor.navyRaised
    }

    /// 卡片描边：1pt。浅色卡片半透明后与背景只差 ~10/255，描边必须更实才认得出轮廓。
    /// `stroke(0.06)`（9.6%）→ 12% 实测「刚过线」，用户反馈「添加 Bot」卡在点阵背景上
    /// 几乎找不到边界 → 抬到 `stroke(0.17)`（≈16%），仍无盒子感。
    var cardStroke: Color {
        isLight ? stroke(0.17) : stroke(0.12)
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

// MARK: - 6.5 摘要卡（06/07 共用，对应参考实现 RefSummaryCard）
//
// 底色**跟随主题**（用户 59 包反馈；原为「两主题都深底」照参考实现，设计理由是
// 三色阶段条在浅色下可读性差）。改为跟随主题时整卡文字色从写死 .white 改为
// palette.ink()，浅色下的三色条/图例用各主题的既有语义色（brand 深浅自动压暗、
// purple/amber 本身是中饱和色，浅色 cardFill 白底上对比足够，截图已复核）。

struct DSHSummarySegment {
    let color: Color
    let label: String
    /// 占比 0...1（调用方按阶段时长归一）
    let share: CGFloat
}

struct DSHSummaryCard: View {
    let totalText: String
    let endText: String?
    let turns: String
    let toolCalls: String
    let steps: String
    let segments: [DSHSummarySegment]

    @Environment(\.colorScheme) private var scheme

    private var p: DSHPalette { DSHPalette(scheme: scheme) }

    var body: some View {
        VStack(alignment: .leading, spacing: DSHSpacing.md) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: DSHSpacing.xxs) {
                    Text("总耗时")
                        .appFont(DSHType.meta)
                        .foregroundStyle(p.ink(0.55))
                    Text(totalText)
                        .appFont(AppFont.system(size: 28, weight: .semibold, design: .monospaced))
                        .foregroundStyle(p.ink(0.92))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: DSHSpacing.sm)
                if let endText {
                    Text(endText)
                        .appFont(DSHType.meta)
                        .foregroundStyle(p.ink(0.55))
                }
            }

            HStack(spacing: 0) {
                summaryMetric(turns, "轮次")
                summaryMetric(toolCalls, "工具调用")
                summaryMetric(steps, "步骤")
            }

            GeometryReader { geo in
                HStack(spacing: 0) {
                    ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                        Rectangle()
                            .fill(segment.color)
                            .frame(width: geo.size.width * max(0, segment.share))
                    }
                }
            }
            .frame(height: 10)
            .clipShape(Capsule())

            HStack(spacing: DSHSpacing.md) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                    HStack(spacing: DSHSpacing.xxs) {
                        Circle().fill(segment.color).frame(width: 6, height: 6)
                        Text(segment.label)
                            .appFont(DSHType.meta)
                            .foregroundStyle(p.ink(0.6))
                            .lineLimit(1)
                    }
                }
            }
        }
        .padding(DSHSpacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            p.cardFill,
            in: RoundedRectangle(cornerRadius: DSHRadius.lg, style: .continuous)
        )
    }

    private func summaryMetric(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: DSHSpacing.xxs) {
            Text(value)
                .appFont(AppFont.system(size: 17, weight: .semibold, design: .monospaced))
                .foregroundStyle(p.ink(0.92))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .appFont(DSHType.meta)
                .foregroundStyle(p.ink(0.55))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 06/07 的时长/时刻格式化（两屏共用一套口径）
enum DSHSummaryFormat {
    /// "7:00.2" —— 分:秒.十分位
    static func duration(_ seconds: TimeInterval) -> String {
        let total = max(0, seconds)
        let minutes = Int(total / 60)
        let rest = total - Double(minutes * 60)
        return String(format: "%d:%04.1f", minutes, rest)
    }

    /// "2:41" —— 分:秒（不足 1 分钟显示 "12.4s"）
    static func coarse(_ seconds: TimeInterval) -> String {
        let total = max(0, seconds)
        if total < 60 { return String(format: "%.1fs", total) }
        let minutes = Int(total / 60)
        let rest = Int(total.rounded()) % 60
        return String(format: "%d:%02d", minutes, rest)
    }

    /// "15:21" —— 24 小时时刻
    static func clock(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
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

extension DSHStatusKind {
    /// 状态圆点颜色的**唯一定义**。首页会话行（`DSHSessionRow`）与 S28 侧栏会话行都调这里——
    /// 两处各写一份 switch 迟早分叉（用户已明确：运行中必须是绿，设计稿画蓝点以既有定义为准）。
    /// 口径：运行中 = 成功绿；等你审批 = 琥珀；等你回答 = 蓝；已中断 = 危险红；空闲 = 灰。
    func dotColor(in p: DSHPalette) -> Color {
        switch self {
        case .waiting: return p.caution
        case .question: return p.brand
        case .running, .online: return p.success
        case .failed: return p.danger
        case .idle: return p.ink(0.35)
        }
    }
}

struct DSHStatus: View {
    @Environment(\.colorScheme) private var scheme
    let kind: DSHStatusKind
    let text: String
    /// 只有需要用户动手的状态才允许开呼吸动画
    var pulsing: Bool = false

    private var isPill: Bool { kind == .waiting || kind == .question }

    /// 状态色：与 `DSHStatusKind.dotColor(in:)` 同一口径（运行中是**绿**，不是蓝）。
    /// 这里曾写 `p.brand`（蓝）——`.running` 目前只走圆点不走胶囊，所以没显蓝，
    /// 但两份定义迟早分叉，按用户口径统一成 success。
    private func color(_ p: DSHPalette) -> Color {
        switch kind {
        case .waiting:  return p.caution
        case .question: return p.brand
        case .running:  return p.success
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
    /// tab 栏总高：内部最小高 54 + 外层上下各 `DSHSpacing.xs` 内边距。
    /// 外层据此算「上移量 = 总高的一半」，改高度时不用再回来改魔数。
    static var totalHeight: CGFloat { 54 + DSHSpacing.xs * 2 }

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
                            // ① 角标挂在**图标**右上（不再挂整个按钮）：
                            // 原来挂按钮 topTrailing + offset(6,-4) 会越出按钮右边缘，
                            // 看起来像右边那个 tab 的角标（用户第五轮反馈）。
                            // ⑥ 角标与图标之间留固定空隙（用户拍板 7pt，不随角标宽度变）：
                            // 宽度交给 DSHTabBadge 自己量，见 DSHTabBadge.iconGap 的注释。
                            .overlay(alignment: .topTrailing) {
                                DSHTabBadge(count: badges[tab] ?? 0, shown: (badges[tab] ?? 0) > 0 && !active)
                            }
                        Text(tab.rawValue).appFont(DSHType.meta)
                    }
                    .foregroundStyle(active ? Color.white : p.ink(0.55))
                    .frame(maxWidth: .infinity, minHeight: 54)
                    // 未选中的 tab 没有背景色，命中区会塌成「图标 + 文字」两块，
                    // 两者之间的空隙（正好也是 accessibility frame 的中心）点不到，
                    // 触摸直接穿透到 tab bar 底下的列表行 —— 2026-11-08 宣传片探针
                    // 就是这样在「设置」上打开了下面那张会话卡的（点到了 demo-5 行）。
                    // contentShape 把整个 frame 变成命中区，选中/未选中一致。
                    .contentShape(Rectangle())
                    .background(active ? AnyShapeStyle(p.brand) : AnyShapeStyle(.clear))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DSHSpacing.xs)
        // 液态玻璃底（用户要求：深浅两档都要）。
        // · iOS 26：`glassEffect(.regular)` —— 真模糊/折射，且自带边缘高光；
        // · iOS 16–25：同一修饰符的材质回退（thinMaterial 磨砂 + 描边 + 轻阴影）。
        //   两代观感不同是**事实而非缺陷**：旧系统没有系统级液态玻璃。
        // 形状：radius = `totalHeight / 2` ⇔ `Capsule()`（胶囊半径就是高度的一半），
        // 与原 `.clipShape(Capsule())` 几何等价，不需要另造圆角参数。
        // 这里直接用 `glassSurface` 而不用 `themedGlassSurface`：后者浅色档是 #F2F2F7
        // **实色面**，会把 tab 栏底下的鲸鱼粒子整块挡住；tab bar 浮在动效背景上，
        // 浅色也要透出底下的模糊，所以两档都走 material 分支（`dark: false` 与既有 7 处调用点同口径）。
        .glassSurface(
            radius: Self.totalHeight / 2,
            dark: false,
            tint: nil,
            lightFill: nil,
            shadowOpacity: scheme == .dark ? 0.24 : 0.10
        )
        .overlay {
            // 边界归属：26 的 `glassEffect` 自带边缘处理，再叠一圈线会显脏；
            // 16–25 的材质面本身没有边界，必须靠这圈描边才立得住。
            if #unavailable(iOS 26.0) {
                Capsule().strokeBorder(p.cardStroke, lineWidth: 1)
            }
        }
        .padding(.horizontal, DSHSpacing.lg)
    }
}


// MARK: - 10b. tab 角标
//
// ⑥ 用户第六轮反馈：角标贴着图标，看着像图标的一部分 —— 要与图标留出明显空隙。
// 空隙的定稿是**固定 7pt**（不随角标宽度变）：最初按「空隙 = 一个角标宽」实现，
// 末位 tab 的两位数角标会顶进胶囊顶部圆角被裁掉一角（feedback4-badge-gap.jpg
// 里「12」缺角即该版实拍存档）；改固定 7pt 后末位 tab 仍有三十余 pt 富余。
// 宽度仍要实测 —— offset 依赖它，不能写死魔数。
// 角标常驻视图树（不可见时 opacity 0），这样宽度在第一帧就量好，出现时不会先贴在图标上跳一下。
private struct DSHTabBadge: View {
    /// 角标与图标的空隙。定稿依据：末位 tab（设置）图标右缘到胶囊边界在角标
    /// 所在高度约有 40pt 余量，固定 7pt 空隙连三位数角标都放得下。
    static let iconGap: CGFloat = 7

    let count: Int
    let shown: Bool
    @State private var width: CGFloat = 0

    var body: some View {
        Text("\(count)")
            .appFont(DSHType.meta.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 1.5)
            .background(DSHColor.cautionAA)
            .clipShape(Capsule())
            .background(
                GeometryReader { proxy in
                    Color.clear
                        .onAppear { width = proxy.size.width }
                        .onChangeCompat(of: proxy.size.width) { _, new in width = new }
                }
            )
            // overlay(.topTrailing) 先把角标右缘对齐图标右缘，再右移 w + iconGap：
            // 角标左缘 = 图标右缘 + iconGap → 空隙恒等于 iconGap，与位数无关。
            .offset(x: width + Self.iconGap, y: -6)
            .opacity(shown ? 1 : 0)
            .allowsHitTesting(false)
            .accessibilityHidden(!shown)
    }
}


// MARK: - 11. 二级页页头（参考实现 ScreensShared · 按其要求上移进设计系统）
//
// 所有 push 页共用：返回 + 标题 + 可选 trailing。返回键是 chipFill 圆底，
// 与 tab bar / 工作区卡的语义一致（可点 = 有明确形状）。

struct DSHPageHeader<Trailing: View>: View {
    @Environment(\.colorScheme) private var scheme
    let title: String
    var onBack: (() -> Void)?
    @ViewBuilder var trailing: () -> Trailing

    init(title: String, onBack: (() -> Void)? = nil,
         @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        HStack(spacing: DSHSpacing.md) {
            Button { onBack?() } label: {
                Image(systemName: "chevron.left")
                    .appFont(DSHType.body.weight(.semibold))
                    .foregroundStyle(p.ink(0.75))
                    .frame(width: 34, height: 34)
                    .background(p.chipFill, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "返回"))

            Text(title)
                .appFont(DSHType.title)
                .foregroundStyle(p.ink(0.92))
                .lineLimit(1)

            Spacer(minLength: DSHSpacing.sm)
            trailing()
        }
    }
}

// MARK: - 12. 会话行（参考实现 RefSessionRow · 上移进设计系统，01/02 共用同一套）
//
// 右列按状态分流是「要不要动手」规则的直接体现：
//   等你审批/等你回答 → 胶囊 + 呼吸 + 箭头（要你动手）
//   运行中 → 蓝圆点（不需要你管）
//   其余 → 相对时间
// `showsMenu`：02 全量页显示「…」，01 首页不显示（参考实现明确区分）。
// 长按菜单由调用方挂 contextMenu，两种入口不冲突。

struct DSHSessionRow: View {
    @Environment(\.colorScheme) private var scheme
    let title: String
    /// 「模式 · N 轮 · M 步」；缺数据时传 nil，整行不显示（不编 0）
    let secondary: String?
    /// 右侧状态位：nil = 无待办；.waiting/.question 走胶囊，.running 走圆点（既有语义不变）
    let status: DSHStatusKind?
    /// 无待办无运行时显示的相对时间
    let timeText: String
    /// 搜索命中片段：非空时替换 secondary（保留既有搜索体验）
    var searchSnippet: String? = nil
    var showsMenu: Bool = false
    var onRename: (() -> Void)?
    var onArchive: (() -> Void)?
    /// 行首状态圆点的独立判据：待办优先，否则「运行中」。
    /// 与 `status` 分开是因为用户要求「运行中」要有行首锚点，但右侧仍照旧显示相对时间。
    /// （放在参数列表末尾，保持既有调用点的实参顺序不变。）
    var dotStatus: DSHStatusKind? = nil

    /// 行首状态圆点颜色：判据唯一定义在 `DSHStatusKind.dotColor(in:)`（S28 侧栏共用同一份）。
    private func dotColor(_ p: DSHPalette) -> Color {
        (dotStatus ?? .idle).dotColor(in: p)
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        HStack(spacing: DSHSpacing.md) {
            // ③ 行首状态锚点（与 Bot 行头像同一位置/同一作用）：
            // 只在「需要关注」时画点（running/waiting/question），空闲行留同等宽空白保持对齐。
            // ④ 用户第五轮口径：
            //   运行中 = 绿（`p.success`，既有语义色，浅色下已压暗）
            //   等你审批 = 琥珀 / 等你回答 = 蓝（保留特殊色）
            //   空闲 / nil = **灰点**（不再是「不画点」；10pt 仍是占位宽度，避免文字左右错位）
            Circle()
                .fill(dotColor(p))
                .frame(width: 10, height: 10)
            .padding(.trailing, DSHSpacing.xxs)

            VStack(alignment: .leading, spacing: DSHSpacing.xxs) {
                Text(title)
                    .appFont(DSHType.body.weight(.semibold))
                    .foregroundStyle(p.ink(0.92))
                    .lineLimit(1)
                if let snippet = searchSnippet, !snippet.isEmpty {
                    Text(snippet).lineLimit(2).appFont(DSHType.footnote).foregroundStyle(p.ink(0.5))
                } else if let secondary, !secondary.isEmpty {
                    Text(secondary).appFont(DSHType.footnote).foregroundStyle(p.ink(0.5)).lineLimit(1)
                }
            }
            Spacer(minLength: DSHSpacing.sm)

            if status == .waiting {
                DSHStatus(kind: .waiting, text: String(localized: "等你审批"), pulsing: true)
                Image(systemName: "chevron.forward")
                    .appFont(DSHType.meta.weight(.semibold)).foregroundStyle(p.caution)
            } else if status == .question {
                DSHStatus(kind: .question, text: String(localized: "等你回答"), pulsing: true)
                Image(systemName: "chevron.forward")
                    .appFont(DSHType.meta.weight(.semibold)).foregroundStyle(p.brand)
            } else if status == .running {
                DSHStatus(kind: .running, text: String(localized: "运行中"))
            } else {
                Text(timeText).appFont(DSHType.meta).foregroundStyle(p.ink(0.45)).lineLimit(1)
            }

            if showsMenu, onRename != nil || onArchive != nil {
                Menu {
                    if let onRename {
                        Button { onRename() } label: { Label("重命名", systemImage: "pencil") }
                    }
                    if let onArchive {
                        Button(role: .destructive) { onArchive() } label: {
                            Label("删除（归档）", systemImage: "archivebox")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .appFont(DSHType.footnote.weight(.semibold))
                        .foregroundStyle(p.ink(0.4))
                        .frame(width: 24)
                        .contentShape(Rectangle())
                }
                .menuStyle(.borderlessButton)
                .accessibilityLabel(String(localized: "会话操作"))
            }
        }
        // 行高对齐 Bot 区（用户要求「会话行以 Bot 区为准」）：
        // Bot 行 = 内容 + `.frame(minHeight: 64)`，列表 `VStack(spacing: 12)` → 节距 76pt
        // （实测头像中心 227px = 75.7pt，3x 屏）。
        // 会话行取同一基准：`.padding(.vertical, 12)` 放在 `minHeight: 64` **之内**，
        // 总高 = max(64, 内容 + 24)；列表再给 12pt 间距 → 节距同为 76pt。
        // 注：上一版把 18pt padding 加在 minHeight 之外，行高被撑到 ~100pt（末行压到 tab 栏），已纠正。
        .padding(.vertical, DSHSpacing.md)
        .frame(minHeight: 64)
        .contentShape(Rectangle())
    }
}

// MARK: - 13. 分段控件（参考实现 ScreensShared · 上移进设计系统）
//
// 用途：设置页主题三选、对话/轨迹、全部/已开启/已结束。
// 深浅色都由 palette 决定；选中档是 cardFill + 轻阴影（不新增颜色）。

struct DSHSegmented: View {
    @Environment(\.colorScheme) private var scheme
    let items: [String]
    @Binding var selection: Int

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        HStack(spacing: DSHSpacing.xxs) {
            ForEach(items.indices, id: \.self) { index in
                let active = index == selection
                Text(items[index])
                    .appFont(DSHType.footnote.weight(active ? .semibold : .regular))
                    .foregroundStyle(active ? p.ink(0.92) : p.ink(0.55))
                    .lineLimit(1)
                    // 用户可把字号放大到 150%：项内文字允许收缩，避免「跟随系统」被截成「跟随…」
                    .minimumScaleFactor(0.75)
                    .frame(maxWidth: .infinity, minHeight: 30)
                    .background {
                        if active {
                            RoundedRectangle(cornerRadius: DSHRadius.sm, style: .continuous)
                                .fill(p.cardFill)
                                .shadow(color: .black.opacity(p.isLight ? 0.08 : 0), radius: 3, y: 1)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { selection = index }
                    .accessibilityAddTraits(active ? [.isSelected, .isButton] : .isButton)
            }
        }
        .padding(3)
        .background(p.veil(0.06), in: Capsule())
    }
}

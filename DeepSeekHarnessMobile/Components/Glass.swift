import SwiftUI

struct GlassSurface: ViewModifier {
    let radius: CGFloat
    let dark: Bool
    let tint: Color?
    /// 浅色专用实色面：传了就不再走 `thinMaterial + tint` 的双叠（那组合在浅色下偏深）。
    let lightFill: Color?
    let clear: Bool
    let shadowOpacity: Double

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if clear {
                if let tint {
                    content
                        .glassEffect(.clear.tint(tint), in: .rect(cornerRadius: radius))
                } else {
                    content
                        .glassEffect(.clear, in: .rect(cornerRadius: radius))
                }
            } else {
                if let tint {
                    content
                        .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: radius))
                } else {
                    content
                        .glassEffect(.regular, in: .rect(cornerRadius: radius))
                }
            }
        } else {
            content
                .background {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(dark
                            ? AnyShapeStyle(.ultraThinMaterial.opacity(tint == nil ? 0.75 : 0.82))
                            : (lightFill.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.thinMaterial)))
                        .overlay {
                            RoundedRectangle(cornerRadius: radius, style: .continuous)
                                .fill(tint ?? .clear)
                        }
                }
                .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(.white.opacity(dark ? 0.2 : 0.7), lineWidth: 0.8))
                .shadow(color: DSHColor.navy.opacity(shadowOpacity), radius: 16, y: 8)
        }
    }
}

/// 主题感知的玻璃面：深色走原样（material + tint），浅色改成**实色浅面**，
/// 不再「thinMaterial + tint」双叠（那组合在白页上偏灰、还叠一层阴影）。
/// 用它就不用在每个调用点手写 `colorScheme == .dark ? … : nil`。
struct ThemedGlassSurface: ViewModifier {
    let radius: CGFloat
    let tint: Color?
    let shadowOpacity: Double
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            // 注意：这 7 个调用点原本都没传 `dark`（默认 false → 一直用 thinMaterial）。
            // 为了让**深色逐字不变**，这里也保持 `dark: false`，只按主题切换 tint/实色面。
            .glassSurface(radius: radius,
                          dark: false,
                          tint: colorScheme == .dark ? tint : nil,
                          lightFill: colorScheme == .dark ? nil : Color(uiColor: .secondarySystemBackground),
                          shadowOpacity: colorScheme == .dark ? shadowOpacity : min(shadowOpacity, 0.06))
            .overlay {
                // 浅色下原来的白色高光描边在白底上不可见，补一圈极淡墨色边界。
                if colorScheme == .light {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .stroke(DSHColor.ink.opacity(0.06), lineWidth: 0.8)
                }
            }
    }
}

extension View {
    /// 浅色实色面 / 深色材质面 的统一入口（见 `ThemedGlassSurface`）。
    func themedGlassSurface(radius: CGFloat = 22, tint: Color? = nil, shadowOpacity: Double = 0.18) -> some View {
        modifier(ThemedGlassSurface(radius: radius, tint: tint, shadowOpacity: shadowOpacity))
    }

    func glassSurface(radius: CGFloat = 22, dark: Bool = false, tint: Color? = nil,
                      lightFill: Color? = nil, clear: Bool = false, shadowOpacity: Double = 0.18) -> some View {
        modifier(GlassSurface(radius: radius, dark: dark, tint: tint, lightFill: lightFill,
                              clear: clear, shadowOpacity: shadowOpacity))
    }

    /// Button 与 Menu 共用原生圆形液态玻璃交互，旧系统沿用材质样式。
    @ViewBuilder
    func glassCircleButton() -> some View {
        if #available(iOS 26.0, *) {
            self
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
        } else {
            self
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.8))
        }
    }
}

struct ConnectionDot: View {
    let state: ConnectionState
    @Environment(\.colorScheme) private var colorScheme

    private var color: Color {
        // 图形判据 ≥3:1：品牌绿/琥珀在浅底只有 2.47/1.77，浅色走压暗档。
        let palette = DSHPalette.resolve(colorScheme)
        switch state {
        case .connected:
            return palette.success
        case .failed:
            return palette.danger
        case .connecting:
            return palette.warning
        case .disconnected:
            return .gray
        }
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .shadow(color: color.opacity(0.65), radius: 5)
    }
}

struct HarnessMark: View {
    var onDarkBackground = true
    var width: CGFloat = 216

    var body: some View {
        HStack(spacing: 4) {
            WhalenessOrcaIcon(size: 29)
                .foregroundStyle(onDarkBackground ? Color.white : Color.black)
            Image("WhalenessHarnessWordmark")
                .resizable()
                .scaledToFit()
                .colorInvertIfNeeded(onDarkBackground)
                .frame(maxWidth: .infinity, maxHeight: 29, alignment: .leading)
        }
        .frame(maxWidth: width, alignment: .leading)
        .frame(height: 29)
        .accessibilityLabel("Whaleness Harness")
    }
}

struct WhalenessOrcaIcon: View {
    var size: CGFloat

    var body: some View {
        Image("DshOrcaMark")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

extension View {
    @ViewBuilder
    func colorInvertIfNeeded(_ inverted: Bool) -> some View {
        if inverted { colorInvert() } else { self }
    }
}

// MARK: - iOS 16 兼容层
// 应用部署目标为 iOS 16.0。系统 iOS 17/16.4+ 才提供的 API 在这里做降级实现,
// 调用点不区分系统版本,全平台共用同一套视图代码。

/// iOS 17 起系统才提供 ContentUnavailableView。模块内同名声明优先生效，
/// 为所有系统版本提供一致的空状态视图。支持的初始化形态与系统 API 保持一致：
/// `ContentUnavailableView("标题", systemImage: "...", description: Text(...))`
/// 以及 label/description/actions 闭包形式。
struct ContentUnavailableView<Label: View, Description: View, Actions: View>: View {
    private let label: Label
    private let descriptionView: Description
    private let actions: Actions

    init(
        @ViewBuilder label: () -> Label,
        @ViewBuilder description: () -> Description,
        @ViewBuilder actions: () -> Actions
    ) {
        self.label = label()
        self.descriptionView = description()
        self.actions = actions()
    }

    var body: some View {
        VStack(spacing: 10) {
            label
                .font(.title3.weight(.semibold))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            descriptionView
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            actions
                .padding(.top, 2)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

/// 空状态图标 + 标题的组合，类型固定便于泛型推断。
struct UnavailableIconLabel: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(.primary)
        }
    }
}

extension ContentUnavailableView where Label == UnavailableIconLabel, Description == EmptyView, Actions == EmptyView {
    /// `ContentUnavailableView("标题", systemImage: "图标")` 的简写形态。
    init(_ title: String, systemImage: String) {
        self.init {
            UnavailableIconLabel(title: title, systemImage: systemImage)
        } description: {
            EmptyView()
        } actions: {
            EmptyView()
        }
    }
}

extension ContentUnavailableView where Label == UnavailableIconLabel, Description == Text, Actions == EmptyView {
    /// `ContentUnavailableView("标题", systemImage: "图标", description: Text(...))` 形态。
    init(_ title: String, systemImage: String, description: Text) {
        self.init {
            UnavailableIconLabel(title: title, systemImage: systemImage)
        } description: {
            description
        } actions: {
            EmptyView()
        }
    }
}

/// (旧值, 新值) 双参 onChange 的 iOS 16 兼容实现:旧版 API 只提供新值,
/// 这里用 @State 记录上一个值;首次变更前先在 onAppear 里同步当前值,
/// 保证任何一次变更都能拿到真实的旧值。
private struct OnChangeCompatModifier<Value: Equatable>: ViewModifier {
    let value: Value
    let action: (Value, Value) -> Void
    @State private var previous: Value?
    @State private var hasPrevious = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                if !hasPrevious {
                    previous = value
                    hasPrevious = true
                }
            }
            .onChange(of: value) { newValue in
                let oldValue = hasPrevious ? (previous ?? newValue) : newValue
                previous = newValue
                hasPrevious = true
                action(oldValue, newValue)
            }
    }
}

extension View {
    /// 双参 onChange 兼容:`.onChangeCompat(of: value) { oldValue, newValue in ... }`。
    func onChangeCompat<Value: Equatable>(
        of value: Value,
        action: @escaping (_ oldValue: Value, _ newValue: Value) -> Void
    ) -> some View {
        modifier(OnChangeCompatModifier(value: value, action: action))
    }

    /// presentationBackground 自 iOS 16.4 起可用;更早系统保持默认背景。
    @ViewBuilder
    func presentationBackgroundCompat<S: ShapeStyle>(_ style: S) -> some View {
        if #available(iOS 16.4, *) {
            self.presentationBackground(style)
        } else {
            self
        }
    }

    /// scrollBounceBehavior 自 iOS 16.4 起可用;更早系统使用系统默认弹性。
    @ViewBuilder
    func scrollBounceBehaviorCompat(_ behavior: CompatScrollBounceBehavior) -> some View {
        if #available(iOS 16.4, *) {
            switch behavior {
            case .basedOnSize: self.scrollBounceBehavior(.basedOnSize)
            case .always: self.scrollBounceBehavior(.always)
            }
        } else {
            self
        }
    }
}

extension View {
    /// presentationCompactAdaptation 自 iOS 16.4 起可用；更早系统保持默认适配。
    @ViewBuilder
    func presentationCompactAdaptationCompat() -> some View {
        if #available(iOS 16.4, *) {
            self.presentationCompactAdaptation(.popover)
        } else {
            self
        }
    }

    /// animation(_:body:) 自 iOS 17 起可用；更早系统退化为普通的
    /// animation(_:)（作用于整棵子树），视觉过渡基本一致。
    @ViewBuilder
    func animationCompat(_ animation: Animation?) -> some View {
        self.animation(animation)
    }

    /// geometryGroup 自 iOS 17 起可用；更早系统退化为普通几何传递。
    @ViewBuilder
    func geometryGroupCompat() -> some View {
        if #available(iOS 17.0, *) {
            self.geometryGroup()
        } else {
            self
        }
    }
}

/// 系统类型 ScrollBounceBehavior 自身要求 iOS 16.4,不能出现在未标注可用性的
/// 方法签名里,因此用本地镜像枚举传值。
enum CompatScrollBounceBehavior {
    case basedOnSize
    case always
}

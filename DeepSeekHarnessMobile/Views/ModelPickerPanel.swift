import SwiftUI

// MARK: - S25 模型选择面板（只切模型）
//
//  对应设计稿 25-model-picker.png（浅色主 + 深色），结构照参考实现 S25_ModelPicker 逐条搬。
//  职责边界（产品确认）：本面板**只切模型**——不出现档位选择，切模型时档位回落为该模型
//  默认档（不支持推理则清空，思考 chip 随之消失），回落逻辑在调用方的 select 里，本文件不碰。
//
//  复用范围（Chief 批准「不留两份」）：
//  · 会话 composer 的模型 chip（锚点 = chip，选完走既有 select(group:model:)）
//  · 新会话的「默认模型」chip（锚点 = chip，回调走 saveDefaultModel，带部署级确认）
//  · 设置页「默认模型」行（锚点 = 行，回调同上）
//
//  行内只放网关真实字段：model.name +「支持 N 档推理」（effort 数），不编造描述。

/// S25 锚点 key：会话 chip / 默认模型 chip / 设置页行共用一个 key 类型，
/// 挂载方用 `.anchorPreference(key:value:.bounds)` 上报、`.overlayPreferenceValue` 消费。
struct ModelPickerAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? { nil }

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

struct ModelPickerPanel: View {
    @Environment(\.colorScheme) private var scheme

    /// 面板标题：会话模式「模型」、默认模型模式「默认模型」
    let title: String
    let groups: [GatewayModelGroup]
    var isLoading = false
    /// 只读的当前选择（勾选行展示用；真实状态在 store，本面板不改它）
    var currentSelection: GatewayModelSelection?
    /// 卡片高度上限（调用方传容器可用值）。nil = 不封顶（列表按内容收紧）。
    /// 上限作用在**列表**上：header/搜索/footer 始终钉在卡片里，超出上限只有列表内部滚动。
    var maximumHeight: CGFloat? = nil
    /// 与既有调用完全一致的签名（ConversationModelMenu 的 onSelectModel）
    var onSelectModel: (GatewayModelGroup, GatewayModelItem) -> Void = { _, _ in }
    var onClose: () -> Void = {}

    @State private var query = ""
    @FocusState private var searchFocused: Bool
    /// header+搜索+footer 的实测高度（不含列表）——列表可用上限 = maximumHeight − chromeHeight
    @State private var chromeHeight: CGFloat = 0
    /// 列表内容自然高度（ScrollView 内测量，同统计浮层的量法）
    @State private var listNaturalHeight: CGFloat = 0

    private struct GroupSection: Identifiable {
        let group: GatewayModelGroup
        let models: [GatewayModelItem]
        var id: String { group.id }
    }

    /// 设计规则：模型总数 > 8 才显示搜索；≤ 8 直接列
    private var totalCount: Int { groups.reduce(0) { $0 + $1.models.count } }
    private var showsSearch: Bool { totalCount > 8 }

    private var currentModelName: String? {
        guard let currentSelection else { return nil }
        for group in groups {
            if let model = group.models.first(where: { $0.id == currentSelection.model }) {
                return model.name
            }
        }
        return currentSelection.model
    }

    private func isSelected(_ group: GatewayModelGroup, _ model: GatewayModelItem) -> Bool {
        guard let selection = currentSelection else { return false }
        return selection.model == model.id && selection.provider == group.id
    }

    private var filtered: [GroupSection] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return groups.compactMap { group in
            let models = q.isEmpty
                ? group.models
                : group.models.filter { $0.name.lowercased().contains(q) || group.name.lowercased().contains(q) }
            return models.isEmpty ? nil : GroupSection(group: group, models: models)
        }
    }

    var body: some View {
        let p = DSHPalette(scheme: scheme)
        let listMax = maximumHeight.map { max(120, $0 - chromeHeight) }
        VStack(spacing: 0) {
            header(p)
            if showsSearch { searchField(p) }
            content(p, listMax: listMax)
            footer(p)
        }
        .background(p.pageBackground)
        .background {
            // header/搜索/footer 三段（不含列表）的实测高度——非滚动区，测量一次即稳定
            if let listMax, chromeHeight == 0 {
                chromeHeightProbe(p)
                    .opacity(0)
                    .fixedSize(horizontal: false, vertical: true)
                    .background {
                        GeometryReader { proxy in
                            Color.clear.onAppear { chromeHeight = proxy.size.height }
                        }
                    }
            }
        }
    }

    /// chrome 三段的隐藏副本（与真实 header/搜索/footer 同参同宽，仅供测量）
    @ViewBuilder
    private func chromeHeightProbe(_ p: DSHPalette) -> some View {
        header(p)
        if showsSearch { searchField(p) }
        footer(p)
    }

    // MARK: 头部：标题 + 完成 + 当前模型（只写模型，不写档位）

    private func header(_ p: DSHPalette) -> some View {
        VStack(alignment: .leading, spacing: DSHSpacing.xxs) {
            HStack {
                Text(title).appFont(DSHType.title).foregroundStyle(p.ink(0.92))
                Spacer()
                Button(String(localized: "完成")) { onClose() }
                    .appFont(DSHType.footnote.weight(.semibold))
                    .foregroundStyle(p.brand)
            }
            if let currentModelName {
                Text(String(localized: "当前 \(currentModelName)"))
                    .appFont(DSHType.meta)
                    .foregroundStyle(p.ink(0.45))
            }
        }
        .padding(.horizontal, DSHSpacing.xl)
        .padding(.top, DSHSpacing.md)
        .padding(.bottom, DSHSpacing.md)
    }

    // MARK: 搜索（仅 > 8 个模型时出现）

    private func searchField(_ p: DSHPalette) -> some View {
        HStack(spacing: DSHSpacing.sm) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13)).foregroundStyle(p.ink(0.4))
            TextField(String(localized: "搜索模型"), text: $query)
                .appFont(DSHType.footnote)
                .foregroundStyle(p.ink(0.92))
                .focused($searchFocused)
        }
        .padding(.horizontal, DSHSpacing.md)
        .frame(minHeight: 36)
        .background(p.chipFill)
        .clipShape(RoundedRectangle(cornerRadius: DSHRadius.sm))
        .padding(.horizontal, DSHSpacing.xl)
        .padding(.bottom, DSHSpacing.sm)
    }

    // MARK: 列表与三种空态

    @ViewBuilder
    private func content(_ p: DSHPalette, listMax: CGFloat?) -> some View {
        if groups.isEmpty {
            VStack(spacing: DSHSpacing.sm) {
                if isLoading { ProgressView() }
                Text(isLoading ? String(localized: "正在读取模型…") : String(localized: "暂无可用模型"))
                    .appFont(DSHType.footnote).foregroundStyle(p.ink(0.5))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, DSHSpacing.xxl)
        } else if filtered.isEmpty {
            VStack(spacing: DSHSpacing.xs) {
                Text(String(localized: "没有匹配的模型")).appFont(DSHType.footnote).foregroundStyle(p.ink(0.6))
                Text(String(localized: "换个关键词试试")).appFont(DSHType.meta).foregroundStyle(p.ink(0.4))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, DSHSpacing.xxl)
        } else {
            ScrollView {
                // 用 VStack 不用 LazyVStack：列表自然高度要参与「收紧 vs 封顶」判断，
                // Lazy 容器量不准；模型量级（数十行）全量构建没有性能问题。
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(filtered) { section in
                        Text(section.group.name)
                            .appFont(DSHType.meta.weight(.semibold))
                            .foregroundStyle(p.ink(0.45))
                            .padding(.horizontal, DSHSpacing.md)
                            .padding(.top, DSHSpacing.md)
                            .padding(.bottom, DSHSpacing.xs)
                        ForEach(section.models) { model in
                            modelRow(p, section.group, model)
                        }
                    }
                }
                .padding(.horizontal, DSHSpacing.lg)
                .padding(.bottom, DSHSpacing.md)
                .background {
                    // 列表内容自然高度（ScrollView 内 = 不受视口约束），量一次即稳定
                    GeometryReader { proxy in
                        Color.clear.onAppear {
                            if listNaturalHeight != proxy.size.height { listNaturalHeight = proxy.size.height }
                        }
                    }
                }
            }
            .frame(height: listMax.map { min(max(120, $0), max(120, listNaturalHeight)) }, alignment: .top)
        }
    }

    // MARK: 行：模型名 + 「支持 N 档推理」+ 勾选（不展开档位）

    private func modelRow(_ p: DSHPalette, _ group: GatewayModelGroup, _ model: GatewayModelItem) -> some View {
        let selected = isSelected(group, model)
        let effortCount = model.reasoning?.efforts.count ?? 0

        return HStack(spacing: DSHSpacing.sm) {
            VStack(alignment: .leading, spacing: 1) {
                Text(model.name)
                    .appFont(DSHType.body.weight(.semibold))
                    .foregroundStyle(p.ink(0.92))
                if effortCount > 0 {
                    Text(String(localized: "支持 \(effortCount) 档推理"))
                        .appFont(DSHType.meta)
                        .foregroundStyle(p.ink(0.45))
                }
            }
            Spacer(minLength: DSHSpacing.sm)
            if selected {
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(p.brand)
            }
        }
        .padding(.horizontal, DSHSpacing.md)
        .padding(.vertical, DSHSpacing.sm + 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(selected ? p.brand.opacity(0.09) : .clear)
        .clipShape(RoundedRectangle(cornerRadius: DSHRadius.sm))
        .contentShape(Rectangle())
        .onTapGesture { onSelectModel(group, model) }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(model.name)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: 底部说明（讲清职责边界）

    private func footer(_ p: DSHPalette) -> some View {
        Text(String(localized: "切换后从下一条消息开始生效；推理档位在输入框的「思考」里单独调。"))
            .appFont(DSHType.meta)
            .foregroundStyle(p.ink(0.4))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, DSHSpacing.xl)
            .padding(.vertical, DSHSpacing.md)
            .overlay(alignment: .top) {
                Rectangle().fill(p.rowDivider).frame(height: 1)
            }
    }
}

// MARK: - 锚定浮层的几何常量（只定义一次）
//
//  浮层「能分到多少高度」= 锚点上方/下方里较大的那一侧 − margin − gap − 箭头高。
//  调用方要传 `maximumContentHeight` 时必须走 `maxContentHeight(...)`，**不要自己拿屏高乘比例**：
//  传一个没扣过空间的数，内容会比浮层实际分到的位置高，卡片底部（footer / 最后一行）
//  会被屏幕边缘切掉（2026-10-31 设置页 66% 上限踩到这个坑）。
enum ComposerPopoverMetrics {
    /// 卡片与锚点之间的垂直间隙
    static let gap: CGFloat = 6
    /// 箭头高度（卡片内容要再扣掉它）
    static let arrowHeight: CGFloat = 8

    /// 锚点上下两侧里较大的一侧能放下的内容高度（已扣 margin / gap / 箭头）。
    static func maxContentHeight(sourceFrame: CGRect, availableSize: CGSize, margin: CGFloat) -> CGFloat {
        let above = max(0, sourceFrame.minY - margin - gap)
        let below = max(0, availableSize.height - sourceFrame.maxY - margin - gap)
        return max(0, max(above, below) - arrowHeight)
    }
}

// MARK: - 锚定浮层（原 ConversationView 私有件，S25 起共享给设置页）
//
//  相比原版仅新增两个可选参数（既有调用点行为不变）：
//  · margin：卡片与容器左右的最小留白（S25 用 8 =「左右各 8 铺满」，原统计/预设弹层仍 14）
//  · scrollsContent：false 时不再包外层 ScrollView——S25 面板自带列表滚动，
//    高度由内容自适应、封顶 maximumContentHeight（嵌套 ScrollView 会吞列表手势）
/// 模式与统计共用液态玻璃和箭头，原生玻璃自行处理高光与投影。
struct ComposerPopoverOverlay<Content: View>: View {
    let sourceFrame: CGRect
    let availableSize: CGSize
    let isPresented: Bool
    let onDismiss: () -> Void
    let preferredWidth: CGFloat
    let alignsToTrailing: Bool
    let maximumContentHeight: CGFloat
    let margin: CGFloat
    let scrollsContent: Bool
    let content: Content

    init(
        sourceFrame: CGRect,
        availableSize: CGSize,
        isPresented: Bool,
        onDismiss: @escaping () -> Void,
        preferredWidth: CGFloat = 300,
        alignsToTrailing: Bool = true,
        maximumContentHeight: CGFloat = .infinity,
        margin: CGFloat = 14,
        scrollsContent: Bool = true,
        @ViewBuilder content: () -> Content
    ) {
        self.sourceFrame = sourceFrame
        self.availableSize = availableSize
        self.isPresented = isPresented
        self.onDismiss = onDismiss
        self.preferredWidth = preferredWidth
        self.alignsToTrailing = alignsToTrailing
        self.maximumContentHeight = maximumContentHeight
        self.margin = margin
        self.scrollsContent = scrollsContent
        self.content = content()
    }

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var contentHeight: CGFloat = 240

    private var presentationAnimation: Animation {
        if reduceMotion { return .easeOut(duration: 0.15) }
        return isPresented
            ? .spring(response: 0.32, dampingFraction: 0.88)
            : .easeInOut(duration: 0.18)
    }

    var body: some View {
        let gap = ComposerPopoverMetrics.gap
        let arrowHeight = ComposerPopoverMetrics.arrowHeight
        let width = min(preferredWidth, max(1, availableSize.width - margin * 2))
        let above = max(0, sourceFrame.minY - margin - gap)
        let below = max(0, availableSize.height - sourceFrame.maxY - margin - gap)
        let bodyHeight = min(contentHeight, maximumContentHeight)
        let displaysAbove = above >= bodyHeight + arrowHeight || above >= below
        let height = min(bodyHeight + arrowHeight, max(arrowHeight + 1, displaysAbove ? above : below))
        let preferredCenterX = alignsToTrailing ? sourceFrame.maxX - width / 2 : sourceFrame.minX + width / 2
        let centerX = min(max(preferredCenterX, margin + width / 2), availableSize.width - margin - width / 2)
        let centerY = displaysAbove
            ? sourceFrame.minY - gap - height / 2
            : sourceFrame.maxY + gap + height / 2
        let shape = ComposerPopoverShape(
            arrowX: sourceFrame.midX - (centerX - width / 2),
            pointsUp: !displaysAbove
        )
        // 缩放原点使用胶囊中心，即使弹窗显示在胶囊下方，也从触发处展开。
        let scaleAnchor = UnitPoint(
            x: sourceFrame.midX / max(1, availableSize.width),
            y: sourceFrame.midY / max(1, availableSize.height)
        )

        ZStack(alignment: .topLeading) {
            if isPresented {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)
                    .accessibilityLabel("关闭")
                    .accessibilityAddTraits(.isButton)

                Group {
                    if scrollsContent {
                        // 既有路径：外层 ScrollView 包内容，高度按测得内容截取（统计/预设弹层）。
                        ScrollView {
                            content
                                .background {
                                    GeometryReader { proxy in
                                        Color.clear
                                            .onAppear { contentHeight = proxy.size.height }
                                            .onChangeCompat(of: proxy.size.height) { _, height in
                                                contentHeight = height
                                            }
                                    }
                                }
                        }
                        .scrollBounceBehaviorCompat(.basedOnSize)
                        .frame(width: width, height: height - arrowHeight)
                    } else {
                        // S25 路径：面板自带列表滚动与高度封顶（maximumHeight 传给面板，
                        // 上限作用在列表上，header/搜索/footer 钉住）；外层只量实际尺寸供定位。
                        content
                            .frame(width: width)
                            .background {
                                GeometryReader { proxy in
                                    Color.clear
                                        .onAppear { contentHeight = proxy.size.height }
                                        .onChangeCompat(of: proxy.size.height) { _, height in
                                            contentHeight = height
                                        }
                                }
                            }
                    }
                }
                .background {
                    if scrollsContent == false {
                        GeometryReader { proxy in
                            Color.clear
                                .onAppear { contentHeight = proxy.size.height }
                                .onChangeCompat(of: proxy.size.height) { _, height in
                                    contentHeight = height
                                }
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .padding(displaysAbove ? .bottom : .top, arrowHeight)
                .background {
                    if #available(iOS 26.0, *) {
                        Color.clear.glassEffect(.regular, in: shape)
                    } else {
                        shape.fill(.ultraThinMaterial)
                            .overlay {
                                shape.stroke(Color.primary.opacity(0.06), lineWidth: 0.5)
                            }
                            .shadow(color: .black.opacity(colorScheme == .dark ? 0.24 : 0.12), radius: 10, x: 0, y: 3)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityAddTraits(.isModal)
                .accessibilityAction(.escape, onDismiss)
                .position(x: centerX, y: centerY)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.16, anchor: scaleAnchor).combined(with: .opacity))
            }
        }
        .animation(presentationAnimation, value: isPresented)
        .allowsHitTesting(isPresented)
    }
}

struct ComposerPopoverShape: Shape {
    let arrowX: CGFloat
    let pointsUp: Bool

    func path(in rect: CGRect) -> Path {
        let arrowHeight = min(8, rect.height / 3)
        let top = rect.minY + arrowHeight
        let bottom = rect.maxY
        let radius = min(24, (bottom - top) / 2, rect.width / 2)
        let halfArrow = min(10, max(0, (rect.width - radius * 2) / 2))
        let center = min(max(arrowX, rect.minX + radius + halfArrow), rect.maxX - radius - halfArrow)
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + radius, y: top))
        path.addLine(to: CGPoint(x: center - halfArrow, y: top))
        path.addQuadCurve(
            to: CGPoint(x: center - 2, y: rect.minY + 2),
            control: CGPoint(x: center - 4, y: top)
        )
        path.addQuadCurve(
            to: CGPoint(x: center + 2, y: rect.minY + 2),
            control: CGPoint(x: center, y: rect.minY - 2)
        )
        path.addQuadCurve(
            to: CGPoint(x: center + halfArrow, y: top),
            control: CGPoint(x: center + 4, y: top)
        )
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: top))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: top + radius), control: CGPoint(x: rect.maxX, y: top))
        path.addLine(to: CGPoint(x: rect.maxX, y: bottom - radius))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - radius, y: bottom), control: CGPoint(x: rect.maxX, y: bottom))
        path.addLine(to: CGPoint(x: rect.minX + radius, y: bottom))
        path.addQuadCurve(to: CGPoint(x: rect.minX, y: bottom - radius), control: CGPoint(x: rect.minX, y: bottom))
        path.addLine(to: CGPoint(x: rect.minX, y: top + radius))
        path.addQuadCurve(to: CGPoint(x: rect.minX + radius, y: top), control: CGPoint(x: rect.minX, y: top))
        path.closeSubpath()
        if pointsUp { return path }
        return path.applying(CGAffineTransform(translationX: 0, y: rect.minY + rect.maxY).scaledBy(x: 1, y: -1))
    }
}

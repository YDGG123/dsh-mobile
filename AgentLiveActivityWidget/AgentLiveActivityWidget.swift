import ActivityKit
import SwiftUI
import WidgetKit

@main
struct AgentLiveActivityWidgetBundle: WidgetBundle {
    var body: some Widget { AgentLiveActivityWidget() }
}

struct AgentLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentActivityAttributes.self) { context in
            AgentLiveActivityCard(context: context)
                .widgetURL(AgentActivityLink.session(context))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    AgentIslandBrand()
                        // 展开岛左侧被系统圆角遮罩裁切：只挪品牌整体，不缩放或裁剪鲸鱼。
                        .padding(.leading, AgentActivityMetrics.expandedBrandLeadingInset)
                        .padding(.top, AgentActivityMetrics.expandedBrandTopInset)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    islandTrailing(context)
                        .padding(.trailing, AgentActivityMetrics.expandedTrailingInset)
                        .padding(.top, AgentActivityMetrics.expandedBrandTopInset)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    AgentIslandExpanded(context: context)
                }
            } compactLeading: {
                AgentWhaleMark(compact: true)
                    .frame(width: 20, height: 20)
            } compactTrailing: {
                AgentCompactStatus(context: context)
            } minimal: {
                Image(systemName: statusSymbol(context.state.phase, isStale: context.isStaleCompat))
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(S29IslandColors.status(context.state.phase))
            }
            .keylineTint(S29IslandColors.status(context.state.phase))
            .widgetURL(AgentActivityLink.session(context))
        }
    }

    /// 展开岛右侧：状态短词 + 计时（进行中跳秒，终态总时长）
    @ViewBuilder
    private func islandTrailing(
        _ context: ActivityViewContext<AgentActivityAttributes>
    ) -> some View {
        HStack(spacing: 5) {
            Image(systemName: statusSymbol(context.state.phase, isStale: context.isStaleCompat))
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(staleAwareColor(context))
            Text(shortStatusText(context.state.phase))
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(staleAwareColor(context))
                .lineLimit(1)
            Text(elapsedText(context))
                .font(.system(size: 11, weight: .medium).monospacedDigit())
                .foregroundStyle(S29IslandColors.dim)
        }
    }
}

// MARK: - 状态映射（锁屏卡 / 灵动岛共用）

private func statusSymbol(_ phase: AgentActivityPhase, isStale: Bool) -> String {
    if isStale && !phase.isTerminal { return "exclamationmark.circle" }
    switch phase {
    case .running: return "waveform.path.ecg"
    case .awaitingChoice: return "questionmark.bubble"
    case .awaitingApproval: return "exclamationmark.shield"
    case .submittingApproval: return "arrow.triangle.2.circlepath"
    case .approved, .completed: return "checkmark.circle"
    case .rejected, .failed: return "xmark.circle"
    }
}

private func shortStatusText(_ phase: AgentActivityPhase) -> String {
    switch phase {
    case .running: return ""
    case .awaitingChoice: return "待选择"
    case .awaitingApproval: return "待审批"
    case .submittingApproval: return "提交中"
    case .approved: return "已允许"
    case .rejected: return "已拒绝"
    case .failed: return "失败"
    case .completed: return "已完成"
    }
}

private func staleAwareColor(
    _ context: ActivityViewContext<AgentActivityAttributes>
) -> Color {
    if context.isStaleCompat && !context.state.phase.isTerminal {
        return S29IslandColors.danger
    }
    return S29IslandColors.status(context.state.phase)
}

private func elapsedText(
    _ context: ActivityViewContext<AgentActivityAttributes>
) -> String {
    S29LiveActivityFormat.duration(from: context.attributes.startedAt, to: context.state.updatedAt)
}

// MARK: - 锁屏卡配色（跟随系统外观）

/// 浅色 = 白卡深字，深色 = 深卡浅字。**不要用 `.activityBackgroundTint(...)`**：
/// 它只接受一个静态色，接不了深浅两套；卡片自己在 `.background(...)` 里画底和描边，
/// 系统材质交给系统合成。
struct S29LiveColors {
    let isLight: Bool

    static func resolve(_ scheme: ColorScheme) -> S29LiveColors {
        S29LiveColors(isLight: scheme == .light)
    }

    var surface: Color {
        isLight ? .white
            : Color(red: 11 / 255, green: 20 / 255, blue: 34 / 255).opacity(0.92)
    }
    var text: Color {
        isLight ? Color(red: 0.078, green: 0.090, blue: 0.114)     // #14171D
            : Color(red: 0.949, green: 0.965, blue: 0.984)          // #F2F6FB
    }
    var text2: Color {
        isLight ? Color(red: 0.353, green: 0.396, blue: 0.467)     // #5A6577
            : Color(red: 0.561, green: 0.639, blue: 0.737)          // #8FA3BC
    }
    var text3: Color {
        isLight ? Color(red: 0.541, green: 0.576, blue: 0.639)     // #8A93A3
            : Color(red: 0.494, green: 0.576, blue: 0.675)          // #7E93AC
    }

    // 状态色：浅色档压暗到 AA，深色档提亮
    var caution: Color {
        isLight ? Color(red: 0.620, green: 0.300, blue: 0.0)       // #9E4C00
            : Color(red: 0.941, green: 0.490, blue: 0.078)          // #F07D14
    }
    var brand: Color {
        isLight ? Color(red: 0.180, green: 0.420, blue: 0.902)     // #2E6BE6
            : Color(red: 0.298, green: 0.553, blue: 1.0)            // #4C8DFF
    }
    var success: Color {
        isLight ? Color(red: 0.070, green: 0.370, blue: 0.180)     // #125E2E
            : Color(red: 0.180, green: 0.722, blue: 0.361)          // #2EB85C
    }
    var danger: Color {
        isLight ? Color(red: 0.720, green: 0.120, blue: 0.100)     // #B81E1A
            : Color(red: 1.0, green: 0.231, blue: 0.188)            // #FF3B30
    }

    var tile: Color { isLight ? Color(red: 0.945, green: 0.957, blue: 0.973) : Color.white.opacity(0.055) }
    var tileStrong: Color { isLight ? Color.black.opacity(0.06) : Color.white.opacity(0.10) }
    var line: Color { isLight ? Color.black.opacity(0.09) : Color.white.opacity(0.085) }
    var code: Color {
        isLight ? Color(red: 0.165, green: 0.184, blue: 0.216)
            : Color(red: 0.788, green: 0.847, blue: 0.918)
    }
    var secondaryLabel: Color {
        isLight ? Color(red: 0.165, green: 0.184, blue: 0.216)
            : Color(red: 0.863, green: 0.902, blue: 0.949)
    }

    func status(_ phase: AgentActivityPhase) -> Color {
        switch phase {
        case .running: brand
        case .awaitingChoice, .awaitingApproval, .submittingApproval: caution
        case .approved, .completed: success
        case .rejected, .failed: danger
        }
    }
}

// MARK: - 灵动岛配色（岛体恒黑，系统绘制；内容只能放浅色字，不跟随主题）

enum S29IslandColors {
    static let text = Color(red: 0.949, green: 0.965, blue: 0.984)
    static let dim = Color(red: 0.545, green: 0.576, blue: 0.627)   // #8B93A0
    static let tile = Color.white.opacity(0.07)
    static let tileStrong = Color.white.opacity(0.12)
    static let accent = Color(red: 0.298, green: 0.553, blue: 1.0)
    static let caution = Color(red: 0.941, green: 0.490, blue: 0.078)
    static let success = Color(red: 0.180, green: 0.722, blue: 0.361)
    static let danger = Color(red: 1.0, green: 0.231, blue: 0.188)

    static func status(_ phase: AgentActivityPhase) -> Color {
        switch phase {
        case .running: accent
        case .awaitingChoice, .awaitingApproval, .submittingApproval: caution
        case .approved, .completed: success
        case .rejected, .failed: danger
        }
    }
}

// MARK: - 锁屏卡

struct AgentLiveActivityCard: View {
    @Environment(\.colorScheme) private var scheme

    let state: AgentActivityAttributes.ContentState
    /// 会话标题会被主机重命名，优先用 state 里的新值
    let fallbackTitle: String
    let startedAt: Date
    let gatewayID: String
    let sessionID: String
    var isStale: Bool = false

    init(context: ActivityViewContext<AgentActivityAttributes>) {
        self.init(
            state: context.state,
            fallbackTitle: context.attributes.sessionTitle,
            startedAt: context.attributes.startedAt,
            gatewayID: context.attributes.gatewayID,
            sessionID: context.attributes.sessionID,
            isStale: context.isStaleCompat
        )
    }

    init(
        state: AgentActivityAttributes.ContentState,
        fallbackTitle: String,
        startedAt: Date,
        gatewayID: String,
        sessionID: String,
        isStale: Bool = false
    ) {
        self.state = state
        self.fallbackTitle = fallbackTitle
        self.startedAt = startedAt
        self.gatewayID = gatewayID
        self.sessionID = sessionID
        self.isStale = isStale
    }

    private var c: S29LiveColors { .resolve(scheme) }
    private var tint: Color {
        if isStale && !state.phase.isTerminal { return c.danger }
        return c.status(state.phase)
    }

    private var title: String {
        let updated = state.sessionTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        return updated?.isEmpty == false ? updated! : fallbackTitle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 品牌行（鲸 + Orcarness + 计时）11pt
            HStack(spacing: 6) {
                AgentWhaleMark()
                Text("Orcarness")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(c.text3)
                Spacer(minLength: 8)
                elapsed
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(c.text3)
            }
            // 会话标题 15/600 —— 主机重命名后优先用 state 里的新值
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .tracking(-0.2)
                .foregroundStyle(c.text)
                .lineLimit(1)
                .padding(.top, 2)
            // 来源机器 · 项目 —— 审批时用户要知道是哪台机器在请求
            if let source = state.sourceLabel, !source.isEmpty {
                Text(source)
                    .font(.system(size: 11))
                    .foregroundStyle(c.text3)
                    .lineLimit(1)
            }

            VStack(alignment: .leading, spacing: 0) {
                statusRow
                if let detail = primaryDetail {
                    Text(detail)
                        .font(.system(size: 12))
                        .lineSpacing(1)
                        .foregroundStyle(c.text2)
                        .lineLimit(2)
                        .padding(.top, 2)
                }
                if let command = state.command, !command.isEmpty,
                   state.phase == .awaitingApproval || state.phase == .failed {
                    commandTile(command).padding(.top, 5)
                }
                actions.padding(.top, 6)
            }
            .padding(.top, 5)
            .overlay(alignment: .top) {
                Rectangle().fill(c.line).frame(height: 1)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        // 卡片自己画底和描边：activityBackgroundTint 只接受静态色，接不了深浅两套
        .background(c.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(c.line, lineWidth: 1)
        }
    }

    // MARK: 计时（进行中跳秒；终态换总时长）

    @ViewBuilder
    private var elapsed: some View {
        if state.phase.isTerminal {
            Text(S29LiveActivityFormat.duration(from: startedAt, to: state.updatedAt))
        } else {
            Text(startedAt, style: .timer).multilineTextAlignment(.trailing)
        }
    }

    // MARK: 状态行（图标 + 状态 + · + 工具名；状态色只染图标和文字，不整行铺色）

    private var statusRow: some View {
        HStack(spacing: 6) {
            Image(systemName: statusSymbol(state.phase, isStale: isStale))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(tint)
            Text(statusText)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(tint)
            if let tool = state.toolName, !tool.isEmpty {
                Text("·").font(.system(size: 13)).foregroundStyle(c.text3)
                Text(tool)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(c.text3)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
    }

    private var statusText: String {
        if isStale && !state.phase.isTerminal { return "待同步" }
        switch state.phase {
        case .running: return state.detail?.isEmpty == false ? state.detail! : "正在执行"
        case .awaitingChoice: return "等你选择"
        case .awaitingApproval: return "等你审批"
        case .submittingApproval: return "提交中"
        case .approved: return "已允许"
        case .rejected: return "已拒绝"
        case .failed: return "执行未完成"
        case .completed: return "任务已完成"
        }
    }

    /// 状态行已经用掉 detail 时（running），副行只放 secondaryDetail，避免同一句出现两次
    private var primaryDetail: String? {
        let detailInRow = (state.phase == .running) && (state.detail?.isEmpty == false)
        if detailInRow {
            return state.secondaryDetail?.isEmpty == false ? state.secondaryDetail : nil
        }
        return state.detail?.isEmpty == false ? state.detail : state.secondaryDetail
    }

    // MARK: 命令块（等宽 12pt + tile 底 + 左侧 2pt 状态色竖条——区别于描述文字的唯一信号）

    private func commandTile(_ command: String) -> some View {
        Text(command)
            .font(.system(size: 12, design: .monospaced))
            .tracking(-0.3)
            .foregroundStyle(c.code)
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(c.tile, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            // iOS 16 没有 UnevenRoundedRectangle：先画直角竖条再统一裁圆角
            .overlay(alignment: .leading) {
                Rectangle().fill(tint).frame(width: 2)
            }
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    // MARK: 按钮（次要 = 弱底 + 主文字色；主要 = 品牌蓝实心）

    @ViewBuilder
    private var actions: some View {
        if state.phase == .awaitingApproval && !isStale {
            AgentApprovalActions(
                gatewayID: gatewayID, sessionID: sessionID,
                rpcID: state.rpcID, approvalID: state.approvalID,
                style: .lockScreen
            )
        } else if state.phase == .failed && (state.command?.isEmpty == false) {
            HStack(spacing: 8) {
                secondaryLink("查看详情", icon: nil, gatewayID: gatewayID, sessionID: sessionID, c: c)
                primaryLink("检查结果", icon: "arrow.clockwise", gatewayID: gatewayID, sessionID: sessionID, c: c)
            }
        }
    }
}

// MARK: - 锁屏卡按钮（iOS 17+ AppIntents 实交互；iOS 16 退化为深链——既有行为不变）

enum AgentActionStyle {
    case lockScreen
    case dynamicIsland
}

private func secondaryLink(
    _ title: String, icon: String?,
    gatewayID: String, sessionID: String,
    c: S29LiveColors
) -> some View {
    Link(destination: AgentActivityLink.session(gatewayID: gatewayID, sessionID: sessionID)) {
        HStack(spacing: 5) {
            if let icon { Image(systemName: icon).font(.system(size: 11, weight: .bold)) }
            Text(title).font(.system(size: 13, weight: .semibold))
        }
        .foregroundStyle(c.secondaryLabel)
        .frame(maxWidth: .infinity, minHeight: 30)
        .background(c.tileStrong, in: Capsule())
    }
    .buttonStyle(.plain)
}

private func primaryLink(
    _ title: String, icon: String?,
    gatewayID: String, sessionID: String,
    c: S29LiveColors
) -> some View {
    Link(destination: AgentActivityLink.session(gatewayID: gatewayID, sessionID: sessionID)) {
        HStack(spacing: 5) {
            if let icon { Image(systemName: icon).font(.system(size: 11, weight: .bold)) }
            Text(title).font(.system(size: 13, weight: .semibold))
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, minHeight: 30)
        .background(c.brand, in: Capsule())
    }
    .buttonStyle(.plain)
}

/// 允许 / 拒绝：iOS 17+ 用 AppIntents（锁屏/岛上直接操作），iOS 16 退化为深链。
/// 版本分叉与旧实现一致，只是把两颗按钮换成参考实现的「次要 / 主要」样式。
private struct AgentApprovalActions: View {
    let gatewayID: String
    let sessionID: String
    let rpcID: String?
    let approvalID: String?
    let style: AgentActionStyle

    @Environment(\.colorScheme) private var scheme

    private var c: S29LiveColors { .resolve(scheme) }
    private var height: CGFloat { style == .dynamicIsland ? 30 : 30 }
    private var titleFont: Font { style == .dynamicIsland ? .system(size: 12, weight: .semibold) : .system(size: 13, weight: .semibold) }
    private var iconFont: Font { style == .dynamicIsland ? .system(size: 10, weight: .bold) : .system(size: 11, weight: .bold) }

    var body: some View {
        if let rpcID, let approvalID {
            if #available(iOS 17.0, *) {
                HStack(spacing: 8) {
                    Button(intent: RejectAgentActionIntent(
                        gatewayID: gatewayID,
                        sessionID: sessionID,
                        rpcID: rpcID,
                        approvalID: approvalID
                    )) {
                        rejectLabel
                    }
                    .buttonStyle(.plain)
                    Button(intent: AllowAgentActionIntent(
                        gatewayID: gatewayID,
                        sessionID: sessionID,
                        rpcID: rpcID,
                        approvalID: approvalID
                    )) {
                        allowLabel
                    }
                    .buttonStyle(.plain)
                }
            } else {
                // iOS 16 的实时活动不支持 AppIntents 交互按钮，退化为深链：
                // 点击打开对应会话，在会话内的审批卡片上完成允许/拒绝。
                HStack(spacing: 8) {
                    Link(destination: AgentActivityLink.session(gatewayID: gatewayID, sessionID: sessionID)) { rejectLabel }
                    Link(destination: AgentActivityLink.session(gatewayID: gatewayID, sessionID: sessionID)) { allowLabel }
                }
            }
        }
    }

    private var rejectLabel: some View {
        HStack(spacing: 5) {
            Image(systemName: "xmark").font(iconFont)
            Text("拒绝").font(titleFont)
        }
        .foregroundStyle(c.secondaryLabel)
        .frame(maxWidth: .infinity, minHeight: height)
        .background(c.tileStrong, in: Capsule())
    }

    private var allowLabel: some View {
        HStack(spacing: 5) {
            Image(systemName: "checkmark").font(iconFont)
            Text("允许一次").font(titleFont)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, minHeight: height)
        .background(c.brand, in: Capsule())
    }
}

// MARK: - 灵动岛（岛体恒黑由系统绘制；这里只决定每个 region 放什么）

private enum AgentActivityMetrics {
    // 展开岛左侧被系统圆角遮罩裁切：只挪品牌整体，不缩放或裁剪鲸鱼。
    static let expandedBrandLeadingInset: CGFloat = 8
    static let expandedBrandTopInset: CGFloat = 8
    static let expandedTrailingInset: CGFloat = 10
    // 展开岛下部圆角收窄早于矩形边界，内容要留出安全余量，
    // 带按钮的状态必须保留正的底部边距，否则系统会收缩外形并裁掉按钮。
    // 5（原 12）：展开岛高度由系统给定，实测按钮下沿被圆角遮罩切掉，
    // 底部留白压到 5 后按钮完整可见（按钮本体 minHeight 30 不动——再小不好点，
    // 省高度只能从间距里省，不能靠缩按钮）。
    static var expandedBottomInset: CGFloat { 5 }
}

private struct AgentIslandBrand: View {
    var body: some View {
        HStack(spacing: 6) {
            AgentWhaleMark(compact: false)
            Text("Orcarness")
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(S29IslandColors.dim)
        }
    }
}

/// 展开态底部：与锁屏卡同一套三层结构（状态行 / 命令块 / 按钮），压缩行高与字号。
private struct AgentIslandExpanded: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    private var state: AgentActivityAttributes.ContentState { context.state }
    private var tint: Color {
        if context.isStaleCompat && !state.phase.isTerminal { return S29IslandColors.danger }
        return S29IslandColors.status(state.phase)
    }

    private var title: String {
        let updated = state.sessionTitle?.trimmingCharacters(in: .whitespacesAndNewlines)
        return updated?.isEmpty == false ? updated! : context.attributes.sessionTitle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 展开岛**故意不放来源行**：这是取舍不是遗漏。
            // 展开岛高度由系统给定，多一行会把按钮顶出圆角遮罩（实测按钮被切掉）。
            // 「来源必须在卡片上」是对**锁屏卡**的硬要求（审批时要知道哪台机器在请求），
            // **锁屏卡才是来源的唯一载体**；不要为了"对齐"把它补回岛里。
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .tracking(-0.2)
                .foregroundStyle(S29IslandColors.text)
                .lineLimit(1)

            HStack(spacing: 5) {
                Image(systemName: statusSymbol(state.phase, isStale: context.isStaleCompat))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(tint)
                Text(islandStatusText)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(tint)
                if let tool = state.toolName, !tool.isEmpty {
                    Text("·").font(.system(size: 13)).foregroundStyle(S29IslandColors.dim)
                    Text(tool)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(S29IslandColors.dim)
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 3)

            if let command = state.command, !command.isEmpty,
               state.phase == .awaitingApproval || state.phase == .failed {
                Text(command)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Color(red: 0.788, green: 0.847, blue: 0.918))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(S29IslandColors.tile, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(alignment: .leading) {
                        Rectangle().fill(tint).frame(width: 2)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .padding(.top, 5)
            } else if let detail = primaryDetail {
                Text(detail)
                    .font(.system(size: 12))
                    .lineSpacing(2)
                    .foregroundStyle(S29IslandColors.dim)
                    .lineLimit(2)
                    .padding(.top, 5)
            }

            if state.phase == .awaitingApproval && !context.isStaleCompat {
                AgentApprovalActions(
                    gatewayID: context.attributes.gatewayID,
                    sessionID: context.attributes.sessionID,
                    rpcID: state.rpcID,
                    approvalID: state.approvalID,
                    style: .dynamicIsland
                )
                    .padding(.top, 4)
            } else if state.phase == .failed && (state.command?.isEmpty == false) {
                HStack(spacing: 7) {
                    Link(destination: AgentActivityLink.session(context)) {
                        Text("查看详情")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color(red: 0.902, green: 0.933, blue: 0.973))
                            .frame(maxWidth: .infinity, minHeight: 30)
                            .background(S29IslandColors.tileStrong, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    Link(destination: AgentActivityLink.session(context)) {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.clockwise").font(.system(size: 10, weight: .bold))
                            Text("检查结果").font(.system(size: 12, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .background(S29IslandColors.accent, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 9)
            }
        }
        .padding(.bottom, AgentActivityMetrics.expandedBottomInset)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    private var islandStatusText: String {
        if context.isStaleCompat && !state.phase.isTerminal { return "待同步" }
        switch state.phase {
        case .running: return state.detail?.isEmpty == false ? state.detail! : "正在执行"
        case .awaitingChoice: return "等你选择"
        case .awaitingApproval: return "等你审批"
        case .submittingApproval: return "提交中"
        case .approved: return "已允许"
        case .rejected: return "已拒绝"
        case .failed: return "执行未完成"
        case .completed: return "任务已完成"
        }
    }

    private var primaryDetail: String? {
        let detailInRow = (state.phase == .running) && (state.detail?.isEmpty == false)
        if detailInRow {
            return state.secondaryDetail?.isEmpty == false ? state.secondaryDetail : nil
        }
        return state.detail?.isEmpty == false ? state.detail : state.secondaryDetail
    }
}

/// 紧凑态右侧：运行中显示跳动计时，其余显示状态短词；过期未终态 = 待同步红圈
private struct AgentCompactStatus: View {
    let context: ActivityViewContext<AgentActivityAttributes>

    var body: some View {
        if context.isStaleCompat && !context.state.phase.isTerminal {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(S29IslandColors.danger)
        } else if context.state.phase == .running {
            Text(context.attributes.startedAt, style: .timer)
                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                .foregroundStyle(S29IslandColors.accent)
                .frame(width: 38, alignment: .trailing)
        } else {
            HStack(spacing: 4) {
                Image(systemName: statusSymbol(context.state.phase, isStale: false))
                    .font(.system(size: 11, weight: .semibold))
                Text(shortStatusText(context.state.phase))
                    .font(.system(size: 11, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(S29IslandColors.status(context.state.phase))
        }
    }
}

// MARK: - 鲸鱼品牌图（用户 2026-10-30 口径）

/// 蓝鲸品牌图（用户 2026-10-31 定稿：**不要浅灰圆底，要蓝鲸，白色必须保留**）。
///
/// 资源 `orcarness-orca-mark-blue.svg` 是**双层**结构（源 SVG 只有一个 `#111111`、
/// 白色是透明负空间，直接改色做不到"黑变蓝、白保留"）：
///   第 1 层 = 身体外轮廓实心**白**
///   第 2 层 = 完整路径（含两条挖孔子路径）染品牌蓝 #2E6BE6
/// 挖孔处露出第 1 层白 → 深黑底（锁屏深卡 / 恒黑灵动岛）上白斑清晰。
///
/// **不能用 `.renderingMode(.template)`**：会把双层压成单色剪影，白斑与鲸身细节全丢
/// （资产级 `template-rendering-intent` 同样不能加）。浅色与深色统一用这张蓝鲸
/// （白底上也是蓝身白斑，用户要求统一）。
private struct AgentWhaleMark: View {
    var compact: Bool = false

    private var size: CGFloat { compact ? 16 : 16 }

    var body: some View {
        Image("OrcarnessOrcaBlue")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

// MARK: - 时长格式（与旧实现同一口径）

enum S29LiveActivityFormat {
    static func duration(from start: Date, to end: Date) -> String {
        let total = max(0, Int(end.timeIntervalSince(start)))
        let h = total / 3_600, m = (total % 3_600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

private enum AgentActivityLink {
    static func session(_ context: ActivityViewContext<AgentActivityAttributes>) -> URL {
        session(gatewayID: context.attributes.gatewayID, sessionID: context.attributes.sessionID)
    }

    static func session(gatewayID: String, sessionID: String) -> URL {
        var components = URLComponents()
        components.scheme = "orcarness"
        components.host = "session"
        components.queryItems = [
            URLQueryItem(name: "gateway", value: gatewayID),
            URLQueryItem(name: "id", value: sessionID)
        ]
        return components.url ?? URL(string: "orcarness://session")!
    }
}

// MARK: - iOS 16.1 兼容层

extension ActivityViewContext {
    /// `isStale` 自 iOS 16.2 起提供；iOS 16.1 上按“未过期”处理。
    var isStaleCompat: Bool {
        if #available(iOS 16.2, *) {
            return isStale
        }
        return false
    }
}

#if DEBUG
// Live Activity 的 `#Preview(as:using:contentStates:)` 是 iOS 17+ API；本扩展
// deployment target 是 16.2，不给它加可用性守卫时 **Debug 配置编不过**（Release 把
// 整段 DEBUG 剔除，所以这个缺口一直没暴露）。下面每个预览都用 `@available` 标注，
// iOS 16 上不参与编译、Xcode 画布在 17+ 环境照常可用。
private let previewAttributes = AgentActivityAttributes(
    gatewayID: "preview-gateway",
    sessionID: "preview-session",
    sessionTitle: "修复 Android 构建",
    startedAt: Date().addingTimeInterval(-168)
)

private func previewState(
    phase: AgentActivityPhase,
    status: String,
    detail: String,
    secondaryDetail: String? = nil,
    command: String? = nil,
    stepKind: AgentActivityStepKind? = nil
) -> AgentActivityAttributes.ContentState {
    AgentActivityAttributes.ContentState(
        sessionTitle: "修复 Android 构建",
        phase: phase,
        status: status,
        detail: detail,
        secondaryDetail: secondaryDetail,
        sourceLabel: "Mac Studio · Orcarness",
        command: command,
        rpcID: command == nil ? nil : "preview-rpc",
        approvalID: command == nil ? nil : "preview-approval",
        toolName: command == nil ? nil : "Bash",
        stepKind: stepKind,
        updatedAt: .now
    )
}

@available(iOS 17.0, *)
#Preview("运行中 · 紧凑", as: .dynamicIsland(.compact), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .running,
        status: "正在执行",
        detail: "正在分析构建日志"
    )
}

@available(iOS 17.0, *)
#Preview("等待审批 · 展开", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .awaitingApproval,
        status: "需要批准 · 仅限本次",
        detail: "运行构建命令",
        command: "./gradlew :androidApp:assembleDebug"
    )
}

@available(iOS 17.0, *)
#Preview("等待审批 · 锁屏", as: .content, using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .awaitingApproval,
        status: "需要批准 · 仅限本次",
        detail: "运行构建命令",
        command: "./gradlew :androidApp:assembleDebug"
    )
}

@available(iOS 17.0, *)
#Preview("运行中 · 锁屏", as: .content, using: previewAttributes) {
    AgentLiveActivityWidget()
} contentStates: {
    previewState(
        phase: .running,
        status: "正在执行",
        detail: "正在分析构建日志"
    )
}
#endif

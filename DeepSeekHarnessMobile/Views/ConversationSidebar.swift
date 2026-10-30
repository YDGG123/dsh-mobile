import SwiftUI

/// 侧栏触发的导航动作，由会话页传入，最终交给持有导航栈的 RootNavigationHost。
/// 默认值都是空操作，侧栏脱离导航栈单独预览时不会崩溃。
struct ConversationSidebarActions {
    var openSession: (SessionSummary) -> Void = { _ in }
    var openPlugins: () -> Void = {}
    var openScheduledTasks: () -> Void = {}
    var openSettings: () -> Void = {}
}

/// 会话页按需弹出的悬浮侧栏（S28）：
/// 字标 + 会话数 → 工作区分组（可折叠）→ 底部工具条（插件 / 定时任务 / 设置）。
/// 展示和关闭由会话页管理，不参与根导航容器的宽度分配。
///
/// 与旧实现的差别只在「怎么显示」：
/// · 三个导航入口从「三条 44pt 行 + 设置沉底」收进底部一条工具条，纵向全部还给会话；
/// · 字标从 170pt 横条缩到 116pt，右侧补「N 个会话」；
/// · 会话行补状态（待审批 / 待回答 = 胶囊，运行中 = 绿点、已中断 = 红点，已完成 = 相对时间），
///   颜色与首页会话行共用 `DSHStatusKind.dotColor(in:)`，运行中是绿不是设计稿的蓝；
/// · 当前会话三个信号：brand 底 + 左侧 3pt 竖条 + 标题加粗；
/// · 底色 / 描边 / 圆角改用 palette + DSHRadius，删掉写死的两套深浅色值。
struct ConversationSidebar: View {
    static let width: CGFloat = 300
    private static let ungroupedKey = "__ungrouped__"

    /// 刻意不观察整个 AppStore（与 RootNavigationHost 同样的做法）：会话页流式输出时
    /// store 的其他 @Published 每个 token 都在变，观察整个对象会让侧栏每秒重算几十次。
    /// 只订阅侧栏真正显示的三项，分组结果缓存在 @State 里。
    let store: AppStore
    let actions: ConversationSidebarActions
    @Environment(\.colorScheme) private var colorScheme
    /// 展开的工作区 id（逗号分隔），只用于持久化；渲染读 expandedKeys。
    @AppStorage("conversationSidebar.expandedWorkspaces") private var expandedStorage = ""
    @State private var expandedKeys: Set<String> = []
    @State private var workspaces: [GatewayWorkspace] = []
    @State private var sessions: [SessionSummary] = []
    @State private var selectedSessionID: String?
    @State private var groups = SessionGroups()

    var body: some View {
        let p = DSHPalette(scheme: colorScheme)
        VStack(spacing: 0) {
            header(p)

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(visibleGroups) { group in
                        groupHeader(group, p)
                        if group.isExpanded {
                            if group.sessions.isEmpty {
                                Text(String(localized: "暂无会话"))
                                    .appFont(DSHType.footnote)
                                    .foregroundStyle(p.ink(0.45))
                                    .padding(.leading, 28)
                                    .padding(.vertical, 6)
                            } else {
                                ForEach(group.sessions) { session in
                                    sessionRow(session, p)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, DSHSpacing.sm)
                .padding(.bottom, DSHSpacing.sm)
            }
            .frame(maxHeight: .infinity)

            footer(p)
        }
        .frame(width: Self.width)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(p.cardFill)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(p.cardStroke, lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("conversation-sidebar")
        .onAppear {
            expandedKeys = Set(expandedStorage.split(separator: ",").map(String.init))
            workspaces = store.workspaces
            sessions = store.sessions
            selectedSessionID = store.selectedSessionId
            regroup()
            expandCurrentWorkspace()
        }
        // @Published 在 willSet 时发出：必须用回调里的新值，不能回头读 store（那时还是旧值）。
        .onReceive(store.$workspaces) { value in
            workspaces = value
            regroup()
        }
        .onReceive(store.$sessions) { value in
            sessions = value
            regroup()
        }
        .onReceive(store.$selectedSessionId) { selectedSessionID = $0 }
    }

    // MARK: - ① 字标 + 会话数

    /// 原来是 170pt 宽的字标占一整条横条；现在缩到一行，右侧补总数
    /// （让「这是全部会话的入口」有依据，不再靠猜）。
    private func header(_ p: DSHPalette) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: DSHSpacing.sm) {
            HarnessMark(onDarkBackground: colorScheme == .dark, width: 116)
            Text(String(localized: "\(visibleSessionCount) 个会话"))
                .appFont(DSHType.meta)
                .foregroundStyle(p.ink(0.45))
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DSHSpacing.lg)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    /// 与会话列表同口径的可见会话数（子智能体 / 无内容会话不计，见 `SessionGroups.group`）。
    private var visibleSessionCount: Int {
        sessions.filter(\.isVisibleInHistory).count
    }

    // MARK: - 分组顺序与数据

    /// 侧栏要显示的一组会话（工作区或「未分组」）。
    /// **注意**：这里只做「取 SessionGroups 的分组结果 + 排序」，不重新分组——
    /// 分组口径在 `SessionGroups.group(...)`（有单测），改动会破坏它。
    private struct SidebarGroup: Identifiable {
        let id: String
        let title: String
        let isTray: Bool
        let sessions: [SessionSummary]
        let isExpanded: Bool
    }

    private var visibleGroups: [SidebarGroup] {
        var result: [SidebarGroup] = []
        for workspace in workspaces {
            result.append(SidebarGroup(
                id: workspace.id,
                title: workspace.title,
                isTray: false,
                sessions: sortedByActivity(groups.byWorkspace[workspace.id] ?? []),
                isExpanded: expandedKeys.contains(workspace.id)
            ))
        }
        result.append(SidebarGroup(
            id: Self.ungroupedKey,
            title: String(localized: "未分组"),
            isTray: true,
            sessions: sortedByActivity(groups.ungrouped),
            isExpanded: expandedKeys.contains(Self.ungroupedKey)
        ))
        return result
    }

    // MARK: - ② 分组头（可折叠，32pt）

    private func groupHeader(_ group: SidebarGroup, _ p: DSHPalette) -> some View {
        let containsCurrent = selectedSessionID.map { id in
            group.sessions.contains { $0.id == id }
        } ?? false
        return Button {
            withAnimation(.easeInOut(duration: 0.18)) { toggle(group.id) }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: group.isTray ? "tray" : "folder")
                    .appFont(.system(size: 12))
                    .foregroundStyle(containsCurrent ? p.brand : p.ink(0.45))
                    .frame(width: 13)
                Text(group.title)
                    .appFont(DSHType.meta.weight(.semibold))
                    .foregroundStyle(p.ink(0.45))
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 4)
                Text("\(group.sessions.count)")
                    .appFont(DSHType.meta)
                    .foregroundStyle(p.ink(0.45))
                    .monospacedDigit()
                Image(systemName: "chevron.down")
                    .appFont(.system(size: 9, weight: .semibold))
                    .foregroundStyle(p.ink(0.35))
                    .rotationEffect(.degrees(group.isExpanded ? 0 : -90))
            }
            .padding(.horizontal, DSHSpacing.sm)
            .frame(height: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 6)
        .accessibilityLabel(group.title)
        .accessibilityValue(group.isExpanded ? Text("已展开") : Text("已收起"))
    }

    // MARK: - ② 会话行（44pt，状态见 §3.3）

    private func sessionRow(_ session: SessionSummary, _ p: DSHPalette) -> some View {
        let selected = session.id == selectedSessionID
        // 是否已在该会话页由导航层判断，仍然发送动作以便关闭面板。
        return Button {
            actions.openSession(session)
        } label: {
            HStack(spacing: DSHSpacing.sm) {
                Text(session.title.isEmpty ? String(localized: "未命名会话") : session.title)
                    .appFont(DSHType.body.weight(selected ? .semibold : .regular))
                    .foregroundStyle(p.ink(0.92))
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 4)
                sessionStatus(session, p)
            }
            // 20 = 分组头 8（行内边距）+ 13（图标宽）− 1：标题与分组名文字起点对齐。
            .padding(.leading, 20)
            .padding(.trailing, DSHSpacing.sm)
            .frame(height: 44)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(p.brand.opacity(p.isLight ? 0.09 : 0.16))
                }
            }
            .overlay(alignment: .leading) {
                if selected {
                    Capsule()
                        .fill(p.brand)
                        .frame(width: 3, height: 20)
                        .padding(.leading, DSHSpacing.sm)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("conversation-sidebar-session-\(session.id)")
    }

    // MARK: - 会话状态（§3.3 的四态，判据与 01/02 屏同源）

    /// 返回 `DSHStatusKind`，**不另立一套枚举**：
    /// · 待办判据复用 `sessionRowStatusKind`（01/02 屏同一个函数）；
    /// · 颜色统一走 `DSHStatusKind.dotColor(in:)`（与首页会话行同一份定义，运行中是**绿**）。
    ///
    /// **「已中断」（`.failed`）当前无数据源**：KMP 与 iOS 的 `SessionSummary` 都只有
    /// `isRunning` / `hasUnread`，没有「上一轮异常结束」的会话级信号（`turn/end` 的失败
    /// 判定只在通知链路用了一次，没落到会话状态）。这里保留 `.failed` 的渲染分支，
    /// 等 store 补上信号即可接——**不编造状态**。
    private func sidebarStatus(_ session: SessionSummary) -> DSHStatusKind {
        if let pending = sessionRowStatusKind(for: session.id, store: store) { return pending }
        return session.isRunning ? .running : .idle
    }

    @ViewBuilder
    private func sessionStatus(_ session: SessionSummary, _ p: DSHPalette) -> some View {
        let kind = sidebarStatus(session)
        switch kind {
        case .waiting:
            DSHStatus(kind: .waiting, text: String(localized: "等你审批"), pulsing: true)
        case .question:
            DSHStatus(kind: .question, text: String(localized: "等你回答"), pulsing: true)
        case .running, .failed:
            // 只是状态：圆点 + 既有语义色（运行中绿 / 已中断红），不加呼吸（不抢注意力）
            Circle().fill(kind.dotColor(in: p)).frame(width: 6, height: 6)
        case .online, .idle:
            Text(session.lastActivity.formatted(.relative(presentation: .named)))
                .appFont(DSHType.meta)
                .foregroundStyle(p.ink(0.45))
                .monospacedDigit()
                .lineLimit(1)
        }
    }

    // MARK: - ③ 底部工具条（插件 / 定时任务 / 设置）

    /// 三个导航入口原来占三条 44pt 行，现在收成一条 48pt 工具条，纵向还给会话。
    private func footer(_ p: DSHPalette) -> some View {
        HStack(spacing: 0) {
            navigationButton(String(localized: "插件"), action: actions.openPlugins, p: p) {
                Image("DshPluginPinwheel")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 15, height: 15)
            }
            navigationButton(String(localized: "定时任务"), action: actions.openScheduledTasks, p: p) {
                Image(systemName: "clock")
                    .appFont(.system(size: 15))
            }
            navigationButton(String(localized: "设置"), action: actions.openSettings, p: p) {
                Image(systemName: "slider.horizontal.3")
                    .appFont(.system(size: 15))
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .overlay(alignment: .top) {
            Rectangle().fill(p.rowDivider).frame(height: 1)
        }
    }

    private func navigationButton<Icon: View>(
        _ title: String,
        action: @escaping () -> Void,
        p: DSHPalette,
        @ViewBuilder icon: () -> Icon
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                icon()
                Text(title)
                    .appFont(DSHType.meta)
                    .lineLimit(1)
            }
            .foregroundStyle(p.ink(0.62))
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    // MARK: - 分组数据（口径在 SessionGroups，这里只缓存）

    /// 一次遍历把可见会话分到所属工作区（同一会话属于多个工作区时各列一份），
    /// 不属于任何工作区的归入「未分组」，与 AppStore.ungroupedSessions 口径一致。
    /// 只在会话或工作区列表变化时调用。
    private func regroup() {
        groups = SessionGroups.group(sessions: sessions, workspaces: workspaces)
    }

    private func sortedByActivity(_ sessions: [SessionSummary]) -> [SessionSummary] {
        sessions.sorted { $0.lastActivity > $1.lastActivity }
    }

    // MARK: - 展开状态（持久化格式不变：逗号分隔的 id）

    private func setExpanded(_ keys: Set<String>) {
        expandedKeys = keys
        expandedStorage = keys.sorted().joined(separator: ",")
    }

    private func toggle(_ key: String) {
        var keys = expandedKeys
        if keys.contains(key) { keys.remove(key) } else { keys.insert(key) }
        setExpanded(keys)
    }

    /// 进入会话页时，保证当前会话所在的工作区是展开的（不改动其他工作区的展开状态）。
    private func expandCurrentWorkspace() {
        let key: String?
        if let sessionID = selectedSessionID {
            key = workspaces.first(where: { $0.sessionIds.contains(sessionID) })?.id ?? Self.ungroupedKey
        } else if store.isUngroupedWorkspaceSelected {
            key = Self.ungroupedKey
        } else {
            key = store.activeWorkspace?.id
        }
        guard let key, !expandedKeys.contains(key) else { return }
        setExpanded(expandedKeys.union([key]))
    }
}

/// 侧栏的工作区分组结果。抽成独立类型是为了可以单测 —— 原本是 View 内部的私有逻辑。
struct SessionGroups: Equatable {
    var byWorkspace: [String: [SessionSummary]] = [:]
    var ungrouped: [SessionSummary] = []

    /// 同一会话属于多个工作区时，每个工作区各列一份；
    /// 不属于任何工作区的会话归入「未分组」。没有会话内容的会话不进入任何一组。
    static func group(sessions: [SessionSummary], workspaces: [GatewayWorkspace]) -> SessionGroups {
        var owners: [String: [String]] = [:]
        for workspace in workspaces {
            for sessionID in workspace.sessionIds {
                owners[sessionID, default: []].append(workspace.id)
            }
        }
        var result = SessionGroups()
        for session in sessions where session.isVisibleInHistory {
            if let workspaceIDs = owners[session.id] {
                for workspaceID in workspaceIDs {
                    result.byWorkspace[workspaceID, default: []].append(session)
                }
            } else {
                result.ungrouped.append(session)
            }
        }
        return result
    }
}

import SwiftUI

// MARK: - 02 屏：全部会话（参考实现 S02_AllSessions + design-handoff §4.1）
//
// 这一屏在工程里是**新建**的：原首页硬上限 12 条、没有全量页。
// 结构：顶栏（返回 + 标题 + 总数）→ 搜索 → 分组（今天/昨天/本周/更早）→ 行（带「…」）。
//
// 排序/分组约定（Chief 批准 2026-10-29，新约定）：
//   - 排序：`lastActivity` 降序
//   - 切组：**按日历日**（今天 / 昨天 / 本周 / 更早），不是「24/48 小时滑动窗口」。
//     理由：用户对「昨天」的直觉是日历日，滑动窗口会让「昨天 23:50」在凌晨被算成今天。
//   - 组内保持 lastActivity 降序。
//
// 行操作：长按 contextMenu + 右侧「…」两条并存（快路径 + 发现性），
// 菜单项不新增（只有「重命名」「删除（归档）」），归档不删历史。

struct AllSessionsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }

    /// 工作区范围内的**全量**会话（未截断、未按搜索过滤）：由首页同一套作用域计算得出，
    /// 保证「全部」与首页「最近 5 条」是同一个集合（见 AppStore.workspaceScopedSessions）。
    let sessions: [SessionSummary]
    let onOpen: (SessionSummary) -> Void

    @State private var query = ""
    @State private var renamingSession: SessionSummary?
    @State private var renamedTitle = ""
    @State private var archivingSession: SessionSummary?

    private static let dayOrder = ["今天", "昨天", "本周", "更早"]

    var body: some View {
        VStack(spacing: 0) {
            DSHPageHeader(title: "全部会话", onBack: { dismiss() }) {
                Text("\(sessions.count) 个")
                    .appFont(DSHType.footnote)
                    .foregroundStyle(palette.ink(0.45))
            }
            .padding(.horizontal, DSHSpacing.lg)
            .padding(.vertical, DSHSpacing.sm)

            searchBar

            ScrollView {
                VStack(alignment: .leading, spacing: DSHSpacing.md) {
                    if groups.isEmpty {
                        emptyState
                    }
                    ForEach(groups, id: \.day) { group in
                        Text(group.day)
                            .appFont(DSHType.meta.weight(.semibold))
                            .foregroundStyle(palette.ink(0.45))
                            .padding(.top, DSHSpacing.xxs)
                        groupCard(group.items)
                    }
                }
                .padding(.horizontal, DSHSpacing.lg)
                .padding(.bottom, DSHSpacing.xxl)
            }
        }
        .background(palette.pageBackground)
        // 顶栏由 DSHPageHeader 独占：隐藏系统导航栏，否则会出现两个返回键
        .toolbar(.hidden, for: .navigationBar)
        // 搜索接真实数据：与 01 屏同一套（onChange → store.search：本地缓存 + 网关结果）
        .onChangeCompat(of: query) { _, value in store.search(value) }
        .alert(
            String(localized: "重命名会话"),
            isPresented: Binding(
                get: { renamingSession != nil },
                set: { if !$0 { renamingSession = nil } }
            )
        ) {
            TextField(String(localized: "会话名称"), text: $renamedTitle)
            Button(String(localized: "取消"), role: .cancel) { renamingSession = nil }
            Button(String(localized: "保存")) {
                if let session = renamingSession { store.renameSession(session.id, title: renamedTitle) }
                renamingSession = nil
            }
        }
        .confirmationDialog(
            String(localized: "删除会话？"),
            isPresented: Binding(
                get: { archivingSession != nil },
                set: { if !$0 { archivingSession = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(String(localized: "删除（归档）"), role: .destructive) {
                if let session = archivingSession { store.archiveSession(session.id) }
                archivingSession = nil
            }
            Button(String(localized: "取消"), role: .cancel) { archivingSession = nil }
        } message: {
            Text(String(localized: "归档不会删除历史；之后可以从归档里找回。"))
        }
        .onDisappear {
            // 离开本页时清掉搜索态，避免网关侧保留一份与首页不一致的结果集。
            if !query.isEmpty { store.search("") }
        }
    }

    // MARK: - 搜索框（参考实现 S02 ②：chipFill 底、sm 圆角、36pt 高）

    private var searchBar: some View {
        HStack(spacing: DSHSpacing.sm) {
            Image(systemName: "magnifyingglass")
                .appFont(DSHType.footnote)
                .foregroundStyle(palette.ink(0.4))
            TextField("", text: $query, prompt: Text("搜索会话").foregroundColor(palette.ink(0.5)))
                .appFont(DSHType.footnote)
                .foregroundStyle(palette.ink(0.92))
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
        }
        .padding(.horizontal, DSHSpacing.md)
        .frame(minHeight: 36)
        .background(palette.chipFill)
        .clipShape(RoundedRectangle(cornerRadius: DSHRadius.sm))
        .padding(.horizontal, DSHSpacing.lg)
        .padding(.bottom, DSHSpacing.sm)
    }

    // MARK: - 分组卡（参考实现 S02 ③④：组内行画分隔线，最后一行不画）

    private func groupCard(_ items: [SessionSummary]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, session in
                Button {
                    onOpen(session)
                } label: {
                    DSHSessionRow(
                        title: session.title,
                        secondary: sessionRowSecondaryText(for: session, store: store),
                        status: sessionRowStatusKind(for: session.id, store: store),
                        timeText: session.lastActivity.formatted(.relative(presentation: .named)),
                        searchSnippet: searchSnippet(for: session),
                        showsMenu: true,
                        onRename: {
                            renamedTitle = session.title
                            renamingSession = session
                        },
                        onArchive: { archivingSession = session },
                        dotStatus: sessionRowDotKind(for: session, store: store)
                    )
                    .padding(.horizontal, DSHSpacing.md)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("all-sessions-row-\(session.id)")
                .contextMenu {
                    // 长按与「…」并存（参考实现要求两条都有）
                    Button {
                        renamedTitle = session.title
                        renamingSession = session
                    } label: { Label("重命名", systemImage: "pencil") }
                    Button(role: .destructive) {
                        archivingSession = session
                    } label: { Label("删除（归档）", systemImage: "archivebox") }
                }

                if index < items.count - 1 {
                    Rectangle()
                        .fill(palette.rowDivider)
                        .frame(height: 1)
                        .padding(.leading, DSHSpacing.md)
                }
            }
        }
        .background(palette.cardFill)
        .clipShape(RoundedRectangle(cornerRadius: DSHRadius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: DSHRadius.md, style: .continuous)
            .strokeBorder(palette.cardStroke, lineWidth: 1))
    }

    // MARK: - 空态

    @ViewBuilder
    private var emptyState: some View {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // 一个会话都没有：沿用首页空态原文（拆成标题+说明两段，字句未改）
            DSHEmptyState(
                icon: "tray",
                title: String(localized: "暂无已知会话"),
                message: String(localized: "点这里连接桌面端后创建第一个任务。")
            )
            .frame(maxWidth: .infinity)
            .padding(.top, DSHSpacing.xxl)
        } else {
            DSHEmptyState(
                icon: "clock",
                title: String(localized: "没有匹配的会话"),
                message: String(localized: "换个关键词试试。")
            )
            .frame(maxWidth: .infinity)
            .padding(.top, DSHSpacing.xxl)
        }
    }

    // MARK: - 数据：过滤 + 分组

    /// 搜索命中片段（与 01 屏一致：有命中就显示片段替代次要信息位）
    private func searchSnippet(for session: SessionSummary) -> String? {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return store.searchResults.first(where: { $0.sessionId == session.id })?.snippet
    }

    /// 过滤后的全量列表（搜索态与首页同一套合并规则：本地会话 ∪ 网关命中）
    private var filteredSessions: [SessionSummary] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let sorted = sessions.sorted { $0.lastActivity > $1.lastActivity }
        guard !normalized.isEmpty else { return sorted }
        let hitIds = Set(store.searchResults.map(\.sessionId))
        let known = Set(sessions.map(\.id))
        let missing = store.searchResults
            .filter { !known.contains($0.sessionId) && !store.isSubagentSession($0.sessionId) }
            .map {
                SessionSummary(id: $0.sessionId, title: "已缓存聊天",
                               lastActivity: .distantPast, isRunning: false, hasUnread: false)
            }
        return (sorted + missing)
            .filter {
                hitIds.contains($0.id)
                    || $0.title.localizedCaseInsensitiveContains(normalized)
                    || $0.id.localizedCaseInsensitiveContains(normalized)
            }
            .sorted { $0.lastActivity > $1.lastActivity }
    }

    private var groups: [(day: String, items: [SessionSummary])] {
        let all = filteredSessions
        return Self.dayOrder.compactMap { day in
            let items = all.filter { Self.dayBucket(for: $0.lastActivity) == day }
            return items.isEmpty ? nil : (day, items)
        }
    }

    /// 日历日切组（约定见文件头）。`本周` = 与今天同一日历周、且不是今天/昨天。
    private static func dayBucket(for date: Date, now: Date = .now, calendar: Calendar = .current) -> String {
        if calendar.isDateInToday(date) { return "今天" }
        if calendar.isDateInYesterday(date) { return "昨天" }
        if let week = calendar.dateInterval(of: .weekOfYear, for: now), week.contains(date) { return "本周" }
        return "更早"
    }
}

// MARK: - 首页与本页共用的会话行判据
//
// 放在这里而不是 AppStore：都是「视图怎么读数据」的投影，不改业务状态。
// 01 屏的 sessionRow 与 02 屏的 DSHSessionRow 都调这两个函数，保证两屏判据一致。

/// 行右列状态：审批/问答两条 pending 通道（「要不要动手」的直接体现）。
@MainActor
func sessionRowStatusKind(for sessionID: String, store: AppStore) -> DSHStatusKind? {
    if store.pendingApprovalRequests.contains(where: { $0.sessionId == sessionID }) { return .waiting }
    if store.pendingQuestionRequests.contains(where: { $0.sessionId == sessionID }) { return .question }
    if store.bridgePendingQuestions.contains(where: { $0.sessionId == sessionID }) { return .question }
    return nil
}

/// 行首状态圆点用的完整判据：待办优先（审批 > 问答），否则运行中才给 `.running`。
@MainActor
/// 与上面的「右侧胶囊」判据分开：胶囊只在待办时出现（既有行为不变），
/// 圆点还要覆盖「运行中」（用户要求：运行状态要有可见锚点）。
func sessionRowDotKind(for session: SessionSummary, store: AppStore) -> DSHStatusKind? {
    if let pending = sessionRowStatusKind(for: session.id, store: store) { return pending }
    return session.isRunning ? .running : nil
}

/// 次要信息位 =「模式 · N 轮 · M 步」。缺哪段省哪段，全缺返回 nil（整行不显示，绝不编 0）。
@MainActor
func sessionRowSecondaryText(for session: SessionSummary, store: AppStore) -> String? {
    var parts: [String] = []
    if let preset = session.agentPreset {
        parts.append(store.agentPresets.first { $0.id == preset }?.displayName
            ?? L10n.presetModeName(for: preset))
    }
    if let stats = store.sessionStatsSnapshots[session.id]?.stats {
        if let turns = stats.turns { parts.append("\(turns) 轮") }
        if let steps = stats.steps { parts.append("\(steps) 步") }
    }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

// MARK: - 「全部 N 个」的数据源
//
// 与 01 屏「最近 5 条」同一个作用域（未分组 / 当前工作区 / 全部），只是不截断、不过搜索。
extension AppStore {
    /// 工作区范围内的**全量**会话（未截断、未按搜索过滤）。
    var workspaceScopedSessions: [SessionSummary] {
        let scoped: [SessionSummary]
        if isUngroupedWorkspaceSelected {
            scoped = ungroupedSessions
        } else if let workspace = activeWorkspace {
            let ids = Set(workspace.sessionIds)
            scoped = historySessions.filter { ids.contains($0.id) }
        } else {
            scoped = historySessions
        }
        return scoped.filter { !archivedSessionIds.contains($0.id) }
    }
}

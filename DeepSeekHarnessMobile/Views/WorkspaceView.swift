import SwiftUI
import AVFoundation

struct WorkspaceView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorScheme) private var colorScheme
    /// 主题感知色板：前景/容器色统一从这里取（浅色下墨色、深色下白色叠加）。
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var hosts: MultiGatewayStore
    let onOpenSession: (SessionSummary) -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void
    let onPlugins: () -> Void
    let onScheduledTasks: () -> Void
    /// 团队页与 bridge 聊天：App 级单例（MultiGatewayStore 持有），这里只观察。
    @ObservedObject var bridgeTeam: BridgeTeamAdapter
    @State private var searchQuery = ""
    @State private var renamingSession: SessionSummary?
    @State private var renamedTitle = ""
    @State private var archivingSession: SessionSummary?
    @State private var connectionIsReady = false
    @State private var showsDirectoryBrowser = false
    @State private var showsQRScanner = false
    @State private var showsManualPairing = false
    @State private var showsTeam = false
    @State private var bridgeChat: BridgeChatTarget?
    /// Bot tab 的新数据源：网关团队帧（WS `/mobile/v1/team`）。拿不到数据时回退旧 bridge 渲染。
    /// 团队页（TeamView）走同一个 feed 类型；这次把用户真正会看的 Bot tab 也接上，
    /// 否则「名册混排 / 实时状态」只在没人能打开的团队页里。
    @StateObject private var gatewayTeam = GatewayTeamFeed()
    @State private var drawerOffset: CGFloat = 0
    @State private var drawerDragStart: CGFloat?
    @State private var drawerFeedback = WorkspaceDrawerFeedback()
    @GestureState private var drawerClosingGestureActive = false
    @FocusState private var sessionSearchIsFocused: Bool

    var body: some View {
        GeometryReader { geometry in
            let drawerWidth = min(geometry.size.width * 0.76, 360)
            let progress = min(max(drawerOffset / max(drawerWidth, 1), 0), 1)
            let dimProgress = min(max((progress - 0.45) / 0.55, 0), 1)
            let dimOpacity = 0.16 * dimProgress * dimProgress * (3 - 2 * dimProgress)
            let topInset = geometry.safeAreaInsets.top
            let fullHeight = geometry.size.height + topInset + geometry.safeAreaInsets.bottom

            ZStack(alignment: .leading) {
                (colorScheme == .dark
                    ? Color(red: 36.0 / 255, green: 36.0 / 255, blue: 38.0 / 255)
                    : Color.white)
                drawerContent(progress: progress, topInset: topInset, width: drawerWidth)
                    .frame(width: drawerWidth, height: fullHeight, alignment: .topLeading)
                    .highPriorityGesture(drawerClosingGesture(width: drawerWidth))

                workspaceContent(
                    openDrawer: { openDrawer(width: drawerWidth) },
                    size: geometry.size,
                    safeArea: geometry.safeAreaInsets,
                    drawerWidth: drawerWidth
                )
                    .frame(width: geometry.size.width, height: fullHeight)
                    .background(DSHColor.navy)
                    .overlay {
                        Color.black.opacity(dimOpacity)
                            .allowsHitTesting(false)
                    }
                    // B4 候选 2：这组「剪裁 + 离屏合成 + 双层实时阴影」会在呈现（push/Menu）
                    // 强制对来源做快照时把主线程拖住；sheet 呈现不走这条快照路径，正好解释
                    // 「sheet 不卡、push 与 Menu 都卡」。诊断开关可整体关掉（进度为 0 时视觉等价）。
                    .modifier(WorkspaceCardComposition(progress: progress))
                    .overlay(alignment: .leading) {
                        if progress > 0.98 || drawerDragStart != nil {
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture { closeDrawer() }
                                .highPriorityGesture(drawerClosingGesture(width: drawerWidth))
                                .accessibilityLabel("关闭侧边栏")
                                .accessibilityAddTraits(.isButton)
                        }
                    }
                    .offset(x: drawerOffset)
            }
            .frame(width: geometry.size.width, height: fullHeight)
            .offset(y: -topInset)
            .onChange(of: geometry.size) { _ in
                // 旋转或调整分屏后清理旧拖拽距离，避免侧栏停在半开位置。
                drawerFeedback.endDrag(cancelled: true)
                drawerDragStart = nil
                drawerOffset = progress > 0.5 ? drawerWidth : 0
            }
            .onChange(of: drawerClosingGestureActive) { active in
                if !active {
                    finishDrawerDrag(translation: 0, velocity: 0, cancelled: true, width: drawerWidth)
                }
            }
        }
        // B6：这里原来是 `.foregroundStyle(.white)`，浅色主题下会让所有「没显式指定颜色」的
        // 文字/图标（Bot 列表行、空态文案、抽屉标题等）变成白字白底，直接看不见。
        // 默认前景改走色板：深色仍是白色，浅色是近黑墨蓝。
        .background((colorScheme == .dark ? DSHColor.navy : Color(red: 0.96, green: 0.98, blue: 1.0)).ignoresSafeArea())
        .onChange(of: drawerOffset) { value in
            if value > 0 { sessionSearchIsFocused = false }
        }
        .foregroundStyle(palette.ink(1))
        .onAppear {
            store.refreshRemoteState()
            gatewayTeam.start()
        }
        .onDisappear { gatewayTeam.stop() }
        .onDisappear {
            drawerFeedback.endDrag(cancelled: true)
            drawerDragStart = nil
        }
        .onReceive(store.gateway.$state) { connectionIsReady = $0.isConnected }
        .alert("重命名会话", isPresented: Binding(
            get: { renamingSession != nil },
            set: { if !$0 { renamingSession = nil } }
        )) {
            TextField("会话名称", text: $renamedTitle)
            Button("取消", role: .cancel) { renamingSession = nil }
            Button("保存") {
                if let session = renamingSession { store.renameSession(session.id, title: renamedTitle) }
                renamingSession = nil
            }
            .disabled(renamedTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .alert("删除会话？", isPresented: Binding(
            get: { archivingSession != nil },
            set: { if !$0 { archivingSession = nil } }
        )) {
            Button("取消", role: .cancel) { archivingSession = nil }
            Button("删除", role: .destructive) {
                if let session = archivingSession { store.archiveSession(session.id) }
                archivingSession = nil
            }
        } message: {
            Text("会话将被归档并从列表隐藏，历史记录会保留。")
        }
        .onChange(of: searchQuery) { value in store.search(value) }
        .sheet(isPresented: $showsDirectoryBrowser) {
            DirectoryBrowserSheet()
                .environmentObject(store)
        }
        .fullScreenCover(isPresented: $showsQRScanner) {
            GatewayQRScannerView(
                onCode: handleScannedCode,
                onCancel: { showsQRScanner = false },
                onFailure: { message in
                    showsQRScanner = false
                    store.lastError = message
                }
            )
        }
        .sheet(isPresented: $showsManualPairing) {
            ManualGatewayPairingSheet(gateway: hosts.pairingStore?.gateway ?? store.gateway)
                .environmentObject(store)
        }
        .sheet(item: $bridgeChat) { target in
            BridgeConversationView(title: target.row.name, engine: target.engine)
        }
        .sheet(isPresented: $showsTeam) {
            TeamView(team: bridgeTeam) { sessionId in
                // P1：团队页只做入口；命中现有 gateway 会话则复用既有聊天链路。
                guard let session = store.sessions.first(where: { $0.id == sessionId }) else {
                    return false
                }
                showsTeam = false
                store.lastError = nil
                onOpenSession(session)
                return true
            }
        }
    }

    private func workspaceContent(
        openDrawer: @escaping () -> Void,
        size: CGSize,
        safeArea: EdgeInsets,
        drawerWidth: CGFloat
    ) -> some View {
        let usesColumns = AdaptiveLayout.workspaceUsesColumns(
            size: size, accessibilityText: dynamicTypeSize.isAccessibilitySize
        )
        return ZStack {
            DeepOceanBackground()
                .contentShape(Rectangle())
                .onTapGesture { sessionSearchIsFocused = false }
            ScrollView {
                VStack(alignment: .leading, spacing: usesColumns ? 40 : 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        header(openDrawer: openDrawer).id("workspace-header")
                        GatewaySwitcherBar()
                    }
                    if usesColumns {
                        HStack(alignment: .top, spacing: 48) {
                            workspaceActions
                                .frame(width: min(440, size.width * 0.38))
                            recentSessions
                                .frame(maxWidth: .infinity)
                        }
                    } else {
                        Spacer(minLength: 44)
                        workspaceActions
                        recentSessions
                    }
                }
                .padding(.horizontal, usesColumns ? 40 : 22)
                .padding(.leading, safeArea.leading)
                .padding(.trailing, safeArea.trailing)
                .padding(.top, safeArea.top + 18)
                .padding(.bottom, safeArea.bottom + 24)
                .readableContentWidth(AdaptiveLayout.workspaceWidth)
                .background {
                    DrawerPanGestureAttachment(
                        shouldBegin: { translation in
                            guard abs(translation.x) > abs(translation.y) * 1.2 else { return false }
                            return drawerOffset > 0 ? translation.x < 0 : translation.x > 0
                        },
                        onChanged: { translation in
                            updateDrawerDrag(translation: translation, width: drawerWidth)
                        },
                        onEnded: { translation, velocity, cancelled in
                            finishDrawerDrag(
                                translation: translation, velocity: velocity,
                                cancelled: cancelled, width: drawerWidth
                            )
                        }
                    )
                }
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .scrollDisabled(drawerDragStart == nil && drawerOffset > 0)
        }
        // A tap handled by empty layout content does not reach the background
        // layer. Keep a container-level fallback; controls retain their own
        // actions and the search field consumes its tap while becoming focused.
        .onTapGesture { sessionSearchIsFocused = false }
    }

    private var workspaceActions: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 7) {
                Text("探索未至之境")
                    .appFont(.system(size: 32, weight: .bold))
                if connectionIsReady {
                    Text("已连接Deepseek Harness预览版")
                        .appFont(.subheadline).foregroundStyle(palette.ink(0.65))
                }
            }
            .id("workspace-hero")
            primaryTabPicker.id("workspace-primary-tab")
            if primaryTab == .sessions {
                pairingEntryCard
                workspaceCard.id("workspace-card")
                newSessionButton.id("workspace-new-session")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// U5：首页主分段。「会话」= 原版分组列表（含新建/搜索/归档）；「Bot」= 团队（bots + 群聊）。
    private enum PrimaryTab: String, CaseIterable {
        case sessions = "会话"
        case bots = "Bot"
    }
    @AppStorage("workspace.primaryTab") private var primaryTabRaw = PrimaryTab.sessions.rawValue
    private var primaryTab: PrimaryTab {
        get { PrimaryTab(rawValue: primaryTabRaw) ?? .sessions }
        nonmutating set { primaryTabRaw = newValue.rawValue }
    }

    /// 自绘两段控件。
    /// 不用系统 Picker(.segmented)：在深色自定义主题 + 滚动容器里它不吃点击（真机表现为
    /// 「Bot 是灰的、点了不切换」），换成两个显式 Button，命中区域与选中态都自己控制。
    private var primaryTabPicker: some View {
        HStack(spacing: 0) {
            ForEach(PrimaryTab.allCases, id: \.self) { tab in
                let selected = primaryTab == tab
                Button {
                    guard !selected else { return }
                    withAnimation(.easeInOut(duration: 0.18)) { primaryTab = tab }
                } label: {
                    Text(tab.rawValue)
                        .appFont(.subheadline.weight(selected ? .semibold : .regular))
                        // B6：选中态以前写死白色，浅色主题下药丸是近白底 → 白字不可见。
                        .foregroundStyle(selected ? palette.ink(1) : palette.ink(0.6))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .fill(palette.surface(0.18))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                                            .stroke(palette.stroke(0.18), lineWidth: 0.5)
                                    )
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.rawValue)
                .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
            }
        }
        .padding(4)
        // 用户反馈「灰底比周围白卡片重」：ink() 的浅色地板是给文字用的，填充改走 veil()。
        .background(palette.veil(0.07), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("首页分组")
    }

    private var recentSessions: some View {
        LazyVStack(alignment: .leading, spacing: 18) {
            sessionsHeader.id("workspace-sessions-header")
            sessionSearch.id("workspace-session-search")
            if bridgeTeam.connectionIssue != nil || (bridgeListReady && bridgeTeam.mirror?.isOffline == true) {
                bridgeOfflineBanner
            }
            if primaryTab == .bots {
                // 数据源优先级：网关团队帧（新）→ bridge 镜像（旧宿主兼容）→ 诊断卡。
                if gatewayTeamReady {
                    gatewayTeamSection
                } else if bridgeListReady {
                    bridgeSessionsSection
                } else {
                    botDiagnosticsCard
                }
            } else if displayedSessions.isEmpty {
                emptySessions.id("workspace-sessions-empty")
            } else {
                sessionsList
            }
        }
    }

    /// 主列表是否由 bridge 主导：已配对（READY/LOADING/OFFLINE 都算）即用 bridge 数据；
    /// 只有未配对/无镜像时才回退旧 gateway 列表，避免已配对状态下误触发回退。
    /// 离线出口：自动回连在后台跑着；这里给出「立即重试」与「更换服务器地址」两条人工路径。
    private var bridgeOfflineBanner: some View {
        let issue = bridgeTeam.connectionIssue
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .foregroundStyle(.orange)
                Text(issue?.headline ?? "连接已断开").appFont(.subheadline.weight(.medium))
                Spacer()
            }
            Text(issue?.detail ?? "正在自动重连…")
                .appFont(.caption)
                .foregroundStyle(palette.ink(0.6))
            HStack(spacing: 10) {
                Button {
                    Task { await bridgeTeam.refreshNow() }
                } label: {
                    Text("立即重试").appFont(.caption.weight(.semibold))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(palette.surface(0.1), in: Capsule())

                // B3：配对统一回到网关（扫码为主、手输地址 + 配对码为辅）。
                Button {
                    store.lastError = nil
                    showsManualPairing = true
                } label: {
                    Text("重新配对").appFont(.caption.weight(.semibold))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(palette.surface(0.1), in: Capsule())
            }
        }
        .padding(14)
        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(.orange.opacity(0.25)))
    }

    // MARK: - Bot tab（网关团队帧）

    private var gatewayTeamReady: Bool {
        switch gatewayTeam.phase {
        case .live: return true
        case .connecting, .offline: return !gatewayTeam.rows.isEmpty
        case .idle, .needsPairing: return false
        }
    }

    private var gatewayBotNames: [String: String] {
        var names: [String: String] = [:]
        for row in gatewayTeam.rows where row.kind == .bot { names[row.id] = row.name }
        return names
    }

    /// 需求 1/3/4：主 Bot 最上 → 置顶 → 网关 `order` 倒序；Bots 与群聊混排；
    /// 名字下挂实时状态；`degraded` 时显式说明「实时状态暂不可用」，绝不显示成「全部空闲」。
    private var gatewayTeamSection: some View {
        LazyVStack(spacing: 0) {
            if gatewayTeam.degraded {
                Text("实时状态暂不可用：网关目前只拿到名册（名字/角色/成员）。干活中、等你回答、等你审批要等桌面端发布团队服务后才显示。")
                    .appFont(.caption)
                    .foregroundStyle(palette.ink(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(palette.surface(0.05), in: RoundedRectangle(cornerRadius: 12))
                    .padding(.bottom, 6)
            }
            if case .offline(let reason) = gatewayTeam.phase {
                Text("团队连接中断：\(reason)")
                    .appFont(.caption).foregroundStyle(palette.caution)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4).padding(.bottom, 6)
            }
            let rows = gatewayTeam.sortedRows.filter { row in
                let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
                return query.isEmpty || row.name.localizedCaseInsensitiveContains(query)
            }
            if rows.isEmpty {
                Text("暂无 Bot 或群聊。桌面端有 Bot 团队后这里会出现。")
                    .appFont(.subheadline).foregroundStyle(palette.ink(0.5))
                    .frame(maxWidth: .infinity, alignment: .leading).padding(18)
                    .background(palette.surface(0.04), in: RoundedRectangle(cornerRadius: 15))
            } else {
                ForEach(rows) { row in
                    Button {
                        sessionSearchIsFocused = false
                        openGatewayTeamRow(row)
                    } label: {
                        gatewayTeamRow(row)
                    }
                    .buttonStyle(.plain)
                    .id("gateway-team-\(row.id)")
                    Divider().overlay(palette.stroke(0.1)).padding(.leading, 18)
                }
            }
        }
    }

    private func gatewayTeamRow(_ row: GatewayTeamFeed.Row) -> some View {
        HStack(spacing: 11) {
            // 头像（静态字段）：有 data URL 用它，没有就回落默认块（色块 + 首字母），**不留空白**。
            gatewayAvatar(row)
                .overlay(alignment: .bottomTrailing) {
                    Circle()
                        .fill(statusTint(for: row))
                        .frame(width: 9, height: 9)
                        .overlay(Circle().stroke(palette.surface(0.9), lineWidth: 1.5))
                }
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row.name).lineLimit(1).appFont(.subheadline.weight(.medium))
                    if row.main { Text("主").appFont(.caption2).foregroundStyle(palette.ink(0.55)) }
                    if row.pinned { Image(systemName: "pin.fill").appFont(.caption2).foregroundStyle(palette.ink(0.4)) }
                }
                if let status = gatewayTeam.statusText(for: row, botNames: gatewayBotNames) {
                    Text(status).appFont(.caption).foregroundStyle(statusForeground(for: row)).lineLimit(1)
                } else {
                    Text(row.kind == .room
                         ? gatewayTeam.roomSubtitle(for: row, botNames: gatewayBotNames)
                         : (row.role ?? "Bot"))
                        .appFont(.caption2).foregroundStyle(palette.ink(0.42)).lineLimit(1)
                }
                if row.kind == .room, let preview = row.preview, !preview.isEmpty {
                    Text(preview).appFont(.caption2).foregroundStyle(palette.ink(0.35)).lineLimit(1)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    /// 头像：Bot 用 avatar 或默认块；群聊用**成员头像堆叠**（room 没有 avatar 字段，用 members join）。
    @ViewBuilder
    private func gatewayAvatar(_ row: GatewayTeamFeed.Row, size: CGFloat = 38) -> some View {
        if row.kind == .room {
            let members = gatewayTeam.memberAvatars(for: row, limit: 3)
            ZStack(alignment: .leading) {
                if members.isEmpty {
                    Circle()
                        .fill(Color(red: 0.06, green: 0.27, blue: 0.66))
                        .frame(width: size, height: size)
                        .overlay {
                            Image(systemName: "person.2.fill")
                                .appFont(.system(size: size * 0.38, weight: .semibold))
                                .foregroundStyle(.white)
                        }
                } else {
                    ForEach(Array(members.enumerated()), id: \.element.id) { index, member in
                        avatarBlock(member, size: size * 0.72)
                            .overlay(Circle().stroke(palette.surface(0.9), lineWidth: 1.5))
                            .offset(x: CGFloat(index) * size * 0.42)
                            .zIndex(Double(3 - index))
                    }
                }
            }
            .frame(width: size + CGFloat(max(0, members.count - 1)) * size * 0.42, height: size, alignment: .leading)
        } else {
            avatarBlock(row, size: size)
        }
    }

    /// 单个头像块：有 data URL 就画图，否则「深色不透明底 + 白色首字母」（白字 ≥4.6:1，方案 A）。
    @ViewBuilder
    private func avatarBlock(_ row: GatewayTeamFeed.Row, size: CGFloat) -> some View {
        // 三层兜底（glyph → image → shape 符号）统一在 TeamAvatarView 里，别再分叉。
        TeamAvatarView(row: row, size: size)
    }

    private func statusTint(for row: GatewayTeamFeed.Row) -> Color {
        if !row.pending.isEmpty || row.placeholderCards > 0 || !row.approvals.isEmpty { return palette.caution }
        if gatewayTeam.isLive(row) { return palette.success }
        return palette.veil(0.35)
    }

    private func statusForeground(for row: GatewayTeamFeed.Row) -> Color {
        if !row.pending.isEmpty || row.placeholderCards > 0 || !row.approvals.isEmpty { return palette.caution }
        if gatewayTeam.isLive(row) { return palette.success }
        return palette.ink(0.5)
    }

    /// 点行直接进对话：先按 id 找 bridge 行（聊天仍走 bridge），找不到就用 sessionId 试网关会话。
    private func openGatewayTeamRow(_ row: GatewayTeamFeed.Row) {
        if let bridgeRow = bridgeTeam.mirror.map({ mirror in
            (row.kind == .room ? mirror.rooms : mirror.bots).first { $0.id == row.id }
        }) ?? nil {
            openBridgeConversation(bridgeRow)
            return
        }
        if let session = store.sessions.first(where: { $0.id == row.sessionId }) {
            onOpenSession(session)
            return
        }
        store.lastError = "这条对话还没同步到会话列表，稍后重试。"
    }

    /// 诊断逃逸口：`dsh.diag.noCompositing` 可关掉离屏合成 + 双层阴影。
    /// **有意保留**（UI 已无开关，见 `SettingsDiagnostics` 注释）；默认 false = 正常形态。
    private struct WorkspaceCardComposition: ViewModifier {
        let progress: CGFloat
        @AppStorage(SettingsDiagnostics.compositingKey) private var disabled = false

        func body(content: Content) -> some View {
            if disabled {
                content
            } else {
                content
                    .clipShape(RoundedRectangle(cornerRadius: 48 * progress, style: .continuous))
                    .compositingGroup()
                    .shadow(
                        color: .black.opacity(0.12 * progress),
                        radius: 9 * progress,
                        x: -2 * progress,
                        y: 0
                    )
                    .shadow(
                        color: .black.opacity(0.30 * progress),
                        radius: 26 * progress,
                        x: -4 * progress,
                        y: 0
                    )
            }
        }
    }

    /// 凹槽类浅色底：**不透明**「白 + ink 5%」= #F3F3F4。
    /// 与白卡片亮度比 1.11:1（目标 1.09–1.15；`surface()` 在白底上会到 1.00–1.01，方向反了）。
    enum WorkspaceCardFill {
        static let sunken = Color(red: 243.0 / 255, green: 243.0 / 255, blue: 244.0 / 255)
    }

    private var bridgeListReady: Bool {
        guard let mirror = bridgeTeam.mirror else { return false }
        return !mirror.needsPairing
    }

    private var bridgeRows: [BridgeTeamRow] {
        guard let mirror = bridgeTeam.mirror else { return [] }
        let rows = mirror.bots + mirror.rooms
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return rows }
        return rows.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    private var bridgeSessionsSection: some View {
        LazyVStack(spacing: 0) {
            if bridgeRows.isEmpty {
                Text("暂无会话。在桌面端与 Bot 对话后，这里会出现会话。")
                    .appFont(.subheadline).foregroundStyle(palette.ink(0.5))
                    .frame(maxWidth: .infinity, alignment: .leading).padding(18)
                    .background(palette.surface(0.04), in: RoundedRectangle(cornerRadius: 15))
            } else {
                ForEach(bridgeRows) { row in
                    Button {
                        sessionSearchIsFocused = false
                        openBridgeConversation(row)
                    } label: {
                        bridgeSessionRow(row)
                    }
                    .buttonStyle(.plain)
                    .id("bridge-session-\(row.id)")
                    Divider()
                        .overlay(palette.stroke(0.1))
                        .padding(.leading, 18)
                }
            }
        }
    }

    private func bridgeSessionRow(_ row: BridgeTeamRow) -> some View {
        HStack(spacing: 11) {
            Circle()
                // 图形判据 ≥3:1：等待用压暗绿，未等待用 stroke 档（原来 ink(0.35) 在浅色下是 0.71 的近黑点）。
                .fill(row.waiting == true ? palette.success : palette.veil(0.35))
                .frame(width: 7, height: 7)
                .shadow(color: row.waiting == true ? palette.success : .clear, radius: 5)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.name).lineLimit(1).appFont(.subheadline.weight(.medium))
                Text(bridgeRowSubtitle(row)).appFont(.caption2).foregroundStyle(palette.ink(0.42)).lineLimit(1)
            }
            Spacer()
            if row.pinned == true {
                Text("置顶").appFont(.caption2).foregroundStyle(palette.ink(0.55))
            } else if row.hidden == true {
                Text("隐藏").appFont(.caption2).foregroundStyle(palette.ink(0.4))
            }
        }
        .opacity(row.hidden == true ? 0.55 : 1)
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    private func bridgeRowSubtitle(_ row: BridgeTeamRow) -> String {
        if let role = row.role, !role.isEmpty { return role }
        if let members = row.members, !members.isEmpty { return members.joined(separator: "、") }
        return row.isRoom ? "群聊" : "Bot"
    }

    private func openBridgeConversation(_ row: BridgeTeamRow) {
        // U5：一次配对已顺带下发网关凭证——优先用原版聊天窗口（工具卡/审批卡齐全）。
        if let session = store.sessions.first(where: { $0.id == row.sessionId }) {
            onOpenSession(session)
            return
        }
        guard let engine = bridgeTeam.chatEngine(conversationId: row.id, sessionId: row.sessionId) else {
            store.lastError = "尚未完成配对或会话不可用，请先在团队页完成配对。"
            return
        }
        bridgeChat = BridgeChatTarget(row: row, engine: engine)
    }

    private func header(openDrawer: @escaping () -> Void) -> some View {
        HStack {
            Button(action: openDrawer) {
                // B6：品牌字标以前默认 onDarkBackground=true（白字 + 白色反相字标），
                // 浅色主题下白底白字整块看不见。改为跟随当前主题。
                HarnessMark(onDarkBackground: colorScheme == .dark,
                            width: dynamicTypeSize.isAccessibilitySize ? 150 : 216)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
                .layoutPriority(-1)
                .buttonStyle(.plain)
                .accessibilityLabel("打开侧边栏")
            Spacer()
            // U5：原版连接入口常驻——扫码 / 手动输入配对信息（已连接时也可重新配对）。
            authenticationMenu
                .padding(.trailing, 6)
            settingsButton
                // Match the outer glass circle to the workspace cards' trailing edge.
                .padding(.trailing, -4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func drawerContent(progress: CGFloat, topInset: CGFloat, width: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HarnessMark(onDarkBackground: colorScheme == .dark, width: min(width - 48, 216))
                .padding(.leading, 12)
                .padding(.bottom, 16)

            drawerItem("插件", icon: {
                Image("DshPluginPinwheel")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 22, height: 22)
                    .frame(width: 24)
            }, action: { selectDrawerItem(onPlugins) })
            drawerItem("定时任务", icon: {
                Image(systemName: "clock")
                    .appFont(.system(size: 20, weight: .medium))
                    .frame(width: 24)
            }, action: { selectDrawerItem(onScheduledTasks) })
            // U 单：旧 gateway 连接降级为备用入口，主流程只走 bridge 一次配对。
            drawerItem("备用主机", icon: {
                Image(systemName: "externaldrive.connected.to.line.below")
                    .appFont(.system(size: 20, weight: .medium))
                    .frame(width: 24)
            }, action: {
                closeDrawer()
                showsQRScanner = true
            })
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, topInset + 18)
        .foregroundStyle(Color(uiColor: .label))
        .opacity(0.6 + 0.4 * progress)
        .scaleEffect(0.9 + 0.1 * progress, anchor: .leading)
        .accessibilityHidden(progress == 0)
    }

    private func drawerItem<Icon: View>(
        _ title: String,
        @ViewBuilder icon: () -> Icon,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                icon()
                Text(title).appFont(.system(size: 17))
                Spacer(minLength: 0)
            }
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func openDrawer(width: CGFloat) {
        sessionSearchIsFocused = false
        settleDrawer(to: width)
    }

    private func drawerClosingGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .global)
            .updating($drawerClosingGestureActive) { value, active, _ in
                active = drawerDragStart != nil || (
                    drawerOffset > 0 && value.translation.width < 0 &&
                    abs(value.translation.width) > abs(value.translation.height) * 1.2
                )
            }
            .onChanged { value in
                // 尚未锁定方向时只接受左滑，拖动开始后允许反向拉回。
                guard drawerDragStart != nil || (
                    drawerOffset > 0 && value.translation.width < 0 &&
                    abs(value.translation.width) > abs(value.translation.height) * 1.2
                ) else { return }
                updateDrawerDrag(translation: value.translation.width, width: width)
            }
            .onEnded { value in
                finishDrawerDrag(
                    translation: value.translation.width, velocity: value.velocity.width,
                    cancelled: false, width: width
                )
            }
    }

    private func updateDrawerDrag(translation: CGFloat, width: CGFloat) {
        if drawerDragStart == nil {
            drawerDragStart = drawerOffset
            drawerFeedback.beginDrag()
        }
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            drawerOffset = min(max((drawerDragStart ?? 0) + translation, 0), width)
        }
    }

    private func finishDrawerDrag(translation: CGFloat, velocity: CGFloat, cancelled: Bool, width: CGFloat) {
        guard let start = drawerDragStart else { return }
        drawerDragStart = nil
        drawerFeedback.endDrag(cancelled: cancelled)
        let open: Bool
        if cancelled {
            open = start > width * 0.5
        } else if start == 0 {
            open = translation > 24 || velocity > 250
        } else {
            open = translation >= -24 && velocity >= -250
        }
        settleDrawer(to: open ? width : 0, feedback: false)
    }

    private func closeDrawer() {
        settleDrawer(to: 0)
    }

    private func settleDrawer(to target: CGFloat, feedback: Bool = true) {
        if feedback { drawerFeedback.tap() }
        if reduceMotion {
            var transaction = Transaction(animation: nil)
            transaction.disablesAnimations = true
            withTransaction(transaction) { drawerOffset = target }
        } else {
            withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                drawerOffset = target
            }
        }
    }

    private func selectDrawerItem(_ action: @escaping () -> Void) {
        // Start the destination push immediately. Reset the workspace behind it
        // without a closing animation so Back reveals the main page.
        drawerFeedback.tap()
        action()
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            drawerOffset = 0
        }
    }

    @ViewBuilder
    private var authenticationMenu: some View {
        GatewayAuthenticationMenu(
            gateway: store.gateway,
            onScan: {
                store.lastError = nil
                showsQRScanner = true
            },
            onManualEntry: {
                store.lastError = nil
                showsManualPairing = true
            }
        )
    }

    @ViewBuilder
    private var settingsButton: some View {
        headerButton(systemName: "gearshape.fill", accessibilityLabel: String(localized: "设置"), action: onSettings)
    }

    @ViewBuilder
    private func headerButton(systemName: String, accessibilityLabel: String, action: @escaping () -> Void) -> some View {
        let label = headerButtonLabel(systemName: systemName)
        if #available(iOS 26.0, *) {
            Button(action: action) { label }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .accessibilityLabel(accessibilityLabel)
        } else {
            Button(action: action) { label }
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(palette.stroke(0.25), lineWidth: 0.8))
                .accessibilityLabel(accessibilityLabel)
        }
    }

    private func headerButtonLabel(systemName: String) -> some View {
        Image(systemName: systemName)
            .appFont(.system(size: 17, weight: .semibold))
            .frame(width: 40, height: 40)
            .contentShape(Circle())
    }

    private var workspaceCard: some View {
        Menu {
            Button {
                store.selectUngroupedWorkspace()
            } label: {
                Label {
                    Text("未分组")
                } icon: {
                    Image(systemName: store.isUngroupedWorkspaceSelected ? "checkmark.circle.fill" : "tray")
                }
            }

            if !store.workspaces.isEmpty { Divider() }

            ForEach(store.workspaces) { workspace in
                Button {
                    store.selectWorkspace(workspace)
                } label: {
                    Label {
                        Text(workspace.title)
                    } icon: {
                        Image(systemName: workspace.id == store.activeWorkspace?.id ? "checkmark.circle.fill" : "folder")
                    }
                }
            }

            Divider()

            Button {
                showsDirectoryBrowser = true
            } label: {
                Label("添加工作区", systemImage: "plus")
            }
        } label: {
            workspaceCardLabel
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "选择工作区"))
    }

    private var workspaceCardLabel: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder").foregroundStyle(.blue).appFont(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(workspaceDisplayTitle).appFont(.subheadline.weight(.semibold)).lineLimit(2)
                Text(workspaceDisplayPath).appFont(.caption).foregroundStyle(palette.ink(0.55)).lineLimit(1)
            }
            Spacer()
            GatewayConnectionIndicator(gateway: store.gateway)
            Image(systemName: "chevron.down").appFont(.caption).foregroundStyle(palette.ink(0.55))
        }
        .padding(16)
        .background(palette.surface(0.055), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(palette.stroke(0.14)))
        .contentShape(RoundedRectangle(cornerRadius: 15))
    }

    private var sessionsHeader: some View {
        HStack {
            Text("最近会话").appFont(.headline)
            Spacer()
            GatewayConnectionStatusText(gateway: store.gateway)
        }
    }

    private func handleScannedCode(_ rawValue: String) {
        do {
            try store.pair(usingQRCode: rawValue)
            showsQRScanner = false
        } catch {
            showsQRScanner = false
            store.lastError = error.localizedDescription
        }
    }

    private var sessionSearch: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(palette.ink(0.68))
            TextField(
                "",
                text: $searchQuery,
                prompt: Text("搜索会话内容")
                    .foregroundColor(palette.ink(0.58))
            )
            .foregroundStyle(palette.ink(0.92))
            .tint(palette.ink(1))
            .textInputAutocapitalization(.never)
            .focused($sessionSearchIsFocused)
            .submitLabel(.search)
            .onSubmit { sessionSearchIsFocused = false }
        }
        .padding(.horizontal, 12).frame(minHeight: 42)
        .background(palette.surface(0.06), in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(palette.stroke(0.1)))
    }

    /// U5：原版连接界面恢复为主入口。任何一边配对成功都会同时拿到两套凭证，
    /// 所以这里既是「原版配对」，也顺带把 Bot tab 接上。
    @ViewBuilder
    private var pairingEntryCard: some View {
        // 只要网关没连上就露出入口：残留的 bridge 会话不该把「重新配对」藏起来。
        if !connectionIsReady {
            VStack(alignment: .leading, spacing: 10) {
                Text("连接桌面端")
                    .appFont(.headline)
                Text("扫码或在手机上输入桌面端显示的配对码；配对成功后会自动带上 Bot 团队。")
                    .appFont(.caption)
                    .foregroundStyle(palette.ink(0.6))
                HStack(spacing: 10) {
                    Button {
                        store.lastError = nil
                        showsQRScanner = true
                    } label: {
                        Label("扫码配对", systemImage: "qrcode.viewfinder")
                            .appFont(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(.borderedProminent)

                    Button {
                        store.lastError = nil
                        showsManualPairing = true
                    } label: {
                        Label("手动输入", systemImage: "keyboard")
                            .appFont(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface(0.06), in: RoundedRectangle(cornerRadius: 16))
        }
    }

    /// Bot tab 未就绪时的诊断卡：直接给出 phase / Keychain 会话 / serverUrl，
    /// 以后出问题不用再靠猜或反复索取日志。
    @ViewBuilder
    private var botDiagnosticsCard: some View {
        let info = bridgeTeam.diagnostics()
        VStack(alignment: .leading, spacing: 6) {
            Text("Bot 团队尚未就绪")
                .appFont(.subheadline.weight(.semibold))
            Text("phase：\(info.phase) · 配对态：\(info.pairedState)")
                .appFont(.caption).foregroundStyle(palette.ink(0.6))
            Text("本机会话：\(info.hasStoredSession ? "有" : "无") · serverUrl：\(info.serverUrl ?? "（空）")")
                .appFont(.caption).foregroundStyle(palette.ink(0.6))
            if let error = info.error {
                Text("原因：\(error)")
                    .appFont(.caption).foregroundStyle(.orange)
            }
            // B3：bridge 粘贴路径退役，重配只能走网关（一次配对双凭证）。
            HStack(spacing: 8) {
                Button {
                    store.lastError = nil
                    showsQRScanner = true
                } label: {
                    Label("扫码重配", systemImage: "qrcode.viewfinder").appFont(.caption.weight(.semibold))
                }
                .buttonStyle(.bordered)
                Button {
                    store.lastError = nil
                    showsManualPairing = true
                } label: {
                    Label("手动输入", systemImage: "keyboard").appFont(.caption.weight(.semibold))
                }
                .buttonStyle(.bordered)
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(palette.surface(0.05), in: RoundedRectangle(cornerRadius: 15))
    }

    private var emptySessions: some View {
        Button {
            store.lastError = nil
            showsManualPairing = true
        } label: {
            Text("暂无已知会话。点这里连接桌面端后创建第一个任务。")
                .appFont(.subheadline).foregroundStyle(palette.ink(0.55))
                .frame(maxWidth: .infinity, alignment: .leading).padding(18)
                .background(palette.surface(0.04), in: RoundedRectangle(cornerRadius: 15))
        }
        .buttonStyle(.plain)
    }

    private var displayedSessions: [SessionSummary] {
        guard connectionIsReady else { return [] }
        let workspaceSessions: [SessionSummary]
        if store.isUngroupedWorkspaceSelected {
            workspaceSessions = store.ungroupedSessions
        } else if let workspace = store.activeWorkspace {
            let ids = Set(workspace.sessionIds)
            workspaceSessions = store.historySessions.filter { ids.contains($0.id) }
        } else {
            workspaceSessions = store.historySessions
        }
        guard !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return workspaceSessions.filter { !store.archivedSessionIds.contains($0.id) }
        }
        let ids = Set(store.searchResults.map(\.sessionId))
        return workspaceSessions.filter { !store.archivedSessionIds.contains($0.id) && (ids.contains($0.id) || $0.title.localizedCaseInsensitiveContains(searchQuery)) }
    }

    private var workspaceDisplayTitle: String {
        store.isUngroupedWorkspaceSelected ? String(localized: "未分组") : (store.activeWorkspace?.title ?? "DeepseekHarnessProject")
    }

    private var workspaceDisplayPath: String {
        if store.isUngroupedWorkspaceSelected {
            return String(localized: "workspace.ungrouped.count", defaultValue: "\(store.ungroupedSessions.count) 个未归属会话")
        }
        return store.activeWorkspace?.path ?? String(localized: "通过 Mobile Gateway 连接")
    }

    private var sessionsList: some View {
        LazyVStack(spacing: 0) {
            ForEach(displayedSessions.prefix(12)) { session in
                Button {
                    sessionSearchIsFocused = false
                    onOpenSession(session)
                } label: {
                    sessionRow(session)
                }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            renamedTitle = session.title
                            renamingSession = session
                        } label: { Label("重命名", systemImage: "pencil") }
                        Button(role: .destructive) {
                            archivingSession = session
                        } label: { Label("删除（归档）", systemImage: "archivebox") }
                    }
                    .id("workspace-session-\(session.id)")

                Divider()
                    .overlay(palette.stroke(0.1))
                    .padding(.leading, 18)
            }
        }
    }

    private func sessionRow(_ session: SessionSummary) -> some View {
        HStack(spacing: 11) {
            Circle()
                .fill(session.isRunning ? palette.success : (session.hasUnread ? DSHColor.ocean : palette.veil(0.35)))
                .frame(width: 7, height: 7)
                .shadow(color: session.isRunning ? palette.success : .clear, radius: 5)
            VStack(alignment: .leading, spacing: 3) {
                Text(session.title).lineLimit(1).appFont(.subheadline.weight(.medium))
                Text(String(session.id.prefix(16))).appFont(.caption2.monospaced()).foregroundStyle(palette.ink(0.42))
            }
            Spacer()
            Text(session.isRunning ? String(localized: "运行中") : session.lastActivity.formatted(.relative(presentation: .named)))
                .appFont(.caption).foregroundStyle(session.isRunning ? palette.success : palette.ink(0.48))
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var newSessionButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: onNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.glass(.clear.tint(DSHColor.navy.opacity(0.22))))
            .buttonBorderShape(.roundedRectangle(radius: 18))
            .buttonSizing(.flexible)
        } else {
            Button(action: onNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.plain)
            // 用户反馈「新建会话」灰底偏深：浅色下原来是 thinMaterial + veil(0.08) 双叠，
            // 现在走统一入口 `themedGlassSurface`：浅色实色面 #F3F3F4（WorkspaceCardFill.sunken，
            // 与白卡片 1.11:1、比分段轨道浅一档），不叠 tint；深色仍是
            // ultraThinMaterial + veil(0.08)，逐字不变。
            // 文字继承外层 ink(1)=#0E131A，压在 #F3F3F4 上 16.80:1。
            .themedGlassSurface(radius: 18,
                                tint: palette.veil(0.08),
                                // 凹槽用**不透明** #F3F3F4（与白卡片 1.11:1）；
                                // surface(0.12) 压在白卡片上只有 1.00:1，等于看不见。
                                lightFill: WorkspaceCardFill.sunken,
                                darkMaterial: true,
                                lightShadowOpacity: 0.18)
        }
    }

    private var newSessionButtonLabel: some View {
        HStack(spacing: 10) {
            Image(systemName: "plus").appFont(.system(size: 15, weight: .semibold))
                .frame(width: 28, height: 28)
                .background(palette.surface(0.1), in: Circle())
            Text("新建会话").appFont(.headline)
        }
        .frame(maxWidth: .infinity).frame(minHeight: 38)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// 拖拽开始预热，松手同步反馈；取消和后续动画均不补震。
@MainActor
final class WorkspaceDrawerFeedback {
    private let prepare: () -> Void
    private let impact: () -> Void
    private var dragging = false

    convenience init() {
        let generator = UIImpactFeedbackGenerator(style: .medium)
        self.init(prepare: { generator.prepare() }, impact: { generator.impactOccurred(intensity: 1) })
    }

    init(prepare: @escaping () -> Void, impact: @escaping () -> Void) {
        self.prepare = prepare
        self.impact = impact
    }

    func beginDrag() {
        guard !dragging else { return }
        dragging = true
        prepare()
    }

    func endDrag(cancelled: Bool) {
        guard dragging else { return }
        dragging = false
        if !cancelled { impact() }
    }

    func tap() {
        dragging = false
        prepare()
        impact()
    }
}

/// Installs a directional pan on the workspace scroll view. A rightward pan
/// wins over controls and scrolling; a vertical pan fails here and stays with
/// the scroll view's native recognizer.
private struct DrawerPanGestureAttachment: UIViewRepresentable {
    let shouldBegin: (CGPoint) -> Bool
    let onChanged: (CGFloat) -> Void
    let onEnded: (_ translation: CGFloat, _ velocity: CGFloat, _ cancelled: Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> AttachmentView {
        let view = AttachmentView()
        view.isUserInteractionEnabled = false
        let coordinator = context.coordinator
        view.onHierarchyChange = { [weak view, weak coordinator] in
            DispatchQueue.main.async { [weak view, weak coordinator] in
                guard let view else { return }
                coordinator?.attach(from: view)
            }
        }
        return view
    }

    func updateUIView(_ uiView: AttachmentView, context: Context) {
        context.coordinator.shouldBegin = shouldBegin
        context.coordinator.onChanged = onChanged
        context.coordinator.onEnded = onEnded
        context.coordinator.attach(from: uiView)
    }

    static func dismantleUIView(_ uiView: AttachmentView, coordinator: Coordinator) {
        uiView.onHierarchyChange = nil
        coordinator.detach()
    }

    final class AttachmentView: UIView {
        var onHierarchyChange: (() -> Void)?

        override func didMoveToSuperview() {
            super.didMoveToSuperview()
            onHierarchyChange?()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            onHierarchyChange?()
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var shouldBegin: ((CGPoint) -> Bool)?
        var onChanged: ((CGFloat) -> Void)?
        var onEnded: ((CGFloat, CGFloat, Bool) -> Void)?
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
            pan.cancelsTouchesInView = true
            scrollView.addGestureRecognizer(pan)
            scrollView.panGestureRecognizer.require(toFail: pan)
            self.scrollView = scrollView
            self.pan = pan
        }

        func detach() {
            pan?.isEnabled = false
            if let pan { scrollView?.removeGestureRecognizer(pan) }
            pan = nil
            scrollView = nil
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
            return shouldBegin?(pan.translation(in: pan.view)) ?? false
        }

        @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
            let translation = pan.translation(in: pan.view).x
            switch pan.state {
            case .began, .changed:
                onChanged?(translation)
            case .ended:
                onEnded?(translation, pan.velocity(in: pan.view).x, false)
            case .cancelled, .failed:
                onEnded?(translation, 0, true)
            default:
                break
            }
        }
    }
}

private struct DirectoryBrowserSheet: View {
    @Environment(\.colorScheme) private var colorScheme
    /// 主题感知色板（浅色下墨色、深色下白色叠加）。
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var creatingPath: String?
    @State private var showsCreateDirectoryPrompt = false
    @State private var newDirectoryName = ""
    @State private var highlightedDirectoryPath: String?

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    Section {
                        Button {
                            if let parentPath { store.browseDirectories(path: parentPath) }
                        } label: {
                            directoryRow(icon: "arrowshape.turn.up.left", title: "..", subtitle: String(localized: "返回上一级"))
                        }
                        .disabled(parentPath == nil)

                        ForEach(store.directoryEntries) { entry in
                            Button {
                                store.browseDirectories(path: entry.path)
                            } label: {
                                directoryRow(
                                    icon: entry.hidden ? "folder.badge.questionmark" : "folder",
                                    title: entry.name,
                                    subtitle: entry.hidden ? String(localized: "隐藏目录") : nil
                                )
                            }
                            .id(entry.path)
                            .listRowBackground(
                                ZStack {
                                    Color(uiColor: .secondarySystemGroupedBackground)
                                    if highlightedDirectoryPath == entry.path {
                                        DSHColor.ocean.opacity(0.24)
                                    }
                                }
                            )
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("当前目录")
                            Text(store.directoryPath ?? String(localized: "正在读取…"))
                                .appFont(.caption.monospaced())
                                .textCase(nil)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .allowsHitTesting(!store.directoryIsLoading && !store.directoryCreationIsLoading)
                .overlay {
                    if store.directoryIsLoading && store.directoryEntries.isEmpty {
                        ProgressView(String(localized: "正在读取远程目录…"))
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    createWorkspaceBar
                }
                .navigationTitle("选择工作区目录")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            newDirectoryName = ""
                            showsCreateDirectoryPrompt = true
                        } label: {
                            Label("新建文件夹", systemImage: "folder.badge.plus")
                        }
                        .disabled(store.directoryPath == nil || store.directoryIsLoading || store.directoryCreationIsLoading)
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("取消") { dismiss() }
                    }
                }
                .onChange(of: store.createdDirectoryPathToReveal) { _ in
                    revealCreatedDirectory(using: proxy)
                }
                .onChange(of: store.directoryEntries) { _ in
                    revealCreatedDirectory(using: proxy)
                }
                .task(id: highlightedDirectoryPath) {
                    guard highlightedDirectoryPath != nil else { return }
                    try? await Task.sleep(for: .seconds(1.6))
                    guard !Task.isCancelled else { return }
                    withAnimation(.easeOut(duration: 0.25)) {
                        highlightedDirectoryPath = nil
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackgroundCompat(.ultraThinMaterial)
        .onAppear {
            store.directoryEntries = []
            store.browseDirectories()
        }
        .onChange(of: store.selectedWorkspaceId) { _ in
            guard let creatingPath,
                  store.activeWorkspace?.path == creatingPath else { return }
            dismiss()
        }
        .alert("新建文件夹", isPresented: $showsCreateDirectoryPrompt) {
            TextField("文件夹名称", text: $newDirectoryName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("取消", role: .cancel) {}
            Button("创建") { createDirectory() }
                .disabled(normalizedDirectoryName.isEmpty)
        } message: {
            Text("将在当前目录中创建一个新的子文件夹。")
        }
    }

    private var parentPath: String? {
        guard store.directoryCrumbs.count > 1 else { return nil }
        return store.directoryCrumbs.dropLast().last?.path
    }

    private var normalizedDirectoryName: String {
        newDirectoryName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func createDirectory() {
        guard let parentPath = store.directoryPath,
              !normalizedDirectoryName.isEmpty else { return }
        store.createDirectory(parentPath: parentPath, name: normalizedDirectoryName)
    }

    private func revealCreatedDirectory(using proxy: ScrollViewProxy) {
        guard let path = store.createdDirectoryPathToReveal,
              store.directoryEntries.contains(where: { $0.path == path }) else { return }
        highlightedDirectoryPath = path
        store.acknowledgeCreatedDirectoryReveal(path: path)
        DispatchQueue.main.async {
            withAnimation(.easeInOut(duration: 0.35)) {
                proxy.scrollTo(path, anchor: .center)
            }
        }
    }

    private func directoryRow(icon: String, title: String, subtitle: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(DSHColor.ocean)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Color(uiColor: .label))
                if let subtitle {
                    Text(subtitle)
                        .appFont(.caption)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .appFont(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    private var createWorkspaceBar: some View {
        VStack(spacing: 8) {
            if let path = store.directoryPath {
                Text(path)
                    .appFont(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Button {
                guard let path = store.directoryPath else { return }
                creatingPath = path
                store.createWorkspace(path: path)
            } label: {
                HStack(spacing: 9) {
                    if store.workspaceCreationIsLoading {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "plus")
                    }
                    Text("在当前目录创建工作区")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .foregroundStyle(.white)
                .background {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.black)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        // 这是纯黑按钮上的一圈高光，与主题无关（原来用 ink 在浅色下变成深色描边，看不见）。
                        .stroke(Color.white.opacity(0.22), lineWidth: 0.8)
                }
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(
                store.directoryPath == nil ||
                store.workspaceCreationIsLoading ||
                store.directoryCreationIsLoading
            )
            .allowsHitTesting(!store.directoryIsLoading)

            if store.directoryCreationIsLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在创建文件夹…")
                }
                .appFont(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial)
    }
}

private struct GatewayConnectionIndicator: View {
    @ObservedObject var gateway: GatewayClient

    var body: some View {
        ConnectionDot(state: gateway.state)
    }
}

private struct GatewayAuthenticationMenu: View {
    @Environment(\.colorScheme) private var colorScheme
    /// 主题感知色板（浅色下墨色、深色下白色叠加）。
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    @ObservedObject var gateway: GatewayClient
    let onScan: () -> Void
    let onManualEntry: () -> Void

    var body: some View {
        if #available(iOS 26.0, *) {
            menu
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
        } else {
            menu
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(palette.stroke(0.25), lineWidth: 0.8))
        }
    }

    private var menu: some View {
        Menu {
            Button(action: onScan) {
                Label("扫描二维码", systemImage: "qrcode.viewfinder")
            }
            Button(action: onManualEntry) {
                Label("手动输入配对信息", systemImage: "keyboard")
            }
        } label: {
            Image(systemName: "person.badge.key.fill")
                .appFont(.system(size: 17, weight: .semibold))
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .accessibilityLabel(String(localized: "a11y.device-auth.state", defaultValue: "设备认证，\(gateway.state.label)"))
    }

}

private struct GatewayConnectionStatusText: View {
    @Environment(\.colorScheme) private var colorScheme
    /// 主题感知色板（浅色下墨色、深色下白色叠加）。
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    @ObservedObject var gateway: GatewayClient

    var body: some View {
        Text(gateway.state.label)
            .appFont(.caption.weight(.medium))
            .foregroundStyle(color)
            .animation(.easeInOut(duration: 0.18), value: gateway.state)
    }

    private var color: Color {
        // B6：浅色下用压暗过的状态色（品牌绿/琥珀在浅底只有 2.6/1.9:1，做文字会糊）。
        switch gateway.state {
        case .connected: palette.success
        case .connecting: palette.warning
        case .failed: palette.danger
        case .disconnected: palette.ink(0.5)
        }
    }
}

private struct ManualGatewayPairingSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var gateway: GatewayClient

    @State private var pairingText = ""
    @State private var validationError: String?
    @State private var didAttemptConnection = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("手动输入配对信息")
                            .appFont(.title2.bold())
                        Text("粘贴 Harness WebUI 提供的 Base64URL 配对字符串。长期设备 token 仍只会保存到 Keychain。")
                            .appFont(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    ZStack(alignment: .topLeading) {
                        if pairingText.isEmpty {
                            Text(Self.placeholder)
                                .appFont(.system(.footnote, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 18)
                                .allowsHitTesting(false)
                        }
                        TextEditor(text: $pairingText)
                            .appFont(.system(.footnote, design: .monospaced))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .scrollContentBackground(.hidden)
                            .padding(10)
                    }
                    .frame(minHeight: 210)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .stroke(.primary.opacity(0.1), lineWidth: 0.8)
                    }

                    connectionResult

                    Button(action: primaryConnectionAction) {
                        HStack(spacing: 9) {
                            if isConnecting {
                                ProgressView().tint(.white)
                            } else {
                                Image(systemName: "link")
                            }
                            Text(
                                isConnecting
                                    ? String(localized: "取消连接")
                                    : gateway.state.isConnected
                                        ? String(localized: "重新配对并连接")
                                        : String(localized: "连接")
                            )
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 50)
                        .foregroundStyle(.white)
                        .background(DSHColor.ocean, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isConnecting)
                    .opacity(pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isConnecting ? 0.45 : 1)
                }
                .padding(22)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("设备认证")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationBackgroundCompat(.regularMaterial)
    }

    @ViewBuilder
    private var connectionResult: some View {
        if let validationError {
            resultCard(
                title: String(localized: "配对信息无效"),
                detail: validationError,
                color: DSHColor.dangerAA,
                symbol: "exclamationmark.triangle.fill"
            )
        } else if didAttemptConnection {
            switch gateway.state {
            case .connecting:
                resultCard(
                    title: String(localized: "正在连接"),
                    detail: String(localized: "pairing.submitting.detail", defaultValue: "正在提交一次性配对码并等待 Mobile Gateway 完成设备鉴权…"),
                    color: DSHColor.warningAA,
                    symbol: "arrow.triangle.2.circlepath"
                )
            case .connected:
                resultCard(
                    title: String(localized: "连接成功"),
                    detail: String(localized: "设备鉴权已完成，长期 token 已安全保存到 Keychain。"),
                    color: DSHColor.successAA,
                    symbol: "checkmark.circle.fill"
                )
            case .failed(let reason):
                resultCard(
                    title: String(localized: "连接失败"),
                    detail: reason,
                    color: DSHColor.dangerAA,
                    symbol: "xmark.octagon.fill"
                )
            case .disconnected:
                resultCard(
                    title: String(localized: "未连接"),
                    detail: String(localized: "请检查配对信息后重新连接。"),
                    color: .secondary,
                    symbol: "network.slash"
                )
            }
        } else {
            resultCard(
                title: gateway.state.label,
                detail: String(localized: "输入 Base64URL 配对字符串后点击连接，结果会显示在这里。"),
                color: .secondary,
                symbol: "person.badge.key.fill"
            )
        }
    }

    private func resultCard(title: String, detail: String, color: Color, symbol: String) -> some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: symbol)
                .appFont(.title3.weight(.semibold))
                .foregroundStyle(color)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).appFont(.headline)
                Text(detail)
                    .appFont(.subheadline)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(color.opacity(0.18), lineWidth: 0.8)
        }
    }

    private func connect() {
        validationError = nil
        didAttemptConnection = true
        do {
            try store.pair(usingQRCode: pairingText, presentsFailureAlert: false)
        } catch {
            validationError = error.localizedDescription
        }
    }

    private var isConnecting: Bool {
        if case .connecting = gateway.state { return true }
        return false
    }

    private func primaryConnectionAction() {
        if isConnecting {
            gateway.disconnect()
        } else {
            connect()
        }
    }

    private static let placeholder = "eyJ2ZXJzaW9uIjoyLCJwdWJsaWNVcmwiOiJ3c3M6Ly9nYXRld2F5LmV4YW1wbGUuY29tL3dzL21vYmlsZSIsLi4ufQ"
}

struct GatewayQRScannerView: View {
    @Environment(\.colorScheme) private var colorScheme
    /// 主题感知色板（浅色下墨色、深色下白色叠加）。
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    let onCode: (String) -> Void
    let onCancel: () -> Void
    let onFailure: (String) -> Void

    var body: some View {
        GeometryReader { geometry in
            scannerContent(side: min(360, max(120, min(geometry.size.width - 44, geometry.size.height - 230))))
        }
    }

    private func scannerContent(side: CGFloat) -> some View {
        ZStack {
            GatewayCameraPreview(onCode: onCode, onFailure: onFailure)
                .ignoresSafeArea()

            LinearGradient(
                colors: [.black.opacity(0.58), .clear, .black.opacity(0.72)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("扫描设备配对码")
                            .appFont(.title2.bold())
                        Text("请扫描 Harness WebUI 生成的二维码")
                            .appFont(.subheadline)
                            .foregroundStyle(palette.ink(0.72))
                    }
                    Spacer()
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .appFont(.system(size: 16, weight: .semibold))
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .background(.ultraThinMaterial, in: Circle())
                    .accessibilityLabel(String(localized: "取消扫描"))
                }
                .padding(.horizontal, 22)
                .padding(.top, 18)

                Spacer()

                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(.white, lineWidth: 3)
                    .frame(width: side, height: side)
                    .overlay(alignment: .bottom) {
                        Text("将二维码完整放入框内")
                            .appFont(.subheadline.weight(.medium))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .background(.ultraThinMaterial, in: Capsule())
                            .offset(y: 58)
                    }

                Spacer()
            }
            .foregroundStyle(.white)
        }
    }
}

struct GatewayCameraPreview: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onFailure: (String) -> Void

    func makeUIViewController(context: Context) -> GatewayScannerController {
        let controller = GatewayScannerController()
        controller.onCode = onCode
        controller.onFailure = onFailure
        controller.start()
        return controller
    }

    func updateUIViewController(_ uiViewController: GatewayScannerController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: GatewayScannerController, coordinator: Void) {
        uiViewController.stop()
    }
}

final class GatewayScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onFailure: ((String) -> Void)?

    private let captureSession = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didFinish = false

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        guard let connection = previewLayer?.connection,
              let orientation = view.window?.windowScene?.interfaceOrientation else { return }
        // 两套 API 语义不同，必须分别映射：
        // - iOS 17+ 的 videoRotationAngle 是「画面顺时针旋转角度」，竖屏=90
        // - iOS 16 的 videoOrientation 是「设备/界面方向枚举」
        // 曾经把角度直接拿去映射方向枚举（90 → .landscapeRight），导致竖着拿看到横画面。
        if #available(iOS 17.0, *) {
            let angle: CGFloat
            switch orientation {
            case .landscapeRight: angle = 0
            case .landscapeLeft: angle = 180
            case .portraitUpsideDown: angle = 270
            default: angle = 90
            }
            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        } else if connection.isVideoOrientationSupported {
            // iOS 16 回退：直接用界面方向，不经过角度中转。
            let videoOrientation: AVCaptureVideoOrientation
            switch orientation {
            case .portrait: videoOrientation = .portrait
            case .portraitUpsideDown: videoOrientation = .portraitUpsideDown
            case .landscapeLeft: videoOrientation = .landscapeLeft
            case .landscapeRight: videoOrientation = .landscapeRight
            @unknown default: videoOrientation = .portrait
            }
            connection.videoOrientation = videoOrientation
        }
    }

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndRun()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        self.configureAndRun()
                    } else {
                        self.finish(with: String(localized: "camera.denied", defaultValue: "未获得相机权限。请在系统设置中允许 Whaleness 使用相机后重试。"))
                    }
                }
            }
        case .denied, .restricted:
            finish(with: String(localized: "camera.restricted", defaultValue: "相机权限不可用。请在系统设置中允许 Whaleness 使用相机后重试。"))
        @unknown default:
            finish(with: String(localized: "无法确定当前相机权限状态。"))
        }
    }

    func stop() {
        guard captureSession.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { [captureSession] in
            captureSession.stopRunning()
        }
    }

    private func configureAndRun() {
        guard !captureSession.isRunning, captureSession.inputs.isEmpty else { return }
        guard let camera = AVCaptureDevice.default(for: .video) else {
            finish(with: String(localized: "此设备没有可用的相机。"))
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            guard captureSession.canAddInput(input) else {
                finish(with: String(localized: "无法把相机接入扫码会话。"))
                return
            }
            captureSession.addInput(input)

            let output = AVCaptureMetadataOutput()
            guard captureSession.canAddOutput(output) else {
                finish(with: String(localized: "当前设备不支持二维码识别。"))
                return
            }
            captureSession.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            guard output.availableMetadataObjectTypes.contains(.qr) else {
                finish(with: String(localized: "当前相机不支持二维码元数据识别。"))
                return
            }
            output.metadataObjectTypes = [.qr]

            let preview = AVCaptureVideoPreviewLayer(session: captureSession)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.bounds
            view.layer.insertSublayer(preview, at: 0)
            previewLayer = preview

            DispatchQueue.global(qos: .userInitiated).async { [captureSession] in
                captureSession.startRunning()
            }
        } catch {
            finish(with: String(localized: "camera.start.failed", defaultValue: "相机启动失败：\(error.localizedDescription)"))
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didFinish,
              let code = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        didFinish = true
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        stop()
        onCode?(code)
    }

    private func finish(with message: String) {
        guard !didFinish else { return }
        didFinish = true
        stop()
        onFailure?(message)
    }
}

import DeepSeekHarnessShared
import SwiftUI

/// 团队页（Docs/bot-team-port-design.md §4.1）：P1 只读列表 + P2 写操作四件套。
/// iOS 16 红线：不使用 ContentUnavailableView / @Observable / 双参 onChange / scrollTargetBehavior。
struct TeamView: View {
    @ObservedObject var team: BridgeTeamAdapter
    /// 返回 true 表示已在现有 gateway 会话列表中找到并进入聊天。
    var onOpenConversation: (String) -> Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @State private var segment: Segment = .team
    @State private var detail: BridgeTeamRow?
    @State private var openFailure: String?
    @State private var showsDevices = false
    @State private var didBootstrap = false
    @State private var bridgeChat: BridgeChatTarget?
    /// 需求 1-4：团队页的数据源迁到网关团队帧（WS `/mobile/v1/team`）。
    /// bridge 在本轮仍只负责**聊天**（点进去之后的会话），团队名册不再由它供数。
    @StateObject private var feed = GatewayTeamFeed()
    @State private var feedDetail: GatewayTeamFeed.Row?

    private enum Segment: String, CaseIterable {
        case team = "团队"
        case sessions = "会话"
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let mirror = team.mirror {
                    if mirror.needsPairing {
                        pairingPrompt
                    } else {
                        if mirror.isOffline {
                            offlineBanner
                        }
                        if usesGatewayFeed, segment == .team {
                            gatewayTeamContent
                        } else if mirror.isLoading && mirror.bots.isEmpty && mirror.rooms.isEmpty {
                            loadingView
                        } else {
                            listContent(mirror)
                        }
                    }
                } else {
                    loadingView
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("团队")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 16) {
                        Button {
                            showsDevices = true
                        } label: {
                            Image(systemName: "iphone.gen2")
                        }
                        .accessibilityLabel("设备管理")
                        Button {
                            Task { await team.refreshNow() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .disabled(team.isRefreshing)
                        .accessibilityLabel("刷新团队")
                    }
                }
            }
            .onAppear {
                if !didBootstrap {
                    didBootstrap = true
                    // 启动时恢复已配对会话；没有就停在未配对态等待配对。
                    team.bootstrap()
                }
                team.startPolling()
                feed.start()
            }
            .onDisappear {
                team.stopPolling()
                feed.stop()
            }
            .onChange(of: scenePhase) { phase in
                // iOS 16 单参旧 API：前台继续轮询，后台停掉省电。
                if phase == .active {
                    team.startPolling()
                    feed.start()
                } else {
                    team.stopPolling()
                    feed.stop()
                }
            }
            .sheet(item: $detail) { row in
                detailView(row)
            }
            .sheet(item: $feedDetail) { row in
                feedDetailView(row)
            }
            .sheet(isPresented: $showsDevices) {
                devicesView
            }
            .sheet(item: $bridgeChat) { target in
                BridgeConversationView(title: target.row.name, engine: target.engine)
            }
        }
    }

    // MARK: - 网关团队帧渲染（需求 1-4）

    /// 网关团队帧可用时用它渲染团队段；未配对/空数据时回落到 bridge 旧渲染（旧宿主兼容）。
    private var usesGatewayFeed: Bool {
        switch feed.phase {
        case .live: return true
        case .connecting: return !feed.rows.isEmpty
        case .offline: return !feed.rows.isEmpty
        case .needsPairing, .idle: return false
        }
    }

    private var botNames: [String: String] {
        var names: [String: String] = [:]
        for row in feed.rows where row.kind == .bot { names[row.id] = row.name }
        return names
    }

    /// 需求 1（主 Bot 最上、Bots 与群聊混排）+ 需求 3（群聊行带成员与实时状态）+ 需求 4（名字下挂状态）。
    private var gatewayTeamContent: some View {
        VStack(spacing: 12) {
        // 「会话」段按用户要求先保留：切过去仍由 bridge 的会话行承载，团队段才走网关帧。
        Picker("分段", selection: $segment) {
            ForEach(Segment.allCases, id: \.self) { item in
                Text(item.rawValue).tag(item)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.top, 12)
        List {
            if feed.degraded {
                Section {
                    // 红线：降级态必须显式，绝不能渲染成「全部空闲」。
                    Text("实时状态暂不可用：网关目前只拿到名册（名字/角色/成员）。干活中、等你回答、等你审批要等桌面端发布团队服务后才显示。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            if case .offline(let reason) = feed.phase {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "wifi.exclamationmark").foregroundStyle(.orange)
                        Text("团队连接中断：\(reason)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            Section {
                if feed.sortedRows.isEmpty {
                    emptyRow("暂无 Bot 或群聊")
                } else {
                    ForEach(feed.sortedRows) { row in
                        gatewayRow(row)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        }
    }

    private func gatewayRow(_ row: GatewayTeamFeed.Row) -> some View {
        HStack(spacing: 12) {
            gatewayAvatar(row, size: 42)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row.name)
                        .font(.body)
                        .lineLimit(1)
                    if row.main { badge("主") }
                    if row.pinned {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                if let status = feed.statusText(for: row, botNames: botNames) {
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(statusColor(row))
                        .lineLimit(1)
                } else if row.kind == .room {
                    Text(feed.roomSubtitle(for: row, botNames: botNames))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else if let role = row.role, !role.isEmpty {
                    Text(role)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if row.kind == .room, let preview = row.preview, !preview.isEmpty {
                    Text(preview)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if row.kind == .room {
                Image(systemName: "person.2.fill")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            // 需求 2：点整行进对话，详情退到右侧信息入口。
            Button {
                detail = bridgeRow(for: row)
                if detail == nil { feedDetail = row }
            } label: {
                Image(systemName: "info.circle")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
                    .padding(.leading, 6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("详情")
        }
        .contentShape(Rectangle())
        .onTapGesture { openGateway(row) }
        .contextMenu {
            Button {
                detail = bridgeRow(for: row)
                if detail == nil { feedDetail = row }
            } label: {
                Label("查看详情", systemImage: "info.circle")
            }
        }
    }

    /// 头像与 Bot tab 同一套：有 data URL 画图，否则深色不透明底 + 白色首字母
    /// （原来的「系统色 0.75 透明 + 白字 / room 的 accentColor 0.18 + 白字」在浅色下只有 1.33–3.49:1）。
    ///
    /// `backdrop` 传 List 行的实际底色：glyph 的眼睛是「挖洞透底」，电脑端透出的是页面背景，
    /// 所以这里必须给行底色（不是固定白、也不是淡色圆底）。动效只在团队页开。
    @ViewBuilder
    private func gatewayAvatar(_ row: GatewayTeamFeed.Row, size: CGFloat) -> some View {
        TeamAvatarView(row: row, size: size,
                       backdrop: Color(.secondarySystemGroupedBackground),
                       motion: .live(working: feed.isLive(row)))
    }

    private func statusColor(_ row: GatewayTeamFeed.Row) -> Color {
        // B6：系统橙/绿在浅底做文字只有 2~3:1，浅色主题下换压暗版本（见 DSHPalette）。
        let palette = DSHPalette.resolve(colorScheme)
        if !row.pending.isEmpty || row.placeholderCards > 0 || !row.approvals.isEmpty { return palette.caution }
        if feed.isLive(row) { return palette.success }
        return .secondary
    }

    /// 网关行 → bridge 行（聊天仍走 bridge，本轮只迁名册）。
    private func bridgeRow(for row: GatewayTeamFeed.Row) -> BridgeTeamRow? {
        guard let mirror = team.mirror else { return nil }
        let pool = row.kind == .room ? mirror.rooms : mirror.bots
        return pool.first { $0.id == row.id }
    }

    private func openGateway(_ row: GatewayTeamFeed.Row) {
        if let bridgeRow = bridgeRow(for: row) {
            open(bridgeRow)
            return
        }
        // bridge 名册还没同步到这一行：先按 sessionId 试网关会话，再提示刷新。
        if onOpenConversation(row.sessionId) {
            dismiss()
            return
        }
        openFailure = "这条对话还没同步到会话列表，稍后重试或下拉刷新团队页。"
    }

    // MARK: - 内容

    private func listContent(_ mirror: BridgeTeamMirror) -> some View {
        VStack(spacing: 12) {
            if showsSegment(mirror) {
                Picker("分段", selection: $segment) {
                    ForEach(Segment.allCases, id: \.self) { item in
                        Text(item.rawValue).tag(item)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.top, 12)
            }
            List {
                if segment == .sessions {
                    sessionsSection(mirror)
                } else {
                    botsSection(mirror)
                    if groupsAllowed(mirror) {
                        roomsSection(mirror)
                    }
                }
                if let error = mirror.lastError {
                    Section {
                        Text(error.displayText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                if let transportError = team.lastTransportError {
                    Section {
                        Text(transportError)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    @ViewBuilder
    private func botsSection(_ mirror: BridgeTeamMirror) -> some View {
        if let mainBot = primaryBot(mirror) {
            Section("主 Bot") {
                mainBotCard(mainBot, mirror: mirror)
            }
        }
        Section("Bots") {
            if mirror.bots.isEmpty {
                emptyRow("暂无 Bot")
            } else {
                ForEach(mirror.bots) { row in
                    botCell(row, mirror: mirror)
                }
            }
        }
    }

    @ViewBuilder
    private func sessionsSection(_ mirror: BridgeTeamMirror) -> some View {
        Section("会话") {
            let rows = mirror.sessions ?? []
            if rows.isEmpty {
                emptyRow("暂无其他会话")
            } else {
                ForEach(rows) { row in
                    sessionCell(row)
                }
            }
        }
    }

    /// 顶部分段：bridge 宣告 sessions 能力、或已拿到会话行时出现（旧服务端保持原样）。
    private func showsSegment(_ mirror: BridgeTeamMirror) -> Bool {
        if groupsAllowed(mirror) { return true }
        if mirror.capabilities?.sessionsAllowed == true { return true }
        return !(mirror.sessions ?? []).isEmpty
    }

    private func sessionCell(_ row: BridgeTeamRow) -> some View {
        Button {
            open(row)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "bubble.left.and.text.bubble.right.fill")
                    .frame(width: 38, height: 38)
                    .background(Color.secondary.opacity(0.12), in: Circle())
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).font(.body).lineLimit(1)
                    HStack(spacing: 6) {
                        if let workspace = row.workspace, !workspace.isEmpty {
                            Text(workspace).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        if let active = row.lastActiveAt {
                            Text(Self.relativeTime(active))
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
                Spacer()
                if row.busy == true {
                    badge("运行中")
                }
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// ms 时间戳 → 人话相对时间（iOS 16 可用的相对格式化器）。
    static func relativeTime(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    @ViewBuilder
    private func roomsSection(_ mirror: BridgeTeamMirror) -> some View {
        Section("群聊") {
            if mirror.rooms.isEmpty {
                emptyRow("暂无群聊")
            } else {
                ForEach(mirror.rooms) { row in
                    roomCell(row)
                }
            }
        }
    }

    private func mainBotCard(_ row: BridgeTeamRow, mirror: BridgeTeamMirror) -> some View {
        HStack(spacing: 12) {
            avatar(for: row, size: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name).font(.headline)
                if let role = row.role, !role.isEmpty {
                    Text(role).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if mirror.isPending(row.id) {
                ProgressView()
            }
            Text("主")
                .font(.caption2)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Color.accentColor.opacity(0.15), in: Capsule())
        }
        .contentShape(Rectangle())
        .onTapGesture { detail = row }
    }

    private func botCell(_ row: BridgeTeamRow, mirror: BridgeTeamMirror) -> some View {
        Button {
            detail = row
        } label: {
            HStack(spacing: 12) {
                avatar(for: row, size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).font(.body)
                    if let role = row.role, !role.isEmpty {
                        Text(role).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if mirror.isPending(row.id) {
                    ProgressView()
                }
                if mirror.mainBotIds?.contains(row.id) == true {
                    badge("Main Bot")
                }
                if row.hidden == true {
                    badge("Hidden")
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func roomCell(_ row: BridgeTeamRow) -> some View {
        Button {
            detail = row
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "person.3.fill")
                    .frame(width: 38, height: 38)
                    .background(Color.secondary.opacity(0.12), in: Circle())
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).font(.body)
                    if let members = row.members, !members.isEmpty {
                        Text(members.joined(separator: "、"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func emptyRow(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 6)
    }

    private func badge(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.secondary.opacity(0.12), in: Capsule())
            .foregroundStyle(.secondary)
    }

    private func avatar(for row: BridgeTeamRow, size: CGFloat) -> some View {
        Circle()
            .fill(avatarColor(row))
            .frame(width: size, height: size)
            .overlay {
                Text(String(row.name.prefix(1)))
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(.white)
            }
    }

    private func avatarColor(_ row: BridgeTeamRow) -> Color {
        if let hex = row.avatar?.color, let color = Color(bridgeHex: hex) {
            return color
        }
        return Color.secondary.opacity(0.55)
    }

    // MARK: - 状态视图

    /// B3：配对入口统一回到网关（扫码为主、手输地址 + 配对码为辅）。
    /// 团队页不再自带 bridge 配对——粘贴配对资料 JSON 那条路径已退役，
    /// bridge 凭证改由网关配对成功后的 `paired` 帧里 `bridgeSession` 字段下发；
    /// 一次配对同时拿到会话与团队两套凭证，没有第二条路径需要用户理解。
    private var pairingPrompt: some View {
        ScrollView {
            VStack(spacing: 14) {
                Image(systemName: "iphone.and.arrow.forward")
                    .font(.system(size: 40))
                    .foregroundStyle(.secondary)
                    .padding(.top, 24)
                Text("尚未连接桌面端")
                    .font(.headline)
                Text("回主页点右上角钥匙图标 →「扫描二维码」或「手动输入配对信息」。\n一次配对会同时带上 Bot 团队。")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 24)

                if case .failed(let message) = team.pairingState {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }

                Button {
                    dismiss()
                } label: {
                    Label("回主页配对", systemImage: "key")
                        .frame(maxWidth: 280)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 4)
                .padding(.bottom, 24)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var loadingView: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("正在同步团队…")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var offlineBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "wifi.exclamationmark")
                .foregroundStyle(.orange)
            Text("离线：显示最后一次同步的数据")
                .font(.footnote)
            Spacer()
            Button("重试") {
                Task { await team.refreshNow() }
            }
            .font(.footnote)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.orange.opacity(0.12))
    }

    // MARK: - 详情（P2：写操作）

    /// 网关行的只读详情：名册信息 + 实时状态。写操作（改模型/思考等级等）仍走 bridge 详情页。
    private func feedDetailView(_ row: GatewayTeamFeed.Row) -> some View {
        NavigationStack {
            Form {
                Section("基本信息") {
                    LabeledContent("名称", value: row.name)
                    if let role = row.role, !role.isEmpty {
                        LabeledContent("角色", value: role)
                    }
                    LabeledContent("类型", value: row.kind == .room ? "群聊" : "Bot")
                    if row.main { LabeledContent("身份", value: "主 Bot") }
                    if row.pinned { LabeledContent("置顶", value: "是") }
                    if row.kind == .room {
                        LabeledContent("成员", value: feed.roomSubtitle(for: row, botNames: botNames))
                        if let mode = row.mode {
                            LabeledContent("发言模式", value: mode == "everyone" ? "所有人轮流" : "管理员指派")
                        }
                    }
                }
                Section("实时状态") {
                    if let status = feed.statusText(for: row, botNames: botNames) {
                        Text(status).font(.subheadline)
                    } else if feed.degraded {
                        Text("实时状态暂不可用（网关降级：只拿到名册）")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("当前没有进行中的动作").font(.subheadline).foregroundStyle(.secondary)
                    }
                    if let work = row.work, let note = work.note ?? work.target ?? work.tool {
                        LabeledContent("在做", value: note)
                    }
                    if let since = row.work?.since {
                        LabeledContent("开始于", value: Self.relativeTime(since))
                    }
                    ForEach(row.approvals) { approval in
                        LabeledContent("待审批", value: approval.reason ?? approval.tool ?? "工具调用")
                    }
                    if let updated = row.updatedAt {
                        LabeledContent("最近活动", value: Self.relativeTime(updated))
                    }
                }
                Section("会话") {
                    LabeledContent("sessionId", value: row.sessionId)
                    Button {
                        openGateway(row)
                    } label: {
                        Label("进入对话", systemImage: "bubble.left.and.bubble.right")
                    }
                }
            }
            .navigationTitle(row.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { feedDetail = nil }
                }
            }
        }
    }

    private func detailView(_ row: BridgeTeamRow) -> some View {
        NavigationStack {
            Group {
                if row.isRoom {
                    roomDetail(row)
                } else {
                    botDetail(row)
                }
            }
            .navigationTitle(row.isRoom ? "群聊" : "Bot")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { detail = nil }
                }
            }
        }
        .alert(
            "无法进入聊天",
            isPresented: Binding(
                get: { openFailure != nil },
                set: { if !$0 { openFailure = nil } }
            )
        ) {
            Button("好", role: .cancel) { openFailure = nil }
        } message: {
            Text(openFailure ?? "")
        }
    }

    private func roomDetail(_ row: BridgeTeamRow) -> some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "person.3.fill")
                        .frame(width: 54, height: 54)
                        .background(Color.secondary.opacity(0.12), in: Circle())
                        .foregroundStyle(.secondary)
                    Text(row.name).font(.title3.weight(.semibold))
                }
                .padding(.vertical, 4)
            }
            Section {
                if let members = row.members, !members.isEmpty {
                    labeledRow("成员", members.joined(separator: "、"))
                }
                if let admin = row.admin, !admin.isEmpty {
                    labeledRow("管理员", admin)
                }
                if let mode = row.mode {
                    labeledRow("发言模式", mode == "admin" ? "管理员主导" : mode)
                }
            }
            Section {
                Button {
                    open(row)
                } label: {
                    Label("发消息", systemImage: "bubble.left.and.bubble.right.fill")
                }
                .disabled(row.sessionId.isEmpty)
            }
        }
    }

    private func botDetail(_ row: BridgeTeamRow) -> some View {
        let mirror = team.mirror
        let canFlags = mirror?.supportsAction("set-flags") == true && mirror?.canWrite == true
        let canUpdate = mirror?.supportsAction("update-bot") == true && mirror?.canWrite == true
        let canSetMain = mirror?.supportsAction("set-main") == true && mirror?.canWrite == true
        let isMain = mirror?.mainBotIds?.contains(row.id) == true
        return List {
            Section {
                HStack(spacing: 14) {
                    avatar(for: row, size: 54)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(row.name).font(.title3.weight(.semibold))
                        if let role = row.role, !role.isEmpty {
                            Text(role).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if mirror?.isPending(row.id) == true {
                        ProgressView()
                    } else if isMain {
                        Text("主")
                            .font(.caption2)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.accentColor.opacity(0.15), in: Capsule())
                    }
                }
                .padding(.vertical, 4)
            }

            if canFlags {
                Section("显示") {
                    Toggle("置顶", isOn: Binding(
                        get: { row.pinned == true },
                        set: { team.setPinned(row.id, $0) }
                    ))
                    Toggle("隐藏", isOn: Binding(
                        get: { row.hidden == true },
                        set: { team.setHidden(row.id, $0) }
                    ))
                }
            }

            if canUpdate {
                Section("模型与档位") {
                    if let models = mirror?.models, !models.isEmpty {
                        Picker("模型", selection: modelSelection(row)) {
                            Text("默认").tag("")
                            ForEach(models) { model in
                                Text(model.name).tag(model.ref)
                            }
                        }
                    }
                    if let efforts = effortOptions(for: row) {
                        Picker("档位", selection: effortSelection(row)) {
                            Text("默认").tag("")
                            ForEach(efforts) { effort in
                                Text(effort.name).tag(effort.id)
                            }
                        }
                    }
                }
            }

            if canSetMain && !isMain {
                Section {
                    Button {
                        team.setMain(row.id)
                    } label: {
                        Label("设为主 Bot", systemImage: "crown.fill")
                    }
                }
            }

            Section {
                Button {
                    open(row)
                } label: {
                    Label("发消息", systemImage: "bubble.left.and.bubble.right.fill")
                }
                .disabled(row.sessionId.isEmpty)
            }

            Section {
                labeledRow("会话", row.sessionId.isEmpty ? "暂无" : row.sessionId)
                if row.waiting == true {
                    labeledRow("状态", "工作中")
                }
            }
        }
        .onAppear {
            if mirror?.supportsAction("models") == true, mirror?.models?.isEmpty != false {
                team.loadModels()
            }
            if mirror?.supportsAction("efforts") == true, let model = row.model {
                team.loadEfforts(ref: model)
            }
        }
    }

    private func modelSelection(_ row: BridgeTeamRow) -> Binding<String> {
        Binding(
            get: { row.model ?? "" },
            set: { next in
                let model = next.isEmpty ? nil : next
                team.updateBot(
                    row.id,
                    includeModel: true,
                    model: model,
                    includeEffort: false,
                    effort: nil
                )
                if let model { team.loadEfforts(ref: model) }
            }
        )
    }

    private func effortSelection(_ row: BridgeTeamRow) -> Binding<String> {
        Binding(
            get: { row.effort ?? "" },
            set: { next in
                team.updateBot(
                    row.id,
                    includeModel: false,
                    model: nil,
                    includeEffort: true,
                    effort: next.isEmpty ? nil : next
                )
            }
        )
    }

    private func effortOptions(for row: BridgeTeamRow) -> [BridgeTeamEffortRow]? {
        guard let model = row.model else { return nil }
        let efforts = team.mirror?.effortsByModel?[model]?.efforts ?? []
        return efforts.isEmpty ? nil : efforts
    }

    // MARK: - 设备管理（P2）

    private var devicesView: some View {
        NavigationStack {
            List {
                if let error = team.mirror?.devicesLoadError {
                    Section {
                        Text(error.displayText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("已配对设备") {
                    let devices = team.mirror?.devices ?? []
                    if devices.isEmpty {
                        Text(team.mirror?.devicesLoading == true ? "加载中…" : "暂无设备")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(devices) { device in
                            deviceCell(device)
                        }
                    }
                }
            }
            .navigationTitle("设备")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("刷新") { team.loadDevices() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { showsDevices = false }
                }
            }
            .onAppear { team.loadDevices() }
        }
    }

    private func deviceCell(_ device: BridgeTeamDeviceRow) -> some View {
        let isCurrent = team.mirror?.currentDeviceId == device.id
        let pending = team.mirror?.isPending(device.id) == true
        return HStack(spacing: 12) {
            Image(systemName: "iphone")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.name ?? device.id).font(.body)
                    if isCurrent { badge("本机") }
                }
                if let lastSeen = device.lastSeenAt {
                    Text("最近活跃 \(formatted(lastSeen))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if pending {
                ProgressView()
            } else if !isCurrent {
                Button(role: .destructive) {
                    team.revokeDevice(device.id)
                } label: {
                    Text("撤销")
                        .font(.footnote)
                }
            }
        }
    }

    private func formatted(_ epochMillis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: Double(epochMillis) / 1000))
    }

    private func labeledRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
    }

    // MARK: - 动作

    private func open(_ row: BridgeTeamRow) {
        // 一次配对语义：优先复用已有 gateway 会话；没有就走 bridge 聊天（U 单）。
        if onOpenConversation(row.sessionId) {
            detail = nil
            dismiss()
            return
        }
        if let engine = team.chatEngine(conversationId: row.id, sessionId: row.sessionId) {
            detail = nil
            bridgeChat = BridgeChatTarget(row: row, engine: engine)
        } else {
            openFailure = "尚未完成配对或会话不可用，请先配对桌面端。"
        }
    }

    private func primaryBot(_ mirror: BridgeTeamMirror) -> BridgeTeamRow? {
        guard let primaryId = mirror.mainBotIds?.first else { return nil }
        return mirror.bots.first { $0.id == primaryId }
    }

    private func groupsAllowed(_ mirror: BridgeTeamMirror) -> Bool {
        mirror.capabilities?.groupsAllowed == true
    }
}

private extension BridgeTeamMirror {
    var canWrite: Bool { readOnlyReason == nil && phase == "READY" }
}

private extension Color {
    init?(bridgeHex: String) {
        let trimmed = bridgeHex.trimmingCharacters(in: .whitespacesAndNewlines)
        var hex = trimmed
        if hex.hasPrefix("#") { hex.removeFirst() }
        guard hex.count == 6 || hex.count == 8 else { return nil }
        guard let value = UInt64(hex, radix: 16) else { return nil }
        if hex.count == 8 {
            self.init(
                .sRGB,
                red: Double((value >> 24) & 0xFF) / 255,
                green: Double((value >> 16) & 0xFF) / 255,
                blue: Double((value >> 8) & 0xFF) / 255,
                opacity: Double(value & 0xFF) / 255
            )
        } else {
            self.init(
                .sRGB,
                red: Double((value >> 16) & 0xFF) / 255,
                green: Double((value >> 8) & 0xFF) / 255,
                blue: Double(value & 0xFF) / 255,
                opacity: 1
            )
        }
    }
}

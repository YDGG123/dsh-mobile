import SwiftUI

/// 团队页（Docs/bot-team-port-design.md §4.1）：P1 只读列表 + P2 写操作四件套。
/// iOS 16 红线：不使用 ContentUnavailableView / @Observable / 双参 onChange / scrollTargetBehavior。
struct TeamView: View {
    @ObservedObject var team: BridgeTeamAdapter
    /// 返回 true 表示已在现有 gateway 会话列表中找到并进入聊天。
    var onOpenConversation: (String) -> Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var segment: Segment = .bots
    @State private var detail: BridgeTeamRow?
    @State private var openFailure: String?
    @State private var showsDevices = false
    @State private var showsBridgeScanner = false
    @State private var showsBridgePairing = false
    @State private var didBootstrap = false

    private enum Segment: String, CaseIterable {
        case bots = "Bots"
        case rooms = "群聊"
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
                        if mirror.isLoading && mirror.bots.isEmpty && mirror.rooms.isEmpty {
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
            }
            .onDisappear { team.stopPolling() }
            .onChange(of: scenePhase) { phase in
                // iOS 16 单参旧 API：前台继续轮询，后台停掉省电。
                if phase == .active {
                    team.startPolling()
                } else {
                    team.stopPolling()
                }
            }
            .sheet(item: $detail) { row in
                detailView(row)
            }
            .sheet(isPresented: $showsDevices) {
                devicesView
            }
            .sheet(isPresented: $showsBridgePairing) {
                BridgePairingSheet(team: team)
            }
            .fullScreenCover(isPresented: $showsBridgeScanner) {
                GatewayQRScannerView(
                    onCode: { code in
                        showsBridgeScanner = false
                        if let payload = BridgePairingPayload.parse(code) {
                            team.pair(payload: payload)
                        } else {
                            team.failPairingWithInvalidQr()
                        }
                    },
                    onCancel: { showsBridgeScanner = false },
                    onFailure: { message in
                        showsBridgeScanner = false
                        team.setPairingFailure(message)
                    }
                )
            }
        }
    }

    // MARK: - 内容

    private func listContent(_ mirror: BridgeTeamMirror) -> some View {
        VStack(spacing: 12) {
            if groupsAllowed(mirror) {
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
                if segment == .bots || !groupsAllowed(mirror) {
                    botsSection(mirror)
                } else {
                    roomsSection(mirror)
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

    private var pairingPrompt: some View {
        VStack(spacing: 14) {
            Image(systemName: "iphone.and.arrow.forward")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("尚未连接桌面端")
                .font(.headline)
            Text("在桌面端「手机访问 → App」生成配对码，然后扫描二维码或手动输入。")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)

            switch team.pairingState {
            case .awaitingApproval:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("等待桌面端批准…请在电脑上点「批准」")
                        .font(.footnote)
                }
                Button("取消") { team.cancelPairing() }
                    .font(.footnote)
            case .connecting:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在连接服务器…").font(.footnote)
                }
            case .failed(let message):
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            case .idle, .paired:
                EmptyView()
            }

            Button {
                showsBridgeScanner = true
            } label: {
                Label("扫描配对二维码", systemImage: "qrcode.viewfinder")
                    .frame(maxWidth: 260)
            }
            .buttonStyle(.borderedProminent)

            Button {
                showsBridgePairing = true
            } label: {
                Label("手动输入服务器地址与配对码", systemImage: "keyboard")
                    .frame(maxWidth: 260)
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        let handled = onOpenConversation(row.sessionId)
        if handled {
            detail = nil
            dismiss()
        } else {
            openFailure = "该 Bot 暂无移动会话。先在桌面端与它对话一次，再回来重试。"
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

// MARK: - 手动配对（服务器地址 + 配对码，或粘贴桌面端「复制配对资料」）

struct BridgePairingSheet: View {
    @ObservedObject var team: BridgeTeamAdapter
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""
    @State private var url = ""
    @State private var code = ""
    @State private var localError: String?

    private var busy: Bool {
        team.pairingState == .connecting || isAwaitingApproval
    }

    private var isAwaitingApproval: Bool {
        if case .awaitingApproval = team.pairingState { return true }
        return false
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("粘贴配对资料（桌面端「复制配对资料」）") {
                    TextEditor(text: $pasted)
                        .frame(minHeight: 88)
                        .font(.footnote)
                    Button("解析并填入") {
                        parsePasted()
                    }
                    .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                Section("服务器地址与配对码") {
                    TextField("服务器地址（https://…）", text: $url)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                    TextField("配对码", text: $code)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                }

                if let localError {
                    Section {
                        Text(localError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                if case .failed(let message) = team.pairingState {
                    Section {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                if isAwaitingApproval {
                    Section {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("等待桌面端批准…请到电脑上点「批准」")
                                .font(.footnote)
                        }
                    }
                }

                Section {
                    Button {
                        start()
                    } label: {
                        Text("开始配对")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(busy)
                }
            }
            .navigationTitle("配对桌面端")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
            .onChange(of: team.pairingState) { state in
                if state == .paired { dismiss() }
            }
        }
    }

    private func parsePasted() {
        localError = nil
        guard let payload = BridgePairingPayload.parse(pasted) else {
            localError = "不是有效的配对资料 JSON（应包含 url 与 code）"
            return
        }
        url = payload.url
        code = payload.code
    }

    private func start() {
        localError = nil
        let trimmedUrl = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedUrl.isEmpty else {
            localError = "请填写服务器地址"
            return
        }
        guard !trimmedCode.isEmpty else {
            localError = "请填写配对码"
            return
        }
        team.pair(url: trimmedUrl, code: trimmedCode)
    }
}

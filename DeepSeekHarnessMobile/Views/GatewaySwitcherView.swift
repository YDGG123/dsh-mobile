import SwiftUI

struct GatewaySwitcherBar: View {
    @Environment(\.colorScheme) private var colorScheme
    /// 主题感知色板（浅色下墨色、深色下白色叠加）。
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    @EnvironmentObject private var hosts: MultiGatewayStore
    @State private var showsHosts = false

    /// 2/5-a：网关行按参考实现 S01 重排为**独立整行**（不再是小胶囊）——
    /// 它回答「我连的哪台机器」，与工作区卡（哪个项目）必须是两行（决策文档裁定 4）。
    /// 结构：DSHStatus（状态点+状态字，既有 gateway.state.label 文案）· 地址（等宽）· 相对时间 ›。
    /// 主机名优先：`GatewayProfile.displayName`（别名优先、否则网关名）——多网关时
    /// 域名（ydgg.dpdns.org）认不出是哪台机器，别名（如「家里 Mac」）才认得出。
    /// 没有 displayName 才回落 host:port 地址。
    var body: some View {
        let connected = hosts.activeStore.gateway.state.isConnected
        return Button { showsHosts = true } label: {
            HStack(spacing: DSHSpacing.sm) {
                DSHStatus(kind: connected ? .online : .failed, text: hosts.activeStore.gateway.state.label)
                if let hostLabel {
                    Text("·").appFont(DSHType.footnote).foregroundStyle(palette.ink(0.3))
                    Text(hostLabel)
                        .appFont(DSHType.mono)
                        .foregroundStyle(palette.ink(0.5))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: DSHSpacing.sm)
                // 断网时参考实现显示「重连中」（S01 gatewayLine）；连接时显示相对时长。
                Text(trailingText)
                    .appFont(DSHType.meta)
                    .foregroundStyle(palette.ink(0.4))
                    .lineLimit(1)
                Image(systemName: "chevron.forward")
                    .appFont(DSHType.meta.weight(.semibold))
                    .foregroundStyle(palette.ink(0.3))
            }
            .padding(.horizontal, DSHSpacing.md)
            .frame(minHeight: 40)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("当前主机：\(hosts.activeProfile?.displayName ?? String(localized: "未选择"))，切换主机")
        .sheet(isPresented: $showsHosts) { GatewaySwitcherSheet() }
    }

    private var trailingText: String {
        if hosts.activeStore.gateway.state.isConnected,
           let lastConnected = hosts.activeProfile?.lastConnectedAt {
            return lastConnected.formatted(.relative(presentation: .named))
        }
        return String(localized: "重连中")
    }

    /// 行左侧显示的主机标识：优先 `displayName`（别名/网关名），没有才回落地址。
    private var hostLabel: String? {
        if let name = hosts.activeProfile?.displayName, !name.isEmpty { return name }
        return activeEndpoint
    }

    /// 设计稿此行显示「一眼能认」的地址（如 `192.168.1.12:3080`）：取首选端点
    /// （缺省回落第一个），剥掉协议头与路径，只留 `域名[:端口]`——
    /// wss 默认 443 / ws 默认 80 时省略端口，非默认端口才带（Chief 裁定 2026-10-29）。
    private var activeEndpoint: String? {
        guard let profile = hosts.activeProfile,
              let raw = profile.preferredEndpoint ?? profile.connectionEndpoints.first,
              var parts = URLComponents(string: raw),
              let host = parts.host?.lowercased(), !host.isEmpty else { return nil }
        parts.path = ""
        let scheme = parts.scheme?.lowercased()
        let isDefaultPort = (scheme == "wss" && parts.port == 443) || (scheme == "ws" && parts.port == 80)
        if let port = parts.port, !isDefaultPort { return "\(host):\(port)" }
        return host
    }
}

private struct GatewaySwitcherSheet: View {
    @EnvironmentObject private var hosts: MultiGatewayStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var editMode: EditMode = .inactive
    @State private var selectedIDs: Set<String> = []
    @State private var confirmingDelete = false
    @State private var editing: GatewayProfile?
    @State private var alias = ""
    @State private var kind = "desktopcomputer"

    private var displayedProfiles: [GatewayProfile] {
        guard
            let activeID = hosts.activeID,
            let activeProfile = hosts.profiles.first(where: { $0.id == activeID })
        else { return hosts.profiles }
        return [activeProfile] + hosts.profiles.filter { $0.id != activeID }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(displayedProfiles) { profile in
                        HStack(spacing: 12) {
                            if editMode.isEditing {
                                Button { toggleSelection(profile.id) } label: {
                                    Image(systemName: selectedIDs.contains(profile.id) ? "circle.inset.filled" : "circle")
                                        .appFont(.system(size: 20, weight: .medium))
                                        .frame(width: 28, height: 44)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(selectedIDs.contains(profile.id)
                                    ? String(localized: "host.deselect", defaultValue: "取消选择 \(profile.displayName)")
                                    : String(localized: "host.select", defaultValue: "选择 \(profile.displayName)"))
                                .transition(.move(edge: .leading).combined(with: .opacity))
                            }
                            Image(systemName: profile.deviceKind)
                                .appFont(.title3).frame(width: 26)
                            Button {
                                if editMode.isEditing {
                                    toggleSelection(profile.id)
                                } else {
                                    hosts.select(profile)
                                    dismiss()
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(profile.displayName).foregroundStyle(Color(uiColor: .label)).lineLimit(1)
                                    Text(hosts.activeID == profile.id ? String(localized: "当前主机") : String(localized: "点击连接"))
                                        .appFont(.caption)
                                        .foregroundStyle(
                                            hosts.activeID == profile.id
                                                ? Color.accentColor
                                                : Color(uiColor: .secondaryLabel)
                                        )
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                            Circle().fill(hosts.onlineIDs.contains(profile.id) ? Color.green : Color.gray.opacity(0.45))
                                .frame(width: 8, height: 8)
                                .accessibilityLabel(hosts.onlineIDs.contains(profile.id)
                                    ? String(localized: "在线") : String(localized: "未连接或不可用"))
                            if editMode.isEditing {
                                Button {
                                    alias = profile.alias ?? ""
                                    kind = profile.deviceKind
                                    editing = profile
                                } label: {
                                    Image(systemName: "pencil")
                                        .appFont(.system(size: 18, weight: .medium))
                                        .frame(width: 44, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("编辑 \(profile.displayName)")
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                            }
                        }
                        .padding(.vertical, 2)
                        .animation(.easeInOut(duration: 0.2), value: editMode.isEditing)
                    }
                } header: { Text("我的主机").foregroundStyle(Color(uiColor: .secondaryLabel)) }
                if hosts.profiles.isEmpty {
                    Text("暂无已连接的主机")
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
            }
            .environment(\.editMode, $editMode)
            .alert(selectedIDs.count == 1 ? String(localized: "删除主机？") : String(localized: "批量删除主机？"), isPresented: $confirmingDelete) {
                Button("取消", role: .cancel) {}
                Button("删除", role: .destructive) {
                    hosts.remove(ids: selectedIDs)
                    selectedIDs.removeAll()
                }
            } message: {
                Text("从 App 移除选中的 \(selectedIDs.count) 台主机及连接凭证；网关上的会话和工作区不会被删除。再次连接需重新配对。")
            }
            .navigationTitle("切换主机")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(editMode.isEditing ? String(localized: "完成编辑") : String(localized: "编辑")) {
                        withAnimation {
                            if editMode.isEditing { selectedIDs.removeAll() }
                            editMode = editMode.isEditing ? .inactive : .active
                        }
                    }
                }
                if editMode.isEditing, !selectedIDs.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(selectedIDs.count == 1 ? String(localized: "删除") : String(localized: "批量删除"), role: .destructive) {
                            confirmingDelete = true
                        }
                        .foregroundStyle(.red)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            .task(id: scenePhase) {
                guard scenePhase == .active else { hosts.stopProbes(); return }
                while !Task.isCancelled {
                    await hosts.refreshPresence()
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                }
            }
            .onDisappear { hosts.stopProbes() }
            .sheet(item: $editing) { profile in
                NavigationStack {
                    Form {
                        Section {
                            TextField(profile.gatewayName, text: $alias)
                            HStack {
                                Text("主机类型")
                                Spacer()
                                Menu {
                                    Picker("主机类型", selection: $kind) {
                                        Label("电脑", systemImage: "desktopcomputer").tag("desktopcomputer")
                                        Label("服务器", systemImage: "server.rack").tag("server.rack")
                                    }
                                } label: {
                                    HStack(alignment: .center, spacing: 4) {
                                        Image(systemName: kind)
                                            .appFont(.system(size: 15))
                                            .frame(width: 20, height: 20)
                                        Text(kind == "server.rack" ? String(localized: "服务器") : String(localized: "电脑"))
                                            .appFont(.body)
                                        Image(systemName: "chevron.up.chevron.down")
                                            .appFont(.system(size: 11, weight: .medium))
                                    }
                                }
                                .accessibilityLabel("主机类型")
                                .accessibilityValue(kind == "server.rack" ? String(localized: "服务器") : String(localized: "电脑"))
                            }
                        } header: {
                            Text("留空使用网关提供的名称：\(profile.gatewayName)")
                                .appFont(.caption)
                                .foregroundStyle(Color(uiColor: .secondaryLabel))
                                .textCase(nil)
                        }
                    }
                    .navigationTitle("编辑主机")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("取消") { editing = nil } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("保存") { hosts.edit(profile, alias: alias, kind: kind); editing = nil }
                        }
                    }
                }
                .foregroundStyle(Color(uiColor: .label))
                .tint(Color(uiColor: .label))
                .presentationDetents([.medium])
            }
        }
        .foregroundStyle(Color(uiColor: .label))
        .tint(Color(uiColor: .label))
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func toggleSelection(_ id: String) {
        if selectedIDs.contains(id) {
            selectedIDs.remove(id)
        } else {
            selectedIDs.insert(id)
        }
    }
}

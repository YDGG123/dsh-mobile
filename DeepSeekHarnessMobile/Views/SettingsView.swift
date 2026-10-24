import SwiftUI
import WebKit
import MarkdownUI

/// 诊断「逃逸口」键：**有意保留，不是死码**。
///
/// U6 按用户要求把设置里的四个诊断 Toggle 撤掉了（用户诉求是"别在设置里添乱"），
/// 但底层的三个读取点仍然在职：`RootIdentityBinding`、`WorkspaceCardComposition`、
/// `HarnessAnimatedBackground`。保留它们的原因只有一个——将来再遇到"呈现/转场/背景"
/// 类问题，维护者还能用一条命令做二分，不必重新加 UI、重新出包：
///
/// ```
/// defaults write com.lichaofan.dshmobile dsh.diag.noCompositing -bool YES   # 关离屏合成+阴影
/// defaults write com.lichaofan.dshmobile dsh.diag.noRootIdentity -bool YES  # 关根 identity 绑定
/// defaults write com.lichaofan.dshmobile dsh.background.layers -string none # 关全部背景图层
/// defaults delete com.lichaofan.dshmobile dsh.diag.noCompositing             # 复位
/// ```
///
/// 默认值全部是「关闭诊断 = 正常形态」；没有 UI 入口，普通用户不会误触。
/// **请勿把这些键当作无引用死码清掉**——删了就只能改代码、重新出包才能二分。
/// `resetLegacyTogglesIfNeeded()` 负责在升级后首次启动时清掉旧版本 Toggle 留下的值。
enum SettingsDiagnostics {
    /// `none` 关掉全部背景图层（也读同名环境变量 `DSH_BACKGROUND_LAYERS`）。
    static let backgroundKey = "dsh.background.layers"
    /// 关掉工作区的「离屏合成 + 双层实时阴影」（B4 候选 2：呈现时快照撞上离屏合成）。
    static let compositingKey = "dsh.diag.noCompositing"
    /// 关掉根视图的 `.id(ObjectIdentifier(activeStore))`（B4 候选 1：整棵树身份绑在实例指针上）。
    static let rootIdentityKey = "dsh.diag.noRootIdentity"

    /// 一次性清理标记：清理过就不再重复，保证 `defaults write` 设进去的值不会被下次启动抹掉。
    private static let legacyResetKey = "dsh.diag.legacyReset.v1"

    /// U6 升级清理（幂等）。
    ///
    /// 上个版本设置里有四个 Toggle，用户做过二分实验，键可能已被拨成「开」。
    /// 新版本没有 UI 能关回去 → App 会永远带着「关离屏合成 / 关根 identity 绑定 /
    /// 关背景图层」跑，且用户无从察觉。所以升级后**首次**启动把这些键复位一次。
    /// 复位之后仍可用上面的 `defaults write` 手动设，逃逸口不受影响。
    static func resetLegacyTogglesIfNeeded(_ defaults: UserDefaults = .standard) {
        guard !defaults.bool(forKey: legacyResetKey) else { return }
        // 前三个是当前键；后两个是旧 Toggle 自己的存储键，一并清掉免得残留。
        let keys = [
            compositingKey,
            rootIdentityKey,
            backgroundKey,
            "dsh.diag.backgroundOff",
            "dsh.diag.settingsSheet",
        ]
        for key in keys { defaults.removeObject(forKey: key) }
        defaults.set(true, forKey: legacyResetKey)
    }
}

struct SettingsView: View {
    @Environment(\.openURL) private var openURL
    @EnvironmentObject private var store: AppStore
    @State private var observedGatewayState: ConnectionState?
    @State private var showsPrivacyPolicy = false
    /// 外观两项改为「点行 → 弹出选项层」，避免 .navigationLink Picker 的推入卡死。
    @State private var showsInterfaceOptions = false
    @State private var showsLanguageOptions = false
    @State private var showsLanguageRestartAlert = false
    /// sheet 关闭后再弹「需重启」提示（iOS 16 时序保险）。
    @State private var pendingLanguageRestart = false
    @State private var showsReleaseNotes = false
    /// 新会话默认配置三项 + 字体大小：用户裁决一律不走推入，改用紧凑弹层。
    @State private var showsPresetOptions = false
    @State private var showsModelOptions = false
    @State private var showsPermissionOptions = false
    @State private var showsFontSizePanel = false
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    private let releaseInfo = BundledAppReleaseInfo.load()

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知"
    }

    private var connectedGatewayVersion: String? {
        guard gatewayState.isConnected, !store.pluginsLoading, store.pluginsError == nil else { return nil }
        return store.pluginCatalog?.bundles.first(where: { $0.name == "dsh-plugin-mobile-gateway" })?.version
    }

    private func openRelease(_ repository: String, version: String?) {
        Task {
            let root = URL(string: "https://github.com/Clarklevis1995/\(repository)")!
            let fallback = repository == "dsh-mobile" ? root.appendingPathComponent("releases") : root
            guard let version, !version.isEmpty else { openURL(fallback); return }
            let tag = version.hasPrefix("v") ? version : "v\(version)"
            let candidate = root.appendingPathComponent("releases/tag").appendingPathComponent(tag)
            var request = URLRequest(url: candidate, timeoutInterval: 8)
            request.httpMethod = "HEAD"
            let response = try? await URLSession.shared.data(for: request)
            let status = (response?.1 as? HTTPURLResponse)?.statusCode ?? 0
            openURL((200..<300).contains(status) ? candidate : fallback)
        }
    }

    private func releaseRow(_ title: String, value: String, repository: String, version: String?) -> some View {
        Button { openRelease(repository, version: version) } label: {
            HStack {
                Text(LocalizedStringKey(title))
                Spacer()
                Text(verbatim: value).foregroundStyle(.secondary)
                Image(systemName: "chevron.right").appFont(.footnote).foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, minHeight: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var gatewayState: ConnectionState {
        observedGatewayState ?? store.gateway.state
    }

    private var selectedPresetName: String {
        guard let id = store.agentPresetDefault else { return String(localized: "未读取") }
        return store.agentPresets.first(where: { $0.id == id })?.displayName ?? id
    }

    private var selectedPermissionName: String {
        guard let id = store.permissionDefault else { return String(localized: "未读取") }
        return store.permissionDefaultOptions.first(where: { $0.value == id })?.name ?? id
    }

    private var defaultsAreLoading: Bool {
        !store.defaultConfigurationLoadingKinds.isDisjoint(with: ["defaults", "agent-presets"])
    }

    private var defaultModelIsLoading: Bool {
        store.defaultConfigurationLoadingKinds.contains("default-model")
            || store.defaultConfigurationLoadingKinds.contains("save-default-model")
    }

    private var defaultModelValueText: String {
        guard let selection = store.defaultModelSelection else { return String(localized: "未读取") }
        let item = store.anyModelCatalog?.groups
            .first(where: { $0.id == selection.provider })?
            .models.first(where: { $0.id == selection.model })
        let name = item?.name ?? Self.modelDisplayName(selection.model)
        guard let effort = selection.reasoningEffort else { return name }
        let effortName = item?.reasoning?.efforts.first(where: { $0.id == effort })?.name ?? Self.reasoningEffortDisplayName(effort)
        return "\(name) · \(effortName)"
    }

    static func modelDisplayName(_ id: String) -> String {
        switch id {
        case "deepseek-chat": return "DeepSeek Chat"
        case "deepseek-reasoner": return "DeepSeek Reasoner"
        default: return id
        }
    }

    static func reasoningEffortDisplayName(_ id: String) -> String {
        switch id.lowercased() {
        case "low": return String(localized: "低")
        case "medium": return String(localized: "中")
        case "high": return String(localized: "高")
        default: return id.capitalized
        }
    }

    var body: some View {
        Form {
            Section {
                // 用户裁决：这四项一律不走推入——既避开「推入 × 设置」的死锁面，
                // 也让设置页保持一屏可读完。选项用紧凑弹层（同「界面 / 语言」的形态）。
                Button {
                    showsPresetOptions = true
                } label: {
                    DefaultConfigurationRow(
                        title: String(localized: "Agent 预设"),
                        value: selectedPresetName,
                        isLoading: defaultsAreLoading
                    )
                }
                .buttonStyle(.plain)
                .disabled(!gatewayState.isConnected)
                .accessibilityIdentifier("default-agent-preset-row")

                Button {
                    showsModelOptions = true
                } label: {
                    DefaultConfigurationRow(
                        title: String(localized: "默认模型"),
                        value: defaultModelValueText,
                        isLoading: defaultModelIsLoading
                    )
                }
                .buttonStyle(.plain)
                .disabled(!gatewayState.isConnected)
                .accessibilityIdentifier("default-model-row")

                Button {
                    showsPermissionOptions = true
                } label: {
                    DefaultConfigurationRow(
                        title: String(localized: "权限"),
                        value: selectedPermissionName,
                        isLoading: defaultsAreLoading
                    )
                }
                .buttonStyle(.plain)
                .disabled(!gatewayState.isConnected || store.defaultConfigurationLoadingKinds.contains("set-default"))
                .accessibilityIdentifier("default-permission-row")
            } header: {
                Text("新会话默认配置")
            } footer: {
                Text("与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。")
            }

            Section("Mobile Gateway") {
                TextField("ws://host:3080/ws/mobile", text: $store.endpoint)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                HStack {
                    ConnectionDot(state: gatewayState)
                    Text(gatewayState.label)
                    Spacer()
                    if let port = store.gateway.serverPort {
                        Text("Port \(port)").foregroundStyle(.secondary)
                    }
                }
                Button(gatewayState.isConnected ? String(localized: "断开连接") : String(localized: "连接")) {
                    if gatewayState.isConnected {
                        store.disconnect()
                    } else {
                        store.connect()
                    }
                }
                Button("Ping 网关") { store.gateway.ping() }
            }

            if let host = store.hostSnapshot {
                Section("DSH Host") {
                    LabeledContent("版本", value: [host.dshVersion, host.version].compactMap { $0 }
                        .first { $0.range(of: "^v?[0-9]+\\.[0-9]+", options: .regularExpression) != nil } ?? "未提供")
                    LabeledContent("已连接会话", value: "\(host.attachedSessions ?? 0)")
                    if let cwd = host.cwd {
                        LabeledContent {
                            Text(cwd)
                                .appFont(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .textSelection(.enabled)
                        } label: {
                            Text("cwd")
                        }
                    }
                }
            }

            Section {
                // 规则：这里**不要**用 .navigationLink 的 Picker——它为每个选项推入子页面，
                // 与全局动画背景（DeepOceanBackground）组合会在真机上卡死；.inline 又会平铺所有选项。
                // 采用第三种形态：点行 → 弹出选项 sheet（不导航推入、不平铺）。
                Button {
                    showsInterfaceOptions = true
                } label: {
                    settingsValueRow(title: "界面", value: store.interfaceStyle.title)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("appearance-style-row")
                Button {
                    showsLanguageOptions = true
                } label: {
                    // 切换后进程内仍是旧语言（iOS 只在启动时读 AppleLanguages），
                    // 所以就在值后面标出来，不用用户猜。
                    settingsValueRow(title: "语言", value: store.appLanguage.title + languageRestartSuffix)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("appearance-language-row")
                Button {
                    showsFontSizePanel = true
                } label: {
                    HStack {
                        Text("字体大小")
                        Spacer()
                        Text(verbatim: fontFollowsSystem ? String(localized: "跟随系统")
                            : "\(Int((AppFontScale.normalized(fontScale) * 100).rounded()))%")
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("font-size-settings-row")
            } header: {
                Text("外观")
            } footer: {
                Text("语言设置将在重新启动应用后生效。")
            }

            Section("关于") {
                releaseRow("当前版本", value: appVersion, repository: "dsh-mobile", version: appVersion)
                releaseRow("要求网关版本", value: releaseInfo.map { "≥ \($0.minimumGatewayVersion)" } ?? "未提供",
                           repository: "dsh-plugin-mobile-gateway", version: releaseInfo?.minimumGatewayVersion)
                releaseRow("已连接网关版本", value: !gatewayState.isConnected ? "未连接" :
                    store.pluginsLoading ? "读取中…" : connectedGatewayVersion ?? "未提供",
                    repository: "dsh-plugin-mobile-gateway", version: connectedGatewayVersion)
                Button("本版本新功能") { showsReleaseNotes = true }
                Button("隐私政策") { showsPrivacyPolicy = true }
            }

            Section {
                // 唯一保留的诊断入口：一次推入，页内只有一个 Text（无请求、无布局、无暂停修饰符）。
                // 它卡 → 问题在导航推入本身；它正常 → 问题在原先那四个设置子页的内容。
                NavigationLink {
                    BlankPushTestView()
                } label: {
                    Label("推入空白测试页", systemImage: "rectangle.dashed")
                }
                .accessibilityIdentifier("diag-blank-push")
            } header: {
                Text("诊断")
            } footer: {
                Text("点进去只有一个文本，不发请求、不做布局。若这一页也卡死，说明问题在「推入」本身；若正常，说明原来的问题在那些设置子页的内容里。")
            }
        }
        .navigationTitle("设置")
        // B4：容器级推入页同样整屏盖住背景，推入期间暂停动画（与设置子页同一策略）。
        .pausesBackgroundAnimation()
        .task {
            store.refreshDefaultConfiguration()
            if gatewayState.isConnected { store.refreshPlugins() }
        }
        .onReceive(store.gateway.$state) { state in
            let wasConnected = observedGatewayState?.isConnected ?? state.isConnected
            observedGatewayState = state
            if !wasConnected && state.isConnected {
                store.refreshPlugins()
                store.refreshDefaultConfiguration()
            }
        }
        .sheet(isPresented: $showsReleaseNotes) {
            NavigationStack {
                ScrollView {
                    Markdown(releaseInfo?.version == appVersion ? releaseInfo!.notes : "本版本发布说明暂未提供，请查看 GitHub 发布页。")
                        .padding(20)
                }
                .navigationTitle("本版本新功能")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { showsReleaseNotes = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("GitHub 发布页") { openRelease("dsh-mobile", version: nil) }
                    }
                }
            }
        }
        .sheet(isPresented: $showsInterfaceOptions) {
            OptionListSheet(
                title: "界面",
                options: InterfaceStyle.allCases.map { OptionListSheet.Option(id: $0.rawValue, title: $0.title) },
                selectedID: store.interfaceStyle.rawValue
            ) { id in
                if let style = InterfaceStyle(rawValue: id) { store.interfaceStyle = style }
            }
        }
        .sheet(isPresented: $showsLanguageOptions, onDismiss: {
            // iOS 16 上「sheet 还在关闭过程中就弹 alert」容易被系统吞掉，
            // 所以先记下待提示，等 sheet 真正 dismiss 之后再弹（onDismiss 时机稳定）。
            if pendingLanguageRestart {
                pendingLanguageRestart = false
                showsLanguageRestartAlert = true
            }
        }) {
            OptionListSheet(
                title: "语言",
                options: AppLanguage.allCases.map { OptionListSheet.Option(id: $0.rawValue, title: $0.title) },
                selectedID: store.appLanguage.rawValue
            ) { id in
                guard let language = AppLanguage(rawValue: id) else { return }
                let changed = language != store.appLanguage
                store.appLanguage = language
                // 用户明确要求：改完必须立刻告诉他为什么没变，而不是让他猜。
                if changed { pendingLanguageRestart = true }
            }
        }
        .sheet(isPresented: $showsPrivacyPolicy) {
            PrivacyPolicySheet()
        }
        .sheet(isPresented: $showsPresetOptions) {
            AgentPresetOptionsSheet()
        }
        .sheet(isPresented: $showsModelOptions) {
            DefaultModelOptionsSheet()
        }
        .sheet(isPresented: $showsPermissionOptions) {
            PermissionOptionsSheet()
        }
        .sheet(isPresented: $showsFontSizePanel) {
            FontSizePanel()
        }
        .alert("语言已切换", isPresented: $showsLanguageRestartAlert) {
            // 程序化退出（exit(0)）App Review 不推荐，但这里**只有用户点了这个按钮**才会执行：
            // iOS 没有公开的「重启本 App」API，而语言只在启动时读取 AppleLanguages，
            // 与其让用户手动去多任务里找，不如提供一键退出；不点则完全不发生。
            Button("退出 App") { exit(0) }
            Button("稍后重启", role: .cancel) {}
        } message: {
            Text("界面语言只在 App 启动时读取系统语言设置，因此需要重启一次才会切换：请从后台把 App 彻底划掉再打开。\n\n如果重启后仍是中文，请检查 iOS「设置 → App → 语言」是否被单独设置过。")
        }
    }

    /// 值后面那句「需重启」：只在用户选择与进程实际生效语言不一致时出现。
    private var languageRestartSuffix: String {
        guard store.appLanguage != .system else { return "" }
        return store.appLanguage == AppLanguage.effective ? "" : " · 需重启后生效"
    }

    /// 「标题 + 当前值 + 右侧箭头」的设置行（点开弹出选项层用）。
    private func settingsValueRow(title: LocalizedStringKey, value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(verbatim: value).foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
}

/// 字体大小：底部紧凑面板，不推入。
/// 刻意不在面板里写「校正值」：原页面 `onAppear` 会把越界的值写回 `@AppStorage`，
/// 而该键被根级 `AppFontScope` 读取并应用到 `.dynamicTypeSize` —— 写入会让整棵树重排，
/// 正好落在推入转场里。这里只由用户拖动写值，正常路径不产生额外的根部失效。
private struct FontSizePanel: View {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    @Environment(\.colorScheme) private var colorScheme
    @State private var fontFeedback = UIImpactFeedbackGenerator(style: .medium)

    private var sliderFontScale: Binding<Double> {
        Binding(
            get: { AppFontScale.normalized(fontScale) },
            set: { value in
                guard !fontFollowsSystem else { return }
                let oldTick = Int((AppFontScale.normalized(fontScale) * 20).rounded())
                let newTick = Int((value * 20).rounded())
                fontScale = min(1.5, max(0.8, value))
                if oldTick != newTick {
                    fontFeedback.impactOccurred()
                    fontFeedback.prepare()
                }
            }
        )
    }

    private var bubbleColor: Color {
        colorScheme == .dark ? Color(red: 0.11, green: 0.18, blue: 0.29)
            : Color(red: 0.93, green: 0.95, blue: 1)
    }

    private var percentText: String {
        fontFollowsSystem ? String(localized: "跟随系统")
            : AppFontScale.normalized(fontScale) == 1 ? String(localized: "标准")
            : "\(Int((AppFontScale.normalized(fontScale) * 100).rounded()))%"
    }

    var body: some View {
        CompactSheet(title: String(localized: "字体大小"), detents: [.height(392)]) {
            VStack(spacing: 18) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Spacer(minLength: 0)
                        Text("预览文字大小")
                            .appFont(.system(size: 17))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .background(bubbleColor, in: UnevenRoundedRectangle(
                                topLeadingRadius: 22, bottomLeadingRadius: 22,
                                bottomTrailingRadius: 6, topTrailingRadius: 22
                            ))
                    }
                    Text("调整后会应用到所有页面，包括对话、按钮和代码。")
                        .appFont(.system(size: 15))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Toggle("跟随系统", isOn: $fontFollowsSystem)
                    .tint(DSHColor.ocean)
                    .appFont(.system(size: 16))
                    .accessibilityIdentifier("font-size-follow-system")

                VStack(spacing: 8) {
                    HStack {
                        Text(verbatim: "A").appFont(.system(size: 14, weight: .medium))
                        Spacer()
                        Text(verbatim: percentText).appFont(.system(size: 14))
                        Spacer()
                        Text(verbatim: "A").appFont(.system(size: 22, weight: .medium))
                    }
                    .foregroundStyle(.secondary)
                    Slider(value: sliderFontScale, in: 0.8...1.5, step: 0.05) { editing in
                        if editing { fontFeedback.prepare() }
                    }
                        .disabled(fontFollowsSystem)
                        .tint(Color(uiColor: .systemGray3))
                        .accessibilityIdentifier("font-size-slider")
                        .accessibilityLabel("字体大小")
                        .accessibilityValue("\(Int((AppFontScale.normalized(fontScale) * 100).rounded()))%")
                    Button("恢复默认") { fontScale = 1 }
                        .appFont(.system(size: 13))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("font-size-reset")
                }
                .disabled(fontFollowsSystem)
                .opacity(fontFollowsSystem ? 0.45 : 1)
            }
            .padding(.horizontal, 20)
            .padding(.top, 6)
            .padding(.bottom, 18)
        }
    }
}

private struct PrivacyPolicySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isLoading = true
    @State private var loadFailed = false
    @State private var reloadID = UUID()

    private let url = URL(string: "https://clarklevis1995.github.io/dsh-mobile/privacy/")!

    var body: some View {
        NavigationStack {
            ZStack {
                PrivacyPolicyWebView(url: url, isLoading: $isLoading, loadFailed: $loadFailed)
                    .id(reloadID)
                    .opacity(loadFailed ? 0 : 1)
                    .allowsHitTesting(!loadFailed)

                if isLoading {
                    ProgressView()
                }

                if loadFailed {
                    VStack(spacing: 12) {
                        Text("无法打开隐私政策")
                        Button("重试") {
                            loadFailed = false
                            isLoading = true
                            reloadID = UUID()
                        }
                    }
                }
            }
            .navigationTitle("隐私政策")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

private struct PrivacyPolicyWebView: UIViewRepresentable {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    let url: URL
    @Binding var isLoading: Bool
    @Binding var loadFailed: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(isLoading: $isLoading, loadFailed: $loadFailed)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.pageZoom = CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.pageZoom = CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var isLoading: Bool
        @Binding var loadFailed: Bool

        init(isLoading: Binding<Bool>, loadFailed: Binding<Bool>) {
            _isLoading = isLoading
            _loadFailed = loadFailed
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            isLoading = true
            loadFailed = false
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            handleLoadError(error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            handleLoadError(error)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if let url = navigationAction.request.url, url.scheme == "mailto" {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
        ) {
            if navigationResponse.isForMainFrame,
               let response = navigationResponse.response as? HTTPURLResponse,
               response.statusCode >= 400 {
                isLoading = false
                loadFailed = true
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        private func handleLoadError(_ error: Error) {
            guard (error as NSError).code != NSURLErrorCancelled else { return }
            isLoading = false
            loadFailed = true
        }
    }
}

private struct DefaultConfigurationRow: View {
    let title: String
    let value: String
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .foregroundStyle(.primary)
            Spacer(minLength: 12)
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(value)
                    .appFont(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

private struct DefaultPermissionChoice: Identifiable, Hashable {
    let id: String
    let title: String
    let description: String

    init(_ option: GatewayPermissionOption) {
        id = option.value
        title = option.name
        description = option.description ?? option.name
    }
}

private struct PendingDefaultModelChange: Identifiable {
    let id = UUID()
    let provider: String
    let providerName: String
    let model: String
    let modelName: String
    let reasoningEffort: String?
    let effortName: String?

    var summary: String {
        var text = "\(providerName) · \(modelName)"
        if let effortName { text += " · \(effortName)" }
        return text
    }
}

private struct BundledAppReleaseInfo: Decodable {
    let version: String
    let minimumGatewayVersion: String
    var notes: String = ""

    private enum CodingKeys: String, CodingKey { case version, minimumGatewayVersion }

    static func load() -> BundledAppReleaseInfo? {
        guard let metadataURL = Bundle.main.url(forResource: "release-info", withExtension: "json"),
              let notesURL = Bundle.main.url(forResource: "release-notes", withExtension: "md"),
              let data = try? Data(contentsOf: metadataURL),
              var info = try? JSONDecoder().decode(Self.self, from: data),
              let notes = try? String(contentsOf: notesURL, encoding: .utf8) else { return nil }
        info.notes = notes
        return info
    }
}

/// 紧凑弹层容器：标题 + 关闭，内容自管。
/// 刻意不套 `NavigationStack`——本次改造就是要让设置里的这些选项彻底离开「推入」这条路径。
private struct CompactSheet<Content: View>: View {
    let title: String
    let detents: Set<PresentationDetent>
    let content: () -> Content

    @Environment(\.dismiss) private var dismiss

    init(title: String, detents: Set<PresentationDetent>, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.detents = detents
        self.content = content
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title).appFont(.headline)
                Spacer()
                Button(String(localized: "关闭")) { dismiss() }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)

            Divider()

            content()
        }
        .presentationDetents(detents)
        .presentationDragIndicator(.visible)
    }
}

/// 弹层里的单行选项（标题 + 可选副标题 + 勾选）。
private struct CompactOptionRow: View {
    let title: String
    var subtitle: String? = nil
    var selected: Bool = false
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).foregroundStyle(.primary)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .appFont(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(DSHColor.ocean)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.5 : 1)
    }
}

/// 弹层里的加载/空态一行（带可选重试）。
private struct CompactStatusRow: View {
    let text: String
    var isLoading = false
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                if isLoading { ProgressView() }
                Text(text).foregroundStyle(.secondary)
            }
            if let retry {
                Button(String(localized: "重试"), action: retry)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

/// Agent 预设：紧凑选项弹层（原来是一整页推入）。
private struct AgentPresetOptionsSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var pendingPreset: GatewayAgentPreset?

    private var isLoading: Bool {
        store.defaultConfigurationLoadingKinds.contains("agent-presets") && store.agentPresets.isEmpty
    }

    private var isChangingDefault: Bool {
        store.defaultConfigurationLoadingKinds.contains("set-default")
    }

    var body: some View {
        CompactSheet(title: String(localized: "Agent 预设"), detents: [.medium, .large]) {
            if isLoading {
                CompactStatusRow(text: String(localized: "正在读取 Agent 预设…"), isLoading: true)
            } else if store.agentPresets.isEmpty {
                CompactStatusRow(
                    text: store.agentPresetsLoadError ?? String(localized: "没有可用的 Agent 预设")
                ) { store.retryAgentPresets() }
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(store.agentPresets) { preset in
                            CompactOptionRow(
                                title: preset.displayName,
                                subtitle: preset.broken == true
                                    ? String(localized: "该预设存在配置错误，暂时不能设为默认值")
                                    : preset.displayDescription,
                                selected: preset.id == store.agentPresetDefault,
                                disabled: preset.broken == true || isChangingDefault
                            ) {
                                pendingPreset = preset
                            }
                            Divider().padding(.leading, 20)
                        }
                    }
                }
                .alert(item: $pendingPreset) { preset in
                    Alert(
                        title: Text("设为全局默认预设？"),
                        message: Text(String(localized: "defaults.preset.confirm.body", defaultValue: "将“\(preset.displayName)”设为新会话的默认 Agent 预设。这会更新部署级设置，并同步影响 WebUI。")),
                        primaryButton: .cancel(Text("取消")),
                        secondaryButton: .default(Text("设为默认")) {
                            store.setDefaultAgentPreset(preset.id)
                            dismiss()
                        }
                    )
                }
            }
        }
        .task {
            if store.agentPresets.isEmpty { store.refreshDefaultConfiguration() }
        }
    }
}

/// 默认模型：紧凑选项弹层（模型列表 + 思考等级 chips，原来是一整页推入）。
private struct DefaultModelOptionsSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var pendingChange: PendingDefaultModelChange?

    private var modelGroups: [GatewayModelGroup] { store.anyModelCatalog?.groups ?? [] }
    private var isLoadingCatalog: Bool {
        store.sessionControlLoadingKinds.contains("models") && modelGroups.isEmpty
    }
    private var isBusy: Bool {
        store.defaultConfigurationLoadingKinds.contains("save-default-model")
    }

    var body: some View {
        CompactSheet(title: String(localized: "默认模型"), detents: [.medium, .large]) {
            if isLoadingCatalog {
                CompactStatusRow(text: String(localized: "正在读取模型列表…"), isLoading: true)
            } else if modelGroups.isEmpty {
                CompactStatusRow(text: String(localized: "暂无可用模型")) {
                    store.ensureModelCatalogForDefaults()
                }
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        ForEach(modelGroups) { group in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(group.name)
                                    .appFont(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 20)
                                ForEach(group.models) { model in
                                    modelRow(group: group, model: model)
                                }
                            }
                        }
                    }
                    .padding(.vertical, 14)
                }
                .alert(item: $pendingChange) { change in
                    Alert(
                        title: Text("修改默认模型？"),
                        message: Text(String(localized: "defaults.model.confirm.body", defaultValue: "将新会话的默认模型改为“\(change.summary)”。这会更新部署级设置，并同步影响 WebUI。")),
                        primaryButton: .cancel(Text("取消")),
                        secondaryButton: .default(Text("确认修改")) {
                            store.saveDefaultModel(
                                provider: change.provider,
                                model: change.model,
                                reasoningEffort: change.reasoningEffort
                            )
                            dismiss()
                        }
                    )
                }
            }
        }
        .task { store.ensureModelCatalogForDefaults() }
    }

    @ViewBuilder
    private func modelRow(group: GatewayModelGroup, model: GatewayModelItem) -> some View {
        let efforts = model.reasoning?.efforts ?? []
        let selected = store.defaultModelSelection?.provider == group.id
            && store.defaultModelSelection?.model == model.id
        let currentEffort = store.defaultModelSelection?.reasoningEffort

        VStack(alignment: .leading, spacing: 6) {
            Button {
                pendingChange = change(group: group, model: model, effort: nil)
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(model.name).foregroundStyle(.primary)
                    Spacer(minLength: 0)
                    if selected {
                        Text("当前使用")
                            .appFont(.caption.weight(.semibold))
                            .foregroundStyle(Color(uiColor: .systemBackground))
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(Color.primary, in: Capsule())
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 9)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isBusy)

            if !efforts.isEmpty {
                HStack(spacing: 8) {
                    Text("思考等级")
                        .appFont(.caption)
                        .foregroundStyle(.secondary)
                    ForEach(efforts) { effort in
                        let isCurrent = selected && effort.id == currentEffort
                        Button {
                            pendingChange = change(group: group, model: model, effort: effort)
                        } label: {
                            Text(effort.name)
                                .appFont(.caption.weight(.medium))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(isCurrent ? Color.primary : Color.secondary.opacity(0.12), in: Capsule())
                                .foregroundStyle(isCurrent ? Color(uiColor: .systemBackground) : .primary)
                        }
                        .buttonStyle(.plain)
                        .disabled(isBusy)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 4)
            }
        }
    }

    private func change(
        group: GatewayModelGroup,
        model: GatewayModelItem,
        effort: GatewayReasoningEffort?
    ) -> PendingDefaultModelChange {
        let efforts = model.reasoning?.efforts ?? []
        let retained = store.defaultModelSelection?.reasoningEffort.flatMap { current in
            efforts.contains(where: { $0.id == current }) ? current : nil
        }
        let effortId = effort?.id ?? retained ?? model.reasoning?.defaultEffort
        return PendingDefaultModelChange(
            provider: group.id,
            providerName: group.name,
            model: model.id,
            modelName: model.name,
            reasoningEffort: effortId,
            effortName: effortId.flatMap { id in efforts.first(where: { $0.id == id })?.name }
        )
    }
}

/// 权限：紧凑选项弹层（原来是一个 Menu）。
private struct PermissionOptionsSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var pendingPermission: DefaultPermissionChoice?

    var body: some View {
        CompactSheet(title: String(localized: "权限"), detents: [.medium]) {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(store.permissionDefaultOptions.map(DefaultPermissionChoice.init)) { option in
                        CompactOptionRow(
                            title: option.title,
                            subtitle: option.description,
                            selected: option.id == store.permissionDefault
                        ) {
                            pendingPermission = option
                        }
                        Divider().padding(.leading, 20)
                    }
                }
            }
            .alert(item: $pendingPermission) { option in
                Alert(
                    title: Text("修改全局默认权限？"),
                    message: Text(String(localized: "defaults.permission.confirm.body", defaultValue: "将新会话的默认权限改为“\(option.title)”。这会更新部署级设置，并同步影响 WebUI。")),
                    primaryButton: .cancel(Text("取消")),
                    secondaryButton: .default(Text("确认修改")) {
                        store.setDefaultPermission(option.id)
                        dismiss()
                    }
                )
            }
        }
    }
}

/// 诊断用：一次「纯推入」。没有请求、没有定时器、没有滚动容器、也没有暂停背景的修饰符——
/// 页内唯一变量就是「推入」这件事本身。
private struct BlankPushTestView: View {
    var body: some View {
        VStack {
            Text("空白测试页")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle("空白测试页")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("diag-blank-page")
    }
}

/// 通用选项弹出层：点一行即选中并关闭。
/// 刻意不使用 NavigationStack 推入——历史 bug 是「导航推入 × 全局动画背景」导致卡死。
private struct OptionListSheet: View {
    struct Option: Identifiable {
        let id: String
        let title: String
    }

    let title: String
    let options: [Option]
    let selectedID: String
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title).appFont(.headline)
                Spacer()
                Button(String(localized: "关闭")) { dismiss() }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)

            Divider()

            List(options) { option in
                Button {
                    onSelect(option.id)
                    dismiss()
                } label: {
                    HStack {
                        Text(verbatim: option.title).foregroundStyle(.primary)
                        Spacer()
                        if option.id == selectedID {
                            Image(systemName: "checkmark").foregroundStyle(DSHColor.ocean)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
        }
        .presentationDetents([.medium, .large])
    }
}

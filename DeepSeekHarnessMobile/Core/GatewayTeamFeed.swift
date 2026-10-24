import Foundation
import SwiftUI
import UIKit

/// 网关团队帧客户端（手机端团队页的新数据源）。
///
/// 协议见 `dsh-plugin-mobile-gateway` 的 `docs/mobile-v1-integration.md`「团队投影帧与卡片帧」与
/// ds-bot `docs/roster-publish-v1.md`（v2）：
/// - WS `/mobile/v1/team`，首帧 `{"type":"auth","token":…}`（**不需要** conversationId）
/// - 服务端回 `ready` → `snapshot` → `delta`（带 `base`）/ `order` / `card*`
/// - `delta.base` 必须等于本地 `seq`（否则丢弃并重取快照）；`instanceId` 变更同样重取
/// - `order` 帧只带排序键（网关会话 `updatedAt`），**不占**契约 seq
/// - `degraded == true` 表示拿不到实时态（名册只有名字/角色）——界面必须显式标注，
///   不能渲染成「全部空闲」
///
/// iOS 16 红线：URLSessionWebSocketTask（iOS 13+）、不用 @Observable、不用双参 onChange。
@MainActor
final class GatewayTeamFeed: ObservableObject {
    enum Phase: Equatable {
        case idle
        case needsPairing      // Keychain 里没有可用会话
        case connecting
        case live
        case offline(String)
    }

    struct Work: Hashable {
        var roomId: String?
        var tool: String?
        var target: String?
        var note: String?
        var said: String?
        var since: Int64?
    }

    struct Approval: Hashable, Identifiable {
        var sessionId: String
        var tool: String?
        var reason: String?
        var at: Int64?
        var id: String { "\(sessionId):\(tool ?? ""):\(at ?? 0)" }
    }

    struct Pending: Hashable {
        var kind: String
        var sessionId: String
        var roomId: String?
        var at: Int64?
    }

    struct Waiting: Hashable {
        var bots: [String] = []
        var rooms: [String] = []
        var forWhom: [String] = []
    }

    struct Row: Identifiable, Hashable {
        enum Kind: String { case bot, room }
        var id: String
        var kind: Kind
        var name: String
        var role: String?
        var sessionId: String
        var main: Bool = false
        var pinned: Bool = false
        var members: [String] = []
        var admin: String?
        var mode: String?
        // 易变
        var activity: String?          // nil = 服务端没提供（降级态）
        var work: Work?
        var approvals: [Approval] = []
        var pending: [Pending] = []
        var waiting: Waiting?
        var preview: String?
        var updatedAt: Int64?
        /// 静态字段：电脑端 rosters 的 `avatar` 是 `{ shape, image }` 对象（不是单个 data URL）。
        /// shape 是角色形状 id（whale/star/…，缺省时电脑端按 bot id 推导），image 是可选图片。
        var avatarShape: String?
        var avatarImage: String?
        /// ds-bot 调色板 key（契约明写「手机端需同名映射」），默认块颜色优先用它。
        var colorKey: String?
        var placeholderCards: Int = 0
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var rows: [Row] = []
    @Published private(set) var degraded = false
    @Published private(set) var lastError: String?
    @Published private(set) var seq: Int = 0
    @Published private(set) var hasMainBot: Bool = false

    private var socket: URLSessionWebSocketTask?
    private var session: URLSession?
    private var instanceId: String = ""
    private var mainBotIds: [String] = []
    private var orderKeys: [String: Int64] = [:]
    private var reconnectTask: Task<Void, Never>?
    private var reconnectDelay: TimeInterval = 1
    private var wantLive = false
    /// 每次连上都自增；旧连接的回调据此丢弃（避免重连后旧 socket 的 onClose 把状态打回 offline）。
    private var generation = 0
    private let log: (String) -> Void

    init(log: @escaping (String) -> Void = { _ in }) {
        self.log = log
    }

    // MARK: - 生命周期

    func start() {
        wantLive = true
        guard sessionResource() != nil else {
            phase = .needsPairing
            return
        }
        connect()
    }

    func stop() {
        wantLive = false
        reconnectTask?.cancel()
        reconnectTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        phase = .idle
    }

    func reload() {
        guard wantLive else { return }
        connect()
    }

    // MARK: - 连接

    private struct Resource {
        var token: String
        var baseURL: URL
    }

    /// 复用配对时落进 Keychain 的 mobile-v1 会话（bridge 那条 token），不另存一份凭证。
    private func sessionResource() -> Resource? {
        let store = BridgeKeychainSessionStore()
        guard let json = store.loadJson(), let data = json.data(using: .utf8) else { return nil }
        struct Wire: Decodable {
            let accessToken: String
            let serverUrl: String?
            let expiresAt: Int64?
        }
        guard let wire = try? JSONDecoder().decode(Wire.self, from: data), !wire.accessToken.isEmpty else { return nil }
        if let expiresAt = wire.expiresAt, expiresAt > 0, expiresAt < Int64(Date().timeIntervalSince1970 * 1000) {
            return nil   // 过期就先按未配对处理，由 bridge 侧的刷新链路补
        }
        let raw = (wire.serverUrl?.isEmpty == false ? wire.serverUrl! : "https://127.0.0.1")
        guard let base = URL(string: raw) else { return nil }
        return Resource(token: wire.accessToken, baseURL: base)
    }

    private func teamURL(_ resource: Resource) -> URL? {
        var components = URLComponents(url: resource.baseURL, resolvingAgainstBaseURL: false)
        components?.scheme = resource.baseURL.scheme == "http" ? "ws" : "wss"
        // 配对资料里的 serverUrl 必须是根 origin（App 侧已校验），这里只补路径。
        let prefix = (components?.path ?? "").hasSuffix("/mobile/v1") ? (components?.path ?? "") : "/mobile/v1"
        components?.path = prefix + "/team"
        components?.query = nil
        components?.fragment = nil
        return components?.url
    }

    private func connect() {
        reconnectTask?.cancel()
        reconnectTask = nil
        guard let resource = sessionResource() else {
            phase = .needsPairing
            return
        }
        guard let url = teamURL(resource) else {
            phase = .offline("配对地址无效，无法建立团队连接。")
            return
        }
        generation += 1
        let mine = generation
        socket?.cancel(with: .goingAway, reason: nil)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        let session = URLSession(configuration: config)
        self.session = session
        let task = session.webSocketTask(with: url)
        socket = task
        phase = rows.isEmpty ? .connecting : phase
        task.resume()
        task.send(.string("{\"type\":\"auth\",\"token\":\"\(resource.token)\"}")) { [weak self] error in
            guard let self, let error else { return }
            Task { @MainActor in self.handleDisconnect(mine, reason: error.localizedDescription) }
        }
        receive(mine: mine)
    }

    private func receive(mine: Int) {
        guard let socket, mine == generation else { return }
        socket.receive { [weak self] result in
            guard let self else { return }
            Task { @MainActor in
                guard mine == self.generation else { return }
                switch result {
                case .failure(let error):
                    self.handleDisconnect(mine, reason: error.localizedDescription)
                case .success(let message):
                    switch message {
                    case .string(let text): self.handleFrame(text)
                    case .data(let data): self.handleFrame(String(data: data, encoding: .utf8) ?? "")
                    @unknown default: break
                    }
                    self.receive(mine: mine)
                }
            }
        }
    }

    private func handleDisconnect(_ mine: Int, reason: String) {
        guard mine == generation else { return }
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        if case .needsPairing = phase { return }
        // 会话过期（4401）时先让 bridge 侧刷新一次 token，再重连。
        if reason.lowercased().contains("unauthorized") || reason.contains("4401") {
            lastError = "团队连接被拒（会话可能已过期），正在重新配对会话…"
        }
        phase = .offline(reason.isEmpty ? "连接中断" : reason)
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard wantLive else { return }
        reconnectTask?.cancel()
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, 30)
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            await MainActor.run { self.connect() }
        }
    }

    // MARK: - 帧

    private func handleFrame(_ text: String) {
        guard let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        if let type = object["type"] as? String {
            if type == "ready" {
                reconnectDelay = 1
                if let instance = object["instanceId"] as? String { instanceId = instance }
                phase = .live
                lastError = nil
                return
            }
            if type == "error" {
                let message = (object["error"] as? [String: Any])?["message"] as? String ?? "团队流被拒绝"
                lastError = message
                return
            }
            return
        }
        guard let kind = object["kind"] as? String else { return }
        switch kind {
        case "snapshot": applySnapshot(object)
        case "delta": applyDelta(object)
        case "order": applyOrder(object)
        case "card", "card-snapshot": applyCards(object)
        default: break
        }
        if phase == .live || phase == .connecting { phase = .live }
    }

    private func applySnapshot(_ object: [String: Any]) {
        let frameInstance = object["instanceId"] as? String ?? instanceId
        instanceId = frameInstance
        mainBotIds = (object["mainBotIds"] as? [String]) ?? []
        if mainBotIds.isEmpty, let single = object["mainBotId"] as? String { mainBotIds = [single] }
        degraded = object["degraded"] as? Bool == true
        seq = (object["seq"] as? NSNumber)?.intValue ?? 0
        var next: [Row] = []
        for raw in (object["bots"] as? [[String: Any]]) ?? [] {
            if let row = row(from: raw, kind: .bot) { next.append(row) }
        }
        for raw in (object["rooms"] as? [[String: Any]]) ?? [] {
            if let row = row(from: raw, kind: .room) { next.append(row) }
        }
        rows = next
        applySnapshotOrderFallback()
        phase = .live
        lastError = nil
    }

    /// snapshot 里已经带 updatedAt（网关补的），作为 order 帧到达前的排序依据。
    private func applySnapshotOrderFallback() {
        for index in rows.indices {
            if let stamp = rows[index].updatedAt { orderKeys[rows[index].sessionId] = stamp }
        }
    }

    private func applyOrder(_ object: [String: Any]) {
        guard let sessions = object["sessions"] as? [String: Any] else { return }
        var next: [String: Int64] = [:]
        for (key, value) in sessions {
            if let number = value as? NSNumber { next[key] = number.int64Value }
        }
        orderKeys.merge(next) { _, new in new }
        objectWillChange.send()
    }

    private func pendingKey(_ row: Row) -> String { row.id }

    private func applyCards(_ object: [String: Any]) {
        // 卡片帧暂时只用来点「等你回答」的红点：正文渲染仍走 bridge 的提问卡片通道。
        let cards: [[String: Any]]
        if let list = object["cards"] as? [[String: Any]] {
            cards = list
        } else {
            cards = ((object["ops"] as? [[String: Any]]) ?? []).compactMap { $0["card"] as? [String: Any] }
        }
        var counts: [String: Int] = [:]
        for card in cards {
            let owner = (card["roomId"] as? String) ?? (card["botId"] as? String) ?? ""
            if !owner.isEmpty { counts[owner, default: 0] += 1 }
        }
        for index in rows.indices {
            rows[index].placeholderCards = counts[pendingKey(rows[index])] ?? 0
        }
    }

    private func applyDelta(_ object: [String: Any]) {
        let frameInstance = object["instanceId"] as? String ?? instanceId
        if !instanceId.isEmpty, frameInstance != instanceId {
            // host 重启：seq 空间重来，必须重取快照（契约 §6）。
            requestResync(reason: "instanceChanged")
            return
        }
        let base = (object["base"] as? NSNumber)?.intValue
        if let base, base != seq {
            requestResync(reason: "baseMismatch(base=\(base),local=\(seq))")
            return
        }
        let newSeq = (object["seq"] as? NSNumber)?.intValue ?? seq
        guard let ops = object["ops"] as? [[String: Any]] else { return }
        var table = Dictionary(uniqueKeysWithValues: rows.map { ($0.id, $0) })
        var order = rows.map(\.id)
        for op in ops {
            guard let name = op["op"] as? String else { continue }
            let id = op["id"] as? String ?? ""
            switch name {
            case "bot.add", "room.add":
                guard let entity = op["entity"] as? [String: Any],
                      let row = row(from: entity, kind: name.hasPrefix("bot") ? .bot : .room) else { continue }
                if table[row.id] == nil { order.append(row.id) }
                table[row.id] = row
            case "bot.update", "room.update":
                guard let entity = op["entity"] as? [String: Any],
                      var row = row(from: entity, kind: name.hasPrefix("bot") ? .bot : .room) else { continue }
                // update 是完整对象；保留帧里没带的易变值，避免结构变更把实时态清空。
                if let existing = table[row.id] {
                    row.activity = row.activity ?? existing.activity
                    row.work = row.work ?? existing.work
                    if row.approvals.isEmpty { row.approvals = existing.approvals }
                    if row.pending.isEmpty { row.pending = existing.pending }
                    if row.waiting == nil { row.waiting = existing.waiting }
                    row.placeholderCards = existing.placeholderCards
                }
                if table[row.id] == nil { order.append(row.id) }
                table[row.id] = row
            case "bot.patch", "room.patch":
                guard var row = table[id], let set = op["set"] as? [String: Any] else { continue }
                merge(set, into: &row)
                table[id] = row
            case "bot.remove", "room.remove":
                table.removeValue(forKey: id)
                order.removeAll { $0 == id }
            case "main.set":
                // 主 Bot 影响排序（sortedRows 把 main 排最前），所以要把表里的行标记同步过来。
                mainBotIds = (op["mainBotIds"] as? [String]) ?? mainBotIds
                for (key, var value) in table {
                    value.main = mainBotIds.contains(key)
                    table[key] = value
                }
            default:
                continue
            }
        }
        rows = order.compactMap { table[$0] }
        seq = newSeq
        applySnapshotOrderFallback()
    }

    private func merge(_ set: [String: Any], into row: inout Row) {
        if set.keys.contains("activity") { row.activity = set["activity"] as? String }
        if let work = set["work"] { row.work = workValue(work) }
        if let approvals = set["approvals"] as? [[String: Any]] {
            row.approvals = approvals.compactMap { approval(from: $0) }
        }
        if let pending = set["pending"] as? [[String: Any]] {
            row.pending = pending.compactMap { pendingValue($0) }
        }
        if let waiting = set["waiting"] as? [String: Any] { row.waiting = waitingValue(waiting) }
        if let preview = set["preview"] as? String { row.preview = preview }
        if let stamp = (set["lastActivityAt"] as? NSNumber)?.int64Value { row.updatedAt = row.updatedAt ?? stamp }
        if set.keys.contains("name"), let name = set["name"] as? String { row.name = name }
        if set.keys.contains("pinned"), let pinned = set["pinned"] as? Bool { row.pinned = pinned }
    }

    private func requestResync(reason: String) {
        log("team feed: resync \(reason)")
        seq = 0
        connect()
    }

    // MARK: - 解码

    private func row(from raw: [String: Any], kind: Row.Kind) -> Row? {
        guard let id = raw["id"] as? String, !id.isEmpty else { return nil }
        let name = raw["name"] as? String ?? id
        let sessionId = raw["sessionId"] as? String ?? id
        var row = Row(id: id, kind: kind, name: name, role: raw["role"] as? String, sessionId: sessionId)
        row.pinned = raw["pinned"] as? Bool ?? false
        row.main = (raw["main"] as? Bool ?? false) || mainBotIds.contains(id)
        row.members = (raw["members"] as? [String]) ?? []
        row.admin = raw["admin"] as? String
        row.mode = raw["mode"] as? String
        row.activity = raw["activity"] as? String
        if let work = raw["work"] { row.work = workValue(work) }
        row.approvals = ((raw["approvals"] as? [[String: Any]]) ?? []).compactMap { approval(from: $0) }
        row.pending = ((raw["pending"] as? [[String: Any]]) ?? []).compactMap { pendingValue($0) }
        if let waiting = raw["waiting"] as? [String: Any] { row.waiting = waitingValue(waiting) }
        row.preview = raw["preview"] as? String
        row.updatedAt = (raw["updatedAt"] as? NSNumber)?.int64Value
        // avatar 可能是 `{ shape, image }`（当前契约实际形态），也可能是旧式单个 data URL 字符串。
        if let avatar = raw["avatar"] as? [String: Any] {
            row.avatarShape = (avatar["shape"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            row.avatarImage = (avatar["image"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        } else if let single = raw["avatar"] as? String, !single.isEmpty {
            row.avatarShape = nil
            row.avatarImage = single
        }
        row.colorKey = raw["color"] as? String
        return row
    }

    private func workValue(_ raw: Any) -> Work? {
        if let list = raw as? [[String: Any]] {
            // 群聊的 work 是数组（每个成员一条），取第一条作为行内摘要。
            guard let first = list.first else { return nil }
            return workValue(first)
        }
        guard let dict = raw as? [String: Any], !dict.isEmpty else { return nil }
        return Work(roomId: dict["roomId"] as? String, tool: dict["tool"] as? String,
                    target: dict["target"] as? String, note: dict["note"] as? String,
                    said: dict["said"] as? String, since: (dict["since"] as? NSNumber)?.int64Value)
    }

    private func approval(from raw: [String: Any]) -> Approval? {
        guard let sessionId = raw["sessionId"] as? String else { return nil }
        return Approval(sessionId: sessionId, tool: raw["tool"] as? String, reason: raw["reason"] as? String,
                        at: (raw["at"] as? NSNumber)?.int64Value)
    }

    private func pendingValue(_ raw: [String: Any]) -> Pending? {
        guard let kind = raw["kind"] as? String, let sessionId = raw["sessionId"] as? String else { return nil }
        return Pending(kind: kind, sessionId: sessionId, roomId: raw["roomId"] as? String,
                       at: (raw["at"] as? NSNumber)?.int64Value)
    }

    private func waitingValue(_ raw: [String: Any]) -> Waiting {
        Waiting(bots: (raw["bots"] as? [String]) ?? [], rooms: (raw["rooms"] as? [String]) ?? [],
                forWhom: (raw["for"] as? [String]) ?? [])
    }

    // MARK: - 展示

    /// 需求 1：主 Bot 最上 → 置顶 → 网关 `order`（会话 `updatedAt`）倒序 → 名字。
    var sortedRows: [Row] {
        rows.sorted { left, right in
            if left.main != right.main { return left.main }
            if left.pinned != right.pinned { return left.pinned }
            let leftStamp = orderKeys[left.sessionId] ?? left.updatedAt ?? 0
            let rightStamp = orderKeys[right.sessionId] ?? right.updatedAt ?? 0
            if leftStamp != rightStamp { return leftStamp > rightStamp }
            return left.name.localizedStandardCompare(right.name) == .orderedAscending
        }
    }

    var bots: [Row] { sortedRows.filter { $0.kind == .bot } }
    var rooms: [Row] { sortedRows.filter { $0.kind == .room } }

    /// 需求 4：名字下面那行实时状态。`nil` 表示这一行没有要显示的状态。
    func statusText(for row: Row, botNames: [String: String] = [:]) -> String? {
        if !row.pending.isEmpty || row.placeholderCards > 0 {
            let kind = row.pending.first?.kind ?? "question"
            return kind == "view" ? "等你确认卡片" : kind == "secret" ? "等你填密钥" : "等你回答"
        }
        if !row.approvals.isEmpty { return "等你审批\(row.approvals.first?.tool.map { "（\($0)）" } ?? "")" }
        if degraded, row.activity == nil { return "实时状态暂不可用（网关降级）" }
        guard let activity = row.activity else { return nil }
        switch activity {
        case "working", "thinking":
            if let work = row.work {
                let where_ = work.roomId.flatMap { botNames[$0] }.map { " · 在 \($0)" } ?? ""
                let what = work.note ?? work.target ?? work.tool ?? "干活中"
                return "正在干活：\(what)\(where_)"
            }
            return "正在干活"
        case "waiting": return "等待中"
        case "alert": return "需要你处理"
        case "idle": return "空闲"
        default: return activity
        }
    }

    /// 群聊行（需求 3）：成员 + 谁在发言 + 最后一条消息摘要。
    func roomSubtitle(for row: Row, botNames: [String: String]) -> String {
        let members = row.members.prefix(4).map { botNames[$0] ?? $0 }.joined(separator: "、")
        let extra = row.members.count > 4 ? " 等 \(row.members.count) 人" : ""
        return members.isEmpty ? "群聊" : "\(members)\(extra)"
    }

    // MARK: - 头像（静态字段，缺失时回落默认块）

    /// ds-bot 的 ink 调色板（`src/client/inks.js` 的 `INKS[k] = [light, dark]`）。
    /// **深浅两档都要抄**：电脑端 `styles.js` 深色走 `INKS[k][1]`，只抄浅色会让
    /// 深色下 19/19 与电脑端不同（QA 实测指出）。
    private static let inkPairs: [String: (light: String, dark: String)] = [
        "deepseek": ("#4D6BFE", "#6A84FF"), "charcoal": ("#141416", "#F1F2F4"),
        "graphite": ("#2D2D2D", "#D6D6DA"), "rose": ("#EC2F6B", "#FF5A7A"),
        "sky": ("#0AA8D6", "#2EE6E0"), "tangerine": ("#FF6900", "#FF8A2E"),
        "cobalt": ("#082DFF", "#5C78FF"), "black": ("#000000", "#FFFFFF"),
        "brown": ("#A27952", "#855C36"), "red": ("#FF3E51", "#E02135"),
        "orange": ("#FF781C", "#FF6700"), "yellow": ("#FFAF38", "#FF9800"),
        "green": ("#00C972", "#009957"), "cyan": ("#1CC3B0", "#00A592"),
        "blue": ("#2A92FE", "#0E74E0"), "violet": ("#A97EFE", "#804EE0"),
        "magenta": ("#FF5EB1", "#E02A88"), "gray": ("#959595", "#777777"),
        "aurora": ("#00B8D4", "#2EE6E0"),
    ]

    /// 电脑端符号墨色（`themes.js` 的 `DARK_INK`）。
    private static let accentDarkInk = "#141414"

    private static func rgb(_ hex: String) -> (Double, Double, Double)? {
        let body = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard body.count == 6, let value = UInt32(body, radix: 16) else { return nil }
        return (Double((value >> 16) & 0xFF) / 255,
                Double((value >> 8) & 0xFF) / 255,
                Double(value & 0xFF) / 255)
    }

    private static func color(hex: String) -> Color? {
        guard let (red, green, blue) = rgb(hex) else { return nil }
        return Color(red: red, green: green, blue: blue)
    }

    /// WCAG 相对亮度（与 `inks.js` 的 `luminance` 同式）。
    static func luminance(hex: String) -> Double {
        guard let (red, green, blue) = rgb(hex) else { return 0 }
        func linear(_ channel: Double) -> Double {
            channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /// 指定主题下 ink 的实际 hex（自定义 hex 两档同色）。
    static func inkHex(_ key: String?, scheme: ColorScheme) -> String? {
        guard let key, !key.isEmpty else { return nil }
        if key.hasPrefix("#") { return key.count == 7 ? key : nil }
        guard let pair = inkPairs[key.lowercased()] else { return nil }
        return scheme == .dark ? pair.dark : pair.light
    }

    /// 指定主题下的 ink 颜色（对拍测试用；视图用 `dynamicInk`）。
    static func inkColor(_ key: String?, scheme: ColorScheme) -> Color? {
        inkHex(key, scheme: scheme).flatMap(color(hex:))
    }

    /// 视图用：随主题自动解析的动态色（渲染层不用逐层加 env）。
    static func dynamicInk(_ key: String?) -> Color? {
        guard let key, !key.isEmpty else { return nil }
        if key.hasPrefix("#") { return color(hex: key) }
        guard inkPairs[key.lowercased()] != nil else { return nil }
        return Color(uiColor: UIColor { traits in
            let scheme: ColorScheme = traits.userInterfaceStyle == .dark ? .dark : .light
            return UIColor(inkColor(key, scheme: scheme) ?? neutralBlockColor)
        })
    }

    /// 电脑端 `accentInk()`：在白与近黑之间选对比度更高的那个。
    /// 规则逐字取自 `themes.js:35-36`，不要自己另发明一套。
    static func symbolInkHex(forAccentHex accent: String) -> String {
        let accentLum = luminance(hex: accent)
        let darkLum = luminance(hex: accentDarkInk)
        return pow(accentLum + 0.05, 2) > 1.05 * (darkLum + 0.05) ? accentDarkInk : "#FFFFFF"
    }

    /// 角色符号的墨色（压在真彩底上）：按当前主题的实际底色二选一。
    static func avatarInk(_ row: Row) -> Color {
        Color(uiColor: UIColor { traits in
            let scheme: ColorScheme = traits.userInterfaceStyle == .dark ? .dark : .light
            let resolved = inkHex(row.colorKey, scheme: scheme) ?? "#959595"
            return UIColor(color(hex: symbolInkHex(forAccentHex: resolved)) ?? .white)
        })
    }

    /// 角色形状 → 近似符号（电脑端是矢量角色；手机端用同一形状语义的符号 + 同色底，
    /// 不再一律首字母）。符号一律走系统可用名，iOS 16 全都有。
    static func shapeSymbol(_ shape: String?) -> String {
        switch (shape ?? "").lowercased() {
        case "whale": return "fish.fill"
        case "star": return "star.fill"
        case "moon": return "moon.fill"
        case "wave": return "water.waves"
        case "squircle": return "square.fill"
        // `zed` 在电脑端是 Bull（不是兔子）；SF Symbols 没有牛，取最接近且不撞符号的四足剪影。
        case "zed": return "tortoise.fill"
        case "hex": return "camera.macro"
        case "gumdrop": return "drop.fill"
        case "peanut": return "circle.hexagongrid.fill"
        case "saucer": return "airplane"
        case "bunny": return "hare.fill"
        case "kitty": return "pawprint.fill"
        case "beacon": return "lightbulb.fill"
        case "plus": return "plus"
        case "magnet": return "magnet"
        case "acorn": return "leaf.fill"
        case "pebbles": return "circle.grid.3x3.fill"
        case "cushion": return "square.stack.3d.up.fill"
        case "ring": return "circle.dashed"
        case "gem": return "diamond.fill"
        default: return "circle.fill"
        }
    }

    /// 旧宿主（未按 v2 契约解析）没有 `color` 时的**中性**色块。
    /// 不再按名字猜色：猜出来的值一定和电脑端不同，用户看到的就是「两边不一样」。
    static let neutralBlockColor = Color(red: 0.29, green: 0.31, blue: 0.33)

    /// room 没有 avatar 字段（契约静态列表里只有 members/admin/mode/notice）：
    /// 成员头像堆叠要用 members 去 join 各 bot 现拼，不给 room 加契约字段。
    func memberAvatars(for room: Row, limit: Int = 3) -> [Row] {
        let byId = Dictionary(uniqueKeysWithValues: rows.filter { $0.kind == .bot }.map { ($0.id, $0) })
        return room.members.prefix(limit).compactMap { byId[$0] }
    }

    /// 契约 v2 起 host 已在 roster 里解析好 `color`（ink key 或 hex），手机端**只映射**。
    /// 深浅两档都按 `colorScheme` 取（电脑端深色走 INKS[k][1]）；
    /// 真拿不到（旧宿主）时用中性色块，**不按名字猜**——猜测必然和电脑端不一致。
    static func avatarColor(_ row: Row) -> Color {
        dynamicInk(row.colorKey) ?? neutralBlockColor
    }

    /// 契约 v2 起 host 已解析好 `avatar.shape`（缺省按 bot id 推导），手机端**只映射符号**。
    /// 旧宿主缺 shape（或形状未知）时落到中性圆点 `circle.fill`，**不做名字/首字母推导**。
    static func avatarSymbol(_ row: Row) -> String {
        shapeSymbol(row.avatarShape)
    }

    /// data URL → UIImage。非 data URL（本地路径）这里不解，交给默认块。
    static func avatarImage(_ row: Row) -> UIImage? {
        guard let raw = row.avatarImage, raw.hasPrefix("data:"),
              let comma = raw.firstIndex(of: ","),
              raw[raw.startIndex..<comma].contains("base64"),
              let data = Data(base64Encoded: String(raw[raw.index(after: comma)...])),
              let image = UIImage(data: data) else { return nil }
        return image
    }

    func isLive(_ row: Row) -> Bool { row.activity == "working" || row.activity == "thinking" }
}

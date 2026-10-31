import Foundation
import os

/// 团队名册（Bot 页）的本地缓存：让 Bot 页像会话页一样"启动秒出、断网也可见"。
///
/// 为什么需要它：团队行完全来自网关实时流（WS 快照），没有缓存时断网启动必然是空列表，
/// 联网也要等握手 + 快照。会话页之所以"秒出"，是因为 `AppPreferences` 把会话列表存成
/// `UserDefaults` 里的 JSON、启动时同步水合（`AppStore` 初始化）——这里照同一套姿势做。
///
/// 设计约束：
/// - **照片头像字节不入这份 JSON**（单个数十 KB，塞 `UserDefaults` 会撑爆 512KB 上限）：
///   `avatar.image` 落盘到 `TeamAvatarFileCache`（文件名 = 内容 sha256 前 16 位），
///   JSON 里只留 `avatar.imageHash` 引用 + `hasImage` 兼容标记；
///   （2026-10-31 依据用户「打开 App 就能看到头像、电脑端变了才换」的要求，把旧的
///    「照片头像不入缓存」约束改成文件缓存，`cacheVersion` 随之升到 2）
/// - **glyph 全量保留**：20 个角色合计才 24KB，是断网时"头像可见"的关键；
/// - **带版本号**：结构里写 `cacheVersion`，对不上或解不出来一律**静默回空态**，
///   绝不让 `JSONDecoder` 的错误打断启动；
/// - 水合可以同步（几百 KB 很快），**回写必须后台**（见 `TeamRosterCacheStore.save`）。
struct TeamRosterCache {

    static let version = 2
    static let defaultTTL: TimeInterval = 7 * 24 * 60 * 60
    /// 缓存体积上限：超了就逐步裁剪，仍然超就放弃缓存（绝不写超大 JSON）。
    static let maxBytes = 512 * 1024

    /// 解出来的缓存内容。
    struct Payload: Equatable {
        /// 裁剪后的快照 JSON（可直接喂回 `applySnapshot` 的同一套解析）。
        let snapshot: [String: Any]
        let savedAt: Date
        let instanceId: String

        static func == (lhs: Payload, rhs: Payload) -> Bool {
            lhs.savedAt == rhs.savedAt && lhs.instanceId == rhs.instanceId
                && NSDictionary(dictionary: lhs.snapshot).isEqual(to: rhs.snapshot)
        }
    }

    static let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-cache")   // store 侧也要打点，放宽为 internal

    // MARK: - 写（裁剪）

    /// 把团队快照裁剪成可落盘的 JSON Data。
    /// 返回 nil 表示"不值得/不能缓存"（例如裁剪到上限仍然超大），调用方静默跳过。
    static func trimmedSnapshot(from object: [String: Any], maxBytes: Int = maxBytes) -> Data? {
        var trimmed = object
        if var bots = trimmed["bots"] as? [[String: Any]] {
            bots = bots.map(trimEntity)
            trimmed["bots"] = bots
        }
        if var rooms = trimmed["rooms"] as? [[String: Any]] {
            rooms = rooms.map(trimEntity)
            trimmed["rooms"] = rooms
        }
        if let data = try? JSONSerialization.data(withJSONObject: trimmed), data.count <= maxBytes {
            return data
        }
        // 第一轮裁剪后仍超限：去掉易变细节（工具/审批/等待），保留名字/头像/角色这些"认得出来"的字段。
        for key in ["bots", "rooms"] {
            guard var list = trimmed[key] as? [[String: Any]] else { continue }
            list = list.map { entity in
                var slim = entity
                slim.removeValue(forKey: "work")
                slim.removeValue(forKey: "approvals")
                slim.removeValue(forKey: "pending")
                slim.removeValue(forKey: "waiting")
                slim.removeValue(forKey: "preview")
                return slim
            }
            trimmed[key] = list
        }
        if let data = try? JSONSerialization.data(withJSONObject: trimmed), data.count <= maxBytes {
            log.notice("roster cache trimmed volatile fields to fit \(maxBytes, privacy: .public)B")
            return data
        }
        log.error("roster cache skipped: snapshot still exceeds \(maxBytes, privacy: .public)B after trim")
        return nil
    }

    /// 单个实体裁剪：照片字节落盘成文件、JSON 里只留内容哈希；其余字段原样保留。
    ///
    /// 落盘失败（磁盘满 / 不是 base64 data URL）也**只留 `hasImage` 标记**，不报错、不阻塞：
    /// 下次快照到了会再试一次；这期间 App 内仍用内存里的 data URL 画头像。
    private static func trimEntity(_ entity: [String: Any]) -> [String: Any] {
        var copy = entity
        if let avatar = copy["avatar"] as? [String: Any] {
            var slimAvatar = avatar
            if let image = avatar["image"] as? String, !image.isEmpty {
                if let hash = TeamAvatarFileCache.shared.store(dataURL: image) {
                    slimAvatar["imageHash"] = hash
                }
                slimAvatar["hasImage"] = true
                slimAvatar.removeValue(forKey: "image")
            } else if slimAvatar["imageHash"] == nil {
                slimAvatar.removeValue(forKey: "hasImage")
            }
            copy["avatar"] = slimAvatar
        }
        return copy
    }

    /// 快照里引用到的全部头像哈希（清理文件缓存时用：名册里没有的就是该删的）。
    static func avatarImageHashes(in snapshot: [String: Any]) -> Set<String> {
        var out: Set<String> = []
        for key in ["bots", "rooms"] {
            for entity in (snapshot[key] as? [[String: Any]]) ?? [] {
                guard let avatar = entity["avatar"] as? [String: Any],
                      let hash = avatar["imageHash"] as? String else { continue }
                out.insert(hash)
            }
        }
        return out
    }

    // MARK: - 读（校验）

    /// 解析落盘数据。**任何异常都返回 nil**（损坏、版本不符、超期），调用方回空态。
    static func load(from data: Data, now: Date = Date(), ttl: TimeInterval = defaultTTL) -> Payload? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            log.notice("roster cache ignored: unreadable payload")
            return nil
        }
        guard let cacheVersion = (root["cacheVersion"] as? NSNumber)?.intValue, cacheVersion == version else {
            log.notice("roster cache ignored: version mismatch")
            return nil
        }
        guard let savedAtMillis = (root["savedAt"] as? NSNumber)?.doubleValue, savedAtMillis > 0 else {
            log.notice("roster cache ignored: missing savedAt")
            return nil
        }
        let savedAt = Date(timeIntervalSince1970: savedAtMillis / 1000)
        guard now.timeIntervalSince(savedAt) <= ttl else {
            log.notice("roster cache ignored: expired")
            return nil
        }
        guard let snapshot = root["snapshot"] as? [String: Any] else {
            log.notice("roster cache ignored: missing snapshot")
            return nil
        }
        let instanceId = snapshot["instanceId"] as? String ?? ""
        return Payload(snapshot: snapshot, savedAt: savedAt, instanceId: instanceId)
    }

    /// 组盘：`{cacheVersion, savedAt, snapshot}`。
    static func encode(snapshot: [String: Any], instanceId: String, savedAt: Date = Date()) -> Data? {
        let wrapper: [String: Any] = [
            "cacheVersion": version,
            "savedAt": savedAt.timeIntervalSince1970 * 1000,
            "instanceId": instanceId,
            "snapshot": snapshot,
        ]
        return try? JSONSerialization.data(withJSONObject: wrapper)
    }
}

/// 团队名册缓存的文件/偏好读写。**回写一律后台**，5 秒节流，值不变就不写。
@MainActor
final class TeamRosterCacheStore {
    private let defaults: UserDefaults
    private let queue = DispatchQueue(label: "ai.dsh.mobile.ios.team-roster-cache", qos: .utility)
    private var lastSavedAt: Date?

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private func key(_ serverKey: String) -> String { serverKey + ".roster-snapshot" }

    /// 同步读（只读几百 KB，启动路径上用；调用方负责不阻塞太久）。
    func load(serverKey: String, now: Date = Date()) -> TeamRosterCache.Payload? {
        guard !serverKey.isEmpty, let data = defaults.data(forKey: key(serverKey)) else { return nil }
        return TeamRosterCache.load(from: data, now: now)
    }

    /// 后台回写：**只有 5 秒时间节流**（内容没变也会写一次，因为这里没有做内容比对——
    /// 别把这条注释读成"有指纹去重"）；**裁剪与序列化都在后台队列**，不阻塞主线程。
    func save(serverKey: String, snapshot: [String: Any], instanceId: String, now: Date = Date()) {
        guard !serverKey.isEmpty else { return }
        if let lastSavedAt, now.timeIntervalSince(lastSavedAt) < 5 {
            TeamRosterCache.log.notice("roster cache save throttled")   // 可观测：每次 return 都留痕（2026-10-27 缓存排障）
            return
        }
        lastSavedAt = now
        let key = key(serverKey)
        let defaults = self.defaults
        queue.async {
            guard let trimmedData = TeamRosterCache.trimmedSnapshot(from: snapshot),
                  let trimmedObject = (try? JSONSerialization.jsonObject(with: trimmedData)) as? [String: Any],
                  let payload = TeamRosterCache.encode(snapshot: trimmedObject, instanceId: instanceId, savedAt: now) else {
                return
            }
            defaults.set(payload, forKey: key)
            // 名册写成功后，清掉已经没有任何 Bot 引用的头像文件（删 Bot / 换头像都靠这一条兜底）。
            TeamAvatarFileCache.shared.prune(keeping: TeamRosterCache.avatarImageHashes(in: trimmedObject))
        }
    }

    /// **同步**写（App 进后台时用）：`queue.async` 的异步写可能在进程被杀前来不及完成。
    /// `queue.sync` 与挂起的异步写串行，不会互相覆盖；绕过 5 秒节流（最后一次机会必须写）。
    func saveNow(serverKey: String, snapshot: [String: Any], instanceId: String, now: Date = Date()) {
        guard !serverKey.isEmpty else { return }
        lastSavedAt = now
        let key = key(serverKey)
        queue.sync {
            guard let trimmedData = TeamRosterCache.trimmedSnapshot(from: snapshot),
                  let trimmedObject = (try? JSONSerialization.jsonObject(with: trimmedData)) as? [String: Any],
                  let payload = TeamRosterCache.encode(snapshot: trimmedObject, instanceId: instanceId, savedAt: now) else { return }
            defaults.set(payload, forKey: key)
            TeamAvatarFileCache.shared.prune(keeping: TeamRosterCache.avatarImageHashes(in: trimmedObject))
        }
        TeamRosterCache.log.notice("roster cache saved on background (sync)")
    }

    func clear(serverKey: String) {
        guard !serverKey.isEmpty else { return }
        let key = key(serverKey)
        let defaults = self.defaults
        queue.async { defaults.removeObject(forKey: key) }
    }
}


/// 启动时间轴：一次启动每个节点只打一条（毫秒级），用来验证"Bot 页与会话区差 < 1s"。
/// 口径：第一条 mark 作为 t0，其余记相对 t0 的毫秒数——只用于**相对比较**，不做绝对承诺。
@MainActor
enum StartupTimeline {
    private static let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "startup")
    private static var origin: TimeInterval?
    private static var marked: Set<String> = []

    static func mark(_ name: String) {
        guard !marked.contains(name) else { return }
        marked.insert(name)
        let now = ProcessInfo.processInfo.systemUptime
        let base = origin ?? now
        origin = base
        let millis = Int(((now - base) * 1000).rounded())
        log.notice("startup \(name, privacy: .public) +\(millis, privacy: .public)ms")
    }
}

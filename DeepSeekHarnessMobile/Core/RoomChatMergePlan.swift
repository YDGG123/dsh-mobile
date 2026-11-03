import Foundation

/// 群聊合流（③）的**纯逻辑层**：网关条目 + bridge 气泡 → 同一条时间线。
///
/// 为什么单独抽出来、只依赖 Foundation：这一层的正确性是靠**用例**保证的
/// （去重键对不对、排序对不对、不许静默跳过），纯函数可以直接用 `swiftc` 编译跑真实产物，
/// 不需要模拟器、不需要 KMP 链接。视图/store 的适配（`ConversationItem` ↔ `RoomChatBubble`）
/// 留在调用方，避免把 UIKit 依赖拖进这一层。
///
/// **取值口径（Chief 2026-11-02 定死）**：
///   取 seq：本结构上的 `seq`（KMP 已填的类目）→ 否则从 `id` 拆（`"<sessionId>-<seq>"` 末段是数字）
///           → 两样都拿不到 ⇒ **该条目不参与去重，但必须回报给调用方去记日志**（不许静默跳过）。
/// **跨源精确键 = `(sessionId, seq)`**：
///   - 两侧 `seq` 同源（都是宿主事件 seq），bridge 行由 `group-projection.mjs:8` 用 `event.seq` 生成；
///   - ⚠️ 两侧 **id 字符串格式不同**（bridge `"sessionId:seq"` 冒号、网关 `"sessionId-seq"` 连字符），
///     所以**绝不能拿 id 直接比**（恒不等＝静默失效，症状是"同一条显示两遍"）。
///   - ⚠️ 只比 `seq` 不比 `sessionId` 也会错：多段 Bot 的两段、或同屏多个会话，`seq` 各自从头，
///     会把不同会话的同序号消息互相吞掉（症状是"某条消息凭空消失"）。两个分量都要参与。
enum RoomChatMergePlan {

    /// 合流用的轻量行（两侧都在调用方映射成它）。
    struct Bubble: Equatable {
        /// 已带命名空间的行 id（网关沿用原 id；bridge 由调用方拼 `bridge:<conversationId>:<seq|id>`）。
        let id: String
        let role: String
        let text: String
        /// KMP 已填时为源事件 seq；未填为 nil（helper 产物，见 ConventionProjection 的已知边界）。
        let seq: Int?
        let dateMillis: Int64?
        /// 群聊成员名（bridge 的 `author.name`）；用户自己发的与 bot 自身回复为 nil。
        let authorName: String?
    }

    struct Result: Equatable {
        let merged: [Bubble]
        /// 从 id 拆出 seq 的行 id —— 用于日志/统计「有多少条目走了兜底路径」。
        let seqFromID: [String]
        /// 两样都拿不到 seq 的行 id —— **必须在调用方记一条日志**，否则就是"看起来去了重、实际没去"。
        let withoutSeq: [String]
        /// 被判定为重复而丢弃的行 id（保留网关那份）。
        let dropped: [String]
    }

    /// 合成 id 的**前缀登记表**（判 seq 时必须整表排除）。
    /// ⚠️ **新增任何合成 id 前缀都必须在此登记**，否则它的末段一旦恰好是数字
    /// （`stream-text-42`）就会被拆成一个假 seq ⇒ 去重错配（症状是"偶发吞噬"）。
    /// 目前登记：`stream-`（KMP `appendStream` 的 `stream-text-/stream-reason-/stream-tool-`）。
    /// 另外两类**由别的规则覆盖、不必在此登记**：
    /// - `"<sessionId>-user-<messageId>"`（steering / 用户消息）⇒ 由 `-user-` marker 拦掉；
    /// - 视图层注入的 `switch-notice-<seq>` ⇒ 末段**就是**真 seq，拆出来是对的（有意保留）。
    private static let syntheticIDPrefixes: Set<String> = ["stream-"]

    /// 网关条目 id 的末段就是 seq（KMP `eventId(record) = "${record.sessionId}-${record.seq}"`）。
    /// **只对"事件 id"形态生效**，合成/特殊形态一律不拆（返回 nil）：
    /// - `-user-` 形态：`"<sessionId>-user-<messageId>"` ⇒ 末段是 messageId，拆出来是误读；
    /// - `syntheticIDPrefixes` 里的前缀：合成 id 没有 seq 语义（iOSTester 抓到 `stream-text-42` 会被误拆，
    ///   Chief 定：已知合成前缀直接判 null）。
    static func seqFromGatewayID(_ id: String) -> Int? {
        if id.contains("-user-") { return nil }
        if syntheticIDPrefixes.contains(where: { id.hasPrefix($0) }) { return nil }
        guard let last = id.split(separator: "-").last, !last.isEmpty,
              last.allSatisfy({ $0.isASCII && $0.isNumber }), let value = Int(last) else { return nil }
        return value
    }

    /// **桥行 id 的 seq**（iOS 侧自己的约定，2026-11-02 AndroidTester 查实后重写）。
    ///
    /// `BridgeChatEngine.roomChatBubble` 拼的是 `"bridge:<conversationId>:<sessionId>:<seq>"`
    /// —— **不是** Android 的 `"<sessionId>:<seq>"`。照抄安卓形态写（判前缀 `"\(sessionId):"`）在 iOS 上
    /// **恒不命中**：id 以 `bridge:` 开头。这是一条"看起来对、永不生效"的死代码，用例若用错 id 形态还会绿着没用。
    ///
    /// 这里**不要求 conversationId**（`merge` 拿不到它），校验方式改成**从右往左**：
    /// 末段必须是纯 ASCII 数字（seq）、倒数第二段必须等于传入的 `sessionId`、前面至少还有一段（conversationId）。
    ///
    /// ⚠️ **为什么不硬判「恰好 4 段」**（Chief 2026-11-02 提的前提）：id 是 `bridge:<conv>:<sid>:<seq>`，
    /// 4 段只在「`<conv>` 不含冒号」时成立。实测当前**成立**（网关 `adapter.mjs:100` / `actions.mjs:48` 是
    /// `id = room.id = sessionId`，落盘样本 12 个全是 UUID、含冒号 0 个），但把判定建在这个未受保护的前提下，
    /// 一旦 room.id 换成带冒号的形态就是**永不命中的死代码**——从右取，两种形态都对。
    static func seqFromBridgeID(_ id: String, sessionId: String?) -> Int? {
        let parts = id.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count >= 4, parts[0] == "bridge" else { return nil }
        if let sessionId, !sessionId.isEmpty, parts[parts.count - 2] != Substring(sessionId) { return nil }
        let tail = parts[parts.count - 1]
        guard !tail.isEmpty, tail.count <= 18,
              tail.allSatisfy({ $0.isASCII && $0.isNumber }), let value = Int(tail) else { return nil }
        return value
    }

    /// bridge 侧 `seq` 是 Kotlin `Long?`、网关侧是 `Int` ⇒ 归一类型。
    /// ⚠️ **更正（2026-11-02，实测）**：早前写的「直接 `Int(long)` 超范围会 trap 崩进程」
    /// **在本项目目标平台（arm64 / x86_64，`Int.bitWidth == 64`）不成立** —— Int64 的值域完全
    /// 装得进 Int，两者行为相同。那条 trap 只对 32 位目标（arm64_32 / i386）成立，本项目不涉及。
    /// 这里保留 `Int(exactly:)` 仅作**防御性写法**（未来换 32 位目标时才起作用），
    /// 不再把它当作「当前平台的崩溃风险」——避免让队友按一个不存在的风险去改代码。
    /// 真正需要小心的是 **Kotlin 侧**：`Long.toInt()` 不崩、**静默截断**（两个不同 seq 截断后可能
    /// 相等 ⇒ 互相吞消息）⇒ 跨端比较建议把窄的一侧提升为 `Long`，两边都免去窄化。
    static func normalizedSeq(_ long: Int64?) -> Int? {
        guard let long else { return nil }
        return Int(exactly: long)
    }

    /// KMP 的 `epochSeconds`（秒，Double）→ 毫秒时间戳；非有限值/非法区间返回 nil（不参与排序）。
    /// 注意 KMP 有的地方已经在秒级做了 `> 1e10 ? /1000 : 原值` 的归一（`normalizeEpoch`），
    /// 所以这里**只做单位换算**，不再二次猜测单位，避免与上游口径打架。
    static func millis(fromEpochSeconds seconds: Double?) -> Int64? {
        guard let seconds, seconds.isFinite, seconds > 0 else { return nil }
        let ms = seconds * 1000
        guard ms < Double(Int64.max) else { return nil }
        return Int64(ms)
    }

    /// 合流：网关条目的相对顺序保持；bridge 里**独有的**条目按时间插入。
    ///
    /// **`sessionId` 参与的是「id 解析」而不是「去重键」**（2026-11-02 更正）：
    /// 一次调用只处理一个会话（`AppStore` 按 sessionId 分桶），所以去重键实际退化为 `seq`；
    /// 但**桥侧的 id 是 `"<sessionId>:<seq>"` 冒号形态**，不带上 sessionId 就无法只认精确形态
    /// （会造出假 seq 而互相吞消息）⇒ `sessionId` 用来做「精确冒号形态」的判定。
    /// 上一条 KDoc 写「跨源精确键 = `(sessionId, seq)`」是**目标形态**；当前单会话调用下与 seq 等价。
    static func merge(gateway: [Bubble], bridge: [Bubble], sessionId: String) -> Result {

        var gatewayKeys = Set<Int>()          // 网关侧「有 seq 的」条目 seq 集合
        var seqFromID: [String] = []
        var withoutSeq: [String] = []
        for item in gateway {
            if let s = item.seq {
                gatewayKeys.insert(s)
            } else if let s = seqFromGatewayID(item.id) {
                gatewayKeys.insert(s)
                seqFromID.append(item.id)
            } else {
                withoutSeq.append(item.id)    // 不参与去重，调用方必须记日志
            }
        }

        var dropped: [String] = []
        var extra: [Bubble] = []
        for item in bridge {
            // **桥侧也要有 id 兜底**（2026-11-02 修，与 KMP 对齐）：桥条目的 `seq` 只能来自
            // `message.seq` 字段，缺字段时 id 里的冒号形态就是唯一的 seq 来源；
            // 原先只判 `item.seq` ⇒ 桥条目缺字段时**永不去重**，同一行会显示两遍
            // （ASSISTANT 带名字 + bridge 气泡）。判据：两端对同一批输入 dropped/withoutSeq 必须相同。
            let key = item.seq ?? seqFromBridgeID(item.id, sessionId: sessionId)
            if let key, gatewayKeys.contains(key) {
                dropped.append(item.id)       // 网关那份信息更全（带卡片上下文）⇒ 保留网关
                continue
            }
            extra.append(item)
        }

        // 稳定合并：有时间的按时间升序；时间相同/缺失时**保留网关在前、bridge 追加在后**的相对顺序。
        let merged = stableMerge(gateway: gateway, extra: extra)
        return Result(merged: merged, seqFromID: seqFromID, withoutSeq: withoutSeq, dropped: dropped)
    }

    /// 稳定归并：网关数组内部顺序不动；`extra` 里每一项插到「第一个 dateMillis 更大者」之前。
    /// 缺时间的项按到达顺序放在其插入点（不参与比较，避免把无时间的流式项抛到最前）。
    ///
    /// 78 修复（2026-11-04 用户报「消息同步完顺序对不上，后发的跑到前面」）：插入完成后
    /// **最终统一按 `(seq, 原相对顺序)` 稳定排序一遍**——`seq` 是宿主事件序号，才是真序；
    /// `dateMillis` 只到毫秒，同毫秒的 reply:0/reply:1 或快速连发的消息插入顺序由循环先后
    /// 决定，不由 seq 决定（网关侧已由探针实测严格 seq 升序，乱序 100% 出在插入层）。
    /// - 有 seq 的按 seq 升序；seq 相同保持原数组相对顺序（Swift sorted 不保证稳定，
    ///   故带原始下标装饰排序）。
    /// - 无 seq 的排最后，组内保持相对顺序（withoutSeq 日志口径不变）。
    /// - `extra` 为空时**原样返回 gateway**（既有 guard 行为不变，见判据）。
    private static func stableMerge(gateway: [Bubble], extra: [Bubble]) -> [Bubble] {
        guard !extra.isEmpty else { return gateway }
        var result = gateway
        for item in extra {
            guard let t = item.dateMillis else { result.append(item); continue }
            var insertAt = result.count
            for (index, existing) in result.enumerated() {
                guard let e = existing.dateMillis else { continue }
                if e > t { insertAt = index; break }
            }
            result.insert(item, at: insertAt)
        }
        return orderedBySeqThenOriginalOrder(result)
    }

    /// 装饰排序：seq 升序 → 同 seq 保持原相对顺序 → 无 seq 殿后且组内保持原相对顺序。
    private static func orderedBySeqThenOriginalOrder(_ items: [Bubble]) -> [Bubble] {
        items.enumerated()
            .sorted { a, b in
                switch (a.element.seq, b.element.seq) {
                case let (l?, r?):
                    l == r ? a.offset < b.offset : l < r
                case (_?, nil):
                    true
                case (nil, _?):
                    false
                default:
                    a.offset < b.offset
                }
            }
            .map(\.element)
    }
}
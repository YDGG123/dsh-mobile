package com.clarklevis.dsh.shared.domain

/**
 * Bot 区群聊的**跨源合流规则**（两端同一语义；iOS 侧对应 `RoomChatMergePlan.swift`）。
 *
 * 背景：网关事件流有结构化卡片（工具/审批/推理），bridge 消息流有成员聊天文字（气泡-only）——
 * 两处各占一半，**合流是唯一能同时满足「有文字」与「有卡片」的形态**（iOS 2026-10-27 定论）。
 *
 * 规则（与 iOS 侧逐条对齐，AndroidTester/iOSTester 的判据一致）：
 * ```
 * 取 seq：item.seq → 否则从 id 拆（末段【纯数字】才拆；`-user-` 形态一律不拆，末段是 messageId）
 *         → 两样都拿不到 ⇒ 归入 withoutSeq：**仍显示、但不参与去重，且调用方必须记日志**
 * 键    ：(sessionId, seq) 两个分量缺一不可（只比 seq 会在多段/多会话同屏时互相吞掉）
 * 去重  ：bridge 命中网关已有键 ⇒ **丢弃 bridge 那份**（保留网关，信息更全）
 * 排序  ：调用方按时间插入，**不是两段拼接**；无时间的条目不得插到最前
 * ```
 *
 * 为什么纯函数：这层规则两端各写一遍（KMP 只管消息合并，不管跨源），所以必须可单测、可逐条验证。
 */
data class RoomChatBubble(
    val id: String,
    val sessionId: String,
    val seq: Long?,
    val role: String,
    val authorName: String?,
    val text: String,
    val dateMillis: Long?
)

/** 网关侧条目的最小投影：id / sessionId / seq / 时间。 */
data class GatewayTimelineRef(
    val id: String,
    val sessionId: String,
    val seq: Int?,
    val epochSeconds: Double
)

data class RoomChatMergeResult(
    /** 要去重比对的网关键（`sessionId|seq`），便于调用方与日志核对。 */
    val gatewayKeys: Set<String>,
    /** bridge 独有条目（应插入时间线）。 */
    val bubbles: List<RoomChatBubble>,
    /** 拿不到 seq 的条目：**仍插入**，但不参与去重；调用方**必须**为每条留日志。 */
    val withoutSeq: List<RoomChatBubble>,
    /**
     * **网关侧**拿不到 seq 的条目：它们只是不进去重键集合，**但同样必须留日志**。
     * 2026-11-02 自查发现：原先这类条目被 `mapNotNull` **静默丢弃**，页面上毫无痕迹——
     * 正是"看起来去了重、实际漏了"的反面形态（漏记 ≠ 漏显示，但同样是判据缺口）。
     */
    val withoutSeqGateway: List<GatewayTimelineRef> = emptyList()
)

private const val USER_ID_MARKER = "-user-"

/**
 * 已知**合成 id 前缀**：这类 id 不是事件 id、没有 seq 语义，一律不拆
 * （iOSTester 2026-11-02 指出：`stream-text-42` 这类"末段恰好是数字"会被误拆成 seq ⇒ 偶发吞噬）。
 */
private val SYNTHETIC_ID_PREFIXES = setOf("stream-")
// ⚠️ **新增合成 id 前缀必须在此登记**（AndroidTester 2026-11-02 前瞻建议）：
// `steering-` 那条目前靠 `-user-` marker 顺带覆盖，并非显式登记；将来出现 `attempt-` 之类新前缀
// 若忘了登记，就会漏拆 ⇒ 被误当 seq ⇒ 「偶发吞噬」那类难查的错。

/**
 * 从条目 id 里拆 seq：仅当末段是**纯数字**。
 * `sessionId-seq` ⇒ 取到；`sessionId-user-<messageId>` / `stream-text-<key>` ⇒ null
 * （**不得抛异常、不得默认 0**——那会变成另一个静默错值）。
 */
fun parseSeqFromTimelineId(id: String): Long? {
    if (SYNTHETIC_ID_PREFIXES.any { id.startsWith(it) }) return null
    if (id.contains(USER_ID_MARKER)) return null
    val tail = id.substringAfterLast('-', "")
    if (tail.isEmpty() || tail.length > 18) return null
    if (!tail.all { it in '0'..'9' }) return null
    return tail.toLongOrNull()
}

/**
 * 严版拆解：**要求 id 恰好是 `"$sessionId-$seq"`**（即 `eventId(record)` 的原样形态）。
 *
 * 为什么需要：宽松版只看"末段是纯数字"，而同一投影里还有 `sessionId-command-<id>` 这类 id，
 * 若 `commandId` 恰好是数字（或将来出现别的数字后缀），就会拆出一个**不属于该条目的 seq**，
 * 于是 bridge 里真正 seq 相等于该数字的消息被当成"重复"吞掉——症状就是「少一条」，且难复现。
 * 一旦 `sessionId` 已知（合流两个来源都带），就应当用这条严判，宽松版只留作无 sessionId 时的兜底。
 */
fun parseSeqFromTimelineId(id: String, sessionId: String?): Long? {
    val seq = parseSeqFromTimelineId(id) ?: return null
    if (sessionId != null && id != "$sessionId-$seq") return null
    return seq
}

fun mergeRoomChat(
    gateway: List<GatewayTimelineRef>,
    bridge: List<RoomChatBubble>
): RoomChatMergeResult {
    val withoutSeqGateway = ArrayList<GatewayTimelineRef>()
    val keys = gateway.mapNotNull { ref ->
        val seq = ref.seq?.toLong() ?: parseSeqFromTimelineId(ref.id, ref.sessionId) ?: run {
            withoutSeqGateway += ref // 不参与去重，但**不静默**：由调用方留日志
            return@mapNotNull null
        }
        key(ref.sessionId, seq)
    }.toSet()

    val bubbles = ArrayList<RoomChatBubble>()
    val withoutSeq = ArrayList<RoomChatBubble>()
    bridge.forEach { bubble ->
        val seq = bubble.seq ?: parseSeqFromTimelineId(bubble.id, bubble.sessionId)
        if (seq == null) {
            withoutSeq += bubble // 仍显示；调用方必须记日志（不许静默跳过）
        } else if (key(bubble.sessionId, seq) !in keys) {
            bubbles += bubble
        }
    }
    return RoomChatMergeResult(keys, bubbles, withoutSeq, withoutSeqGateway)
}

private fun key(sessionId: String, seq: Long): String = "$sessionId|$seq"
/**
 * 按时间把 [inserted] 插进 [existing] 之间（**不是两段拼接**；iOSTester 判据④）。
 *
 * 规则（与 iOS `RoomChatMergePlan` 的 ⑤/⑥/⑬ 同款，两端共用同一份可测逻辑）：
 * - 时间是**毫秒**；`null` 或非正视为"无时间"（NaN/≤0 一律不参与排序，避免实现间排序漂移）；
 * - 稳定：`existing` 内部顺序不变，`inserted` 中有时按时间升序插到正确位置；
 * - 无时间的 `inserted` 条目**追加到末尾**（不得插到最前）。
 */
fun <T> interleaveByTime(
    existing: List<Pair<Long?, T>>,
    inserted: List<Pair<Long?, T>>
): List<T> {
    if (inserted.isEmpty()) return existing.map { it.second }
    val timed = inserted.filter { (it.first ?: 0L) > 0L }.sortedBy { it.first }
    val untimed = inserted.filter { (it.first ?: 0L) <= 0L }
    val out = ArrayList<T>(existing.size + inserted.size)
    var next = 0
    existing.forEach { (millis, value) ->
        val existingMillis = millis?.takeIf { it > 0L }
        while (next < timed.size && existingMillis != null && (timed[next].first ?: Long.MAX_VALUE) <= existingMillis) {
            out += timed[next].second
            next++
        }
        out += value
    }
    while (next < timed.size) {
        out += timed[next].second
        next++
    }
    out += untimed.map { it.second }
    return out
}

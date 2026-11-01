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
    val withoutSeq: List<RoomChatBubble>
)

private const val USER_ID_MARKER = "-user-"

/**
 * 从条目 id 里拆 seq：仅当末段是**纯数字**。
 * `sessionId-seq` ⇒ 取到；`sessionId-user-<messageId>` / `stream-text-<key>` ⇒ null
 * （**不得抛异常、不得默认 0**——那会变成另一个静默错值）。
 */
fun parseSeqFromTimelineId(id: String): Long? {
    if (id.contains(USER_ID_MARKER)) return null
    val tail = id.substringAfterLast('-', "")
    if (tail.isEmpty() || tail.length > 18) return null
    if (!tail.all { it in '0'..'9' }) return null
    return tail.toLongOrNull()
}

fun mergeRoomChat(
    gateway: List<GatewayTimelineRef>,
    bridge: List<RoomChatBubble>
): RoomChatMergeResult {
    val keys = gateway.mapNotNull { ref ->
        val seq = ref.seq?.toLong() ?: parseSeqFromTimelineId(ref.id) ?: return@mapNotNull null
        key(ref.sessionId, seq)
    }.toSet()

    val bubbles = ArrayList<RoomChatBubble>()
    val withoutSeq = ArrayList<RoomChatBubble>()
    bridge.forEach { bubble ->
        val seq = bubble.seq ?: parseSeqFromTimelineId(bubble.id)
        if (seq == null) {
            withoutSeq += bubble // 仍显示；调用方必须记日志（不许静默跳过）
        } else if (key(bubble.sessionId, seq) !in keys) {
            bubbles += bubble
        }
    }
    return RoomChatMergeResult(keys, bubbles, withoutSeq)
}

private fun key(sessionId: String, seq: Long): String = "$sessionId|$seq"
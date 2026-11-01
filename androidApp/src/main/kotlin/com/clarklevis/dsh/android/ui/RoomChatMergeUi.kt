package com.clarklevis.dsh.android.ui

import android.util.Log
import com.clarklevis.dsh.shared.domain.BridgeChatSnapshot
import com.clarklevis.dsh.shared.domain.GatewayTimelineRef
import com.clarklevis.dsh.shared.domain.RoomChatBubble
import com.clarklevis.dsh.shared.domain.mergeRoomChat
import com.clarklevis.dsh.shared.domain.decodeBridgeChatSnapshot

/**
 * 群聊合流的**解析与插入**（vc43 ③ 接线层；规则本身在 `shared/domain/RoomChatMergePlan.kt`）。
 *
 * 三条纪律（Chief / iOSTester / AndroidTester 2026-11-02 定的）：
 * - **只读**：这里的输入只有 bridge `/messages` 的快照 JSON，**没有任何发送路径**（room 的发送走网关）；
 * - **第三档是常态**：拿不到 seq 的条目仍显示，但**每条必须留日志**（`withoutSeq` 不许静默）；
 * - **身份纪律**：`authorName != null` ⇒ 显示名字；`role == user` ⇒ 「我」；**assistant 且无 author
 *   ⇒ 不显示任何名字，绝不回落成「我」**。
 */
private const val TAG = "RoomChatMerge"

/** 把 adapter 缓存的 `BridgeChatSnapshot` JSON 解成气泡列表；解析失败返回空（不抛）。 */
internal fun decodeRoomChatBubbles(snapshotJson: String?): List<RoomChatBubble> {
    if (snapshotJson.isNullOrBlank()) return emptyList()
    val snapshot = decodeBridgeChatSnapshot(snapshotJson) ?: return emptyList()
    return snapshot.messages.map { message ->
        RoomChatBubble(
            id = message.id,
            sessionId = message.sessionId,
            seq = message.seq,
            role = message.role,
            authorName = message.author?.name?.takeIf { it.isNotBlank() },
            text = message.text,
            dateMillis = message.createdAtMillis
        )
    }
}

/**
 * 合流：网关条目 + bridge 气泡 → 要插入的 [ConversationTimelineEntry.PeerMessage] 列表。
 *
 * @param withoutSeqLogged 由调用方传入日志函数（测试可注入），**每条都要调用**。
 */
internal fun mergeRoomChatEntries(
    items: List<com.clarklevis.dsh.shared.projection.ConversationItem>,
    sessionId: String,
    bubbles: List<RoomChatBubble>,
    logWithoutSeq: (String) -> Unit = { Log.i(TAG, "bubble without seq, not deduped: $it") }
): List<ConversationTimelineEntry.PeerMessage> {
    if (bubbles.isEmpty()) return emptyList()
    val refs = items.map { item ->
        GatewayTimelineRef(id = item.id, sessionId = sessionId, seq = item.seq, epochSeconds = item.epochSeconds)
    }
    val merged = mergeRoomChat(gateway = refs, bridge = bubbles)
    // 第三档：留痕（C7-b 判据靠它）——**两侧都要记**：bridge 气泡与网关条目各记各的
    merged.withoutSeq.forEach { logWithoutSeq("bridge:${it.id}") }
    merged.withoutSeqGateway.forEach { logWithoutSeq("gateway:${it.id}") }
    return (merged.bubbles + merged.withoutSeq).map { bubble ->
        ConversationTimelineEntry.PeerMessage(
            key = bubble.id,
            sessionId = bubble.sessionId,
            seq = bubble.seq,
            role = bubble.role,
            authorName = bubble.authorName,
            text = bubble.text,
            dateMillis = bubble.dateMillis
        )
    }
}

/**
 * 把 peer 条目按时间**插入**既有条目之间（不是两段拼接，iOSTester 判据④）——
 * 排序规则在 `shared/domain/RoomChatMergePlan.interleaveByTime`（纯函数、两端共用、单测覆盖）。
 */
internal fun interleavePeerEntries(
    entries: List<ConversationTimelineEntry>,
    peers: List<ConversationTimelineEntry.PeerMessage>
): List<ConversationTimelineEntry> =
    com.clarklevis.dsh.shared.domain.interleaveByTime(
        existing = entries.map { it.epochMillisOrNull() to it },
        inserted = peers.map { it.dateMillis to it }
    )

/** 条目时间（毫秒）；拿不到返回 null（无时间的条目不参与时序比较）。 */
private fun ConversationTimelineEntry.epochMillisOrNull(): Long? = when (this) {
    is ConversationTimelineEntry.Display -> when (val display = entry) {
        is ConversationDisplayEntry.Message -> display.item.epochSeconds.positiveMillisOrNull()
        is ConversationDisplayEntry.Process -> null
    }
    is ConversationTimelineEntry.AssistantHeader -> item.epochSeconds.positiveMillisOrNull()
    is ConversationTimelineEntry.PeerMessage -> dateMillis
    else -> null
}
/**
 * epochSeconds → 毫秒；**非有限（NaN/Inf）或 ≤0 视为「无时间」**（Chief 2026-11-02 采纳的 ⑬）：
 * 让 NaN 参与比较会让排序结果在不同实现之间漂移——平时看不出来、换份数据就乱。
 */
private fun Double.positiveMillisOrNull(): Long? =
    if (!isFinite() || this <= 0.0) null else (this * 1000).toLong()

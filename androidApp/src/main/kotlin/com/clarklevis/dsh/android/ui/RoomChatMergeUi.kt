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
 * 合流的**去重签名**：只由「能参与去重键的字段」决定——`id` 与 `seq`。
 *
 * 为什么需要它（AndroidTester 2026-11-02 查出的一处缓存键漏输入）：
 * 原 memo 键只有 `(room.id, bridge 快照)`，但 `mergeRoomChatEntries` 还用 `items` 建去重键集合——
 * 网关条目走实时事件、桥快照走 8s 轮询，两者节奏不同 ⇒ 网关先到、桥还没刷新时 memo 不重算
 * ⇒ 去重用的是**旧的键集合** ⇒ 本该丢的桥气泡留着 ⇒ 同一行短暂显示两份（≤8s 后收敛）。
 *
 * 为什么不用 `items.size`：流式 token 期间**条数不变、内容在变**，用条数会让这类检查恒假（iOSDev 同款提醒）。
 * 为什么不用整个 `items`：那样每个 token 都会让合流重算（含排序），代价随流式频率上升。
 * ⇒ 用 id+seq 的滚动哈希：文本变化不影响它，条目增删或 seq 变化才变。
 */
internal fun gatewayDedupeSignature(
    items: List<com.clarklevis.dsh.shared.projection.ConversationItem>
): Int {
    var hash = 0
    items.forEach { item ->
        hash = 31 * hash + item.id.hashCode()
        hash = 31 * hash + (item.seq ?: -1)
    }
    return hash
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
    // **合流锚点（vc49）**：没有它，"not deduped 0 命中" 与 "根本没跑过合流" 不可区分（AndroidTester 2026-11-02）。
    // 三件套判据：该行出现 ⇒ withoutSeq=0 ⇒ not deduped 0 命中。空 bridge 也要打，否则整个判据在空态下恒真。
    if (bubbles.isEmpty()) {
        Log.i(TAG, "room chat merge: gateway=${items.size} bridge=0 dropped=0 withoutSeq=0 (no bridge bubbles)")
        return emptyList()
    }
    val refs = items.map { item ->
        GatewayTimelineRef(id = item.id, sessionId = sessionId, seq = item.seq, epochSeconds = item.epochSeconds)
    }
    val merged = mergeRoomChat(gateway = refs, bridge = bubbles)
    Log.i(
        TAG,
        "room chat merge: gateway=${refs.size} bridge=${bubbles.size} " +
            "dropped=${bubbles.size - merged.bubbles.size - merged.withoutSeq.size} " +
            "withoutSeq=${merged.withoutSeq.size} gatewayWithoutSeq=${merged.withoutSeqGateway.size}"
    )
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

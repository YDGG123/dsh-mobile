package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.BridgeStreamEvent
import com.clarklevis.dsh.shared.protocol.BridgeToolActivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * bridge 聊天消息归并（逐行移植 packages/mobile-sdk/index.js 的
 * mergeMessages / acceptedSubmissionMessage / applyConversationEvent）。
 *
 * 正确性红线（§6 P3 验收）：断线重连经 reset 基线不重复、流式增量幂等、
 * localSubmission 与宿主确认回执按 (sessionId, requestId) 对账。
 */
@Serializable
data class BridgeChatMessage(
    val id: String,
    val sessionId: String,
    val seq: Long? = null,
    val role: String,
    val text: String = "",
    val createdAt: JsonPrimitive? = null,
    val updatedSeq: Long? = null,
    val requestId: String? = null,
    val localSubmission: Boolean? = null,
    val delivery: String? = null,
    val pending: Boolean? = null,
    val attemptId: String? = null,
    val streamIndex: Int? = null,
    val reasoning: String? = null,
    val tool: BridgeToolActivity? = null,
    val attachments: List<JsonObject> = emptyList(),
    val systemEvent: JsonObject? = null,
    val turn: Int? = null,
    val step: Int? = null,
    val interrupted: Boolean? = null,
    /** 发送者（群聊成员名；用户自己发的没有 author）。 */
    val author: com.clarklevis.dsh.shared.protocol.BridgeAuthor? = null,
    val groupReplyId: String? = null
) {
    val createdAtMillis: Long? get() = createdAt?.content?.toLongOrNull()
}

/** 合并两条同 id 消息：[high] 的字段优先，[base] 补齐缺失（等价 {...base, ...high}）。 */
private fun highPriority(base: BridgeChatMessage, high: BridgeChatMessage): BridgeChatMessage = base.copy(
    seq = high.seq ?: base.seq,
    role = high.role.ifEmpty { base.role },
    text = high.text.ifEmpty { base.text },
    createdAt = high.createdAt ?: base.createdAt,
    updatedSeq = high.updatedSeq ?: base.updatedSeq,
    requestId = high.requestId ?: base.requestId,
    localSubmission = high.localSubmission ?: base.localSubmission,
    delivery = high.delivery ?: base.delivery,
    pending = high.pending ?: base.pending,
    attemptId = high.attemptId ?: base.attemptId,
    streamIndex = high.streamIndex ?: base.streamIndex,
    reasoning = high.reasoning ?: base.reasoning,
    tool = high.tool ?: base.tool,
    attachments = high.attachments.ifEmpty { base.attachments },
    systemEvent = high.systemEvent ?: base.systemEvent,
    turn = high.turn ?: base.turn,
    step = high.step ?: base.step,
    interrupted = high.interrupted ?: base.interrupted
)

private fun version(message: BridgeChatMessage): Long? = message.updatedSeq ?: message.seq

private fun confirmationKey(message: BridgeChatMessage): String? =
    if (message.role == "user" && message.requestId != null && message.localSubmission != true) {
        "${message.sessionId}|${message.requestId}"
    } else {
        null
    }

private fun BridgeToolActivity.merge(over: BridgeToolActivity): BridgeToolActivity = copy(
    callId = callId.ifEmpty { over.callId },
    name = name.ifEmpty { over.name },
    state = state.ifEmpty { over.state },
    durationMs = durationMs ?: over.durationMs
)

/** 归并两批消息：按 id 去重，`updatedSeq ?: seq` 判新旧，同 id 取新；结果稳定排序。 */
fun mergeBridgeMessages(
    existing: List<BridgeChatMessage>,
    incoming: List<BridgeChatMessage>
): List<BridgeChatMessage> {
    val confirmed = (existing + incoming)
        .mapNotNull { confirmationKey(it) }
        .toSet()
    val merged = LinkedHashMap<String, BridgeChatMessage>()
    for (message in existing + incoming) {
        if (message.id.isEmpty()) continue
        if (message.localSubmission == true) {
            val key = confirmationKey(message) ?: "${message.sessionId}|${message.requestId}"
            if (key in confirmed) continue
        }
        val previous = merged[message.id]
        val previousVersion = previous?.let { version(it) }
        val incomingVersion = version(message)
        val stale = previous != null &&
            previousVersion != null &&
            incomingVersion != null &&
            incomingVersion < previousVersion
        var value: BridgeChatMessage = when {
            previous == null -> message
            stale -> highPriority(base = message, high = previous) // 旧消息优先，新消息补缺
            else -> highPriority(base = previous, high = message) // 新消息优先
        }
        val previousTool = previous?.tool
        if (previousTool != null && message.tool != null) {
            var tool = if (stale) message.tool.merge(previousTool) else previousTool.merge(message.tool)
            var text = value.text
            if (tool.name == "Tool") {
                val known = listOf(previousTool, message.tool).firstOrNull { it.name != "Tool" }
                if (known != null) {
                    tool = tool.copy(name = known.name)
                    text = known.name
                }
            }
            val first = when {
                previous.seq != null && message.seq != null ->
                    if (previous.seq <= message.seq) previous else message
                else -> null
            }
            value = value.copy(
                tool = tool,
                text = text,
                seq = first?.seq ?: value.seq,
                createdAt = first?.createdAt ?: value.createdAt
            )
        }
        merged[message.id] = if (previous != null && previous == value) previous else value
    }
    val result = merged.values.sortedWith { a, b ->
        val aPending = a.pending == true
        val bPending = b.pending == true
        if (aPending != bPending) return@sortedWith if (aPending) 1 else -1
        if (a.localSubmission == true || b.localSubmission == true) {
            val diff = (a.createdAtMillis ?: 0) - (b.createdAtMillis ?: 0)
            if (diff != 0L) return@sortedWith if (diff < 0) -1 else 1
        }
        if (a.sessionId == b.sessionId && a.seq != null && b.seq != null) {
            val diff = a.seq - b.seq
            if (diff != 0L) return@sortedWith if (diff < 0) -1 else 1
        }
        val diff = (a.createdAtMillis ?: 0) - (b.createdAtMillis ?: 0)
        if (diff < 0) -1 else if (diff > 0) 1 else 0
    }
    return if (result == existing) existing else result
}

/** 宿主已确认的提交生成一条本地用户消息（等价 acceptedSubmissionMessage）。 */
fun acceptedBridgeSubmissionMessage(
    conversationId: String,
    sessionId: String,
    requestId: String,
    text: String,
    receiptStatus: String,
    receiptRequestId: String?,
    receiptConversationId: String?,
    receiptSessionId: String?,
    receiptCreatedAtMillis: Long?
): BridgeChatMessage? {
    if (receiptStatus != "accepted" || receiptRequestId != requestId) return null
    if (receiptConversationId != null && receiptConversationId != conversationId) return null
    if (receiptSessionId != null && receiptSessionId != sessionId) return null
    return BridgeChatMessage(
        id = "accepted:$requestId",
        sessionId = sessionId,
        requestId = requestId,
        role = "user",
        text = text,
        createdAt = JsonPrimitive(receiptCreatedAtMillis ?: 0L),
        seq = Long.MAX_VALUE,
        localSubmission = true
    )
}

/** 应用会话事件（reset / delta / message / status.clearAttempt）。 */
fun applyBridgeConversationEvent(
    messages: List<BridgeChatMessage>,
    event: BridgeStreamEvent,
    nowMillis: Long
): List<BridgeChatMessage> = when (event.type) {
    "reset" -> mergeBridgeMessages(messages.filter { it.pending != true }, event.messages.map { it.toChatMessage() })
    "delta" -> {
        val attemptId = event.attemptId
        val text = event.text
        if (attemptId != null && text != null) {
            val id = "stream:${event.sessionId}:$attemptId"
            val previous = messages.firstOrNull { it.id == id }
            val index = event.index
            val previousIndex = previous?.streamIndex
            if (previous != null && index != null && previousIndex != null && index < previousIndex) {
                messages
            } else {
                mergeBridgeMessages(
                    messages,
                    listOf(
                        BridgeChatMessage(
                            id = id,
                            sessionId = event.sessionId.orEmpty(),
                            seq = Long.MAX_VALUE,
                            role = "assistant",
                            text = text,
                            createdAt = previous?.createdAt ?: JsonPrimitive(nowMillis),
                            pending = true,
                            reasoning = event.reasoning ?: previous?.reasoning,
                            attemptId = attemptId,
                            streamIndex = index
                        )
                    )
                )
            }
        } else {
            messages
        }
    }
    "message" -> {
        val incoming = event.message
        if (incoming == null) {
            messages
        } else {
            val remaining = messages.filterNot { message ->
                message.pending == true &&
                    event.replaceAttemptId != null &&
                    message.attemptId == event.replaceAttemptId
            }
            mergeBridgeMessages(remaining, listOf(incoming.toChatMessage()))
        }
    }
    "status" -> {
        if (event.clearAttempt == true) {
            messages.filterNot { it.pending == true && it.attemptId == event.attemptId }
        } else {
            messages
        }
    }
    else -> messages
}

private fun com.clarklevis.dsh.shared.protocol.BridgeStreamMessage.toChatMessage(): BridgeChatMessage =
    BridgeChatMessage(
        id = id,
        sessionId = sessionId,
        seq = seq,
        role = role,
        text = text,
        createdAt = createdAt,
        reasoning = reasoning,
        tool = tool,
        attachments = attachments,
        systemEvent = systemEvent,
        author = author,
        groupReplyId = groupReplyId
    )

// ---- 平台边界：JSON 进出的聊天状态机（Swift 只持有字符串，归并全部留在 KMP）----

@Serializable
data class BridgeChatSnapshot(val messages: List<BridgeChatMessage> = emptyList())

/**
 * 快照 JSON → `BridgeChatSnapshot`；解析失败返回 null（**不抛**）。
 * 平台侧（Android UI 层）需要读快照，`wireJson` 是 internal，所以这里开一个公开入口。
 */
fun decodeBridgeChatSnapshot(snapshotJson: String): BridgeChatSnapshot? =
    runCatching {
        com.clarklevis.dsh.shared.protocol.wireJson.decodeFromString<BridgeChatSnapshot>(snapshotJson)
    }.getOrNull()

/** 历史页 → 聊天消息列表 JSON。 */
fun chatMessagesFromHistory(historyJson: String): String {
    val page = com.clarklevis.dsh.shared.protocol.parseBridgeHistory(historyJson)
    val messages = page.messages.map { it.toChatMessage() }
    return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
        BridgeChatSnapshot.serializer(),
        BridgeChatSnapshot(messages)
    )
}

/**
 * 多页历史（REST 翻页，从新到旧逐页取）→ 单一 `BridgeChatSnapshot` JSON。
 *
 * 翻页不可避免会拿到重叠条目（服务端 `cursor:null` 前最后几页常与上一页交叠），
 * 这里**按 message id 去重并保持"先出现的在前"**（页序即时间序，新→旧取完再按到达顺序排）。
 * AndroidTester 2026-11-02 的 C9 边界：**返回 null 后再请求不得出现重复条目**。
 */
fun chatMessagesFromHistoryPages(
    pages: List<String>,
    /**
     * 某页解析失败时回调 `(页序号, 异常)`——**不吞**：调用方据此打日志/自检。
     * 单页坏掉不该让整批失败，但也不该毫无痕迹（2026-11-02 自测抓到的静默吞没）。
     */
    onPageDropped: (Int, Throwable) -> Unit = { _, _ -> },
): String {
    val seen = LinkedHashMap<String, BridgeChatMessage>()
    pages.forEachIndexed { index, pageJson ->
        val page = runCatching {
            com.clarklevis.dsh.shared.protocol.parseBridgeHistory(pageJson)
        }.getOrElse { error ->
            onPageDropped(index, error)
            return@forEachIndexed
        }
        page.messages.map { it.toChatMessage() }.forEach { message ->
            // ⚠️ 这里原来写的是 `putIfAbsent`：它是 **JVM 专有**（java.util.Map 的默认方法），
            // commonMain 里在 iOS/native 目标上编不过（`:shared:compileKotlinIosX64` 直接失败）
            // ⇒ Android 的 JVM 单测全绿，iOS 却整个平台编不出来。`getOrPut` 是 common stdlib，
            // 语义相同（缺席才写入 = 保留先到的）。
            seen.getOrPut(message.id) { message }
        }
    }
    return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
        BridgeChatSnapshot.serializer(),
        BridgeChatSnapshot(seen.values.toList())
    )
}

/**
 * 两个快照**按 message id 并集合并**（[existing] 在前、[incoming] 里新出现的追加）。
 *
 * 用途：群聊只在进入时全量拉过一次；停留期间成员继续发言，轮询只取最新一页——
 * 若直接覆盖，早先几页就会消失。合并后老的保留、新的追加，交给时间线按时间排序。
 */
fun mergeChatSnapshots(existing: String?, incoming: String): String {
    val old = existing?.let { decodeBridgeChatSnapshot(it) }?.messages.orEmpty()
    val new = decodeBridgeChatSnapshot(incoming)?.messages.orEmpty()
    val seen = LinkedHashMap<String, BridgeChatMessage>()
    // 同 `mergeChatHistoryPages`：`putIfAbsent` 是 JVM 专有，native 目标编不过，用 `getOrPut`。
    old.forEach { seen.getOrPut(it.id) { it } }
    new.forEach { seen.getOrPut(it.id) { it } }
    return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
        BridgeChatSnapshot.serializer(),
        BridgeChatSnapshot(seen.values.toList())
    )
}

/** 应用 WS 事件，返回新消息列表 JSON。 */
fun chatApplyEvent(messagesJson: String, eventJson: String, nowMillis: Long): String {
    val current = runCatching {
        com.clarklevis.dsh.shared.protocol.wireJson.decodeFromString<BridgeChatSnapshot>(messagesJson)
    }.getOrNull() ?: BridgeChatSnapshot()
    val event = runCatching {
        com.clarklevis.dsh.shared.protocol.parseBridgeEvent(eventJson)
    }.getOrNull() ?: return messagesJson
    val next = applyBridgeConversationEvent(current.messages, event, nowMillis)
    if (next == current.messages) return messagesJson
    return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
        BridgeChatSnapshot.serializer(),
        BridgeChatSnapshot(next)
    )
}

/** 发送前追加本地 pending 用户消息（requestId 幂等键）。 */
fun chatAppendLocalSubmission(
    messagesJson: String,
    sessionId: String,
    requestId: String,
    text: String,
    nowMillis: Long
): String {
    val current = runCatching {
        com.clarklevis.dsh.shared.protocol.wireJson.decodeFromString<BridgeChatSnapshot>(messagesJson)
    }.getOrNull() ?: BridgeChatSnapshot()
    val local = BridgeChatMessage(
        id = "local:$requestId",
        sessionId = sessionId,
        requestId = requestId,
        role = "user",
        text = text,
        createdAt = JsonPrimitive(nowMillis),
        seq = Long.MAX_VALUE,
        localSubmission = true,
        delivery = "sending"
    )
    val next = mergeBridgeMessages(current.messages, listOf(local))
    return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
        BridgeChatSnapshot.serializer(),
        BridgeChatSnapshot(next)
    )
}

/** 宿主回执到达：accepted 则把本地 pending 换成确认消息；rejected 则标记失败。 */
fun chatApplySubmissionReceipt(
    messagesJson: String,
    conversationId: String,
    sessionId: String,
    requestId: String,
    text: String,
    receiptJson: String,
    nowMillis: Long
): String {
    val current = runCatching {
        com.clarklevis.dsh.shared.protocol.wireJson.decodeFromString<BridgeChatSnapshot>(messagesJson)
    }.getOrNull() ?: BridgeChatSnapshot()
    val root = runCatching {
        com.clarklevis.dsh.shared.protocol.wireJson.parseToJsonElement(receiptJson)
            as? kotlinx.serialization.json.JsonObject
    }.getOrNull()
    val status = (root?.get("status") as? JsonPrimitive)?.content ?: return messagesJson
    val receiptRequestId = (root["requestId"] as? JsonPrimitive)?.content
    val receiptConversationId = (root["conversationId"] as? JsonPrimitive)?.content
    val receiptSessionId = (root["sessionId"] as? JsonPrimitive)?.content
    val receiptCreatedAt = (root["createdAt"] as? JsonPrimitive)?.let {
        it.content.toLongOrNull() ?: nowMillis
    } ?: nowMillis
    if (status == "accepted") {
        val confirmed = acceptedBridgeSubmissionMessage(
            conversationId = conversationId,
            sessionId = sessionId,
            requestId = requestId,
            text = text,
            receiptStatus = status,
            receiptRequestId = receiptRequestId,
            receiptConversationId = receiptConversationId,
            receiptSessionId = receiptSessionId,
            receiptCreatedAtMillis = receiptCreatedAt
        ) ?: return messagesJson
        val remaining = current.messages.filterNot { it.id == "local:$requestId" }
        val next = mergeBridgeMessages(remaining, listOf(confirmed))
        return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
            BridgeChatSnapshot.serializer(),
            BridgeChatSnapshot(next)
        )
    }
    if (status == "rejected") {
        val next = current.messages.map {
            if (it.id == "local:$requestId") it.copy(delivery = "rejected") else it
        }
        return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
            BridgeChatSnapshot.serializer(),
            BridgeChatSnapshot(next)
        )
    }
    return messagesJson
}

/** 历史分页合并：把更早一页并进现有列表（按 id 去重，不丢实时消息）。 */
fun chatMergeHistoryPage(messagesJson: String, historyJson: String): String {
    val current = runCatching {
        com.clarklevis.dsh.shared.protocol.wireJson.decodeFromString<BridgeChatSnapshot>(messagesJson)
    }.getOrNull() ?: BridgeChatSnapshot()
    val page = com.clarklevis.dsh.shared.protocol.parseBridgeHistory(historyJson)
    val incoming = page.messages.map { it.toChatMessage() }
    val next = mergeBridgeMessages(current.messages, incoming)
    if (next == current.messages) return messagesJson
    return com.clarklevis.dsh.shared.protocol.wireJson.encodeToString(
        BridgeChatSnapshot.serializer(),
        BridgeChatSnapshot(next)
    )
}

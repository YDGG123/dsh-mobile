package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationProjectionOperation

private const val MAXIMUM_INCREMENTAL_ROW_OPERATIONS = 64

/** 历史重新校验后只传变化行；已有行次序改变时退回原子替换。 */
internal fun conversationHistoryPatch(
    sessionId: String,
    previous: List<ConversationItem>?,
    next: List<ConversationItem>,
    lastSequence: Int
): SharedConversationPatch {
    fun replacement() = SharedConversationPatch(
        sessionId = sessionId,
        replacesAll = true,
        replacementItems = next,
        lastSequence = lastSequence
    )
    if (previous == null) return replacement()
    val oldById = previous.associateBy { it.id }
    val nextById = next.associateBy { it.id }
    require(oldById.size == previous.size && nextById.size == next.size)
    val retained = previous.filter { it.id in nextById }.map { it.id }
    if (retained != next.filter { it.id in oldById }.map { it.id }) return replacement()
    val operations = mutableListOf<ConversationProjectionOperation>()
    previous.filter { it.id !in nextById }.forEach {
        operations += ConversationProjectionOperation("remove", itemId = it.id)
    }
    var retainedIndex = 0
    next.forEach { item ->
        val old = oldById[item.id]
        if (old == null) {
            val anchor = retained.getOrNull(retainedIndex)
            operations += ConversationProjectionOperation(
                kind = if (anchor == null) "insert" else "insert-before",
                item = item,
                itemId = anchor
            )
        } else {
            retainedIndex += 1
            if (old != item) operations += ConversationProjectionOperation("replace", item = item, itemId = item.id)
        }
    }
    // 高变更量使用原子替换，避免平台逐项定位和插入退化成平方级开销。
    if (operations.size > MAXIMUM_INCREMENTAL_ROW_OPERATIONS) return replacement()
    return SharedConversationPatch(sessionId = sessionId, operations = operations, lastSequence = lastSequence)
}

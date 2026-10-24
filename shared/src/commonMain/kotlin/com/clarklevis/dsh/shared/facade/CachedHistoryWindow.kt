package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.Serializable

/** 版本化的正文缓存；同步水位与向前分页游标分别保存。 */
@Serializable
data class CachedHistoryWindow(
    val schema: Int = 1,
    val sessionId: String,
    val historyFormatVersion: Int? = null,
    val persistentCursor: Int? = null,
    val hasMore: Boolean = false,
    val nextBeforeSequence: Int? = null,
    val events: List<SessionEvent> = emptyList()
) {
    fun validate(expectedSessionId: String) {
        require(schema == 1 && sessionId == expectedSessionId && sessionId.isNotBlank())
        require(persistentCursor == null || persistentCursor >= 0)
        require(nextBeforeSequence == null || nextBeforeSequence >= 0)
        require(events.all { it.sessionId == sessionId && it.seq >= 0 && it.time.isFinite() })
        require(events.zipWithNext().all { (left, right) -> left.seq < right.seq })
        require(events.none { it.event.type == "assistant/chunk" })
    }
}

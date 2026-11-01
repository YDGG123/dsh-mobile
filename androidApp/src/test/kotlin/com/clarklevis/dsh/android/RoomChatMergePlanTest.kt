package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.domain.GatewayTimelineRef
import com.clarklevis.dsh.shared.domain.RoomChatBubble
import com.clarklevis.dsh.shared.domain.mergeRoomChat
import com.clarklevis.dsh.shared.domain.parseSeqFromTimelineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bot 区群聊跨源合流规则的单测——**与 iOS 侧 `RoomChatMergePlan` 的 9 个用例一一对应**
 * （iOSCoder 2026-11-02 用真实产物编译跑过 9/9）。两端规则各写一遍，所以两边都要有可执行定义。
 */
class RoomChatMergePlanTest {

    private fun gateway(id: String, seq: Int? = null, epoch: Double = 0.0, session: String = "room-1") =
        GatewayTimelineRef(id = id, sessionId = session, seq = seq, epochSeconds = epoch)

    private fun bubble(
        id: String,
        seq: Long? = null,
        text: String = "hi",
        date: Long? = null,
        session: String = "room-1",
        role: String = "assistant"
    ) = RoomChatBubble(id = id, sessionId = session, seq = seq, role = role, authorName = "Chief", text = text, dateMillis = date)

    /** ① 同 seq：bridge 那份被丢弃（保留网关）。 */
    @Test
    fun dedupesSameSeqKeepingGateway() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("room-1-42", seq = 42)),
            bridge = listOf(bubble("room-1:42", seq = 42L))
        )
        assertTrue(result.bubbles.isEmpty())
        assertTrue(result.withoutSeq.isEmpty())
    }

    /** ② 网关 seq 为 null 但 id 形如 `sessionId-seq` ⇒ 从 id 拆出来仍能精确去重。 */
    @Test
    fun dedupesViaIdParsedSeqWhenFieldMissing() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("s-42", seq = null, session = "s")),
            bridge = listOf(bubble("s:42", seq = 42L, session = "s"))
        )
        assertEquals(42L, parseSeqFromTimelineId("s-42"))
        assertTrue(result.bubbles.isEmpty())
    }

    /** ③ 两侧都拿不到 seq ⇒ 进 withoutSeq（不静默跳过）。 */
    @Test
    fun reportsBubblesWithoutSeq() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("stream-text-abc", seq = null)),
            bridge = listOf(bubble("stream-text-abc", seq = null))
        )
        assertEquals(1, result.withoutSeq.size)
        assertEquals(0, result.bubbles.size)
    }

    /** ③b `-user-` 形态不得被误拆（末段是 messageId，不是 seq）。 */
    @Test
    fun doesNotParseSeqFromUserMessageId() {
        assertNull(parseSeqFromTimelineId("room-1-user-abc123"))
        assertNull(parseSeqFromTimelineId("room-1-user-77")) // 即便末段是数字，也不拆
        assertNull(parseSeqFromTimelineId("stream-reason-xyz"))
    }

    /** ④ bridge 独有条目（别人发言 / 群回复）保留。 */
    @Test
    fun keepsBridgeOnlyBubbles() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("room-1-1", seq = 1)),
            bridge = listOf(bubble("room-1:9", seq = 9L, text = "别人的话"))
        )
        assertEquals(1, result.bubbles.size)
        assertEquals("别人的话", result.bubbles.first().text)
    }

    /** ⑤ 排序由调用方按时间插入（此处只断言"保留时间字段"，防止实现把它丢掉）。 */
    @Test
    fun preservesBubbleTimestampForInsertion() {
        val result = mergeRoomChat(
            gateway = emptyList(),
            bridge = listOf(bubble("room-1:5", seq = 5L, date = 1_700_000_000_000L))
        )
        assertEquals(1_700_000_000_000L, result.bubbles.first().dateMillis)
    }

    /** ⑥ 无时间戳的条目仍被保留（调用方不得把它插到最前——排序策略在视图层）。 */
    @Test
    fun keepsBubblesWithoutTimestamp() {
        val result = mergeRoomChat(gateway = emptyList(), bridge = listOf(bubble("room-1:6", seq = 6L, date = null)))
        assertNull(result.bubbles.first().dateMillis)
    }

    /** ⑦ 空输入安全。 */
    @Test
    fun handlesEmptyInput() {
        val result = mergeRoomChat(emptyList(), emptyList())
        assertTrue(result.bubbles.isEmpty() && result.withoutSeq.isEmpty() && result.gatewayKeys.isEmpty())
    }

    /** ⑧ 别的会话同 seq 不被吞（键必须两个分量都参与）。 */
    @Test
    fun doesNotSwallowSameSeqFromAnotherSession() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("room-1-7", seq = 7, session = "room-1")),
            bridge = listOf(bubble("room-2:7", seq = 7L, session = "room-2", text = "另一个群"))
        )
        assertEquals(1, result.bubbles.size)
        assertEquals("另一个群", result.bubbles.first().text)
    }
}
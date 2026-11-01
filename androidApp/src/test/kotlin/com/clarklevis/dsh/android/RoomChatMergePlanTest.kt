package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.domain.GatewayTimelineRef
import com.clarklevis.dsh.shared.domain.RoomChatBubble
import com.clarklevis.dsh.shared.domain.interleaveByTime
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

    /** ⑭/⑮/⑯ 合成 key 不拆、合法 seq 形态仍拆、合成 key 落第三档（与 iOS 同规则）。 */
    @Test
    fun parseRulesMirrorIosSide() {
        assertEquals(1234L, parseSeqFromTimelineId("sess-1234"))   // ⑮ 末段纯数字 ⇒ 拆
        assertNull(parseSeqFromTimelineId("stream-text-3f2a-8b1c")) // ⑭ 末段 uuid ⇒ 不拆
        assertNull(parseSeqFromTimelineId("s-42-user-77"))          // `-user-` 形态：末段是 messageId
        val result = mergeRoomChat(
            gateway = emptyList(),
            bridge = listOf(bubble("stream-text-3f2a-8b1c", seq = null))
        )
        assertEquals(1, result.withoutSeq.size)                     // ⑯ 第三档是常态
    }

    /** ⑭ 补强：合成 id（`stream-` 开头）**末段是纯数字也不拆**——否则会被误当 seq ⇒ 偶发吞噬。 */
    @Test
    fun doesNotParseSeqFromSyntheticIdsEvenWhenTailIsNumeric() {
        assertNull(parseSeqFromTimelineId("stream-text-42"))
        assertNull(parseSeqFromTimelineId("stream-text-20261102"))
        assertNull(parseSeqFromTimelineId("stream-reason-7"))
    }

    /** ⑤ 插入位置在纯函数里可测：有时按时间插到中间，**不是两段拼接**（两端同规则）。 */
    @Test
    fun interleavesByTimeInTheMiddle() {
        val existing = listOf(1_000L to "gw-1", 3_000L to "gw-3")
        val inserted = listOf(2_000L to "br-2")
        assertEquals(listOf("gw-1", "br-2", "gw-3"), interleaveByTime(existing, inserted))
    }

    /** ⑥/⑬ 无时间（null / NaN 语义下的 0 或负）一律追加末尾，不插到最前。 */
    @Test
    fun appendsUntimedEntriesAtTheEnd() {
        val existing = listOf(1_000L to "gw-1", 2_000L to "gw-2")
        val inserted = listOf(null to "br-no-time", 0L to "br-zero")
        assertEquals(listOf("gw-1", "gw-2", "br-no-time", "br-zero"), interleaveByTime(existing, inserted))
    }

    /** ⑬ epochSeconds 非有限或 ≤0 ⇒ 视为"无时间"，不参与排序（Chief 采纳的跨端口径）。 */
    @Test
    fun treatsNonPositiveEpochAsNoTime() {
        // 直接测 shared 的纯函数（不依赖 UI 类型）：0/负数/无值都算"无时间"，一律追加末尾。
        val existing = listOf<Pair<Long?, String>>(5_000L to "gw-5")
        val inserted = listOf<Pair<Long?, String>>(0L to "zero", -1L to "negative", null to "missing")
        assertEquals(listOf("gw-5", "zero", "negative", "missing"), interleaveByTime(existing, inserted))
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
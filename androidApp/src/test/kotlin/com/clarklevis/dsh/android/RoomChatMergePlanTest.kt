package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.domain.GatewayTimelineRef
import com.clarklevis.dsh.shared.domain.chatMessagesFromHistoryPages
import com.clarklevis.dsh.shared.domain.decodeBridgeChatSnapshot
import com.clarklevis.dsh.shared.domain.RoomChatBubble
import com.clarklevis.dsh.shared.domain.interleaveByTime
import com.clarklevis.dsh.shared.domain.mergeChatSnapshots
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

    /** C9 多页翻页：页间重叠条目按 message id 去重，且**保持首次出现顺序**（新→旧页序即时间序）。 */
    @Test
    fun deduplicatesOverlappingHistoryPages() {
        val page1 = """{"sessionId":"s","messages":[{"id":"s:3","sessionId":"s","seq":3,"role":"assistant","text":"第三"},{"id":"s:2","sessionId":"s","seq":2,"role":"user","text":"第二"}]}"""
        val page2 = """{"sessionId":"s","messages":[{"id":"s:2","sessionId":"s","seq":2,"role":"user","text":"第二"},{"id":"s:1","sessionId":"s","seq":1,"role":"assistant","text":"第一"}]}"""
        val snapshot = decodeBridgeChatSnapshot(chatMessagesFromHistoryPages(listOf(page1, page2)))
        assertEquals(listOf("s:3", "s:2", "s:1"), snapshot?.messages?.map { it.id })
    }

    /** C9 边界：某页 JSON 坏了**不抛**、也不整批丢——其余页照常合成，且不产生重复。 */
    @Test
    fun survivesOneBrokenHistoryPage() {
        val good = """{"sessionId":"s","messages":[{"id":"s:1","sessionId":"s","seq":1,"role":"assistant","text":"第一"}]}"""
        val dropped = ArrayList<Int>()
        val snapshot = decodeBridgeChatSnapshot(
            chatMessagesFromHistoryPages(listOf(good, "{ not json", good)) { index, _ -> dropped += index }
        )
        assertEquals(listOf("s:1"), snapshot?.messages?.map { it.id })
        // 坏页必须留痕（不能静默丢），且只报真正坏的那一页
        assertEquals(listOf(1), dropped)
    }

    /** C9 边界：服务端 cursor:null 后仍被再请求一次（空转），条目不得增多。 */
    @Test
    fun repeatedFinalPageDoesNotDuplicate() {
        val page = """{"sessionId":"s","messages":[{"id":"s:9","sessionId":"s","seq":9,"role":"assistant","text":"末"}],"cursor":null}"""
        val once = decodeBridgeChatSnapshot(chatMessagesFromHistoryPages(listOf(page)))
        val twice = decodeBridgeChatSnapshot(chatMessagesFromHistoryPages(listOf(page, page)))
        assertEquals(1, once?.messages?.size)
        assertEquals(1, twice?.messages?.size)
    }

    /** 严版拆解：只有 id 恰好是 `<sessionId>-<seq>` 才拆得出来（数字后缀的 command id 不算）。 */
    @Test
    fun strictIdParseRejectsNonEventIds() {
        assertEquals(123L, parseSeqFromTimelineId("room-1-123", "room-1"))
        // 宽松版能拆出 123（末段是数字），严版必须拒绝：它不是 eventId 形态
        assertEquals(123L, parseSeqFromTimelineId("room-1-command-123"))
        assertNull(parseSeqFromTimelineId("room-1-command-123", "room-1"))
        assertNull(parseSeqFromTimelineId("room-1-12-reason", "room-1"))
    }

    /** 假 seq 不得吞掉 bridge 真条目：command 条目 id 尾号 = 99，而 bridge 真有 seq 99 的发言。 */
    @Test
    fun numericCommandIdDoesNotSwallowRealMessage() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("room-1-command-99", seq = null, session = "room-1")),
            bridge = listOf(bubble("room-1:99", seq = 99L, session = "room-1", text = "该留"))
        )
        assertEquals(listOf("该留"), result.bubbles.map { it.text })
    }

    /** 第三档**两侧都要留痕**：网关条目拿不到 seq 时，不得被 mapNotNull 静默丢掉（2026-11-02 自查修正）。 */
    @Test
    fun gatewayEntryWithoutSeqIsReportedNotSilentlyDropped() {
        val result = mergeRoomChat(
            gateway = listOf(
                gateway("room-1-7", seq = null, session = "room-1"),
                gateway("room-1-command-abc", seq = null, session = "room-1")
            ),
            bridge = emptyList()
        )
        assertEquals(setOf("room-1|7"), result.gatewayKeys)
        assertEquals(listOf("room-1-command-abc"), result.withoutSeqGateway.map { it.id })
    }

    /** iOS 侧 ⑭⑮⑯ 三条逐条落地（Chief 要求确认拆解依据是"末段纯数字"，而不是"含某前缀就跳过"）。 */
    @Test
    fun idParsingFollowsTailDigitRuleNotPrefixRule() {
        // ⑮ 合法 sessionId-seq：末段纯数字 ⇒ 拆出（sessionId 本身含连字符/字母也不影响）
        assertEquals(1234L, parseSeqFromTimelineId("sess-1234"))
        assertEquals(1234L, parseSeqFromTimelineId("room-1-1234", "room-1"))
        // ⑭ 合成 key：末段不是数字 ⇒ null（末段规则本身就能挡住，不是"看到 stream- 字样就跳过"）
        assertNull(parseSeqFromTimelineId("stream-text-2f1c"))
        assertNull(parseSeqFromTimelineId("stream-reason-9"))
        // 末段是数字但整体不是 eventId 形态 ⇒ 严版拒绝（宽松版仍可拆，仅作无 sessionId 时兜底）
        assertNull(parseSeqFromTimelineId("room-1-command-1234", "room-1"))
        // -user- 形态：末段即便是数字（messageId）也不拆
        assertNull(parseSeqFromTimelineId("s-42-user-77"))
    }

    /** 桥口音 `"<sessionId>:<seq>"` 也必须能拆 —— 否则桥条目退到 withoutSeq、同一行显示两遍。 */
    @Test
    fun parsesBridgeColonFormExactly() {
        assertEquals(7L, parseSeqFromTimelineId("room-1:7", "room-1"))
        // 只认精确形态：别的 id 里出现冒号+数字不得被拆
        assertNull(parseSeqFromTimelineId("x-room-1:7", "room-1"))
        assertNull(parseSeqFromTimelineId("room-1:7:9", "room-1"))
        // 网关口音不受影响
        assertEquals(7L, parseSeqFromTimelineId("room-1-7", "room-1"))
    }

    /**
     * **生产路径**（AndroidTester 2026-11-02 指出此前 0 覆盖）：
     * 桥气泡 `seq = null` 时，必须靠 id 的冒号形态拆出 seq 才能去重——
     * 只测解析函数不够（那只能证明"拆得对"），这里测**去重真的生效**。
     *
     * 断言同时要求 `withoutSeq` 为空：否则"bridge 被丢弃"可能是因为它落进第三档被跳过显示，
     * 而不是因为精确键命中——那是另一种假绿。
     */
    @Test
    fun bridgeBubbleWithoutSeqIsDedupedByColonID() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("room-1-7", seq = 7, session = "room-1")),
            bridge = listOf(bubble("room-1:7", seq = null, session = "room-1"))
        )
        assertTrue("bridge 那份应被网关那份去重掉", result.bubbles.isEmpty())
        assertTrue("不得靠落 withoutSeq 蒙过去", result.withoutSeq.isEmpty())
        assertEquals(setOf("room-1|7"), result.gatewayKeys)
    }

    /** 反向：桥 id 形态不对（非精确冒号）时**不得**去重——否则就是"假去重吞消息"。 */
    @Test
    fun nonExactColonIDIsNotDeduped() {
        val result = mergeRoomChat(
            gateway = listOf(gateway("room-1-7", seq = 7, session = "room-1")),
            bridge = listOf(bubble("room-1:7:9", seq = null, session = "room-1"))
        )
        // 不进 bubbles（那只是"要插入的去重后条目"），而是如实落第三档：仍显示 + 调用方留日志
        assertTrue(result.bubbles.isEmpty())
        assertEquals(listOf("room-1:7:9"), result.withoutSeq.map { it.id })
    }

/** vc63 ①判据（红→绿）：同毫秒、seq 递增的乱序插入（模拟 reply:0/reply:1 同毫秒、或快速连发乱序送达）
 *  ——终序必须按 **seq**（宿主事件序才是真序），不由毫秒精度/插入先后决定（iOS 04b84c0 同款修复）。
 *  此用例在旧代码（无终序重排）上必须红：输出会是 [s100, s102, s101]。 */
@Test
fun mergeOrderFollowsSeqNotMillis() {
    val existing = listOf<Pair<Long?, String>>(5_000L to "s100")
    // 同毫秒 5_000，插入顺序故意 seq 降序（102 先于 101 送达）
    val inserted = listOf<Pair<Long?, String>>(
        5_000L to "s102",
        5_000L to "s101",
    )
    val seqOf: (String) -> Long? = { it.removePrefix("s").toLongOrNull() }
    assertEquals(
        "同毫秒时 seq 决定终序，插入先后不决定",
        listOf("s100", "s101", "s102"),
        interleaveByTime(existing, inserted, seqOf),
    )
}

/** ②判据（反向）：无 seq 的条目殿后、组内保持原相对顺序；有 seq 的不被它们插队。 */
@Test
fun noSeqEntriesGoLastInOriginalOrder() {
    val existing = listOf<Pair<Long?, String>>(5_000L to "s100")
    val inserted = listOf<Pair<Long?, String>>(
        null to "n1",
        6_000L to "s200",
        null to "n2",
    )
    val seqOf: (String) -> Long? = { it.removePrefix("s").toLongOrNull() }
    assertEquals(
        listOf("s100", "s200", "n1", "n2"),
        interleaveByTime(existing, inserted, seqOf),
    )
}

/** ③判据（稳定）：seq 相同的两条保持原相对顺序（装饰排序，不赌 sortedBy 的稳定性）。 */
@Test
fun equalSeqKeepsOriginalRelativeOrder() {
    val existing = emptyList<Pair<Long?, String>>()
    val inserted = listOf<Pair<Long?, String>>(
        1_000L to "a-first",
        2_000L to "b-second",
    )
    val seqOf: (String) -> Long? = { 7L } // 全部同 seq
    assertEquals(
        "seq 相同 ⇒ 保持原相对顺序",
        listOf("a-first", "b-second"),
        interleaveByTime(existing, inserted, seqOf),
    )
}
}

// 合并快照的用例放在文件末尾（另一个类）
private fun snapshotJson(vararg ids: String): String =
    """{"messages":[${ids.joinToString(",") { """{"id":"$it","sessionId":"s","role":"assistant","text":"$it"}""" }}]}"""

class ChatSnapshotMergeTest {
    /** 轮询增量：老快照里的条目必须保留，新出现的追加（直接覆盖会让早先几页消失）。 */
    @Test
    fun mergeKeepsOldMessagesAndAppendsNew() {
        val merged = decodeBridgeChatSnapshot(
            mergeChatSnapshots(snapshotJson("s:3", "s:2"), snapshotJson("s:2", "s:1"))
        )
        assertEquals(listOf("s:3", "s:2", "s:1"), merged?.messages?.map { it.id })
    }

    /** 没有旧快照时等价于直接返回新快照（首次进入）。 */
    @Test
    fun mergeWithoutPreviousReturnsIncoming() {
        val merged = decodeBridgeChatSnapshot(mergeChatSnapshots(null, snapshotJson("s:1")))
        assertEquals(listOf("s:1"), merged?.messages?.map { it.id })
    }

}

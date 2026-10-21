package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.BridgeChatMessage
import com.clarklevis.dsh.shared.domain.acceptedBridgeSubmissionMessage
import com.clarklevis.dsh.shared.domain.applyBridgeConversationEvent
import com.clarklevis.dsh.shared.domain.mergeBridgeMessages
import com.clarklevis.dsh.shared.protocol.parseBridgeEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** mergeMessages 移植验收（§6 P3：断线/重连/流式不重复、对账正确）。 */
class BridgeChatMergeTest {

    private val now = 1_760_000_000_000L

    private fun message(
        id: String,
        text: String,
        seq: Long,
        role: String = "user",
        updatedSeq: Long? = null,
        requestId: String? = null,
        localSubmission: Boolean? = null,
        pending: Boolean? = null,
        attemptId: String? = null,
        sessionId: String = "s-1"
    ) = BridgeChatMessage(
        id = id,
        sessionId = sessionId,
        seq = seq,
        role = role,
        text = text,
        createdAt = JsonPrimitive(now + seq),
        updatedSeq = updatedSeq,
        requestId = requestId,
        localSubmission = localSubmission,
        pending = pending,
        attemptId = attemptId
    )

    @Test
    fun dedupeKeepsNewestVersionById() {
        val existing = listOf(message("m1", "v1", 1))
        val incoming = listOf(message("m1", "v2", 2))
        val merged = mergeBridgeMessages(existing, incoming)
        assertEquals(1, merged.size)
        assertEquals("v2", merged.single().text)
    }

    @Test
    fun staleIncomingNeverDowngrades() {
        val existing = listOf(message("m1", "new", seq = 5))
        val incoming = listOf(message("m1", "old", seq = 3))
        val merged = mergeBridgeMessages(existing, incoming)
        assertEquals("new", merged.single().text)
    }

    @Test
    fun updatedSeqWinsOverSeq() {
        val existing = listOf(message("m1", "v1", seq = 2, updatedSeq = 9))
        val incoming = listOf(message("m1", "v2", seq = 3))
        // incoming version 3 < previous version(updatedSeq) 9 ⇒ 不降级
        assertEquals("v1", mergeBridgeMessages(existing, incoming).single().text)
    }

    @Test
    fun localSubmissionIsReplacedByHostConfirmation() {
        val local = message(
            "accepted:r1", "hello", seq = Long.MAX_VALUE,
            requestId = "r1", localSubmission = true
        )
        val host = message("m-host", "hello", seq = 4, requestId = "r1")
        val merged = mergeBridgeMessages(listOf(local), listOf(host))
        assertEquals(1, merged.size)
        assertEquals("m-host", merged.single().id)
        assertNull(merged.single().localSubmission)
    }

    @Test
    fun deltaAccumulatesIntoPendingRowAndIgnoresStaleIndex() {
        val e1 = parseBridgeEvent("""{"type":"delta","sessionId":"s-1","attemptId":"a1","text":"hel","index":1}""")
        val e2 = parseBridgeEvent("""{"type":"delta","sessionId":"s-1","attemptId":"a1","text":"hello","index":2}""")
        val stale = parseBridgeEvent("""{"type":"delta","sessionId":"s-1","attemptId":"a1","text":"he","index":0}""")

        var messages = applyBridgeConversationEvent(emptyList(), e1, now)
        assertEquals(1, messages.size)
        assertEquals("hel", messages.single().text)
        assertTrue(messages.single().pending == true)

        messages = applyBridgeConversationEvent(messages, e2, now)
        assertEquals(1, messages.size)
        assertEquals("hello", messages.single().text)
        assertEquals(2, messages.single().streamIndex)

        val afterStale = applyBridgeConversationEvent(messages, stale, now)
        assertEquals("hello", afterStale.single().text)
    }

    @Test
    fun finalMessageReplacesPendingAttempt() {
        val delta = parseBridgeEvent("""{"type":"delta","sessionId":"s-1","attemptId":"a1","text":"par","index":1}""")
        var messages = applyBridgeConversationEvent(emptyList(), delta, now)
        assertEquals(1, messages.size)

        val finalMessage = parseBridgeEvent(
            """{"type":"message","sessionId":"s-1","replaceAttemptId":"a1",
                "message":{"id":"m-final","sessionId":"s-1","seq":7,"role":"assistant","text":"done"}}"""
        )
        messages = applyBridgeConversationEvent(messages, finalMessage, now)
        assertEquals(1, messages.size)
        assertEquals("m-final", messages.single().id)
        assertEquals("done", messages.single().text)
    }

    @Test
    fun resetDropsPendingAndRebuildsFromSnapshot() {
        val delta = parseBridgeEvent("""{"type":"delta","sessionId":"s-1","attemptId":"a1","text":"par","index":1}""")
        var messages = applyBridgeConversationEvent(emptyList(), delta, now)

        val reset = parseBridgeEvent(
            """{"type":"reset","sessionId":"s-1","eventSeq":41,
                "messages":[{"id":"m1","sessionId":"s-1","seq":1,"role":"user","text":"hi"},
                            {"id":"m2","sessionId":"s-1","seq":2,"role":"assistant","text":"yo"}]}"""
        )
        messages = applyBridgeConversationEvent(messages, reset, now)
        assertEquals(listOf("m1", "m2"), messages.map { it.id })
        assertTrue(messages.none { it.pending == true })
    }

    @Test
    fun statusClearAttemptRemovesPendingRow() {
        val delta = parseBridgeEvent("""{"type":"delta","sessionId":"s-1","attemptId":"a1","text":"par","index":1}""")
        var messages = applyBridgeConversationEvent(emptyList(), delta, now)

        val status = parseBridgeEvent(
            """{"type":"status","sessionId":"s-1","busy":false,"phase":"idle","clearAttempt":true,"attemptId":"a1"}"""
        )
        messages = applyBridgeConversationEvent(messages, status, now)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun pendingRowsSortLastAndConfirmedBySeq() {
        val pending = message("stream:s-1:a1", "streaming", seq = Long.MAX_VALUE, role = "assistant", pending = true, attemptId = "a1")
        val second = message("m2", "second", seq = 2)
        val first = message("m1", "first", seq = 1)
        val merged = mergeBridgeMessages(emptyList(), listOf(pending, second, first))
        assertEquals(listOf("m1", "m2", "stream:s-1:a1"), merged.map { it.id })
    }

    @Test
    fun acceptedSubmissionRequiresMatchingReceipt() {
        val accepted = acceptedBridgeSubmissionMessage(
            conversationId = "c-1",
            sessionId = "s-1",
            requestId = "r1",
            text = "hello",
            receiptStatus = "accepted",
            receiptRequestId = "r1",
            receiptConversationId = "c-1",
            receiptSessionId = "s-1",
            receiptCreatedAtMillis = now
        )
        assertNotNull(accepted)
        assertTrue(accepted.localSubmission == true)

        assertNull(
            acceptedBridgeSubmissionMessage(
                conversationId = "c-1",
                sessionId = "s-1",
                requestId = "r1",
                text = "hello",
                receiptStatus = "rejected",
                receiptRequestId = "r1",
                receiptConversationId = "c-1",
                receiptSessionId = "s-1",
                receiptCreatedAtMillis = now
            )
        )
        assertNull(
            acceptedBridgeSubmissionMessage(
                conversationId = "c-1",
                sessionId = "s-1",
                requestId = "r1",
                text = "hello",
                receiptStatus = "accepted",
                receiptRequestId = "other",
                receiptConversationId = "c-1",
                receiptSessionId = "s-1",
                receiptCreatedAtMillis = now
            )
        )
    }
}

package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.conversationHistoryPatch
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class SharedConversationHistoryIncrementalTest {
    @Test
    fun largeAppendAndOversizedSingleMessageStayOnTheWorkerPath() {
        val store = SharedConversationStore()
        val records = listOf(record(1))
        store.replaceTypedSession("s", records)
        assertNull(store.appendHistorySnapshot("s", snapshot((1..40).map(::record))))
        val large = record(2).copy(event = GatewayEvent("user/message", text = "正".repeat(300_000)))
        assertNull(store.appendHistorySnapshot("s", snapshot(records + large)))
        assertNotNull(store.appendHistorySnapshot("s", snapshot(records + record(2))))
    }

    @Test
    fun cachedEightyEventsAppendOnlyTheNewEventAndAdvanceInvisibleWatermark() {
        val store = SharedConversationStore()
        val received = observe(store)
        val cached = (1..80).map(::record)
        store.replaceTypedSession("s", cached)
        val initial = patch(received.last()).replacementItems!!
        val added = cached + record(81)
        assertNotNull(store.appendHistorySnapshot("s", snapshot(added)))
        val delta = patch(received.last())
        assertFalse(delta.replacesAll)
        assertEquals(1, delta.operations.size)
        assertEquals("s-81", delta.operations.single().item!!.id)
        assertEquals(ConversationProjector.make(added), apply(initial, delta))
        val hidden = added + SessionEvent("s", 82, 82.0, GatewayEvent("step/end"))
        assertNotNull(store.appendHistorySnapshot("s", snapshot(hidden)))
        assertTrue(patch(received.last()).operations.isEmpty())
        assertEquals(82, patch(received.last()).lastSequence)
    }

    @Test
    fun prependedMissingEventSendsOnlyOneRowAndPreservesTheOtherEighty() {
        val store = SharedConversationStore()
        val received = observe(store)
        val cached = (2..81).map(::record)
        store.replaceTypedSession("s", cached)
        val initial = patch(received.last()).replacementItems!!
        val authoritative = listOf(record(1)) + cached
        assertNull(store.appendHistorySnapshot("s", snapshot(authoritative)))
        val prepared = assertNotNull(store.prepareHistorySnapshot("s", snapshot(authoritative))).prepare()
        assertNotNull(store.commitPreparedHistory(prepared))
        val delta = patch(received.last())
        assertFalse(delta.replacesAll)
        assertEquals(1, delta.operations.size)
        assertEquals("insert-before", delta.operations.single().kind)
        assertEquals("s-2", delta.operations.single().itemId)
        assertEquals(ConversationProjector.make(authoritative), apply(initial, delta))
    }

    @Test
    fun revisionsAndDeletionsAreRevalidatedAndEmitOnlyChangedRows() {
        val store = SharedConversationStore()
        val received = observe(store)
        val old = (1..4).map(::record)
        store.replaceTypedSession("s", old)
        val initial = patch(received.last()).replacementItems!!
        val updated = listOf(record(1), record(2).copy(event = GatewayEvent("user/message", text = "修改")), record(4))
        assertNull(store.appendHistorySnapshot("s", snapshot(updated)))
        assertNotNull(store.commitPreparedHistory(assertNotNull(store.prepareHistorySnapshot("s", snapshot(updated))).prepare()))
        val delta = patch(received.last())
        assertEquals(listOf("remove", "replace"), delta.operations.map { it.kind })
        assertEquals(ConversationProjector.make(updated), apply(initial, delta))
    }

    @Test
    fun identicalBaselineDoesNotSendRowsAndNewSnapshotInvalidatesPreparedWork() {
        val store = SharedConversationStore()
        val received = observe(store)
        val records = listOf(record(1))
        store.replaceTypedSession("s", records)
        val oldWork = assertNotNull(store.prepareHistorySnapshot("s", snapshot(records))).prepare()
        assertNotNull(store.appendHistorySnapshot("s", snapshot(records)))
        assertFalse(patch(received.last()).replacesAll)
        assertTrue(patch(received.last()).operations.isEmpty())
        assertNull(store.commitPreparedHistory(oldWork))
        store.clearSession("s")
        assertNull(store.appendHistorySnapshot("s", snapshot(records)))
    }

    @Test
    fun streamDeltaCannotReuseAnUnprovenDurablePrefixAndTransientRowsAreRemoved() {
        val store = SharedConversationStore()
        val received = observe(store)
        val records = listOf(record(1))
        store.replaceTypedSession("s", records)
        store.assistantChunks("s", "attempt", """[{"time":2,"chunk":{"type":"text-delta","text":"live"}}]""")
        assertTrue(store.hasAssistantChunks("s"))
        assertNull(store.appendHistorySnapshot("s", snapshot(records + record(2))))
        assertNotNull(store.commitPreparedHistory(assertNotNull(store.prepareHistorySnapshot("s", snapshot(records))).prepare()))
        assertFalse(store.hasAssistantChunks("s"))
        assertEquals("remove", patch(received.last()).operations.single().kind)
        store.receiveStreamDelta(
            wireJson.encodeToString(
                SessionEvent(
                    "s",
                    1,
                    2.0,
                    GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "delta")
                )
            )
        )
        assertNull(store.appendHistorySnapshot("s", snapshot(records + record(2))))
    }

    @Test
    fun changedOrderFallsBackToAtomicReplacementAndMultipleInsertionsKeepOrder() {
        val items = ConversationProjector.make((1..5).map(::record))
        assertTrue(conversationHistoryPatch("s", items, items.reversed(), 5).replacesAll)
        val old = listOf(items[2], items[4])
        val delta = conversationHistoryPatch("s", old, items, 5)
        assertFalse(delta.replacesAll)
        assertEquals(items, apply(old, delta))
        assertEquals(emptyList(), apply(items, conversationHistoryPatch("s", items, emptyList(), -1)))
        val large = ConversationProjector.make((1..100).map(::record))
        assertTrue(conversationHistoryPatch("s", emptyList(), large, 100).replacesAll)
    }

    private fun snapshot(records: List<SessionEvent>) = SharedHistoryProjectionSnapshot("s", 1, records)
    private fun record(sequence: Int) =
        SessionEvent("s", sequence, sequence.toDouble(), GatewayEvent("user/message", text = "正文 $sequence"))
    private fun observe(store: SharedConversationStore) = mutableListOf<SharedMviEvent>().also {
        store.subscribe(SharedMviEventObserver(it::add))
    }
    private fun patch(event: SharedMviEvent): SharedConversationPatch = wireJson.decodeFromString(event.statePayloadJson!!)

    private fun apply(old: List<ConversationItem>, patch: SharedConversationPatch): List<ConversationItem> {
        if (patch.replacesAll) return patch.replacementItems!!
        val items = old.toMutableList()
        patch.operations.forEach { operation ->
            when (operation.kind) {
                "insert" -> items.add(operation.item!!)
                "insert-before" -> items.add(items.indexOfFirst { it.id == operation.itemId }, operation.item!!)
                "remove" -> items.removeAt(items.indexOfFirst { it.id == operation.itemId })
                "replace" -> items[items.indexOfFirst { it.id == operation.itemId }] = operation.item!!
                else -> error("unexpected operation ${operation.kind}")
            }
        }
        return items
    }
}

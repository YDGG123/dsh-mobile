package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationHistoryPreparationTest {
    @Test
    fun preparedProjectionMatchesSynchronousAndTransfersOnlyOnce() {
        val snapshot = snapshot()
        val legacy = SharedConversationStore()
        val optimized = SharedConversationStore()
        val oldEvents = observe(legacy)
        val fastEvents = observe(optimized)
        val prepared = assertNotNull(optimized.prepareHistorySnapshot("s1", snapshot)).prepare()
        assertFalse(optimized.hasProjection("s1"), "worker must not mutate owner")
        legacy.replaceHistorySnapshot("s1", snapshot)
        assertNotNull(optimized.commitPreparedHistory(prepared))
        assertEquals(patch(oldEvents.last()), patch(fastEvents.last()))
        assertNull(optimized.commitPreparedHistory(prepared))
    }

    @Test
    fun anotherSessionMutationDoesNotInvalidatePreparedHistory() {
        val store = SharedConversationStore()
        val prepared = assertNotNull(store.prepareHistorySnapshot("s1", snapshot())).prepare()
        store.receiveEvent(wireJson.encodeToString(record(1, "other").copy(sessionId = "s2")))
        assertNotNull(store.commitPreparedHistory(prepared))
        assertTrue(store.hasProjection("s1"))
        assertTrue(store.hasProjection("s2"))
    }

    @Test
    fun everySameSessionMutationIncludingNoOpRejectsOldPreparedState() {
        val mutations: List<(SharedConversationStore) -> Unit> = listOf(
            { it.receiveEvent(wireJson.encodeToString(record(2, "new live"))); Unit },
            { it.receiveStreamDelta(wireJson.encodeToString(SessionEvent("s1", 1, 2.0,
                GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "delta")))); Unit },
            { it.assistantChunks("s1", "attempt", "[]"); Unit },
            { it.clearAssistantChunks("s1"); Unit },
            { it.replaceSteeringMessages("s1", "[]"); Unit },
            { it.clearSession("s1"); Unit },
            { it.replaceHistorySnapshot("s1", snapshot()); Unit }
        )
        for (mutate in mutations) {
            val store = SharedConversationStore()
            store.replaceHistorySnapshot("s1", snapshot())
            val prepared = assertNotNull(store.prepareHistorySnapshot("s1", snapshot())).prepare()
            mutate(store)
            assertNull(store.commitPreparedHistory(prepared))
        }
    }

    @Test
    fun clearCannotBeUndoneByLatePreparedHistoryAndAnotherOwnerCannotConsumeIt() {
        val owner = SharedConversationStore()
        val another = SharedConversationStore()
        val prepared = assertNotNull(owner.prepareHistorySnapshot("s1", snapshot())).prepare()
        assertNull(another.commitPreparedHistory(prepared))
        assertFalse(another.hasProjection("s1"))
        owner.clearSession("s1")
        assertNull(owner.commitPreparedHistory(prepared))
        assertFalse(owner.hasProjection("s1"))
    }

    @Test
    fun preparationFailureLeavesTheOwnersProjectionAndMviUntouched() {
        val store = SharedConversationStore()
        val events = observe(store)
        store.replaceHistorySnapshot("s1", snapshot())
        val count = events.size
        val invalid = SharedHistoryProjectionSnapshot("s1", 2, listOf(record(2, "invalid").copy(time = Double.NaN)))
        val input = assertNotNull(store.prepareHistorySnapshot("s1", invalid))
        assertFailsWith<Exception> { input.prepare() }
        assertEquals(count, events.size)
        assertEquals(listOf("original"), patch(events.last()).replacementItems!!.map { it.text })
        assertNull(store.prepareHistorySnapshot("other", snapshot()))
    }

    private fun snapshot() = SharedHistoryProjectionSnapshot("s1", 1, listOf(record(1, "original")))
    private fun record(sequence: Int, text: String) = SessionEvent("s1", sequence, sequence.toDouble(), GatewayEvent("user/message", text = text))
    private fun observe(store: SharedConversationStore) = mutableListOf<SharedMviEvent>().also {
        store.subscribe(SharedMviEventObserver(it::add))
    }
    private fun patch(event: SharedMviEvent): SharedConversationPatch = wireJson.decodeFromString(event.statePayloadJson!!)
}

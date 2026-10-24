package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.SharedSessionListSnapshot
import com.clarklevis.dsh.shared.facade.SharedSessionListStore
import com.clarklevis.dsh.shared.facade.SharedSessionSummarySnapshot
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedHistoryProjectionTransportTest {
    @Test
    fun referencesMatchFullPayloadWithLivePriorityAndLastIncomingDuplicate() {
        val legacy = SharedHistoryStore()
        val optimized = SharedHistoryStore()
        val legacyEvents = observe(legacy)
        val optimizedEvents = observe(optimized)
        val retained = listOf(record(10, "live"))
        for (store in listOf(legacy, optimized)) {
            store.installSnapshot("s1", wireJson.encodeToString(retained), true, 10)
            store.start("s1", true, true, 10)
        }
        val base = optimizedEvents.last().sequence
        val incoming = listOf(record(9, "first"), record(10, "stale"), record(8, "eight"), record(9, "last"))
        val json = wireJson.encodeToString(incoming)
        assertTrue(legacy.pageReceived("s1", json, 100, false, null, null).accepted)
        assertTrue(optimized.pageReceivedWithEventReferences("s1", json, 100, false, null, null).accepted)
        val full = patch(legacyEvents.last())
        val fast = patch(optimizedEvents.last())
        val instructions = assertNotNull(fast.eventPatch)
        assertNull(instructions.replacementEvents)
        assertEquals(base, instructions.referenceBaseEventSequence)
        val reconstructed = assertNotNull(instructions.replacementReferences).map { reference ->
            val source = if (reference.source == "retained") retained else incoming
            source[reference.index].also { assertEquals(reference.sequence, it.seq) }
        }
        assertEquals(full.eventPatch!!.replacementEvents, reconstructed)
        assertEquals(listOf("eight", "last", "live"), reconstructed.map { it.event.text })
        assertEquals(full.copy(eventPatch = null), fast.copy(eventPatch = null))
    }

    @Test
    fun referencesPreserveServerAuthorityOverCachedSameSequence() {
        val store = SharedHistoryStore()
        val received = observe(store)
        val cached = listOf(record(10, "cached"))
        store.hydrateTypedCachedWindow("s1", CachedHistoryWindow(sessionId = "s1", events = cached, hasMore = true))
        store.start("s1", false, true, 10)
        val incoming = listOf(record(10, "server"))
        assertTrue(store.pageReceivedWithEventReferences("s1", wireJson.encodeToString(incoming), 1, false, null, null).accepted)
        val refs = patch(received.last()).eventPatch!!
        assertTrue(refs.authoritative)
        assertEquals("incoming", refs.replacementReferences!!.single().source)
        assertEquals(0, refs.replacementReferences.single().index)
    }

    @Test
    fun emptyReferencePageKeepsTheAuthoritativeEmptyReplacement() {
        val store = SharedHistoryStore()
        val received = observe(store)
        store.hydrateTypedCachedWindow("s1", CachedHistoryWindow(sessionId = "s1", events = listOf(record(1, "old"))))
        store.start("s1", false, true, 1)
        store.pageReceivedWithEventReferences("s1", "[]", 0, false, null, null)
        assertEquals(emptyList(), patch(received.last()).eventPatch!!.replacementReferences)
        assertNull(patch(received.last()).eventPatch!!.replacementEvents)
    }

    @Test
    fun snapshotsSurviveEarlierObserverReentrantLiveAndClearButRegistryDrains() {
        val store = SharedHistoryStore()
        var armed = false
        store.subscribe(SharedMviEventObserver { event ->
            if (armed && event.kind == "transition") {
                armed = false
                store.liveEventReceived(wireJson.encodeToString(record(20, "later")))
                store.clearSession("s1")
            }
        })
        val snapshots = mutableListOf<SharedHistoryProjectionSnapshot>()
        store.subscribe(SharedMviEventObserver { event ->
            if (event.kind == "transition" && patch(event).eventPatch != null) {
                snapshots += assertNotNull(store.projectionSnapshotForEvent("s1", event.sequence))
                assertNull(store.projectionSnapshotForEvent("wrong", event.sequence))
            }
        })
        armed = true
        store.installSnapshot("s1", wireJson.encodeToString(listOf(record(10, "original"))), false, null)
        assertEquals(listOf(listOf("original"), listOf("original", "later"), emptyList()),
            snapshots.map { snapshot -> snapshot.records.map { it.event.text } })
        assertTrue(snapshots.zipWithNext().all { (a, b) -> b.eventSequence == a.eventSequence + 1 })
        snapshots.forEach { assertNull(store.projectionSnapshotForEvent("s1", it.eventSequence)) }
        assertEquals("original", wireJson.decodeFromString<List<SessionEvent>>(snapshots.first().eventsJson()).single().event.text)
    }

    @Test
    fun delayedSnapshotConversationMatchesJsonAndRejectsAnotherSession() {
        val history = SharedHistoryStore()
        var snapshot: SharedHistoryProjectionSnapshot? = null
        history.subscribe(SharedMviEventObserver { event ->
            if (event.kind == "transition") snapshot = history.projectionSnapshotForEvent("s1", event.sequence)
        })
        val records = listOf(record(1, "question"), record(2, "answer", "assistant/message"))
        history.installSnapshot("s1", wireJson.encodeToString(records), false, null)
        val captured = assertNotNull(snapshot)
        history.clearSession("s1")
        val legacy = SharedConversationStore()
        val optimized = SharedConversationStore()
        val oldEvents = mutableListOf<SharedMviEvent>()
        val fastEvents = mutableListOf<SharedMviEvent>()
        legacy.subscribe(SharedMviEventObserver(oldEvents::add))
        optimized.subscribe(SharedMviEventObserver(fastEvents::add))
        legacy.replaceSession("s1", captured.eventsJson())
        optimized.replaceHistorySnapshot("s1", captured)
        assertEquals(conversationPatch(oldEvents.last()), conversationPatch(fastEvents.last()))
        assertFalse(optimized.replaceHistorySnapshot("other", captured).accepted)
        assertFalse(optimized.hasProjection("other"))
        assertTrue(optimized.hasProjection("s1"))
    }

    @Test
    fun snapshotSessionListHasIdenticalReducerResultAndNoCrossSessionMutation() {
        val snapshot = SharedHistoryProjectionSnapshot("s1", 1, listOf(record(1, "", "turn/end")))
        val initial = SharedSessionListSnapshot(sessions = listOf(SharedSessionSummarySnapshot("s1", "标题", 5.0, true, true)))
        val legacy = SharedSessionListStore("New", "Remote", "Blank")
        val optimized = SharedSessionListStore("New", "Remote", "Blank")
        legacy.restore(wireJson.encodeToString(initial))
        optimized.restore(wireJson.encodeToString(initial))
        assertEquals(legacy.receiveHistory("s1", snapshot.eventsJson()), optimized.receiveHistorySnapshot("s1", snapshot))
        assertFalse(optimized.receiveHistorySnapshot("other", snapshot).isSuccess)
        val state = wireJson.decodeFromString<SharedSessionListSnapshot>(optimized.snapshot().snapshotJson!!)
        assertEquals(initial.sessions.single().copy(isRunning = false), state.sessions.single())
    }

    @Test
    fun referencePayloadSizeDoesNotScaleWithBodyText() {
        fun payload(text: String): String {
            val store = SharedHistoryStore()
            val received = observe(store)
            store.start("s1", false, false, null)
            store.pageReceivedWithEventReferences("s1", wireJson.encodeToString(listOf(record(1, text))), 1, false, null, null)
            return received.last().statePayloadJson!!
        }
        assertEquals(payload("x"), payload("x".repeat(200_000)))
    }

    private fun observe(store: SharedHistoryStore): MutableList<SharedMviEvent> = mutableListOf<SharedMviEvent>().also {
        store.subscribe(SharedMviEventObserver(it::add))
    }

    private fun record(sequence: Int, text: String, type: String = "user/message") =
        SessionEvent("s1", sequence, sequence.toDouble(), GatewayEvent(type, text = text))

    private fun patch(event: SharedMviEvent): SharedHistoryPatch = wireJson.decodeFromString(event.statePayloadJson!!)
    private fun conversationPatch(event: SharedMviEvent): SharedConversationPatch = wireJson.decodeFromString(event.statePayloadJson!!)
}

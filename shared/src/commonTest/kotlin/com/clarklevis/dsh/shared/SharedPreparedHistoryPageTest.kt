package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.SharedPreparedHistoryPage
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

class SharedPreparedHistoryPageTest {
    @Test
    fun preparedAndJsonPagesHaveIdenticalMergeMetadataReferencesAndEffects() {
        for (cached in listOf(false, true)) {
            val old = SharedHistoryStore()
            val typed = SharedHistoryStore()
            val oldEvents = observe(old)
            val typedEvents = observe(typed)
            val retained = listOf(record(10, "retained"))
            for (store in listOf(old, typed)) {
                if (cached) store.hydrateTypedCachedWindow("s1", CachedHistoryWindow(sessionId = "s1", events = retained))
                else store.installSnapshot("s1", wireJson.encodeToString(retained), true, 10)
                store.start("s1", false, true, 10)
                store.liveEventReceived(wireJson.encodeToString(record(12, "live")))
            }
            val incoming = listOf(record(9, "first"), record(10, "server"), record(12, "stale"), record(9, "last"))
            val encoded = wireJson.encodeToString(incoming)
            val prepared = SharedPreparedHistoryPage.parse("s1", encoded)
            assertTrue(old.pageReceivedWithEventReferences("s1", encoded, 50, false, null, 10.0).accepted)
            assertTrue(typed.pageReceivedPreparedWithEventReferences("s1", prepared, 50, false, null, 10.0).accepted)
            assertEquals(oldEvents.last().statePayloadJson, typedEvents.last().statePayloadJson)
            assertEquals(oldEvents.last().effectsJson, typedEvents.last().effectsJson)
        }
    }

    @Test
    fun malformedOrCrossSessionFactoryInputsThrowWithoutTouchingAStore() {
        for ((id, json) in listOf("" to "[]", "s1" to "{", "s1" to "{}",
            "s1" to wireJson.encodeToString(listOf(record(1, "other").copy(sessionId = "s2"))))) {
            assertFailsWith<Exception> { SharedPreparedHistoryPage.parse(id, json) }
        }
        assertEquals(emptyList(), SharedPreparedHistoryPage.parse("s1", "[]").records)
    }

    @Test
    fun consumingAnotherSessionsPreparedPageEmitsErrorWithoutPartialDomainMutation() {
        val store = SharedHistoryStore()
        val received = observe(store)
        store.installSnapshot("s1", wireJson.encodeToString(listOf(record(10, "keep"))), true, 10)
        store.start("s1", true, true, 10)
        val foreign = SharedPreparedHistoryPage.parse("other",
            wireJson.encodeToString(listOf(record(1, "wrong").copy(sessionId = "other"))))
        assertFalse(store.pageReceivedPreparedWithEventReferences("s1", foreign, 1, false, null, null).accepted)
        assertEquals("error", received.last().kind)
        assertNull(store.projectionSnapshotForEvent("s1", received.last().sequence))
        val bootstrap = observe(store).last()
        val state = wireJson.decodeFromString<SharedHistoryBootstrap>(bootstrap.statePayloadJson!!)
        assertEquals(listOf("keep"), state.eventsBySession["s1"]!!.map { it.event.text })
        assertTrue(state.state.sessions["s1"]!!.isLoading)
        assertFalse(state.eventsBySession.containsKey("other"))
    }

    @Test
    fun preparedDecodedRecordsAreIndependentOfTheOriginalMutableContainer() {
        val source = mutableListOf(record(1, "original"))
        val prepared = SharedPreparedHistoryPage.parse("s1", wireJson.encodeToString(source))
        source[0] = record(1, "changed")
        source.clear()
        val store = SharedHistoryStore()
        var snapshot: SharedHistoryProjectionSnapshot? = null
        store.subscribe(SharedMviEventObserver { event ->
            if (event.kind == "transition" && wireJson.decodeFromString<SharedHistoryPatch>(event.statePayloadJson!!).eventPatch != null) {
                snapshot = store.projectionSnapshotForEvent("s1", event.sequence)
            }
        })
        store.start("s1", false, false, null)
        store.pageReceivedPreparedWithEventReferences("s1", prepared, 1, false, null, null)
        assertEquals(listOf("original"), assertNotNull(snapshot).records.map { it.event.text })
    }

    private fun record(sequence: Int, text: String) = SessionEvent("s1", sequence, sequence.toDouble(), GatewayEvent("user/message", text = text))
    private fun observe(store: SharedHistoryStore) = mutableListOf<SharedMviEvent>().also {
        store.subscribe(SharedMviEventObserver(it::add))
    }
}

package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
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

class CachedHistoryHydrationTest {
    @Test
    fun restoresDisplayAndPaginationWithoutEstablishingNetworkBaseline() {
        val store = SharedHistoryStore()
        val changes = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(changes::add))
        store.awaitSnapshot("s")
        assertTrue(store.hydrateCachedWindow("s", window(event(8, "disk"))).accepted)
        val patch = wireJson.decodeFromString<SharedHistoryPatch>(changes.last().statePayloadJson!!)
        assertTrue(patch.session!!.isLoading)
        assertTrue(patch.session.hasMore)
        assertEquals(7, patch.session.nextBeforeSequence)
        assertNull(patch.session.syncedActivityTimestamp)
        assertEquals("[]", changes.last().effectsJson)
        assertNull(store.cachedWindowJson("s", 1, 999), "未确认的磁盘副本不能重新写成权威数据")
    }

    @Test
    fun authoritativeHistoryReplacesDiskWhilePreservingNewLiveEvents() {
        val store = SharedHistoryStore()
        store.hydrateCachedWindow("s", window(event(8, "disk"), event(100, "stale-tail")))
        store.liveEventReceived(wireJson.encodeToString(event(9, "live")))
        store.start("s", false, true, 8)
        store.pageReceived(
            "s",
            wireJson.encodeToString(listOf(event(8, "server"), event(9, "old-page"))),
            100,
            false,
            null,
            10.0
        )
        assertEquals(listOf("server", "live"), records(store).map { it.event.text })
        val saved = wireJson.decodeFromString<CachedHistoryWindow>(assertNotNull(store.cachedWindowJson("s", 2, 999)))
        assertEquals(999, saved.persistentCursor)
        assertNull(saved.nextBeforeSequence)
    }

    @Test
    fun emptyServerResultRemovesStaleDiskTailAndLateDiskCannotOverwriteIt() {
        val store = SharedHistoryStore()
        store.hydrateCachedWindow("s", window(event(100, "disk")))
        store.start("s", false, true, 100)
        store.pageReceived("s", "[]", 0, false, null, null)
        assertEquals(emptyList(), records(store))
        store.hydrateCachedWindow("s", window(event(100, "late-disk")))
        assertEquals(emptyList(), records(store))
    }

    @Test
    fun identicalSnapshotClearsDiskProvenanceAndChunksAreNeverPersisted() {
        val store = SharedHistoryStore()
        store.hydrateCachedWindow("s", window(event(1, "same")))
        store.installSnapshot("s", wireJson.encodeToString(listOf(event(1, "same"))), true, 7)
        assertNotNull(store.cachedWindowJson("s", 1, 500))
        store.liveEventReceived(wireJson.encodeToString(event(2, "delta").copy(event = GatewayEvent("assistant/chunk", text = "delta"))))
        val saved = wireJson.decodeFromString<CachedHistoryWindow>(assertNotNull(store.cachedWindowJson("s", 1, 500)))
        assertEquals(listOf(1), saved.events.map { it.seq })
        assertEquals(7, saved.nextBeforeSequence)
        assertEquals(500, saved.persistentCursor)
    }

    @Test
    fun invalidOrForeignWindowIsRejected() {
        val store = SharedHistoryStore()
        assertFalse(store.hydrateCachedWindow("s", "bad-json").accepted)
        assertFalse(store.hydrateCachedWindow("other", window(event(1, "foreign"))).accepted)
        assertFalse(store.hydrateCachedWindow("s", window(event(1, "a"), event(1, "duplicate"))).accepted)
        assertEquals(emptyList(), records(store))
    }

    @Test
    fun freshEmptyNetworkPageBlocksLateDiskAndOlderPagesRemainCacheable() {
        val store = SharedHistoryStore()
        store.start("s", false, false, null)
        store.pageReceived("s", "[]", 0, false, null, null)
        store.hydrateCachedWindow("s", window(event(99, "late")))
        assertEquals(emptyList(), records(store))
        store.installSnapshot("s", wireJson.encodeToString(listOf(event(8, "new"))), true, 7)
        store.start("s", true, true, 8)
        store.pageReceived("s", wireJson.encodeToString(listOf(event(1, "old"))), 20, false, null, null)
        assertNotNull(store.cachedWindowJson("s", 1, 999))
    }

    private fun window(vararg events: SessionEvent): String = wireJson.encodeToString(
        CachedHistoryWindow(
            sessionId = "s",
            historyFormatVersion = 1,
            persistentCursor = 999,
            hasMore = true,
            nextBeforeSequence = 7,
            events = events.toList()
        )
    )

    private fun event(seq: Int, text: String) = SessionEvent("s", seq, seq.toDouble(), GatewayEvent("assistant/message", text = text))

    private fun records(store: SharedHistoryStore): List<SessionEvent> {
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        return wireJson.decodeFromString<SharedHistoryBootstrap>(received.single().statePayloadJson!!).eventsBySession["s"].orEmpty()
    }
}

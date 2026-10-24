package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOfflineCatalogTest {
    @Test fun offlineSelectionNeverRequestsHistoryOrCreatesLoadingState() {
        val requests = mutableListOf<String>()
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { id, _, _ -> requests += id })
        frame(projection, sessions)
        projection.selectSession("s", offline = true)
        assertTrue(requests.isEmpty())
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertEquals("s", projection.snapshot().selectedSessionId)
    }

    @Test fun disconnectedKeepsCatalogSelectionAndNetworkTextButAllowsSameFormatLocalOlderPage() {
        val requests = mutableListOf<String>()
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { id, _, _ -> requests += id })
        frame(projection, sessions)
        frame(projection, workspaces)
        frame(projection, """{"kind":"hello","historyFormatVersion":1}""")
        projection.selectSession("s")
        frame(projection, history)
        val before = projection.snapshot()
        assertFalse(projection.canPrependCachedPage("s"))
        val disconnected = projection.disconnected()
        assertEquals(before.sessions, disconnected.sessions)
        assertEquals(before.workspaces, disconnected.workspaces)
        assertEquals(before.conversation, disconnected.conversation)
        assertEquals("s", disconnected.selectedSessionId)
        assertFalse(disconnected.selectedHistoryIsLoading)
        val older = CachedHistoryWindow(
            sessionId = "s",
            historyFormatVersion = 1,
            events = listOf(SessionEvent("s", 1, 1.0, GatewayEvent("user/message", text = "older cached")))
        )
        assertTrue(projection.canPrependCachedPage("s", older))
        assertFalse(projection.canPrependCachedPage("s", older.copy(historyFormatVersion = 3)))
        projection.prependCachedPage("s", older)
        assertEquals(listOf("older cached", "latest network"), projection.snapshot().conversation.map { it.text })
        assertEquals(listOf("s"), requests)
        // 重连后的真实网络空窗口可以删除离线正文，不会 merge 复活旧页。
        projection.catchUpSelectedHistoryAfterReconnect()
        frame(projection, """{"kind":"history","sessionId":"s","historyFormatVersion":1,"events":[],"hasMore":false}""")
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertFalse(projection.canPrependCachedPage("s"))
    }

    @Test fun actorMergesLateCatalogWithoutOverwritingUserSelectionOrFreshEmptyAuthority() = runTest {
        val catalog = AndroidGatewayProjection().let {
            frame(it, sessions)
            frame(it, workspaces)
            it.selectSession("s", offline = true)
            it.exportOfflineCatalog("w")
        }
        val publications = mutableListOf<com.clarklevis.dsh.shared.facade.SharedMobileSnapshot>()
        val actor = AndroidProjectionActor(AndroidGatewayProjection(), Dispatchers.Unconfined, { snapshot, _ -> publications += snapshot })
        val initial = actor.offlineCatalogRestoreGeneration()
        assertTrue(actor.restoreOfflineCatalog(catalog, initial))
        assertEquals("s", publications.last().selectedSessionId)
        assertEquals("w", publications.last().workspaces.single().workspaceId)
        assertFalse(publications.last().selectedHistoryIsLoading)
        actor.close()

        val selected =
            AndroidProjectionActor(AndroidGatewayProjection(), Dispatchers.Unconfined, { snapshot, _ -> publications += snapshot })
        val oldGeneration = selected.offlineCatalogRestoreGeneration()
        selected.selectSession("new-selection", offline = true)
        assertTrue(selected.restoreOfflineCatalog(catalog, oldGeneration))
        assertEquals("new-selection", publications.last().selectedSessionId)
        selected.close()

        val authority =
            AndroidProjectionActor(AndroidGatewayProjection(), Dispatchers.Unconfined, { snapshot, _ -> publications += snapshot })
        val generation = authority.offlineCatalogRestoreGeneration()
        val empty = """{"kind":"sessions","items":[]}"""
        authority.acceptFrame(empty, GatewayWireDecoder.decode(empty), null)
        assertTrue(authority.restoreOfflineCatalog(catalog, generation))
        assertTrue(publications.last().sessions.isEmpty())
        authority.close()
    }

    @Test fun workspacesArrivingBeforeDiskRestoreKeepFreshPathButRecoverMissingSessionCatalog() = runTest {
        val source = AndroidGatewayProjection()
        frame(source, sessions)
        frame(source, workspaces)
        val catalog = source.exportOfflineCatalog("w")
        val projection = AndroidGatewayProjection()
        val actor = AndroidProjectionActor(projection, Dispatchers.Unconfined, { _, _ -> })
        val generation = actor.offlineCatalogRestoreGeneration()
        val fresh = workspaces.replace("/kept", "/fresh")
        actor.acceptFrame(fresh, GatewayWireDecoder.decode(fresh), null)
        assertTrue(actor.restoreOfflineCatalog(catalog, generation))
        assertEquals("s", projection.snapshot().sessions.single().id)
        assertEquals("/fresh", projection.snapshot().workspaces.single().path)
        actor.close()
    }

    @Test fun offlineOlderMissDoesNotUseNetworkOrKeepLoading() = runTest {
        val requests = mutableListOf<String>()
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { id, _, _ -> requests += id })
        frame(projection, sessions)
        projection.selectSession("s", offline = true)
        val actor = AndroidProjectionActor(projection, Dispatchers.Unconfined, { _, _ -> })
        actor.loadHistory("s", older = true, offline = true)
        assertTrue(requests.isEmpty())
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertFalse(projection.snapshot().selectedHistoryHasMore)
        actor.close()
    }

    @Test fun archiveAndTitleChangesUseTheirRealWireFieldsAndMissingPayloadIsIgnored() {
        assertTrue(GatewayWireDecoder.decode("""{"kind":"session-archives","archivedSessionIds":["s"]}""").hasOfflineCatalogMutation())
        assertTrue(
            GatewayWireDecoder.decode(
                """{"kind":"session-title-changed","sessionId":"s","title":"Changed","seq":10}"""
            ).hasOfflineCatalogMutation()
        )
        assertFalse(GatewayWireDecoder.decode("""{"kind":"session-archives"}""").hasOfflineCatalogMutation())
        assertFalse(GatewayWireDecoder.decode("""{"kind":"session-title-changed","sessionId":"s"}""").hasOfflineCatalogMutation())
    }

    @Test fun validEmptyAuthorityCanBePersistedWhereasMissingOrMalformedDirectoryCannot() = runTest {
        val projection = AndroidGatewayProjection()
        val actor = AndroidProjectionActor(projection, Dispatchers.Unconfined, { _, _ -> })
        assertFalse(actor.hasOfflineCatalogAuthority())
        val missing = """{"kind":"sessions"}"""
        actor.acceptFrame(missing, GatewayWireDecoder.decode(missing), null)
        assertFalse(actor.hasOfflineCatalogAuthority())
        val malformed = """{"kind":"sessions","items":[{}]}"""
        actor.acceptFrame(malformed, GatewayWireDecoder.decode(malformed), null)
        assertFalse(actor.hasOfflineCatalogAuthority())
        val empty = """{"kind":"sessions","items":[]}"""
        actor.acceptFrame(empty, GatewayWireDecoder.decode(empty), null)
        assertTrue(actor.hasOfflineCatalogAuthority())
        actor.close()
    }

    private fun frame(projection: AndroidGatewayProjection, raw: String) {
        val frame = GatewayWireDecoder.decode(raw)
        projection.acceptFrame(raw, frame, frame.sessionId)
    }

    companion object {
        private val sessions =
            """{"kind":"sessions","items":[{"sessionId":"s","title":"Kept session","updatedAt":10,"running":false,"blank":false}]}"""
        private val workspaces =
            """{"kind":"workspaces","items":[{"workspaceId":"w","path":"/kept","title":"Kept workspace","sessionIds":["s"],"createdAt":"now","updatedAt":"now"}]}"""
        private val history =
            """{"kind":"history","sessionId":"s","historyFormatVersion":1,"events":[{"type":"user/message","seq":2,"time":2,"data":{"content":[{"type":"text","text":"latest network"}]}}],"hasMore":true,"nextBeforeSeq":2}"""
    }
}

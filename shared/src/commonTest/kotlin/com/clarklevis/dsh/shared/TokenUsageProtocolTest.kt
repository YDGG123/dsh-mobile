package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.SessionControlState
import com.clarklevis.dsh.shared.facade.SharedSessionControlPatch
import com.clarklevis.dsh.shared.facade.SharedSessionControlStore
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsageTotals
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenUsageProtocolTest {
    @Test
    fun contextProjectionAcceptsReportedCacheReadCountAndPreservesSnapshotAndPatch() {
        val store = SharedSessionControlStore()
        val result = store.mergeContextProjection(
            "session-1", 42,
            """{"outputTokens":8235762,"uncachedInputTokens":22506251,"cacheWriteTokens":0,"cacheReadTokens":2284347716}""",
            null, null
        )

        assertTrue(result.applied)
        assertNull(result.errorCode)
        val state = wireJson.decodeFromString<SessionControlState>(assertNotNull(store.snapshot().snapshotJson))
        val usage = assertNotNull(state.contextSnapshots["session-1"]?.tokenUsage)
        assertEquals(2_284_347_716L, usage.cacheReadTokens)
        assertEquals(22_506_251L, usage.uncachedInputTokens)
        assertEquals(8_235_762L, usage.outputTokens)
        assertEquals(0L, usage.cacheWriteTokens)
        val patch = wireJson.decodeFromString<SharedSessionControlPatch>(assertNotNull(result.snapshotJson))
        assertEquals(usage, patch.contextSnapshotsUpsert["session-1"]?.tokenUsage)
    }

    @Test
    fun wireAndStoreResponsesAndProjectionsPreserveAllLargeTokenCounts() {
        val totalsJson = """{"inputTokens":2284347716,"outputTokens":2284347717,"cacheReadTokens":2284347718,"cacheWriteTokens":2284347719,"reasoningTokens":2284347720}"""
        val usageJson = """{"uncachedInputTokens":2284347716,"outputTokens":2284347717,"cacheReadTokens":2284347718,"cacheWriteTokens":2284347719,"totals":$totalsJson}"""
        val expectedTotals = GatewaySessionTokenUsageTotals(
            inputTokens = 2_284_347_716L, outputTokens = 2_284_347_717L,
            cacheReadTokens = 2_284_347_718L, cacheWriteTokens = 2_284_347_719L,
            reasoningTokens = 2_284_347_720L
        )
        val context = GatewayWireDecoder.decode(
            """{"kind":"context-usage","sessionId":"session-1","tokenUsage":$usageJson}"""
        )
        val usage = assertNotNull(context.tokenUsage)
        assertEquals(2_284_347_716L, usage.uncachedInputTokens)
        assertEquals(2_284_347_717L, usage.outputTokens)
        assertEquals(2_284_347_718L, usage.cacheReadTokens)
        assertEquals(2_284_347_719L, usage.cacheWriteTokens)
        assertEquals(expectedTotals, usage.totals)
        val stats = GatewayWireDecoder.decode(
            """{"kind":"session-stats","sessionId":"session-1","tokenUsage":{"totals":$totalsJson}}"""
        )
        assertEquals(expectedTotals, stats.tokenUsage?.totals)

        for (isProjection in listOf(false, true)) {
            val store = SharedSessionControlStore()
            val contextResult = if (isProjection) {
                store.mergeContextProjection("session-1", 42, usageJson, null, null)
            } else {
                store.requestContextUsage("session-1", true)
                store.contextReceived("session-1", 42, usageJson, null, null)
            }
            assertTrue(contextResult.applied)
            assertNull(contextResult.errorCode)
            val contextState = wireJson.decodeFromString<SessionControlState>(
                assertNotNull(store.snapshot().snapshotJson)
            )
            assertEquals(usage, contextState.contextSnapshots["session-1"]?.tokenUsage)
            val statsResult = if (isProjection) {
                store.mergeStatsProjection("session-1", 42, null, totalsJson, null)
            } else {
                store.requestSessionStats("session-1", true)
                store.statsReceived("session-1", 42, null, totalsJson, null)
            }
            assertTrue(statsResult.applied)
            assertNull(statsResult.errorCode)
            val state = wireJson.decodeFromString<SessionControlState>(assertNotNull(store.snapshot().snapshotJson))
            assertEquals(expectedTotals, state.sessionStatsSnapshots["session-1"]?.tokenUsage?.totals)
        }
    }
}

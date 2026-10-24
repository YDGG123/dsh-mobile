package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidChatPrefetcherTest {
    private class Harness(val scope: TestScope, val order: List<String> = listOf("hot", "warm", "cold")) {
        val requests = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val prefetch = AndroidChatPrefetcher(
            scope.backgroundScope,
            plan = { excluded, selected -> order.filter { it !in excluded && it != selected } },
            ticket = { "ticket-$it" },
            request = { id, _ ->
                requests += id
                true
            },
            accept = { id, _, _ ->
                writes += id
                true
            },
            cancelTicket = { id, _ -> cancelled += id },
            cleanupScope = scope.backgroundScope,
            monotonicMillis = { scope.testScheduler.currentTime }
        )
        fun connect(busy: Boolean = false) {
            prefetch.connectionChanged(true)
            prefetch.foregroundChanged("current", busy)
            prefetch.helloReceived(1)
            prefetch.sessionsReceived()
        }
        suspend fun response(id: String, kind: String = "history", requestType: String? = null): Boolean =
            prefetch.intercept("wire", GatewayFrame(kind, sessionId = id, requestType = requestType), id, isBackgroundHistory = true)
    }

    @Test fun onlySelectedRunningSessionAndForegroundPagingPausePrefetch() {
        val state = com.clarklevis.dsh.shared.facade.SharedMobileSnapshot(
            selectedSessionId = "current",
            sessions = listOf(
                com.clarklevis.dsh.shared.domain.SessionSummary("current", "", 0.0, false, false),
                com.clarklevis.dsh.shared.domain.SessionSummary("background", "", 0.0, true, false)
            )
        )
        assertFalse(state.isChatPrefetchBusy())
        assertTrue(state.copy(selectedSessionId = "background").isChatPrefetchBusy())
        assertTrue(state.copy(selectedHistoryIsLoading = true).isChatPrefetchBusy())
        assertTrue(state.copy(selectedHistoryIsLoadingOlder = true).isChatPrefetchBusy())
    }

    @Test fun firstScreenThenIdleHotnessOrderAndSingleFlight() = runTest {
        val h = Harness(this)
        h.connect(busy = true)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(h.requests.isEmpty())
        h.prefetch.foregroundChanged("current", false)
        advanceTimeBy(749)
        runCurrent()
        assertTrue(h.requests.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("hot"), h.requests)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, h.requests.size)
        assertTrue(h.response("hot"))
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot", "warm"), h.requests)
        assertEquals(listOf("hot"), h.writes)
    }

    @Test fun helloAndSessionListAreBothRequired() = runTest {
        val h = Harness(this)
        h.prefetch.connectionChanged(true)
        h.prefetch.helloReceived(null)
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(h.requests.isEmpty())
        h.prefetch.sessionsReceived()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot"), h.requests)
    }

    @Test fun handshakeFramesArrivingBeforeConnectedStateStillStartExactlyOneRound() = runTest {
        val h = Harness(this)
        h.prefetch.helloReceived(1)
        h.prefetch.sessionsReceived()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(h.requests.isEmpty())
        h.prefetch.connectionChanged(true)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot"), h.requests)
        h.prefetch.connectionChanged(true)
        h.prefetch.sessionsReceived()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot"), h.requests)
    }

    @Test fun noMoreThanSixRequestsAndSessionUpdatesDoNotRestartRound() = runTest {
        val h = Harness(this, (1..20).map { "session-$it" })
        h.connect()
        repeat(6) {
            advanceTimeBy(750)
            runCurrent()
            assertTrue(h.response(h.requests.last()))
        }
        advanceTimeBy(750)
        runCurrent()
        repeat(4) { h.prefetch.sessionsReceived() }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(6, h.requests.size)
    }

    @Test fun foregroundSelectionHandsLegacyPageBackAndNeverSwallowsItsError() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        h.prefetch.foregroundChanged("hot", true)
        assertFalse(h.response("hot"))
        assertFalse(h.response("hot", "error", "history"))
        assertFalse(h.prefetch.intercept("wire", GatewayFrame("error", requestType = "history"), null))
        assertTrue(h.writes.isEmpty())
        runCurrent()
        assertEquals(listOf("hot"), h.cancelled)
        h.prefetch.foregroundChanged("hot", false)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot", "warm"), h.requests)
    }

    @Test fun assistantStreamSelectionDropsRawPrefetchPageWithoutMutatingBaseline() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        h.prefetch.foregroundChanged("hot", true, acceptsHistory = false)
        assertTrue(h.response("hot"))
        assertTrue(h.writes.isEmpty())
        assertFalse(h.prefetch.intercept("normal older", GatewayFrame("history", sessionId = "hot"), "hot", isBackgroundHistory = false))
        assertFalse(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestRejected(
                    "history",
                    "gateway-request-failed",
                    "hot",
                    isBackgroundHistory = false
                )
            )
        )
        assertFalse(h.prefetch.intercept("snapshot", GatewayFrame("session-snapshot", sessionId = "hot"), "hot"))
        assertFalse(h.prefetch.intercept("stream", GatewayFrame("assistant-stream", sessionId = "hot"), "hot"))
    }

    @Test fun runtimeTerminalFailureIsIsolatedAndForegroundHandoffRetainsItsOwnError() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        assertTrue(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestRejected(
                    "history",
                    "gateway-request-failed",
                    "hot",
                    isBackgroundHistory = true
                )
            )
        )
        assertFalse(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestRejected(
                    "history",
                    "gateway-request-failed",
                    "current"
                )
            )
        )
        assertFalse(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestRejected(
                    "history",
                    "gateway-request-failed",
                    null
                )
            )
        )
        assertFalse(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestRejected(
                    "message",
                    "gateway-request-failed",
                    "hot"
                )
            )
        )
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot", "warm"), h.requests)
        h.prefetch.foregroundChanged("warm", true)
        assertFalse(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestTimedOut("history", "warm", isBackgroundHistory = true)
            )
        )
        h.prefetch.foregroundChanged("warm", true, acceptsHistory = false)
        assertTrue(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestTimedOut("history", "warm", isBackgroundHistory = true)
            )
        )
    }

    @Test fun timeoutDiscardsOnlyItsOwnLateNonCurrentResponse() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(10_750)
        runCurrent()
        assertTrue(h.response("hot"))
        assertFalse(h.prefetch.intercept("normal", GatewayFrame("history", sessionId = "unrelated"), "unrelated"))
        assertFalse(h.response("current"))
        assertTrue(h.writes.isEmpty())
        assertEquals(listOf("hot"), h.cancelled)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot"), h.requests)
    }

    @Test fun backgroundTimeoutCooldownSurvivesRepeatedAutomaticReconnectsAndForegroundRemainsUnfiltered() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        assertTrue(
            h.prefetch.interceptTerminal(
                com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestTimedOut(
                    "history",
                    "hot",
                    isBackgroundHistory = true
                )
            )
        )
        repeat(3) {
            h.prefetch.connectionChanged(false)
            h.connect()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf("hot"), h.requests)
        }
        // 冷却只限制可选预加载，真实前台 history/错误照常交给原来的 Store。
        assertFalse(h.prefetch.intercept("foreground", GatewayFrame("history", sessionId = "current"), "current"))
        assertFalse(
            h.prefetch.interceptTerminal(com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent.RequestTimedOut("history", "current"))
        )
        advanceTimeBy(120_001)
        runCurrent()
        h.prefetch.connectionChanged(false)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot", "hot"), h.requests)
    }

    @Test fun clearAndMemoryPressureStopRemainingRoundAndInvalidateInflightTicket() = runTest {
        for (reason in listOf("clear", "memory", "close")) {
            val h = Harness(this)
            h.connect()
            advanceTimeBy(750)
            runCurrent()
            h.prefetch.stop(reason)
            assertTrue(h.response("hot"))
            h.prefetch.foregroundChanged("current", false)
            h.prefetch.sessionsReceived()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf("hot"), h.requests)
            assertTrue(h.writes.isEmpty())
            assertEquals(listOf("hot"), h.cancelled)
        }
    }

    @Test fun disconnectCancelsOldRoundAndReconnectWaitsForNewHandshake() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        h.prefetch.connectionChanged(false)
        assertTrue(h.response("hot"))
        assertTrue(h.writes.isEmpty())
        h.prefetch.connectionChanged(true)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, h.requests.size)
        h.prefetch.helloReceived(1)
        h.prefetch.sessionsReceived()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot", "hot"), h.requests)
    }

    @Test fun generationActivityPausesPendingRequestAndDoesNotWarmWhileBusy() = runTest {
        val h = Harness(this)
        h.connect()
        advanceTimeBy(750)
        runCurrent()
        h.prefetch.foregroundChanged("current", true)
        assertTrue(h.response("hot"))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf("hot"), h.requests)
        assertTrue(h.writes.isEmpty())
        h.prefetch.foregroundChanged("current", false)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("hot", "warm"), h.requests)
    }
}

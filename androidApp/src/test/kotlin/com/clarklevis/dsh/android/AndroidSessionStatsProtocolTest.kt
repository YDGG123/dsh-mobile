package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSessionStatsProtocolTest {
    @Test
    fun statsAcceptInFlightStepObject() {
        for (firstTokenTime in listOf("null", "1200.5")) {
            val frame =
                GatewayWireDecoder.decode(
                    """{"kind":"session-stats","sessionId":"s1","asOfSeq":42,
                    "sessionStats":{"turns":3,"steps":157,"decodeTokens":385,
                        "openStep":{"turn":4,"step":158,"startTime":1000,"firstTokenTime":$firstTokenTime},
                        "pendingCalls":{}},
                    "tokenUsage":{"totals":{"inputTokens":100,"outputTokens":50}}}""",
                )

            assertEquals("session-stats", frame.kind)
            assertEquals(157, frame.sessionStats?.steps)
            assertEquals(
                158.0,
                frame.sessionStats
                    ?.openStep
                    ?.get("step")
                    ?.doubleValue,
            )
            assertEquals(100, frame.tokenUsage?.totals?.inputTokens)
        }
    }

    @Test
    fun statsAcceptCompletedStepAndPublicProjectionWithoutInternalState() {
        for (state in listOf("", ",\"openStep\":null", ",\"openStep\":158")) {
            val frame =
                GatewayWireDecoder.decode(
                    """{"kind":"session-stats","sessionId":"s1","sessionStats":{"turns":4,"steps":158$state}}""",
                )

            assertEquals(158, frame.sessionStats?.steps)
            assertNull(frame.tokenUsage)
        }
    }
}

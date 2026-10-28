package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.LocaleTestRule
import org.junit.Rule
import com.clarklevis.dsh.shared.protocol.GatewaySessionStats
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatsFormatterTest {
    @get:Rule val locale = LocaleTestRule()

    @Test
    fun `formats execution metrics like iOS`() {
        val stats = GatewaySessionStats(
            turns = 2,
            steps = 3,
            ttftMs = 1_941.0,
            ttftSteps = 3,
            decodeMs = 900.0,
            decodeTokens = 100.0
        )

        assertEquals("2 轮 · 3 步", SessionStatsFormatter.turnsStepsLine(stats))
        assertEquals("27.1s", SessionStatsFormatter.duration(27_100.0))
        assertEquals("647ms", SessionStatsFormatter.duration(SessionStatsFormatter.averageTtft(stats)!!))
        assertEquals("111.1", SessionStatsFormatter.compactDecimal(SessionStatsFormatter.throughput(stats)!!))
    }

    @Test
    fun `formats token and cache metrics compactly`() {
        assertEquals("9.4K", SessionStatsFormatter.compact(9_400))
        assertEquals("1M", SessionStatsFormatter.compact(1_000_000))
        assertEquals("2284.3M", SessionStatsFormatter.compact(2_284_347_716L))
        assertEquals(
            0.5,
            SessionStatsFormatter.cacheHitRate(
                GatewaySessionTokenUsageTotals(inputTokens = 100, cacheReadTokens = 100)
            )!!,
            0.0001
        )
        assertNull(SessionStatsFormatter.turnsStepsLine(null))
    }

    @Test
    fun `cache rate preserves counts and totals above Int max`() {
        assertEquals(
            0.5,
            SessionStatsFormatter.cacheHitRate(
                GatewaySessionTokenUsageTotals(inputTokens = 2_284_347_716L, cacheReadTokens = 2_284_347_716L)
            )!!,
            0.0001
        )
        assertEquals(
            0.5,
            SessionStatsFormatter.cacheHitRate(
                GatewaySessionTokenUsageTotals(inputTokens = 1_500_000_000L, cacheReadTokens = 1_500_000_000L)
            )!!,
            0.0001
        )
    }
}

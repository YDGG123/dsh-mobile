package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.teamfeed.TeamFeedGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1 段 5 纯映射判据（Chief：段 5 抽纯函数，JVM 用例钉住渲染器选择与状态语义）。 */
class TeamFeedOverlayTest {

    // —— 状态副行：契约枚举 working/thinking/idle；缺字段一律「不动」——
    @Test
    fun statusLineFollowsContractEnum() {
        assertEquals("正在干活", TeamFeedOverlay.statusLine("working", degraded = false, bridgeBusy = false))
        assertEquals("正在干活", TeamFeedOverlay.statusLine("thinking", degraded = false, bridgeBusy = true))
        assertEquals("空闲", TeamFeedOverlay.statusLine("idle", degraded = false, bridgeBusy = true))
    }

    /**
     * 缺字段「不动」= **沿用 bridge 既有显示**（bridge busy 就是现在的显示来源），
     * 不是清空；bridgeBusy 也拿不到时才返回 null（调用方保持上一帧）。
     * 未知值（契约外）同样按缺字段处理，不猜。
     */
    @Test
    fun missingActivityFallsBackToBridgeDisplay() {
        assertEquals("空闲", TeamFeedOverlay.statusLine(null, degraded = false, bridgeBusy = false))
        assertEquals("正在干活", TeamFeedOverlay.statusLine(null, degraded = false, bridgeBusy = true))
        assertNull(TeamFeedOverlay.statusLine(null, degraded = false, bridgeBusy = null))
        // 未知值（契约外）按缺字段处理 ⇒ 沿用 bridge 显示，不猜。
        assertEquals("正在干活", TeamFeedOverlay.statusLine("napping", degraded = false, bridgeBusy = true))
    }

    /** degraded ⇒ 易变数据不可信：状态回落 bridge，且 overlay 不激活。 */
    @Test
    fun degradedSuppressesVolatileOverlay() {
        assertNull(TeamFeedOverlay.statusLine("working", degraded = true, bridgeBusy = false))
        assertFalse(TeamFeedOverlay.overlayActive(TeamFeedPhase.LIVE, degraded = true))
        assertTrue(TeamFeedOverlay.overlayActive(TeamFeedPhase.LIVE, degraded = false))
        assertFalse(TeamFeedOverlay.overlayActive(TeamFeedPhase.CONNECTING, degraded = false))
    }

    // —— 兜底链：image > glyph > shape > 派生 > 字母（本层只管 image vs glyph）——
    @Test
    fun glyphOnlyWhenFeedLiveAndNoImage() {
        val glyph = TeamFeedGlyph(body = "M1 1L2 2Z")
        assertTrue(TeamFeedOverlay.useGlyph(feedLive = true, degraded = false, hasImage = false, glyph = glyph))
        assertFalse("有照片就不用 glyph（契约顺序）", TeamFeedOverlay.useGlyph(true, false, hasImage = true, glyph = glyph))
        assertFalse("降级时不换渲染", TeamFeedOverlay.useGlyph(true, true, hasImage = false, glyph = glyph))
        assertFalse("feed 未 live 不换渲染", TeamFeedOverlay.useGlyph(false, false, hasImage = false, glyph = glyph))
        assertFalse("glyph 空 body 不用", TeamFeedOverlay.useGlyph(true, false, hasImage = false, glyph = TeamFeedGlyph(body = "")))
    }

    @Test
    fun imagePreferredOverGlyphRegardlessOfGlyphPresence() {
        assertTrue(TeamFeedOverlay.preferImageOverGlyph(hasImage = true, hasGlyph = true))
        assertTrue(TeamFeedOverlay.preferImageOverGlyph(hasImage = true, hasGlyph = false))
        assertFalse(TeamFeedOverlay.preferImageOverGlyph(hasImage = false, hasGlyph = true))
    }
}

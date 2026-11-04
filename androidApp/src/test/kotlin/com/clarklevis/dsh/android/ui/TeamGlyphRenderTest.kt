package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** vc61 ③⑥ 判据：眼睛椭圆几何 + 水合缓存渲染门。 */
class TeamGlyphRenderTest {

    /** ③ 眼睛 = 椭圆（w=2rx, h=2ry）+ 0.5pt 下界；之前 drawCircle(rx) 把高瘦眼画成圆点（真机反馈）。 */
    @Test
    fun eyeSizeUsesFullWidthAndHeightWithFloor() {
        // w=4,h=8 的眼（rx=2,ry=4），scale=3 ⇒ 宽 12、高 24——宽高不等是关键断言
        val (w, h) = teamGlyphEyeSize(rx = 2.0, ry = 4.0, scale = 3f)
        assertEquals(12f, w)
        assertEquals(24f, h)
    }

    @Test
    fun tinyEyeHitsFloorNotZero() {
        val (w, h) = teamGlyphEyeSize(rx = 0.0001, ry = 0.0001, scale = 1f)
        assertEquals(0.5f, w)
        assertEquals(0.5f, h)
    }

    /** 反向：正圆眼（rx==ry）尺寸相等（回归护栏：不得又变成只有半径一边）。 */
    @Test
    fun roundEyeStaysRound() {
        val (w, h) = teamGlyphEyeSize(rx = 3.0, ry = 3.0, scale = 2f)
        assertEquals(w, h)
    }

    /** ⑥ 水合缓存也允许渲染行（切 tab 回来立刻有头像）；LIVE+非降级照旧允许。 */
    @Test
    fun hydratedStateAllowsRowRendering() {
        assertTrue(TeamFeedOverlay.overlayActiveWithCache(TeamFeedPhase.CONNECTING, degraded = false, hydrated = true))
        assertTrue(TeamFeedOverlay.overlayActiveWithCache(TeamFeedPhase.LIVE, degraded = false, hydrated = false))
    }

    /** 反向 1：缓存未命中（hydrated=false）且非 LIVE ⇒ 不渲染（旧行为不变）。 */
    @Test
    fun noCacheNoLiveStillFallsBack() {
        assertFalse(TeamFeedOverlay.overlayActiveWithCache(TeamFeedPhase.CONNECTING, degraded = false, hydrated = false))
        assertFalse(TeamFeedOverlay.overlayActiveWithCache(TeamFeedPhase.IDLE, degraded = false, hydrated = false))
    }

    /** 反向 2：降级横幅语义不被缓存稀释——LIVE+degraded 只开横幅不开行级 overlay（旧红线）。 */
    @Test
    fun degradedBannerSemanticsUnchanged() {
        assertFalse(TeamFeedOverlay.overlayActive(TeamFeedPhase.LIVE, degraded = true))
        assertFalse(TeamFeedOverlay.overlayActiveWithCache(TeamFeedPhase.LIVE, degraded = true, hydrated = false))
    }
}

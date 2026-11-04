package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** vc61③⑥ + vc62③ 判据：眼睛胶囊几何 / 眨眼压缩 / 水合缓存渲染门。 */
class TeamGlyphRenderTest {

    /** ③ 眼睛 = **胶囊**（w=2rx、h=2ry、cornerRadius=min(w,h)/2）——vc62 对齐 iOS TeamGlyph.swift:216-218。 */
    @Test
    fun eyeCapsuleUsesFullWidthAndHeight() {
        // w=10、h=4 的眼（rx=5,ry=2），scale=1 ⇒ 10×4、圆角 2（胶囊）
        val rect = teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 1f)
        assertEquals(10f, rect.width)
        assertEquals(4f, rect.height)
        assertEquals(2f, rect.cornerRadius)
    }

    /** ① 判据（Chief 2026-11-04）：eye(w=10,h=4)、squash=0.1 ⇒ **高度变 0.4、宽度不变**——压短轴不是压整只。 */
    @Test
    fun squashShrinksHeightOnly() {
        val rect = teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 1f, eyeSquash = 0.1f)
        assertEquals("宽度不受眨眼影响", 10f, rect.width)
        assertEquals(0.4f, rect.height)
        assertEquals("圆角沿用未压尺寸（capsule 随 scaleY 整体压扁，iOS 同构）", 2f, rect.cornerRadius)
    }

    /** ② 反向对照：squash=1 尺寸与未压一致；squash 夹 [0.01,1]（iOS eyePath:230——眯到近闭但不消失）。 */
    @Test
    fun squashOneIsIdentityAndClamped() {
        val idle = teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 1f, eyeSquash = 1f)
        assertEquals(idle, teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 1f))
        assertEquals("0 输入夹到 0.01", 0.04f, teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 1f, eyeSquash = 0f).height)
        assertEquals(">1 输入夹到 1", 4f, teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 1f, eyeSquash = 7f).height)
    }

    /** 反向：scale 参与宽高（iOS path 缩放后在视图层画）；tilt 不在尺寸函数里（旋转由 rotate 块承担）。 */
    @Test
    fun scaleMultipliesBothDimensions() {
        val rect = teamGlyphEyeRect(rx = 5.0, ry = 2.0, scale = 2f)
        assertEquals(20f, rect.width)
        assertEquals(8f, rect.height)
    }

    /** 反向：极小眼下界 0.5/0.25 生效（对齐 iOS 视图路径 eyeLayout——Chief 2026-11-04 纠正后恢复）。 */
    @Test
    fun tinyEyeHitsFloors() {
        val rect = teamGlyphEyeRect(rx = 0.0001, ry = 0.0001, scale = 1f)
        assertEquals(0.5f, rect.width)
        assertEquals("下界在 squash 之前：0.5×squash(1)=0.5", 0.5f, rect.height)
        assertEquals(0.25f, rect.cornerRadius)
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

package com.clarklevis.dsh.shared.teamfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc63 判据：眼洞填色对比度校正（iOS `TeamGlyph.swift:450-475` 逐行移植的共享实现）。
 * 判据来源 = Chief 2026-11-04 派单：①三分支 ②校正后对比度 ≥3:1（不是"接近"）。
 */
class TeamAvatarEyeColorTest {

    /** ① 分支一：backdrop 与 body 对比已足 → 原样返回（不混色）。 */
    @Test
    fun sufficientContrastReturnsBackdropAsIs() {
        val backdrop = RgbColor.WHITE
        val body = RgbColor.BLACK
        assertEquals(backdrop, TeamAvatarEyeColor.resolve(backdrop, body), "21:1 足量 → 原色")
    }

    /** ① 分支二：暗车身不足 → 往白推，且结果与 body 对比 ≥3:1。 */
    @Test
    fun darkBodyInsufficientPushesTowardWhite() {
        // 深底（charcoal 系）+ 近黑车身：iOS 实测 1.02-1.08:1，典型不足
        val backdrop = RgbColor(0x17 / 255.0, 0x18 / 255.0, 0x18 / 255.0)
        val body = RgbColor(0.05, 0.05, 0.05)
        assertTrue(backdrop.contrast(body) < TeamAvatarEyeColor.MINIMUM_CONTRAST, "前置：用例确实不足 3:1")
        val fill = TeamAvatarEyeColor.resolve(backdrop, body)
        assertTrue(fill.contrast(body) >= TeamAvatarEyeColor.MINIMUM_CONTRAST, "校正后必须 ≥3:1")
        assertTrue(fill.red >= backdrop.red && fill.green >= backdrop.green, "暗车身方向 = 往白推")
    }

    /** ① 分支三：亮车身不足 → 往黑压，且结果与 body 对比 ≥3:1。 */
    @Test
    fun lightBodyInsufficientPushesTowardBlack() {
        val backdrop = RgbColor(0xA0 / 255.0, 0xA0 / 255.0, 0xA0 / 255.0)
        val body = RgbColor(0xF5 / 255.0, 0xF5 / 255.0, 0xF5 / 255.0)
        assertTrue(backdrop.contrast(body) < TeamAvatarEyeColor.MINIMUM_CONTRAST, "前置：用例确实不足 3:1")
        val fill = TeamAvatarEyeColor.resolve(backdrop, body)
        assertTrue(fill.contrast(body) >= TeamAvatarEyeColor.MINIMUM_CONTRAST, "校正后必须 ≥3:1")
        assertTrue(fill.red <= backdrop.red && fill.green <= backdrop.green, "亮车身方向 = 往黑压")
    }

    /** ② 反向：公式钉值——黑白对比恒 21:1（WCAG 定义值，公式错一位就红）。 */
    @Test
    fun whiteVersusBlackIsExactly21() {
        assertEquals(21.0, RgbColor.WHITE.contrast(RgbColor.BLACK), 1e-9)
    }

    /** ② 反向：分支边界——body 亮度跨 0.18 时方向相反（darkBodyLuminance 钉值）。
     *  注意配对要选「真不足」的：深底×亮身常常足量（0.1 底 × 0.9 身 = 13.9:1），别拿足量对验方向。 */
    @Test
    fun directionFlipsAtDarkBodyLuminanceBoundary() {
        val backdrop = RgbColor(0xA0 / 255.0, 0xA0 / 255.0, 0xA0 / 255.0) // 与亮身 2.07:1（不足）
        // 0.18 边界之下的暗车身 → 往白推（先造一个与 backdrop 不足的暗车身对）
        val darkBodyBackdrop = RgbColor(0x17 / 255.0, 0x18 / 255.0, 0x18 / 255.0)
        val darkBody = RgbColor(0.05, 0.05, 0.05) // luminance ≈0.0036 < 0.18 → 往白
        val lightBody = RgbColor(0.9, 0.9, 0.9)   // luminance ≈0.787 ≥ 0.18 → 往黑
        val toWhite = TeamAvatarEyeColor.resolve(darkBodyBackdrop, darkBody)
        val toBlack = TeamAvatarEyeColor.resolve(backdrop, lightBody)
        assertTrue(toWhite.red >= darkBodyBackdrop.red, "暗车身方向=往白推")
        assertTrue(toBlack.red < backdrop.red, "亮车身方向=往黑压")
        // 边界读法钉值：0.19 灰的公式亮度 ≈0.030，仍 < 0.18——阈值看 WCAG 公式亮度，不是灰度直觉
        assertTrue(RgbColor(0.19, 0.19, 0.19).luminance < TeamAvatarEyeColor.DARK_BODY_LUMINANCE)
    }
}

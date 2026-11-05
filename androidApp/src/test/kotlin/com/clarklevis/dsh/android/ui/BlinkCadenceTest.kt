package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bot 头像眨眼节奏（用户 2026-11-02：iOS Bot 页会眨，Android 不会，要求同步）。
 *
 * 交付的核心不是"动了"而是**与电脑端/iOS 同一相位**：同一只 Bot 在任何平台都从周期的同一处开始，
 * 否则一排头像会同时眨、机械感很重。相位的参考值由电脑端同一个 `fnv1a`
 * （`dsh-plugin-mobile-gateway/lib/mobile-v1/project-avatar.mjs` 导出的 `fnv1a`）算出后冻结在这里。
 */
class BlinkCadenceTest {

    /** 与 JS `(fnv1a(id) >>> 0) % 5000` 逐值相等（23:5x 用 node 跑出，非手抄）。 */
    @Test
    fun blinkPhaseMatchesDesktopFnv1a() {
        assertEquals(2860L, blinkPhaseMillis("session-a5000f0e-e571-41f5-9c02-8cc30cbc7fef"))
        assertEquals(3631L, blinkPhaseMillis("session-48a9b54f-0e1e-4e32-9823-3fc4e8b454c8"))
        assertEquals(4015L, blinkPhaseMillis("AndroidTester"))
        assertEquals(2230L, blinkPhaseMillis("表情符号-🤖-乙"))
    }

    /** 相位永远落在窗口内、且空 id 不炸（空 id 的 Bot 行不该因为动效崩掉）。 */
    @Test
    fun blinkPhaseStaysWithinWindow() {
        val ids = listOf("", "   ", null, "a", "b", "0", "session-ffffffff-ffff-ffff-ffff-ffffffffffff", "测试机器人-甲")
        for (id in ids) {
            val phase = blinkPhaseMillis(id)
            assertTrue("相位越界: $id -> $phase", phase in 0 until BLINK_PHASE_WINDOW_MILLIS)
        }
        assertEquals(0L, blinkPhaseMillis(null))
        assertEquals(0L, blinkPhaseMillis(""))
        assertEquals(0L, blinkPhaseMillis("   "))
    }

    /** 不同 Bot 的相位应当不同（否则整排同时眨）。 */
    @Test
    fun differentBotsDoNotSharePhase() {
        val ids = listOf(
            "session-a5000f0e-e571-41f5-9c02-8cc30cbc7fef",
            "session-48a9b54f-0e1e-4e32-9823-3fc4e8b454c8",
            "session-4d8741a8-4c26-4f23-94a4-89774b1d31ff",
            "session-48799833-b205-487f-b40c-ad96ccfe7e9e"
        )
        val phases = ids.map(::blinkPhaseMillis)
        assertEquals("相位撞车：$phases", phases.size, phases.toSet().size)
    }

    /** 动效开关：入口关或系统关动画时一律不动（无障碍"移除动画"必须生效）。 */
    @Test
    fun blinkRequiresBothEntryAndSystemAnimation() {
        assertTrue(shouldBlink(entryEnabled = true, systemAnimationsEnabled = true))
        assertFalse(shouldBlink(entryEnabled = false, systemAnimationsEnabled = true))
        assertFalse(shouldBlink(entryEnabled = true, systemAnimationsEnabled = false))
        assertFalse(shouldBlink(entryEnabled = false, systemAnimationsEnabled = false))
    }

    /** 节奏表：档位合法、周期可计算、干活时更勤（与 iOS `blinkSteps(working:)` 同意图）。 */
    @Test
    fun blinkStepsAreSaneAndWorkingIsFaster() {
        for (working in listOf(false, true)) {
            for (glyph in listOf(false, true)) {
                val steps = blinkStepsFor(working, glyphBranch = glyph)
                assertTrue("档位为空", steps.isNotEmpty())
                assertTrue("压扁系数越界", steps.all { it.squash in 0.03f..1.1f })
                assertTrue("保持时长为非正", steps.all { it.holdMillis > 0 })
                // 一轮结束后必须回稳到 1，否则头像会一直歪着。
                assertEquals(1.0f, steps.last().squash)
            }
        }
        assertTrue(
            "干活时的眨眼间隔应比空闲短（Icon 分支）",
            blinkPeriodMillis(working = true, glyphBranch = false) < blinkPeriodMillis(working = false, glyphBranch = false)
        )
        assertEquals(blinkStepsFor(false, glyphBranch = false).sumOf { it.holdMillis }, blinkPeriodMillis(false, glyphBranch = false))
    }

    /** vc63 ②判据（用户：幅度太小）：glyph 分支照 iOS 逐值——压到 0.03、一周期眨两次（9 档）、周期恰 5s。 */
    @Test
    fun glyphIdleStepsMatchIosRhythm() {
        val steps = blinkStepsFor(working = false, glyphBranch = true)
        assertEquals("iOS 空闲一周期眨两次 = 9 档", 9, steps.size)
        assertEquals("bt-blink-idle 周期 5s", 5000L, blinkPeriodMillis(working = false, glyphBranch = true))
        assertEquals("眯到 0.03（iOS 同值）", 0.03f, steps.minOf { it.squash })
        assertEquals("回弹过冲 1.06 保留", 1.06f, steps.maxOf { it.squash })
        // 两次闭眼的档位下标：iOS 序列的第 2 档与第 6 档
        assertEquals(listOf(1, 5), steps.withIndex().filter { it.value.squash == 0.03f }.map { it.index })
    }

    /** vc63 ②判据：glyph 干活档 = iOS bt-blink 2.4s（5 档），仍是 0.03。 */
    @Test
    fun glyphWorkingStepsMatchIosRhythm() {
        val steps = blinkStepsFor(working = true, glyphBranch = true)
        assertEquals("iOS 干活 5 档", 5, steps.size)
        assertEquals("bt-blink 周期 2.4s", 2400L, blinkPeriodMillis(working = true, glyphBranch = true))
        assertEquals(0.03f, steps.minOf { it.squash })
    }

    /** vc63 有意差异：Icon 分支（整只符号）保持轻压 0.80——0.03 会把符号压成一条线。 */
    @Test
    fun iconBranchKeepsLightSquash() {
        for (working in listOf(false, true)) {
            val steps = blinkStepsFor(working, glyphBranch = false)
            assertTrue(
                "Icon 分支整只符号不得压到 0.03",
                steps.all { it.squash >= 0.5f },
            )
        }
    }
}
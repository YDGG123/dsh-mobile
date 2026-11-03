package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc55 ②的**代码级判据**（Chief：落点要机器判，不落设备级）：
 * 三个头像的实际落点必须是 **0 / 16 / 32**（iOS ZStack 同一起点语义），
 * 反向对照 = 旧 Row+offset 的 **0 / 43 / 86** 必须被判不等——否则只验数字、不验排布语义换了。
 */
class RoomStackGeometryTest {

    @Test
    fun threeMembersLandAt0_16_32() {
        val (width, offsets) = RoomStackGeometry.layout(3)
        assertEquals(listOf(0f, 16f, 32f), offsets)
        assertEquals(70, width) // 38 + (3-1)×16
    }

    /** 反向对照：旧 Row 语义（排位 27/54 再叠 16 步进）= 0/43/86，必须**不是**现在的落点。 */
    @Test
    fun legacyRowSemanticsAreExplicitlyNotUsed() {
        val legacy = listOf(0f, 43f, 86f)
        val (_, offsets) = RoomStackGeometry.layout(3)
        assertTrue("落点不得退回旧 Row 语义 0/43/86", offsets != legacy && offsets.take(2) != legacy.take(2))
    }

    @Test
    fun singleAndTwoMembers() {
        assertEquals(Pair(38, listOf(0f)), RoomStackGeometry.layout(1))
        assertEquals(Pair(54, listOf(0f, 16f)), RoomStackGeometry.layout(2))
    }

    /** 上限 3（iOS prefix(3) 同规）；0 成员回退 38 容器（RoomGroupGlyph 路径不进堆叠）。 */
    @Test
    fun capsAtThreeAndZeroIsGlyphFallbackSize() {
        val (width, offsets) = RoomStackGeometry.layout(9)
        assertEquals(3, offsets.size)
        assertEquals(70, width)
        assertEquals(Pair(38, emptyList<Float>()), RoomStackGeometry.layout(0))
    }
}

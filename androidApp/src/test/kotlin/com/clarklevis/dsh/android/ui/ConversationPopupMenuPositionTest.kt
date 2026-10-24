package com.clarklevis.dsh.android.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationPopupMenuPositionTest {
    @Test
    fun teamPanelKeepsItsAnchorWhileShadowFitsInsideWindow() {
        val result =
            dshMenuPosition(
                IntRect(290, 80, 338, 126),
                IntSize(411, 900),
                IntSize(348, 448),
                LayoutDirection.Ltr,
                24,
                IntOffset(0, 8),
            )
        assertEquals(IntOffset(14, 110), result)
        assertEquals(38, result.x + 24)
        assertEquals(134, result.y + 24)
    }

    @Test
    fun moreMenuOffsetStillPositionsTheVisiblePanel() {
        val result =
            dshMenuPosition(
                IntRect(352, 80, 400, 126),
                IntSize(411, 900),
                IntSize(276, 120),
                LayoutDirection.Ltr,
                24,
                IntOffset(-220, 8),
            )
        assertEquals(IntOffset(108, 110), result)
    }

    @Test
    fun rightToLeftPanelMirrorsTheAnchorWithoutLosingShadowSpace() {
        val result =
            dshMenuPosition(
                IntRect(73, 80, 121, 126),
                IntSize(411, 900),
                IntSize(348, 448),
                LayoutDirection.Rtl,
                24,
                IntOffset(0, 8),
            )
        assertEquals(IntOffset(49, 110), result)
    }

    @Test
    fun bottomAnchorMovesMenuAboveAndShortWindowKeepsPopupOnScreen() {
        assertEquals(
            IntOffset(14, 348),
            dshMenuPosition(
                IntRect(290, 780, 338, 826),
                IntSize(411, 900),
                IntSize(348, 448),
                LayoutDirection.Ltr,
                24,
                IntOffset(0, 8),
            ),
        )
        assertEquals(
            IntOffset.Zero,
            dshMenuPosition(
                IntRect(248, 280, 296, 326),
                IntSize(320, 360),
                IntSize(320, 344),
                LayoutDirection.Ltr,
                24,
                IntOffset(0, 8),
            ),
        )
    }
}

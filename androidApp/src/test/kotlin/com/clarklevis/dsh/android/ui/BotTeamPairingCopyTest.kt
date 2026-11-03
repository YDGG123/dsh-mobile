package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** vc60 P1 判据：未配对档文案必须区分「从未配对」与「凭证轮换后自动恢复中」，不许共用一句话。 */
class BotTeamPairingCopyTest {

    @Test
    fun reissuePendingShowsRecoveringCopy() {
        assertEquals("凭证已被桌面端轮换，正在自动重新获取…", pairingStatusCopy(reissuePending = true))
    }

    @Test
    fun plainUnpairedShowsPairingCopy() {
        assertEquals(
            "尚未配对桥接设备：请先与网关配对（配对成功会自动下发团队会话）",
            pairingStatusCopy(reissuePending = false),
        )
    }

    /** 反向：两态永不共用文案（防止将来有人把 when 合并掉，P1 语义回归）。 */
    @Test
    fun twoStatesNeverShareCopy() {
        assertNotEquals(pairingStatusCopy(true), pairingStatusCopy(false))
    }
}

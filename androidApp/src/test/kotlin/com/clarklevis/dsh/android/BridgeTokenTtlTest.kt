package com.clarklevis.dsh.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主动换证的节奏换算（对齐 iOS `BridgeTokenTTL`）。
 *
 * 为什么值得测：这个数决定"多久换一次凭证"——算错要么频繁换证打扰网关，
 * 要么贴近过期边界导致**偶发 401**（用户看到的形态是"用一会儿就掉线"）。
 */
class BridgeTokenTtlTest {

    /** 正常 TTL：取 90%（15 分钟 → 810 秒）。 */
    @Test
    fun takesNinetyPercentOfTtl() {
        assertEquals(810.0, BridgeTokenTtl.proactiveIntervalSeconds(900.0)!!, 0.001)
    }

    /** 夹到上限：超长 TTL 不得无限延后刷新（1 小时封顶）。 */
    @Test
    fun clampsToMaximum() {
        assertEquals(3600.0, BridgeTokenTtl.proactiveIntervalSeconds(24 * 3600.0)!!, 0.001)
    }

    /** 夹到下限：极短 TTL 不得高频换证（60 秒封顶）。 */
    @Test
    fun clampsToMinimum() {
        assertEquals(60.0, BridgeTokenTtl.proactiveIntervalSeconds(10.0)!!, 0.001)
    }

    /** 非正 / 非有限 一律当作"没给"（调用方回落 14 分钟兜底）。 */
    @Test
    fun rejectsInvalidTtl() {
        assertNull(BridgeTokenTtl.proactiveIntervalSeconds(0.0))
        assertNull(BridgeTokenTtl.proactiveIntervalSeconds(-5.0))
        assertNull(BridgeTokenTtl.proactiveIntervalSeconds(Double.NaN))
        assertNull(BridgeTokenTtl.proactiveIntervalSeconds(Double.POSITIVE_INFINITY))
        assertNull(BridgeTokenTtl.proactiveIntervalSeconds(null))
    }

    /** 从 /info 解析：字段缺失、类型不对、坏 JSON 都返回 null（前向兼容红线）。 */
    @Test
    fun parsesTtlFromInfoAndToleratesBadShapes() {
        val ok = """{"capabilities":{"accessTokenTtlSeconds":900}}"""
        assertEquals(810.0, BridgeTokenTtl.proactiveIntervalSecondsFromInfo(ok)!!, 0.001)

        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo("""{"capabilities":{}}"""))
        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo("""{"capabilities":{"accessTokenTtlSeconds":"900"}}"""))
        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo("""{"capabilities":{"accessTokenTtlSeconds":-1}}"""))
        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo("""{"capabilities":[]}"""))
        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo("not json"))
        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo(null))
    }

    /** 老网关（无 capabilities）→ null，调用方用 14 分钟兜底。 */
    @Test
    fun oldGatewayWithoutCapabilitiesFallsBack() {
        assertNull(BridgeTokenTtl.proactiveIntervalSecondsFromInfo("""{"server":"x"}"""))
        assertEquals(840.0, BridgeTokenTtl.DEFAULT_INTERVAL_SECONDS, 0.001)
    }
}

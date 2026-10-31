package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.decodeBridgeErrorEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 审查修复（iOSCoder 预审 P1 + iOSTester origin 收紧意见）的纯函数用例：
 * origin 策略门对齐 iOS `BridgeTransport.allowInsecureLoopback`（生产 https-only）；
 * 错误信封解码保证 401 → wire UNAUTHORIZED → 判死重配链可达。
 */
class AndroidBridgeOriginPolicyTest {
    @Test
    fun originPolicyMirrorsIosHttpsOnlyWithLoopbackOptIn() {
        val f = AndroidBridgeTeamAdapter::isAcceptableBridgeOrigin
        assertTrue(f("https://ydgg.dpdns.org", false))
        assertFalse(f("http://192.168.1.5:3081", false))
        assertFalse("非 loopback 不因开关放行", f("http://192.168.1.5:3081", true))
        assertFalse("loopback 明文默认拒绝", f("http://127.0.0.1:3081", false))
        assertTrue(f("http://127.0.0.1:3081", true))
        assertTrue(f("http://localhost:3081", true))
        assertTrue(f("http://[::1]:3081", true))
        assertFalse("空 origin 拒绝", f(null, true))
        assertFalse(f("ftp://example.com", true))
        assertFalse(f("not-a-url", true))
    }

    @Test
    fun endpointDerivationFollowsScheme() {
        assertEquals("https://ydgg.dpdns.org", AndroidBridgeTeamAdapter.originFromEndpoint("wss://ydgg.dpdns.org/ws/mobile"))
        assertEquals("http://10.0.2.2:3081", AndroidBridgeTeamAdapter.originFromEndpoint("ws://10.0.2.2:3081/ws/mobile"))
        assertNull(AndroidBridgeTeamAdapter.originFromEndpoint("not-a-url"))
        assertNull(AndroidBridgeTeamAdapter.originFromEndpoint(""))
    }

    @Test
    fun unboundPathsAndCrossOriginAreRejected() {
        val isBound = AndroidBridgeTeamAdapter::isBoundUrl
        val bound = "https://ydgg.dpdns.org"
        assertTrue(isBound("https://ydgg.dpdns.org/mobile/v1/info", bound))
        assertTrue(isBound("https://ydgg.dpdns.org/mobile/v1", bound))
        assertFalse("跨 host 拒绝", isBound("https://evil.example/mobile/v1/info", bound))
        assertFalse("降 scheme 拒绝", isBound("http://ydgg.dpdns.org/mobile/v1/info", bound))
        assertFalse("换 port 拒绝", isBound("https://ydgg.dpdns.org:9/mobile/v1/info", bound))
        assertFalse("越界路径拒绝", isBound("https://ydgg.dpdns.org/admin", bound))
        assertFalse("归一化后越界拒绝", isBound("https://ydgg.dpdns.org/mobile/v1/../admin", bound))
        assertFalse("无绑定全拒绝", isBound("https://ydgg.dpdns.org/mobile/v1/info", null))
    }

    @Test
    fun errorEnvelopeDecodeKeepsWireCodeForIdentityFailure() {
        val error = decodeBridgeErrorEnvelope(401, """{"error":{"code":"UNAUTHORIZED","message":"token expired"}}""")
        assertEquals("UNAUTHORIZED", error.code)
        assertTrue("全大写码应分到 wire 族", error.isWire)
        assertEquals("UNAUTHORIZED", error.knownWireCode)

        val fallback = decodeBridgeErrorEnvelope(500, "not json")
        assertEquals("request_failed", fallback.code)
        assertFalse("无信封兜底 request_failed 属 client 族", fallback.isWire)
    }
}

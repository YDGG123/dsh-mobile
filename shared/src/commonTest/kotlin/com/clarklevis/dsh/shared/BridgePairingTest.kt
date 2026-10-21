package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.BridgePairingCompleteResult
import com.clarklevis.dsh.shared.protocol.BridgePairingParseResult
import com.clarklevis.dsh.shared.protocol.classifyBridgePairingComplete
import com.clarklevis.dsh.shared.protocol.parseBridgePairingPayload
import com.clarklevis.dsh.shared.protocol.parseBridgeSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 配对资料解析与 complete 三态（§2.8）——真机配对链路的纯逻辑基线。 */
class BridgePairingTest {

    private val now = 1_760_000_000_000L

    private fun payload(
        url: String = "https://pushing-cricket-ensure-execute.trycloudflare.com",
        code: String = "abc123",
        expiresAt: Long? = now + 120_000,
        tlsPin: String? = null
    ): String = buildString {
        append("""{"url":"$url","code":"$code","serverId":"computer-1"""")
        if (expiresAt != null) append(""","expiresAt":$expiresAt""")
        if (tlsPin != null) append(""","tlsPin":"$tlsPin"""")
        append("}")
    }

    @Test
    fun validPayloadNormalizesOrigin() {
        val result = parseBridgePairingPayload(payload(), now)
        assertTrue(result.isValid)
        assertEquals("https://pushing-cricket-ensure-execute.trycloudflare.com", result.origin)
        assertEquals("abc123", result.code)
        assertEquals("computer-1", result.serverId)
        assertNull(result.tlsPin)
    }

    @Test
    fun invalidJsonIsRejected() {
        assertEquals(
            BridgePairingParseResult.STATUS_INVALID_JSON,
            parseBridgePairingPayload("not-json", now).status
        )
        assertEquals(
            BridgePairingParseResult.STATUS_INVALID_JSON,
            parseBridgePairingPayload("""{"code":"abc"}""", now).status
        )
    }

    @Test
    fun insecureOrDirtyURLIsRejected() {
        assertEquals(
            BridgePairingParseResult.STATUS_INSECURE_URL,
            parseBridgePairingPayload(payload(url = "http://192.168.1.5:7443"), now).status
        )
        assertEquals(
            BridgePairingParseResult.STATUS_INSECURE_URL,
            parseBridgePairingPayload(payload(url = "https://host.example/other/path"), now).status
        )
    }

    @Test
    fun blankCodeIsRejected() {
        assertEquals(
            BridgePairingParseResult.STATUS_INVALID_CODE,
            parseBridgePairingPayload(payload(code = "  "), now).status
        )
    }

    @Test
    fun expiredPayloadIsRejected() {
        assertEquals(
            BridgePairingParseResult.STATUS_EXPIRED,
            parseBridgePairingPayload(payload(expiresAt = now - 1), now).status
        )
    }

    @Test
    fun malformedPinIsRejected() {
        assertEquals(
            BridgePairingParseResult.STATUS_INVALID_PIN,
            parseBridgePairingPayload(payload(tlsPin = "sha256:nope"), now).status
        )
    }

    @Test
    fun pendingCompleteStaysPending() {
        val result = classifyBridgePairingComplete(
            """{"status":"pending","requestId":"req-1"}""",
            origin = "https://host.example",
            tlsPin = null
        )
        assertEquals(BridgePairingCompleteResult.STATUS_PENDING, result.status)
        assertTrue(result.isPending)
        assertNull(result.sessionJson)
    }

    @Test
    fun approvedCompleteMergesBindingAndProducesSessionJson() {
        val result = classifyBridgePairingComplete(
            """{"accessToken":"acc","refreshToken":"ref","deviceId":"dev-1",
                "serverId":"computer-1","expiresAt":${now + 900_000}}""",
            origin = "https://host.example",
            tlsPin = null
        )
        assertTrue(result.isApproved)
        val json = result.sessionJson
        assertTrue(!json.isNullOrBlank())
        val session = parseBridgeSession(json!!)
        assertEquals("acc", session.accessToken)
        assertEquals("dev-1", session.deviceId)
        assertEquals("https://host.example", session.serverUrl)
        assertNull(session.tlsPin)
    }

    @Test
    fun approvedCompleteKeepsPinBinding() {
        val pin = "sha256:" + "a".repeat(64)
        val result = classifyBridgePairingComplete(
            """{"accessToken":"acc","refreshToken":"ref","deviceId":"dev-1",
                "serverId":"computer-1","expiresAt":${now + 900_000}}""",
            origin = "https://host.example",
            tlsPin = pin
        )
        assertTrue(result.isApproved)
        assertEquals(pin, parseBridgeSession(result.sessionJson!!).tlsPin)
    }

    @Test
    fun garbageCompleteIsInvalid() {
        assertEquals(
            BridgePairingCompleteResult.STATUS_INVALID,
            classifyBridgePairingComplete("nope", origin = "https://host.example", tlsPin = null).status
        )
        assertEquals(
            BridgePairingCompleteResult.STATUS_INVALID,
            classifyBridgePairingComplete("""{"accessToken":""}""", "https://host.example", null).status
        )
    }
}

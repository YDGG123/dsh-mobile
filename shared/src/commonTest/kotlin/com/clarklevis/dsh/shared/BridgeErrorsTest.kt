package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.BridgeClientKnownCode
import com.clarklevis.dsh.shared.protocol.BridgeTeamError
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import com.clarklevis.dsh.shared.protocol.decodeBridgeErrorEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 错误码两族分表（§2.9 v1.2）单测：分族、透传不归一、未知码 fail-closed、信封兜底。 */
class BridgeErrorsTest {

    @Test
    fun wireFamilyUppercaseCodesAreKnown() {
        val error = com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode("READ_ONLY")
        assertEquals(BridgeTeamError.SOURCE_WIRE, error.source)
        assertEquals("READ_ONLY", error.code)
        assertEquals(BridgeWireKnownCode.READ_ONLY.name, error.knownWireCode)
        assertNull(error.knownClientCode)
        assertTrue(error.isWire)
    }

    @Test
    fun clientFamilyLowercaseCodesAreKnown() {
        val error = com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode("server_changed")
        assertEquals(BridgeTeamError.SOURCE_CLIENT, error.source)
        assertEquals("server_changed", error.code)
        assertEquals(BridgeClientKnownCode.SERVER_CHANGED.name, error.knownClientCode)
        assertNull(error.knownWireCode)
    }

    @Test
    fun unknownWireCodeStaysUnrecognizedFailClosed() {
        val error = com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode("FUTURE_WIRE_CODE")
        assertEquals(BridgeTeamError.SOURCE_WIRE, error.source)
        assertEquals("FUTURE_WIRE_CODE", error.code)
        assertNull(error.knownWireCode)
        assertTrue(error.isUnrecognizedWire)
    }

    @Test
    fun unknownClientCodeStaysUnrecognizedFailClosed() {
        val error = com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode("future_client_code")
        assertEquals(BridgeTeamError.SOURCE_CLIENT, error.source)
        assertNull(error.knownClientCode)
        assertTrue(error.isUnrecognizedWire.not())
    }

    @Test
    fun envelopeCodeIsPassedThroughVerbatim() {
        val error = decodeBridgeErrorEnvelope(
            409,
            """{"error":{"code":"QUEUE_CHANGED","message":"队列已变化"}}"""
        )
        assertEquals(BridgeTeamError.SOURCE_WIRE, error.source)
        assertEquals("QUEUE_CHANGED", error.code)
        assertEquals("队列已变化", error.message)
    }

    @Test
    fun envelopeWithoutCodeFallsBackToRequestFailed() {
        // index.js:303 语义：value?.error?.code || 'request_failed'（族 2）。
        val noEnvelope = decodeBridgeErrorEnvelope(500, """{"oops":true}""")
        assertEquals(BridgeTeamError.SOURCE_CLIENT, noEnvelope.source)
        assertEquals(BridgeClientKnownCode.REQUEST_FAILED.wireValue, noEnvelope.code)

        val garbage = decodeBridgeErrorEnvelope(500, "not-json-at-all")
        assertEquals(BridgeClientKnownCode.REQUEST_FAILED.wireValue, garbage.code)
    }

    @Test
    fun caseAloneDecidesTheFamily() {
        // 同词根两族并存：DISPOSED(wire 503) vs disposed(client)。
        assertEquals(
            BridgeWireKnownCode.DISPOSED.name,
            com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode("DISPOSED").knownWireCode
        )
        assertEquals(
            BridgeClientKnownCode.DISPOSED.name,
            com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode("disposed").knownClientCode
        )
    }
}

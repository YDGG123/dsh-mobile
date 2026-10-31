package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.BridgeSessionStore
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.facade.SharedBridgeTeamStore
import com.clarklevis.dsh.shared.protocol.decodeBridgeErrorEnvelope
import com.clarklevis.dsh.shared.protocol.decodeTeamUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1 收口（Chief 拍板 2026-11-01）：HTTP 非 2xx 不能只当通用失败吞掉——
 * 401 的错误信封 → wire 族 `unauthorized` → reducer 判死 → NEEDS_PAIRING → 平台清落盘会话。
 * 修复前：fetch 把 status 丢掉，token 失效后 8s 轮询无限空转、永不提示重配。
 */
class BridgeTeamAuthDeathChainTest {
    private class RecordingSessionStore : BridgeSessionStore {
        val operations = mutableListOf<String>()
        override fun loadJson(): String? = null
        override fun saveJson(json: String) {
            operations += "save"
        }

        override fun clear() {
            operations += "clear"
        }
    }

    @Test
    fun http401EnvelopeDrivesStoreIntoNeedsPairingAndClearsSessionStore() {
        val sessionStore = RecordingSessionStore()
        val store = SharedBridgeTeamStore(sessionStore)
        assertTrue(store.sessionPaired("""{"accessToken":"a","refreshToken":"r","deviceId":"d","serverId":"s"}""").isSuccess)

        // 网关 401 的真实信封形态（本机实测：GET /mobile/v1/state 不带 token）。
        val error = decodeBridgeErrorEnvelope(
            401,
            """{"error":{"code":"UNAUTHORIZED","message":"A paired device session is required."}}"""
        )
        // classifyBridgeErrorCode 保留原始码并按大小写分族：全大写 = wire 族（判死）。
        assertEquals("UNAUTHORIZED", error.code, "401 envelope should classify into the wire family")

        val result = store.refreshFailed(error.code, error.message)
        assertTrue(result.isSuccess, "refreshFailed should accept a known wire code")

        val state = decodeTeamUiState(requireNotNull(store.snapshot().snapshotJson))
        assertEquals(TeamPhase.NEEDS_PAIRING, state.phase, "401 must land in NEEDS_PAIRING, not endless offline")
        assertTrue(
            "clear" in sessionStore.operations,
            "platform session store must be cleared on identity death (was: ${sessionStore.operations})"
        )
    }

    @Test
    fun genericClientErrorStaysOfflineInsteadOfForcingRePairing() {
        val store = SharedBridgeTeamStore(RecordingSessionStore())
        store.sessionPaired("""{"accessToken":"a","refreshToken":"r","deviceId":"d","serverId":"s"}""")

        // 5xx / 传输类失败是暂时的：不许把用户踢回配对。
        val error = decodeBridgeErrorEnvelope(503, """{"error":{"code":"SERVICE_UNAVAILABLE","message":"down"}}""")
        store.refreshFailed(error.code, error.message)
        val state = decodeTeamUiState(requireNotNull(store.snapshot().snapshotJson))
        assertEquals(TeamPhase.OFFLINE, state.phase, "temporary transport failures keep the last roster and stay OFFLINE")
    }
}

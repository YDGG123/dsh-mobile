package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.facade.SharedBridgeTeamStore
import com.clarklevis.dsh.shared.protocol.decodeTeamUiState
import com.clarklevis.dsh.shared.protocol.parseBridgeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1 管线验证：用 2026-11-01 从本机网关（/mobile/v1）抓下的真实
 * `/info` `/state` `/conversations` 响应走完 SharedBridgeTeamStore 合流与 UI 状态回解。
 *
 * 资源文件（shared/src/jvmTest/resources/bridge-c1/）是原样抓包，不改一字 ——
 * 换网关/换版本时要重新抓，保证测试对着真实 wire。
 */
class BridgeTeamC1PipelineTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/bridge-c1/$name")?.readBytes()?.decodeToString()
            ?: error("missing resource bridge-c1/$name")

    private val serverId = "6002f4c5-385e-4e1c-b70a-7a0561a3c832"

    @Test
    fun sessionPairedThenRefreshProducesRosterWithRealBotNames() {
        val store = SharedBridgeTeamStore()
        val session =
            """{"accessToken":"test-access","refreshToken":"test-refresh","deviceId":"device-1","serverId":"$serverId"}"""
        assertTrue(store.sessionPaired(session).isSuccess)

        val result = store.refreshTeam(resource("info.json"), resource("state.json"), resource("conversations.json"))
        assertTrue(result.isSuccess, "refreshTeam failed: ${result.errorCode} ${result.errorMessage}")

        val snapshotJson = requireNotNull(store.snapshot().snapshotJson)
        val state = decodeTeamUiState(snapshotJson)

        assertEquals(TeamPhase.READY, state.phase, "state should be READY after a successful refresh")
        assertEquals("DS Bot", state.serverName, "/info 的 name 应进入状态")

        val botNames = state.bots.map { it.name }
        assertTrue("AndroidCoder" in botNames, "expected AndroidCoder in bots, got $botNames")
        assertTrue("Chief" in botNames, "expected Chief in bots, got $botNames")
        assertTrue(state.rooms.isNotEmpty(), "rooms should not be empty (real gateway has rooms)")
        assertTrue(
            state.bots.size + state.rooms.size + state.sessions.size == 72,
            "roster rows (${state.bots.size}+${state.rooms.size}+${state.sessions.size}) should equal /conversations rows (72)"
        )
    }

    @Test
    fun wireStateDecodesAndToleratesUnknownFields() {
        // 前向兼容红线：新版本宿主新增字段必须被忽略而不是解码失败。
        // 注意 wire /state 是 BridgeTeamState（store 内部解析），不是 TeamUiState。
        val wireState = parseBridgeState(resource("state.json"))
        assertEquals(11, wireState.bots.size, "state.json bots should decode")
        assertTrue(wireState.revisionValue != null)
    }
}

package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.OpenTargetResolution
import com.clarklevis.dsh.shared.domain.TeamAction
import com.clarklevis.dsh.shared.domain.TeamCapabilities
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.domain.TeamReducer
import com.clarklevis.dsh.shared.domain.TeamUiState
import com.clarklevis.dsh.shared.protocol.BridgeClientKnownCode
import com.clarklevis.dsh.shared.protocol.BridgeConversationRow
import com.clarklevis.dsh.shared.protocol.BridgeConversationsPage
import com.clarklevis.dsh.shared.protocol.BridgeServerInfo
import com.clarklevis.dsh.shared.protocol.BridgeTeamState
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamReducerTest {

    private fun info(
        protocolVersion: Int = 1,
        serverId: String = "computer-1"
    ) = BridgeServerInfo(
        protocolVersion = protocolVersion,
        serverId = serverId,
        capabilities = kotlinx.serialization.json.buildJsonObject {
            put("actions", kotlinx.serialization.json.JsonArray(
                listOf(kotlinx.serialization.json.JsonPrimitive("models"), kotlinx.serialization.json.JsonPrimitive("set-flags"))
            ))
            put("groups", kotlinx.serialization.json.JsonPrimitive(true))
            put("queue", kotlinx.serialization.json.JsonPrimitive(false))
        }
    )

    private fun bridgeState(
        revision: Long = 7,
        readOnly: String? = null,
        sessions: List<BridgeConversationRow> = emptyList(),
        bots: List<BridgeConversationRow> = listOf(
            BridgeConversationRow(id = "chief", kind = "bot", name = "Chief", sessionId = "s-chief", pinned = true),
            BridgeConversationRow(id = "coder", kind = "bot", name = "Coder", sessionId = "s-coder")
        )
    ) = BridgeTeamState(
        revision = kotlinx.serialization.json.JsonPrimitive(revision),
        readOnly = readOnly,
        sessions = sessions,
        bots = bots,
        rooms = listOf(
            BridgeConversationRow(
                id = "room-1", kind = "room", name = "开发组", sessionId = "room-1",
                members = listOf("chief", "coder"), admin = "chief", mode = "admin"
            )
        ),
        mainBotIds = listOf("chief"),
        mainBotId = "chief"
    )

    private fun ready(revision: Long = 7): TeamUiState = TeamReducer.reduce(
        TeamReducer.reduce(TeamUiState(), TeamAction.SessionPaired(
            com.clarklevis.dsh.shared.protocol.BridgeDeviceSession(
                accessToken = "a", refreshToken = "r", deviceId = "d", serverId = "computer-1"
            )
        )),
        TeamAction.RefreshSucceeded(info(), bridgeState(revision), BridgeConversationsPage())
    )

    @Test
    fun idleGoesLoadingThenReadyWithSortedRowsAndCaps() {
        val loading = TeamReducer.reduce(TeamUiState(), TeamAction.SessionPaired(
            com.clarklevis.dsh.shared.protocol.BridgeDeviceSession(
                accessToken = "a", refreshToken = "r", deviceId = "d", serverId = "computer-1"
            )
        ))
        assertEquals(TeamPhase.LOADING, loading.phase)

        val readyState = ready()
        assertEquals(TeamPhase.READY, readyState.phase)
        assertEquals(7L, readyState.revision)
        assertEquals("computer-1", readyState.serverId)
        // main 在前，其余按名称。
        assertEquals(listOf("chief", "coder"), readyState.bots.map { it.id })
        assertTrue(readyState.capabilities.supportsAction("set-flags"))
        assertFalse(readyState.capabilities.supportsAction("update-bot"))
        assertTrue(readyState.capabilities.supportsFeature("groups"))
        assertTrue(readyState.canWrite)
    }

    @Test
    fun staleRevisionIsDroppedWithoutStateChange() {
        val current = ready(revision = 7)
        val stale = TeamReducer.reduce(
            current,
            TeamAction.RefreshSucceeded(info(), bridgeState(revision = 6), BridgeConversationsPage())
        )
        assertEquals(current, stale)
        assertEquals(7L, stale.revision)
    }

    @Test
    fun revisionGateIsPerServerSoNewServerReplacesStaleRows() {
        // 换服务器：新 serverId + 更小 revision 必须被接受，否则旧 sessionId 残留 → 发消息 409。
        val previous = ready(revision = 9000)
        assertEquals("computer-1", previous.serverId)
        val switched = TeamReducer.reduce(
            previous,
            TeamAction.RefreshSucceeded(
                info(serverId = "computer-2"),
                bridgeState(
                    revision = 3,
                    bots = listOf(
                        BridgeConversationRow(id = "chief", kind = "bot", name = "Chief", sessionId = "new-chief", pinned = true)
                    )
                ),
                BridgeConversationsPage()
            )
        )
        assertEquals("computer-2", switched.serverId)
        assertEquals(3L, switched.revision)
        assertEquals(listOf("chief"), switched.bots.map { it.id })
        assertEquals("new-chief", switched.bots.first().sessionId)
    }

    @Test
    fun sessionsAreSortedByLastActiveAndOpenIsAllowed() {
        // U4：普通会话行（kind:"session"）进 state，按 lastActiveAt 倒序；点入放行。
        val state = TeamReducer.reduce(
            ready(),
            TeamAction.RefreshSucceeded(
                info(),
                bridgeState(
                    revision = 8,
                    sessions = listOf(
                        BridgeConversationRow(id = "s-old", kind = "session", name = "Old", sessionId = "s-old", lastActiveAt = 10),
                        BridgeConversationRow(id = "s-new", kind = "session", name = "New", sessionId = "s-new", lastActiveAt = 90, workspace = "alpha"),
                        BridgeConversationRow(id = "s-unknown", kind = "session", name = "Unknown", sessionId = "s-unknown")
                    )
                ),
                BridgeConversationsPage()
            )
        )
        assertEquals(listOf("s-new", "s-old", "s-unknown"), state.sessions.map { it.id })
        assertEquals("alpha", state.sessions.first().workspace)
        // 团队行不受影响。
        assertEquals(listOf("chief", "coder"), state.bots.map { it.id })
        val open = TeamReducer.resolveOpenTarget(state, "s-new")
        assertTrue(open is OpenTargetResolution.Ready, "会话行应可打开")
        assertEquals("s-new", (open as OpenTargetResolution.Ready).sessionId)
    }

    @Test
    fun refreshFailureKeepsDataAndGoesOffline() {
        val current = ready()
        val offline = TeamReducer.reduce(
            current,
            TeamAction.RefreshFailed(
                com.clarklevis.dsh.shared.protocol.BridgeTeamError(
                    source = com.clarklevis.dsh.shared.protocol.BridgeTeamError.SOURCE_CLIENT,
                    code = BridgeClientKnownCode.NETWORK_ERROR.wireValue,
                    knownClientCode = BridgeClientKnownCode.NETWORK_ERROR.name
                )
            )
        )
        assertEquals(TeamPhase.OFFLINE, offline.phase)
        // 保留最后一次数据供展示（§6 P1 ③）。
        assertEquals(2, offline.bots.size)
        assertTrue(offline.canWrite.not())
    }

    @Test
    fun unauthorizedAndServerChangedReturnToPairing() {
        val wireUnauthorized = TeamReducer.reduce(
            ready(),
            TeamAction.RefreshFailed(
                com.clarklevis.dsh.shared.protocol.BridgeTeamError(
                    source = com.clarklevis.dsh.shared.protocol.BridgeTeamError.SOURCE_WIRE,
                    code = BridgeWireKnownCode.UNAUTHORIZED.wireValue,
                    knownWireCode = BridgeWireKnownCode.UNAUTHORIZED.name
                )
            )
        )
        assertEquals(TeamPhase.NEEDS_PAIRING, wireUnauthorized.phase)

        val clientServerChanged = TeamReducer.reduce(
            ready(),
            TeamAction.RefreshFailed(
                com.clarklevis.dsh.shared.protocol.BridgeTeamError(
                    source = com.clarklevis.dsh.shared.protocol.BridgeTeamError.SOURCE_CLIENT,
                    code = BridgeClientKnownCode.SERVER_CHANGED.wireValue,
                    knownClientCode = BridgeClientKnownCode.SERVER_CHANGED.name
                )
            )
        )
        assertEquals(TeamPhase.NEEDS_PAIRING, clientServerChanged.phase)
    }

    @Test
    fun incompatibleProtocolVersionReturnsToPairing() {
        val next = TeamReducer.reduce(
            TeamReducer.reduce(TeamUiState(), TeamAction.SessionPaired(
                com.clarklevis.dsh.shared.protocol.BridgeDeviceSession(
                    accessToken = "a", refreshToken = "r", deviceId = "d", serverId = "computer-1"
                )
            )),
            TeamAction.RefreshSucceeded(
                info(protocolVersion = 2),
                bridgeState(),
                BridgeConversationsPage()
            )
        )
        assertEquals(TeamPhase.NEEDS_PAIRING, next.phase)
        assertEquals(
            BridgeClientKnownCode.INCOMPATIBLE_PROTOCOL.name,
            next.lastError?.knownClientCode
        )
    }

    @Test
    fun mainBotsLeadThenPinnedThenRest() {
        val rows = listOf(
            BridgeConversationRow(id = "worker", kind = "bot", name = "Worker", sessionId = "s-w"),
            BridgeConversationRow(id = "pinned-one", kind = "bot", name = "Pinned", sessionId = "s-p", pinned = true),
            BridgeConversationRow(id = "b-main", kind = "bot", name = "B", sessionId = "s-b"),
            BridgeConversationRow(id = "a-main", kind = "bot", name = "A", sessionId = "s-a")
        )
        val sorted = TeamReducer.sortRows(rows, listOf("b-main", "a-main"))
        assertEquals(listOf("b-main", "a-main", "pinned-one", "worker"), sorted.map { it.id })
    }

    @Test
    fun readOnlyReasonDisablesWrites() {
        val readOnly = TeamReducer.reduce(
            ready(),
            TeamAction.RefreshSucceeded(
                info(),
                bridgeState(readOnly = "宿主正在维护"),
                BridgeConversationsPage()
            )
        )
        assertEquals("宿主正在维护", readOnly.readOnlyReason)
        assertFalse(readOnly.canWrite)
    }

    @Test
    fun resolveOpenTargetCoversAllThreeOutcomes() {
        val current = ready()
        val readyRow = TeamReducer.resolveOpenTarget(current, "chief")
        assertTrue(readyRow is OpenTargetResolution.Ready)
        assertEquals("s-chief", (readyRow as OpenTargetResolution.Ready).sessionId)

        val unknown = TeamReducer.resolveOpenTarget(current, "ghost")
        assertTrue(unknown is OpenTargetResolution.Denied)
        assertEquals(
            BridgeWireKnownCode.TARGET_DENIED.name,
            (unknown as OpenTargetResolution.Denied).error.knownWireCode
        )

        val blankSession = current.copy(
            bots = current.bots.map {
                if (it.id == "chief") it.copy(sessionId = "") else it
            }
        )
        val unavailable = TeamReducer.resolveOpenTarget(blankSession, "chief")
        assertTrue(unavailable is OpenTargetResolution.Denied)
        assertEquals(
            BridgeClientKnownCode.OPEN_UNAVAILABLE.name,
            (unavailable as OpenTargetResolution.Denied).error.knownClientCode
        )
        assertNull((unavailable as OpenTargetResolution.Denied).error.knownWireCode)
    }

    @Test
    fun capabilityGatingFailsClosedOnMissingInfo() {
        // 白名单未知 ⇒ 全部动作 deny。
        val denied = TeamCapabilities.Denied
        assertFalse(denied.supportsAction("models"))
        assertFalse(denied.isKnown)

        // feature 缺失 ⇒ false；面板要求全组就绪。
        val partial = TeamCapabilities(actionsWhitelist = listOf("models"), features = mapOf("groups" to true))
        assertTrue(partial.supportsAction("models"))
        assertFalse(partial.supportsAction("memory-view"))
        assertTrue(partial.mobilePanelAllowed("groups"))
        assertFalse(partial.mobilePanelAllowed("groups", "memory"))
    }
}

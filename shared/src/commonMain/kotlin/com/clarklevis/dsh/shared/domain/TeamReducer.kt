package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.BridgeClientKnownCode
import com.clarklevis.dsh.shared.protocol.BridgeConversationRow
import com.clarklevis.dsh.shared.protocol.BridgeConversationsPage
import com.clarklevis.dsh.shared.protocol.BridgeDeviceRow
import com.clarklevis.dsh.shared.protocol.BridgeDeviceSession
import com.clarklevis.dsh.shared.protocol.BridgeDeviceRevoked
import com.clarklevis.dsh.shared.protocol.BridgeDevicesPayload
import com.clarklevis.dsh.shared.protocol.BridgeEffortRow
import com.clarklevis.dsh.shared.protocol.BridgeEffortsPayload
import com.clarklevis.dsh.shared.protocol.BridgeModelRow
import com.clarklevis.dsh.shared.protocol.BridgeModelsPayload
import com.clarklevis.dsh.shared.protocol.BridgeServerInfo
import com.clarklevis.dsh.shared.protocol.BridgeTeamError
import com.clarklevis.dsh.shared.protocol.BridgeTeamState
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import kotlinx.serialization.Serializable

/**
 * 团队页状态机（Docs/bot-team-port-design.md §3.4，P1 读 + P2 写）。
 *
 * 纯逻辑：无 I/O、无时钟。写操作为「乐观更新 + effect 回执」：
 * 请求时立即改状态（1s 内 UI 反映），回执成功清 pending，失败清 pending + 记错，
 * TARGET_CHANGED/READ_ONLY 由 store 追加 refresh-team effect 自动纠偏（§6 P2）。
 */
enum class TeamPhase {
    /** 未配对。 */
    IDLE,
    /** session 失效（server_changed / unauthorized / incompatible_protocol），需重新配对。 */
    NEEDS_PAIRING,
    /** 已配对，等待首个成功刷新。 */
    LOADING,
    /** 数据就绪，8s 前台轮询维持。 */
    READY,
    /** 刷新失败（网络/超时/5xx）；保留最后一次数据供展示。 */
    OFFLINE
}

/** 进行中的写操作；effectId 与平台回执一一对应。 */
@Serializable
data class TeamPendingWrite(
    val action: String,
    val effectId: String
)

@Serializable
data class TeamEfforts(
    val efforts: List<BridgeEffortRow> = emptyList(),
    val default: String? = null
)

@Serializable
data class TeamUiState(
    val phase: TeamPhase = TeamPhase.IDLE,
    val revision: Long? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val capabilities: TeamCapabilities = TeamCapabilities.Denied,
    val bots: List<BridgeConversationRow> = emptyList(),
    val rooms: List<BridgeConversationRow> = emptyList(),
    /** U4：普通会话（顶部分段「会话」页）。 */
    val sessions: List<BridgeConversationRow> = emptyList(),
    val models: List<BridgeModelRow> = emptyList(),
    val defaultModel: String? = null,
    val mainBotIds: List<String> = emptyList(),
    val readOnlyReason: String? = null,
    val lastError: BridgeTeamError? = null,
    /** targetId（bot/room/device id）→ 进行中写操作。 */
    val pendingActions: Map<String, TeamPendingWrite> = emptyMap(),
    /** models 动作加载结果；null 表示尚未加载（UI 按需拉取）。 */
    val modelsLoaded: Boolean = false,
    /** model ref → efforts 动作结果。 */
    val effortsByModel: Map<String, TeamEfforts> = emptyMap(),
    val devices: List<BridgeDeviceRow> = emptyList(),
    val currentDeviceId: String? = null,
    val devicesLoading: Boolean = false,
    val devicesLoadError: BridgeTeamError? = null
) {
    val canWrite: Boolean get() = readOnlyReason == null && phase == TeamPhase.READY

    fun isPending(targetId: String): Boolean = pendingActions.containsKey(targetId)
}

sealed interface TeamAction {
    data class SessionPaired(val session: BridgeDeviceSession) : TeamAction
    data class SessionLost(val error: BridgeTeamError?) : TeamAction
    data class RefreshSucceeded(
        val info: BridgeServerInfo,
        val state: BridgeTeamState,
        val conversations: BridgeConversationsPage
    ) : TeamAction

    data class RefreshFailed(val error: BridgeTeamError) : TeamAction
    data class OpenRejected(val error: BridgeTeamError) : TeamAction
    data object Reset : TeamAction

    // ---- P2 写操作 ----

    data class SetFlagsRequested(
        val id: String,
        val pinned: Boolean?,
        val hidden: Boolean?,
        val effectId: String
    ) : TeamAction

    data class UpdateBotRequested(
        val id: String,
        val includeModel: Boolean,
        val model: String?,
        val includeEffort: Boolean,
        val effort: String?,
        val effectId: String
    ) : TeamAction

    data class SetMainRequested(val id: String, val effectId: String) : TeamAction

    /** 任意写操作回执成功：按 effectId 清 pending。 */
    data class WriteApplied(val effectId: String) : TeamAction

    /** 任意写操作回执失败：按 effectId 清 pending 并记错。 */
    data class WriteFailed(val effectId: String, val error: BridgeTeamError) : TeamAction

    data class ModelsLoaded(val payload: BridgeModelsPayload) : TeamAction

    data class EffortsLoaded(val ref: String, val payload: BridgeEffortsPayload) : TeamAction

    data class DevicesRequested(val effectId: String) : TeamAction

    data class DevicesLoaded(val payload: BridgeDevicesPayload) : TeamAction

    data class DevicesFailed(val error: BridgeTeamError) : TeamAction

    data class DeviceRevokeRequested(val id: String, val effectId: String) : TeamAction

    data class DeviceRevokeApplied(val id: String, val revoked: BridgeDeviceRevoked) : TeamAction

    data class DeviceRevokeFailed(val id: String, val error: BridgeTeamError) : TeamAction
}

object TeamReducer {

    fun reduce(state: TeamUiState, action: TeamAction): TeamUiState = when (action) {
        TeamAction.Reset -> TeamUiState()
        is TeamAction.SessionPaired -> state.copy(
            phase = TeamPhase.LOADING,
            lastError = null
        )
        is TeamAction.SessionLost -> state.copy(
            phase = TeamPhase.NEEDS_PAIRING,
            lastError = action.error
        )
        is TeamAction.RefreshSucceeded -> onRefreshSucceeded(state, action)
        is TeamAction.RefreshFailed -> onRefreshFailed(state, action.error)
        is TeamAction.OpenRejected -> state.copy(lastError = action.error)
        is TeamAction.SetFlagsRequested -> onSetFlagsRequested(state, action)
        is TeamAction.UpdateBotRequested -> onUpdateBotRequested(state, action)
        is TeamAction.SetMainRequested -> onSetMainRequested(state, action)
        is TeamAction.WriteApplied -> clearPending(state, action.effectId)
        is TeamAction.WriteFailed -> failPending(state, action.effectId, action.error)
        is TeamAction.ModelsLoaded -> state.copy(
            models = action.payload.models,
            defaultModel = action.payload.defaultModel ?: state.defaultModel,
            modelsLoaded = true
        )
        is TeamAction.EffortsLoaded -> state.copy(
            effortsByModel = state.effortsByModel + (
                action.ref to TeamEfforts(action.payload.efforts, action.payload.default)
                )
        )
        is TeamAction.DevicesRequested -> state.copy(
            devicesLoading = true,
            devicesLoadError = null,
            pendingActions = state.pendingActions + (
                DEVICES_PENDING_KEY to TeamPendingWrite(ACTION_DEVICES, action.effectId)
                )
        )
        is TeamAction.DevicesLoaded -> state.copy(
            devices = action.payload.devices,
            currentDeviceId = action.payload.currentDeviceId ?: state.currentDeviceId,
            devicesLoading = false,
            devicesLoadError = null,
            pendingActions = state.pendingActions - DEVICES_PENDING_KEY
        )
        is TeamAction.DevicesFailed -> state.copy(
            devicesLoading = false,
            devicesLoadError = action.error,
            pendingActions = state.pendingActions - DEVICES_PENDING_KEY
        )
        is TeamAction.DeviceRevokeRequested -> state.copy(
            pendingActions = state.pendingActions + (
                action.id to TeamPendingWrite(ACTION_DEVICE_REVOKE, action.effectId)
                )
        )
        is TeamAction.DeviceRevokeApplied -> state.copy(
            devices = state.devices.filterNot { it.id == action.id && action.revoked.revoked == true },
            pendingActions = state.pendingActions - action.id
        )
        is TeamAction.DeviceRevokeFailed -> state.copy(
            pendingActions = state.pendingActions - action.id,
            devicesLoadError = action.error
        )
    }

    private fun onSetFlagsRequested(
        state: TeamUiState,
        action: TeamAction.SetFlagsRequested
    ): TeamUiState = state.copy(
        bots = state.bots.map { if (it.id == action.id) it.withFlags(action.pinned, action.hidden) else it },
        rooms = state.rooms.map { if (it.id == action.id) it.withFlags(action.pinned, action.hidden) else it },
        pendingActions = state.pendingActions + (
            action.id to TeamPendingWrite(ACTION_SET_FLAGS, action.effectId)
            ),
        lastError = null
    )

    private fun onUpdateBotRequested(
        state: TeamUiState,
        action: TeamAction.UpdateBotRequested
    ): TeamUiState = state.copy(
        bots = state.bots.map { row ->
            if (row.id != action.id) {
                row
            } else {
                row.copy(
                    model = if (action.includeModel) action.model else row.model,
                    effort = if (action.includeEffort) action.effort else row.effort
                )
            }
        },
        pendingActions = state.pendingActions + (
            action.id to TeamPendingWrite(ACTION_UPDATE_BOT, action.effectId)
            ),
        lastError = null
    )

    private fun onSetMainRequested(
        state: TeamUiState,
        action: TeamAction.SetMainRequested
    ): TeamUiState {
        val mainBotIds = listOf(action.id)
        return state.copy(
            mainBotIds = mainBotIds,
            bots = sortRows(state.bots, mainBotIds),
            pendingActions = state.pendingActions + (
                action.id to TeamPendingWrite(ACTION_SET_MAIN, action.effectId)
                ),
            lastError = null
        )
    }

    private fun clearPending(state: TeamUiState, effectId: String): TeamUiState {
        val entry = state.pendingActions.entries.firstOrNull { it.value.effectId == effectId }
            ?: return state
        return state.copy(pendingActions = state.pendingActions - entry.key)
    }

    private fun failPending(
        state: TeamUiState,
        effectId: String,
        error: BridgeTeamError
    ): TeamUiState {
        val entry = state.pendingActions.entries.firstOrNull { it.value.effectId == effectId }
            ?: return state.copy(lastError = error)
        return state.copy(
            pendingActions = state.pendingActions - entry.key,
            lastError = error
        )
    }

    private fun BridgeConversationRow.withFlags(pinned: Boolean?, hidden: Boolean?): BridgeConversationRow =
        copy(
            pinned = pinned ?: this.pinned,
            hidden = hidden ?: this.hidden
        )

    private fun onRefreshSucceeded(
        state: TeamUiState,
        action: TeamAction.RefreshSucceeded
    ): TeamUiState {
        // 协议版本不符：强制回到配对（§3.4 server_changed/incompatible_protocol 同路）。
        if (action.info.protocolVersion != BRIDGE_PROTOCOL_VERSION) {
            return state.copy(
                phase = TeamPhase.NEEDS_PAIRING,
                lastError = BridgeTeamError(
                    source = BridgeTeamError.SOURCE_CLIENT,
                    code = BridgeClientKnownCode.INCOMPATIBLE_PROTOCOL.wireValue,
                    knownClientCode = BridgeClientKnownCode.INCOMPATIBLE_PROTOCOL.name
                )
            )
        }
        // revision 单调门控：旧响应直接丢弃，不影响现有状态。
        // 但门控只在**同一声明服务器**内成立：换服务器（serverId 变化）后 revision
        // 从小数重新开始，继续按旧水位丢弃会把上一台机器的 bots/rooms（含旧 sessionId）
        // 留在内存里，之后发消息就会 409 TARGET_CHANGED。
        val sameServer = state.serverId == null || state.serverId == action.info.serverId
        val incomingRevision = action.state.revisionValue
        if (sameServer && state.revision != null && incomingRevision != null && incomingRevision < state.revision) {
            return state
        }
        val bots = sortRows(action.state.bots, action.state.mainBotIds)
        val rooms = sortRows(action.state.rooms, emptyList())
        // 普通会话按最近活跃倒序（无时间戳的排最后，保持服务端顺序）。
        val sessions = sortSessions(action.state.sessions)
        return state.copy(
            phase = TeamPhase.READY,
            revision = incomingRevision ?: state.revision,
            serverId = action.info.serverId,
            serverName = action.info.name,
            capabilities = TeamCapabilities.fromInfo(action.info),
            bots = bots,
            rooms = rooms,
            sessions = sessions,
            models = action.state.models,
            defaultModel = action.state.defaultModel,
            mainBotIds = action.state.mainBotIds,
            readOnlyReason = action.state.readOnly,
            lastError = null
        )
    }

    private fun onRefreshFailed(state: TeamUiState, error: BridgeTeamError): TeamUiState {
        // 身份/协议失效 ⇒ 回到配对（配合 store 发 session-clear effect）。
        val identityBroken = when {
            error.isWire -> error.knownWireCode == BridgeWireKnownCode.UNAUTHORIZED.name
            else -> error.knownClientCode == BridgeClientKnownCode.SERVER_CHANGED.name ||
                error.knownClientCode == BridgeClientKnownCode.INCOMPATIBLE_PROTOCOL.name ||
                error.knownClientCode == BridgeClientKnownCode.INVALID_SESSION.name ||
                error.knownClientCode == BridgeClientKnownCode.UNAUTHORIZED.name
        }
        return if (identityBroken) {
            state.copy(phase = TeamPhase.NEEDS_PAIRING, lastError = error)
        } else {
            state.copy(phase = TeamPhase.OFFLINE, lastError = error)
        }
    }

    /** main 在前（保持 mainBotIds 次序），pinned 次之，其余按名称稳定排序。 */
    fun sortRows(
        rows: List<BridgeConversationRow>,
        mainBotIds: List<String>
    ): List<BridgeConversationRow> {
        val mainRank = mainBotIds.withIndex().associate { (index, id) -> id to index }
        return rows.sortedWith(
            compareBy(
                { row -> mainRank[row.id] ?: Int.MAX_VALUE },
                { row -> if (mainRank.containsKey(row.id)) 0 else 1 },
                { row -> if (row.pinned == true) 0 else 1 },
                { row -> row.name }
            )
        )
    }

    /** U4：普通会话按最近活跃倒序；缺时间戳的排在最后（稳定排序保持服务端顺序）。 */
    fun sortSessions(rows: List<BridgeConversationRow>): List<BridgeConversationRow> =
        rows.sortedByDescending { it.lastActiveAt ?: Long.MIN_VALUE }

    /** 打开会话的解析：目标可见且带 sessionId 才放行；其余给出确定性失败原因。 */
    fun resolveOpenTarget(
        state: TeamUiState,
        conversationId: String
    ): OpenTargetResolution {
        val row = state.bots.plus(state.rooms).plus(state.sessions).firstOrNull { it.id == conversationId }
            ?: return OpenTargetResolution.Denied(
                BridgeTeamError(
                    source = BridgeTeamError.SOURCE_WIRE,
                    code = BridgeWireKnownCode.TARGET_DENIED.wireValue,
                    knownWireCode = BridgeWireKnownCode.TARGET_DENIED.name
                )
            )
        if (row.sessionId.isBlank()) {
            return OpenTargetResolution.Denied(
                BridgeTeamError(
                    source = BridgeTeamError.SOURCE_CLIENT,
                    code = BridgeClientKnownCode.OPEN_UNAVAILABLE.wireValue,
                    knownClientCode = BridgeClientKnownCode.OPEN_UNAVAILABLE.name
                )
            )
        }
        return OpenTargetResolution.Ready(row.sessionId)
    }

    const val BRIDGE_PROTOCOL_VERSION = 1

    const val ACTION_SET_FLAGS = "set-flags"
    const val ACTION_UPDATE_BOT = "update-bot"
    const val ACTION_SET_MAIN = "set-main"
    const val ACTION_MODELS = "models"
    const val ACTION_EFFORTS = "efforts"
    const val ACTION_DEVICES = "devices"
    const val ACTION_DEVICE_REVOKE = "device-revoke"
    const val DEVICES_PENDING_KEY = "devices"
}

sealed interface OpenTargetResolution {
    data class Ready(val sessionId: String) : OpenTargetResolution
    data class Denied(val error: BridgeTeamError) : OpenTargetResolution
}

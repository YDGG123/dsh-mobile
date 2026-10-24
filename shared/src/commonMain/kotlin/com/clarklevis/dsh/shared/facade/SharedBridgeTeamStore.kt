package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.domain.BridgeSessionStore
import com.clarklevis.dsh.shared.domain.BridgeTokenMachine
import com.clarklevis.dsh.shared.domain.BridgeTokenOutcome
import com.clarklevis.dsh.shared.domain.OpenTargetResolution
import com.clarklevis.dsh.shared.domain.TeamAction
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.domain.TeamReducer
import com.clarklevis.dsh.shared.domain.TeamUiState
import com.clarklevis.dsh.shared.protocol.BridgeClientKnownCode
import com.clarklevis.dsh.shared.protocol.BridgeTeamError
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import com.clarklevis.dsh.shared.protocol.bridgeSetFlagsPayload
import com.clarklevis.dsh.shared.protocol.bridgeSetMainPayload
import com.clarklevis.dsh.shared.protocol.bridgeUpdateBotPayload
import com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode
import com.clarklevis.dsh.shared.protocol.decodeBridgeErrorEnvelope
import com.clarklevis.dsh.shared.protocol.parseBridgeConversations
import com.clarklevis.dsh.shared.protocol.parseBridgeDeviceRevoked
import com.clarklevis.dsh.shared.protocol.parseBridgeDevicesPayload
import com.clarklevis.dsh.shared.protocol.parseBridgeEffortsPayload
import com.clarklevis.dsh.shared.protocol.parseBridgeInfo
import com.clarklevis.dsh.shared.protocol.parseBridgeModelsPayload
import com.clarklevis.dsh.shared.protocol.parseBridgeState
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 团队页（Mobile Bridge）唯一业务状态来源；HTTP/WS 执行留在平台层。
 *
 * P1：三响应合流刷新、点 Bot 进聊天。
 * P2：写操作四件套（set-flags / update-bot / set-main / 设备管理）——
 * 立即乐观更新（1s 内 UI 反映），平台执行 bridge-http effect 后经
 * [acceptBridgeResponse] 回执；TARGET_CHANGED/READ_ONLY 自动追加 refresh-team 纠偏。
 * 全部 Intent 非抛出，失败走 [SharedTeamResult.errorCode]（fail-closed）。
 */
@Serializable
data class SharedTeamEffect(
    val kind: String,
    /** kind = open-conversation：进入聊天要用的会话 id（现有 gateway 链路）。 */
    val sessionId: String? = null,
    /** kind = session-clear：平台同步清掉内存镜像里的会话。 */
    val reason: String? = null,
    val effectId: String? = null,
    /** kind = bridge-http：method/path 相对 origin（平台拼 /mobile/v1）。 */
    val method: String? = null,
    val path: String? = null,
    val bodyJson: String? = null,
    val requiresAuth: Boolean = true
) {
    companion object {
        const val KIND_OPEN_CONVERSATION = "open-conversation"
        const val KIND_SESSION_CLEAR = "session-clear"
        const val KIND_REFRESH_TEAM = "refresh-team"
        const val KIND_BRIDGE_HTTP = "bridge-http"
    }
}

data class SharedTeamResult(
    val snapshotJson: String?,
    val effectJson: String?,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isSuccess: Boolean get() = errorCode == null
}

class SharedBridgeTeamStore(
    private val sessionStore: BridgeSessionStore? = null
) {
    private var state = TeamUiState()
    private val tokenMachine = BridgeTokenMachine(sessionStore)
    private val events = SharedMviEventEmitter("team")
    private var effectCounter = 0L

    /** effectId → 待回执的动作（仅平台执行中的 effect 存在这里，不进 state）。 */
    private val pendingEffects = linkedMapOf<String, PendingEffect>()

    private class PendingEffect(
        val action: String,
        val targetId: String,
        val ref: String? = null
    )

    private class Transition(
        val next: TeamUiState,
        val effects: List<SharedTeamEffect> = emptyList(),
        val errorCode: String? = null,
        val errorMessage: String? = null
    )

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(state))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "team-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun snapshot(): SharedTeamResult = try {
        SharedTeamResult(wireJson.encodeToString(state), null, null, null)
    } catch (error: Throwable) {
        failure("team-snapshot-failed", error)
    }

    /** 平台启动时恢复持久化会话；恢复成功即进入 LOADING 等待首个刷新。 */
    fun restoreSession(): SharedTeamResult = mutate("restore-session") {
        when (val outcome = tokenMachine.restore()) {
            is BridgeTokenOutcome.Restored ->
                Transition(TeamReducer.reduce(state, TeamAction.SessionPaired(outcome.session)))
            is BridgeTokenOutcome.NeedsPairing ->
                Transition(TeamReducer.reduce(state, TeamAction.SessionLost(outcome.error)))
            else -> Transition(state)
        }
    }

    /** 配对完成：绑定并落库会话，进入 LOADING；平台随后开始 8s 前台轮询。 */
    fun sessionPaired(sessionJson: String): SharedTeamResult = mutate("session-paired") {
        when (val outcome = tokenMachine.bindFromPairing(sessionJson)) {
            is BridgeTokenOutcome.NeedsPairing ->
                Transition(TeamReducer.reduce(state, TeamAction.SessionLost(outcome.error)))
            is BridgeTokenOutcome.Refreshed ->
                Transition(TeamReducer.reduce(state, TeamAction.SessionPaired(outcome.session)))
            else -> Transition(state)
        }
    }

    /** 会话失效（server_changed / 配对撤销 / 手动登出）。 */
    fun sessionLost(reason: String?, message: String?): SharedTeamResult = mutate("session-lost") {
        tokenMachine.clear()
        val error = reason?.let { classifyBridgeErrorCode(it).copy(message = message) }
        Transition(
            TeamReducer.reduce(state, TeamAction.SessionLost(error)),
            effects = listOf(SharedTeamEffect(kind = SharedTeamEffect.KIND_SESSION_CLEAR, reason = reason))
        )
    }

    /**
     * 三响应合流（/info + /state + /conversations）。任一响应不可解码都按
     * invalid_response 走 reducer 的失败路径，保证 UI 呈现 Offline 而不是静默。
     */
    fun refreshTeam(infoJson: String, stateJson: String, conversationsJson: String): SharedTeamResult =
        mutate("refresh-team") {
            runCatching {
                Triple(
                    parseBridgeInfo(infoJson),
                    parseBridgeState(stateJson),
                    parseBridgeConversations(conversationsJson)
                )
            }.fold(
                onSuccess = { (info, bridgeState, conversations) ->
                    Transition(
                        TeamReducer.reduce(
                            state,
                            TeamAction.RefreshSucceeded(info, bridgeState, conversations)
                        )
                    )
                },
                onFailure = { error ->
                    Transition(
                        TeamReducer.reduce(state, TeamAction.RefreshFailed(invalidResponse(error.message)))
                    )
                }
            )
        }

    /** 平台传输失败（超时/断网/4xx/5xx）：code 原样分族后进 reducer。 */
    fun refreshFailed(code: String, message: String?): SharedTeamResult = mutate("refresh-failed") {
        val error = classifyBridgeErrorCode(code).copy(message = message)
        val next = TeamReducer.reduce(state, TeamAction.RefreshFailed(error))
        if (next.phase == TeamPhase.NEEDS_PAIRING) {
            tokenMachine.clear()
            Transition(
                next,
                effects = listOf(SharedTeamEffect(kind = SharedTeamEffect.KIND_SESSION_CLEAR, reason = code))
            )
        } else {
            Transition(next)
        }
    }

    /** 点 Bot/群：目标可见且有 sessionId 才发 open-conversation effect。 */
    fun openTeamConversation(conversationId: String): SharedTeamResult = mutate("open-team-conversation") {
        when (val resolution = TeamReducer.resolveOpenTarget(state, conversationId)) {
            is OpenTargetResolution.Ready -> Transition(
                state,
                effects = listOf(
                    SharedTeamEffect(
                        kind = SharedTeamEffect.KIND_OPEN_CONVERSATION,
                        sessionId = resolution.sessionId,
                        effectId = conversationId
                    )
                )
            )
            is OpenTargetResolution.Denied -> Transition(
                TeamReducer.reduce(state, TeamAction.OpenRejected(resolution.error))
            )
        }
    }

    // ---- P2：写操作四件套（乐观更新 + bridge-http effect）----

    /** `set-flags {id, pinned?, hidden?}`；能力/只读门控 fail-closed。 */
    fun setFlags(id: String, pinned: Boolean?, hidden: Boolean?): SharedTeamResult =
        writeIntent("set-flags", TeamReducer.ACTION_SET_FLAGS) {
            val effectId = nextEffectId()
            val payload = bridgeSetFlagsPayload(id, pinned, hidden)
            pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_SET_FLAGS, id)
            Transition(
                TeamReducer.reduce(state, TeamAction.SetFlagsRequested(id, pinned, hidden, effectId)),
                effects = listOf(actionEffect(effectId, TeamReducer.ACTION_SET_FLAGS, payload))
            )
        }

    /** `update-bot {id, model?, effort?}`；`model: null` = 使用默认模型。 */
    fun updateBot(
        id: String,
        includeModel: Boolean,
        model: String?,
        includeEffort: Boolean,
        effort: String?
    ): SharedTeamResult = writeIntent("update-bot", TeamReducer.ACTION_UPDATE_BOT) {
        val effectId = nextEffectId()
        val payload = bridgeUpdateBotPayload(id, includeModel, model, includeEffort, effort)
        pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_UPDATE_BOT, id)
        Transition(
            TeamReducer.reduce(
                state,
                TeamAction.UpdateBotRequested(id, includeModel, model, includeEffort, effort, effectId)
            ),
            effects = listOf(actionEffect(effectId, TeamReducer.ACTION_UPDATE_BOT, payload))
        )
    }

    /** `set-main {id, main:true, primary:true}`：把目标设为唯一主 Bot。 */
    fun setMain(id: String): SharedTeamResult = writeIntent("set-main", TeamReducer.ACTION_SET_MAIN) {
        val effectId = nextEffectId()
        val payload = bridgeSetMainPayload(id, main = true, primary = true)
        pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_SET_MAIN, id)
        Transition(
            TeamReducer.reduce(state, TeamAction.SetMainRequested(id, effectId)),
            effects = listOf(actionEffect(effectId, TeamReducer.ACTION_SET_MAIN, payload))
        )
    }

    /** `models` 动作：模型选择面板数据。 */
    fun loadModels(): SharedTeamResult = mutate("load-models") {
        denyFor(TeamReducer.ACTION_MODELS)?.let { return@mutate denied(it) }
        val effectId = nextEffectId()
        pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_MODELS, "")
        Transition(
            state,
            effects = listOf(
                actionEffect(effectId, TeamReducer.ACTION_MODELS, JsonObject(emptyMap()))
            )
        )
    }

    /** `efforts {ref}` 动作：按模型取档位。 */
    fun loadEfforts(ref: String): SharedTeamResult = mutate("load-efforts") {
        denyFor(TeamReducer.ACTION_EFFORTS)?.let { return@mutate denied(it) }
        val effectId = nextEffectId()
        pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_EFFORTS, "", ref)
        val payload = buildJsonObject { put("ref", ref) }
        Transition(
            state,
            effects = listOf(actionEffect(effectId, TeamReducer.ACTION_EFFORTS, payload))
        )
    }

    /** `GET /devices`：设备列表（含 currentDeviceId）。 */
    fun loadDevices(): SharedTeamResult = mutate("load-devices") {
        if (state.phase != TeamPhase.READY) {
            return@mutate denied(
                BridgeTeamError(
                    source = BridgeTeamError.SOURCE_CLIENT,
                    code = BridgeClientKnownCode.OPEN_UNAVAILABLE.wireValue,
                    knownClientCode = BridgeClientKnownCode.OPEN_UNAVAILABLE.name,
                    message = "团队尚未就绪"
                )
            )
        }
        val effectId = nextEffectId()
        pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_DEVICES, "")
        Transition(
            TeamReducer.reduce(state, TeamAction.DevicesRequested(effectId)),
            effects = listOf(
                SharedTeamEffect(
                    kind = SharedTeamEffect.KIND_BRIDGE_HTTP,
                    method = "GET",
                    path = "/devices",
                    effectId = effectId
                )
            )
        )
    }

    /** `DELETE /devices/{id}`：撤销设备。 */
    fun revokeDevice(id: String): SharedTeamResult = mutate("revoke-device") {
        if (state.phase != TeamPhase.READY || state.readOnlyReason != null) {
            return@mutate denied(readOnlyOrNotReady())
        }
        val effectId = nextEffectId()
        pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_DEVICE_REVOKE, id)
        Transition(
            TeamReducer.reduce(state, TeamAction.DeviceRevokeRequested(id, effectId)),
            effects = listOf(
                SharedTeamEffect(
                    kind = SharedTeamEffect.KIND_BRIDGE_HTTP,
                    method = "DELETE",
                    path = "/devices/$id",
                    effectId = effectId
                )
            )
        )
    }

    /**
     * 平台执行 bridge-http effect 后的回执入口。
     * 成功按动作种类解析响应；失败走错误信封 → WriteFailed + 自动纠偏 effect。
     */
    fun acceptBridgeResponse(effectId: String, status: Int, bodyJson: String): SharedTeamResult =
        mutate("accept-response") {
            val pending = pendingEffects.remove(effectId) ?: return@mutate Transition(state)
            if (status !in 200..299) {
                val error = decodeBridgeErrorEnvelope(status, bodyJson)
                transitionForFailure(effectId, pending, error)
            } else {
                when (pending.action) {
                    TeamReducer.ACTION_SET_FLAGS -> Transition(
                        TeamReducer.reduce(state, TeamAction.WriteApplied(effectId))
                    )
                    TeamReducer.ACTION_UPDATE_BOT -> Transition(
                        TeamReducer.reduce(state, TeamAction.WriteApplied(effectId))
                    )
                    TeamReducer.ACTION_SET_MAIN -> Transition(
                        TeamReducer.reduce(state, TeamAction.WriteApplied(effectId))
                    )
                    TeamReducer.ACTION_MODELS -> Transition(
                        TeamReducer.reduce(
                            state,
                            TeamAction.ModelsLoaded(parseBridgeModelsPayload(bodyJson))
                        )
                    )
                    TeamReducer.ACTION_EFFORTS -> Transition(
                        TeamReducer.reduce(
                            state,
                            TeamAction.EffortsLoaded(
                                pending.ref.orEmpty(),
                                parseBridgeEffortsPayload(bodyJson)
                            )
                        )
                    )
                    TeamReducer.ACTION_DEVICES -> Transition(
                        TeamReducer.reduce(state, TeamAction.DevicesLoaded(parseBridgeDevicesPayload(bodyJson)))
                    )
                    TeamReducer.ACTION_DEVICE_REVOKE -> Transition(
                        TeamReducer.reduce(
                            state,
                            TeamAction.DeviceRevokeApplied(
                                pending.targetId,
                                parseBridgeDeviceRevoked(bodyJson)
                            )
                        )
                    )
                    else -> Transition(state)
                }
            }
        }

    /** 平台传输层失败（超时/断网/解析失败）：按 client 码走同一条纠偏路径。 */
    fun acceptBridgeTransportFailure(effectId: String, code: String): SharedTeamResult =
        mutate("accept-transport-failure") {
            val pending = pendingEffects.remove(effectId) ?: return@mutate Transition(state)
            transitionForFailure(effectId, pending, classifyBridgeErrorCode(code))
        }

    private fun transitionForFailure(
        effectId: String,
        pending: PendingEffect,
        error: BridgeTeamError
    ): Transition {
        val next = when (pending.action) {
            TeamReducer.ACTION_DEVICES ->
                TeamReducer.reduce(state, TeamAction.DevicesFailed(error))
            TeamReducer.ACTION_DEVICE_REVOKE ->
                TeamReducer.reduce(state, TeamAction.DeviceRevokeFailed(pending.targetId, error))
            else -> TeamReducer.reduce(state, TeamAction.WriteFailed(effectId, error))
        }
        return Transition(next, effects = recoveryEffects(error, pending))
    }

    /** 平台刷新 token 成功后喂回；身份变化会把团队页打回配对。 */
    fun tokenRefreshSucceeded(sessionJson: String): SharedTeamResult = mutate("token-refresh-succeeded") {
        when (val outcome = tokenMachine.refreshSucceeded(sessionJson)) {
            is BridgeTokenOutcome.NeedsPairing -> Transition(
                TeamReducer.reduce(state, TeamAction.SessionLost(outcome.error)),
                effects = listOf(
                    SharedTeamEffect(
                        kind = SharedTeamEffect.KIND_SESSION_CLEAR,
                        reason = outcome.error.code
                    )
                )
            )
            else -> Transition(state)
        }
    }

    /** 平台刷新 token 被拒；permanent 失效（unauthorized/invalid_session）打回配对。 */
    fun tokenRefreshRejected(code: String): SharedTeamResult = mutate("token-refresh-rejected") {
        when (val outcome = tokenMachine.refreshRejected(code)) {
            is BridgeTokenOutcome.NeedsPairing -> Transition(
                TeamReducer.reduce(state, TeamAction.SessionLost(outcome.error)),
                effects = listOf(SharedTeamEffect(kind = SharedTeamEffect.KIND_SESSION_CLEAR, reason = code))
            )
            else -> Transition(state)
        }
    }

    /** 平台执行 /auth/refresh 前的单飞问询；false = 本次不要发请求。 */
    fun beginTokenRefresh(): Boolean = tokenMachine.beginRefresh()

    fun currentAccessToken(): String? = tokenMachine.accessToken()

    fun currentServerId(): String? = state.serverId

    // ---- 内部 ----

    private fun nextEffectId(): String = "team-effect:${++effectCounter}"

    private fun actionEffect(effectId: String, endpoint: String, payload: JsonObject): SharedTeamEffect {
        val body = buildJsonObject {
            put("endpoint", endpoint)
            put("payload", payload)
        }
        return SharedTeamEffect(
            kind = SharedTeamEffect.KIND_BRIDGE_HTTP,
            method = "POST",
            path = "/actions",
            bodyJson = wireJson.encodeToString(body),
            effectId = effectId
        )
    }

    /** 写操作门控：能力白名单（fail-closed）→ 只读 → 就绪态。 */
    private fun denyFor(action: String): BridgeTeamError? = when {
        !state.capabilities.supportsAction(action) -> BridgeTeamError(
            source = BridgeTeamError.SOURCE_WIRE,
            code = BridgeWireKnownCode.ACTION_NOT_ALLOWED.wireValue,
            knownWireCode = BridgeWireKnownCode.ACTION_NOT_ALLOWED.name,
            message = "服务器未开放该操作"
        )
        state.readOnlyReason != null -> BridgeTeamError(
            source = BridgeTeamError.SOURCE_WIRE,
            code = BridgeWireKnownCode.READ_ONLY.wireValue,
            knownWireCode = BridgeWireKnownCode.READ_ONLY.name,
            message = state.readOnlyReason
        )
        state.phase != TeamPhase.READY -> BridgeTeamError(
            source = BridgeTeamError.SOURCE_CLIENT,
            code = BridgeClientKnownCode.OPEN_UNAVAILABLE.wireValue,
            knownClientCode = BridgeClientKnownCode.OPEN_UNAVAILABLE.name,
            message = "团队尚未就绪"
        )
        else -> null
    }

    private fun readOnlyOrNotReady(): BridgeTeamError = when {
        state.readOnlyReason != null -> BridgeTeamError(
            source = BridgeTeamError.SOURCE_WIRE,
            code = BridgeWireKnownCode.READ_ONLY.wireValue,
            knownWireCode = BridgeWireKnownCode.READ_ONLY.name,
            message = state.readOnlyReason
        )
        else -> BridgeTeamError(
            source = BridgeTeamError.SOURCE_CLIENT,
            code = BridgeClientKnownCode.OPEN_UNAVAILABLE.wireValue,
            knownClientCode = BridgeClientKnownCode.OPEN_UNAVAILABLE.name,
            message = "团队尚未就绪"
        )
    }

    private fun denied(error: BridgeTeamError): Transition =
        Transition(
            state,
            effects = emptyList(),
            errorCode = error.code,
            errorMessage = error.message
        )

    /** 失败后的自动纠偏：TARGET_CHANGED/READ_ONLY → 全量刷新；设备类 → 重拉设备列表。 */
    private fun recoveryEffects(
        error: BridgeTeamError,
        pending: PendingEffect
    ): List<SharedTeamEffect> {
        val code = error.knownWireCode
        return when {
            code == BridgeWireKnownCode.TARGET_CHANGED.name ||
                code == BridgeWireKnownCode.READ_ONLY.name ->
                listOf(SharedTeamEffect(kind = SharedTeamEffect.KIND_REFRESH_TEAM))
            pending.action == TeamReducer.ACTION_DEVICES ||
                pending.action == TeamReducer.ACTION_DEVICE_REVOKE -> {
                val effectId = nextEffectId()
                pendingEffects[effectId] = PendingEffect(TeamReducer.ACTION_DEVICES, "")
                listOf(
                    SharedTeamEffect(
                        kind = SharedTeamEffect.KIND_BRIDGE_HTTP,
                        method = "GET",
                        path = "/devices",
                        effectId = effectId
                    )
                )
            }
            else -> emptyList()
        }
    }

    private inline fun writeIntent(
        operation: String,
        action: String,
        transform: () -> Transition
    ): SharedTeamResult = mutate(operation) {
        denyFor(action)?.let { return@mutate denied(it) }
        transform()
    }

    private inline fun mutate(operation: String, transform: () -> Transition): SharedTeamResult = try {
        val transition = transform()
        if (transition.errorCode != null) {
            failureResult(operation, transition.errorCode, transition.errorMessage)
        } else {
            success(transition.next, transition.effects)
        }
    } catch (error: Throwable) {
        failure("team-$operation-failed", error)
    }

    private fun success(next: TeamUiState, effects: List<SharedTeamEffect>): SharedTeamResult {
        val changed = next != state
        val snapshotJson = if (changed) wireJson.encodeToString(next) else null
        val effectJson = effects.takeIf { it.isNotEmpty() }?.let { wireJson.encodeToString(it) }
        if (snapshotJson != null || effectJson != null) {
            state = next
            events.emitTransition(
                transactionId = "team:${events.currentSequence + 1}",
                statePayloadJson = snapshotJson,
                effectsJson = effectJson ?: "[]"
            )
        }
        return SharedTeamResult(snapshotJson, effectJson, null, null)
    }

    private fun failure(operation: String, error: Throwable): SharedTeamResult {
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        events.emitError("$operation:${events.currentSequence + 1}", operation, message)
        return SharedTeamResult(null, null, operation, message)
    }

    private fun failureResult(operation: String, code: String, message: String?): SharedTeamResult {
        events.emitError("$operation:${events.currentSequence + 1}", code, message)
        return SharedTeamResult(null, null, code, message)
    }

    private fun invalidResponse(message: String?): BridgeTeamError = BridgeTeamError(
        source = BridgeTeamError.SOURCE_CLIENT,
        code = BridgeClientKnownCode.INVALID_RESPONSE.wireValue,
        knownClientCode = BridgeClientKnownCode.INVALID_RESPONSE.name,
        message = message
    )
}

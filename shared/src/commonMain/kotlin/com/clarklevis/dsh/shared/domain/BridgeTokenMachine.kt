package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.BridgeClientKnownCode
import com.clarklevis.dsh.shared.protocol.BridgeDeviceSession
import com.clarklevis.dsh.shared.protocol.BridgeTeamError
import com.clarklevis.dsh.shared.protocol.BridgeWireKnownCode
import com.clarklevis.dsh.shared.protocol.classifyBridgeErrorCode
import com.clarklevis.dsh.shared.protocol.parseBridgeSession

/**
 * 设备会话/token 刷新状态机（§2.1 认证约定 + §2.8 配对绑定，纯逻辑）。
 *
 * - 刷新单飞（single-flight）：已在刷新期间再次请求直接拒绝，保证平台 I/O 至多一次；
 * - 绑定校验：serverId/deviceId 变化即 server_changed，强制清空重新配对（index.js:222-224、243）；
 * - Keychain 存取通过 [BridgeSessionStore] 由平台实现，KMP 只发 JSON。
 */
interface BridgeSessionStore {
    fun loadJson(): String?
    fun saveJson(json: String)
    fun clear()
}

sealed interface BridgeTokenOutcome {
    /** 已存在的会话恢复成功。 */
    data class Restored(val session: BridgeDeviceSession) : BridgeTokenOutcome

    /** 刷新请求开始（本机状态机翻转，平台由此发起一次 /auth/refresh）。 */
    data object RefreshStarted : BridgeTokenOutcome

    data class Refreshed(val session: BridgeDeviceSession) : BridgeTokenOutcome

    /** 刷新被拒但会话保留（网络类失败，稍后重试）。 */
    data class RefreshRejected(val error: BridgeTeamError) : BridgeTokenOutcome

    /** 会话失效，必须重新配对（状态已清空）。 */
    data class NeedsPairing(val error: BridgeTeamError) : BridgeTokenOutcome
}

class BridgeTokenMachine(private val sessionStore: BridgeSessionStore? = null) {

    private var session: BridgeDeviceSession? = null
    private var refreshing = false

    fun current(): BridgeDeviceSession? = session

    fun accessToken(): String? = session?.accessToken

    val isRefreshing: Boolean get() = refreshing

    /** 启动时恢复持久化会话；JSON 损坏一律清空（fail-closed）。 */
    fun restore(): BridgeTokenOutcome {
        val json = sessionStore?.loadJson().orEmpty()
        if (json.isBlank()) return BridgeTokenOutcome.NeedsPairing(clientError(BridgeClientKnownCode.UNAUTHORIZED, "尚未配对"))
        val restored = runCatching { parseBridgeSession(json) }.getOrNull()
        if (restored == null || restored.accessToken.isBlank()) {
            sessionStore?.clear()
            session = null
            return BridgeTokenOutcome.NeedsPairing(clientError(BridgeClientKnownCode.INVALID_SESSION, "存储的会话无效"))
        }
        session = restored
        return BridgeTokenOutcome.Restored(restored)
    }

    /** 配对完成后落库并绑定 serverUrl(+tlsPin)；配对 JSON 的 serverUrl/pin 由平台先行校验。 */
    fun bindFromPairing(sessionJson: String): BridgeTokenOutcome {
        val bound = runCatching { parseBridgeSession(sessionJson) }.getOrNull()
            ?: return BridgeTokenOutcome.NeedsPairing(clientError(BridgeClientKnownCode.INVALID_SESSION, "配对返回的会话无效"))
        session = bound
        refreshing = false
        sessionStore?.saveJson(sessionJson)
        return BridgeTokenOutcome.Refreshed(bound)
    }

    /** 单飞入口：返回 false 表示本次不发起刷新（已在刷或无会话）。 */
    fun beginRefresh(): Boolean {
        if (refreshing) return false
        if (session == null) return false
        refreshing = true
        return true
    }

    fun refreshSucceeded(sessionJson: String): BridgeTokenOutcome {
        refreshing = false
        val next = runCatching { parseBridgeSession(sessionJson) }.getOrNull()
            ?: return failToPairing(BridgeClientKnownCode.INVALID_SESSION, "刷新返回的会话无效")
        val previous = session
        // 身份绑定校验：serverId/deviceId 变化即 server_changed（index.js:243）。
        if (previous != null &&
            (next.serverId != previous.serverId || next.deviceId != previous.deviceId)
        ) {
            return failToPairing(BridgeClientKnownCode.SERVER_CHANGED, "服务器身份已变化，请重新配对")
        }
        session = next
        sessionStore?.saveJson(sessionJson)
        return BridgeTokenOutcome.Refreshed(next)
    }

    fun refreshRejected(code: String): BridgeTokenOutcome {
        refreshing = false
        val error = classifyBridgeErrorCode(code)
        val permanent = when {
            error.isWire -> error.knownWireCode == BridgeWireKnownCode.UNAUTHORIZED.name
            else -> error.knownClientCode == BridgeClientKnownCode.UNAUTHORIZED.name ||
                error.knownClientCode == BridgeClientKnownCode.INVALID_SESSION.name
        }
        return if (permanent) {
            clear()
            BridgeTokenOutcome.NeedsPairing(error)
        } else {
            BridgeTokenOutcome.RefreshRejected(error)
        }
    }

    fun clear(error: BridgeTeamError? = null): BridgeTokenOutcome {
        session = null
        refreshing = false
        sessionStore?.clear()
        return BridgeTokenOutcome.NeedsPairing(error ?: clientError(BridgeClientKnownCode.UNAUTHORIZED, "会话已清除"))
    }

    private fun failToPairing(code: BridgeClientKnownCode, message: String): BridgeTokenOutcome {
        clear()
        return BridgeTokenOutcome.NeedsPairing(clientError(code, message))
    }

    private fun clientError(code: BridgeClientKnownCode, message: String) = BridgeTeamError(
        source = BridgeTeamError.SOURCE_CLIENT,
        code = code.wireValue,
        knownClientCode = code.name,
        message = message
    )
}

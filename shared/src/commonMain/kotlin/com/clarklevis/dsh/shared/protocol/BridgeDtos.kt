package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mobile Bridge（/mobile/v1）协议 DTO，逐字段对照 Docs/bot-team-port-design.md §2
 * （来源：bridge/src/adapter.mjs、server.js 与 packages/mobile-sdk）。
 *
 * 与 spike（Spikes/mobile-bridge-protocol）唯一的差别是命名加 Bridge 前缀，
 * 避免与 Gateway 协议类型混淆；wire 字段名必须与 spike 完全一致，不得漂移。
 * 解码统一走 wireJson（ignoreUnknownKeys + explicitNulls=false）：
 * 新版本宿主新增字段必须被忽略而不是解码失败（前向兼容红线）。
 */

@Serializable
data class BridgeServerInfo(
    val protocolVersion: Int,
    val serverId: String,
    val name: String = "DS Bot",
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val compatibility: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class BridgePairing(
    val claimToken: String,
    val requestId: String,
    val status: String
)

/** 设备会话；expiresAt 宿主可能发 epoch 毫秒或 ISO 字符串，两种都接受。 */
@Serializable
data class BridgeDeviceSession(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val serverId: String,
    val serverUrl: String = "",
    val tlsPin: String? = null,
    val expiresAt: JsonPrimitive? = null
) {
    val expiresAtIsString: Boolean get() = expiresAt?.isString == true

    /** 绑定三元组变更即 server_changed，强制重新配对（SDK index.js:222-224 语义）。 */
    fun bindingKey(): String = listOf(serverId, serverUrl, tlsPin ?: "").joinToString("|")
}

@Serializable
data class BridgeConversationRow(
    val id: String,
    val kind: String,
    val name: String,
    val sessionId: String,
    val role: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val waiting: Boolean? = null,
    val pinned: Boolean? = null,
    val hidden: Boolean? = null,
    val parts: List<String> = emptyList(),
    val members: List<String> = emptyList(),
    val admin: String? = null,
    val mode: String? = null,
    val avatar: JsonObject? = null,
    /** U4：普通会话行的最近活跃时间（ms），团队行不带。 */
    val lastActiveAt: Long? = null,
    /** U4：工作区名（服务端只下发末段，不下发完整路径）。 */
    val workspace: String? = null,
    /** U4：会话正在运行。 */
    val busy: Boolean? = null
) {
    val isRoom: Boolean get() = kind == "room"
    val isSession: Boolean get() = kind == "session"
}

@Serializable
data class BridgeModelRow(
    val ref: String,
    val name: String,
    val providerName: String? = null
)

@Serializable
data class BridgeWorkStatus(
    val tool: String? = null,
    val said: String? = null,
    val note: String? = null,
    val target: String? = null,
    val switching: Boolean? = null,
    val since: Long? = null,
    val roomId: String? = null
)

@Serializable
data class BridgeMemberWork(
    val botId: String,
    val speaking: Boolean? = null,
    val tool: String? = null,
    val said: String? = null,
    val note: String? = null
)

@Serializable
data class BridgeApproval(
    val id: String,
    val botId: String,
    val roomId: String? = null,
    val sessionId: String? = null,
    val answerable: Boolean? = null
)

@Serializable
data class BridgeTeamWork(
    val bots: Map<String, BridgeWorkStatus> = emptyMap(),
    val rooms: Map<String, List<BridgeMemberWork>> = emptyMap(),
    val approvals: List<BridgeApproval> = emptyList(),
    val waiting: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)

@Serializable
data class BridgeTeamState(
    val revision: JsonPrimitive? = null,
    val readOnly: String? = null,
    val bots: List<BridgeConversationRow> = emptyList(),
    val rooms: List<BridgeConversationRow> = emptyList(),
    /** U4：非团队的普通会话（kind:"session"）。 */
    val sessions: List<BridgeConversationRow> = emptyList(),
    val models: List<BridgeModelRow> = emptyList(),
    val defaultModel: String? = null,
    val mainBotIds: List<String> = emptyList(),
    val mainBotId: String? = null,
    val questions: JsonObject = JsonObject(emptyMap()),
    val activity: JsonObject = JsonObject(emptyMap()),
    val work: BridgeTeamWork = BridgeTeamWork(),
    val memory: Boolean = false,
    val browser: Boolean = false
) {
    /** revision 单调门控的取值；宿主发非数字时视为未知（null，不推进游标）。 */
    val revisionValue: Long?
        get() = revision?.content?.toLongOrNull()
}

@Serializable
data class BridgeApiError(val code: String, val message: String)

@Serializable
data class BridgeToolActivity(
    val callId: String,
    val name: String,
    val state: String,
    val durationMs: Long? = null
)

@Serializable
data class BridgeStreamMessage(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val role: String,
    val text: String,
    val createdAt: JsonPrimitive? = null,
    val reasoning: String? = null,
    val tool: BridgeToolActivity? = null,
    val attachments: List<JsonObject> = emptyList(),
    val systemEvent: JsonObject? = null
)

@Serializable
data class BridgeQueueItem(
    val id: String,
    val text: String,
    val target: String,
    val requestId: String? = null
)

/** `type` 是封闭集合；未知 type 丢弃不渲染（SDK 语义）。 */
@Serializable
data class BridgeStreamEvent(
    val type: String,
    val conversationId: String? = null,
    val sessionId: String? = null,
    val attemptId: String? = null,
    val replaceAttemptId: String? = null,
    val text: String? = null,
    val reasoning: String? = null,
    val index: Int? = null,
    val replace: Boolean? = null,
    val clearAttempt: Boolean? = null,
    val message: BridgeStreamMessage? = null,
    val messages: List<BridgeStreamMessage> = emptyList(),
    val cursor: String? = null,
    val eventSeq: Long? = null,
    val busy: Boolean? = null,
    val phase: String? = null,
    val queue: List<BridgeQueueItem> = emptyList(),
    val error: BridgeApiError? = null,
    val serverId: String? = null
) {
    companion object {
        val KNOWN_TYPES = setOf("ready", "delta", "message", "reset", "status", "error")
    }

    val isKnownType: Boolean get() = type in KNOWN_TYPES
}

@Serializable
data class BridgeHistoryPage(
    val messages: List<BridgeStreamMessage> = emptyList(),
    val cursor: String? = null,
    val sessionId: String
)

@Serializable
data class BridgeConversationsPage(val conversations: List<BridgeConversationRow> = emptyList())

@Serializable
data class BridgeActionEnvelope(
    val endpoint: String,
    val payload: JsonObject = JsonObject(emptyMap())
)

/** WS 认证首帧：type 恒为 "auth"，可选 after 必须缺省而不是 null（编码细节被测试钉死）。 */
@Serializable
data class BridgeWsAuthFrame(
    val type: String = "auth",
    val token: String,
    val conversationId: String,
    val after: Long? = null
)

fun parseBridgeInfo(raw: String): BridgeServerInfo = wireJson.decodeFromString(raw)

fun parseBridgeState(raw: String): BridgeTeamState = wireJson.decodeFromString(raw)

fun parseBridgeConversations(raw: String): BridgeConversationsPage = wireJson.decodeFromString(raw)

fun parseBridgeEvent(raw: String): BridgeStreamEvent = wireJson.decodeFromString(raw)

fun parseBridgeHistory(raw: String): BridgeHistoryPage = wireJson.decodeFromString(raw)

fun parseBridgePairing(raw: String): BridgePairing = wireJson.decodeFromString(raw)

fun parseBridgeSession(raw: String): BridgeDeviceSession = wireJson.decodeFromString(raw)

fun encodeBridgeAction(endpoint: String, payload: JsonObject): String =
    wireJson.encodeToString(BridgeActionEnvelope.serializer(), BridgeActionEnvelope(endpoint, payload))

fun encodeBridgeAuthFrame(token: String, conversationId: String, after: Long?): String =
    wireJson.encodeToString(
        BridgeWsAuthFrame.serializer(),
        BridgeWsAuthFrame(token = token, conversationId = conversationId, after = after)
    )

/** 镜像 packages/mobile-sdk validateTlsPin：^sha256:[a-f0-9]{64}$。 */
private val BRIDGE_PIN_REGEX = Regex("^sha256:[a-f0-9]{64}$")

fun validateBridgeTlsPin(value: String?): String? =
    if (value != null && BRIDGE_PIN_REGEX.matches(value)) value else null

/** 镜像 packages/mobile-sdk validateServerUrl：HTTPS 强制，loopback HTTP 需显式 allowInsecure。 */
fun validateBridgeServerOrigin(value: String, allowInsecure: Boolean = false): String? {
    val regex = Regex("^(https?)://([A-Za-z0-9.\\-\\[\\]:]+)(/|/mobile/v1/?)?$")
    val match = regex.find(value) ?: return null
    val scheme = match.groupValues[1]
    if (scheme == "http" && !allowInsecure) return null
    return "${scheme}://${match.groupValues[2]}"
}

/** /info.capabilities 中布尔 feature 的安全读取：缺失/非布尔一律 false（fail-closed）。 */
fun BridgeServerInfo.booleanFeature(name: String): Boolean =
    (capabilities[name] as? JsonPrimitive)?.booleanOrNull == true

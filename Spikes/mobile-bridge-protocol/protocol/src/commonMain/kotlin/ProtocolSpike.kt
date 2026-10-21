package spike.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Spike: parse the ds-bot-mobile-app `/mobile/v1` payloads with kotlinx.serialization
 * using the project's existing `ignoreUnknownKeys` policy (see protocol/JsonValue.kt).
 *
 * Goal is not a full client: it proves that none of the observed shapes need
 * runtime reflection, custom polymorphic serializers, or platform-only APIs,
 * i.e. the bridge DTO layer can live in commonMain.
 */

val spikeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = false
}

@Serializable
data class ServerInfo(
    val protocolVersion: Int,
    val serverId: String,
    val name: String = "DS Bot",
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val compatibility: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class Pairing(
    val claimToken: String,
    val requestId: String,
    val status: String
)

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val serverId: String,
    val expiresAt: JsonPrimitive
) {
    /** Host may send epoch millis or an ISO string; both keep the same meaning. */
    val expiresAtIsString: Boolean get() = expiresAt.isString
}

@Serializable
data class ConversationRow(
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
    val avatar: JsonObject? = null
)

@Serializable
data class ModelRow(
    val ref: String,
    val name: String,
    val providerName: String? = null
)

@Serializable
data class WorkStatus(
    val tool: String? = null,
    val said: String? = null,
    val note: String? = null,
    val target: String? = null,
    val switching: Boolean? = null,
    val since: Long? = null,
    val roomId: String? = null
)

@Serializable
data class MemberWork(
    val botId: String,
    val speaking: Boolean? = null,
    val tool: String? = null,
    val said: String? = null,
    val note: String? = null
)

@Serializable
data class Approval(
    val id: String,
    val botId: String,
    val roomId: String? = null,
    val sessionId: String? = null,
    val answerable: Boolean? = null
)

@Serializable
data class TeamWork(
    val bots: Map<String, WorkStatus> = emptyMap(),
    val rooms: Map<String, List<MemberWork>> = emptyMap(),
    val approvals: List<Approval> = emptyList(),
    val waiting: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class TeamState(
    val revision: JsonPrimitive? = null,
    val readOnly: String? = null,
    val bots: List<ConversationRow> = emptyList(),
    val rooms: List<ConversationRow> = emptyList(),
    val models: List<ModelRow> = emptyList(),
    val defaultModel: String? = null,
    val mainBotIds: List<String> = emptyList(),
    val mainBotId: String? = null,
    val questions: JsonObject = JsonObject(emptyMap()),
    val activity: JsonObject = JsonObject(emptyMap()),
    val work: TeamWork = TeamWork(),
    val memory: Boolean = false,
    val browser: Boolean = false
)

@Serializable
data class ActionCapabilities(
    val actions: List<String> = emptyList(),
    val modelSelection: Boolean = false,
    val queue: Boolean = false,
    val cancel: Boolean = false,
    val approvals: Boolean = false,
    val memoryAsk: Boolean = false,
    val scheduleUpdate: Boolean = false
)

@Serializable
data class ActionEnvelope(
    val endpoint: String,
    val payload: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class WsAuthFrame(
    val type: String = "auth",
    val token: String,
    val conversationId: String,
    val after: Long? = null
)

@Serializable
data class ToolActivity(
    val callId: String,
    val name: String,
    val state: String,
    val durationMs: Long? = null
)

@Serializable
data class StreamMessage(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val role: String,
    val text: String,
    val createdAt: JsonPrimitive? = null,
    val reasoning: String? = null,
    val tool: ToolActivity? = null,
    val attachments: List<JsonObject> = emptyList(),
    val systemEvent: JsonObject? = null
)

@Serializable
data class QueueItem(
    val id: String,
    val text: String,
    val target: String,
    val requestId: String? = null
)

@Serializable
data class ApiError(val code: String, val message: String)

/** `type` is a closed set in the RN client; unknown types are dropped, not rendered. */
@Serializable
data class StreamEvent(
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
    val message: StreamMessage? = null,
    val messages: List<StreamMessage> = emptyList(),
    val cursor: String? = null,
    val eventSeq: Long? = null,
    val busy: Boolean? = null,
    val phase: String? = null,
    val queue: List<QueueItem> = emptyList(),
    val error: ApiError? = null,
    val serverId: String? = null
) {
    companion object {
        val KNOWN = setOf("ready", "delta", "message", "reset", "status", "error")
    }

    val isKnown: Boolean get() = type in KNOWN
}

@Serializable
data class HistoryPage(
    val messages: List<StreamMessage> = emptyList(),
    val cursor: String? = null,
    val sessionId: String
)

@Serializable
data class ConversationsPage(val conversations: List<ConversationRow> = emptyList())

fun parseInfo(raw: String): ServerInfo = spikeJson.decodeFromString(raw)

fun parseState(raw: String): TeamState = spikeJson.decodeFromString(raw)

fun parseCapabilities(raw: String): ActionCapabilities =
    spikeJson.decodeFromJsonElement(ActionCapabilities.serializer(), parseInfo(raw).capabilities)

fun parseEvent(raw: String): StreamEvent = spikeJson.decodeFromString(raw)

fun parseHistory(raw: String): HistoryPage = spikeJson.decodeFromString(raw)

fun encodeAction(endpoint: String, payload: JsonObject): String =
    spikeJson.encodeToString(ActionEnvelope.serializer(), ActionEnvelope(endpoint, payload))

/** The auth frame must always carry `type:"auth"`; optional `after` is absent, never null. */
private val frameJson: Json = Json { encodeDefaults = true; explicitNulls = false }

fun encodeAuthFrame(token: String, conversationId: String, after: Long?): String =
    frameJson.encodeToString(
        WsAuthFrame.serializer(),
        WsAuthFrame(token = token, conversationId = conversationId, after = after)
    )

/** Mirrors packages/mobile-sdk validateTlsPin. */
private val PIN = Regex("^sha256:[a-f0-9]{64}$")

fun validateTlsPin(value: String?): String? =
    if (value != null && PIN.matches(value)) value else null

/** Mirrors packages/mobile-sdk validateServerUrl (HTTPS only; loopback HTTP opt-in). */
fun validateServerOrigin(value: String, allowInsecure: Boolean = false): String? {
    val m = Regex("^(https?)://([A-Za-z0-9.\\-\\[\\]:]+)(/|/mobile/v1/?)?$").find(value) ?: return null
    val scheme = m.groupValues[1]
    if (scheme == "http" && !allowInsecure) return null
    return "$scheme://${m.groupValues[2]}"
}

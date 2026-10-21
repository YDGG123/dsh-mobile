package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 错误码两族分表建模（Docs/bot-team-port-design.md §2.9，v1.2）：
 *
 * 族 1 wire：服务端 JSON 错误信封 error.code，大写，SDK 原样透传不归一（index.js:303）；
 *           信封缺 code 时兜底 request_failed（族 2）。
 * 族 2 client：SDK/客户端状态机自产，小写。
 *
 * 规则：raw 一律原样保留；未知 wire 码 fail-closed 进 UNRECOGNIZED 语义（known == null），
 * 由平台按「显示原文 + 通用文案」处理，绝不猜测语义。
 */

enum class BridgeWireKnownCode(val wireValue: String) {
    // 认证/会话/传输
    UNAUTHORIZED("UNAUTHORIZED"),
    TOKEN_IN_URL("TOKEN_IN_URL"),
    SESSION_DENIED("SESSION_DENIED"),
    DISPOSED("DISPOSED"),
    CONNECTION_UNAVAILABLE("CONNECTION_UNAVAILABLE"),
    RATE_LIMITED("RATE_LIMITED"),
    ORIGIN_DENIED("ORIGIN_DENIED"),
    CONNECTION_LIMIT("CONNECTION_LIMIT"),
    // 配对/设备
    INVALID_PAIRING_CODE("INVALID_PAIRING_CODE"),
    PAIRING_DENIED("PAIRING_DENIED"),
    PAIRING_NOT_FOUND("PAIRING_NOT_FOUND"),
    PAIRING_LIMIT("PAIRING_LIMIT"),
    INVALID_DEVICE_NAME("INVALID_DEVICE_NAME"),
    DEVICE_LIMIT("DEVICE_LIMIT"),
    DEVICE_NOT_FOUND("DEVICE_NOT_FOUND"),
    // 动作/写状态
    ACTION_NOT_ALLOWED("ACTION_NOT_ALLOWED"),
    INVALID_ACTION("INVALID_ACTION"),
    READ_ONLY("READ_ONLY"),
    TARGET_DENIED("TARGET_DENIED"),
    TARGET_CHANGED("TARGET_CHANGED"),
    QUEUE_CHANGED("QUEUE_CHANGED"),
    TASK_DENIED("TASK_DENIED"),
    APPROVAL_UNAVAILABLE("APPROVAL_UNAVAILABLE"),
    // 会话/消息/历史
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND"),
    SUBMISSION_NOT_FOUND("SUBMISSION_NOT_FOUND"),
    REQUEST_ID_CONFLICT("REQUEST_ID_CONFLICT"),
    INVALID_CURSOR("INVALID_CURSOR"),
    INVALID_LIMIT("INVALID_LIMIT"),
    INVALID_SEQUENCE("INVALID_SEQUENCE"),
    HISTORY_UNAVAILABLE("HISTORY_UNAVAILABLE"),
    INVALID_CONTENT("INVALID_CONTENT"),
    INVALID_ATTACHMENTS("INVALID_ATTACHMENTS"),
    UPLOAD_EXPIRED("UPLOAD_EXPIRED"),
    BODY_TOO_LARGE("BODY_TOO_LARGE"),
    VIEW_TOO_LARGE("VIEW_TOO_LARGE"),
    // 投影/redaction
    REDACTION_REQUIRED("REDACTION_REQUIRED"),
    REDACTED_PROJECTION_REQUIRED("REDACTED_PROJECTION_REQUIRED"),
    REDACTION_FAILED("REDACTION_FAILED"),
    REDACTION_UNAVAILABLE("REDACTION_UNAVAILABLE"),
    STABLE_SUBMIT_REQUIRED("STABLE_SUBMIT_REQUIRED"),
    // 团队投影
    TEAM_REFUSED("TEAM_REFUSED"),
    TEAM_UNAVAILABLE("TEAM_UNAVAILABLE"),
    // 请求/其他
    INVALID_JSON("INVALID_JSON"),
    INVALID_REQUEST("INVALID_REQUEST"),
    INVALID_PATH("INVALID_PATH"),
    NOT_FOUND("NOT_FOUND"),
    UNSUPPORTED("UNSUPPORTED"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    REQUEST_ABORTED("REQUEST_ABORTED"),
    REQUEST_FAILED("REQUEST_FAILED");

    companion object {
        private val byWireValue = entries.associateBy { it.wireValue }
        fun fromWireValue(value: String): BridgeWireKnownCode? = byWireValue[value]
    }
}

enum class BridgeClientKnownCode(val wireValue: String) {
    INVALID_URL("invalid_url"),
    INSECURE_URL("insecure_url"),
    INVALID_TLS_PIN("invalid_tls_pin"),
    MISSING_PINNED_TRANSPORT("missing_pinned_transport"),
    INVALID_RESPONSE("invalid_response"),
    UNAUTHORIZED("unauthorized"),
    SERVER_CHANGED("server_changed"),
    SESSION_CHANGED("session_changed"),
    INVALID_SESSION("invalid_session"),
    INVALID_RECEIPT("invalid_receipt"),
    INCOMPATIBLE_PROTOCOL("incompatible_protocol"),
    INVALID_SEQUENCE("invalid_sequence"),
    CANCELLED("cancelled"),
    DISPOSED("disposed"),
    TIMEOUT("timeout"),
    NETWORK_ERROR("network_error"),
    REQUEST_FAILED("request_failed"),
    /** 本端状态机自产：目标可见但无可用移动会话（§3.5 降级提示）。 */
    OPEN_UNAVAILABLE("open_unavailable");

    companion object {
        private val byWireValue = entries.associateBy { it.wireValue }
        fun fromWireValue(value: String): BridgeClientKnownCode? = byWireValue[value]
    }
}

/** TeamError：source 区分两族，code 原样保留（不归一大小写）。 */
@Serializable
data class BridgeTeamError(
    val source: String,
    val code: String,
    val knownWireCode: String? = null,
    val knownClientCode: String? = null,
    val message: String? = null
) {
    val isWire: Boolean get() = source == SOURCE_WIRE
    /** fail-closed：未知 wire 码（knownWireCode == null）显示原文 + 通用文案。 */
    val isUnrecognizedWire: Boolean get() = isWire && knownWireCode == null

    companion object {
        const val SOURCE_WIRE = "wire"
        const val SOURCE_CLIENT = "client"
    }
}

/** 按大小写分族：全大写（含下划线）= wire 族，其余 = client 族。 */
fun classifyBridgeErrorCode(raw: String): BridgeTeamError {
    val trimmed = raw.trim()
    val isWireFamily = trimmed.isNotEmpty() && trimmed == trimmed.uppercase()
    return if (isWireFamily) {
        BridgeTeamError(
            source = BridgeTeamError.SOURCE_WIRE,
            code = trimmed,
            knownWireCode = BridgeWireKnownCode.fromWireValue(trimmed)?.name
        )
    } else {
        BridgeTeamError(
            source = BridgeTeamError.SOURCE_CLIENT,
            code = trimmed,
            knownClientCode = BridgeClientKnownCode.fromWireValue(trimmed)?.name
        )
    }
}

private val bridgeErrorEnvelopeJson by lazy {
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

/** HTTP 4xx/5xx JSON 错误信封 {"error":{code,message}}；缺 code 兜底 request_failed（族 2）。 */
fun decodeBridgeErrorEnvelope(status: Int, body: String?): BridgeTeamError {
    val root: JsonObject? = runCatching {
        bridgeErrorEnvelopeJson.parseToJsonElement(body.orEmpty()).jsonObject
    }.getOrNull()
    val envelope = root?.get("error")?.let {
        runCatching { bridgeErrorEnvelopeJson.decodeFromJsonElement(BridgeApiError.serializer(), it.jsonObject) }.getOrNull()
    }
    val rawCode = envelope?.code?.trim().orEmpty()
    return when {
        rawCode.isNotEmpty() -> classifyBridgeErrorCode(rawCode).copy(message = envelope?.message)
        else -> BridgeTeamError(
            source = BridgeTeamError.SOURCE_CLIENT,
            code = BridgeClientKnownCode.REQUEST_FAILED.wireValue,
            knownClientCode = BridgeClientKnownCode.REQUEST_FAILED.name,
            message = envelope?.message
        )
    }
}

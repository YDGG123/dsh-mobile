package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * 配对资料解析与配对三态判定（Docs/bot-team-port-design.md §2.8）。
 *
 * 配对资料来自桌面端 companion：`pairing.create` 返回 {url, code, serverId, expiresAt}，
 * QR 与「复制配对资料」文本同形（client.mjs 用 JSON.stringify(state.pairing) 生成二维码）。
 * 这里把解析、校验与 complete 三态（pending / approved / denied·invalid）放到 commonMain，
 * 由 commonTest 覆盖，Swift 只做 HTTP 执行与 UI。
 */

@Serializable
data class BridgePairingPayload(
    val url: String,
    val code: String,
    val serverId: String? = null,
    val expiresAt: JsonPrimitive? = null,
    val tlsPin: String? = null
)

/** 解析结果：status ∈ valid / invalid_json / insecure_url / invalid_code / expired / invalid_pin。 */
@Serializable
data class BridgePairingParseResult(
    val status: String,
    val url: String? = null,
    /** 规范化 origin（无 path/query/凭据），配对绑定用。 */
    val origin: String? = null,
    val code: String? = null,
    val serverId: String? = null,
    val tlsPin: String? = null
) {
    val isValid: Boolean get() = status == STATUS_VALID

    companion object {
        const val STATUS_VALID = "valid"
        const val STATUS_INVALID_JSON = "invalid_json"
        const val STATUS_INSECURE_URL = "insecure_url"
        const val STATUS_INVALID_CODE = "invalid_code"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_INVALID_PIN = "invalid_pin"
    }
}

/** complete 三态：pending（继续等桌面批准）/ approved（sessionJson 可落库）/ invalid。 */
@Serializable
data class BridgePairingCompleteResult(
    val status: String,
    val sessionJson: String? = null
) {
    val isPending: Boolean get() = status == STATUS_PENDING
    val isApproved: Boolean get() = status == STATUS_APPROVED

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_INVALID = "invalid"
    }
}

/**
 * 解析并校验配对资料。`nowMillis` 由平台注入（纯函数，便于测试）。
 * `expiresAt` 缺失视为不校验（桌面端总有值，缺省按宽松处理）。
 */
fun parseBridgePairingPayload(raw: String, nowMillis: Long): BridgePairingParseResult {
    val payload = runCatching { wireJson.decodeFromString<BridgePairingPayload>(raw) }.getOrNull()
        ?: return BridgePairingParseResult(status = BridgePairingParseResult.STATUS_INVALID_JSON)
    val origin = validateBridgeServerOrigin(payload.url)
        ?: return BridgePairingParseResult(status = BridgePairingParseResult.STATUS_INSECURE_URL)
    if (payload.code.isBlank() || payload.code.contains(",")) {
        return BridgePairingParseResult(status = BridgePairingParseResult.STATUS_INVALID_CODE)
    }
    val tlsPin = payload.tlsPin
    if (tlsPin != null && validateBridgeTlsPin(tlsPin) == null) {
        return BridgePairingParseResult(status = BridgePairingParseResult.STATUS_INVALID_PIN)
    }
    val expiresAt = payload.expiresAt?.content?.toLongOrNull()
    if (expiresAt != null && expiresAt <= nowMillis) {
        return BridgePairingParseResult(status = BridgePairingParseResult.STATUS_EXPIRED)
    }
    return BridgePairingParseResult(
        status = BridgePairingParseResult.STATUS_VALID,
        url = payload.url,
        origin = origin,
        code = payload.code,
        serverId = payload.serverId,
        tlsPin = tlsPin
    )
}

/**
 * 判定 `/pairings/complete` 的返回：
 * - `{status:"pending"}` ⇒ pending；
 * - Session（含 accessToken/refreshToken/deviceId/serverId/expiresAt）⇒ approved，
 *   并按 SDK index.js:242 语义合并 serverUrl/tlsPin 后输出可落 Keychain 的 sessionJson；
 * - 其余 ⇒ invalid（调用方按错误码另行分类，如 denied/expired 由 HTTP 403/404 表达）。
 */
fun classifyBridgePairingComplete(
    raw: String,
    origin: String,
    tlsPin: String?
): BridgePairingCompleteResult {
    val root = runCatching { wireJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return BridgePairingCompleteResult(status = BridgePairingCompleteResult.STATUS_INVALID)
    val status = root["status"]?.let { (it as? JsonPrimitive)?.content }
    if (status == "pending") {
        return BridgePairingCompleteResult(status = BridgePairingCompleteResult.STATUS_PENDING)
    }
    val session = runCatching { wireJson.decodeFromString<BridgeDeviceSession>(raw) }.getOrNull()
        ?: return BridgePairingCompleteResult(status = BridgePairingCompleteResult.STATUS_INVALID)
    if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
        return BridgePairingCompleteResult(status = BridgePairingCompleteResult.STATUS_INVALID)
    }
    val merged = session.copy(serverUrl = origin, tlsPin = tlsPin)
    return BridgePairingCompleteResult(
        status = BridgePairingCompleteResult.STATUS_APPROVED,
        sessionJson = wireJson.encodeToString(BridgeDeviceSession.serializer(), merged)
    )
}

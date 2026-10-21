package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2 写操作与设备管理的 wire DTO（Docs/bot-team-port-design.md §2.5 / §2.8）。
 * 形状对照 bridge 0.0.6：actions.mjs（models/efforts/update-bot/set-flags）与
 * auth.js（publicDevice / revoke）。
 *
 * 编码红线：`update-bot` 的 `model: null` 表示「使用默认模型」，与「不带该键」语义不同，
 * 因此 payload 构建器必须显式区分 include 与 null（bridge 用 Object.hasOwn 判定）。
 */

@Serializable
data class BridgeEffortRow(
    val id: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class BridgeEffortsPayload(
    val efforts: List<BridgeEffortRow> = emptyList(),
    val default: String? = null
)

@Serializable
data class BridgeModelsPayload(
    val models: List<BridgeModelRow> = emptyList(),
    val defaultModel: String? = null
)

@Serializable
data class BridgeFlagsApplied(
    val id: String,
    val pinned: Boolean? = null,
    val hidden: Boolean? = null
)

@Serializable
data class BridgeBotUpdated(
    val id: String,
    val changed: Boolean? = null
)

/** set-main 的响应属上游宿主契约，bridge 0.0.6 未放行；按不透明 ack 处理。 */
@Serializable
data class BridgeAck(val ok: Boolean? = null)

@Serializable
data class BridgeDeviceRow(
    val id: String,
    val name: String? = null,
    val createdAt: JsonPrimitive? = null,
    val lastSeenAt: JsonPrimitive? = null,
    val revokedAt: JsonPrimitive? = null
) {
    val createdAtValue: Long? get() = createdAt?.content?.toLongOrNull()
    val lastSeenAtValue: Long? get() = lastSeenAt?.content?.toLongOrNull()
    val isRevoked: Boolean get() = revokedAt != null
}

@Serializable
data class BridgeDevicesPayload(
    val devices: List<BridgeDeviceRow> = emptyList(),
    val currentDeviceId: String? = null
)

@Serializable
data class BridgeDeviceRevoked(
    val id: String,
    val revoked: Boolean? = null
)

/** `set-flags {id, pinned?, hidden?}`：只带调用方明确指定的键。 */
fun bridgeSetFlagsPayload(id: String, pinned: Boolean?, hidden: Boolean?): JsonObject =
    buildJsonObject {
        put("id", id)
        if (pinned != null) put("pinned", pinned)
        if (hidden != null) put("hidden", hidden)
    }

/** `update-bot {id, model?, effort?}`；includeModel/includeEffort 控制键是否存在（null 有意义）。 */
fun bridgeUpdateBotPayload(
    id: String,
    includeModel: Boolean,
    model: String?,
    includeEffort: Boolean,
    effort: String?
): JsonObject = buildJsonObject {
    put("id", id)
    if (includeModel) {
        put("model", model?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
    }
    if (includeEffort) {
        put("effort", effort?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
    }
}

/** `set-main {id, main, primary?}`（§2.5 上游宿主契约）。 */
fun bridgeSetMainPayload(id: String, main: Boolean, primary: Boolean?): JsonObject =
    buildJsonObject {
        put("id", id)
        put("main", main)
        if (primary != null) put("primary", primary)
    }

fun parseBridgeModelsPayload(raw: String): BridgeModelsPayload = wireJson.decodeFromString(raw)

fun parseBridgeEffortsPayload(raw: String): BridgeEffortsPayload = wireJson.decodeFromString(raw)

fun parseBridgeDevicesPayload(raw: String): BridgeDevicesPayload = wireJson.decodeFromString(raw)

fun parseBridgeDeviceRevoked(raw: String): BridgeDeviceRevoked = wireJson.decodeFromString(raw)

fun parseBridgeFlagsApplied(raw: String): BridgeFlagsApplied = wireJson.decodeFromString(raw)

fun parseBridgeBotUpdated(raw: String): BridgeBotUpdated = wireJson.decodeFromString(raw)

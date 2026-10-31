package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桥接卡片帧（`kind:"question" / "view" / "secret"`）的 shared 解析。
 *
 * 逐语义对照 iOS `GatewayTeamFeed.cardQuestion(from:)`（:957）与 `cardOwner`（:888）：
 * - owner = `roomId` 优先，其次 `botId`，空串视同缺失；
 * - question 取第一条问题的 `question`/`title` + `options[].label` + `custom`/`multiple`；
 * - view 取 `view.title/text/body` 第一个非空；
 * - secret 取 `secret.name/description` 第一个非空；
 * - 未知 kind 返回 null（iOS default 分支）。
 *
 * 展示纪律（iOS :1179 起同款）：bridge 没有 question-answer action，
 * 卡片是**本地展示**，UI 必须标明「在电脑端处理」，绝不假装能点。
 */
data class BridgeCardQuestion(
    val id: String,
    val owner: String,
    val sessionId: String? = null,
    val kind: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val allowsCustom: Boolean = false,
    val allowsMultiple: Boolean = false
) {
    /** 行内状态文案（iOS statusText :1179 的 kind 分支）。 */
    val statusText: String
        get() = when (kind) {
            "view" -> "等你确认卡片"
            "secret" -> "等你填密钥"
            else -> "等你回答"
        }
}

/** 归属键：roomId 优先，其次 botId；两者皆空 = 无主卡（iOS cardOwner 返回 nil）。 */
fun bridgeCardOwner(card: JsonObject): String? {
    val owner = card.stringOf("roomId") ?: card.stringOf("botId") ?: return null
    return owner.ifEmpty { null }
}

/** 单张卡 → 可展示问句；结构与 iOS `cardQuestion(from:)` 一致，未知 kind = null。 */
fun parseBridgeCardQuestion(card: JsonObject): BridgeCardQuestion? {
    val id = card.stringOf("id").orEmpty()
    if (id.isEmpty()) return null
    val owner = bridgeCardOwner(card) ?: return null
    val kind = card.stringOf("kind") ?: "question"
    val sessionId = card.stringOf("sessionId")
    return when (kind) {
        "question" -> {
            val first = card.arrayOf("questions")?.firstOrNull() as? JsonObject ?: return null
            val prompt = first.stringOf("question") ?: first.stringOf("title")
            if (prompt.isNullOrEmpty()) return null
            val options = (first.arrayOf("options").orEmpty())
                .mapNotNull { (it as? JsonObject)?.stringOf("label") }
                .filter { it.isNotEmpty() }
            BridgeCardQuestion(
                id = id,
                owner = owner,
                sessionId = sessionId,
                kind = kind,
                prompt = prompt,
                options = options,
                allowsCustom = first.booleanOf("custom") == true,
                allowsMultiple = first.booleanOf("multiple") == true
            )
        }
        "view" -> {
            val view = card.objOf("view") ?: return null
            val prompt = listOf("title", "text", "body").firstNotNullOfOrNull(view::stringOf)
            if (prompt.isNullOrEmpty()) return null
            BridgeCardQuestion(id = id, owner = owner, sessionId = sessionId, kind = kind, prompt = prompt)
        }
        "secret" -> {
            val secret = card.objOf("secret") ?: return null
            val prompt = listOf("name", "description").firstNotNullOfOrNull(secret::stringOf)
            if (prompt.isNullOrEmpty()) return null
            BridgeCardQuestion(id = id, owner = owner, sessionId = sessionId, kind = kind, prompt = prompt)
        }
        else -> null
    }
}

/**
 * 卡片帧 → 卡列表。接受的两种 wire 形态：
 * - 快照：`{ kind:"card-snapshot", cards:[…] }`（`GET /mobile/v1/cards` 与 WS 同源帧）；
 * - 裸数组：`[…]`。
 * `ops` 增量（card.shown/answered/cleared）属 WS 实时流（D 组），此处不解析。
 */
fun parseBridgeCardsFrame(frameJson: String): List<BridgeCardQuestion> {
    val root = runCatching { wireJson.parseToJsonElement(frameJson) }.getOrNull() ?: return emptyList()
    val cards: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> root.arrayOf("cards") ?: return emptyList()
        else -> return emptyList()
    }
    return cards.mapNotNull { element ->
        (element as? JsonObject)?.let(::parseBridgeCardQuestion)
    }
}

private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.booleanOf(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.arrayOf(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.objOf(key: String): JsonObject? = this[key] as? JsonObject

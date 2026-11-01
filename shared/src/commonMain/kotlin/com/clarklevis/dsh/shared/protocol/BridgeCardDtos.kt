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
 * 展示纪律（**2026-11-01 更正**）：bridge **有** question-answer 动作
 * （iOS `BridgeTeamAdapter.swift:371-394`，载荷 = `selected` + 可选 `custom`；
 *  Android 侧对应 `ConversationScreen.kt:644-647 → AndroidSharedStateHolder.kt:1637-1639`）。
 * 原先此处写「bridge 没有 question-answer action，绝不假装能点」是**过期错注释**——
 * 照它会把这个组件做成不可点，少一个 iOS 有的能力。
 * **仍然成立的是**：`secret` 卡**没有**提交通道（网关 `actions.mjs` 无 `secret-set`，
 * iOS 也只解析不提交）→ secret 卡保持只读，行内文案「等你填密钥」并标明在电脑端填写。
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
    /** 行内状态文案 —— **仅 iOS `statusText(for:)` 的 kind 分支**（:1179，文案逐字一致）。
     *  iOS 该函数是行级多层（pending → placeholderCards「在电脑端回答」→ approvals →
     *  degraded → activity），其余层依赖实时流与写操作，按计划归 D 组，不在本 DTO 范围。 */
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
            // iOS 语义（:966）：`question ?? title` 是 nil 合并 —— question 存在但是空串
            // **不会**回退到 title，随后 `!prompt.isEmpty` 直接丢卡。别改成跳空串。
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
            // iOS 语义（:977）：`compactMap { as? String }.first { !$0.isEmpty }` ——
            // **跳过空串与非字符串继续往后找**，全空才丢卡（AndroidTester 对拍 B2/B3/B5）。
            val prompt = listOf("title", "text", "body")
                .firstNotNullOfOrNull { key -> view.stringOf(key)?.takeIf(String::isNotEmpty) }
                ?: return null
            BridgeCardQuestion(id = id, owner = owner, sessionId = sessionId, kind = kind, prompt = prompt)
        }
        "secret" -> {
            val secret = card.objOf("secret") ?: return null
            // 同 view：跳过空串继续找（对拍 B7）。
            val prompt = listOf("name", "description")
                .firstNotNullOfOrNull { key -> secret.stringOf(key)?.takeIf(String::isNotEmpty) }
                ?: return null
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

/** iOS `as? String` 等价的**严格**取串：仅 JSON 字符串；数字/布尔/null 一律缺失（对拍 B5/B15 ——
 *  `JsonPrimitive.content` 会把 123 转成 "123"，iOS 的类型转换不会）。 */
private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** iOS `as? Bool` 等价的**严格**取布尔：JSON 布尔字面量；字符串一律不算（对拍 B22）；
 *  数字仅 `1`→true、`0`→false（iOS 经 Swift `NSNumber→Bool` 桥接只认数字 1，对拍加严批次；
 *  kotlinx `booleanOrNull` 对数字返回 null，须显式桥接）。其余数字（2/-1…）= false。 */
private fun JsonObject.booleanOf(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { primitive ->
        when (primitive.content) {
            "1" -> true
            "0" -> false
            else -> primitive.booleanOrNull
        }
    }

private fun JsonObject.arrayOf(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.objOf(key: String): JsonObject? = this[key] as? JsonObject

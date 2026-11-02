package com.clarklevis.dsh.shared.teamfeed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * D1 帧模型与解析 —— 帧序与字段以网关 `lib/mobile-v1/team-feed.mjs` 为准（2026-11-03 00:3x 实读）：
 *
 * - `ready`（服务端握手确认）
 * - `snapshot`：`{v, kind, instanceId, seq, at, degraded, mainBotId, mainBotIds, bots[], rooms[], pending[]}`
 *   （:308-324；降级快照 :367 只有名册，刻意不写 activity）
 * - `delta`：`{v, kind, instanceId, seq, base, at, ops[], degraded}`（:406/:511/:538）
 *   ops 八种：`bot|room . add|update|patch|remove` + `main.set`
 *   （:32-33 的 `STRUCTURAL_OPS/PATCH_OPS`、`normalizeOp :42-59`）；`patch` 的 `set` 已被网关裁掉静态字段，
 *   客户端在 reducer 里**再裁一次**（防御，不信任上游）。
 * - `order`：`{v, kind:'order', instanceId, seq:head, orderSeq, at, sessions, degraded}`（:232）
 *   —— `seq` 是回显 head，**不推进**消费水位；排序键在 `sessions`。
 * - `avatar`：快照分帧的补齐批次 `{kind:'avatar', items:[{id, avatar}]}`（:431 附近的分帧说明 + iOS :756）。
 * - `card` / `card-snapshot`：`{v: CARD_FRAMES_VERSION, kind, instanceId, seq, base, at, ops[], degraded?}`
 *   （`card-frames.mjs:181-223`）；ops：`card.shown`（带 card 正文）/ `card.answered` / `card.cleared`
 *   （:212-223；**answered/cleared 不带 card 字段** ⇒ 客户端必须维护活卡集合，iOS `:139-147`）。
 *
 * 解析纪律：**缺字段 = 字段不存在**（可空），不造默认值冒充服务端给过；未知 kind / 不合规 op 返回 null 丢弃。
 */
sealed interface TeamFeedFrame {
    data class Ready(val raw: JsonObject) : TeamFeedFrame

    data class Snapshot(
        val instanceId: String?,
        val seq: Long,
        val degraded: Boolean,
        val mainBotIds: List<String>,
        val rows: List<TeamFeedRow>,
        val at: Long?,
    ) : TeamFeedFrame

    data class Delta(
        val instanceId: String?,
        val seq: Long,
        val base: Long,
        val degraded: Boolean,
        /** 已按 `normalizeOp` 规则过滤后的合法 op；不合规的已丢弃。 */
        val ops: List<TeamFeedOp>,
        val at: Long?,
    ) : TeamFeedFrame

    /** `order` 帧：只带排序键。注意 [seq] 是 head 回显，reducer **不得**用它推进水位。 */
    data class Order(
        val instanceId: String?,
        val seq: Long,
        val orderSeq: Long?,
        val sessions: List<String>,
        val degraded: Boolean,
    ) : TeamFeedFrame

    /** 快照分帧的头像补齐批次：`items` = (id, avatar) 对。 */
    data class AvatarBatch(val items: List<Pair<String, TeamFeedAvatar>>) : TeamFeedFrame

    data class Cards(
        val isSnapshot: Boolean,
        val instanceId: String?,
        val seq: Long?,
        val base: Long?,
        /** snapshot ⇒ 全量替换；增量 ⇒ 逐 op 归并。 */
        val ops: List<TeamFeedCardOp>,
        val degraded: Boolean?,
    ) : TeamFeedFrame
}

sealed interface TeamFeedOp {
    data class Upsert(val kind: String, val row: TeamFeedRow) : TeamFeedOp
    data class Patch(val kind: String, val id: String, val set: Map<String, JsonElement>) : TeamFeedOp
    data class Remove(val kind: String, val id: String) : TeamFeedOp
    data class MainSet(val mainBotIds: List<String>) : TeamFeedOp
}

sealed interface TeamFeedCardOp {
    /** `card.shown`：带完整卡正文。 */
    data class Shown(val card: TeamFeedCard) : TeamFeedCardOp

    /** `card.answered` / `card.cleared`：**不带 card 字段**，只有 id。 */
    data class Retire(val id: String) : TeamFeedCardOp
}

private val jsonLenient = Json { ignoreUnknownKeys = true }

fun parseTeamFeedFrame(text: String): TeamFeedFrame? = runCatching {
    val obj = jsonLenient.parseToJsonElement(text).let { it as? JsonObject } ?: return null
    parseTeamFeedFrame(obj)
}.getOrNull()

fun parseTeamFeedFrame(element: JsonElement): TeamFeedFrame? {
    val obj = element as? JsonObject ?: return null
    return when (obj.str("kind")) {
        "ready" -> TeamFeedFrame.Ready(obj)
        "snapshot" -> TeamFeedFrame.Snapshot(
            instanceId = obj.str("instanceId"),
            seq = obj.long("seq") ?: return null,
            degraded = obj.bool("degraded") ?: return null,
            mainBotIds = obj.arrayOfStrings("mainBotIds"),
            rows = (obj.array("bots") + obj.array("rooms")).mapNotNull(::parseTeamFeedRow),
            at = obj.long("at"),
        )
        "delta" -> TeamFeedFrame.Delta(
            instanceId = obj.str("instanceId"),
            seq = obj.long("seq") ?: return null,
            base = obj.long("base") ?: return null,
            degraded = obj.bool("degraded") ?: return null,
            ops = obj.array("ops").mapNotNull(::parseTeamFeedOp),
            at = obj.long("at"),
        )
        "order" -> TeamFeedFrame.Order(
            instanceId = obj.str("instanceId"),
            seq = obj.long("seq") ?: return null,
            orderSeq = obj.long("orderSeq"),
            sessions = obj.arrayOfStrings("sessions"),
            degraded = obj.bool("degraded") ?: return null,
        )
        "avatar" -> TeamFeedFrame.AvatarBatch(
            items = obj.array("items").mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val avatar = (o["avatar"] as? JsonObject)?.let(::parseTeamFeedAvatar) ?: return@mapNotNull null
                id to avatar
            },
        )
        "card", "card-snapshot" -> TeamFeedFrame.Cards(
            isSnapshot = obj.str("kind") == "card-snapshot",
            instanceId = obj.str("instanceId"),
            seq = obj.long("seq"),
            base = obj.long("base"),
            ops = if (obj.str("kind") == "card-snapshot") {
                obj.array("cards").mapNotNull { card -> parseTeamFeedCard(card)?.let(TeamFeedCardOp::Shown) }
            } else {
                obj.array("ops").mapNotNull { op ->
                    val o = op as? JsonObject ?: return@mapNotNull null
                    when (o.str("op")) {
                        "card.shown" -> o["card"]?.let(::parseTeamFeedCard)?.let(TeamFeedCardOp::Shown)
                        "card.answered", "card.cleared" -> o.str("id")?.let(TeamFeedCardOp::Retire)
                        else -> null
                    }
                }
            },
            degraded = obj.bool("degraded"),
        )
        else -> null
    }
}

/** `normalizeOp`（`team-feed.mjs:42-59`）的 Kotlin 版：不合规 op 一律 null。 */
fun parseTeamFeedOp(element: JsonElement): TeamFeedOp? {
    val obj = element as? JsonObject ?: return null
    val op = obj.str("op") ?: return null
    if (op == "main.set") {
        val ids = obj["mainBotIds"] as? kotlinx.serialization.json.JsonArray ?: return null
        val list = ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return TeamFeedOp.MainSet(list)
    }
    val match = Regex("^(bot|room)\\.(add|update|patch|remove)$").find(op) ?: return null
    val (kind, verb) = match.destructured
    val id = obj.str("id")?.takeIf { it.isNotEmpty() } ?: return null
    return when (verb) {
        "remove" -> TeamFeedOp.Remove(kind, id)
        "patch" -> {
            val set = obj["set"] as? JsonObject ?: return null
            if (set.isEmpty()) return null
            TeamFeedOp.Patch(kind, id, set)
        }
        else -> {
            val body = obj["entity"] as? JsonObject ?: return null
            val row = parseTeamFeedRow(body, expectedKind = kind) ?: return null
            TeamFeedOp.Upsert(kind, row)
        }
    }
}

fun parseTeamFeedRow(element: JsonElement, expectedKind: String? = null): TeamFeedRow? {
    val obj = element as? JsonObject ?: return null
    val id = obj.str("id")?.takeIf { it.isNotEmpty() } ?: return null
    val kind = obj.str("kind") ?: expectedKind ?: return null
    if (kind != "bot" && kind != "room") return null
    // 契约：bot 必须有 sessionId（fallbackSnapshot :352 也按它过滤）；room 不需要。
    val sessionId = obj.str("sessionId")
    if (kind == "bot" && sessionId.isNullOrBlank()) return null
    return TeamFeedRow(
        id = id,
        kind = kind,
        name = obj.str("name") ?: "",
        role = obj.str("role"),
        model = obj.str("model"),
        effort = obj.str("effort"),
        sessionId = sessionId,
        main = obj.bool("main") ?: false,
        pinned = obj.bool("pinned") ?: false,
        hidden = obj.bool("hidden") ?: false,
        members = obj.arrayOfStrings("members"),
        admin = obj.str("admin"),
        mode = obj.str("mode"),
        notice = obj.str("notice"),
        preview = obj.str("preview"),
        parts = obj.arrayOfStrings("parts"),
        activity = obj.str("activity"),
        work = (obj["work"] as? JsonObject)?.let(::parseTeamFeedWork),
        approvals = obj.array("approvals").mapNotNull(::parseTeamFeedApproval),
        pending = obj.array("pending").mapNotNull(::parseTeamFeedPending),
        waiting = (obj["waiting"] as? JsonObject)?.let(::parseTeamFeedWaiting),
        updatedAt = obj.long("updatedAt"),
        avatar = (obj["avatar"] as? JsonObject)?.let(::parseTeamFeedAvatar) ?: TeamFeedAvatar(),
        // iOS :329-333：glyph 在 bot 顶层，avatar.glyph 只是兜底。
        glyph = (obj["glyph"] as? JsonObject)?.let(::parseTeamFeedGlyph)
            ?: (obj["avatar"] as? JsonObject)?.let { (it["glyph"] as? JsonObject)?.let(::parseTeamFeedGlyph) },
        colorKey = obj.str("color"),
        placeholderCards = obj.int("placeholderCards") ?: 0,
    )
}

private fun parseTeamFeedWork(obj: JsonObject): TeamFeedWork = TeamFeedWork(
    roomId = obj.str("roomId"), tool = obj.str("tool"), target = obj.str("target"),
    note = obj.str("note"), said = obj.str("said"), since = obj.long("since"),
)

private fun parseTeamFeedApproval(element: JsonElement): TeamFeedApproval? {
    val obj = element as? JsonObject ?: return null
    val sessionId = obj.str("sessionId") ?: return null
    return TeamFeedApproval(sessionId, obj.str("tool"), obj.str("reason"), obj.long("at"))
}

private fun parseTeamFeedPending(element: JsonElement): TeamFeedPending? {
    val obj = element as? JsonObject ?: return null
    val kind = obj.str("kind") ?: return null
    val sessionId = obj.str("sessionId") ?: return null
    return TeamFeedPending(kind, sessionId, obj.str("roomId"), obj.long("at"))
}

private fun parseTeamFeedWaiting(obj: JsonObject): TeamFeedWaiting = TeamFeedWaiting(
    bots = obj.arrayOfStrings("bots"),
    rooms = obj.arrayOfStrings("rooms"),
    forWhom = obj.arrayOfStrings("for"),
)

private fun parseTeamFeedAvatar(obj: JsonObject): TeamFeedAvatar = TeamFeedAvatar(
    shape = obj.str("shape"),
    image = obj.str("image"),
    hash = obj.str("hash") ?: obj.str("imageHash"),
    glyph = (obj["glyph"] as? JsonObject)?.let(::parseTeamFeedGlyph),
)

fun parseTeamFeedGlyph(obj: JsonObject): TeamFeedGlyph? {
    val body = obj.str("path") ?: obj.str("body") ?: return null
    if (body.isBlank()) return null
    val size = obj.dbl("size") ?: 100.0
    if (size <= 0) return null
    return TeamFeedGlyph(
        size = size,
        body = body,
        layers = obj.array("layers").mapNotNull { layer ->
            val l = layer as? JsonObject ?: return@mapNotNull null
            val d = l.str("d") ?: return@mapNotNull null
            TeamFeedGlyphLayer(d, l.str("fill"), l.dbl("opacity"))
        },
        eyes = obj.array("eyes").mapNotNull { eye ->
            val e = eye as? JsonArray ?: return@mapNotNull null
            val numbers = e.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            // 电脑端 eyes = [cx, cy, w, h, tilt?]（w/h 是宽高，这里存成半径对）。
            if (numbers.size < 4) return@mapNotNull null
            TeamFeedGlyphEye(cx = numbers[0], cy = numbers[1], rx = numbers[2] / 2, ry = numbers[3] / 2)
        },
        color = obj.str("color"),
    )
}

private fun parseTeamFeedCard(element: JsonElement): TeamFeedCard? {
    val obj = element as? JsonObject ?: return null
    val id = obj.str("id")?.takeIf { it.isNotEmpty() } ?: return null
    val kind = obj.str("kind") ?: return null
    val owner = obj.str("roomId") ?: obj.str("botId") ?: return null
    return TeamFeedCard(
        id = id,
        owner = owner.ifEmpty { null },
        sessionId = obj.str("sessionId"),
        kind = kind,
        prompt = obj.str("prompt") ?: obj.str("text") ?: obj.str("title"),
        options = obj.arrayOfStrings("options"),
        allowsCustom = obj.bool("allowsCustom") ?: false,
        allowsMultiple = obj.bool("allowsMultiple") ?: false,
    )
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() || (this[key] as? JsonPrimitive)?.isString == true }

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.array(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.toList() ?: emptyList()

internal fun JsonObject.arrayOfStrings(key: String): List<String> =
    array(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

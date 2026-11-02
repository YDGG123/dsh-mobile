package com.clarklevis.dsh.shared.teamfeed

import kotlinx.serialization.Serializable

/**
 * D1（dsBotTeam feed）行模型 —— 字段逐个对齐 iOS `GatewayTeamFeed.Row`（:80-123）。
 *
 * 契约要点（`team-feed.mjs` / ds-bot `roster-publish-v1.md` v2）：
 * - 静态字段（avatar/color/name/role/…）只在 snapshot / add / update 出现，patch 一律裁掉
 *   （网关 `team-feed.mjs:416` 的 `STATIC_ONLY`，客户端二次防御见 [TeamFeedReducer]）。
 * - `glyph` 在 **bot 顶层**（与 avatar 平级），不是 avatar 里的字段 —— iOS `:329-333`
 *   记着历史坑：写成 `avatar["glyph"]` 会恒 nil。
 * - `activity == null` 是「服务端没提供」（降级态），**不是** idle；契约枚举里没有 unknown，
 *   缺字段 + `degraded: true` 才是诚实表达（`team-feed.mjs:353` 的注释）。
 */
@Serializable
data class TeamFeedWork(
    val roomId: String? = null,
    val tool: String? = null,
    val target: String? = null,
    val note: String? = null,
    val said: String? = null,
    val since: Long? = null,
)

@Serializable
data class TeamFeedApproval(
    val sessionId: String,
    val tool: String? = null,
    val reason: String? = null,
    val at: Long? = null,
)

@Serializable
data class TeamFeedPending(
    val kind: String,
    val sessionId: String,
    val roomId: String? = null,
    val at: Long? = null,
)

/** 契约里 `waiting` 的键是 `for`；Kotlin 里 `for` 是关键字，映射名 [forWhom]（解析层处理）。 */
@Serializable
data class TeamFeedWaiting(
    val bots: List<String> = emptyList(),
    val rooms: List<String> = emptyList(),
    val forWhom: List<String> = emptyList(),
)

/** card 帧正文（iOS `GatewayTeamFeed.CardQuestion` 同构）。owner 归属键：roomId 优先，其次 botId。 */
@Serializable
data class TeamFeedCard(
    val id: String,
    val owner: String? = null,
    val sessionId: String? = null,
    val kind: String,
    val prompt: String? = null,
    val options: List<String> = emptyList(),
    val allowsCustom: Boolean = false,
    val allowsMultiple: Boolean = false,
)

/** glyph 矢量定义（ds-bot SHAPES 同源；JSON→模型解析在 androidApp `TeamGlyphParser.kt`）。 */
@Serializable
data class TeamFeedGlyph(
    val size: Double = 100.0,
    val body: String = "",
    val layers: List<TeamFeedGlyphLayer> = emptyList(),
    val eyes: List<TeamFeedGlyphEye> = emptyList(),
    val color: String? = null,
)

@Serializable
data class TeamFeedGlyphLayer(val d: String, val fill: String? = null, val opacity: Double? = null)

@Serializable
data class TeamFeedGlyphEye(val cx: Double, val cy: Double, val rx: Double = 1.0, val ry: Double = 1.0)

/** ds-bot rosters 的 `avatar` 是 `{ shape, image }` 对象（不是单个 data URL）。 */
@Serializable
data class TeamFeedAvatar(
    val shape: String? = null,
    val image: String? = null,
    /** 头像文件内容哈希（sha256 前 16 位）：缓存键；实时帧带 image，水合帧可能只带 hash。 */
    val hash: String? = null,
    val glyph: TeamFeedGlyph? = null,
)

@Serializable
data class TeamFeedRow(
    val id: String,
    /** `bot` | `room`；未知 kind 丢弃（契约枚举没有第三种）。 */
    val kind: String,
    val name: String = "",
    val role: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val sessionId: String? = null,
    val main: Boolean = false,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val members: List<String> = emptyList(),
    val admin: String? = null,
    val mode: String? = null,
    val notice: String? = null,
    val preview: String? = null,
    val parts: List<String> = emptyList(),
    // —— 易变字段（契约 §4：只随 delta；缺字段 = 服务端没提供，不许当 idle）——
    val activity: String? = null,
    val work: TeamFeedWork? = null,
    val approvals: List<TeamFeedApproval> = emptyList(),
    val pending: List<TeamFeedPending> = emptyList(),
    val waiting: TeamFeedWaiting? = null,
    val updatedAt: Long? = null,
    // —— 静态字段 ——
    val avatar: TeamFeedAvatar = TeamFeedAvatar(),
    /** bot 顶层的 glyph（iOS `:329-333`：与 avatar 平级；解析时附带 avatar.glyph 兜底）。 */
    val glyph: TeamFeedGlyph? = null,
    val colorKey: String? = null,
    val placeholderCards: Int = 0,
)

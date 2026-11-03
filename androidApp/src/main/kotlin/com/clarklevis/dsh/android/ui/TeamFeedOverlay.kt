package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.teamfeed.TeamFeedGlyph
import com.clarklevis.dsh.shared.teamfeed.TeamFeedReducer

/**
 * D1 段 5 的**纯映射函数**（JVM 可测）——bridge 名册行 × feed 行 → UI 展示决定。
 *
 * 契约要点（iOS `GatewayTeamFeed` / `WorkspaceView` 同规）：
 * - `degraded == true`：易变数据不可信，UI 只显示降级横幅，状态回落 bridge，**不许渲染成「全部空闲」**
 * - `activity` 缺失（null）≠ idle：契约枚举没有 unknown，缺字段一律「不动」（inheritsVolatileState 语义）
 * - `activity ∈ {working, thinking}` ⇒ 干活中；`idle` ⇒ 空闲；未知值按缺字段处理（不猜）
 * - 兜底链：`image > glyph > shape > 派生 > 首字母`（本文件只决定 glyph 与 image 的优先级，
 *   shape/派生在 RosterAvatar 既有链里）
 */
internal object TeamFeedOverlay {

    /** 状态副行文案；null = 沿用 bridge 显示（feed 没给这个字段）。 */
    fun statusLine(activity: String?, degraded: Boolean, bridgeBusy: Boolean?): String? {
        if (degraded) return null
        return when (activity) {
            "working", "thinking" -> "正在干活"
            "idle" -> "空闲"
            else -> null // 缺字段/未知值：不动（绝不谎报空闲）
        }.let { line ->
            if (line != null || bridgeBusy == null) line
            else if (bridgeBusy) "正在干活" else "空闲"
        }.takeIf { activity != null || bridgeBusy != null }
    }

    /** 兜底链里「image 与 glyph 谁先」：有照片用照片，没有才轮到 glyph（iOS 契约顺序）。 */
    fun preferImageOverGlyph(hasImage: Boolean, hasGlyph: Boolean): Boolean = hasImage || !hasGlyph

    /** 该行是否要用 feed 的 glyph（feed live 且行内有 glyph 且没有照片）。 */
    fun useGlyph(feedLive: Boolean, degraded: Boolean, hasImage: Boolean, glyph: TeamFeedGlyph?): Boolean =
        feedLive && !degraded && !hasImage && glyph != null && glyph.body.isNotBlank()

    /** 降级横幅：**逐字**对齐 iOS `WorkspaceView:583`，不自编。 */
    const val DEGRADED_BANNER =
        "实时状态暂不可用：网关目前只拿到名册（名字/角色/成员）。干活中、等你回答、等你审批要等桌面端发布团队服务后才显示。"

    /** feed live 且非降级才允许 overlay；其余情况整段回落 bridge。 */
    fun overlayActive(phase: TeamFeedPhase, degraded: Boolean): Boolean =
        phase == TeamFeedPhase.LIVE && !degraded
}

enum class TeamFeedPhase { IDLE, CONNECTING, LIVE, OFFLINE }

/** bridge 行 id → feed 行（仅 bots/rooms），供 BotTeamScreen 按行取 overlay。 */
internal fun overlayRowsByFeedId(state: TeamFeedReducer.TeamFeedState): Map<String, TeamFeedRowUi> =
    state.rows.values.associate { row -> row.id to TeamFeedRowUi(activity = row.activity, glyph = row.glyph, colorKey = row.colorKey) }

internal data class TeamFeedRowUi(val activity: String?, val glyph: com.clarklevis.dsh.shared.teamfeed.TeamFeedGlyph?, val colorKey: String?)

/**
 * 群聊成员头像堆叠几何（75 复核，**JVM 可钉**）——对齐 iOS `gatewayAvatar`：
 * 容器 38dp 高；第 i 个头像落在 `x = i × 16dp`（同一起点，**不是 Row 排布再叠加 offset**）。
 * iOS 是 `ZStack(alignment:.leading)` + `offset`：三头像落点 0/16/32；
 * 之前 Android 用 Row+offset 变成 0/43/86（Row 先排位、offset 再叠加），第三个溢出容器与文字相叠。
 */
internal object RoomStackGeometry {
    const val CONTAINER_HEIGHT_DP = 38
    const val AVATAR_SIZE_DP = 27
    const val STEP_DP = 16

    /** 返回 (容器宽 dp, 每个头像的 x 偏移列表)。成员数截到 3（iOS prefix(3) 同规）。 */
    fun layout(memberCount: Int): Pair<Int, List<Float>> {
        val count = memberCount.coerceIn(0, 3)
        val offsets = List(count) { index -> (index * STEP_DP).toFloat() }
        val width = if (count == 0) CONTAINER_HEIGHT_DP else CONTAINER_HEIGHT_DP + (count - 1) * STEP_DP
        return width to offsets
    }
}

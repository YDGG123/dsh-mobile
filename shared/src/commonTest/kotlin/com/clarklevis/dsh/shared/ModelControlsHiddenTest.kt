package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.domain.isRoomSession
import com.clarklevis.dsh.shared.domain.modelControlsHidden
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * modelControlsHidden（输入框模型/思考 chip 判定）的反向测试。
 * 判据 = iOS `BridgeTeamAdapter.modelControlsHidden` 镜像（**三态 × 只防群聊**，
 * 2026-11-06 用户两轮裁定收敛：Bot 会话放开切模型，只有群聊不许切——
 * 11-04 三群聊 MODEL_SWITCH_COMPACTION_FAILED 事故防线保留）。
 * 纯 JVM 断言，不依赖模拟器。旧口径（bots∪rooms 全防）的 isBotDomainSession
 * 判据在 BotDomainSessionTest，函数保留未改。
 */
class ModelControlsHiddenTest {

    private val rooms = setOf("room-session-1", "room-session-2", "room-session-3")
    private val bots = setOf("bot-session-1", "bot-session-2")

    // ── READY：按命中，只防 rooms ─────────────────────────────────────────

    @Test
    fun `READY 群聊命中 rooms - 隐藏（11-04 事故防线保留）`() {
        assertTrue(
            modelControlsHidden(sessionId = "room-session-1", bridgePhase = TeamPhase.READY, roomSessionIds = rooms)
        )
    }

    @Test
    fun `READY Bot 会话命中 bots - 显示（用户口径：Bot 会话放开切模型）`() {
        // 与旧口径（isBotDomainSession，bots∪rooms 全防）的关键分歧点。
        assertFalse(
            modelControlsHidden(sessionId = "bot-session-1", bridgePhase = TeamPhase.READY, roomSessionIds = rooms)
        )
    }

    @Test
    fun `READY 普通会话未命中 - 显示`() {
        assertFalse(
            modelControlsHidden(sessionId = "normal-session-x", bridgePhase = TeamPhase.READY, roomSessionIds = rooms)
        )
    }

    // ── OFFLINE：保留最后一次好名册，照常按命中 ──────────────────────────

    @Test
    fun `OFFLINE 群聊命中保留名册 - 隐藏；未命中 - 显示`() {
        assertTrue(
            modelControlsHidden(sessionId = "room-session-2", bridgePhase = TeamPhase.OFFLINE, roomSessionIds = rooms)
        )
        assertFalse(
            modelControlsHidden(sessionId = "normal-session-x", bridgePhase = TeamPhase.OFFLINE, roomSessionIds = rooms)
        )
    }

    // ── fail-closed 档（名册不在手上，此刻确实可能是群聊）──────────────────

    @Test
    fun `LOADING 名册未达 - fail-closed 隐藏（86 保留）`() {
        assertTrue(
            modelControlsHidden(sessionId = "normal-session-x", bridgePhase = TeamPhase.LOADING, roomSessionIds = emptySet())
        )
    }

    @Test
    fun `IDLE - fail-closed 隐藏（iOS 同口径；前提是轮询必须继续跑）`() {
        // ⚠️ 与旧口径（IDLE→显示）的第二个分歧点：iOS 86 判 IDLE/LOADING 同档
        // fail-closed。此判定成立的前提是 /state 轮询不跳过（插件缺失时 /state
        // 照样 200 返回空名册，READY 后按命中揭示）——省流量跳过已于 11-11 撤销。
        assertTrue(
            modelControlsHidden(sessionId = "normal-session-x", bridgePhase = TeamPhase.IDLE, roomSessionIds = emptySet())
        )
    }

    // ── 显示档 ────────────────────────────────────────────────────────────

    @Test
    fun `NEEDS_PAIRING - 显示（保留名册不认，iOS 同口径）`() {
        assertFalse(
            modelControlsHidden(sessionId = "room-session-3", bridgePhase = TeamPhase.NEEDS_PAIRING, roomSessionIds = rooms)
        )
    }

    @Test
    fun `无桥适配层 phase 为 null - 显示`() {
        assertFalse(
            modelControlsHidden(sessionId = "room-session-1", bridgePhase = null, roomSessionIds = rooms)
        )
    }

    @Test
    fun `无选中会话或空串 - 显示`() {
        assertFalse(
            modelControlsHidden(sessionId = null, bridgePhase = TeamPhase.READY, roomSessionIds = rooms)
        )
        assertFalse(
            modelControlsHidden(sessionId = "", bridgePhase = TeamPhase.READY, roomSessionIds = rooms)
        )
    }

    // ── isRoomSession（新公开判定，iOS isRoomSession 同源）─────────────────

    @Test
    fun `isRoomSession - rooms 命中即群聊，bots 命中不算`() {
        assertTrue(isRoomSession("room-session-1", rooms))
        assertFalse(isRoomSession("bot-session-1", rooms))
        assertFalse(isRoomSession(null, rooms))
        assertFalse(isRoomSession("", rooms))
    }
}

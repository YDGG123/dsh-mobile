package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.domain.isBotDomainSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bot 域会话判定的反向测试（Chief 2026-11-03 派单验证要求：普通会话 chip 在、
 * Bot 会话与群聊 room chip 不在）。纯 JVM 断言，不依赖模拟器。
 *
 * 判定语义见 [isBotDomainSession]（桌面端 ds-bot/src/client/index.js:916：
 * Bot 会话输入框没有模型选择）。
 */
class BotDomainSessionTest {

    private val botSessions = setOf("bot-session-1", "bot-session-2")
    private val roomSessions = setOf("room-session-1", "room-session-2", "room-session-3")

    // ── Chief 指定的三种反向用例 ──────────────────────────────────────────

    @Test
    fun `普通会话 - READY 名册非空但未命中 - 不在 Bot 域`() {
        // chip 应当显示（调用方据此决定显示 ModelControl/EffortControl）。
        assertFalse(
            isBotDomainSession(
                sessionId = "normal-session-x",
                bridgePhase = TeamPhase.READY,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    @Test
    fun `Bot 自己的会话 - READY 名册 bots 命中 - 在 Bot 域`() {
        assertTrue(
            isBotDomainSession(
                sessionId = "bot-session-1",
                bridgePhase = TeamPhase.READY,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    @Test
    fun `群聊 room - READY 名册 rooms 命中 - 在 Bot 域`() {
        assertTrue(
            isBotDomainSession(
                sessionId = "room-session-2",
                bridgePhase = TeamPhase.READY,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    // ── 名册未就绪的口径（fail-closed 的边界）────────────────────────────

    @Test
    fun `LOADING 已配对名册未达 - fail-closed 一律按可能在 Bot 域`() {
        // 事故形态（MODEL_SWITCH_COMPACTION_FAILED）就发生在名册缺席的窗口里：
        // 宁可普通会话短暂无 chip，不可给群聊留切模型入口。
        assertTrue(
            isBotDomainSession(
                sessionId = "normal-session-x",
                bridgePhase = TeamPhase.LOADING,
                botSessionIds = emptySet(),
                roomSessionIds = emptySet(),
            )
        )
    }

    @Test
    fun `IDLE 未配对 - Bot 域不可达 - 一律普通会话`() {
        // 未配对用户的名册永远不会到达；此处 fail-closed 会让全体普通会话
        // 永久丢 chip，是不可接受的回归，所以必须 fail-open。
        assertFalse(
            isBotDomainSession(
                sessionId = "normal-session-x",
                bridgePhase = TeamPhase.IDLE,
                botSessionIds = emptySet(),
                roomSessionIds = emptySet(),
            )
        )
        // 防御性：即便 IDLE 时列表残留了数据（正常不应发生），也按不可达处理。
        assertFalse(
            isBotDomainSession(
                sessionId = "room-session-1",
                bridgePhase = TeamPhase.IDLE,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    @Test
    fun `OFFLINE 保留最后一次好名册 - 按命中判定`() {
        // TeamReducer：刷新失败（网络/超时/5xx）⇒ OFFLINE 且保留最后一次数据。
        assertTrue(
            isBotDomainSession(
                sessionId = "room-session-3",
                bridgePhase = TeamPhase.OFFLINE,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
        assertFalse(
            isBotDomainSession(
                sessionId = "normal-session-x",
                bridgePhase = TeamPhase.OFFLINE,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    @Test
    fun `NEEDS_PAIRING 保留名册 - 按命中判定`() {
        // TeamReducer：session 失效 ⇒ NEEDS_PAIRING 且 copy 保留既有数据——
        // 房间不会因为手机侧 token 过期就不再是房间。
        assertTrue(
            isBotDomainSession(
                sessionId = "bot-session-2",
                bridgePhase = TeamPhase.NEEDS_PAIRING,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
        assertFalse(
            isBotDomainSession(
                sessionId = "normal-session-x",
                bridgePhase = TeamPhase.NEEDS_PAIRING,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    // ── 退化输入 ─────────────────────────────────────────────────────────

    @Test
    fun `无选中会话 - 不在 Bot 域`() {
        assertFalse(
            isBotDomainSession(
                sessionId = null,
                bridgePhase = TeamPhase.READY,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }

    @Test
    fun `桥适配层不存在 phase 为 null - 不在 Bot 域`() {
        assertFalse(
            isBotDomainSession(
                sessionId = "room-session-1",
                bridgePhase = null,
                botSessionIds = botSessions,
                roomSessionIds = roomSessions,
            )
        )
    }
}

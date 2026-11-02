package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.protocol.BridgeCardQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ + A（会话页卡片接线）的归属判据 —— C6「同一张卡归属唯一」的机器版。
 *
 * 背景：卡片此前只在 Bot 页成块渲染（用户截图「卡片串到 Bot 页」）。按 iOS 页面归属改成
 * 「Bot 页只留行内预览 + 整张卡只在**该卡所属会话**的会话页」之后，最容易假绿的两点是：
 * ① 一律渲染（无卡会话凭空出卡）；② 只对默认/当前选中会话生效（分属两会话时串台）。
 * 所以这里正反两向都断言。
 */
class BridgeCardPlacementTest {

    private fun card(id: String, sessionId: String?, kind: String = "question") = BridgeCardQuestion(
        id = id,
        owner = "session-owner",
        sessionId = sessionId,
        kind = kind,
        prompt = "prompt-$id"
    )

    private val sessionA = "session-aaaa"
    private val sessionB = "session-bbbb"

    @Test
    fun cardAppearsOnlyInItsOwnSession() {
        val cards = listOf(card("c1", sessionA))
        assertEquals(listOf("c1"), bridgeCardsForSession(cards, sessionA).map { it.id })
        assertTrue("A 会话的卡出现在了 B 会话", bridgeCardsForSession(cards, sessionB).isEmpty())
    }

    /** 两张卡分属两个会话：各在各的会话里出现，互不串（防「只对当前会话生效」的假绿）。 */
    @Test
    fun twoCardsInDifferentSessionsDoNotCrossOver() {
        val cards = listOf(card("cA", sessionA), card("cB", sessionB))
        assertEquals(listOf("cA"), bridgeCardsForSession(cards, sessionA).map { it.id })
        assertEquals(listOf("cB"), bridgeCardsForSession(cards, sessionB).map { it.id })
    }

    /** 无卡会话 ⇒ 空列表，不许凭空渲染空卡。 */
    @Test
    fun sessionWithoutCardsRendersNothing() {
        assertTrue(bridgeCardsForSession(emptyList(), sessionA).isEmpty())
        assertTrue(bridgeCardsForSession(listOf(card("cB", sessionB)), sessionA).isEmpty())
    }

    /** 没有会话（新建会话 / 未选中）⇒ 一张都不显示，即使存在无主卡。 */
    @Test
    fun noSelectedSessionRendersNothing() {
        val cards = listOf(card("cA", sessionA), card("noSession", null))
        assertTrue(bridgeCardsForSession(cards, null).isEmpty())
        assertTrue(bridgeCardsForSession(cards, "").isEmpty())
        assertTrue(bridgeCardsForSession(cards, "   ").isEmpty())
    }

    /** 无 `sessionId` 的无主卡不属于任何会话 ⇒ 任何会话都不渲染它（它只走 Bot 页的行内预览）。 */
    @Test
    fun ownerOnlyCardNeverLandsInAConversation() {
        val cards = listOf(card("ownerOnly", null), card("cA", sessionA))
        for (session in listOf(sessionA, sessionB, null)) {
            val ids = bridgeCardsForSession(cards, session).map { it.id }
            assertTrue("无主卡被塞进了会话 $session", "ownerOnly" !in ids)
        }
    }

    /** 同一会话多张卡：全部显示且保持顺序（列表顺序由 /cards 快照决定，不在 UI 里重排）。 */
    @Test
    fun multipleCardsOfOneSessionAllShowInSnapshotOrder() {
        val cards = listOf(card("c1", sessionA), card("c2", sessionA), card("c3", sessionB))
        assertEquals(listOf("c1", "c2"), bridgeCardsForSession(cards, sessionA).map { it.id })
    }
}
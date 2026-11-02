package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.LocaleTestRule
import com.clarklevis.dsh.shared.protocol.BridgeConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * 群聊行副行文案（②，2026-11-02 用户截图报障）。
 *
 * 缺陷本体：`row.members` 是 bot 的 **session id**（网关 `adapter.mjs:101` 原样下发），
 * 旧代码直接 `joinToString("、")` ⇒ 界面上每行都摊着 `session-a5000f0e-…`，
 * 长字符串还挤坏了行距。修法与 iOS `roomSubtitle`（GatewayTeamFeed:1209）对齐：
 * 映射成**名字**、前 4 个 + 「等 N 人」、空 ⇒「群聊」；**查不到的一律丢弃、绝不回落打印 id**。
 */
class RoomMemberNamesTest {
    @get:Rule val locale = LocaleTestRule()

    private fun bot(id: String, name: String) =
        BridgeConversationRow(id = id, kind = "bot", name = name, sessionId = id)

    private val bots = listOf(
        bot("session-a5000f0e", "Chief"),
        bot("session-48a9b54f", "iOSCoder"),
        bot("session-4d8741a8", "AndroidCoder"),
        bot("session-48799833", "AndroidTester"),
        bot("session-ffad66de", "iOSTester"),
        bot("session-4745edd2", "ReleaseBot")
    )

    @Test
    fun memberIdsRenderAsNamesInRowOrder() {
        assertEquals(
            "Chief、iOSCoder、AndroidCoder",
            roomMemberNames(listOf("session-a5000f0e", "session-48a9b54f", "session-4d8741a8"), bots)
        )
    }

    /** 超过 4 个只列前 4 个 + 「等 N 人」（iOS 同式；N 是成员总数，不是其余数）。 */
    @Test
    fun moreThanFourMembersCollapseToFirstFourPlusCount() {
        val all = bots.map { it.id }
        val text = roomMemberNames(all, bots)
        assertEquals("Chief、iOSCoder、AndroidCoder、AndroidTester 等 6 人", text)
    }

    /** 空成员 / 全部查不到 ⇒ 「群聊」，**不是**空字符串也不是 id 串（iOS 的回落文案）。 */
    @Test
    fun emptyOrUnknownMembersFallBackToRoomLabel() {
        assertEquals("群聊", roomMemberNames(emptyList(), bots))
        assertEquals("群聊", roomMemberNames(null, bots))
        assertEquals("群聊", roomMemberNames(listOf("session-deadbeef", "session-cafebabe"), bots))
    }

    /** 部分查不到：丢掉那几个，剩下的照常显示 —— 不因为一个查不到就整行退成「群聊」。 */
    @Test
    fun partiallyUnknownMembersKeepTheKnownOnes() {
        assertEquals(
            "Chief、ReleaseBot",
            roomMemberNames(listOf("session-a5000f0e", "session-deadbeef", "session-4745edd2"), bots)
        )
    }

    /**
     * **硬判据**：任何输入下都不得出现 `session-` 字串（用户明确「内部 id 一律不露」）。
     * 这条是给 AndroidTester 的机器判据留的同款断言，不只是看一个正常路径。
     */
    @Test
    fun renderedTextNeverLeaksInternalSessionIds() {
        val inputs = listOf(
            bots.map { it.id },
            listOf("session-deadbeef"),
            listOf("session-a5000f0e", "session-deadbeef", "session-cafebabe", "nope", "session-4d8741a8"),
            emptyList(),
            null
        )
        for (members in inputs) {
            val text = roomMemberNames(members, bots)
            assertFalse("副行文案泄露了内部 id: $text", text.contains("session-"))
        }
    }
}
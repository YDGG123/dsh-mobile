package com.clarklevis.dsh.android

import com.clarklevis.dsh.android.ui.gatewayDedupeSignature
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「合流 memo 键」的纯函数判据（AndroidTester 2026-11-02 要求）。
 *
 * 背景：错的是 `remember` 的**键**，不是 `mergeRoomChat` 本身——纯函数测不到键，
 * Compose 测试本机跑不了（无模拟器/设备）。所以把键抽成 [gatewayDedupeSignature]，
 * 在这里钉死三条，**并用「故意写成 size」的反向验证证明它会红**（只跑绿不算证明）。
 */
class GatewayDedupeSignatureTest {

    private fun item(id: String, seq: Int?, text: String = "t") = ConversationItem(
        id = id,
        kind = ConversationItemKind.ASSISTANT,
        title = "",
        text = text,
        epochSeconds = 1.0,
        seq = seq
    )

    /** ① 同输入两次 ⇒ 键相等（否则每次重组都重算，等于没有 memo）。 */
    @Test
    fun sameInputYieldsSameKey() {
        val items = listOf(item("room-1-7", 7), item("room-1-8", 8))
        assertEquals(gatewayDedupeSignature(items), gatewayDedupeSignature(items.toList()))
    }

    /**
     * ② **命门**：同数量、但内容（id/seq）变了 ⇒ 键必须变。
     * 「用 size 当键」的实现会在这一条变红——这正是 iOSDev 点名的坑。
     */
    @Test
    fun sameCountButDifferentContentChangesKey() {
        val before = listOf(item("room-1-7", 7), item("room-1-8", 8))
        val after = listOf(item("room-1-7", 7), item("room-1-999", 999))   // 数量不变、seq 变了
        assertNotEquals(gatewayDedupeSignature(before), gatewayDedupeSignature(after))
    }

    /** ③ items 追加 ⇒ 键变（网关新条目到达时要让合流重算）。 */
    @Test
    fun appendingItemChangesKey() {
        val before = listOf(item("room-1-7", 7))
        val after = before + item("room-1-8", 8)
        assertNotEquals(gatewayDedupeSignature(before), gatewayDedupeSignature(after))
    }

    /**
     * ④ 流式文本变化（id/seq 不变）⇒ 键**不变**。
     * 这条是"不要用整个 items 当键"的理由：否则每个 token 都重算合流（含排序）。
     * 「用 size 当键」在这里也是不变的，所以它不能替 ② 挡错——两条缺一不可。
     */
    @Test
    fun textOnlyChangeKeepsKeyStable() {
        val a = listOf(item("room-1-7", 7, text = "你"))
        val b = listOf(item("room-1-7", 7, text = "你好，世界"))
        assertEquals(gatewayDedupeSignature(a), gatewayDedupeSignature(b))
    }

    /** ⑤ 非空自证：上面几条若在空表上跑，"相等"会恒真 ⇒ 先证明样本非空。 */
    @Test
    fun sampleIsNotEmpty() {
        val items = listOf(item("room-1-7", 7), item("room-1-8", 8))
        assertTrue(items.isNotEmpty())
        assertNotEquals(0, gatewayDedupeSignature(items))
    }
}

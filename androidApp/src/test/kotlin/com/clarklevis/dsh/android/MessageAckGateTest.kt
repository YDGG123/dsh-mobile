package com.clarklevis.dsh.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送确认状态机（缺陷登记表 #14）的三条判据 —— Chief 定的：
 * ① 纯文本发送后，确认**迟到**仍能清空输入框、按钮恢复；
 * ② 请求被拒（4xx）时草稿不清空、能重发；
 * ③ 同一句不会被发两次（同一条投递只被认领一次）。
 *
 * 状态机只管"该不该认领这条确认"，草稿清不清由 `applyMessageSendResult` 的 generation 判据决定；
 * 这里断言的是它交给那边的**载荷与时机**。
 */
class MessageAckGateTest {

    private fun gate() = MessageAckGate<String>()

    /** ① 超时只撤「还在等」，身份留着：迟到确认仍能认领（这正是 #14 的病灶）。 */
    @Test
    fun lateAckStillAcceptedAfterTimeout() {
        val g = gate()
        g.begin("req-1", "hello")
        assertTrue("发起后应处于『还在等』", g.awaitingAck)

        g.timedOut()
        assertFalse("超时后『还在等』必须撤（按钮要恢复）", g.awaitingAck)
        assertEquals("超时不许撤身份", "req-1", g.inFlightRequestId)

        assertEquals("迟到确认认领不到载荷", "hello", g.accept("req-1", submissionHadNoFiles = true))
        assertNull("认领后身份必须清掉", g.inFlightRequestId)
    }

    /** ③ 同一条投递只认领一次：重复 `sent` 帧不会把草稿清两遍、也不会重复计数。 */
    @Test
    fun duplicateAckIsNotAcceptedTwice() {
        val g = gate()
        g.begin("req-1", "hello")
        assertEquals("hello", g.accept("req-1", submissionHadNoFiles = true))
        assertNull("第二次同 requestId 确认必须匹配不到", g.accept("req-1", submissionHadNoFiles = true))
    }

    /** ② 被拒/取消 = 确定没成：身份一起撤，此后再来的"迟到确认"不再认领（草稿留着让用户重发）。 */
    @Test
    fun abandonDropsIdentitySoLateAckIsIgnored() {
        val g = gate()
        g.begin("req-1", "hello")
        g.abandon()
        assertFalse(g.awaitingAck)
        assertNull(g.inFlightRequestId)
        assertNull("被拒之后的确认不该再清草稿", g.accept("req-1", submissionHadNoFiles = true))
    }

    /** 别的投递的确认不能认领到这条头上（A 的 requestId 不能吃掉 B 的结果）。 */
    @Test
    fun foreignRequestIdIsIgnored() {
        val g = gate()
        g.begin("req-1", "hello")
        assertNull(g.accept("req-other", submissionHadNoFiles = true))
        assertEquals("未命中的确认不能动状态", "req-1", g.inFlightRequestId)
        assertTrue(g.awaitingAck)
    }

    /** 老网关不回显 requestId：只有「这条投递没有文件回执」时才按 null 认领（与 iOS :3397 同判据）。 */
    @Test
    fun nullRequestIdOnlyMatchesWhenSubmissionHadNoFiles() {
        val noFiles = gate()
        noFiles.begin("req-1", "text")
        assertEquals("纯文本 + null 确认应认领", "text", noFiles.accept(null, submissionHadNoFiles = true))

        val withFiles = gate()
        withFiles.begin("req-2", "with-file")
        assertNull("带文件回执的投递不接受 null 确认", withFiles.accept(null, submissionHadNoFiles = false))
        assertEquals("req-2", withFiles.inFlightRequestId)
    }

    /** 没有在途投递时，任何确认都不认领（不该凭空清空输入框）。 */
    @Test
    fun ackWithoutInFlightIsIgnored() {
        val g = gate()
        assertNull(g.accept("req-1", submissionHadNoFiles = true))
        assertNull(g.accept(null, submissionHadNoFiles = true))
    }

    /** 新投递覆盖旧身份：用户决定不等上一条了，旧确认不会再回来清这条的草稿。 */
    @Test
    fun newSubmissionReplacesIdentity() {
        val g = gate()
        g.begin("req-1", "first")
        g.begin("req-2", "second")
        assertNull("旧身份的确认不该认领", g.accept("req-1", submissionHadNoFiles = true))
        assertEquals("second", g.accept("req-2", submissionHadNoFiles = true))
    }
}
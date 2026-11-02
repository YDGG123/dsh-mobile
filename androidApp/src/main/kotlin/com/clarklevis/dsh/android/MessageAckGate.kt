package com.clarklevis.dsh.android

/**
 * 发送确认状态机（缺陷登记表 #14）——「还在等」与「这条投递的身份」**分开**。
 *
 * ## 修的是什么（可观察症状）
 * 用户报：消息已经发出去了，界面却报「发送确认超时」，而且**输入框不清空**。
 * 根因是一个：本地超时（或断线）把「还在等」提前撤掉，而 `sent` 帧的接受条件**以「还在等」
 * 为必要条件** ⇒ 迟到的成功确认被整支丢掉：草稿永远不清、按钮状态也不对。
 *
 * ## 两条状态，各自的生命周期
 * - `awaitingAck`（还在等）：超时/断线**立刻撤**——按钮要马上恢复可用，不能卡住用户。
 * - `requestId`（身份）：**只有**三件事能撤——成功确认、明确失败（取消/被拒/错误帧）、换下一条投递。
 *   超时不撤，所以"迟到的成功"仍然认领得回来。
 *
 * ## 为什么超时不撤身份是对的
 * 超时只说明**我们这边不等了**，不说明桌面端没收到。把身份也撤掉，就等于赌"一定没发出去"，
 * 而这正是 #14 的病灶。
 *
 * ## 重复确认只认领一次
 * `accept()` 命中即清身份 ⇒ 同一条重复 `sent` 帧第二次进来匹配不到任何投递，不会把草稿清两遍、
 * 也不会重复计数（iOS `ceb0dfa` 修的是同一个坑）。
 *
 * @param T 被跟踪的投递载荷（Android 侧是 `AndroidSharedStateHolder.MessageSubmission`）
 */
internal class MessageAckGate<T> {

    /** 界面语义：「还在等确认」。超时/断线就撤，**不影响** [inFlightRequestId]。 */
    var awaitingAck: Boolean = false
        private set

    /** 在途投递的身份；null = 没有待认领的投递。 */
    var inFlightRequestId: String? = null
        private set

    private var inFlightPayload: T? = null

    val payload: T? get() = inFlightPayload

    /** 发起一条投递。旧身份被覆盖（用户已经决定不再等上一条）。 */
    fun begin(requestId: String, payload: T) {
        awaitingAck = true
        inFlightRequestId = requestId
        inFlightPayload = payload
    }

    /** 本地超时 / 断线：**只撤「还在等」**，身份留着等迟到的确认。 */
    fun timedOut() {
        awaitingAck = false
    }

    /** 明确失败（取消 / 被拒 / 错误帧）：这条路不会再来了，身份一起撤，草稿留着让用户重发。 */
    fun abandon() {
        awaitingAck = false
        inFlightRequestId = null
        inFlightPayload = null
    }

    /**
     * 收到 `sent` 帧：只认**身份相等**的那一条。
     *
     * 两个兼容分支：
     * - `frameRequestId == null` 且这条投递没有文件回执 ⇒ 老网关不回显 requestId 时仍能认领
     *   （与 iOS `:3397` 同一判据：`submittedUploadIDs.isEmpty && frame.requestId == nil`）。
     * - 其余情况一律要求相等，避免 A 的确认认领到 B 头上。
     *
     * 命中即清身份，返回被认领的载荷；未命中返回 null，状态**不动**。
     */
    fun accept(frameRequestId: String?, submissionHadNoFiles: Boolean): T? {
        val expected = inFlightRequestId ?: return null
        val matches = frameRequestId == expected || (frameRequestId == null && submissionHadNoFiles)
        if (!matches) return null
        val accepted = inFlightPayload
        abandon()
        return accepted
    }
}
package com.clarklevis.dsh.shared.teamfeed

/**
 * D1 段 1 的传输接口 —— 帧序以网关 `team-feed.mjs` 为准：
 * WS `/mobile/v1/team`，首帧 `{"type":"auth","token":…}`（**不需要** conversationId）→
 * 服务端 `ready` → `snapshot` → `delta(base)/order` → 卡片帧；握手声明 `chunked-frames-v1`
 * 才会有分块帧（重组在客户端）。
 *
 * 平台实现（androidMain 用 OkHttp WebSocket）只负责「收发字节」，**解析与状态机都不在这层**：
 * - 收到文本帧 ⇒ 回调 [Listener.onFrameText]（原样交回，解析在共享层）；
 * - 连接建立 ⇒ [Listener.onOpen]（此后客户端发 auth 帧）；
 * - 断开/错误 ⇒ [Listener.onClosed]（原因原样上抛，重连策略归上层）。
 */
interface TeamFeedTransport {
    fun connect(endpoint: String, token: String, listener: Listener)
    fun disconnect()

    interface Listener {
        fun onOpen()
        fun onFrameText(text: String)
        fun onClosed(reason: String?)
    }
}

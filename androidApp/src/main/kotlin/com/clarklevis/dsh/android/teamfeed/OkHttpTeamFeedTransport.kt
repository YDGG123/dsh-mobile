package com.clarklevis.dsh.android.teamfeed

import android.util.Log
import com.clarklevis.dsh.shared.teamfeed.TeamFeedChunkInbox
import com.clarklevis.dsh.shared.teamfeed.TeamFeedTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * D1 段 1 的 Android 实现：OkHttp WebSocket。
 *
 * 只做「收发字节」：auth 帧在 [onOpen] 发（首帧必须是 `{"type":"auth","token":…}`，
 * 网关契约）；文本帧原样交 [TeamFeedTransport.Listener.onFrameText]，解析归共享层。
 * 重连策略不在这层（上层按 Phase 决定），这层只如实上报 onClosed。
 */
class OkHttpTeamFeedTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : TeamFeedTransport {

    private companion object {
        const val TAG = "TeamFeedTransport"
    }

    private var webSocket: WebSocket? = null
    private var closedByUs = false

    /**
     * chunk 感知收帧（缺口 2 闭环，2026-11-03）：声明了 `chunked-frames-v1` 之后网关会对
     * >512KB 的帧发 chunk，**必须在这里重组**——否则大帧（avatar batch 带 data URL 最容易超）
     * 会被静默丢弃（AndroidTester 复核指出的「确定地丢」）。与 iOS 同层：FrameReassembler
     * 也在 transport 收帧处。流按连接计，[connect] 时重置。
     */
    private val inbox = TeamFeedChunkInbox()

    override fun connect(endpoint: String, token: String, listener: TeamFeedTransport.Listener) {
        disconnect()
        closedByUs = false
        inbox.reset()
        val request = Request.Builder().url(endpoint).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                    // 契约：首帧 auth（**不需要** conversationId）；capabilities 声明 chunked-frames-v1
                    // 之后网关才可能发 chunk 帧（chunked.mjs:11；server.mjs:560 按声明置 state.chunked）。
                    // 失败由服务端关连接，onClosed 会上抛。
                    val sent = webSocket.send(
                        "{\"type\":\"auth\",\"token\":\"$token\",\"capabilities\":[\"chunked-frames-v1\"]}",
                    )
                    if (!sent) listener.onClosed("auth frame not sent")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val result = inbox.onText(text)) {
                        is TeamFeedChunkInbox.Result.Deliver -> listener.onFrameText(result.text)
                        is TeamFeedChunkInbox.Result.Complete -> listener.onFrameText(result.json)
                        TeamFeedChunkInbox.Result.Pending -> Unit
                        is TeamFeedChunkInbox.Result.Failed ->
                            // 流已丢弃：记日志，快照重拉归会话层（段 5 接线时挂上）。
                            Log.w(TAG, "chunk reassembly failed: ${result.error}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!closedByUs) listener.onClosed(t.message ?: "transport failure")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closedByUs) listener.onClosed(reason.ifEmpty { "closed code=$code" })
                }
            },
        )
    }

    override fun disconnect() {
        closedByUs = true
        webSocket?.close(1000, "client disconnect")
        webSocket = null
    }
}

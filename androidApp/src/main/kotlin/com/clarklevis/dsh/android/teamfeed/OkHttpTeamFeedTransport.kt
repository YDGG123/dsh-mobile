package com.clarklevis.dsh.android.teamfeed

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

    private var webSocket: WebSocket? = null
    private var closedByUs = false

    override fun connect(endpoint: String, token: String, listener: TeamFeedTransport.Listener) {
        disconnect()
        closedByUs = false
        val request = Request.Builder().url(endpoint).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                    // 契约：首帧 auth（**不需要** conversationId）；失败由服务端关连接，onClosed 会上抛。
                    val sent = webSocket.send("{\"type\":\"auth\",\"token\":\"$token\"}")
                    if (!sent) listener.onClosed("auth frame not sent")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onFrameText(text)
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

package com.clarklevis.dsh.android.teamfeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.shared.teamfeed.TeamFeedChunkInbox
import com.clarklevis.dsh.shared.teamfeed.TeamFeedTransport
import com.clarklevis.dsh.shared.teamfeed.TeamFeedChunkReassembler
import com.clarklevis.dsh.shared.teamfeed.TeamFeedFrame
import com.clarklevis.dsh.shared.teamfeed.TeamFeedReducer
import com.clarklevis.dsh.shared.teamfeed.parseTeamFeedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * D1 段 5：团队 feed 的 Android 客户端——把 [OkHttpTeamFeedTransport] 收到的帧泵进
 * [TeamFeedReducer]，向 UI 暴露 Compose 状态。
 *
 * 帧序契约（网关 server.mjs:555-570 / team-feed.mjs）：auth（含 chunked-frames-v1 声明）→
 * ready → snapshot → delta(base)/order → card*。`delta.base != seq` ⇒ reducer 置 needSnapshot，
 * 这里按最朴素的重同步：**断开重连**（服务端对新 auth 必发整帧快照）。
 *
 * 重连：指数退避 5s→30s 封顶；`stop()` 后不再自动重连（换网关/退出页面由上层决定生命周期）。
 * resume（零往返增量续连）归下批：先保证"断了能自己回来"。
 */
class TeamFeedController(
    private val scope: CoroutineScope,
    private val transport: TeamFeedTransport = OkHttpTeamFeedTransport(),
) {
    enum class Phase { IDLE, CONNECTING, LIVE, OFFLINE }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var offlineReason: String? by mutableStateOf(null)
        private set
    var state by mutableStateOf(TeamFeedReducer.TeamFeedState())
        private set

    private val reducer = TeamFeedReducer()
    private val inbox = TeamFeedChunkInbox(TeamFeedChunkReassembler())
    private var endpoint: String? = null
    private var token: String? = null
    private var connectJob: Job? = null
    private var stopped = false
    private var attempt = 0

    fun start(newEndpoint: String, newToken: String) {
        stopped = false
        if (endpoint == newEndpoint && token == newToken && connectJob != null) return
        endpoint = newEndpoint
        token = newToken
        connect()
    }

    fun stop() {
        stopped = true
        connectJob?.cancel()
        transport.disconnect()
        phase = Phase.IDLE
    }

    private fun connect() {
        val ep = endpoint ?: return
        val tk = token ?: return
        if (stopped) return
        phase = Phase.CONNECTING
        connectJob = scope.launch {
            transport.connect(
                ep,
                tk,
                object : TeamFeedTransport.Listener {
                    override fun onOpen() {
                        // auth 由 transport 发（含 chunked-frames-v1 声明）；等服务端 ready/snapshot。
                    }

                    override fun onFrameText(text: String) {
                        when (val result = inbox.onText(text)) {
                            is TeamFeedChunkInbox.Result.Deliver -> consume(result.text)
                            is TeamFeedChunkInbox.Result.Complete -> consume(result.json)
                            TeamFeedChunkInbox.Result.Pending -> Unit
                            is TeamFeedChunkInbox.Result.Failed -> android.util.Log.w(
                                "TeamFeedController",
                                "chunk reassembly failed: ${result.error} → resync",
                            )
                                .also { reconnect() }
                        }
                    }

                    override fun onClosed(reason: String?) {
                        if (stopped) return
                        android.util.Log.w("TeamFeedController", "feed closed: $reason")
                        reconnect()
                    }
                },
            )
        }
    }

    private fun consume(text: String) {
        val frame = parseTeamFeedFrame(text) ?: return
        when (frame) {
            is TeamFeedFrame.Ready -> {
                phase = Phase.LIVE
                attempt = 0
            }
            else -> {
                val outcome = reducer.apply(frame)
                state = reducer.state
                if (outcome is TeamFeedReducer.Outcome.NeedSnapshot) {
                    // 最朴素的重同步：断开重连，服务端对新 auth 重发整帧快照。
                    android.util.Log.w("TeamFeedController", "resync: ${outcome.reason}")
                    reconnect()
                }
            }
        }
    }

    private fun reconnect() {
        if (stopped) return
        phase = Phase.OFFLINE
        offlineReason = "feed disconnected"
        transport.disconnect()
        val backoff = (5_000L shl attempt.coerceAtMost(3)).coerceAtMost(30_000L)
        attempt += 1
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(backoff)
            connect()
        }
    }
}

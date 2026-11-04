package com.clarklevis.dsh.android.teamfeed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.shared.teamfeed.TeamFeedChunkInbox
import com.clarklevis.dsh.shared.teamfeed.TeamFeedKeyValueStore
import com.clarklevis.dsh.shared.teamfeed.TeamFeedTransport
import com.clarklevis.dsh.shared.teamfeed.TeamFeedChunkReassembler
import com.clarklevis.dsh.shared.teamfeed.TeamFeedFrame
import com.clarklevis.dsh.shared.teamfeed.TeamFeedReducer
import com.clarklevis.dsh.shared.teamfeed.TeamRosterCache
import com.clarklevis.dsh.shared.teamfeed.parseTeamFeedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    /** vc61 ⑥：名册水合缓存落盘层（Android 文件实现）；null = 不接缓存（单测/预览）。 */
    private val cacheStore: TeamFeedKeyValueStore? = null,
) {
    enum class Phase { IDLE, CONNECTING, LIVE, OFFLINE }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var offlineReason: String? by mutableStateOf(null)
        private set
    var state by mutableStateOf(TeamFeedReducer.TeamFeedState())
        private set

    /** vc61 ⑥：true = 当前 state 来自水合缓存（非实时）——UI 渲染行但强制降级语义（iOS 同规）。 */
    var hydrated by mutableStateOf(false)
        private set

    private val reducer = TeamFeedReducer()
    private val rosterCache = cacheStore?.let(::TeamRosterCache)
    private val inbox = TeamFeedChunkInbox(TeamFeedChunkReassembler())
    private var endpoint: String? = null
    private var token: String? = null
    private var connectJob: Job? = null
    private var stopped = false
    private var attempt = 0
    private var cacheSaveInFlight = false
    private var cacheSavePending = false

    init {
        // vc61 ⑥：构造即水合——切 tab 回来/重启后**立刻**有头像（iOS `hydrateRosterCacheIfNeeded`
        // 同规：rows 非空跳过、命中即 degraded=true + phase=CONNECTING、每个 return 点留痕）。
        hydrateRosterCacheIfNeeded()
    }

    fun start(newEndpoint: String, newToken: String) {
        stopped = false
        if (endpoint == newEndpoint && token == newToken && connectJob?.isActive == true) return
        if (endpoint != newEndpoint || token != newToken) {
            // 端点/token 变了（init 默认值 → preferences 真值）：断开旧 socket、撤旧重连，
            // 否则旧连接与重连循环永远指着 127.0.0.1（vc56 真机「Bot 页全空闲」的根因）。
            connectJob?.cancel()
            connectJob = null
            transport.disconnect()
        }
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
                android.util.Log.i("TeamFeedController", "feed ready: phase=LIVE (type==\"ready\" dispatch)")
            }
            is TeamFeedFrame.Error -> android.util.Log.w(
                "TeamFeedController",
                "gateway error frame: code=${frame.code} message=${frame.message}",
            )
            else -> {
                val outcome = reducer.apply(frame)
                state = reducer.state
                // vc62：真快照落地 ⇒ 退出缓存态——但**非空快照才清**（补充 B）：空快照保持现状
                // （不清也不置），否则 hydrated 被清后降级横幅语义漂移、界面退化成空。
                if (frame is TeamFeedFrame.Snapshot && state.rows.isNotEmpty()) hydrated = false
                if (outcome is TeamFeedReducer.Outcome.Applied) scheduleCacheSave()
                if (outcome is TeamFeedReducer.Outcome.NeedSnapshot) {
                    // 最朴素的重同步：断开重连，服务端对新 auth 重发整帧快照。
                    android.util.Log.w(TAG, "resync: ${outcome.reason}")
                    reconnect()
                }
            }
        }
    }

    /**
     * vc61 ⑥：水合上次名册（含 glyph）——iOS `hydrateRosterCacheIfNeeded` 同规
     * （GatewayTeamFeed:215-252）：rows 非空跳过；命中即 `degraded = true`（**缓存不是实时态**，
     * 绝不把旧 activity 显示成当前）+ phase=CONNECTING，行照常渲染（降级横幅沿用）；
     * 每个 return 点都留痕（iOS 2026-10-27 缓存排障纪律）。
     */
    private fun hydrateRosterCacheIfNeeded() {
        val cache = rosterCache ?: return
        if (state.rows.isNotEmpty()) {
            android.util.Log.i(TAG, "roster cache skipped: rows non-empty (${state.rows.size})")
            return
        }
        val cached = cache.hydrate() ?: run {
            android.util.Log.i(TAG, "roster cache miss")
            return
        }
        state = cached.state.copy(degraded = true)
        hydrated = true
        phase = Phase.CONNECTING
        android.util.Log.i(TAG, "roster cache hydrated rows=${state.rows.size}")
    }

    /** vc61 ⑥：回写单飞合并（burst 期间至多一个在飞 + 一个挂起），固定 IO 线程（红线：回写不阻塞 UI）。 */
    private fun scheduleCacheSave() {
        val cache = rosterCache ?: return
        // vc62：空名册不写盘——Ready/Error 帧 Applied 时 rows 尚空，会把上次的好缓存覆盖成空
        // （用户「列表没有缓存」的根因：开一下 Bot 页就切走即触发 Ready→断线）。
        if (state.rows.isEmpty()) return
        val launchNow = synchronized(this) {
            if (cacheSaveInFlight) {
                cacheSavePending = true
                false
            } else {
                cacheSaveInFlight = true
                true
            }
        }
        if (!launchNow) return
        scope.launch(Dispatchers.IO) {
            try {
                cache.save(state)
            } finally {
                val again = synchronized(this) {
                    val pending = cacheSavePending
                    cacheSavePending = false
                    cacheSaveInFlight = pending
                    pending
                }
                if (again) scheduleCacheSave()
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

    private companion object {
        const val TAG = "TeamFeedController"
    }
}

package com.clarklevis.dsh.android

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.android.platform.AndroidBridgeSessionStore
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.domain.chatMessagesFromHistory
import com.clarklevis.dsh.shared.domain.TeamUiState
import com.clarklevis.dsh.shared.facade.SharedBridgeTeamStore
import com.clarklevis.dsh.shared.protocol.BridgeCardQuestion
import com.clarklevis.dsh.shared.protocol.BridgeDeviceSession
import com.clarklevis.dsh.shared.protocol.BridgeServerInfo
import com.clarklevis.dsh.shared.protocol.decodeBridgeErrorEnvelope
import com.clarklevis.dsh.shared.protocol.decodeTeamUiState
import com.clarklevis.dsh.shared.protocol.encodeBridgeSession
import com.clarklevis.dsh.shared.protocol.parseBridgeCardsFrame
import com.clarklevis.dsh.shared.protocol.parseBridgeInfo
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Mobile Bridge（`/mobile/v1`）平台适配层 —— Android 侧等价物。
 *
 * 分工照 shared 层 `SharedBridgeTeamStore` 的注释：**HTTP 执行留在平台层**，
 * 这里只做三件事：
 * 1. 拉 `/info`（免鉴权）+ `/state` + `/conversations`（Bearer），8s 轮询；
 * 2. 把三份原始 JSON 交给 store 合流，再从 `snapshot()` 回解 [TeamUiState]；
 * 3. 提供 `paired` 帧里 `bridgeSession` 的落地入口（U5 对称配对，不用二次配对）。
 *
 * 尚未实现（对齐 iOS `BridgeTeamAdapter.swift` 的其余职责，按 C2/D 组推进）：
 * token 主动刷新、看门狗、写操作 effect 执行、设备管理、bridge 聊天入口。
 */
@Stable
internal class AndroidBridgeTeamAdapter(
    context: Context,
    private val scope: CoroutineScope,
    /**
     * 对齐 iOS `BridgeTransport.allowInsecureLoopback`（iOS 生产两处调用点均显式 false）：
     * 桥根 origin 默认 https-only；明文 http 仅 loopback host 且显式放行时接受，
     * LAN 明文（如 3081）与 iOS 生产语义一致默认拒绝。
     */
    private val allowInsecureLoopback: Boolean = false,
) {
    private val sessionStore = AndroidBridgeSessionStore(context)
    private val store = SharedBridgeTeamStore(sessionStore)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** store 合流后的团队状态（bots/rooms/phase…）。 */
    var state: TeamUiState by mutableStateOf(TeamUiState())
        private set

    /** Mobile Bridge 的 HTTP 根（由当前网关 endpoint 推导），日志与排障用。 */
    var origin: String? by mutableStateOf(null)
        private set

    /** 最近一次 `/info` 的 serverName（C1 的最小可见证据之一）。 */
    var serverInfo: BridgeServerInfo? by mutableStateOf(null)
        private set

    var lastError: String? by mutableStateOf(null)
        private set

    var lastRefreshAtMillis: Long by mutableLongStateOf(0L)
        private set

    /**
     * E1：桥接卡片帧（`GET /mobile/v1/cards` 快照，与 WS 同源）。
     * 解析语义在 shared（`parseBridgeCardsFrame`）；展示是本地的——bridge 没有
     * question-answer action，卡片只能看、要在电脑端处理（iOS 同款纪律）。
     */
    var pendingCards: List<BridgeCardQuestion> by mutableStateOf(emptyList())
        private set

    /**
     * 群聊消息流（vc43 ③）：conversationId → KMP `BridgeChatSnapshot` JSON。
     * **只读**——不接 bridge 的 send/submissions（room 的发送一律走网关，避免一次发言提交两次，
     * iOSCoder 2026-11-02 指出的坑）。合并/去重逻辑用 commonMain 的 chatApplyEvent。
     */
    var chatMessagesByConversation: Map<String, String> by mutableStateOf(emptyMap())
        private set

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    private var loopJob: Job? = null
    private var loopOrigin: String? = null
    private var endpoint: String? = null

    /** 串行化 refreshOnce：手动刷新与 8s 轮询不再交错进两次 refreshTeam。 */
    private val refreshGate = Mutex()

    val hasSession: Boolean get() = store.currentAccessToken() != null

    /** 网关 endpoint 变化（换网关/重新配对）时调用；ws(s) → http(s) 推导桥根并过 origin 策略门。 */
    fun updateGatewayEndpoint(value: String) {
        if (value == endpoint) return
        endpoint = value
        val derived = originFromEndpoint(value)
        if (derived != null && !isAcceptableBridgeOrigin(derived, allowInsecureLoopback)) {
            Log.w(
                TAG,
                "bridge origin rejected by policy: $derived " +
                    "(endpoint=$value, allowInsecureLoopback=$allowInsecureLoopback)"
            )
            origin = null
            return
        }
        origin = derived
        Log.i(TAG, "gateway endpoint=$value -> bridge origin=$origin")
    }

    /** 从 `paired` 帧拿到 bridgeSession 时调用：落盘 + 启动轮询。 */
    fun adoptSession(session: BridgeDeviceSession) {
        val json = encodeBridgeSession(session)
        val result = store.sessionPaired(json)
        if (!result.isSuccess) {
            lastError = result.errorMessage ?: result.errorCode
            Log.w(TAG, "adopt bridge session failed: ${result.errorCode} ${result.errorMessage}")
            return
        }
        Log.i(TAG, "bridge session adopted device=${session.deviceId} server=${session.serverId}")
        start()
    }

    /** 幂等启动：恢复落盘会话 → 立刻刷新一次 → 8s 轮询。 */
    /** true = 本地有 bridge 会话密文但解不开（Keystore 失效）：UI 应显示「需要重新配对」，不是空列表。 */
    @Volatile var restoreFailed: Boolean = false
        private set

    fun start() {
        val restore = store.restoreSession()
        restoreFailed = sessionStore.restoreFailed
        if (!restore.isSuccess) {
            Log.w(TAG, "restore bridge session failed: ${restore.errorCode} ${restore.errorMessage}")
        }
        publishState()
        if (!hasSession) {
            Log.i(TAG, "bridge polling skipped: no device session (needs pairing)")
            return
        }
        if (loopJob?.isActive == true) {
            if (loopOrigin == origin) return
            Log.i(TAG, "bridge origin changed, restarting polling: $loopOrigin -> $origin")
            loopJob?.cancel()
            loopJob = null
        }
        loopOrigin = origin
        loopJob = scope.launch {
            // 对齐 iOS startPolling（BridgeTeamAdapter.swift:827-847）：
            // ① 可取消循环（`while(true)` → `isActive`）；② 失败在循环体内处理、非静默；
            // ③ 离线指数退避 750ms→15s（在线仍 8s）；④ 进后台由宿主 stop() 整支取消。
            var failureAttempt = 0
            while (currentCoroutineContext().isActive) {
                refreshOnce()
                val offline = lastError != null
                val delayMillis = if (offline) {
                    (750L shl failureAttempt.coerceAtMost(5)).coerceAtMost(15_000L)
                } else {
                    POLL_INTERVAL_MILLIS
                }
                if (offline) failureAttempt++ else failureAttempt = 0
                delay(delayMillis)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * 拉取某群聊的消息历史（REST `GET /mobile/v1/conversations/{id}/messages?limit=50[&cursor=]`），
     * 交给 KMP `chatMessagesFromHistory` 解析成 `BridgeChatSnapshot` JSON 后缓存。
     *
     * @return true = 成功（含"服务端返回空列表"）；false = 失败，`lastError` 已置原因。
     */
    suspend fun loadChatMessages(conversationId: String, cursor: String? = null): Boolean {
        val base = origin ?: run { lastError = "no-bridge-origin"; return false }
        val token = store.currentAccessToken() ?: run { lastError = "no-device-session"; return false }
        val url = buildString {
            append(base).append("/mobile/v1/conversations/").append(conversationId).append("/messages?limit=50")
            if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
        }
        return when (val result = fetch(url, token)) {
            is BridgeFetch.Ok -> {
                val snapshotJson = runCatching { chatMessagesFromHistory(result.body) }.getOrElse { error ->
                    lastError = "chat_parse_failed: ${error.javaClass.simpleName}"
                    return false
                }
                chatMessagesByConversation = chatMessagesByConversation + (conversationId to snapshotJson)
                true
            }
            is BridgeFetch.HttpError -> failWith(result).let { false }
            else -> fail("chat_messages_failed", "群聊消息拉取失败").let { false }
        }
    }

    /** 手动刷新一次（点按重试 / 进入团队页时调用）。 */
    fun refreshNow() {
        scope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() = refreshGate.withLock {
        // 只信已过策略门的 origin；不在取用时二次推导（那会绕过 updateGatewayEndpoint 的校验）。
        val base = origin
        if (base == null) {
            lastError = "no-bridge-origin"
            return@withLock
        }
        isRefreshing = true
        try {
            val info = fetch("$base/mobile/v1/info", token = null)
            val infoJson = when (info) {
                is BridgeFetch.Ok -> info.body
                is BridgeFetch.HttpError -> return@withLock failWith(info)
                else -> return@withLock fail("network_error", "/info 传输失败")
            }
            serverInfo = runCatching { parseBridgeInfo(infoJson) }.getOrNull()
            val token = store.currentAccessToken()
            if (token.isNullOrBlank()) return@withLock fail("no-device-session", "本地无移动设备会话")
            val state = fetch("$base/mobile/v1/state", token)
            val stateJson = when (state) {
                is BridgeFetch.Ok -> state.body
                is BridgeFetch.HttpError -> return@withLock failWith(state)
                else -> return@withLock fail("network_error", "/state 传输失败")
            }
            val conversations = fetch("$base/mobile/v1/conversations", token)
            val conversationsJson = when (conversations) {
                is BridgeFetch.Ok -> conversations.body
                is BridgeFetch.HttpError -> return@withLock failWith(conversations)
                else -> return@withLock fail("network_error", "/conversations 传输失败")
            }
            val result = store.refreshTeam(infoJson, stateJson, conversationsJson)
            if (!result.isSuccess) {
                lastError = result.errorMessage ?: result.errorCode
                Log.w(TAG, "team refresh rejected: ${result.errorCode} ${result.errorMessage}")
            } else {
                lastError = null
            }
            publishState()
            refreshCards(base, token)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (unexpected: Throwable) {
            // 兜底（AndroidTester 2026-11-02）：这条 while(true) 轮询**只有 finally、没有 catch**，
            // 而它跑在 applicationScope（SupervisorJob、无 CoroutineExceptionHandler）里——
            // 一次解析/状态机异常（Serialization / IllegalState / NPE）就会逃逸成进程崩溃，
            // 与用户「挂着不动会闪退」的形态吻合。这里把它降级成 lastError + 继续轮询。
            lastError = "refresh_unhandled: ${unexpected.javaClass.simpleName}"
            Log.w(TAG, "refresh unhandled: ${unexpected.javaClass.simpleName}: ${unexpected.message}")
        } finally {
            isRefreshing = false
        }
    }

    /**
     * 卡片快照是第四个端点：失败**不拖垮**名册刷新（名册已成功），只记日志保留旧卡。
     * 401 场景下 state/conversations 已先行判死，这里不会误吞判死信号。
     */
    private suspend fun refreshCards(base: String, token: String) {
        val snapshot = fetch("$base/mobile/v1/cards", token)
        when (snapshot) {
            is BridgeFetch.Ok -> {
                pendingCards = parseBridgeCardsFrame(snapshot.body)
                Log.i(TAG, "cards snapshot parsed: ${pendingCards.size} card(s)")
            }
            is BridgeFetch.HttpError -> Log.w(TAG, "cards snapshot failed: ${snapshot.status}（保留旧卡）")
            else -> Log.w(TAG, "cards snapshot transport failure（保留旧卡）")
        }
    }

    /** 非 2xx：从错误信封取 wire 码（对齐 iOS `BridgeResponseStatus` → `decodeBridgeErrorEnvelope`）。 */
    private fun failWith(response: BridgeFetch.HttpError) {
        val error = decodeBridgeErrorEnvelope(response.status, response.body)
        fail(error.code, error.message ?: "HTTP ${response.status}")
    }

    private fun fail(code: String, message: String? = null) {
        lastError = if (message.isNullOrBlank()) code else "$code: $message"
        store.refreshFailed(code, message)
        publishState()
    }

    private suspend fun fetch(url: String, token: String?): BridgeFetch = withContext(Dispatchers.IO) {
        if (!isBoundUrl(url)) {
            Log.w(TAG, "blocked unbound request: $url (origin=$origin)")
            return@withContext BridgeFetch.Blocked
        }
        val request = Request.Builder()
            .url(url)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .get()
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.i(TAG, "GET ${request.url.encodedPath} -> ${response.code} (${body.length}B)")
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET ${request.url.encodedPath} status=${response.code} body=${body.take(240)}")
                    BridgeFetch.HttpError(response.code, body)
                } else {
                    BridgeFetch.Ok(body)
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "GET ${request.url.encodedPath} failed: ${error.message}")
            BridgeFetch.TransportFailed
        }
    }

    private fun publishState() {
        val snapshotJson = store.snapshot().snapshotJson ?: return
        val decoded = runCatching { decodeTeamUiState(snapshotJson) }.getOrNull() ?: return
        state = decoded
        lastRefreshAtMillis = System.currentTimeMillis()
        Log.i(
            TAG,
            "team state phase=${decoded.phase} bots=${decoded.bots.size} " +
                "rooms=${decoded.rooms.size} sessions=${decoded.sessions.size} " +
                "names=${decoded.bots.take(5).joinToString(",") { it.name }}"
        )
        if (decoded.phase == TeamPhase.NEEDS_PAIRING) {
            Log.i(TAG, "team phase NEEDS_PAIRING: 需要重新配对桥接设备")
            sessionStore.clear()
        }
    }

    companion object {
        private const val TAG = "DshBridgeTeam"
        private const val POLL_INTERVAL_MILLIS = 8_000L
        private const val CONNECT_TIMEOUT_SECONDS = 8L
        private const val READ_TIMEOUT_SECONDS = 12L
        private const val CALL_TIMEOUT_SECONDS = 15L

        /**
         * 网关 WebSocket endpoint → 同一主机的 HTTP 根。
         * `ws://10.0.2.2:3081/ws/mobile` → `http://10.0.2.2:3081`；
         * `wss://host` → `https://host`。
         */
        internal fun originFromEndpoint(endpoint: String): String? {
            val trimmed = endpoint.trim()
            if (trimmed.isEmpty()) return null
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = when (trimmed.substring(0, schemeEnd).lowercase()) {
                "ws", "http" -> "http"
                "wss", "https" -> "https"
                else -> return null
            }
            val rest = trimmed.substring(schemeEnd + 3)
            val authority = rest.substringBefore('/').substringBefore('?')
            if (authority.isEmpty()) return null
            return "$scheme://$authority"
        }

        /**
         * origin 策略门（对齐 iOS `BridgeTransport.swift:41`）：https 直接过；
         * 明文 http 仅 loopback host 且 allowInsecureLoopback=true 时放行。
         * iOS 生产两处调用均显式 false ⇒ 生产语义 https-only，Android 同默认。
         */
        internal fun isAcceptableBridgeOrigin(origin: String?, allowInsecureLoopback: Boolean): Boolean {
            val parsed = runCatching { origin?.toHttpUrlOrNull() }.getOrNull() ?: return false
            return when (parsed.scheme) {
                "https" -> true
                "http" -> allowInsecureLoopback && parsed.host.isLoopbackHost()
                else -> false
            }
        }

        private fun String.isLoopbackHost(): Boolean {
            val host = lowercase().trimStart('[').trimEnd(']')
            return host == "localhost" || host == "::1" || host.startsWith("127.")
        }

        /** origin 绑定：请求必须与配对 origin 同 scheme/host/port，且路径落在 `/mobile/v1/` 下（镜像 iOS LocalTLS.validateLocalURL 的约束面）。 */
        internal fun isBoundUrl(url: String, boundOrigin: String?): Boolean {
            val base = boundOrigin ?: return false
            val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return false
            val origin = runCatching { base.toHttpUrlOrNull() }.getOrNull() ?: return false
            val sameAuthority = parsed.scheme == origin.scheme &&
                parsed.host == origin.host &&
                parsed.port == origin.port
            return sameAuthority &&
                (parsed.encodedPath == "/mobile/v1" || parsed.encodedPath.startsWith("/mobile/v1/"))
        }
    }

    private fun isBoundUrl(url: String): Boolean = isBoundUrl(url, origin)
}

/** fetch 三态结果：HTTP 非 2xx 必须带出 status+body，错误信封解码（→ 判死重配链）才有着落。 */
private sealed interface BridgeFetch {
    data class Ok(val body: String) : BridgeFetch
    data class HttpError(val status: Int, val body: String) : BridgeFetch
    data object TransportFailed : BridgeFetch
    data object Blocked : BridgeFetch
}

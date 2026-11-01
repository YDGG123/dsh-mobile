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
import com.clarklevis.dsh.shared.domain.mergeChatSnapshots
import com.clarklevis.dsh.shared.domain.chatMessagesFromHistoryPages
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

    /**
     * **给用户看的**错误（人话）。技术 code 一律不进这里——
     * 截图实测 `no-device-session: 本地无移动设备会话` 就是 code 外露（AndroidTester/iOSDev 2026-11-02 定位）。
     */
    var lastError: String? by mutableStateOf(null)
        private set

    /** 技术错误码，只进日志与「上次崩溃日志」，**不参与 UI 渲染**。 */
    var lastErrorCode: String? by mutableStateOf(null)
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

    /**
     * 当前**正在看的**群聊 conversationId（由会话页设置/清除，故 setter 开放）。
     * 轮询时若它非空，就顺带增量拉最新一页并**合并**进已有快照——
     * 否则停留在群聊里收不到成员新发言，只能退出重进才看到（与 iOS 的常驻引擎不对齐）。
     */
    var activeChatConversationId: String? by mutableStateOf(null)

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    private var loopJob: Job? = null
    private var loopOrigin: String? = null

    /**
     * 主动换凭证（对齐 iOS `startTokenRefreshLoop`，`BridgeTeamAdapter.swift:852-875`）：
     * 拿到 `/info` 公告的 TTL 就按 90% 排；老网关没有该字段则 14 分钟兜底。
     * 单飞与永久失效判定在 KMP（`beginTokenRefresh` / `tokenRefreshRejected`），这里不另做并发控制。
     */
    private var proactiveRefreshIntervalMs: Long =
        (BridgeTokenTtl.DEFAULT_INTERVAL_SECONDS * 1000).toLong()
    private var lastTokenRefreshAtMs: Long = 0L
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
        applyEffects(result)
        // 诊断（用户报「每次重装都要重新配对」）：这条日志要能区分三种结局——
        // ① 落盘成功 ② 解析/绑定失败（不落盘）③ 落盘了但读不出来。日志里带 deviceId/serverId 便于与网关侧比对。
        Log.i(
            TAG,
            "adoptSession device=${session.deviceId.take(8)} server=${session.serverId.take(8)} " +
                "ok=${result.isSuccess} code=${result.errorCode ?: "-"}"
        )
        if (!result.isSuccess) {
            lastError = result.errorMessage ?: HUMAN_GENERIC_FAILURE
            lastErrorCode = result.errorCode
            Log.w(TAG, "adopt bridge session failed: ${result.errorCode} ${result.errorMessage}")
            return
        }
        // 立刻回读一次：写入"成功"但读不出来（加密失败被静默吞掉）是这条链上最隐蔽的形态。
        val persisted = sessionStore.loadJson() != null
        Log.i(TAG, "bridge session adopted; persisted-now=$persisted restoreFailed=${sessionStore.restoreFailed}")
        if (!persisted) {
            Log.w(TAG, "bridge session write did NOT persist (saveJson silently failed?)")
        }
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
                maybeRefreshTokenProactively()
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
    suspend fun loadChatMessages(conversationId: String, maxPages: Int = 8, merge: Boolean = false): Boolean {
        val base = origin ?: run { fail("no-bridge-origin", HUMAN_NO_ORIGIN); return false }
        val token = store.currentAccessToken() ?: run { fail("no-device-session", HUMAN_NOT_PAIRED); return false }
        val pages = ArrayList<String>()
        var cursor: String? = null
        var pageIndex = 0
        while (pageIndex < maxPages) {
            val url = buildString {
                append(base).append("/mobile/v1/conversations/").append(conversationId).append("/messages?limit=50")
                // cursor 是**不透明**的（含 conversationId，服务端会校验），一律原样回传，不自己拼。
                if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
            }
            when (val result = authorizedFetch(url)) {
                is BridgeFetch.Ok -> {
                    pages += result.body
                    // 拿到 cursor:null 就停止翻页（AndroidTester C9：不许空转重试）
                    cursor = runCatching {
                        com.clarklevis.dsh.shared.protocol.parseBridgeHistory(result.body).cursor
                    }.getOrNull()
                    if (cursor.isNullOrBlank()) break
                }
                is BridgeFetch.HttpError -> return failWith(result).let { false }
                else -> return fail("chat_messages_failed", "群聊消息拉取失败").let { false }
            }
            pageIndex++
        }
        val snapshotJson = runCatching {
            chatMessagesFromHistoryPages(pages) { index, error ->
                // 丢页必须留痕（否则"少几条"没有任何线索）；首尾页丢失也要能在日志里看到。
                Log.w(TAG, "chat history page#$index dropped: ${error.javaClass.simpleName} ${error.message}")
            }
        }.getOrElse { error ->
            Log.w(TAG, "chat parse failed: ${error.javaClass.simpleName}")
            lastError = HUMAN_CHAT_FAILED
            lastErrorCode = "chat_parse_failed"
            return false
        }
        if (pages.isEmpty()) return false
        val merged = if (merge) {
            chatMessagesByConversation[conversationId].let { previous ->
                runCatching { mergeChatSnapshots(previous, snapshotJson) }.getOrDefault(snapshotJson)
            }
        } else {
            snapshotJson
        }
        chatMessagesByConversation = chatMessagesByConversation + (conversationId to merged)
        return true
    }

    /** 手动刷新一次（点按重试 / 进入团队页时调用）。 */
    fun refreshNow() {
        scope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() = refreshGate.withLock {
        // 只信已过策略门的 origin；不在取用时二次推导（那会绕过 updateGatewayEndpoint 的校验）。
        val base = origin
        if (base == null) {
            fail("no-bridge-origin", HUMAN_NO_ORIGIN)
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
            BridgeTokenTtl.proactiveIntervalSecondsFromInfo(infoJson)?.let { seconds ->
                val millis = (seconds * 1000).toLong()
                if (millis != proactiveRefreshIntervalMs) {
                    proactiveRefreshIntervalMs = millis
                    Log.i(TAG, "token ttl from /info -> proactive interval ${seconds.toLong()}s")
                }
            }
            val token = store.currentAccessToken()
            if (token.isNullOrBlank()) return@withLock fail("no-device-session", "本地无移动设备会话")
            val state = authorizedFetch("$base/mobile/v1/state")
            val stateJson = when (state) {
                is BridgeFetch.Ok -> state.body
                is BridgeFetch.HttpError -> return@withLock failWith(state)
                else -> return@withLock fail("network_error", "/state 传输失败")
            }
            val conversations = authorizedFetch("$base/mobile/v1/conversations")
            val conversationsJson = when (conversations) {
                is BridgeFetch.Ok -> conversations.body
                is BridgeFetch.HttpError -> return@withLock failWith(conversations)
                else -> return@withLock fail("network_error", "/conversations 传输失败")
            }
            val result = store.refreshTeam(infoJson, stateJson, conversationsJson)
            if (!result.isSuccess) {
                lastError = result.errorMessage ?: HUMAN_GENERIC_FAILURE
                lastErrorCode = result.errorCode
                Log.w(TAG, "team refresh rejected: ${result.errorCode} ${result.errorMessage}")
            } else {
                lastError = null
                lastErrorCode = null
            }
            applyEffects(result) // 只有显式 session-clear 才销毁凭证（不再按 phase 推断）
            publishState()
            refreshCards(base, token)
            // 停留在某个群聊里时顺带补拉最新一页（合并而非覆盖，老的几页不会丢）
            activeChatConversationId?.let { id ->
                runCatching { loadChatMessages(id, maxPages = 1, merge = true) }
                    .onFailure { Log.w(TAG, "active chat refresh failed: ${it.javaClass.simpleName}") }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (unexpected: Throwable) {
            // 兜底（AndroidTester 2026-11-02）：这条 while(true) 轮询**只有 finally、没有 catch**，
            // 而它跑在 applicationScope（SupervisorJob、无 CoroutineExceptionHandler）里——
            // 一次解析/状态机异常（Serialization / IllegalState / NPE）就会逃逸成进程崩溃，
            // 与用户「挂着不动会闪退」的形态吻合。这里把它降级成 lastError + 继续轮询。
            lastError = HUMAN_GENERIC_FAILURE
            lastErrorCode = "refresh_unhandled: ${unexpected.javaClass.simpleName}"
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
        // 卡片同理：凭证过期时先换一次再取，而不是把整块卡片静默留旧（iOS 走同一套 401 重试）。
        val snapshot = authorizedFetch("$base/mobile/v1/cards")
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
        // 只把**人话**放进 lastError；code 单独存（不拼进用户可见文本）
        lastError = message?.takeIf { it.isNotBlank() } ?: humanMessageFor(code)
        lastErrorCode = code
        store.refreshFailed(code, message)
        publishState()
    }

    /**
     * **带凭证的 GET**：401 时先换一次凭证、再重试**一次**（对齐 iOS 的 `refreshToken` 回调 + 401 重试）。
     *
     * 换证失败就**原样返回 401**，交给调用方既有的 `failWith` 判死路径——
     * 不在这里吞掉错误，否则会出现"页面停在旧数据、错误也不报"的静默态。
     */
    private suspend fun authorizedFetch(url: String): BridgeFetch {
        val first = fetch(url, store.currentAccessToken())
        if (first !is BridgeFetch.HttpError || first.status != 401) return first
        if (!refreshAccessTokenOnce()) return first
        Log.i(TAG, "401 -> token refreshed, retry once: ${url.substringAfterLast('/')}")
        return fetch(url, store.currentAccessToken())
    }

    /** 轮询循环里的主动换证：到点换一次；首次只记时点（从"连上"开始算窗口）。 */
    private suspend fun maybeRefreshTokenProactively() {
        if (store.currentAccessToken() == null) return
        val now = System.currentTimeMillis()
        if (lastTokenRefreshAtMs == 0L) {
            lastTokenRefreshAtMs = now
            return
        }
        if (now - lastTokenRefreshAtMs < proactiveRefreshIntervalMs) return
        val ok = refreshAccessTokenOnce()
        if (ok) {
            Log.i(TAG, "proactive token refresh ok (interval ${proactiveRefreshIntervalMs}ms)")
        } else {
            // 主动刷新失败不自己重试：退化成"被踢再刷"，下一次 401 或下一轮成功都会重新排上。
            Log.w(TAG, "proactive token refresh failed; falling back to 401-driven refresh")
        }
    }

    /**
     * 用存盘的 refreshToken 换一次访问凭证（单飞由 KMP 的 `beginTokenRefresh` 保证）。
     *
     * `serverUrl` / `tlsPin` 由**本地**补回：网关只答凭证，不回传我们的 origin 绑定信息。
     */
    private suspend fun refreshAccessTokenOnce(): Boolean {
        val base = origin ?: return false
        if (!store.beginTokenRefresh()) return false
        val payload = sessionStore.loadJson()?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        }
        val refreshToken = payload?.get("refreshToken")?.jsonPrimitive?.contentOrNull
        if (refreshToken.isNullOrBlank() || payload == null) {
            applyTokenResult(store.tokenRefreshRejected("unauthorized"))
            return false
        }
        val url = "$base/auth/refresh"
        if (!isBoundUrl(url)) {
            applyTokenResult(store.tokenRefreshRejected("blocked_url"))
            return false
        }
        val body = buildJsonObject { put("refreshToken", refreshToken) }.toString()
        return when (val result = postJson(url, body)) {
            is BridgeFetch.Ok -> {
                val next = runCatching { Json.parseToJsonElement(result.body).jsonObject }.getOrNull()
                if (next == null) {
                    applyTokenResult(store.tokenRefreshRejected("invalid_response"))
                    false
                } else {
                    val merged = buildJsonObject {
                        next.forEach { (key, value) -> put(key, value) }
                        payload.get("serverUrl")?.let { put("serverUrl", it) }
                        payload.get("tlsPin")?.let { put("tlsPin", it) }
                    }.toString()
                    applyTokenResult(store.tokenRefreshSucceeded(merged))
                    lastTokenRefreshAtMs = System.currentTimeMillis()
                    publishState()
                    true
                }
            }
            is BridgeFetch.HttpError -> {
                val envelope = decodeBridgeErrorEnvelope(result.status, result.body)
                Log.w(TAG, "token refresh rejected: ${result.status} ${envelope.code}")
                applyTokenResult(store.tokenRefreshRejected(envelope.code))
                false
            }
            else -> {
                applyTokenResult(store.tokenRefreshRejected("network_error"))
                false
            }
        }
    }

    /**
     * 应用 store 返回的 effect：**只有 `session-clear` 需要平台动手**（清掉存盘凭证）。
     * 不处理它 = 令牌已死但密文还在，下一次冷启动会带着死凭证再来一遍（iOS 那边由 store 效果驱动）。
     */
    private fun applyTokenResult(result: com.clarklevis.dsh.shared.facade.SharedTeamResult) {
        applyEffects(result)
        publishState()
    }

    /**
     * 按 KMP 返回的 effects 行事。
     *
     * ⚠️ `effectJson` 是**数组**（`wireJson.encodeToString(List<SharedTeamEffect>)`），
     * 不是单个对象——按对象解析会永远拿不到 kind，属于"看着跑了其实没生效"的那类。
     * 目前只有 `session-clear` 需要平台动手：清掉存盘凭证（**唯一**允许销毁凭证的地方）。
     */
    private fun applyEffects(result: com.clarklevis.dsh.shared.facade.SharedTeamResult) {
        val effectJson = result.effectJson ?: return
        val kinds = runCatching {
            Json.parseToJsonElement(effectJson).jsonArray.mapNotNull { element ->
                element.jsonObject["kind"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull().orEmpty()
        if (com.clarklevis.dsh.shared.facade.SharedTeamEffect.KIND_SESSION_CLEAR in kinds) {
            Log.w(TAG, "store says session permanently invalid -> clearing stored bridge session")
            sessionStore.clear()
        }
    }

    /** POST JSON（凭证刷新是唯一需要 POST 的调用；无鉴权头，凭 refreshToken 自证）。 */
    private suspend fun postJson(url: String, body: String): BridgeFetch = withContext(Dispatchers.IO) {
        if (!isBoundUrl(url)) return@withContext BridgeFetch.Blocked
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                Log.i(TAG, "POST ${request.url.encodedPath} -> ${response.code} (${text.length}B)")
                if (response.isSuccessful) BridgeFetch.Ok(text)
                else BridgeFetch.HttpError(response.code, text)
            }
        } catch (io: IOException) {
            Log.w(TAG, "POST ${request.url.encodedPath} failed: ${io.javaClass.simpleName}")
            BridgeFetch.TransportFailed
        }
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
            // ⚠️ **绝不在这里删会话**（2026-11-02 用户报障「覆盖安装后 Bot 页每次都要重配」的根因）：
            // NEEDS_PAIRING 只是**当前状态**，可能是瞬时的（首轮 /state 还没回来、一次 401、网关刚重启）。
            // 在这里 clear() 会把「瞬时失败」变成「永久丢配对」——与本项目已定的
            // 「非销毁式失败（保留密文 + restoreFailed 标记）」原则直接冲突。
            // 真正的永久失效由 KMP 返回 `session-clear` effect 显式通知，见 applyEffects()。
            Log.i(TAG, "team phase NEEDS_PAIRING (kept stored session; only explicit session-clear clears it)")
        }
    }

    /** code → 人话（新增 code 必须在这里登记，否则 UI 只会看到兜底文案——不允许把 code 漏出去）。 */
    private fun humanMessageFor(code: String): String = when (code) {
        "no-bridge-origin" -> HUMAN_NO_ORIGIN
        "no-device-session" -> HUMAN_NOT_PAIRED
        "network_error", "transport_failed" -> "网络不通，稍后会自动重试"
        "unauthorized", "invalid_session", "session_clear", "blocked_url" -> HUMAN_NOT_PAIRED
        else -> HUMAN_GENERIC_FAILURE
    }

    companion object {
        private const val TAG = "DshBridgeTeam"
        private const val HUMAN_NO_ORIGIN = "尚未连接桌面端：请先在设置里配对服务器"
        private const val HUMAN_NOT_PAIRED = "尚未配对桥接设备：请先与网关配对"
        private const val HUMAN_CHAT_FAILED = "聊天记录读取失败，稍后会自动重试"
        private const val HUMAN_GENERIC_FAILURE = "连接出问题了，稍后会自动重试"
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
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private sealed interface BridgeFetch {
    data class Ok(val body: String) : BridgeFetch
    data class HttpError(val status: Int, val body: String) : BridgeFetch
    data object TransportFailed : BridgeFetch
    data object Blocked : BridgeFetch
}

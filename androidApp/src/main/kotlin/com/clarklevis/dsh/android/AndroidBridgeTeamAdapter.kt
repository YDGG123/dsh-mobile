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
import com.clarklevis.dsh.shared.domain.TeamUiState
import com.clarklevis.dsh.shared.facade.SharedBridgeTeamStore
import com.clarklevis.dsh.shared.protocol.BridgeDeviceSession
import com.clarklevis.dsh.shared.protocol.BridgeServerInfo
import com.clarklevis.dsh.shared.protocol.decodeTeamUiState
import com.clarklevis.dsh.shared.protocol.encodeBridgeSession
import com.clarklevis.dsh.shared.protocol.parseBridgeInfo
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    private var loopJob: Job? = null
    private var endpoint: String? = null

    val hasSession: Boolean get() = store.currentAccessToken() != null

    /** 网关 endpoint 变化（换网关/重新配对）时调用；ws(s) → http(s) 推导桥根。 */
    fun updateGatewayEndpoint(value: String) {
        if (value == endpoint) return
        endpoint = value
        origin = originFromEndpoint(value)
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
    fun start() {
        val restore = store.restoreSession()
        if (!restore.isSuccess) {
            Log.w(TAG, "restore bridge session failed: ${restore.errorCode} ${restore.errorMessage}")
        }
        publishState()
        if (loopJob?.isActive == true) return
        if (!hasSession) {
            Log.i(TAG, "bridge polling skipped: no device session (needs pairing)")
            return
        }
        loopJob = scope.launch {
            while (true) {
                refreshOnce()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** 手动刷新一次（点按重试 / 进入团队页时调用）。 */
    fun refreshNow() {
        scope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val base = origin ?: originFromEndpoint(endpoint.orEmpty())
        if (base == null) {
            lastError = "no-bridge-origin"
            return
        }
        isRefreshing = true
        try {
            val infoJson = fetch("$base/mobile/v1/info", token = null)
                ?: return fail("info-failed")
            serverInfo = runCatching { parseBridgeInfo(infoJson) }.getOrNull()
            val token = store.currentAccessToken()
            if (token.isNullOrBlank()) return fail("no-device-session")
            val stateJson = fetch("$base/mobile/v1/state", token) ?: return fail("state-failed")
            val conversationsJson = fetch("$base/mobile/v1/conversations", token)
                ?: return fail("conversations-failed")
            val result = store.refreshTeam(infoJson, stateJson, conversationsJson)
            if (!result.isSuccess) {
                lastError = result.errorMessage ?: result.errorCode
                Log.w(TAG, "team refresh rejected: ${result.errorCode} ${result.errorMessage}")
            } else {
                lastError = null
            }
            publishState()
        } finally {
            isRefreshing = false
        }
    }

    private fun fail(code: String) {
        lastError = code
        store.refreshFailed(code, code)
        publishState()
    }

    private suspend fun fetch(url: String, token: String?): String? = withContext(Dispatchers.IO) {
        if (!isBoundUrl(url)) {
            Log.w(TAG, "blocked unbound request: $url (origin=$origin)")
            return@withContext null
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
                    Log.w(TAG, "GET ${request.url.encodedPath} body=${body.take(240)}")
                    null
                } else {
                    body
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "GET ${request.url.encodedPath} failed: ${error.message}")
            null
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

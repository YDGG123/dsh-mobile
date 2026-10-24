package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun SharedMobileSnapshot.isChatPrefetchBusy(): Boolean =
    selectedHistoryIsLoading || selectedHistoryIsLoadingOlder || sessions.any { it.id == selectedSessionId && it.isRunning }

/** 只暖磁盘的后台请求队列；无 requestId 的旧协议按会话隔离，绝不订阅或改变前台 Store。 */
internal class AndroidChatPrefetcher(
    private val scope: CoroutineScope,
    private val plan: suspend (excluded: Set<String>, selected: String?) -> List<String>,
    private val ticket: suspend (String) -> String,
    private val request: suspend (String, Int?) -> Boolean,
    private val accept: suspend (String, String, String) -> Boolean,
    private val cancelTicket: suspend (String, String) -> Unit = { _, _ -> },
    private val cleanupScope: CoroutineScope = CLEANUP_SCOPE,
    private val log: (stage: String, count: Int) -> Unit = { _, _ -> },
    private val idleMillis: Long = 750,
    private val timeoutMillis: Long = 10_000,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Pending(val session: String, val ticket: String, val done: CompletableDeferred<Unit>)
    private val lock = Any()
    private var connected = false
    private var hello = false
    private var sessions = false
    private var stopped = false
    private var complete = false
    private var selected: String? = null
    private var busy = false
    private var acceptsForegroundHistory = true
    private var format: Int? = null
    private var generation = 0L

    // 可选预加载超时会回收旧协议 socket；跨自动重连冷却，避免形成无限重连环。
    private var cooldownUntilMillis = Long.MIN_VALUE
    private var worker: Job? = null
    private var pending: Pending? = null

    // 有上限（每连接最多6），迟到响应只隔离自己的非前台会话。
    private val attempted = linkedSetOf<String>()

    fun connectionChanged(value: Boolean) = synchronized(lock) {
        if (connected == value) return@synchronized
        cancelLocked("disconnect")
        connected = value
        if (!value) {
            hello = false
            sessions = false
        }
        stopped = false
        complete = false
        attempted.clear()
        startLocked()
    }

    fun helloReceived(historyFormat: Int?) = synchronized(lock) {
        hello = true
        format = historyFormat
        startLocked()
    }

    fun sessionsReceived() = synchronized(lock) {
        sessions = true
        startLocked()
    }

    fun foregroundChanged(session: String?, isBusy: Boolean, acceptsHistory: Boolean = true) = synchronized(lock) {
        acceptsForegroundHistory = acceptsHistory
        if (selected == session && busy == isBusy) return@synchronized
        selected = session
        busy = isBusy
        cancelLocked("foreground")
        startLocked()
    }

    /** clear、内存压力、关闭均终止本连接剩余轮次，避免清理完马上填回。 */
    fun stop(reason: String) = synchronized(lock) {
        stopped = true
        cancelLocked(reason)
    }

    fun interceptTerminal(event: GatewayRuntimeEvent): Boolean {
        val target = when (event) {
            is GatewayRuntimeEvent.RequestCancelled -> event.targetSessionId.takeIf {
                event.requestType == "history" &&
                    event.isBackgroundHistory
            }
            is GatewayRuntimeEvent.RequestTimedOut -> event.targetSessionId.takeIf {
                event.requestType == "history" &&
                    event.isBackgroundHistory
            }
            is GatewayRuntimeEvent.RequestRejected -> event.targetSessionId.takeIf {
                event.requestType == "history" &&
                    event.isBackgroundHistory
            }
            else -> null
        } ?: return false
        synchronized(lock) {
            if (event is GatewayRuntimeEvent.RequestTimedOut) timeoutLocked()
            if (target == selected && acceptsForegroundHistory) return false
            pending?.takeIf { it.session == target }?.let {
                pending = null
                invalidate(it)
                it.done.complete(Unit)
            }
            log("skip-error", 1)
            return true
        }
    }

    suspend fun intercept(rawJson: String, frame: GatewayFrame, correlatedSession: String?, isBackgroundHistory: Boolean = false): Boolean {
        if (!isBackgroundHistory) return false
        if (frame.kind != "history" && !(frame.kind == "error" && frame.requestType == "history")) return false
        val session = frame.sessionId ?: correlatedSession ?: return false
        val match = synchronized(lock) {
            if (session == selected && acceptsForegroundHistory) return false
            if (!connected || session == selected) null else pending?.takeIf { it.session == session }
        }
        // 仅 Runtime 本地 active request 标记过的响应可被拦截；普通前台 older/错误一律放行。
        if (match == null) {
            log("skip-late", 1)
            return true
        }
        try {
            if (frame.kind == "history") {
                val stillOwned = synchronized(lock) { !stopped && pending === match && selected != session }
                if (stillOwned) log(if (accept(session, rawJson, match.ticket)) "hit" else "skip-write", 1)
            } else {
                log("skip-error", 1)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log("skip-write", 1)
        } finally {
            synchronized(lock) {
                if (pending === match) pending = null
                match.done.complete(Unit)
            }
        }
        return true
    }

    private fun cancelLocked(reason: String) {
        generation += 1
        worker?.cancel()
        worker = null
        pending?.let {
            it.done.complete(Unit)
            invalidate(it)
        }
        pending = null
        log("cancel-$reason", attempted.size)
    }

    private fun startLocked() {
        if (!connected ||
            !hello ||
            !sessions ||
            stopped ||
            complete ||
            busy ||
            worker != null ||
            monotonicMillis() < cooldownUntilMillis
        ) {
            return
        }
        val expected = generation
        worker = scope.launch {
            try {
                while (true) {
                    delay(idleMillis)
                    val context = synchronized(lock) {
                        if (generation != expected || stopped || busy || !connected) return@launch
                        if (attempted.size >= MAXIMUM_REQUESTS) {
                            complete = true
                            log("complete", attempted.size)
                            return@launch
                        }
                        attempted.toSet() to selected
                    }
                    val next = plan(context.first, context.second).firstOrNull { it !in context.first && it != context.second }
                    if (next == null) {
                        synchronized(lock) { if (generation == expected) complete = true }
                        log("complete", context.first.size)
                        return@launch
                    }
                    val nextTicket = ticket(next)
                    val flight = synchronized(lock) {
                        if (generation != expected || stopped || busy || next == selected || !connected) return@launch
                        attempted += next
                        Pending(next, nextTicket, CompletableDeferred()).also { pending = it }
                    }
                    log("start", context.first.size + 1)
                    if (request(next, format)) {
                        if (withTimeoutOrNull(timeoutMillis) {
                                flight.done.await()
                                true
                            } != true
                        ) {
                            synchronized(lock) { if (pending === flight) timeoutLocked() }
                            log("skip-timeout", 1)
                            return@launch
                        }
                    } else {
                        invalidate(flight)
                        log("skip-send", 1)
                    }
                    synchronized(lock) { if (pending === flight) pending = null }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { if (generation == expected) complete = true }
                log("skip-failure", 1)
            } finally {
                synchronized(lock) { if (generation == expected) worker = null }
            }
        }
    }

    private fun timeoutLocked() {
        cooldownUntilMillis = maxOf(cooldownUntilMillis, monotonicMillis() + TIMEOUT_COOLDOWN_MILLIS)
        stopped = true
        cancelLocked("timeout")
    }

    private fun invalidate(flight: Pending) {
        cleanupScope.launch { runCatching { cancelTicket(flight.session, flight.ticket) } }
    }

    companion object {
        private const val MAXIMUM_REQUESTS = 6
        private const val TIMEOUT_COOLDOWN_MILLIS = 5 * 60 * 1000L
        private val CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

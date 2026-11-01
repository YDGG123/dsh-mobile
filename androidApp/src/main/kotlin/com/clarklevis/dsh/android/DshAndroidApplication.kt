package com.clarklevis.dsh.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clarklevis.dsh.android.platform.GatewayLifecycleEvent
import com.clarklevis.dsh.android.ui.AppearanceSettings
import kotlinx.coroutines.launch

class DshAndroidApplication : Application(), DefaultLifecycleObserver {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppearanceSettings.localizedContext(base))
    }

    lateinit var hosts: AndroidMultiGatewayStore
        private set
    val graph: AndroidAppGraph get() = hosts.activeGraph
    @Volatile
    internal var isInForeground = false
        private set
    internal var pendingNotificationSession by mutableStateOf<AndroidNotificationSessionRoute?>(null)
        private set

    internal fun openNotificationSession(intent: Intent?) {
        val gatewayId = intent?.getStringExtra(EXTRA_NOTIFICATION_GATEWAY_ID)?.takeIf(String::isNotBlank)
        val sessionId = intent?.getStringExtra(EXTRA_NOTIFICATION_SESSION_ID)?.takeIf(String::isNotBlank)
        if (gatewayId != null && sessionId != null) {
            pendingNotificationSession = AndroidNotificationSessionRoute(gatewayId, sessionId)
        }
    }

    internal fun clearNotificationSession(route: AndroidNotificationSessionRoute) {
        if (pendingNotificationSession == route) pendingNotificationSession = null
    }

    override fun onCreate() {
        super<Application>.onCreate()
        // 取证设施：未捕获异常落盘（时间/线程/前后台/版本/堆栈），设置「关于」里可查看。
        com.clarklevis.dsh.android.platform.CrashLog.install(this) { isInForeground }
        // 头像文件缓存初始化（AndroidTester 2026-11-02 抓的：init 零调用 = 整层永不生效，
        // store/fileFor/prune 全部在首行 directory ?: return 处静默早退）。
        com.clarklevis.dsh.android.platform.BotAvatarFileCache.init(this)
        hosts = AndroidMultiGatewayStore(this)
        maintainCacheStorage()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private fun maintainCacheStorage() {
        graph.gatewayScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cache = com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(this@DshAndroidApplication)
            try {
                cache.trimToBytes(72L * AndroidCacheDiskManager.MIB)
                AndroidCacheStorage.shared(this@DshAndroidApplication).maintain()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.util.Log.w("CacheStorage", "maintenance-failed reason=storage-error")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::hosts.isInitialized) return
        graph.stateHolder.trimMemory()
        com.clarklevis.dsh.android.platform.AndroidAttachmentCache.trimMemory()
        com.clarklevis.dsh.android.ui.PluginIconImageCache.trimMemory()
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.clarklevis.dsh.android.ui.trimMarkdownMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onStart(owner: LifecycleOwner) {
        isInForeground = true
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        graph.gatewayRuntime.applicationDidBecomeActive()
        // 回前台补拉一次（iOS AppStore.swift:1657-1680 同语义：「补查后台期间漏掉的会话终态」）：
        // bridge 名册恢复轮询 + 网关会话列表拉一次，**不在循环里补**，避免回前台请求风暴。
        graph.stateHolder.bridgeTeam?.let { team ->
            team.start()
            team.refreshNow()
        }
        graph.gatewayScope.launch { graph.gatewayRuntime.requestSessions() }
    }

    override fun onStop(owner: LifecycleOwner) {
        isInForeground = false
        // 后台停常驻（对齐 iOS：chatPrefetcher.stop("background") + stopPolling()）：
        // bridge 轮询整支取消；后台只留 WS 与保活。这也是把「熄屏挂着崩」的
        // 候选链整条切掉——后台不再有轮询，就没有那条后台抛异常的路径。
        graph.stateHolder.bridgeTeam?.stop()
        maintainCacheStorage()
        hosts.stopPresence()
        hosts.cancelPairing()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        graph.gatewayScope.launch { graph.gatewayRuntime.applicationDidEnterBackground() }
    }
}

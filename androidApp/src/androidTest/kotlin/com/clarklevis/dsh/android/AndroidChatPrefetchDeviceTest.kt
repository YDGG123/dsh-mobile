package com.clarklevis.dsh.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.AndroidChatHistoryCache
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayPreferencesSnapshot
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 模拟网关 + 真 Room，覆盖连接调度与前台订阅隔离。 */
class AndroidChatPrefetchDeviceTest {
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun connectionPrioritizesForegroundThenWarmsHottestSessionWithoutSubscription() = runBlocking {
        withGraph { graph, transport ->
            val cache = AndroidChatHistoryCache.shared(application)
            repeat(4) { cache.recordSessionOpen(graph.gatewayLocalId, "hot") }
            handshake(graph, transport)
            onMain { graph.stateHolder.selectSession("current") }
            waitFor { transport.historySessions() == listOf("current") }
            transport.receive(sessions)
            delay(1_100)
            assertEquals("前台 history 未完成时不抢 lane", listOf("current"), transport.historySessions())
            transport.receive(history("current", "foreground"))
            waitFor { transport.historySessions().size == 2 }
            assertEquals(listOf("current", "hot"), transport.historySessions())
            val request = transport.payloads("history").last()
            assertEquals("80", request.getValue("maxMessages").jsonPrimitive.content)
            assertEquals("262144", request.getValue("maxBytes").jsonPrimitive.content)
            transport.receive(history("hot", "preloaded"))
            waitFor { cache.loadTypedPage(graph.gatewayLocalId, "hot", null, 80)?.events?.singleOrNull()?.event?.text == "preloaded" }
            onMain {
                assertEquals("current", graph.stateHolder.snapshot.selectedSessionId)
                assertEquals("foreground", graph.stateHolder.snapshot.conversation.single().text)
            }
            assertFalse(transport.payloads("subscribe").any { it["sessionId"]?.jsonPrimitive?.content == "hot" })
            onMain { graph.stateHolder.selectSession("hot") }
            waitFor {
                graph.stateHolder.snapshot.selectedSessionId == "hot" &&
                    graph.stateHolder.snapshot.conversation.any { it.text == "preloaded" }
            }
        }
    }

    @Test fun runtimeBackgroundHistoryErrorDoesNotSetForegroundPlatformErrorOrStopNextCandidate() = runBlocking {
        withGraph { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            waitFor { transport.historySessions().isNotEmpty() }
            val first = transport.historySessions().first()
            transport.receive("""{"kind":"error","requestType":"history","sessionId":"$first","code":"history/unavailable"}""")
            waitFor { transport.historySessions().size >= 2 }
            onMain {
                assertEquals(null, graph.stateHolder.platformError)
                assertEquals(null, graph.stateHolder.snapshot.selectedHistoryError)
            }
            assertEquals(null, AndroidChatHistoryCache.shared(application).loadTypedPage(graph.gatewayLocalId, first, null, 80))
        }
    }

    @Test fun clearCancelsInflightPrefetchAndLateResponseCannotRecreateRoomWindow() = runBlocking {
        withGraph { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            waitFor { transport.historySessions().isNotEmpty() }
            val pending = transport.historySessions().first()
            onMain { graph.stateHolder.refreshCacheStorage(clear = true) }
            waitFor { !graph.stateHolder.cacheMaintenanceRunning }
            transport.receive(history(pending, "must not revive"))
            delay(1_100)
            val cache = AndroidChatHistoryCache.shared(application)
            assertEquals(null, cache.loadTypedPage(graph.gatewayLocalId, pending, null, 80))
            assertEquals(1, transport.historySessions().size)
        }
    }

    @Test fun disconnectCancelsPrefetchAndOldSocketResponseCannotWriteRoom() = runBlocking {
        withGraph { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            waitFor { transport.historySessions().isNotEmpty() }
            val pending = transport.historySessions().first()
            graph.gatewayRuntime.disconnect()
            waitFor { graph.stateHolder.gatewayState.connection.name != "CONNECTED" }
            transport.receive(history(pending, "old socket"))
            delay(300)
            assertEquals(null, AndroidChatHistoryCache.shared(application).loadTypedPage(graph.gatewayLocalId, pending, null, 80))
        }
    }

    private suspend fun handshake(graph: AndroidAppGraph, transport: FakeTransport) {
        waitFor { transport.generation != null }
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"historyFormatVersion":1}""")
        waitFor { graph.stateHolder.gatewayState.connection.name == "CONNECTED" }
    }

    private suspend fun withGraph(block: suspend (AndroidAppGraph, FakeTransport) -> Unit) {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application,
            transportOverride = transport,
            preferencesOverride = object : GatewayPreferences {
                private val value = MutableStateFlow(GatewayPreferencesSnapshot(endpoint = "wss://gateway.example/ws/mobile"))
                override val snapshots = value
                override suspend fun load() = value.value
                override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
                    value.value = snapshot
                }
            },
            credentialStoreOverride = object : GatewayCredentialStore {
                override suspend fun loadOrCreateDeviceId() = "prefetch-test-device"
                override suspend fun loadToken(endpoint: String) = "token"
                override suspend fun saveToken(endpoint: String, token: String) = Unit
                override suspend fun deleteToken(endpoint: String) = Unit
            },
            networkMonitorOverride = object : GatewayNetworkMonitor {
                override val state = MutableStateFlow(GatewayNetworkState.AVAILABLE)
            },
            gatewayLocalId = "prefetch-device-" + UUID.randomUUID()
        )
        graph.stateHolder
        try {
            block(graph, transport)
        } finally {
            onMain { graph.stateHolder.close() }
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
            kotlinx.coroutines.withContext(Dispatchers.IO) { AndroidChatHistoryCache.shared(application).clear(graph.gatewayLocalId) }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun waitFor(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(20) }

    private class FakeTransport : GatewayTransport {
        override val state = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        override val events = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 128)

        @Volatile var generation: Long? = null
        private val sent = mutableListOf<String>()
        override suspend fun open(spec: GatewayConnectionSpec) {
            generation = spec.generation
            emit(GatewayTransportState.Opening(spec.generation))
        }
        override suspend fun send(text: String) {
            synchronized(sent) { sent += text }
        }
        override suspend fun close() {
            emit(GatewayTransportState.Closed(generation ?: 0))
        }
        fun opened() = emit(GatewayTransportState.Open(requireNotNull(generation)))
        fun receive(raw: String) {
            check(
                events.tryEmit(GatewayTransportEvent.Frame(GatewayTransportFrame(requireNotNull(generation), raw, raw.toByteArray().size)))
            )
        }
        fun payloads(type: String) = synchronized(sent) {
            sent.map { Json.parseToJsonElement(it).jsonObject }.filter { it["type"]?.jsonPrimitive?.content == type }
        }
        fun historySessions() = payloads("history").map { it.getValue("sessionId").jsonPrimitive.content }
        private fun emit(value: GatewayTransportState) {
            state.value = value
            check(events.tryEmit(GatewayTransportEvent.State(value)))
        }
    }

    companion object {
        private val sessions =
            """{"kind":"sessions","items":[{"sessionId":"current","updatedAt":100,"running":false,"blank":false},{"sessionId":"hot","updatedAt":1,"running":false,"blank":false},{"sessionId":"recent","updatedAt":200,"running":true,"blank":false}]}"""
        private fun history(id: String, text: String) =
            """{"kind":"history","sessionId":"$id","historyFormatVersion":1,"events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"$text"}]}}],"hasMore":false,"bytes":100}"""
    }
}

package com.clarklevis.dsh.android.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.clarklevis.dsh.android.AndroidAppGraph
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.platform.AndroidChatHistoryCache
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayPreferencesSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatCacheDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun offlineSearchWithoutSessionMetadataOpensOlderMatchingPage() = withHolder { graph, holder ->
        save(graph.gatewayLocalId, "offline", 150)
        val direct =
            runBlocking(Dispatchers.IO) { AndroidChatHistoryCache.shared(application).search(graph.gatewayLocalId, "needle", null, 50) }
        assertTrue("真实 Room seed 应可检索", direct.contains("needle old message"))
        compose.setContent {
            DshTheme {
                var query by remember { mutableStateOf("") }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalChatSearchContent(holder, query, {
                        query = it
                        holder.search(it)
                    }, holder::selectSession)
                }
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("needle")
        compose.waitUntil(15_000) { holder.localChatMatches.isNotEmpty() }
        compose.onNodeWithText("needle old message").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            holder.snapshot.selectedSessionId == "offline" &&
                holder.localSearchIsFragment &&
                holder.snapshot.conversation.any { it.text == "needle old message" }
        }
        assertTrue(holder.snapshot.sessions.isEmpty())
        assertFalse(holder.snapshot.selectedHistoryIsLoading)
        assertEquals("offline", holder.localSearchScrollTarget?.sessionId)
        assertEquals(1, holder.localSearchScrollTarget?.seq)
        compose.runOnIdle {
            holder.messageDraft = "unsent draft"
            holder.sendMessage()
        }
        assertTrue(holder.platformError?.contains("只读") == true)
        assertEquals("unsent draft", holder.messageDraft)
        compose.runOnIdle { holder.returnToLatestCached() }
        compose.waitUntil(15_000) { !holder.localSearchIsFragment && holder.snapshot.conversation.size == 80 }
        assertEquals("message 150", holder.snapshot.conversation.last().text)
        assertEquals("unsent draft", holder.messageDraft)
    }

    @Test fun settingsShowsCategoriesAndClearsRebuildableFiles() = withHolder { _, holder ->
        val cached = File(application.cacheDir, "workspace-previews/device-cache-test/video").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(4096))
        }
        val source = File(application.cacheDir, "upload-source-device-test/image").apply {
            parentFile!!.mkdirs()
            writeText("user original")
        }
        val image = File(application.cacheDir, "gateway-image-attachments/storage-test/image").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(2048))
        }
        try {
            compose.setContent {
                var storageOpen by remember { mutableStateOf(false) }
                DshTheme {
                    if (storageOpen) StorageSpaceScreen(holder, { storageOpen = false })
                    else SettingsScreen(holder, {}, {}, {}, {}, onOpenStorage = { storageOpen = true })
                }
            }
            compose.onNodeWithTag("storage-space-settings-row").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { holder.storageUsage != null && !holder.cacheMaintenanceRunning }
            compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("磁盘占用")).assertIsDisplayed()
            compose.onNodeWithTag("storage-clear-previews").assertDoesNotExist()
            compose.onNodeWithTag("storage-category-previews").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("storage-clear-previews").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { !holder.cacheMaintenanceRunning && !cached.exists() }
            compose.onNodeWithText("内存命中", substring = true).assertDoesNotExist()
            compose.onNodeWithText("磁盘命中", substring = true).assertDoesNotExist()
            assertTrue(image.exists())
            assertTrue(source.exists())
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("storage-sort-menu").assertExists().performClick()
            compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("名称")).performClick()
        } finally {
            source.parentFile!!.deleteRecursively()
            image.parentFile!!.deleteRecursively()
        }
    }

    @Test fun coldTailPagesAndWarmSwitchRestoreFromRealRoom() = withHolder { graph, holder ->
        save(graph.gatewayLocalId, "page", 150)
        save(graph.gatewayLocalId, "other", 1)
        compose.runOnIdle { holder.selectSession("page") }
        compose.waitUntil(15_000) { holder.snapshot.conversation.size == 80 && !holder.snapshot.selectedHistoryIsLoading }
        assertEquals(71, holder.snapshot.selectedHistoryEarliestSequence)
        compose.runOnIdle { holder.loadOlderHistory() }
        compose.waitUntil(15_000) { holder.snapshot.conversation.size == 150 }
        compose.runOnIdle { holder.selectSession("other") }
        compose.waitUntil(15_000) { holder.snapshot.selectedSessionId == "other" && holder.snapshot.conversation.size == 1 }
        compose.runOnIdle { holder.selectSession("page") }
        compose.waitUntil(15_000) { holder.snapshot.selectedSessionId == "page" && holder.snapshot.conversation.size == 150 }
        assertEquals("needle old message", holder.snapshot.conversation.first().text)
        repeat(8) { index ->
            val id = "eviction-$index"
            save(graph.gatewayLocalId, id, 1)
            compose.runOnIdle { holder.selectSession(id) }
            compose.waitUntil(15_000) { holder.snapshot.selectedSessionId == id && holder.snapshot.conversation.size == 1 }
        }
        compose.runOnIdle { holder.selectSession("page") }
        compose.waitUntil(15_000) { holder.snapshot.selectedSessionId == "page" && holder.snapshot.conversation.size == 80 }
        compose.runOnIdle { holder.loadOlderHistory() }
        compose.waitUntil(15_000) { holder.snapshot.conversation.size == 150 }
    }

    @Test fun imageCopyDoesNotBlockMainThreadLeaseAndClearRejectsOldWrite() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val storage = com.clarklevis.dsh.android.AndroidCacheStorage.shared(application)
        val cache = com.clarklevis.dsh.android.platform.AndroidAttachmentCache(
            application,
            gatewayId = "blocked-image-test",
            writeFile = { file, bytes ->
                entered.countDown()
                check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS))
                file.writeBytes(bytes)
            }
        )
        cache.read("image")
        val writing = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { cache.write("image", ByteArray(100)) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val start = System.nanoTime()
                storage.lease(File(application.cacheDir, "workspace-files/lease-test/file")).close()
                assertTrue("图片复制不能阻塞主线程租约", System.nanoTime() - start < 200_000_000L)
            }
            kotlinx.coroutines.withContext(Dispatchers.IO) { storage.maintain(clear = true) }
        } finally {
            resume.countDown()
        }
        assertFalse(writing.await())
        assertEquals(null, cache.read("image"))
        cache.read("old-evicted-ticket")
        repeat(513) { cache.read("other-$it") }
        kotlinx.coroutines.withContext(Dispatchers.IO) { storage.maintain(clear = true) }
        assertFalse("被逐出票据的旧请求也不能在清空后重建缓存", cache.write("old-evicted-ticket", ByteArray(10)))
    }

    @Test fun authoritativeTailCanOpenOlderSearchWithoutReplacingNormalHistory() = withHolder { graph, _ ->
        val reference = java.util.concurrent.atomic.AtomicReference<com.clarklevis.dsh.shared.facade.SharedMobileSnapshot>()
        val projection = com.clarklevis.dsh.android.AndroidGatewayProjection()
        val cache = AndroidChatHistoryCache.shared(application)
        val result = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, Boolean>>()
        val actor = com.clarklevis.dsh.android.AndroidProjectionActor(
            projection,
            Dispatchers.Main.immediate,
            publish = { state, _ -> reference.set(state) },
            chatCache = cache,
            gatewayCacheId = graph.gatewayLocalId,
            onSearchNavigationResolved = { found, fragment -> result.set(found to fragment) }
        )
        try {
            save(graph.gatewayLocalId, "authority", 200)
            runBlocking { actor.selectSession("authority", offline = true) }
            compose.waitUntil(15_000) { reference.get()?.conversation?.size == 80 }
            val records = (121..200).joinToString(",") { seq ->
                """{"type":"user/message","seq":$seq,"time":$seq,"data":{"content":[{"type":"text","text":"network $seq"}]}}"""
            }
            val frame = """{"kind":"history","sessionId":"authority","hasMore":true,"nextBeforeSeq":121,"events":[$records]}"""
            runBlocking {
                actor.loadHistory("authority", older = false)
                actor.acceptFrame(frame, com.clarklevis.dsh.shared.protocol.GatewayWireDecoder.decode(frame), "authority")
            }
            compose.waitUntil(15_000) { reference.get()?.conversation?.firstOrNull()?.text == "network 121" }
            runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(15_000) {
                    while (cache.loadTypedWindow(graph.gatewayLocalId, "authority")?.events?.firstOrNull()?.event?.text != "network 121") {
                        kotlinx.coroutines.delay(25)
                    }
                }
            }
            save(graph.gatewayLocalId, "authority", 200)
            runBlocking { actor.selectSession("authority", offline = true, targetSequence = 12) }
            compose.waitUntil(15_000) { reference.get()?.conversation?.lastOrNull()?.text == "message 12" }
            assertTrue("搜索片段不能混入正常最新消息", reference.get().conversation.none { it.text.startsWith("network") })
            assertTrue("正常同步窗口必须保留", projection.cachedWindowJson("authority")?.contains("network 121") == true)
            val live = """{"kind":"event","sessionId":"authority","seq":201,"time":201,"event":{"type":"user/message","text":"live 201"}}"""
            runBlocking { actor.acceptFrame(live, com.clarklevis.dsh.shared.protocol.GatewayWireDecoder.decode(live), "authority") }
            assertEquals("message 12", reference.get().conversation.last().text)
            runBlocking { actor.returnToLatestCached("authority") }
            compose.waitUntil(15_000) { reference.get()?.conversation?.lastOrNull()?.text == "live 201" }
            assertEquals(81, reference.get().conversation.size)
            runBlocking { actor.selectSession("authority", offline = true, targetSequence = 190) }
            compose.waitUntil(15_000) { result.get() == (true to false) }
            assertEquals(81, reference.get().conversation.size)
            val frames = listOf(
                """{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}""",
                """{"kind":"subscribed","sessionId":"authority","subscriptionId":"sub","assistantStream":true}""",
                """{"kind":"session-snapshot","sessionId":"authority","subscriptionId":"sub","streamId":"cleared","historyFormatVersion":3,"cursor":220,"events":[],"hasMore":false}"""
            )
            runBlocking {
                frames.forEach { actor.acceptFrame(it, com.clarklevis.dsh.shared.protocol.GatewayWireDecoder.decode(it), "authority") }
            }
            assertTrue(reference.get().conversation.isEmpty())
            save(graph.gatewayLocalId, "authority", 200)
            runBlocking { actor.selectSession("authority", offline = true, targetSequence = 12) }
            compose.waitUntil(15_000) { result.get() == (false to false) }
            assertTrue("完整空快照不能复活缓存正文", reference.get().conversation.isEmpty())
        } finally {
            actor.close()
        }
    }

    @Test fun closingGatewayReleasesSelectedWindowProtectionInRealRoom() = withHolder { _, _ ->
        val gatewayId = "closing-${UUID.randomUUID()}"
        val cache = AndroidChatHistoryCache.shared(application)
        val projection = com.clarklevis.dsh.android.AndroidGatewayProjection()
        val state = java.util.concurrent.atomic.AtomicReference<com.clarklevis.dsh.shared.facade.SharedMobileSnapshot>()
        val actor = com.clarklevis.dsh.android.AndroidProjectionActor(
            projection,
            Dispatchers.Main.immediate,
            publish = { snapshot, _ -> state.set(snapshot) },
            chatCache = cache,
            gatewayCacheId = gatewayId
        )
        try {
            save(gatewayId, "selected", 100)
            runBlocking { actor.selectSession("selected", offline = true) }
            compose.waitUntil(15_000) { state.get()?.conversation?.size == 80 }
            runBlocking(Dispatchers.IO) {
                cache.trimToBytes(0)
                assertTrue("当前会话仍受保护", cache.loadTypedWindow(gatewayId, "selected") != null)
                actor.close()?.join()
                cache.trimToBytes(0)
                assertEquals("网关关闭后旧会话可回收", null, cache.loadTypedWindow(gatewayId, "selected"))
            }
        } finally {
            actor.close()
            runBlocking { cache.clear(gatewayId) }
        }
    }

    @Test fun delayedOldGatewayCloseCannotUnprotectItsReplacement() = withHolder { _, _ ->
        val gatewayId = "replacement-${UUID.randomUUID()}"
        val cache = AndroidChatHistoryCache.shared(application)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val old = com.clarklevis.dsh.android.AndroidProjectionActor(
            com.clarklevis.dsh.android.AndroidGatewayProjection(),
            Dispatchers.Main.immediate,
            publish = { _, _ -> },
            chatCache = cache,
            gatewayCacheId = gatewayId,
            onDiskChanged = {
                entered.countDown()
                check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
        )
        var replacement: com.clarklevis.dsh.android.AndroidProjectionActor? = null
        try {
            save(gatewayId, "old", 100)
            save(gatewayId, "new", 100)
            runBlocking { old.selectSession("old", offline = true) }
            assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val closing = old.close()
            val state = java.util.concurrent.atomic.AtomicReference<com.clarklevis.dsh.shared.facade.SharedMobileSnapshot>()
            replacement = com.clarklevis.dsh.android.AndroidProjectionActor(
                com.clarklevis.dsh.android.AndroidGatewayProjection(),
                Dispatchers.Main.immediate,
                publish = { snapshot, _ -> state.set(snapshot) },
                chatCache = cache,
                gatewayCacheId = gatewayId
            )
            runBlocking { replacement.selectSession("new", offline = true) }
            compose.waitUntil(15_000) { state.get()?.conversation?.size == 80 }
            release.countDown()
            runBlocking(Dispatchers.IO) {
                closing?.join()
                cache.trimToBytes(0)
                assertTrue("旧清理不能解除新网关保护", cache.loadTypedWindow(gatewayId, "new") != null)
                assertEquals(null, cache.loadTypedWindow(gatewayId, "old"))
            }
        } finally {
            release.countDown()
            old.close()
            runBlocking {
                replacement?.close()?.join()
                cache.clear(gatewayId)
            }
        }
    }

    private fun save(gatewayId: String, sessionId: String, count: Int) = runBlocking(Dispatchers.IO) {
        val events = (1..count).map { seq ->
            SessionEvent(
                sessionId,
                seq,
                seq.toDouble(),
                GatewayEvent("user/message", text = if (seq == 1) "needle old message" else "message $seq")
            )
        }
        val payload = Json.encodeToString(CachedHistoryWindow(sessionId = sessionId, events = events))
        assertTrue(AndroidChatHistoryCache.shared(application).save(gatewayId, sessionId, payload, System.nanoTime()))
    }

    private fun withHolder(block: (AndroidAppGraph, AndroidSharedStateHolder) -> Unit) {
        val graph = AndroidAppGraph(
            application,
            preferencesOverride = object : GatewayPreferences {
                val value = MutableStateFlow(GatewayPreferencesSnapshot(automaticallyConnect = false))
                override val snapshots = value
                override suspend fun load() = value.value
                override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
                    value.value = snapshot
                }
            },
            gatewayLocalId = "device-chat-cache-${UUID.randomUUID()}"
        )
        val holder = graph.stateHolder
        try {
            block(graph, holder)
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.close() }
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
            runBlocking(Dispatchers.IO) { AndroidChatHistoryCache.shared(application).clear(graph.gatewayLocalId) }
        }
    }
}

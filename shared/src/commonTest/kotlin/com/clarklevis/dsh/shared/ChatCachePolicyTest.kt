package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.cache.ChatCacheKey
import com.clarklevis.dsh.shared.cache.ChatCacheMemoryEstimator
import com.clarklevis.dsh.shared.cache.ChatCacheWindowPolicy
import com.clarklevis.dsh.shared.cache.ChatMemoryCache
import com.clarklevis.dsh.shared.cache.searchIndex
import com.clarklevis.dsh.shared.cache.unicodeUnits
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ChatCachePolicyTest {
    @Test
    fun memoryUsesReadRecencyAndCountAndBytesBounds() {
        val memory = ChatMemoryCache(maximumBytes = 2_000, maximumSessions = 2)
        val a = ChatCacheKey("g", "a")
        val b = ChatCacheKey("g", "b")
        val c = ChatCacheKey("g", "c")
        memory.put(a, window("a"), 100)
        memory.put(b, window("b"), 100)
        assertNotNull(memory.get(a))
        memory.put(c, window("c"), 100)
        assertNull(memory.get(b))
        assertNotNull(memory.get(a))
        assertEquals(2, memory.size)
        assertTrue(memory.bytes <= 2_000)
        memory.put(c, window("c"), 10_000)
        assertNull(memory.get(c))
        memory.clear("foreign")
        assertNotNull(memory.get(a))
        memory.clear("g")
        assertEquals(0L, memory.bytes)
    }

    @Test
    fun searchIndexesOnlyMessageTextAndKeepsUnicodeScalars() {
        val record = SessionEvent("s", 1, 1.0, GatewayEvent("assistant/message", text = "中文😀ABC", reasoning = "不检索推理"))
        val tool = SessionEvent("s", 2, 2.0, GatewayEvent("tool/result", text = "不检索工具"))
        val index = searchIndex("g", CachedHistoryWindow(sessionId = "s", events = listOf(record, tool)))
        assertEquals(listOf("中文😀ABC"), index.texts.map { it.text })
        assertTrue(index.grams.any { it.gram == "文😀a" })
        assertTrue(index.grams.none { it.gram.contains("推理") || it.gram.contains("工具") })
        assertEquals(listOf("中", "😀", "文"), unicodeUnits("中😀文"))
        assertTrue(index.bytes > 0)
    }

    @Test
    fun highEntropyTextDoesNotBuildUnboundedNgramsOrLoseItsSearchableBody() {
        val text = (0..4096).joinToString("") { "${it.toString(16)}中" }
        val index = searchIndex(
            "g",
            CachedHistoryWindow(
                sessionId = "s",
                events = listOf(
                    SessionEvent("s", 1, 1.0, GatewayEvent("user/message", text = text))
                )
            )
        )
        assertFalse(index.texts.single().gramIndexed)
        assertEquals(text, index.texts.single().text)
        assertEquals(0, index.grams.size)
    }

    @Test
    fun typedCachePagesCannotResurrectAfterNetworkTakesOver() {
        val store = SharedHistoryStore()
        store.hydrateTypedCachedWindow("s", window("s", 2))
        assertTrue(store.canPrependCachedPage("s"))
        store.prependTypedCachedPage("s", window("s", 1))
        assertTrue(recordsJson(store, "s").contains("\"seq\":1"))
        store.installSnapshot("s", "[]", false, null)
        assertFalse(store.canPrependCachedPage("s"))
        store.prependTypedCachedPage("s", window("s", 1))
        assertEquals("[]", recordsJson(store, "s"))
    }

    @Test
    fun retainingSessionsReleasesInactiveCacheButKeepsSelectedSet() {
        val store = SharedHistoryStore()
        store.hydrateTypedCachedWindow("a", window("a"))
        store.hydrateTypedCachedWindow("b", window("b"))
        assertEquals(listOf("b"), store.retainSessions(listOf("a")))
        assertEquals("[]", recordsJson(store, "b"))
        assertTrue(recordsJson(store, "a").contains("message"))
    }

    @Test
    fun longHistoryExportKeepsLatestFourHundredEventsAndDoesNotMutateHistory() {
        val store = SharedHistoryStore()
        val records = (1..600).map { SessionEvent("s", it, it.toDouble(), GatewayEvent("assistant/message", text = "正文$it")) }
        store.installSnapshot("s", wireJson.encodeToString(records), false, null)
        val json = assertNotNull(store.cachedWindowJson("s", 7, 999))
        val cached = wireJson.decodeFromString<CachedHistoryWindow>(json)
        assertEquals((201..600).toList(), cached.events.map { it.seq })
        assertEquals(999, cached.persistentCursor)
        assertEquals(7, cached.historyFormatVersion)
        assertTrue(cached.hasMore)
        assertEquals(201, cached.nextBeforeSequence)
        assertEquals(600, wireJson.decodeFromString<List<SessionEvent>>(recordsJson(store, "s")).size)
    }

    @Test
    fun byteBudgetTrimsOldWholeEventsAndExportsLatestAfterHugeToolPayload() {
        val large = "正".repeat(1_100_000)
        val records = (1..3).map { SessionEvent("s", it, it.toDouble(), GatewayEvent("assistant/message", text = large)) }
        val metadata = CachedHistoryWindow(sessionId = "s", persistentCursor = 999)
        val json = assertNotNull(ChatCacheWindowPolicy.exportTail(metadata, records))
        assertTrue(json.encodeToByteArray().size <= ChatCacheWindowPolicy.DEFAULT_MAXIMUM_BYTES)
        val cached = wireJson.decodeFromString<CachedHistoryWindow>(json)
        assertEquals(listOf(2, 3), cached.events.map { it.seq })
        assertEquals(2, cached.nextBeforeSequence)
        assertTrue(cached.events.all { it.event.text == large })
        assertEquals(3, records.size)
        val oversizedTool = SessionEvent("s", 4, 4.0, GatewayEvent("tool/result", raw = JsonValue.StringValue("x".repeat(9_000_000))))
        assertNull(ChatCacheWindowPolicy.exportTail(metadata, records + oversizedTool))
        val latest = SessionEvent("s", 5, 5.0, GatewayEvent("assistant/message", text = "最新正文"))
        val recovered = wireJson.decodeFromString<CachedHistoryWindow>(
            assertNotNull(
                ChatCacheWindowPolicy.exportTail(
                    metadata,
                    records + oversizedTool + latest
                )
            )
        )
        assertEquals(listOf(5), recovered.events.map { it.seq })
        assertEquals(999, recovered.persistentCursor)
        assertEquals(5, recovered.nextBeforeSequence)
        assertTrue(recovered.hasMore)
    }

    @Test
    fun boundedTailPreservesExistingRemoteGapAndNeverTruncatesEscapedText() {
        val metadata = CachedHistoryWindow(sessionId = "s", persistentCursor = 50, hasMore = true, nextBeforeSequence = 7)
        val record = SessionEvent("s", 8, 8.0, GatewayEvent("user/message", text = "\"\\\n😀中文"))
        val unchanged = wireJson.decodeFromString<CachedHistoryWindow>(
            assertNotNull(ChatCacheWindowPolicy.exportTail(metadata, listOf(record)))
        )
        assertEquals(record, unchanged.events.single())
        assertEquals(7, unchanged.nextBeforeSequence)
        assertEquals(50, unchanged.persistentCursor)
        assertTrue(unchanged.hasMore)
        assertNull(ChatCacheWindowPolicy.exportTail(metadata, listOf(record), maximumBytes = 64))
    }

    @Test
    fun rawToolMemoryEstimateCountsNestedPayloadAndDuplicateRetainedPipelineCopies() {
        val event = SessionEvent(
            "s",
            1,
            1.0,
            GatewayEvent(
                "tool/result",
                text = "ok",
                arguments = JsonValue.ObjectValue(mapOf("payload" to JsonValue.StringValue("x".repeat(5_000_000))))
            )
        )
        val estimated = ChatCacheMemoryEstimator.estimateHistory(listOf(event))
        assertTrue(estimated > 24L * 1024 * 1024, "短UI文本不能掩盖大tool原始payload")
        var deeplyNested: JsonValue = JsonValue.StringValue("tiny")
        repeat(200) { deeplyNested = JsonValue.ArrayValue(listOf(deeplyNested)) }
        val deep = event.copy(event = GatewayEvent("tool/result", raw = deeplyNested))
        assertTrue(ChatCacheMemoryEstimator.estimateHistory(listOf(deep)) > 24L * 1024 * 1024)
        assertNull(ChatCacheWindowPolicy.exportTail(CachedHistoryWindow(sessionId = "s"), listOf(deep)))
    }

    private fun recordsJson(store: SharedHistoryStore, id: String): String {
        var bootstrap: SharedHistoryBootstrap? = null
        val subscription = store.subscribe(
            SharedMviEventObserver {
                if (it.statePayloadJson != null) bootstrap = wireJson.decodeFromString(it.statePayloadJson)
            }
        )
        subscription.cancel()
        return wireJson.encodeToString(bootstrap?.eventsBySession?.get(id).orEmpty())
    }

    private fun window(id: String, seq: Int = 1) = CachedHistoryWindow(
        sessionId = id,
        events = listOf(
            SessionEvent(id, seq, 1.0, GatewayEvent("assistant/message", text = "message"))
        )
    )
}

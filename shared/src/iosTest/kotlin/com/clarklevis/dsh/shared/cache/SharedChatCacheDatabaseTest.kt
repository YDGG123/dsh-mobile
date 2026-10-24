package com.clarklevis.dsh.shared.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/** 真正运行 Room generated DAO + BundledSQLite + 文件数据库，不使用模拟 DAO。 */
@OptIn(ExperimentalForeignApi::class)
class SharedChatCacheDatabaseTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun removeTestDatabases() {
        for (path in paths) {
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                NSFileManager.defaultManager.removeItemAtPath(path + suffix, null)
            }
        }
    }

    @Test
    fun persistsReopensAndSearchesChineseShortLongEmojiAndSqlLiterals() = runTest {
        val path = path()
        val first = create(path)
        assertTrue(first.save("g1", "s", window("s", listOf("中文内容😀ABC 100%_ 'or 1=1 --")), 1))
        assertTrue(first.save("g2", "s", window("s", listOf("foreign")), 1))
        first.close()
        val reopened = create(path)
        for (query in listOf("中", "中文", "中文内容", "😀ab", "%_", "'or 1=1 --")) {
            val result = searchResults(reopened.search("g1", query, null, 20))
            assertEquals(1, result.size, query)
            assertEquals("s", result.single().jsonObject["sessionId"]!!.jsonPrimitive.content)
        }
        assertEquals(0, searchResults(reopened.search("g2", "中文", null, 20)).size)
        assertNotNull(reopened.load("g1", "s"))
        reopened.close()
    }

    @Test
    fun messageIndexDoesNotExposeToolArgumentsReasoningAndIsDeletedWithWindow() = runTest {
        val cache = create(path())
        val window = CachedHistoryWindow(
            sessionId = "s",
            events = listOf(
                SessionEvent("s", 1, 1.0, GatewayEvent("assistant/message", text = "正文", reasoning = "私有推理")),
                SessionEvent("s", 2, 2.0, GatewayEvent("tool/result", text = "工具原始"))
            )
        )
        assertTrue(cache.save("g", "s", wireJson.encodeToString(window), 1))
        assertEquals(0, searchResults(cache.search("g", "私有推理", null, 20)).size)
        assertEquals(0, searchResults(cache.search("g", "工具原始", null, 20)).size)
        assertEquals(1, searchResults(cache.search("g", "正文", null, 20)).size)
        cache.remove("g", "s", 2)
        assertEquals(0, searchResults(cache.search("g", "正文", null, 20)).size)
        assertEquals(0L, stats(cache)["eventCount"]!!.jsonPrimitive.long)
        cache.close()
    }

    @Test
    fun pagesKeepPersistentCursorAndDoNotPoisonFullMemoryWindow() = runTest {
        val path = path()
        val cache = create(path)
        assertTrue(cache.save("g", "s", window("s", (1..5).map { "正文$it" }, hasMore = true), 1))
        cache.close()
        val reopened = create(path)
        val page = assertNotNull(reopened.loadTypedPage("g", "s", null, 2))
        assertEquals(listOf(4, 5), page.events.map { it.seq })
        assertEquals(4, page.nextBeforeSequence)
        assertEquals(999, page.persistentCursor)
        assertTrue(page.hasMore)
        val older = assertNotNull(reopened.loadTypedPage("g", "s", 4, 2))
        assertEquals(listOf(2, 3), older.events.map { it.seq })
        val oldest = assertNotNull(reopened.loadTypedPage("g", "s", 2, 2))
        assertEquals(listOf(1), oldest.events.map { it.seq })
        assertEquals(0, oldest.nextBeforeSequence)
        assertTrue(oldest.hasMore, "远端尚有缺口不能伪装本地全量")
        val full = assertNotNull(reopened.loadTypedWindow("g", "s"))
        assertEquals(5, full.events.size)
        assertNotNull(reopened.loadTypedWindow("g", "s"))
        assertTrue(stats(reopened)["memoryHits"]!!.jsonPrimitive.long > 0)
        reopened.close()
    }

    @Test
    fun clearScopeEpochStaleWriteAndGenerationProtectNewerContent() = runTest {
        val cache = create(path())
        assertTrue(cache.save("g1", "s", window("s", listOf("old")), 1))
        assertTrue(cache.save("g2", "s", window("s", listOf("foreign")), 1))
        val before = cache.epoch()
        cache.clear("g1")
        assertFalse(cache.saveAtEpoch("g1", "s", window("s", listOf("stale")), 99, before))
        assertNull(cache.load("g1", "s"))
        assertNotNull(cache.load("g2", "s"))
        assertTrue(cache.saveAtEpoch("g1", "s", window("s", listOf("new")), 2, cache.epoch()))
        cache.remove("g1", "s", 4)
        assertFalse(cache.save("g1", "s", window("s", listOf("late")), 3))
        assertNull(cache.load("g1", "s"))
        cache.clear(null)
        assertEquals(0L, stats(cache)["logicalBytes"]!!.jsonPrimitive.long)
        cache.close()
    }

    @Test
    fun indexedLongHighEntropyFallbackStillFindsTail() = runTest {
        val cache = create(path())
        val text = (0..4096).joinToString("") { "${it.toString(16)}中" } + "唯一尾部命中"
        assertTrue(cache.save("g", "s", window("s", listOf(text)), 1))
        assertEquals(1, searchResults(cache.search("g", "唯一尾部命中", null, 20)).size)
        cache.close()
    }

    @Test
    fun lruCascadesIndexAndProtectedCapacityFailureRollsBack() = runTest {
        val measurement = create(path())
        val payload = window("s1", listOf("一二三正文".repeat(40)))
        assertTrue(measurement.save("g", "s1", payload, 1))
        val size = stats(measurement)["logicalBytes"]!!.jsonPrimitive.long
        measurement.close()
        val cache = create(path(), size * 3, size * 2)
        for (id in listOf("s1", "s2", "s3")) assertTrue(cache.save("g", id, window(id, listOf("一二三正文".repeat(40))), 1))
        cache.protect("g", listOf("s1"))
        assertTrue(cache.save("g", "s4", window("s4", listOf("一二三正文".repeat(40))), 1))
        assertNotNull(cache.load("g", "s1"))
        assertNull(cache.load("g", "s2"))
        assertNull(cache.load("g", "s3"))
        assertEquals(2, searchResults(cache.search("g", "一二三", null, 20)).size)
        cache.protect("g", listOf("s1", "s4"))
        assertEquals(0L, cache.trimToBytes(0))
        assertFalse(cache.save("g", "s5", window("s5", listOf("四五六正文".repeat(80))), 1))
        assertNotNull(cache.load("g", "s1"))
        cache.close()
    }

    @Test
    fun concurrentColdReadsSaveRemoveAndClearCannotResurrectOldMemory() = runTest {
        val cache = create(path())
        repeat(20) { round ->
            val id = "s$round"
            assertTrue(cache.save("g", id, window(id, listOf("old".repeat(500))), 1))
            val reads = (1..8).map { async(Dispatchers.Default) { cache.loadTypedWindow("g", id) } }
            cache.remove("g", id, 2)
            reads.awaitAll()
            assertNull(cache.loadTypedWindow("g", id), "remove返回后不得复活")
            val ticket = cache.epoch()
            val writer = async(Dispatchers.Default) { cache.saveAtEpoch("g", id, window(id, listOf("late")), 3, ticket) }
            cache.clear(null)
            writer.await()
            assertNull(cache.loadTypedWindow("g", id), "clear返回后不得复活")
        }
        cache.close()
    }

    @Test
    fun migratesVersionOneRealDatabasePreservingBodyAndRebuildingSearch() = runTest {
        val path = path()
        val payload = wireJson.decodeFromString<CachedHistoryWindow>(window("legacy", listOf("旧版中文正文")))
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL(
                """
                    CREATE TABLE chat_session_cache (gatewayId TEXT NOT NULL,sessionId TEXT NOT NULL,headerJson TEXT NOT NULL,logicalBytes INTEGER NOT NULL,lastAccessAt INTEGER NOT NULL,PRIMARY KEY(gatewayId,sessionId))
                """
            )
            db.execSQL("CREATE INDEX index_chat_session_cache_lastAccessAt ON chat_session_cache(lastAccessAt)")
            db.execSQL(
                """
                    CREATE TABLE chat_cached_event (gatewayId TEXT NOT NULL,sessionId TEXT NOT NULL,seq INTEGER NOT NULL,eventJson TEXT NOT NULL,PRIMARY KEY(gatewayId,sessionId,seq),FOREIGN KEY(gatewayId,sessionId) REFERENCES chat_session_cache(gatewayId,sessionId)
                    ON UPDATE NO ACTION ON DELETE CASCADE)
                """
            )
            db.prepare("INSERT INTO chat_session_cache VALUES(?,?,?,?,?)").use { statement ->
                statement.bindText(1, "g")
                statement.bindText(2, "legacy")
                statement.bindText(3, wireJson.encodeToString(payload.copy(events = emptyList())))
                statement.bindLong(4, 1024)
                statement.bindLong(5, 1)
                statement.step()
            }
            db.prepare("INSERT INTO chat_cached_event VALUES(?,?,?,?)").use { statement ->
                statement.bindText(1, "g")
                statement.bindText(2, "legacy")
                statement.bindLong(3, 1)
                statement.bindText(4, wireJson.encodeToString(payload.events.single()))
                statement.step()
            }
            db.execSQL("PRAGMA user_version=1")
        }
        val cache = create(path)
        assertTrue(assertNotNull(cache.load("g", "legacy")).contains("旧版中文正文"))
        assertEquals(1, searchResults(cache.search("g", "旧版中文", null, 20)).size)
        cache.close()
        val reopened = create(path)
        assertEquals(1, searchResults(reopened.search("g", "中文", null, 20)).size)
        reopened.close()
    }

    @Test
    fun sqliteUsesGramIndexAndMainFileHasBoundedPagesAndTruncatedJournal() = runTest {
        val path = path()
        val maximum = 2L * 1024 * 1024
        val cache = create(path, maximum, maximum / 2)
        repeat(12) { index ->
            val id = "session-$index"
            assertTrue(cache.save("g", id, window(id, listOf("中文快速搜索".repeat(8_000))), 1))
        }
        assertTrue(stats(cache)["logicalBytes"]!!.jsonPrimitive.long <= maximum)
        assertTrue(cache.trimToBytes(0) > 0)
        assertEquals(0L, stats(cache)["logicalBytes"]!!.jsonPrimitive.long)
        cache.close()
        BoundedCacheDriver(maximum).open(path).use { db ->
            fun pragma(name: String): Long = db.prepare("PRAGMA $name").use {
                it.step()
                it.getLong(0)
            }
            val maximumPhysical = maximum + 32L * 1024 * 1024
            assertTrue(pragma("max_page_count") * pragma("page_size") <= maximumPhysical)
            assertTrue(pragma("page_count") * pragma("page_size") <= maximumPhysical)
            db.prepare(
                """
                    EXPLAIN QUERY PLAN SELECT t.*
                    FROM chat_search_gram g
                    JOIN chat_search_text t ON t.gatewayId=g.gatewayId
                    AND t.sessionId=g.sessionId
                    AND t.seq=g.seq
                    WHERE g.gatewayId=?
                    AND g.gram=?
                    AND instr(t.normalizedText,?)>0
                """
            ).use { statement ->
                statement.bindText(1, "g")
                statement.bindText(2, "中文快")
                statement.bindText(3, "中文快速")
                val details = mutableListOf<String>()
                while (statement.step()) details += statement.getText(3)
                assertTrue(details.any { "SEARCH g" in it && "gatewayId=? AND gram=?" in it }, details.joinToString())
            }
        }
        val journal = NSFileManager.defaultManager.attributesOfItemAtPath(path + "-journal", null)
        assertTrue(journal == null || (journal["NSFileSize"] as? NSNumber)?.longLongValue == 0L)
    }

    @Test
    fun readThenImmediateWriteKeepsMostRecentlyReadSession() = runTest {
        val measurement = create(path())
        assertTrue(measurement.save("g", "s1", window("s1", listOf("LRU正文".repeat(40))), 1))
        val size = stats(measurement)["logicalBytes"]!!.jsonPrimitive.long
        measurement.close()
        repeat(30) {
            val cache = create(path(), size * 3, size * 2)
            for (id in listOf("s1", "s2", "s3")) assertTrue(cache.save("g", id, window(id, listOf("LRU正文".repeat(40))), 1))
            assertNotNull(cache.loadTypedWindow("g", "s1"))
            assertTrue(cache.save("g", "s4", window("s4", listOf("LRU正文".repeat(40))), 1))
            assertNotNull(cache.loadTypedWindow("g", "s1"), "热读必须先参与此次磁盘LRU排序")
            assertNull(cache.loadTypedWindow("g", "s2"))
            cache.close()
        }
    }

    @Test
    fun cancelledColdLoadDoesNotLeaveSingleFlightHangingAndClosedCacheReturnsMiss() = runTest {
        val path = path()
        val first = create(path)
        assertTrue(first.save("g", "s", window("s", (1..200).map { "正文$it".repeat(100) }), 1))
        first.close()
        val cache = create(path)
        val cancelled = async(Dispatchers.Default) { cache.loadTypedWindow("g", "s") }
        yield()
        cancelled.cancelAndJoin()
        withContext(Dispatchers.Default) { withTimeout(5_000) { assertNotNull(cache.loadTypedWindow("g", "s")) } }
        cache.close()
        assertNull(cache.loadTypedWindow("g", "s"))
        assertNull(cache.loadTypedPage("g", "s", null, 20))
    }

    @Test
    fun tombstoneLimitRollsEpochWithoutAllowingOldQueuedWrite() = runTest {
        val cache = create(path())
        val ticket = cache.epoch()
        repeat(4_100) { cache.remove("g", "removed-$it", 1) }
        assertTrue(cache.epoch() > ticket)
        assertFalse(cache.saveAtEpoch("g", "removed-0", window("removed-0", listOf("旧写")), 0, ticket))
        assertTrue(cache.saveAtEpoch("g", "fresh", window("fresh", listOf("新写")), 2, cache.epoch()))
        cache.close()
    }

    @Test
    fun diskWriteAdmissionRejectsLowSpaceKeepsOldWindowAndRecovers() = runTest {
        var free: Long? = 512L * 1024 * 1024
        val path = path()
        val cache = Room.databaseBuilder<ChatCacheDatabase>(name = path).buildCache(96L * 1024 * 1024, 72L * 1024 * 1024) { free }
        assertTrue(cache.save("g", "s", window("s", listOf("旧窗口")), 1))
        free = SharedChatHistoryCache.MINIMUM_FREE_RESERVE_BYTES
        assertFalse(cache.save("g", "s", window("s", listOf("低空间新窗口")), 2))
        cache.trimMemory()
        assertTrue(assertNotNull(cache.load("g", "s")).contains("旧窗口"))
        free = null
        assertFalse(cache.save("g", "s", window("s", listOf("不可用空间")), 3))
        free = 512L * 1024 * 1024
        assertTrue(cache.save("g", "s", window("s", listOf("恢复空间新窗口")), 4))
        cache.trimMemory()
        assertTrue(assertNotNull(cache.load("g", "s")).contains("恢复空间新窗口"))
        assertEquals(2L, stats(cache)["writeAdmissionRefusals"]!!.jsonPrimitive.long)
        assertEquals(SharedChatHistoryCache.MINIMUM_FREE_RESERVE_BYTES, stats(cache)["minimumFreeReserveBytes"]!!.jsonPrimitive.long)
        cache.close()
    }

    @Test
    fun memoryTrimKeepsDiskEpochAndPendingWriteTicketAndSeparatesPageMisses() = runTest {
        val cache = create(path())
        assertTrue(cache.save("g", "s", window("s", listOf("磁盘正文")), 1))
        assertEquals(1L, stats(cache)["memorySessions"]!!.jsonPrimitive.long)
        val ticket = cache.epoch()
        cache.trimMemory()
        assertEquals(ticket, cache.epoch())
        assertEquals(0L, stats(cache)["memorySessions"]!!.jsonPrimitive.long)
        assertEquals(1L, stats(cache)["sessionCount"]!!.jsonPrimitive.long)
        assertNotNull(cache.loadTypedPage("g", "s", null, 20))
        assertNull(cache.loadTypedPage("g", "missing", null, 20))
        assertEquals(1L, stats(cache)["diskHits"]!!.jsonPrimitive.long)
        assertEquals(1L, stats(cache)["misses"]!!.jsonPrimitive.long)
        assertTrue(cache.saveAtEpoch("g", "s", window("s", listOf("未丢待写")), 2, ticket))
        cache.close()
    }

    @Test
    fun oversizedFullHistoryExportsBoundedTailAndRealRoomStillCachesNewFinalMessage() = runTest {
        val cache = create(path())
        val metadata = CachedHistoryWindow(sessionId = "s", persistentCursor = 999)
        val records = (1..3).map {
            SessionEvent("s", it, it.toDouble(), GatewayEvent("tool/result", raw = JsonValue.StringValue("x".repeat(3_000_000))))
        }
        val latest = SessionEvent("s", 4, 4.0, GatewayEvent("assistant/message", text = "最近终稿"))
        val tail = assertNotNull(ChatCacheWindowPolicy.exportTail(metadata, records + latest))
        assertTrue(cache.save("g", "s", tail, 1))
        cache.trimMemory()
        val loaded = assertNotNull(cache.loadTypedWindow("g", "s"))
        assertEquals("最近终稿", loaded.events.last().event.text)
        assertTrue(loaded.hasMore)
        assertEquals(2, loaded.nextBeforeSequence)
        assertEquals(999, loaded.persistentCursor)
        cache.close()
    }

    @Test
    fun heatPersistsAcrossReopenAndWindowReplacementButExplicitClearDeletesIt() = runTest {
        val path = path()
        val cache = create(path)
        repeat(4) { cache.recordSessionOpenAt("g1", "hot", 1000.0) }
        cache.recordSessionOpenAt("g2", "foreign", 1000.0)
        assertTrue(cache.save("g1", "hot", window("hot", listOf("first")), 1))
        assertTrue(cache.save("g1", "hot", window("hot", listOf("second")), 2))
        cache.trimToBytes(0)
        cache.close()
        val reopened = create(path)
        val candidates = """[{"id":"hot"},{"id":"a","lastActivityEpochSeconds":1000}]"""
        assertEquals("hot", planned(reopened.planPrefetchAt("g1", candidates, null, 1000.0)).first())
        assertEquals("a", planned(reopened.planPrefetchAt("g2", candidates, null, 1000.0)).first())
        reopened.clear("g1")
        assertEquals("a", planned(reopened.planPrefetchAt("g1", candidates, null, 1000.0)).first())
        assertEquals(
            "foreign",
            planned(
                reopened.planPrefetchAt("g2", """[{"id":"foreign"},{"id":"a","lastActivityEpochSeconds":1000}]""", null, 1000.0)
            ).first()
        )
        reopened.clear(null)
        assertEquals(
            "a",
            planned(
                reopened.planPrefetchAt("g2", """[{"id":"foreign"},{"id":"a","lastActivityEpochSeconds":1000}]""", null, 1000.0)
            ).first()
        )
        reopened.close()
    }

    @Test
    fun heatMetadataIsGloballyBoundedAndExplicitRemoveDeletesUserHeat() = runTest {
        val path = path()
        val cache = create(path)
        // 真 Room 批量预置边界，真实 recordOpen 事务负责最终裁剪。
        cache.statistics() // Room 延迟开库，先触发建表。
        cache.close()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("BEGIN")
            for (index in 0 until ChatPrefetchPolicy.MAXIMUM_HEAT_RECORDS) {
                db.execSQL("INSERT INTO chat_session_heat VALUES('g','s$index',$index,1)")
            }
            db.execSQL("COMMIT")
        }
        val reopened = create(path)
        reopened.recordSessionOpenAt("other", "new", 10000.0)
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("SELECT COUNT(*) FROM chat_session_heat").use { statement ->
                assertTrue(statement.step())
                assertEquals(ChatPrefetchPolicy.MAXIMUM_HEAT_RECORDS.toLong(), statement.getLong(0))
            }
            db.prepare("SELECT COUNT(*) FROM chat_session_heat WHERE sessionId='s0'").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
        reopened.remove("other", "new", 1)
        assertEquals(listOf("a", "new"), planned(reopened.planPrefetchAt("other", """[{"id":"new"},{"id":"a"}]""", null, 10000.0)))
        reopened.close()
    }

    @Test
    fun prefetchStoresColdWindowWithoutPollutingL1OrUserHeatOrNormalGeneration() = runTest {
        val cache = create(path())
        val ticket = cache.prefetchTicket("g", "s")
        assertTrue(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s", 2), ticket))
        assertEquals(0L, stats(cache)["memorySessions"]!!.jsonPrimitive.long)
        assertEquals(listOf("a", "s"), planned(cache.planPrefetchAt("g", """[{"id":"s"},{"id":"a"}]""", null, 1000.0)))
        val loaded = assertNotNull(cache.loadTypedWindow("g", "s"))
        assertEquals(2, loaded.persistentCursor)
        assertEquals(4, loaded.historyFormatVersion)
        assertEquals(2, loaded.nextBeforeSequence)
        assertTrue(loaded.hasMore)
        assertTrue(cache.save("g", "s", window("s", listOf("foreground")), 1), "后台不得递增前台generation")
        cache.close()
    }

    @Test
    fun prefetchTicketsRejectClearRemoveCancellationCrossKeyAndNewForegroundWrite() = runTest {
        val cache = create(path())
        val first = cache.prefetchTicket("g", "s")
        assertFalse(cache.acceptPrefetchHistory("other", "s", prefetchFrame("s"), first))
        assertFalse(cache.acceptPrefetchHistory("g", "other", prefetchFrame("other"), first))
        cache.clear("g")
        assertFalse(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s"), first))
        val second = cache.prefetchTicket("g", "s")
        cache.cancelPrefetch("g", "s", second)
        assertFalse(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s"), second))
        val removed = cache.prefetchTicket("g", "s")
        cache.remove("g", "s", 1)
        assertFalse(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s"), removed))
        val changed = cache.prefetchTicket("g", "s")
        assertTrue(cache.save("g", "s", window("s", listOf("foreground")), 2))
        assertFalse(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s", 10000), changed))
        assertTrue(assertNotNull(cache.load("g", "s")).contains("foreground"))
        cache.close()
    }

    @Test
    fun prefetchRejectsMalformedOversizedOldPagesAndPreservesBroaderSameCursorWindow() = runTest {
        val cache = create(path())
        for (payload in listOf(
            "not json",
            prefetchFrame("wrong"),
            prefetchFrame("s").replace("history", "event"),
            prefetchFrame("s").replace("\"historyFormatVersion\":4", "\"historyFormatVersion\":999"),
            prefetchFrame("s").dropLast(1) + ",\"beforeSeq\":5}",
            prefetchFrame("s") + " ".repeat(256 * 1024)
        )) {
            assertFalse(cache.acceptPrefetchHistory("g", "s", payload, cache.prefetchTicket("g", "s")), payload.take(100))
        }
        val old = wireJson.decodeFromString<CachedHistoryWindow>(window("s", (1..5).map { "body$it" })).copy(historyFormatVersion = 4)
        assertTrue(cache.save("g", "s", wireJson.encodeToString(old), 1))
        assertFalse(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s", 999), cache.prefetchTicket("g", "s")))
        assertEquals(5, assertNotNull(cache.loadTypedWindow("g", "s")).events.size)
        cache.close()
    }

    @Test
    fun prefetchDoesNotCompareCursorsAcrossDifferentHistoryFormats() = runTest {
        val cache = create(path())
        val old = CachedHistoryWindow(
            sessionId = "s",
            historyFormatVersion = 3,
            persistentCursor = 100,
            events = listOf(SessionEvent("s", 100, 1000.0, GatewayEvent("assistant/message", text = "old format")))
        )
        assertTrue(cache.save("g", "s", wireJson.encodeToString(old), 1))
        assertTrue(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s", 1), cache.prefetchTicket("g", "s")))
        val latest = assertNotNull(cache.loadTypedWindow("g", "s"))
        assertEquals(4, latest.historyFormatVersion)
        assertEquals(1, latest.persistentCursor)
        cache.close()
    }

    @Test
    fun prefetchRejectsMoreThanEightyEventsAndCannotReplayAnAcceptedTicket() = runTest {
        val cache = create(path())
        val event = """{"type":"assistant/message","seq":1,"time":1000,"data":{"message":{"content":[]}}}"""
        val tooMany = """{"kind":"history","sessionId":"s","events":[${List(81) { event }.joinToString(",")}]}"""
        assertFalse(cache.acceptPrefetchHistory("g", "s", tooMany, cache.prefetchTicket("g", "s")))
        val ticket = cache.prefetchTicket("g", "s")
        assertTrue(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s"), ticket))
        assertFalse(cache.acceptPrefetchHistory("g", "s", prefetchFrame("s", 2), ticket))
        assertEquals(1, assertNotNull(cache.loadTypedWindow("g", "s")).persistentCursor)
        cache.close()
    }

    @Test
    fun migratesVersionTwoDatabaseAndPreservesItsCachedBody() = runTest {
        val path = path()
        val cache = create(path)
        assertTrue(cache.save("g", "s", window("s", listOf("v2正文")), 1))
        cache.close()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DROP TABLE chat_session_heat")
            db.execSQL("DROP TABLE room_master_table")
            db.execSQL("PRAGMA user_version=2")
        }
        val migrated = create(path)
        assertTrue(assertNotNull(migrated.load("g", "s")).contains("v2正文"))
        migrated.recordSessionOpenAt("g", "s", 1000.0)
        assertEquals("s", planned(migrated.planPrefetchAt("g", """[{"id":"s"},{"id":"a"}]""", null, 1000.0)).first())
        migrated.close()
    }

    @Test
    fun offlineCatalogPersistsAcrossReopenAndBodyEvictionButIsGatewayScoped() = runTest {
        val path = path()
        val cache = create(path)
        val catalog = offlineCatalog("s")
        assertTrue(cache.saveOfflineCatalog("g", catalog, cache.offlineCatalogTicket("g")))
        assertTrue(cache.save("g", "s", window("s", listOf("正文")), 1))
        cache.trimToBytes(0)
        assertNotNull(cache.loadOfflineCatalog("g"))
        assertNull(cache.loadOfflineCatalog("other"))
        assertNull(cache.load("g", "s"))
        cache.close()
        val reopened = create(path)
        assertEquals(catalog, reopened.loadOfflineCatalog("g"))
        reopened.close()
    }

    @Test
    fun catalogReplaceClearAndForgetRejectOldTicketsWithoutResurrectingBody() = runTest {
        val cache = create(path())
        val first = cache.offlineCatalogTicket("g")
        val second = cache.offlineCatalogTicket("g")
        assertFalse(cache.saveOfflineCatalog("g", offlineCatalog("old"), first))
        assertTrue(cache.saveOfflineCatalog("g", offlineCatalog("new"), second))
        val beforeClear = cache.offlineCatalogTicket("g")
        cache.clear("g")
        assertFalse(cache.saveOfflineCatalog("g", offlineCatalog("stale"), beforeClear))
        assertEquals(offlineCatalog("new"), cache.loadOfflineCatalog("g"))
        val beforeForget = cache.offlineCatalogTicket("g")
        cache.forgetOfflineCatalog("g")
        assertFalse(cache.saveOfflineCatalog("g", offlineCatalog("stale"), beforeForget))
        assertNull(cache.loadOfflineCatalog("g"))
        val empty = assertNotNull(com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec().create("{}", "[]", null))
        assertTrue(cache.saveOfflineCatalog("g", empty, cache.offlineCatalogTicket("g")))
        assertEquals(empty, cache.loadOfflineCatalog("g"))
        assertNull(cache.load("g", "new"))
        cache.close()
    }

    @Test
    fun catalogGlobalCountIsBoundedAndRecentReadsPreserveDirectory() = runTest {
        val cache = create(path())
        for (index in 1..64) assertTrue(cache.saveOfflineCatalog("g$index", offlineCatalog("s"), cache.offlineCatalogTicket("g$index")))
        assertNotNull(cache.loadOfflineCatalog("g1"))
        assertTrue(cache.saveOfflineCatalog("g65", offlineCatalog("s"), cache.offlineCatalogTicket("g65")))
        assertNotNull(cache.loadOfflineCatalog("g1"))
        assertNull(cache.loadOfflineCatalog("g2"))
        assertNotNull(cache.loadOfflineCatalog("g65"))
        cache.close()
    }

    @Test
    fun invalidOrLowSpaceCatalogWritePreservesPreviousCatalog() = runTest {
        val path = path()
        val cache = create(path)
        assertTrue(cache.saveOfflineCatalog("g", offlineCatalog("old"), cache.offlineCatalogTicket("g")))
        assertFalse(cache.saveOfflineCatalog("g", "{}", cache.offlineCatalogTicket("g")))
        assertFalse(cache.saveOfflineCatalog("g", "x".repeat(1024 * 1024 + 1), cache.offlineCatalogTicket("g")))
        cache.close()
        val low = Room.databaseBuilder<ChatCacheDatabase>(name = path).buildCache(96L * 1024 * 1024, 72L * 1024 * 1024) { 0L }
        assertFalse(low.saveOfflineCatalog("g", offlineCatalog("new"), low.offlineCatalogTicket("g")))
        assertEquals(offlineCatalog("old"), low.loadOfflineCatalog("g"))
        low.close()
    }

    @Test
    fun versionThreeMigrationRetainsBodySearchAndUserHeat() = runTest {
        val path = path()
        val cache = create(path)
        assertTrue(cache.save("g", "s", window("s", listOf("保留正文")), 1))
        cache.recordSessionOpenAt("g", "s", 1000.0)
        cache.close()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DROP TABLE chat_offline_catalog")
            db.execSQL("DROP TABLE room_master_table")
            db.execSQL("PRAGMA user_version=3")
        }
        val migrated = create(path)
        assertTrue(assertNotNull(migrated.load("g", "s")).contains("保留正文"))
        assertEquals(1, searchResults(migrated.search("g", "保留正文", null, 20)).size)
        assertEquals("s", planned(migrated.planPrefetchAt("g", """[{"id":"s"},{"id":"a"}]""", null, 1000.0)).first())
        assertTrue(migrated.saveOfflineCatalog("g", offlineCatalog("s"), migrated.offlineCatalogTicket("g")))
        assertNotNull(migrated.loadOfflineCatalog("g"))
        migrated.close()
    }

    @Test
    fun concurrentCatalogSaveClearAndForgetCannotReviveForgottenNavigation() = runTest {
        val cache = create(path())
        repeat(20) { index ->
            val gateway = "g$index"
            val ticket = cache.offlineCatalogTicket(gateway)
            val writes = (1..4).map {
                async(Dispatchers.Default) { cache.saveOfflineCatalog(gateway, offlineCatalog("s"), ticket) }
            }
            cache.clear(gateway)
            cache.forgetOfflineCatalog(gateway)
            writes.awaitAll()
            assertNull(cache.loadOfflineCatalog(gateway))
            assertFalse(cache.saveOfflineCatalog(gateway, offlineCatalog("late"), ticket))
        }
        cache.close()
    }

    @Test
    fun catalogByteBudgetEvictsMetadataOnlyAndRetainsMostRecentDirectory() = runTest {
        val path = path()
        val cache = create(path)
        val title = "字".repeat(2400)
        val sessions = (1..100).joinToString(",") { index ->
            """{"id":"s$index","title":"$title","lastActivityEpochSeconds":1,"isRunning":false,"hasUnread":false}"""
        }
        val large =
            assertNotNull(com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec().create("""{"sessions":[$sessions]}""", "[]", null))
        assertTrue(large.encodeToByteArray().size in 700_000..1_048_576)
        for (index in 1..12) assertTrue(cache.saveOfflineCatalog("g$index", large, cache.offlineCatalogTicket("g$index")))
        assertNull(cache.loadOfflineCatalog("g1"))
        assertNotNull(cache.loadOfflineCatalog("g12"))
        cache.close()
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("SELECT SUM(logicalBytes),COUNT(*) FROM chat_offline_catalog").use { statement ->
                assertTrue(statement.step())
                assertTrue(statement.getLong(0) <= 8L * 1024 * 1024)
                assertTrue(statement.getLong(1) <= 64)
            }
        }
    }

    private fun offlineCatalog(id: String): String = assertNotNull(
        com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec().create(
            """{"sessions":[{"id":"$id","title":"目录","lastActivityEpochSeconds":1,"isRunning":false,"hasUnread":false}],"selectedSessionId":"$id"}""",
            "[]",
            null
        )
    )

    private fun planned(value: String) = wireJson.decodeFromString<List<String>>(value)
    private fun prefetchFrame(id: String, seq: Int = 1) =
        """{"kind":"history","sessionId":"$id","historyFormatVersion":4,"cursor":$seq,"hasMore":true,"nextBeforeSeq":$seq,"events":[{"type":"assistant/message","seq":$seq,"time":1000,"data":{"message":{"content":[{"type":"text","text":"prefetched"}]}}}]}"""

    private fun create(path: String, maximum: Long = 96L * 1024 * 1024, target: Long = 72L * 1024 * 1024) =
        Room.databaseBuilder<ChatCacheDatabase>(name = path).buildCache(maximum, target)

    private fun path(): String = (NSTemporaryDirectory() + "dsh-shared-cache-${NSUUID().UUIDString}.db").also(paths::add)
    private suspend fun stats(cache: SharedChatHistoryCache) = wireJson.decodeFromString<JsonObject>(cache.statistics())
    private fun searchResults(json: String) = wireJson.parseToJsonElement(json).jsonObject["results"]!!.jsonArray
    private fun window(id: String, texts: List<String>, hasMore: Boolean = false): String = wireJson.encodeToString(
        CachedHistoryWindow(
            sessionId = id,
            persistentCursor = 999,
            hasMore = hasMore,
            nextBeforeSequence = if (hasMore) 0 else null,
            events = texts.mapIndexed { index, text ->
                SessionEvent(id, index + 1, (index + 1).toDouble(), GatewayEvent("assistant/message", text = text))
            }
        )
    )
}
